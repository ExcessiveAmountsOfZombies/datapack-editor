package com.epherical.datapackeditor.editor.assets;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.epherical.datapackeditor.editor.MemoryChecker;
import com.epherical.datapackeditor.editor.MemoryChecker.CapacityException;
import com.google.gson.*;
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
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class EditorAssets {
    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<String> BUNDLE_DIRECTORIES = List.of(
            DatapackEditorConstants.SCHEMA_ITEMS, "models", "blockstates", "textures", "atlases", "equipment", "particles");

    private static final Pattern ASSET_PATH = Pattern.compile("assets/[a-z0-9_.-]+(?:/[a-z0-9_./-]+)?");
    private static final Pattern ATLAS_PATH = Pattern.compile("assets/[^/]+/atlases/.+\\.json");
    private static final Pattern ITEM_DEFINITION_PATH = Pattern.compile("assets/[^/]+/items/.+\\.json");

    private final List<Source> sources;
    private final String manifest;
    private CompletableFuture<Path> bundle;
    private boolean closed;

    public EditorAssets(MinecraftServer server) {
        this(server.getResourceManager(), server.getServerDirectory().resolve("mods"),
                SharedConstants.getCurrentVersion().name());
    }

    public EditorAssets(ResourceManager resourceManager, Path modsDirectory, String version) {
        List<Source> orderedSources = new ArrayList<>();
        List<PackResources> loadedPacks = resourceManager.listPacks().toList();
        PackResources vanilla = null;
        for (int index = loadedPacks.size() - 1; index >= 0; index--) {
            MemoryChecker.checkpoint();
            PackResources pack = loadedPacks.get(index);
            if ("vanilla".equals(pack.packId())) {
                vanilla = pack;
            } else {
                orderedSources.add(new Source(pack));
            }
        }

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
            layers.add(Map.of(DatapackEditorConstants.JSON_ID, index, DatapackEditorConstants.JSON_NAME, sources.get(index).name));
        }
        manifest = GSON.toJson(Map.of("version", version, DatapackEditorConstants.JSON_SOURCES, layers));
    }

    public void registerRoutes(JavalinConfig config) {
        config.routes.get("/api/assets", context -> context
                .header(DatapackEditorConstants.HTTP_CACHE_CONTROL, DatapackEditorConstants.HTTP_NO_STORE)
                .contentType(DatapackEditorConstants.HTTP_JSON)
                .result(manifest));
        config.routes.get("/api/asset-file", this::readFile);
        config.routes.get("/api/asset-resolved", this::readResolvedFile);
        config.routes.get("/api/asset-list", this::listDirectory);
        config.routes.get("/api/asset-list-resolved", this::listResolvedDirectory);
        config.routes.get("/api/asset-bundle", this::readBundle);
    }

    private synchronized CompletableFuture<Path> bundle() {
        if (closed) return CompletableFuture.completedFuture(null);
        if (bundle == null) bundle = CompletableFuture.supplyAsync(this::buildBundle);
        return bundle;
    }

    public synchronized void close() {
        closed = true;
        if (bundle != null) bundle.whenComplete((path, failure) -> deleteBundle(path));
    }

    private static void deleteBundle(@Nullable Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            LOGGER.warn("Could not delete temporary editor asset bundle {}", path, exception);
        }
    }

    private void readBundle(Context context) throws IOException {
        Path path = bundle().join();
        if (path == null) {
            error(context, HttpStatus.SERVICE_UNAVAILABLE, "Asset bundle unavailable");
            return;
        }
        context.header(DatapackEditorConstants.HTTP_CACHE_CONTROL, DatapackEditorConstants.HTTP_PRIVATE_CACHE)
                .contentType("application/zip")
                .result(Files.newInputStream(path));
    }

    private @Nullable Path buildBundle() {
        long started = System.nanoTime();
        Set<String> included = new HashSet<>();
        Set<String> atlases = new TreeSet<>();
        Path path = null;
        boolean complete = false;
        try {
            path = Files.createTempFile("datapack-editor-assets-", DatapackEditorConstants.PATH_ZIP_EXTENSION);
            try (ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(path), 64 * DatapackEditorConstants.KIBIBYTE))) {
                for (Source source : sources) {
                    source.writeBundle(output, included, atlases);
                }
                for (String atlas : atlases) {
                    byte[] merged = readAtlas(atlas);
                    if (merged != null) bundleEntry(output, included, atlas, merged);
                }
                if (!included.contains("assets/minecraft/textures/block/stone.png")
                        || !included.contains("assets/minecraft/models/block/stone.json")
                        || !included.contains("assets/minecraft/atlases/blocks.json")) {
                    LOGGER.warn("Editor asset bundle is incomplete; using individual asset reads");
                    return null;
                }
                output.finish();
            }
            LOGGER.info("Prepared editor asset bundle with {} files and {} bytes in {} ms",
                    included.size(), Files.size(path), (System.nanoTime() - started) / 1_000_000);
            complete = true;
            return path;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not prepare editor asset bundle", exception);
            return null;
        } finally {
            if (!complete) deleteBundle(path);
        }
    }

    private static boolean bundlePath(String path) {
        if (!ASSET_PATH.matcher(path).matches()) return false;
        for (String segment : path.split(DatapackEditorConstants.PATH_SEPARATOR)) {
            if (segment.equals(DatapackEditorConstants.PATH_CURRENT_DIRECTORY) || segment.equals(DatapackEditorConstants.PATH_PARENT_DIRECTORY))
                return false;
        }
        String rest = path.substring(DatapackEditorConstants.PATH_ASSETS_PREFIX.length());
        int separator = rest.indexOf(DatapackEditorConstants.PATH_SEPARATOR_CHAR);
        if (separator < 0) return false;
        String asset = rest.substring(separator + 1);
        for (String directory : BUNDLE_DIRECTORIES) {
            if (asset.startsWith(directory + DatapackEditorConstants.PATH_SEPARATOR)) return true;
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

    private static void bundleEntry(ZipOutputStream output, Set<String> included,
                                    String path, InputStream input) throws IOException {
        if (!bundlePath(path) || included.contains(path)) return;
        if (ITEM_DEFINITION_PATH.matcher(path).matches()) {
            bundleEntry(output, included, path, readAsset(input, path));
            return;
        }
        output.putNextEntry(new ZipEntry(path));
        MemoryChecker.input(input, DatapackEditorConstants.BYTES_64_MIB, "Streamed bundle asset").transferTo(output);
        output.closeEntry();
        included.add(path);
    }

    private static byte[] readAsset(InputStream input, String path) throws IOException {
        byte[] bytes = MemoryChecker.bytes(input, path.endsWith(DatapackEditorConstants.PATH_JSON_EXTENSION) ? DatapackEditorConstants.EDITOR_JSON_MAX_BYTES : (8 * DatapackEditorConstants.MEBIBYTE), "Asset " + path);
        if (path.endsWith(DatapackEditorConstants.PATH_JSON_EXTENSION)) MemoryChecker.validateJson(bytes);
        if (path.endsWith(".png")) {
            if (bytes.length < 24 || ByteBuffer.wrap(bytes).getLong() != 0x89504e470d0a1a0aL
                    || ByteBuffer.wrap(bytes, 12, DatapackEditorConstants.PNG_DIMENSION_BYTES).getInt() != 0x49484452)
                throw new IOException("Invalid PNG header");
            int width = ByteBuffer.wrap(bytes, 16, DatapackEditorConstants.PNG_DIMENSION_BYTES).getInt();
            int height = ByteBuffer.wrap(bytes, 20, DatapackEditorConstants.PNG_DIMENSION_BYTES).getInt();
            if (width < 1 || height < 1 || width > DatapackEditorConstants.ASSET_MAX_TEXTURE_DIMENSION || height > DatapackEditorConstants.ASSET_MAX_TEXTURE_DIMENSION || (long) width * height > DatapackEditorConstants.ASSET_MAX_TEXTURE_PIXELS) {
                throw new CapacityException("Texture " + path + " exceeds the " + DatapackEditorConstants.ASSET_MAX_TEXTURE_PIXELS / DatapackEditorConstants.MEBIBYTE + "-megapixel/" + DatapackEditorConstants.ASSET_MAX_TEXTURE_DIMENSION + "-dimension preview limit");
            }
        }
        return bytes;
    }

    private static boolean atlasPath(String path) {
        return ATLAS_PATH.matcher(path).matches();
    }

    private byte @Nullable [] readAtlas(String path) throws IOException {
        JsonArray entries = new JsonArray();
        boolean found = false;
        long totalBytes = 0;
        for (Source source : sources.reversed()) { // reversing the sources so that resource packs load first (haven't tested with a resource pack)
            try {
                byte[] bytes = source.read(path);
                if (bytes == null) continue;
                totalBytes += bytes.length;
                if (totalBytes > DatapackEditorConstants.EDITOR_JSON_MAX_BYTES)
                    throw new CapacityException("Merged atlas exceeds its " + DatapackEditorConstants.EDITOR_JSON_MAX_BYTES / DatapackEditorConstants.MEBIBYTE + " MiB budget");
                JsonElement value = MemoryChecker.json(bytes);
                if (!value.isJsonObject() || !value.getAsJsonObject().has(DatapackEditorConstants.JSON_SOURCES)
                        || !value.getAsJsonObject().get(DatapackEditorConstants.JSON_SOURCES).isJsonArray()) {
                    LOGGER.warn("Ignoring invalid editor atlas {} from {}: expected a sources array", path, source.name);
                    continue;
                }
                JsonArray additions = value.getAsJsonObject().getAsJsonArray(DatapackEditorConstants.JSON_SOURCES);
                if (entries.size() + additions.size() > DatapackEditorConstants.ASSET_MAX_ATLAS_SOURCES)
                    throw new CapacityException("Merged atlas exceeds its " + DatapackEditorConstants.ASSET_MAX_ATLAS_SOURCES + "-source budget");
                entries.addAll(additions);
                found = true;
            } catch (IOException | JsonParseException exception) {
                LOGGER.warn("Ignoring invalid editor atlas {} from {}", path, source.name, exception);
            }
        }
        if (!found) return null;
        JsonObject atlas = new JsonObject();
        atlas.add(DatapackEditorConstants.JSON_SOURCES, entries);
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

    private static byte[] adaptItemDefinition(String path, byte[] bytes) throws IOException {
        if (!ITEM_DEFINITION_PATH.matcher(path).matches()) return bytes;
        try {
            JsonElement value = MemoryChecker.json(bytes);
            if (!value.isJsonObject()) return bytes;
            JsonObject definition = value.getAsJsonObject();
            JsonElement modelValue = definition.get(DatapackEditorConstants.JSON_MODEL);
            if (modelValue == null || !modelValue.isJsonObject()) return bytes;
            JsonObject model = modelValue.getAsJsonObject();
            JsonElement type = model.get(DatapackEditorConstants.JSON_TYPE);
            JsonElement baseModel = model.get("base_model");
            if (type == null || !type.isJsonPrimitive() || !type.getAsJsonPrimitive().isString()
                    || !"neoforge:trimmed_armor".equals(type.getAsString())
                    || baseModel == null || !baseModel.isJsonObject()) return bytes;
            definition.add(DatapackEditorConstants.JSON_MODEL, baseModel.deepCopy());
            return GSON.toJson(definition).getBytes(StandardCharsets.UTF_8);
        } catch (JsonParseException exception) {
            return bytes;
        }
    }

    private void readFile(Context context) {
        Source source = requestedSource(context);
        String path = context.queryParam(DatapackEditorConstants.JSON_PATH);
        if (source == null || !validFilePath(path)) {
            error(context, HttpStatus.BAD_REQUEST, "Invalid asset source or path");
            return;
        }
        try {
            byte[] bytes = source.read(path);
            if (bytes == null) {
                error(context, HttpStatus.NOT_FOUND, DatapackEditorConstants.MESSAGE_ASSET_NOT_FOUND);
                return;
            }
            context.header(DatapackEditorConstants.HTTP_CACHE_CONTROL, DatapackEditorConstants.HTTP_PRIVATE_CACHE)
                    .contentType(DatapackEditorConstants.HTTP_OCTET_STREAM)
                    .result(adaptItemDefinition(path, bytes));
        } catch (IOException exception) {
            error(context, HttpStatus.INTERNAL_SERVER_ERROR, DatapackEditorConstants.MESSAGE_COULD_NOT_READ_ASSET);
        }
    }

    private void listDirectory(Context context) {
        Source source = requestedSource(context);
        String directory = context.queryParam(DatapackEditorConstants.JSON_DIR);
        if (source == null || !validDirectory(directory)) {
            error(context, HttpStatus.BAD_REQUEST, "Invalid asset source or directory");
            return;
        }
        context.header(DatapackEditorConstants.HTTP_CACHE_CONTROL, DatapackEditorConstants.HTTP_PRIVATE_CACHE)
                .contentType(DatapackEditorConstants.HTTP_JSON)
                .result(directoryJson(source.getListOfAssets(directory)));
    }

    private void readResolvedFile(Context context) {
        String path = context.queryParam(DatapackEditorConstants.JSON_PATH);
        if (!validFilePath(path)) {
            error(context, HttpStatus.BAD_REQUEST, "Invalid asset path");
            return;
        }
        try {
            byte[] bytes = resolveFile(path);
            if (bytes != null) {
                context.header(DatapackEditorConstants.HTTP_CACHE_CONTROL, DatapackEditorConstants.HTTP_PRIVATE_CACHE)
                        .contentType(DatapackEditorConstants.HTTP_OCTET_STREAM)
                        .result(bytes);
                return;
            }
            error(context, HttpStatus.NOT_FOUND, DatapackEditorConstants.MESSAGE_ASSET_NOT_FOUND);
        } catch (IOException exception) {
            error(context, HttpStatus.INTERNAL_SERVER_ERROR, DatapackEditorConstants.MESSAGE_COULD_NOT_READ_ASSET);
        }
    }

    private void listResolvedDirectory(Context context) {
        String directory = context.queryParam(DatapackEditorConstants.JSON_DIR);
        if (!validDirectory(directory)) {
            error(context, HttpStatus.BAD_REQUEST, "Invalid asset directory");
            return;
        }
        Set<String> names = new TreeSet<>();
        for (Source source : sources) {
            names.addAll(source.getListOfAssets(directory));
            if (names.size() > DatapackEditorConstants.ASSET_MAX_DIRECTORY_NAMES)
                throw new CapacityException(DatapackEditorConstants.MESSAGE_ASSET_DIRECTORY_TOO_LARGE);
        }
        context.header(DatapackEditorConstants.HTTP_CACHE_CONTROL, DatapackEditorConstants.HTTP_PRIVATE_CACHE)
                .contentType(DatapackEditorConstants.HTTP_JSON)
                .result(directoryJson(names));
    }

    private static String directoryJson(Collection<String> names) {
        long characters = 0;
        if (names.size() > DatapackEditorConstants.ASSET_MAX_DIRECTORY_NAMES)
            throw new CapacityException(DatapackEditorConstants.MESSAGE_ASSET_DIRECTORY_TOO_LARGE);
        for (String name : names) {
            MemoryChecker.requireRunning();
            characters += name.length() + 3;
            if (characters > 262_144) throw new CapacityException("Asset directory exceeds its text budget");
        }
        return GSON.toJson(names);
    }

    private @Nullable Source requestedSource(Context context) {
        try {
            int index = Integer.parseInt(context.queryParam(DatapackEditorConstants.JSON_SOURCE));
            return index >= 0 && index < sources.size() ? sources.get(index) : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean validFilePath(@Nullable String path) {
        return DatapackEditorConstants.PATH_PACK_METADATA.equals(path) || validAssetPath(path);
    }

    private boolean validDirectory(@Nullable String directory) {
        return DatapackEditorConstants.PATH_ASSETS.equals(directory) || validAssetPath(directory);
    }

    private boolean validAssetPath(@Nullable String path) {
        if (path == null || path.length() > DatapackEditorConstants.ASSET_MAX_PATH_LENGTH || !path.startsWith(DatapackEditorConstants.PATH_ASSETS_PREFIX)
                || path.contains(DatapackEditorConstants.PATH_WINDOWS_SEPARATOR) || path.contains("//") || path.endsWith(DatapackEditorConstants.PATH_SEPARATOR)) {
            return false;
        }
        for (String segment : path.split(DatapackEditorConstants.PATH_SEPARATOR)) {
            if (segment.equals(DatapackEditorConstants.PATH_CURRENT_DIRECTORY) || segment.equals(DatapackEditorConstants.PATH_PARENT_DIRECTORY)) {
                return false;
            }
        }
        return ASSET_PATH.matcher(path).matches();
    }

    private void error(Context context, HttpStatus status, String message) {
        context.status(status).contentType(DatapackEditorConstants.HTTP_JSON)
                .result(GSON.toJson(Map.of(DatapackEditorConstants.JSON_ERROR, message)));
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
                    MemoryChecker.checkpoint();
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String path = entry.getName();
                    if (!path.startsWith(DatapackEditorConstants.PATH_ASSETS_PREFIX) && !DatapackEditorConstants.PATH_PACK_METADATA.equals(path))
                        continue;
                    if (path.length() > DatapackEditorConstants.ASSET_MAX_PATH_LENGTH) continue;
                    if (!DatapackEditorConstants.PATH_PACK_METADATA.equals(path) && !ASSET_PATH.matcher(path).matches())
                        continue;
                    String[] segments = path.split(DatapackEditorConstants.PATH_SEPARATOR);
                    StringBuilder directory = new StringBuilder();
                    for (String segment : segments) {
                        directories.computeIfAbsent(directory.toString(), ignored -> new TreeSet<>()).add(segment);
                        if (!directory.isEmpty()) directory.append(DatapackEditorConstants.PATH_SEPARATOR_CHAR);
                        directory.append(segment);
                    }
                }
            }
            jarDirectories = directories;
        }

        public byte @Nullable [] read(String path) throws IOException {
            if (pack != null) {
                IoSupplier<InputStream> supplier;
                if (DatapackEditorConstants.PATH_PACK_METADATA.equals(path)) supplier = pack.getRootResource(path);
                else {
                    Identifier identifier = identifier(path);
                    if (identifier == null) return null;
                    supplier = pack.getResource(PackType.CLIENT_RESOURCES, identifier);
                }
                if (supplier == null) return null;
                try (InputStream input = supplier.get()) {
                    return readAsset(input, path);
                }
            }
            if (jar == null) return null;
            int separator = path.lastIndexOf(DatapackEditorConstants.PATH_SEPARATOR_CHAR);
            String directory = separator < 0 ? "" : path.substring(0, separator);
            String fileName = path.substring(separator + 1);
            if (!jarDirectories.getOrDefault(directory, Set.of()).contains(fileName)) return null;
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry entry = zip.getEntry(path);
                if (entry == null || entry.isDirectory()) return null;
                try (InputStream input = zip.getInputStream(entry)) {
                    return readAsset(input, path);
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
                            bundleEntry(output, included, path, input);
                        }
                    }
                }
                return;
            }
            if (pack == null) return;
            for (String namespace : pack.getNamespaces(PackType.CLIENT_RESOURCES)) {
                for (String directory : BUNDLE_DIRECTORIES) {
                    pack.listResources(PackType.CLIENT_RESOURCES, namespace, directory, (location, supplier) -> {
                        String path = DatapackEditorConstants.PATH_ASSETS_PREFIX + namespace + DatapackEditorConstants.PATH_SEPARATOR + location.getPath();
                        if (!bundlePath(path) || included.contains(path)) return;
                        if (atlasPath(path)) {
                            atlases.add(path);
                            return;
                        }
                        try (InputStream input = supplier.get()) {
                            bundleEntry(output, included, path, input);
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
            if (DatapackEditorConstants.PATH_ASSETS.equals(directory)) {
                return pack.getNamespaces(PackType.CLIENT_RESOURCES).stream().sorted().toList();
            }
            String rest = directory.substring(DatapackEditorConstants.PATH_ASSETS_PREFIX.length());
            int separator = rest.indexOf(DatapackEditorConstants.PATH_SEPARATOR_CHAR);
            String namespace = separator < 0 ? rest : rest.substring(0, separator);
            if (!pack.getNamespaces(PackType.CLIENT_RESOURCES).contains(namespace)) return List.of();
            String resourceDirectory = separator < 0 ? "" : rest.substring(separator + 1);
            Set<String> names = new TreeSet<>();
            pack.listResources(PackType.CLIENT_RESOURCES, namespace, resourceDirectory, (location, supplier) -> {
                MemoryChecker.requireRunning();
                String prefix = resourceDirectory.isEmpty() ? "" : resourceDirectory + DatapackEditorConstants.PATH_SEPARATOR;
                if (location.getPath().startsWith(prefix)) {
                    names.add(location.getPath().substring(prefix.length()).split(DatapackEditorConstants.PATH_SEPARATOR, 2)[0]);
                    if (names.size() > DatapackEditorConstants.ASSET_MAX_DIRECTORY_NAMES)
                        throw new CapacityException(DatapackEditorConstants.MESSAGE_ASSET_DIRECTORY_TOO_LARGE);
                }
            });
            return names;
        }

        private @Nullable Identifier identifier(String path) {
            String rest = path.substring(DatapackEditorConstants.PATH_ASSETS_PREFIX.length());
            int separator = rest.indexOf(DatapackEditorConstants.PATH_SEPARATOR_CHAR);
            return separator < 0 ? null : Identifier.tryParse(rest.substring(0, separator) + ":" + rest.substring(separator + 1));
        }
    }
}
