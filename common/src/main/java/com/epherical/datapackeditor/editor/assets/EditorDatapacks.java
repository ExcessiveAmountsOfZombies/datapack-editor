package com.epherical.datapackeditor.editor.assets;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.epherical.datapackeditor.editor.MemoryChecker;
import com.epherical.datapackeditor.editor.MemoryChecker.CapacityException;
import com.epherical.datapackeditor.editor.EditorWorkspace;
import com.epherical.datapackeditor.editor.advancement.AdvancementCacher;
import com.epherical.datapackeditor.impl.DataPackRegistrationImpl;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonWriter;
import com.mojang.logging.LogUtils;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.storage.loot.LootDataType;
import org.slf4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class EditorDatapacks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern ADVANCEMENT_PATH = Pattern.compile(".*/data/[^/]+/advancements?/.*\\.json");

    private final Map<String, Resource> files;
    private final TreeNode root;
    private final List<String> advancementPaths;
    private final AdvancementCacher advancementCacher;

    public EditorDatapacks(Map<String, Resource> files, TreeNode root) {
        this.files = files;
        this.root = root;
        this.advancementPaths = files.keySet().stream()
                .filter(path -> ADVANCEMENT_PATH.matcher(path).matches())
                .sorted().toList();
        this.advancementCacher = new AdvancementCacher(files, advancementPaths);
    }

    public static EditorDatapacks create(MinecraftServer server) {
        return create(server.getResourceManager(), collectDataDirectories(server));
    }

    public static EditorDatapacks create(ResourceManager resourceManager, Set<String> dataDirectories) {
        TreeNode root = new TreeNode(DatapackEditorConstants.EDITOR_LOADED_DATAPACKS, "", false);
        Map<String, Resource> files = new HashMap<>();
        Map<String, Integer> filesByPack = new TreeMap<>();

        // ResourceManager requires a non-empty directory generate top-level directories from the server's registry set and scan the full resource
        // stacks so every contributing pack remains visible
        for (String directory : dataDirectories) {
            MemoryChecker.checkpoint();
            scanDirectory(resourceManager, directory, root, files, filesByPack);
        }
        for (ResourceLocation file : DataPackRegistrationImpl.registeredFiles()) {
            exposeResources(file, resourceManager.getResourceStack(file), root, files, filesByPack);
        }
        for (Map.Entry<String, Integer> entry : filesByPack.entrySet()) {
            LOGGER.info("Exposed {} JSON files from data pack {} to the editor",
                    entry.getValue(), entry.getKey());
        }

        return new EditorDatapacks(Map.copyOf(files), root);
    }

    private static Set<String> collectDataDirectories(MinecraftServer server) {
        return collectDataDirectories(server.registryAccess().registries().map(RegistryAccess.RegistryEntry::key));
    }

    public static Set<String> collectDataDirectories(
            Stream<? extends ResourceKey<? extends Registry<?>>> registryKeys) {
        Set<String> directories = new TreeSet<>();
        registryKeys.forEach(registryKey -> {
            directories.add(topLevelDirectory(Registries.elementsDirPath(registryKey)));
            directories.add(topLevelDirectory(Registries.tagsDirPath(registryKey)));
        });
        // these two managers use registry-shaped paths but are not part of the server's RegistryAccess snapshot
        directories.add(topLevelDirectory(Registries.elementsDirPath(Registries.ADVANCEMENT)));
        directories.add(topLevelDirectory(Registries.elementsDirPath(Registries.RECIPE)));
        // Loot tables, predicates, and item modifiers live in reloadable
        // registries, so their resource paths are also absent from the
        // server's RegistryAccess snapshot.
        LootDataType.values().forEach(data -> directories.add(
                topLevelDirectory(Registries.elementsDirPath(data.registryKey()))));
        // Vanilla's optional built-in packs are stored below
        // data/minecraft/datapacks rather than a registry directory.
        directories.add("datapacks");
        DataPackRegistrationImpl.registeredDirectories().forEach(directory -> directories.add(topLevelDirectory(directory)));
        LOGGER.info("Scanning {} data-pack directories for editor files", directories.size());
        return directories;
    }

    private static String topLevelDirectory(String path) {
        int separator = path.indexOf(DatapackEditorConstants.PATH_SEPARATOR);
        return separator < 0 ? path : path.substring(0, separator);
    }

    private static void scanDirectory(ResourceManager resourceManager, String directory,
                                      TreeNode root, Map<String, Resource> files,
                                      Map<String, Integer> filesByPack) {
        try {
            Map<ResourceLocation, List<Resource>> stacks = resourceManager.listResourceStacks(
                    directory, identifier -> identifier.getPath().endsWith(DatapackEditorConstants.PATH_JSON_EXTENSION));
            for (Map.Entry<ResourceLocation, List<Resource>> entry : stacks.entrySet()) {
                exposeResources(entry.getKey(), entry.getValue(), root, files, filesByPack);
            }
        } catch (CapacityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not enumerate data-pack directory {}", directory, exception);
        }
    }

    private static void exposeResources(ResourceLocation identifier, List<Resource> resources, TreeNode root,
                                        Map<String, Resource> files, Map<String, Integer> filesByPack) {
        for (Resource resource : resources) {
            MemoryChecker.requireRunning();
            String packId = resource.sourcePackId();
            String virtualPath = String.join(DatapackEditorConstants.PATH_SEPARATOR, packId, DatapackEditorConstants.PATH_DATA,
                    identifier.getNamespace(), identifier.getPath());
            if (files.containsKey(virtualPath)) continue;
            if (files.size() >= DatapackEditorConstants.DATAPACK_MAX_FILES)
                throw new CapacityException("Datapack file index exceeds its " + DatapackEditorConstants.DATAPACK_MAX_FILES + "-file limit");
            files.put(virtualPath, resource);
            addFile(root, packId, identifier, virtualPath);
            filesByPack.merge(packId, 1, Integer::sum);
        }
    }

    public void registerRoutes(JavalinConfig config) {
        config.routes.get("/api/datapacks", this::writeTree);
        config.routes.get("/api/datapack-file", this::readFile);
        config.routes.get("/api/advancements", this::writeAdvancements);
        config.routes.post("/api/advancement-tree", context -> {
            try {
                int cursor = Integer.parseInt(Objects.requireNonNullElse(context.queryParam(DatapackEditorConstants.JSON_CURSOR), "0"));
                int limit = Integer.parseInt(Objects.requireNonNullElse(context.queryParam(DatapackEditorConstants.JSON_LIMIT), "32"));
                context.contentType(DatapackEditorConstants.HTTP_JSON).result(advancementCacher.page(
                        MemoryChecker.json(context.bodyAsBytes()).getAsJsonObject(), cursor, limit).toString());
            } catch (IllegalArgumentException | IllegalStateException exception) {
                error(context, HttpStatus.BAD_REQUEST, "Invalid advancement tree request");
            }
        });
        config.routes.post("/api/datapack-override", this::createOverride);
    }

    private void readFile(Context context) {
        LoadedFile file = requestedFile(context);
        if (file == null) {
            return;
        }
        try (InputStream input = file.resource.open()) {
            context.header(DatapackEditorConstants.HTTP_CACHE_CONTROL, DatapackEditorConstants.HTTP_NO_STORE)
                    .header("X-datapack-editor-Read-Only", "true")
                    .contentType(DatapackEditorConstants.HTTP_JSON_UTF8)
                    .result(MemoryChecker.bytes(input, DatapackEditorConstants.EDITOR_JSON_MAX_BYTES, "Loaded datapack JSON"));
        } catch (IOException exception) {
            error(context, HttpStatus.INTERNAL_SERVER_ERROR, "Could not read the loaded data-pack file");
        }
    }

    private void createOverride(Context context) {
        LoadedFile file = requestedFile(context);
        if (file == null) {
            return;
        }
        int dataPath = file.path.indexOf(DatapackEditorConstants.PATH_SEPARATOR + DatapackEditorConstants.PATH_DATA + DatapackEditorConstants.PATH_SEPARATOR);
        if (dataPath < 0) {
            error(context, HttpStatus.BAD_REQUEST, "Data-pack file has no override path");
            return;
        }

        try (InputStream input = file.resource.open()) {
            byte[] bytes = input.readNBytes(DatapackEditorConstants.EDITOR_JSON_MAX_BYTES + 1);
            if (bytes.length > DatapackEditorConstants.EDITOR_JSON_MAX_BYTES) {
                error(context, HttpStatus.BAD_REQUEST, DatapackEditorConstants.MESSAGE_WORKSPACE_FILE_TOO_LARGE);
                return;
            }
            String workspacePath = EditorWorkspace.writeDatapackOverride(
                    file.path.substring(dataPath + DatapackEditorConstants.PATH_SEPARATOR.length()), new String(bytes, StandardCharsets.UTF_8));
            context.status(HttpStatus.CREATED)
                    .contentType(DatapackEditorConstants.HTTP_JSON)
                    .result(GSON.toJson(Map.of("operation", "overridden", DatapackEditorConstants.JSON_PATH, workspacePath)));
        } catch (EditorWorkspace.WorkspaceException exception) {
            error(context, HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IOException exception) {
            LOGGER.warn("Could not create workspace override for {}", file.path, exception);
            error(context, HttpStatus.INTERNAL_SERVER_ERROR, "Could not create the workspace override");
        }
    }

    private LoadedFile requestedFile(Context context) {
        String path = context.queryParam(DatapackEditorConstants.JSON_PATH);
        if (path == null || path.isBlank()) {
            error(context, HttpStatus.BAD_REQUEST, "A data-pack path is required");
            return null;
        }
        Resource resource = files.get(path);
        if (resource == null) {
            error(context, HttpStatus.NOT_FOUND, "Data-pack file was not found");
            return null;
        }
        return new LoadedFile(path, resource);
    }

    private void writeTree(Context context) throws IOException {
        JsonWriter writer = responseWriter(context);
        writer.beginObject().name(DatapackEditorConstants.JSON_ROOT).value(DatapackEditorConstants.EDITOR_LOADED_DATAPACKS).name(DatapackEditorConstants.JSON_READ_ONLY).value(true)
                .name("fileCount").value(files.size()).name(DatapackEditorConstants.JSON_ENTRIES);
        writeEntries(writer, root);
        writer.endObject();
        writer.flush();
    }

    private void writeAdvancements(Context context) throws IOException {
        int cursor;
        int limit;
        try {
            cursor = Integer.parseInt(Objects.requireNonNullElse(context.queryParam(DatapackEditorConstants.JSON_CURSOR), "0"));
            limit = Integer.parseInt(Objects.requireNonNullElse(context.queryParam(DatapackEditorConstants.JSON_LIMIT), "128"));
            if (cursor < 0 || cursor > advancementPaths.size() || limit < 1 || limit > DatapackEditorConstants.ADVANCEMENT_LEGACY_MAX_PAGE_SIZE) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            error(context, HttpStatus.BAD_REQUEST, "Invalid advancement cursor or limit (1–" + DatapackEditorConstants.ADVANCEMENT_LEGACY_MAX_PAGE_SIZE + ")");
            return;
        }
        int end = Math.min(advancementPaths.size(), cursor + limit);
        JsonWriter writer = responseWriter(context);
        writer.beginObject().name(DatapackEditorConstants.JSON_FILES).beginArray();
        for (int index = cursor; index < end; index++) {
            MemoryChecker.checkpoint();
            String path = advancementPaths.get(index);
            String summary;
            try (InputStreamReader reader = new InputStreamReader(MemoryChecker.input(files.get(path).open(), DatapackEditorConstants.EDITOR_JSON_MAX_BYTES, DatapackEditorConstants.MESSAGE_ADVANCEMENT_JSON), StandardCharsets.UTF_8)) {
                summary = AdvancementCacher.summary(reader);
            } catch (CapacityException exception) {
                MemoryChecker.requireRunning();
                continue;
            } catch (IOException | JsonParseException | IllegalStateException exception) {
                LOGGER.warn("Could not read editor advancement {}", path, exception);
                continue;
            }
            writer.beginObject().name(DatapackEditorConstants.JSON_PATH)
                    .value(path).name(DatapackEditorConstants.JSON_CONTENT)
                    .value(summary).endObject();
        }
        writer.endArray().name(DatapackEditorConstants.JSON_NEXT_CURSOR);
        if (end < advancementPaths.size()) writer.value(end);
        else writer.nullValue();
        writer.endObject();
        writer.flush();
    }

    private static JsonWriter responseWriter(Context context) {
        context.header(DatapackEditorConstants.HTTP_CACHE_CONTROL, DatapackEditorConstants.HTTP_NO_STORE).contentType(DatapackEditorConstants.HTTP_JSON_UTF8);
        return new JsonWriter(new BufferedWriter(new OutputStreamWriter(context.outputStream(), StandardCharsets.UTF_8), DatapackEditorConstants.TEXT_CHUNK_CHARS));
    }

    private static void writeEntries(JsonWriter writer, TreeNode parent) throws IOException {
        // TreeMap is already name sorted; two passes keep directories before files without a second tree.
        writer.beginArray();
        for (boolean files : new boolean[]{false, true}) {
            for (TreeNode node : parent.children.values()) {
                MemoryChecker.requireRunning();
                if (node.file != files) continue;
                writer.beginObject().name(DatapackEditorConstants.JSON_NAME).value(node.name).name(DatapackEditorConstants.JSON_PATH).value(node.path)
                        .name(DatapackEditorConstants.JSON_SOURCE).value(DatapackEditorConstants.JSON_DATAPACK).name(DatapackEditorConstants.JSON_READ_ONLY).value(true)
                        .name(DatapackEditorConstants.JSON_TYPE).value(node.file ? "file" : DatapackEditorConstants.JSON_DIRECTORY);
                if (node.file) writer.name("editable").value(true);
                else {
                    writer.name(DatapackEditorConstants.JSON_CHILDREN);
                    writeEntries(writer, node);
                }
                writer.endObject();
            }
        }
        writer.endArray();
    }

    private static void addFile(TreeNode root, String packId, ResourceLocation identifier, String virtualPath) {
        TreeNode pack = root.child(packId, packId, false);
        StringBuilder path = new StringBuilder(packId).append(DatapackEditorConstants.PATH_SEPARATOR).append(DatapackEditorConstants.PATH_DATA);
        TreeNode data = pack.child(DatapackEditorConstants.PATH_DATA, path.toString(), false);
        path.append(DatapackEditorConstants.PATH_SEPARATOR).append(identifier.getNamespace());
        TreeNode namespace = data.child(identifier.getNamespace(), path.toString(), false);
        String[] segments = identifier.getPath().split(DatapackEditorConstants.PATH_SEPARATOR);
        TreeNode parent = namespace;
        for (int index = 0; index < segments.length; index++) {
            path.append(DatapackEditorConstants.PATH_SEPARATOR).append(segments[index]);
            boolean file = index == segments.length - 1;
            parent = parent.child(segments[index], file ? virtualPath : path.toString(), file);
        }
    }

    private static void error(Context context, HttpStatus status, String message) {
        context.status(status).contentType(DatapackEditorConstants.HTTP_JSON)
                .result(GSON.toJson(Map.of(DatapackEditorConstants.JSON_ERROR, message)));
    }

    private static class TreeNode {
        private final String name;
        private final String path;
        private final boolean file;
        private final Map<String, TreeNode> children;

        public TreeNode(String name, String path, boolean file) {
            this.name = name;
            this.path = path;
            this.file = file;
            this.children = file ? Map.of() : new TreeMap<>();
        }

        private TreeNode child(String name, String path, boolean file) {
            return children.computeIfAbsent(name, ignored -> new TreeNode(name, path, file));
        }
    }

    private record LoadedFile(String path, Resource resource) {
    }
}
