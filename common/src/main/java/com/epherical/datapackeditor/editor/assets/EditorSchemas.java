package com.epherical.datapackeditor.editor.assets;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.epherical.datapackeditor.impl.CodecJsonSchemaImpl;
import com.epherical.datapackeditor.impl.DataPackRegistrationImpl;
import com.epherical.datapackeditor.impl.RegistrySchemaCache;
import com.epherical.datapackeditor.impl.schemas.RecipeSchemaBuilder;
import com.epherical.datapackeditor.impl.schemas.TagSchemaBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.epherical.datapackeditor.editor.http.HttpRoutes;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;

import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class EditorSchemas implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private final MinecraftServer server;
    private Generation current;
    private Object registryGeneration;
    private String schemaOrigin;
    private boolean closed;

    public EditorSchemas(MinecraftServer server) {
        this.server = server;
    }

    private synchronized Generation generation(String origin) {
        if (closed) throw new CancellationException("Editor schemas stopped");
        Object stamp = server.reloadableRegistries().get();
        if (current == null || registryGeneration != stamp || !origin.equals(schemaOrigin)) {
            if (current != null) current.close();
            registryGeneration = stamp;
            schemaOrigin = origin;
            current = new Generation(server.registryAccess(), origin);
        }
        return current;
    }

    public void registerRoutes(HttpRoutes routes) {
        routes.before("/api/schema/*", context -> context.header(DatapackEditorConstants.HTTP_CACHE_CONTROL, DatapackEditorConstants.HTTP_NO_STORE));
        routes.exception(CancellationException.class, (error, context) -> context.status(503)
                .contentType(DatapackEditorConstants.HTTP_JSON)
                .result(GSON.toJson(Map.of(DatapackEditorConstants.JSON_ERROR, error.getMessage() == null ? DatapackEditorConstants.MESSAGE_SCHEMA_GENERATION_CANCELLED : error.getMessage()))));
        routes.exception(RejectedExecutionException.class, (error, context) -> context.status(DatapackEditorConstants.HTTP_TOO_MANY_REQUESTS)
                .header(DatapackEditorConstants.HTTP_RETRY_AFTER, DatapackEditorConstants.HTTP_RETRY_SECONDS).contentType(DatapackEditorConstants.HTTP_JSON)
                .result(GSON.toJson(Map.of(DatapackEditorConstants.JSON_ERROR, "Schema compiler is busy"))));
        routes.get("/api/schema/catalog", context -> {
            Generation owner = generation(context.origin());
            JsonArray catalog = new JsonArray();
            DataPackRegistrationImpl.catalog().forEach((id, entry) -> {
                entry.addProperty(DatapackEditorConstants.JSON_URI, owner.uri(DatapackEditorConstants.JSON_DATAPACK, id));
                catalog.add(entry);
            });
            server.registryAccess().registries().map(entry -> Registries.tagsDirPath(entry.key())).distinct().sorted().forEach(directory -> {
                JsonObject entry = new JsonObject();
                entry.addProperty(DatapackEditorConstants.JSON_URI, owner.uri(DatapackEditorConstants.JSON_TAGS, directory));
                JsonArray matches = new JsonArray();
                matches.add(DatapackEditorConstants.PATH_DATA_FILE_GLOB_PREFIX + directory + DatapackEditorConstants.PATH_JSON_FILE_GLOB);
                matches.add(DatapackEditorConstants.PATH_DATA_FILE_GLOB_PREFIX + directory + DatapackEditorConstants.PATH_RECURSIVE_JSON_FILE_GLOB);
                entry.add(DatapackEditorConstants.JSON_FILE_MATCH, matches);
                catalog.add(entry);
            });
            JsonObject recipes = new JsonObject();
            recipes.addProperty(DatapackEditorConstants.JSON_URI, owner.uri(DatapackEditorConstants.JSON_RECIPES, ""));
            JsonArray matches = new JsonArray();
            for (String directory : new String[]{DatapackEditorConstants.JSON_RECIPE, DatapackEditorConstants.JSON_RECIPES}) {
                matches.add(DatapackEditorConstants.PATH_DATA_FILE_GLOB_PREFIX + directory + DatapackEditorConstants.PATH_JSON_FILE_GLOB);
                matches.add(DatapackEditorConstants.PATH_DATA_FILE_GLOB_PREFIX + directory + DatapackEditorConstants.PATH_RECURSIVE_JSON_FILE_GLOB);
            }
            recipes.add(DatapackEditorConstants.JSON_FILE_MATCH, matches);
            catalog.add(recipes);
            JsonObject result = new JsonObject();
            result.add(DatapackEditorConstants.JSON_SCHEMAS, catalog);
            context.contentType(DatapackEditorConstants.HTTP_JSON).result(result.toString());
        });
        routes.get("/api/schema/family", context -> {
            Generation owner = generation(context.origin());
            owner.validate(context.queryParam(DatapackEditorConstants.JSON_GENERATION));
            String kind = context.queryParam("kind");
            String resourceId = context.queryParam(DatapackEditorConstants.JSON_DIRECTORY);
            if (resourceId == null) resourceId = "";
            String selected = resourceId;
            String key = kind + ":" + selected;
            String response = owner.request(key, () -> switch (kind == null ? "" : kind) {
                case DatapackEditorConstants.JSON_RECIPES -> RecipeSchemaBuilder.buildSchema(server);
                case DatapackEditorConstants.JSON_DATAPACK -> onlySchema(DataPackRegistrationImpl.buildResponse(server.registryAccess(), selected));
                case DatapackEditorConstants.JSON_TAGS -> onlySchema(TagSchemaBuilder.buildResponse(server.registryAccess(), selected, owner.registries));
                default -> throw new IllegalArgumentException("Unknown schema family");
            });
            context.contentType(DatapackEditorConstants.HTTP_JSON_SCHEMA).result(response);
        });
        routes.get("/api/schema/registry", context -> {
            Generation owner = generation(context.origin());
            owner.validate(context.queryParam(DatapackEditorConstants.JSON_GENERATION));
            String name = context.queryParam(DatapackEditorConstants.JSON_REGISTRY);
            boolean tags = Boolean.parseBoolean(context.queryParam(DatapackEditorConstants.JSON_TAGS));
            if (name == null) throw new IllegalArgumentException("Missing registry");
            String response = owner.request("registry:" + name + ":" + tags, () -> owner.registries.document(
                    ResourceKey.createRegistryKey(ResourceLocation.parse(name)), tags));
            context.contentType(DatapackEditorConstants.HTTP_JSON_SCHEMA).result(response);
        });
    }

    private static JsonElement onlySchema(JsonObject response) {
        JsonArray schemas = response.getAsJsonArray(DatapackEditorConstants.JSON_SCHEMAS);
        if (schemas.isEmpty()) throw new IllegalArgumentException("Unknown schema resource");
        return schemas.get(0).getAsJsonObject().get(DatapackEditorConstants.JSON_SCHEMA);
    }

    public synchronized void close() {
        closed = true;
        if (current != null) current.close();
        current = null;
        registryGeneration = null;
        schemaOrigin = null;
    }

    private static class Generation implements AutoCloseable {
        private final String id = UUID.randomUUID().toString();
        private final RegistrySchemaCache registries;
        private final String origin;
        private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), Thread.ofPlatform().daemon().name("datapack-editor-schema").factory());
        private final Map<String, Future<String>> pending = new LinkedHashMap<>();
        private final Map<String, String> cache = new LinkedHashMap<>(16, 0.75f, true);
        private int cacheBytes;
        private boolean stopped;

        public Generation(HolderLookup.Provider registries, String origin) {
            this.origin = origin;
            this.registries = new RegistrySchemaCache(registries, origin + "/api/schema/registry?generation=" + id);
        }

        private String uri(String kind, String resourceId) {
            return origin + "/api/schema/family?generation=" + id + "&kind=" + kind
                    + "&directory=" + URLEncoder.encode(resourceId, StandardCharsets.UTF_8);
        }

        private void validate(String requested) {
            if (!id.equals(requested))
                throw new CancellationException("Resources reloaded; refresh schema catalog");
        }

        private String request(String key, Supplier<JsonElement> build) {
            Future<String> job;
            synchronized (this) {
                if (stopped) throw new CancellationException(DatapackEditorConstants.MESSAGE_SCHEMA_SESSION_STOPPED);
                String cached = cache.get(key);
                if (cached != null) return cached;
                job = pending.computeIfAbsent(key, ignored -> executor.submit(() -> CodecJsonSchemaImpl.withRegistrySchemas(registries, () -> {
                    LimitedWriter writer = new LimitedWriter();
                    GSON.toJson(build.get(), writer);
                    String result = writer.toString();
                    synchronized (this) {
                        if (stopped) throw new CancellationException(DatapackEditorConstants.MESSAGE_SCHEMA_SESSION_STOPPED);
                        cache.put(key, result);
                        cacheBytes += result.length() * DatapackEditorConstants.UTF16_BYTES_PER_CHAR;
                        while (cacheBytes > (16 * DatapackEditorConstants.MEBIBYTE) || cache.size() > 64) {
                            String oldest = cache.keySet().iterator().next();
                            cacheBytes -= cache.remove(oldest).length() * DatapackEditorConstants.UTF16_BYTES_PER_CHAR;
                        }
                    }
                    return result;
                })));
            }
            try {
                return job.get(20, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                job.cancel(true);
                throw new CancellationException("Schema request interrupted");
            } catch (TimeoutException error) {
                job.cancel(true);
                throw new CancellationException("Schema request timed out");
            } catch (ExecutionException error) {
                if (error.getCause() instanceof CancellationException cancelled) {
                    CancellationException failure = new CancellationException("Schema " + key + ": " + cancelled.getMessage());
                    failure.initCause(cancelled);
                    throw failure;
                }
                if (error.getCause() instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Schema compilation failed: " + key, error.getCause());
            } finally {
                synchronized (this) { if (job.isDone()) pending.remove(key, job); }
            }
        }

        public synchronized void close() {
            stopped = true;
            new ArrayList<>(pending.values()).forEach(job -> job.cancel(true));
            executor.shutdownNow();
            pending.clear();
            cache.clear();
            cacheBytes = 0;
        }
    }

    private static class LimitedWriter extends Writer {
        private final StringBuilder result = new StringBuilder();

        public LimitedWriter() {}

        public void write(char[] chars, int offset, int count) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Schema serialization cancelled");
            if ((long) result.length() + count > 4_194_304)
                throw new CancellationException("Schema response exceeds 4 Mi characters");
            result.append(chars, offset, count);
        }

        public void flush() {}

        public void close() {}

        public String toString() { return result.toString(); }
    }
}
