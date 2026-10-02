package com.epherical.datapackeditor.editor;

import com.epherical.datapackeditor.editor.EditorWorkspace.WorkspaceException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.stream.Stream;

public class EditorDatapackExport {
    public static final String DIRECTORY_NAME = "workspace-exported-datapacks";
    private static final String METADATA_FILE = "pack.mcmeta";

    public static Path export(Path workspace, String name, String description)
            throws IOException {
        String filename = zipName(name);
        if (Files.isSymbolicLink(workspace) || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException("The workspace must be a directory without symbolic links");
        }
        List<Path> entries;
        try (Stream<Path> paths = Files.walk(workspace)) {
            entries = paths.filter(path -> !path.equals(workspace))
                    .filter(path -> !isInternalFile(path)).sorted().toList();
        }
        for (Path entry : entries) {
            if (Files.isSymbolicLink(entry) || (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))) {
                throw new WorkspaceException("Datapack exports cannot contain symbolic links or special files");
            }
        }
        Path directory = workspace.resolveSibling(DIRECTORY_NAME);
        if (Files.isSymbolicLink(directory)) {
            throw new WorkspaceException("The export directory cannot be a symbolic link");
        }
        Files.createDirectories(directory);
        Path target = directory.resolve(filename);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException("An exported datapack already exists with that name. Choose another name.");
        }
        Path temporary = Files.createTempFile(directory, ".datapack-editor-export-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING);
                 ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                zip.putNextEntry(new ZipEntry(METADATA_FILE));
                zip.write(metadata(description).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                for (Path entry : entries) {
                    String relative = workspace.relativize(entry).toString().replace('\\', '/');
                    if (relative.equals(METADATA_FILE)) continue;
                    boolean folder = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
                    zip.putNextEntry(new ZipEntry(relative + (folder ? "/" : "")));
                    if (!folder) {
                        try (var input = Files.newInputStream(entry, LinkOption.NOFOLLOW_LINKS)) {
                            input.transferTo(zip);
                        }
                    }
                    zip.closeEntry();
                }
            }
            Files.move(temporary, target);
            return target.toAbsolutePath().normalize();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean isInternalFile(Path path) {
        String name = path.getFileName().toString();
        return name.equals(".datapack-editor-initialized")
                || (name.startsWith(".datapack-editor-save-") || name.startsWith(".datapack-editor-override-"))
                && name.endsWith(".tmp");
    }

    private static String zipName(String name) {
        String stem = name.trim();
        if (stem.toLowerCase(Locale.ROOT).endsWith(".zip")) stem = stem.substring(0, stem.length() - 4);
        if (stem.isBlank() || stem.contains("/") || stem.contains("\\")) {
            throw new WorkspaceException("Enter a ZIP filename without path separators");
        }
        return stem + ".zip";
    }

    private static String metadata(String description) {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", description);
        JsonArray version = new JsonArray();
        version.add(101);
        version.add(1);
        pack.add("min_format", version);
        pack.add("max_format", version.deepCopy());
        JsonObject metadata = new JsonObject();
        metadata.add("pack", pack);
        return metadata + "\n";
    }
}
