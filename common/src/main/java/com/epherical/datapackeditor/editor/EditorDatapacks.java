package com.epherical.datapackeditor.editor;

import com.epherical.datapackeditor.impl.DataPackSchemaImpl;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.storage.loot.LootDataType;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;

public class EditorDatapacks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String ROOT_NAME = "Loaded Datapacks";
    private static final String DATA_DIRECTORY = "data";
    private static final String FORWARD_SLASH = "/";
    private static final Set<String> EXTRA_DIRECTORIES = Sets.newHashSet();
    private static final Set<Identifier> EXTRA_FILES = Sets.newHashSet();

    public static void registerDirectory(String directory) {
        if (directory == null || !directory.matches("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*")
                || Stream.of(directory.split("/")).anyMatch(segment -> segment.equals(".") || segment.equals(".."))) {
            throw new IllegalArgumentException("Invalid datapack directory: " + directory);
        }
        EXTRA_DIRECTORIES.add(topLevelDirectory(directory));
    }

    public static void registerFile(Identifier file) {
        if (!file.getPath().endsWith(".json")) throw new IllegalArgumentException("Expected a JSON resource");
        EXTRA_FILES.add(file);
    }

    private final Map<String, String> files;
    private final String treeJson;

    public EditorDatapacks(Map<String, String> files, String treeJson) {
        this.files = files;
        this.treeJson = treeJson;
    }

    public static EditorDatapacks create(MinecraftServer server) {
        TreeNode root = new TreeNode(ROOT_NAME, "", false);
        Map<String, String> files = new TreeMap<>();
        ResourceManager resourceManager = server.getResourceManager();
        Set<String> dataDirectories = collectDataDirectories(server);
        Map<String, Integer> filesByPack = new TreeMap<>();

        // ResourceManager requires a non-empty directory generate top-level directories from the server's registry set and scan the full resource
        // stacks so every contributing pack remains visible
        for (String directory : dataDirectories) {
            scanDirectory(resourceManager, directory, root, files, filesByPack);
        }
        for (Identifier file : EXTRA_FILES) {
            exposeResources(file, resourceManager.getResourceStack(file), root, files, filesByPack);
        }
        for (Map.Entry<String, Integer> entry : filesByPack.entrySet()) {
            LOGGER.info("Exposed {} JSON files from data pack {} to the editor",
                    entry.getValue(), entry.getKey());
        }

        DatapackTree tree = new DatapackTree(ROOT_NAME, files.size(), entries(root));
        return new EditorDatapacks(Map.copyOf(files), GSON.toJson(tree));
    }

    private static Set<String> collectDataDirectories(MinecraftServer server) {
        return collectDataDirectories(server.registryAccess().listRegistryKeys());
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
        directories.addAll(EXTRA_DIRECTORIES);
        DataPackSchemaImpl.registeredDirectories().forEach(directory -> directories.add(topLevelDirectory(directory)));
        LOGGER.info("Scanning {} data-pack directories for editor files", directories.size());
        return directories;
    }

    private static String topLevelDirectory(String path) {
        int separator = path.indexOf(FORWARD_SLASH);
        return separator < 0 ? path : path.substring(0, separator);
    }

    private static void scanDirectory(ResourceManager resourceManager, String directory,
                                      TreeNode root, Map<String, String> files,
                                      Map<String, Integer> filesByPack) {
        try {
            Map<Identifier, List<Resource>> stacks = resourceManager.listResourceStacks(
                    directory, identifier -> identifier.getPath().endsWith(".json"));
            for (Map.Entry<Identifier, List<Resource>> entry : stacks.entrySet()) {
                exposeResources(entry.getKey(), entry.getValue(), root, files, filesByPack);
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not enumerate data-pack directory {}", directory, exception);
        }
    }

    private static void exposeResources(Identifier identifier, List<Resource> resources, TreeNode root,
                                        Map<String, String> files, Map<String, Integer> filesByPack) {
        for (Resource resource : resources) {
            String packId = resource.sourcePackId();
            String virtualPath = String.join(FORWARD_SLASH, packId, DATA_DIRECTORY,
                    identifier.getNamespace(), identifier.getPath());
            if (files.containsKey(virtualPath)) continue;
            try (var input = resource.open()) {
                files.put(virtualPath, new String(input.readAllBytes(), StandardCharsets.UTF_8));
                addFile(root, packId, identifier, virtualPath);
                filesByPack.merge(packId, 1, Integer::sum);
            } catch (IOException exception) {
                LOGGER.warn("Could not expose data-pack JSON {} from {} to the editor", identifier, packId, exception);
            }
        }
    }

    public void registerRoutes(JavalinConfig config) {
        config.routes.get(FORWARD_SLASH + "api/datapacks", context -> context
                .header("Cache-Control", "no-store")
                .contentType("application/json")
                .result(treeJson));
        config.routes.get(FORWARD_SLASH + "api/datapack-file", this::readFile);
        config.routes.get(FORWARD_SLASH + "api/advancements", context -> context
                .header("Cache-Control", "no-store")
                .contentType("application/json")
                .result(GSON.toJson(files.entrySet().stream()
                        .filter(entry -> entry.getKey().matches(".*/data/[^/]+/advancements?/.*\\.json"))
                        .map(entry -> Map.of("path", entry.getKey(), "content", entry.getValue()))
                        .toList())));
        config.routes.post(FORWARD_SLASH + "api/datapack-override", this::createOverride);
    }

    private void readFile(Context context) {
        LoadedFile file = requestedFile(context);
        if (file == null) {
            return;
        }
        context.header("Cache-Control", "no-store")
                .header("X-datapack-editor-Read-Only", "true")
                .contentType("application/json; charset=utf-8")
                .result(file.content);
    }

    private void createOverride(Context context) {
        LoadedFile file = requestedFile(context);
        if (file == null) {
            return;
        }
        int dataPath = file.path.indexOf(FORWARD_SLASH + DATA_DIRECTORY + FORWARD_SLASH);
        if (dataPath < 0) {
            error(context, HttpStatus.BAD_REQUEST, "Data-pack file has no override path");
            return;
        }

        try {
            String workspacePath = EditorWorkspace.writeDatapackOverride(
                    file.path.substring(dataPath + FORWARD_SLASH.length()), file.content);
            context.status(HttpStatus.CREATED)
                    .contentType("application/json")
                    .result(GSON.toJson(Map.of("operation", "overridden", "path", workspacePath)));
        } catch (EditorWorkspace.WorkspaceException exception) {
            error(context, HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IOException exception) {
            LOGGER.warn("Could not create workspace override for {}", file.path, exception);
            error(context, HttpStatus.INTERNAL_SERVER_ERROR, "Could not create the workspace override");
        }
    }

    private LoadedFile requestedFile(Context context) {
        String path = context.queryParam("path");
        if (path == null || path.isBlank()) {
            error(context, HttpStatus.BAD_REQUEST, "A data-pack path is required");
            return null;
        }
        String content = files.get(path);
        if (content == null) {
            error(context, HttpStatus.NOT_FOUND, "Data-pack file was not found");
            return null;
        }
        return new LoadedFile(path, content);
    }

    private static void addFile(TreeNode root, String packId, Identifier identifier, String virtualPath) {
        TreeNode pack = root.child(packId, packId, false);
        StringBuilder path = new StringBuilder(packId).append(FORWARD_SLASH).append(DATA_DIRECTORY);
        TreeNode data = pack.child(DATA_DIRECTORY, path.toString(), false);
        path.append(FORWARD_SLASH).append(identifier.getNamespace());
        TreeNode namespace = data.child(identifier.getNamespace(), path.toString(), false);
        String[] segments = identifier.getPath().split(FORWARD_SLASH);
        TreeNode parent = namespace;
        for (int index = 0; index < segments.length; index++) {
            path.append(FORWARD_SLASH).append(segments[index]);
            boolean file = index == segments.length - 1;
            parent = parent.child(segments[index], file ? virtualPath : path.toString(), file);
        }
    }

    private static List<DatapackEntry> entries(TreeNode parent) {
        return parent.children.values().stream()
                .sorted(Comparator.comparing((TreeNode node) -> node.file).thenComparing(node -> node.name))
                .map(DatapackEntry::new)
                .toList();
    }

    private static void error(Context context, HttpStatus status, String message) {
        context.status(status).contentType("application/json")
                .result(GSON.toJson(Map.of("error", message)));
    }

    private static class DatapackTree {
        private final String root;
        private final boolean readOnly = true;
        private final int fileCount;
        private final List<DatapackEntry> entries;

        public DatapackTree(String root, int fileCount, List<DatapackEntry> entries) {
            this.root = root;
            this.fileCount = fileCount;
            this.entries = entries;
        }
    }

    private static class DatapackEntry {
        private final String name;
        private final String path;
        private final String source = "datapack";
        private final boolean readOnly = true;
        private final String type;
        private final Boolean editable;
        private final List<DatapackEntry> children;

        public DatapackEntry(TreeNode node) {
            this.name = node.name;
            this.path = node.path;
            this.type = node.file ? "file" : "directory";
            this.editable = node.file ? true : null;
            this.children = node.file ? null : entries(node);
        }
    }

    private static class TreeNode {
        private final String name;
        private final String path;
        private final boolean file;
        private final Map<String, TreeNode> children = new TreeMap<>();

        public TreeNode(String name, String path, boolean file) {
            this.name = name;
            this.path = path;
            this.file = file;
        }

        private TreeNode child(String name, String path, boolean file) {
            return children.computeIfAbsent(name, ignored -> new TreeNode(name, path, file));
        }
    }

    private record LoadedFile(String path, String content) {
    }
}
