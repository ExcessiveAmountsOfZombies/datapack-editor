package com.epherical.epherolib.editor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class EditorAssets {
    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ASSETS = "assets/";
    private static final int MAX_PATH_LENGTH = 512;
    private static final List<String> BUNDLE_DIRECTORIES = List.of(
            "items", "models", "blockstates", "textures", "atlases", "equipment", "particles");
    public static final String SOURCE = "source";

    public static final String CONTENT_TYPE = "application/json";
    public static final String ERROR = "error";

    public static final String ASSETS_PATHS = "assets/[a-z0-9_.-]+(?:/[a-z0-9_./-]+)?";

    public static final String FORWARD_SLASH = "/";
    public static final char FORWARD_SLASH_CHAR = '/';

    private final List<Source> sources;
    private final String manifest;
    private final CompletableFuture<byte[]> bundle;

    public EditorAssets(MinecraftServer server) {
        List<Source> orderedSources = new ArrayList<>();
        List<PackResources> loadedPacks = server.getResourceManager().listPacks().toList();
        PackResources vanilla = null;
        for (int index = loadedPacks.size() - 1; index >= 0; index--) {
            PackResources pack = loadedPacks.get(index);
            if ("vanilla".equals(pack.packId())) {
                vanilla = pack;
            } else {
                orderedSources.add(new Source(pack));
            }
        }

        Path modsDirectory = server.getServerDirectory().resolve("mods");
        if (Files.isDirectory(modsDirectory)) {
            try (var files = Files.list(modsDirectory)) {
                files.filter(path -> Files.isRegularFile(path)
                                && path.getFileName().toString().toLowerCase().endsWith(".jar"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .forEach(path -> {
                            try {
                                orderedSources.add(new Source(path));
                            } catch (IOException exception) {
                                LOGGER.warn("Could not index editor assets from {}", path, exception);
                            }
                        });
            } catch (IOException exception) {
                LOGGER.warn("Could not list mod jars for editor assets in {}", modsDirectory, exception);
            }
        }
        if (vanilla != null) {
            orderedSources.add(new Source(vanilla));
        }
        sources = orderedSources;
        List<Map<String, Object>> layers = new ArrayList<>();
        for (int index = 0; index < sources.size(); index++) {
            layers.add(Map.of("id", index, "name", sources.get(index).name));
        }
        manifest = GSON.toJson(Map.of("version", SharedConstants.getCurrentVersion().name(), "sources", layers));
        bundle = CompletableFuture.supplyAsync(this::buildBundle);
    }

    public void registerRoutes(JavalinConfig config) {
        config.routes.get("/api/assets", context -> context
                .header("Cache-Control", "no-store")
                .contentType(CONTENT_TYPE)
                .result(manifest));
        config.routes.get("/api/asset-file", this::readFile);
        config.routes.get("/api/asset-resolved", this::readResolvedFile);
        config.routes.get("/api/asset-list", this::listDirectory);
        config.routes.get("/api/asset-list-resolved", this::listResolvedDirectory);
        config.routes.get("/api/asset-bundle", this::readBundle);
    }

    private void readBundle(Context context) {
        byte[] bytes = bundle.join();
        if (bytes.length == 0) {
            error(context, HttpStatus.SERVICE_UNAVAILABLE, "Asset bundle unavailable");
            return;
        }
        context.header("Cache-Control", "private, max-age=300")
                .contentType("application/zip")
                .result(bytes);
    }

    private byte[] buildBundle() {
        long started = System.nanoTime();
        Set<String> included = new HashSet<>();
        Set<String> atlases = new TreeSet<>();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (Source source : sources) {
                source.writeBundle(output, included, atlases);
            }
            for (String path : atlases) {
                byte[] merged = readAtlas(path);
                if (merged != null) bundleEntry(output, included, path, merged);
            }
            if (!included.contains("assets/minecraft/textures/block/stone.png")
                    || !included.contains("assets/minecraft/models/block/stone.json")
                    || !included.contains("assets/minecraft/atlases/blocks.json")) {
                LOGGER.warn("Editor asset bundle is incomplete; using individual asset reads");
                return new byte[0];
            }
            output.finish();
            byte[] result = bytes.toByteArray();
            LOGGER.info("Prepared editor asset bundle with {} files and {} bytes in {} ms",
                    included.size(), result.length, (System.nanoTime() - started) / 1_000_000);
            return result;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not prepare editor asset bundle", exception);
            return new byte[0];
        }
    }

    private static boolean bundlePath(String path) {
        if (!path.matches(ASSETS_PATHS)) return false;
        for (String segment : path.split(FORWARD_SLASH)) {
            if (segment.equals(".") || segment.equals("..")) return false;
        }
        String rest = path.substring(ASSETS.length());
        int separator = rest.indexOf(FORWARD_SLASH_CHAR);
        if (separator < 0) return false;
        String asset = rest.substring(separator + 1);
        for (String directory : BUNDLE_DIRECTORIES) {
            if (asset.startsWith(directory + FORWARD_SLASH)) return true;
        }
        return false;
    }

    private static void bundleEntry(ZipOutputStream output, Set<String> included,
                                    String path, byte[] bytes) throws IOException {
        if (!bundlePath(path) || included.contains(path)) return;
        output.putNextEntry(new ZipEntry(path));
        output.write(adaptItemDefinition(path, bytes));
        output.closeEntry();
        included.add(path);
    }

    private static boolean atlasPath(String path) {
        return path.matches("assets/[^/]+/atlases/.+\\.json");
    }

    private byte @Nullable [] readAtlas(String path) throws IOException {
        JsonArray entries = new JsonArray();
        boolean found = false;
        // Minecraft applies atlas sources from low to high pack priority, including filters.
        for (Source source : sources.reversed()) {
            byte[] bytes = source.read(path);
            if (bytes == null) continue;
            try {
                JsonElement value = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                if (!value.isJsonObject() || !value.getAsJsonObject().has("sources")
                        || !value.getAsJsonObject().get("sources").isJsonArray()) {
                    LOGGER.warn("Ignoring invalid editor atlas {} from {}: expected a sources array", path, source.name);
                    continue;
                }
                entries.addAll(value.getAsJsonObject().getAsJsonArray("sources"));
                found = true;
            } catch (JsonParseException exception) {
                LOGGER.warn("Ignoring invalid editor atlas {} from {}", path, source.name, exception);
            }
        }
        if (!found) return null;
        JsonObject atlas = new JsonObject();
        atlas.add("sources", entries);
        return GSON.toJson(atlas).getBytes(StandardCharsets.UTF_8);
    }

    private byte @Nullable [] resolveFile(String path) throws IOException {
        if (atlasPath(path)) return readAtlas(path);
        for (Source source : sources) {
            byte[] bytes = source.read(path);
            if (bytes != null) return adaptItemDefinition(path, bytes);
        }
        return null;
    }

    private static byte[] adaptItemDefinition(String path, byte[] bytes) {
        if (!path.matches("assets/[^/]+/items/.+\\.json")) return bytes;
        try {
            JsonElement value = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!value.isJsonObject()) return bytes;
            JsonObject definition = value.getAsJsonObject();
            JsonElement modelValue = definition.get("model");
            if (modelValue == null || !modelValue.isJsonObject()) return bytes;
            JsonObject model = modelValue.getAsJsonObject();
            JsonElement type = model.get("type");
            JsonElement baseModel = model.get("base_model");
            if (type == null || !type.isJsonPrimitive() || !type.getAsJsonPrimitive().isString()
                    || !"neoforge:trimmed_armor".equals(type.getAsString())
                    || baseModel == null || !baseModel.isJsonObject()) return bytes;
            // The browser renderer does not implement NeoForge's trim dispatcher.
            definition.add("model", baseModel.deepCopy());
            return GSON.toJson(definition).getBytes(StandardCharsets.UTF_8);
        } catch (JsonParseException exception) {
            return bytes;
        }
    }

    private void readFile(Context context) {
        Source source = requestedSource(context);
        String path = context.queryParam("path");
        if (source == null || !validFilePath(path)) {
            error(context, HttpStatus.BAD_REQUEST, "Invalid asset source or path");
            return;
        }
        try {
            byte[] bytes = source.read(path);
            if (bytes == null) {
                error(context, HttpStatus.NOT_FOUND, "Asset not found");
                return;
            }
            context.header("Cache-Control", "private, max-age=300")
                    .contentType("application/octet-stream")
                    .result(adaptItemDefinition(path, bytes));
        } catch (IOException exception) {
            error(context, HttpStatus.INTERNAL_SERVER_ERROR, "Could not read asset");
        }
    }

    private void listDirectory(Context context) {
        Source source = requestedSource(context);
        String directory = context.queryParam("dir");
        if (source == null || !validDirectory(directory)) {
            error(context, HttpStatus.BAD_REQUEST, "Invalid asset source or directory");
            return;
        }
        context.header("Cache-Control", "private, max-age=300")
                .contentType(CONTENT_TYPE)
                .result(GSON.toJson(source.getListOfAssets(directory)));
    }

    private void readResolvedFile(Context context) {
        String path = context.queryParam("path");
        if (!validFilePath(path)) {
            error(context, HttpStatus.BAD_REQUEST, "Invalid asset path");
            return;
        }
        try {
            byte[] bytes = resolveFile(path);
            if (bytes != null) {
                context.header("Cache-Control", "private, max-age=300")
                        .contentType("application/octet-stream")
                        .result(bytes);
                return;
            }
            error(context, HttpStatus.NOT_FOUND, "Asset not found");
        } catch (IOException exception) {
            error(context, HttpStatus.INTERNAL_SERVER_ERROR, "Could not read asset");
        }
    }

    private void listResolvedDirectory(Context context) {
        String directory = context.queryParam("dir");
        if (!validDirectory(directory)) {
            error(context, HttpStatus.BAD_REQUEST, "Invalid asset directory");
            return;
        }
        Set<String> names = new TreeSet<>();
        for (Source source : sources) {
            names.addAll(source.getListOfAssets(directory));
        }
        context.header("Cache-Control", "private, max-age=300")
                .contentType(CONTENT_TYPE)
                .result(GSON.toJson(names));
    }

    private @Nullable Source requestedSource(Context context) {
        try {
            int index = Integer.parseInt(context.queryParam(SOURCE));
            return index >= 0 && index < sources.size() ? sources.get(index) : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean validFilePath(@Nullable String path) {
        return "pack.mcmeta".equals(path) || validAssetPath(path);
    }

    private boolean validDirectory(@Nullable String directory) {
        return "assets".equals(directory) || validAssetPath(directory);
    }

    private boolean validAssetPath(@Nullable String path) {
        if (path == null || path.length() > MAX_PATH_LENGTH || !path.startsWith(ASSETS)
                || path.contains("\\") || path.contains("//") || path.endsWith(FORWARD_SLASH)) {
            return false;
        }
        for (String segment : path.split(FORWARD_SLASH)) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return path.matches(ASSETS_PATHS);
    }

    private void error(Context context, HttpStatus status, String message) {
        context.status(status).contentType(CONTENT_TYPE)
                .result(GSON.toJson(Map.of(ERROR, message)));
    }

    private static class Source {
        private final String name;
        private final @Nullable PackResources pack;
        private final @Nullable Path jar;
        private final Map<String, Set<String>> jarDirectories;

        public Source(PackResources pack) {
            name = pack.packId();
            this.pack = pack;
            jar = null;
            jarDirectories = Map.of();
        }

        public Source(Path jar) throws IOException {
            name = jar.getFileName().toString();
            pack = null;
            this.jar = jar;
            Map<String, Set<String>> directories = new HashMap<>();
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String path = entry.getName();
                    if (!path.startsWith(ASSETS) && !"pack.mcmeta".equals(path)) continue;
                    String[] segments = path.split(FORWARD_SLASH);
                    StringBuilder directory = new StringBuilder();
                    for (String segment : segments) {
                        directories.computeIfAbsent(directory.toString(), ignored -> new TreeSet<>()).add(segment);
                        if (!directory.isEmpty()) directory.append(FORWARD_SLASH_CHAR);
                        directory.append(segment);
                    }
                }
            }
            jarDirectories = directories;
        }

        public byte @Nullable [] read(String path) throws IOException {
            if (pack != null) {
                IoSupplier<InputStream> supplier;
                if ("pack.mcmeta".equals(path)) supplier = pack.getRootResource(path);
                else {
                    Identifier identifier = identifier(path);
                    if (identifier == null) return null;
                    supplier = pack.getResource(PackType.CLIENT_RESOURCES, identifier);
                }
                if (supplier == null) return null;
                try (InputStream input = supplier.get()) {
                    return input.readAllBytes();
                }
            }
            if (jar == null) return null;
            int separator = path.lastIndexOf(FORWARD_SLASH_CHAR);
            String directory = separator < 0 ? "" : path.substring(0, separator);
            String fileName = path.substring(separator + 1);
            if (!jarDirectories.getOrDefault(directory, Set.of()).contains(fileName)) return null;
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry entry = zip.getEntry(path);
                if (entry == null || entry.isDirectory()) return null;
                try (InputStream input = zip.getInputStream(entry)) {
                    return input.readAllBytes();
                }
            }
        }

        public void writeBundle(ZipOutputStream output, Set<String> included, Set<String> atlases) throws IOException {
            if (jar != null) {
                try (ZipFile zip = new ZipFile(jar.toFile())) {
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String path = entry.getName();
                        if (entry.isDirectory() || !bundlePath(path) || included.contains(path)) continue;
                        if (atlasPath(path)) {
                            atlases.add(path);
                            continue;
                        }
                        try (InputStream input = zip.getInputStream(entry)) {
                            bundleEntry(output, included, path, input.readAllBytes());
                        }
                    }
                }
                return;
            }
            if (pack == null) return;
            for (String namespace : pack.getNamespaces(PackType.CLIENT_RESOURCES)) {
                for (String directory : BUNDLE_DIRECTORIES) {
                    pack.listResources(PackType.CLIENT_RESOURCES, namespace, directory, (location, supplier) -> {
                        String path = ASSETS + namespace + FORWARD_SLASH + location.getPath();
                        if (!bundlePath(path) || included.contains(path)) return;
                        if (atlasPath(path)) {
                            atlases.add(path);
                            return;
                        }
                        try (InputStream input = supplier.get()) {
                            bundleEntry(output, included, path, input.readAllBytes());
                        } catch (IOException exception) {
                            throw new UncheckedIOException(exception);
                        }
                    });
                }
            }
        }

        public Collection<String> getListOfAssets(String directory) {
            if (pack == null) {
                return jarDirectories.getOrDefault(directory, Set.of());
            }
            if ("assets".equals(directory)) {
                return pack.getNamespaces(PackType.CLIENT_RESOURCES).stream().sorted().toList();
            }
            String rest = directory.substring(ASSETS.length());
            int separator = rest.indexOf(FORWARD_SLASH_CHAR);
            String namespace = separator < 0 ? rest : rest.substring(0, separator);
            if (!pack.getNamespaces(PackType.CLIENT_RESOURCES).contains(namespace)) return List.of();
            String resourceDirectory = separator < 0 ? "" : rest.substring(separator + 1);
            Set<String> names = new TreeSet<>();
            pack.listResources(PackType.CLIENT_RESOURCES, namespace, resourceDirectory, (location, supplier) -> {
                String prefix = resourceDirectory.isEmpty() ? "" : resourceDirectory + FORWARD_SLASH;
                if (location.getPath().startsWith(prefix)) {
                    names.add(location.getPath().substring(prefix.length()).split(FORWARD_SLASH, 2)[0]);
                }
            });
            return names;
        }

        private @Nullable Identifier identifier(String path) {
            String rest = path.substring(ASSETS.length());
            int separator = rest.indexOf(FORWARD_SLASH_CHAR);
            return separator < 0 ? null : Identifier.tryParse(rest.substring(0, separator) + ":" + rest.substring(separator + 1));
        }
    }
}
