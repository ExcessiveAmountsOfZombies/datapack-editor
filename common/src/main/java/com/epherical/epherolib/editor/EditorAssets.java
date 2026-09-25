package com.epherical.epherolib.editor;

import com.google.gson.Gson;
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class EditorAssets {
    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ASSETS = "assets/";
    private static final int MAX_PATH_LENGTH = 512;
    public static final String SOURCE = "source";

    public static final String CONTENT_TYPE = "application/json";
    public static final String ERROR = "error";

    public static final String ASSETS_PATHS = "assets/[a-z0-9_.-]+(?:/[a-z0-9_./-]+)?";

    public static final String FORWARD_SLASH = "/";
    public static final char FORWARD_SLASH_CHAR = '/';

    private final List<Source> sources;
    private final String manifest;

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
    }

    public void registerRoutes(JavalinConfig config) {
        config.routes.get("/api/assets", context -> context
                .header("Cache-Control", "no-store")
                .contentType(CONTENT_TYPE)
                .result(manifest));
        config.routes.get("/api/asset-file", this::readFile);
        config.routes.get("/api/asset-list", this::listDirectory);
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
                    .result(bytes);
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
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry entry = zip.getEntry(path);
                if (entry == null || entry.isDirectory()) return null;
                try (InputStream input = zip.getInputStream(entry)) {
                    return input.readAllBytes();
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
