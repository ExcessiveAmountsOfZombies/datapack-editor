package com.epherical.epherolib.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class EditorWorkspace {
    public static final String DIRECTORY_NAME = "workspace";

    private static final int MAX_ENTRIES = 2_000;
    private static final int MAX_DEPTH = 24;
    private static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
    private static final String INITIALIZED_FILE = ".epherolib-initialized";
    private static final Set<String> EDITABLE_EXTENSIONS = Set.of(
            "js", "json", "txt", "md", "mcfunction", "properties", "toml", "yaml", "yml"
    );
    private static final Path ROOT = Path.of(DIRECTORY_NAME).toAbsolutePath().normalize();
    public static final String WORKSPACE_FILE_TOO_LARGE = "File is larger than 2 MiB";
    public static final String CREATED = "created";
    public static final String SAVED = "saved";
    public static final String PATH = "path";
    public static final String TARGET = "target";

    public static void initialize() {
        try {
            if (Files.exists(ROOT, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(ROOT)) {
                throw new IOException("The editor workspace cannot be a symbolic link");
            }
            Files.createDirectories(ROOT);
            Path marker = ROOT.resolve(INITIALIZED_FILE);
            if (Files.notExists(marker, LinkOption.NOFOLLOW_LINKS)) {
                seedExamples();
                Files.writeString(marker, "EpheroLib editor workspace\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not initialize " + DIRECTORY_NAME, exception);
        }
    }

    public static void registerRoutes(JavalinConfig config) {
        config.routes.get("/api/workspace", EditorWorkspace::listWorkspace);
        config.routes.get("/api/file", EditorWorkspace::readFile);
        config.routes.put("/api/file", EditorWorkspace::saveFile);
        config.routes.post("/api/file", EditorWorkspace::createFile);
        config.routes.post("/api/folder", EditorWorkspace::createFolder);
        config.routes.post("/api/move", EditorWorkspace::moveEntry);
        config.routes.post("/api/export-datapack", EditorWorkspace::exportDatapack);
        config.routes.delete("/api/entry", EditorWorkspace::deleteEntry);
    }

    public static synchronized String writeDatapackOverride(String workspacePath, String content) throws IOException {
        Path file = resolveEntry(workspacePath);
        requireEditableExtension(file);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            // do we need this?
            throw new WorkspaceException(WORKSPACE_FILE_TOO_LARGE);
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException("The override path is not a file");
        }

        Files.createDirectories(file.getParent());
        rejectSymbolicLinks(file);
        Path temporary = Files.createTempFile(file.getParent(), ".epherolib-override-", ".tmp");
        writeFile(file, bytes, temporary);
        return relativePath(file);
    }

    private static void writeFile(Path file, byte[] bytes, Path temporary) throws IOException {
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static synchronized void listWorkspace(Context context) {
        handle(context, () -> {
            StringBuilder json = new StringBuilder("{\"root\":\"")
                    .append(jsonEscape(DIRECTORY_NAME))
                    .append("\",\"entries\":[");
            AtomicInteger count = new AtomicInteger();
            appendChildren(json, ROOT, 0, count);
            json.append("]}");
            context.contentType("application/json").result(json.toString());
        });
    }

    private static synchronized void readFile(Context context) {
        handle(context, () -> {
            Path file = resolveEntry(requiredQuery(context, PATH));
            requireEditableFile(file);
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                throw new WorkspaceException(WORKSPACE_FILE_TOO_LARGE);
            }
            context.header("Cache-Control", "no-store")
                    .contentType("text/plain; charset=utf-8")
                    .result(Files.readString(file, StandardCharsets.UTF_8));
        });
    }

    private static synchronized void saveFile(Context context) {
        handle(context, () -> {
            Path file = resolveEntry(requiredQuery(context, PATH));
            requireEditableFile(file);
            byte[] content = context.body().getBytes(StandardCharsets.UTF_8);
            if (content.length > MAX_FILE_BYTES) {
                throw new WorkspaceException(WORKSPACE_FILE_TOO_LARGE);
            }

            Path temporary = Files.createTempFile(file.getParent(), ".epherolib-save-", ".tmp");
            writeFile(file, content, temporary);
            success(context, SAVED, relativePath(file));
        });
    }

    private static synchronized void createFile(Context context) {
        handle(context, () -> {
            Path file = resolveEntry(requiredQuery(context, PATH));
            requireEditableExtension(file);
            requireExistingParent(file);
            Files.createFile(file);
            context.status(HttpStatus.CREATED);
            success(context, CREATED, relativePath(file));
        });
    }

    private static synchronized void createFolder(Context context) {
        handle(context, () -> {
            Path folder = resolveEntry(requiredQuery(context, PATH));
            requireExistingParent(folder);
            Files.createDirectory(folder);
            context.status(HttpStatus.CREATED);
            success(context, CREATED, relativePath(folder));
        });
    }

    private static synchronized void moveEntry(Context context) {
        handle(context, () -> {
            Path source = resolveEntry(requiredQuery(context, PATH));
            Path target = resolveEntry(requiredQuery(context, TARGET));
            requireExistingEntry(source);
            if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    && !source.getFileName().equals(target.getFileName())) {
                requireEditableExtension(target);
            }
            requireExistingParent(target);
            Files.move(source, target);
            success(context, "moved", relativePath(target));
        });
    }

    private static synchronized void exportDatapack(Context context) {
        handle(context, () -> {
            JsonObject request;
            try {
                JsonElement parsed = JsonParser.parseString(context.body());
                if (!parsed.isJsonObject()) throw new WorkspaceException("Export settings must be a JSON object");
                request = parsed.getAsJsonObject();
            } catch (JsonParseException exception) {
                throw new WorkspaceException("Export settings must be valid JSON");
            }
            String name = requiredString(request, "name");
            String description = requiredString(request, "description");
            if (request.has("files")) {
                throw new WorkspaceException("Export uses saved workspace files. Save changes before exporting.");
            }
            Path exported;
            try {
                exported = EditorDatapackExport.export(ROOT, name, description);
            } catch (InvalidPathException exception) {
                throw new WorkspaceException("The computer cannot use that ZIP filename: " + exception.getMessage());
            } catch (IOException exception) {
                error(context, HttpStatus.INTERNAL_SERVER_ERROR,
                        "Could not create the datapack ZIP: " + exception.getMessage());
                return;
            }
            context.status(HttpStatus.CREATED);
            success(context, "exported", exported.toString());
        });
    }

    private static String requiredString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new WorkspaceException("Missing or invalid export field: " + key);
        }
        return value.getAsString();
    }

    private static synchronized void deleteEntry(Context context) {
        handle(context, () -> {
            Path entry = resolveEntry(requiredQuery(context, PATH));
            requireExistingEntry(entry);
            try (Stream<Path> paths = Files.walk(entry)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
            success(context, "deleted", relativePath(entry));
        });
    }

    private static void appendChildren(StringBuilder json, Path directory, int depth, AtomicInteger count)
            throws IOException {
        if (depth > MAX_DEPTH) {
            throw new WorkspaceException("Workspace nesting is too deep");
        }

        try (Stream<Path> children = Files.list(directory)) {
            var ordered = children
                    .filter(path -> !path.getFileName().toString().equals(INITIALIZED_FILE))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .sorted(Comparator
                            .comparing((Path path) -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                            .thenComparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
            boolean first = true;
            for (Path child : ordered) {
                if (count.incrementAndGet() > MAX_ENTRIES) {
                    throw new WorkspaceException("Workspace contains more than " + MAX_ENTRIES + " entries");
                }
                if (!first) {
                    json.append(',');
                }
                first = false;
                String name = child.getFileName().toString();
                String relative = relativePath(child);
                json.append("{\"name\":\"").append(jsonEscape(name))
                        .append("\",\"path\":\"").append(jsonEscape(relative)).append('\"');
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    json.append(",\"type\":\"directory\",\"children\":[");
                    appendChildren(json, child, depth + 1, count);
                    json.append("]}");
                } else {
                    json.append(",\"type\":\"file\",\"editable\":")
                            .append(isEditableExtension(child)).append('}');
                }
            }
        }
    }

    private static Path resolveEntry(String rawPath) {
        if (rawPath.isBlank()) {
            throw new WorkspaceException("A workspace path is required");
        }
        try {
            Path relative = Path.of(rawPath.replace('\\', '/')).normalize();
            if (relative.isAbsolute() || relative.startsWith("..") || relative.toString().isBlank()) {
                throw new WorkspaceException("Path must stay inside the editor workspace");
            }
            if (relative.getNameCount() == 1 && relative.getFileName().toString().equals(INITIALIZED_FILE)) {
                throw new WorkspaceException("That workspace path is reserved");
            }
            Path resolved = ROOT.resolve(relative).normalize();
            if (!resolved.startsWith(ROOT)) {
                throw new WorkspaceException("Path must stay inside the editor workspace");
            }
            rejectSymbolicLinks(resolved);
            return resolved;
        } catch (InvalidPathException exception) {
            throw new WorkspaceException("Invalid workspace path");
        }
    }

    private static void rejectSymbolicLinks(Path path) {
        Path current = ROOT;
        for (Path segment : ROOT.relativize(path)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new WorkspaceException("Symbolic links are not supported");
            }
        }
    }

    private static void requireEditableFile(Path file) throws IOException {
        requireExistingEntry(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException("Path is not a file");
        }
        requireEditableExtension(file);
    }

    private static void requireEditableExtension(Path file) {
        if (!isEditableExtension(file)) {
            throw new WorkspaceException("Supported text files: " + String.join(", ", EDITABLE_EXTENSIONS));
        }
    }

    private static boolean isEditableExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && EDITABLE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static void requireExistingEntry(Path path) throws NoSuchFileException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(relativePath(path));
        }
    }

    private static void requireExistingParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException("Parent folder does not exist");
        }
    }

    private static String requiredQuery(Context context, String name) {
        String value = context.queryParam(name);
        if (value == null) {
            throw new WorkspaceException("Missing query parameter: " + name);
        }
        return value;
    }

    private static String relativePath(Path path) {
        return ROOT.relativize(path).toString().replace('\\', '/');
    }

    private static void seedExamples() throws IOException {
        Path scripts = createSeedDirectory("scripts");
        Path data = createSeedDirectory("data");
        writeExample(data.resolve("example.json"), """
                {
                  "type": "minecraft:crafting_shapeless",
                  "ingredients": [
                    { "item": "minecraft:coal" },
                    { "item": "minecraft:coal" }
                  ],
                  "result": {
                    "id": "minecraft:diamond",
                    "count": 2
                  }
                }
                """);
    }

    private static Path createSeedDirectory(String name) throws IOException {
        Path directory = resolveEntry(name);
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(directory);
        } else if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Workspace seed path is not a directory: " + name);
        }
        return directory;
    }

    private static void writeExample(Path path, String content) throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
    }

    private static void handle(Context context, WorkspaceAction action) {
        try {
            action.run();
        } catch (WorkspaceException exception) {
            error(context, HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (NoSuchFileException exception) {
            error(context, HttpStatus.NOT_FOUND, "Workspace entry was not found");
        } catch (FileAlreadyExistsException exception) {
            error(context, HttpStatus.CONFLICT, "A workspace entry already exists at that path");
        } catch (DirectoryNotEmptyException exception) {
            error(context, HttpStatus.CONFLICT, "Folder is not empty");
        } catch (IOException exception) {
            error(context, HttpStatus.INTERNAL_SERVER_ERROR, "Workspace operation failed");
        }
    }

    private static void success(Context context, String operation, String path) {
        context.contentType("application/json").result("{\"operation\":\"" + jsonEscape(operation)
                + "\",\"path\":\"" + jsonEscape(path) + "\"}");
    }

    private static void error(Context context, HttpStatus status, String message) {
        context.status(status).contentType("application/json")
                .result("{\"error\":\"" + jsonEscape(message) + "\"}");
    }

    private static String jsonEscape(String value) {
        return getEscapedString(value);
    }

    @NonNull
    public static String getEscapedString(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append("\\u%04x".formatted((int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    @FunctionalInterface
    private interface WorkspaceAction {
        void run() throws IOException;
    }

    public static class WorkspaceException extends RuntimeException {
        public WorkspaceException(String message) {
            super(message);
        }
    }
}
