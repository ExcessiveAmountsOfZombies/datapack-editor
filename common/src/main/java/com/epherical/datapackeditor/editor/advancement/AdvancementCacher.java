package com.epherical.datapackeditor.editor.advancement;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.epherical.datapackeditor.editor.MemoryChecker;
import com.epherical.datapackeditor.editor.MemoryChecker.CapacityException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import net.minecraft.server.packs.resources.Resource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class AdvancementCacher {

    private final Map<String, Resource> resources;
    private final List<String> paths;
    private List<Node> indexed;
    private String viewKey;
    private View cached;

    public AdvancementCacher(Map<String, Resource> resources, List<String> paths) {
        this.resources = resources;
        this.paths = paths;
    }

    public synchronized JsonObject page(JsonObject request, int cursor, int limit) throws IOException {
        MemoryChecker.checkpoint();
        if (indexed == null) index();
        String activePath = string(request, DatapackEditorConstants.JSON_PATH);
        JsonArray overrides = request.has(DatapackEditorConstants.JSON_OVERRIDES) ? request.getAsJsonArray(DatapackEditorConstants.JSON_OVERRIDES) : new JsonArray();
        if (overrides.size() > DatapackEditorConstants.ADVANCEMENT_MAX_OVERRIDES)
            throw new CapacityException("Preview supports at most " + DatapackEditorConstants.ADVANCEMENT_MAX_OVERRIDES + " workspace/open advancement overrides");
        if (activePath.length() > DatapackEditorConstants.ADVANCEMENT_MAX_ID_CHARS || overrides.toString().length() > 131_072)
            throw new IllegalArgumentException("Oversized advancement lookup request");
        String key = activePath + "\n" + overrides;
        if (!key.equals(viewKey)) {
            cached = view(activePath, overrides);
            viewKey = key;
        }
        String root = string(request, DatapackEditorConstants.JSON_ROOT);
        if (!cached.groups.containsKey(root)) root = cached.rootById.get(id(activePath));
        if (!cached.groups.containsKey(root)) root = cached.roots.isEmpty() ? null : cached.roots.getFirst().id;
        List<Node> nodes = root == null ? List.of() : cached.groups.get(root);
        if (cursor < 0 || cursor > nodes.size() || limit < 1 || limit > DatapackEditorConstants.ADVANCEMENT_PAGE_SIZE)
            throw new IllegalArgumentException("Invalid tree cursor or limit (1–" + DatapackEditorConstants.ADVANCEMENT_PAGE_SIZE + ")");
        JsonObject result = new JsonObject();
        JsonArray roots = new JsonArray();
        for (Node node : cached.roots) {
            JsonObject entry = new JsonObject();
            entry.addProperty(DatapackEditorConstants.JSON_ID, node.id);
            entry.addProperty(DatapackEditorConstants.JSON_PATH, node.path);
            roots.add(entry);
        }
        result.add(DatapackEditorConstants.JSON_ROOTS, roots);
        if (nodes.size() > DatapackEditorConstants.ADVANCEMENT_TREE_MAX_NODES)
            throw new SelectionException("This advancement tree exceeds the " + DatapackEditorConstants.ADVANCEMENT_TREE_MAX_NODES + "-node preview limit. Open individual JSON files instead.", roots);
        result.addProperty(DatapackEditorConstants.JSON_ROOT, root);
        result.addProperty(DatapackEditorConstants.JSON_COUNT, nodes.size());
        result.addProperty("unavailable", cached.unavailable);
        JsonArray files = new JsonArray();
        int end = Math.min(nodes.size(), cursor + limit);
        for (int index = cursor; index < end; index++) {
            MemoryChecker.checkpoint();
            Node node = nodes.get(index);
            Resource resource = resources.get(node.path);
            if (resource == null) continue; // Workspace definitions are already held by the client.
            try (InputStreamReader reader = reader(resource)) {
                JsonObject file = new JsonObject();
                file.addProperty(DatapackEditorConstants.JSON_PATH, node.path);
                file.addProperty(DatapackEditorConstants.JSON_CONTENT, summary(reader));
                files.add(file);
            } catch (CapacityException exception) {
                MemoryChecker.requireRunning();
                throw exception;
            } catch (IOException | IllegalStateException exception) {
                throw new IOException("Cannot preview advancement " + node.path, exception);
            }
        }
        result.add(DatapackEditorConstants.JSON_FILES, files);
        if (end < nodes.size()) result.addProperty(DatapackEditorConstants.JSON_NEXT_CURSOR, end);
        else result.add(DatapackEditorConstants.JSON_NEXT_CURSOR, null);
        return result;
    }

    private void index() {
        if (paths.size() > DatapackEditorConstants.ADVANCEMENT_MAX_DEFINITIONS) throw new CapacityException("Advancement lookup exceeds its " + DatapackEditorConstants.ADVANCEMENT_MAX_DEFINITIONS + "-definition limit");
        List<Node> nodes = new ArrayList<>();
        long characters = 0;
        for (String path : paths) {
            MemoryChecker.checkpoint();
            try (InputStreamReader source = reader(resources.get(path))) {
                JsonReader reader = new JsonReader(source);
                String parent = null;
                boolean display = false;
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (name.equals(DatapackEditorConstants.JSON_PARENT) && reader.peek() == JsonToken.STRING)
                        parent = reader.nextString();
                    else {
                        if (name.equals(DatapackEditorConstants.JSON_DISPLAY))
                            display = reader.peek() == JsonToken.BEGIN_OBJECT;
                        reader.skipValue();
                    }
                }
                reader.endObject();
                if (reader.peek() != JsonToken.END_DOCUMENT)
                    throw new IOException(DatapackEditorConstants.MESSAGE_TRAILING_JSON);
                if (path.length() > DatapackEditorConstants.ADVANCEMENT_MAX_ID_CHARS || (parent != null && parent.length() > DatapackEditorConstants.ADVANCEMENT_MAX_ID_CHARS))
                    throw new IOException("Oversized advancement ID");
                Node node = new Node(id(path), path, normalize(parent), display);
                characters += node.id.length() + path.length() + (parent == null ? 0 : parent.length());
                if (characters > DatapackEditorConstants.ADVANCEMENT_METADATA_MAX_CHARS)
                    throw new CapacityException("Advancement lookup exceeds its metadata budget");
                nodes.add(node);
            } catch (CapacityException exception) {
                MemoryChecker.requireRunning();
                // reject oversized files
                if (characters > DatapackEditorConstants.ADVANCEMENT_METADATA_MAX_CHARS) throw exception;
            } catch (IOException | IllegalStateException _) {
            }
        }
        indexed = nodes;
    }

    private View view(String activePath, JsonArray overrides) {
        Map<String, Node> chosen = new LinkedHashMap<>();
        Map<String, Integer> priorities = new HashMap<>();
        String activePack = activePath.contains(DatapackEditorConstants.PATH_DATA_SEGMENT) ? activePath.substring(0, activePath.indexOf(DatapackEditorConstants.PATH_DATA_SEGMENT)) : "";
        List<Node> candidates = new ArrayList<>(indexed);
        for (JsonElement value : overrides) {
            JsonObject entry = value.getAsJsonObject();
            String path = string(entry, DatapackEditorConstants.JSON_PATH);
            String parent = string(entry, DatapackEditorConstants.JSON_PARENT);
            if (path.length() > DatapackEditorConstants.ADVANCEMENT_MAX_ID_CHARS || parent.length() > DatapackEditorConstants.ADVANCEMENT_MAX_ID_CHARS || id(path) == null)
                throw new IllegalArgumentException("Invalid advancement override");
            candidates.add(new Node(id(path), path, normalize(parent.isEmpty() ? null : parent), entry.has(DatapackEditorConstants.JSON_DISPLAY) && entry.get(DatapackEditorConstants.JSON_DISPLAY).getAsBoolean()));
        }
        for (Node node : candidates) {
            MemoryChecker.requireRunning();
            int priority = node.path.equals(activePath) ? 3 : node.path.startsWith(DatapackEditorConstants.PATH_DATA_PREFIX) ? 2
                    : !activePack.isEmpty() && node.path.startsWith(activePack + DatapackEditorConstants.PATH_DATA_SEGMENT) ? 1 : 0;
            int previous = priorities.getOrDefault(node.id, -1);
            if (priority > previous || (priority > 0 && priority == previous)
                    || node.path.equals(chosen.containsKey(node.id) ? chosen.get(node.id).path : null)) {
                chosen.put(node.id, node);
                priorities.put(node.id, priority);
            }
        }
        Map<String, String> rootById = new HashMap<>();
        Map<String, List<Node>> groups = new HashMap<>();
        for (Node node : chosen.values()) {
            List<Node> chain = new ArrayList<>();
            HashSet<String> visited = new HashSet<>();
            Node current = node;
            String root = null;
            while (current != null) {
                if (rootById.containsKey(current.id)) {
                    root = rootById.get(current.id);
                    break;
                }
                if (!visited.add(current.id)) break;
                chain.add(current);
                Node parent = current.parent == null ? null : chosen.get(current.parent);
                if (parent == null) {
                    root = current.display ? current.id : null;
                    break;
                }
                current = parent;
            }
            for (Node ancestor : chain) rootById.put(ancestor.id, root);
            if (root != null) groups.computeIfAbsent(root, ignored -> new ArrayList<>()).add(node);
        }
        List<Node> roots = groups.keySet().stream().map(chosen::get).sorted(Comparator.comparing(Node::id)).toList();
        if (roots.size() > DatapackEditorConstants.ADVANCEMENT_TREE_MAX_ROOTS) throw new CapacityException("Advancement lookup exceeds its " + DatapackEditorConstants.ADVANCEMENT_TREE_MAX_ROOTS + "-root preview limit");
        return new View(roots, groups, rootById, paths.size() - indexed.size());
    }

    // now this should just provide a smaller sample of an advancement file, but with the other memory optimizations that were done
    // im not sure i really need this anymore
    public static String summary(Reader source) throws IOException {
        JsonReader reader = new JsonReader(source);
        StringWriter text = new StringWriter();
        JsonWriter writer = new JsonWriter(new LimitedWriter(text, DatapackEditorConstants.TEXT_CHUNK_CHARS));
        writer.beginObject();
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case DatapackEditorConstants.JSON_PARENT -> {
                    if (reader.peek() == JsonToken.STRING)
                        writer.name(DatapackEditorConstants.JSON_PARENT).value(reader.nextString());
                    else reader.skipValue();
                }
                case DatapackEditorConstants.JSON_DISPLAY -> {
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        writer.name(DatapackEditorConstants.JSON_DISPLAY);
                        display(reader, writer);
                    } else reader.skipValue();
                }
                case DatapackEditorConstants.JSON_CRITERIA -> {
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        writer.name(DatapackEditorConstants.JSON_CRITERIA);
                        criteria(reader, writer);
                    } else reader.skipValue();
                }
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing advancement JSON");
        writer.endObject();
        writer.flush();
        return text.toString();
    }

    private static void display(JsonReader reader, JsonWriter writer) throws IOException {
        writer.beginObject();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            switch (name) {
                case DatapackEditorConstants.JSON_TITLE, DatapackEditorConstants.JSON_DESCRIPTION, "icon", "background",
                     "frame", "show_toast", "announce_to_chat", "hidden" -> {
                    writer.name(name);
                    copy(reader, writer, 0, new int[]{0});
                }
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        writer.endObject();
    }

    private static void criteria(JsonReader reader, JsonWriter writer) throws IOException {
        writer.beginObject();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue();
                continue;
            }
            String trigger = null;
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (field.equals(DatapackEditorConstants.JSON_TRIGGER) && reader.peek() == JsonToken.STRING) {
                    trigger = reader.nextString();
                } else reader.skipValue();
            }
            reader.endObject();
            if (trigger != null)
                writer.name(name).beginObject().name(DatapackEditorConstants.JSON_TRIGGER).value(trigger).endObject();
        }
        reader.endObject();
        writer.endObject();
    }

    private static void copy(JsonReader reader, JsonWriter writer, int depth, int[] values) throws IOException {
        MemoryChecker.requireRunning();
        if (depth > DatapackEditorConstants.ADVANCEMENT_SUMMARY_MAX_DEPTH || ++values[0] > DatapackEditorConstants.ADVANCEMENT_SUMMARY_MAX_VALUES)
            throw new CapacityException("Advancement display is too complex for a safe preview (depth " + DatapackEditorConstants.ADVANCEMENT_SUMMARY_MAX_DEPTH + ", values " + DatapackEditorConstants.ADVANCEMENT_SUMMARY_MAX_VALUES + ")");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                writer.beginObject();
                while (reader.hasNext()) {
                    writer.name(reader.nextName());
                    copy(reader, writer, depth + 1, values);
                }
                reader.endObject();
                writer.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                writer.beginArray();
                while (reader.hasNext()) copy(reader, writer, depth + 1, values);
                reader.endArray();
                writer.endArray();
            }
            case STRING -> writer.value(reader.nextString());
            case NUMBER -> writer.jsonValue(reader.nextString());
            case BOOLEAN -> writer.value(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                writer.nullValue();
            }
            default -> throw new IOException("Invalid advancement display JSON");
        }
    }

    private static class LimitedWriter extends Writer {
        private final Writer delegate;
        private final int limit;
        private int written;

        public LimitedWriter(Writer delegate, int limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public void write(char[] characters, int offset, int length) throws IOException {
            MemoryChecker.requireRunning();
            if (length > limit - written) throw new CapacityException("Advancement preview exceeds its text budget");
            written += length;
            delegate.write(characters, offset, length);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static InputStreamReader reader(Resource resource) throws IOException {
        return new InputStreamReader(MemoryChecker.input(resource.open(), DatapackEditorConstants.EDITOR_JSON_MAX_BYTES,
                DatapackEditorConstants.MESSAGE_ADVANCEMENT_JSON), StandardCharsets.UTF_8);
    }

    private static String string(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
    }

    private static String normalize(String id) {
        return id == null || id.contains(":") ? id : "minecraft:" + id;
    }

    private static String id(String path) {
        int data = path.startsWith(DatapackEditorConstants.PATH_DATA_PREFIX) ? 0 : path.indexOf(DatapackEditorConstants.PATH_DATA_SEGMENT) + 1;
        if (!path.endsWith(DatapackEditorConstants.PATH_JSON_EXTENSION)) return null;
        String[] parts = path.substring(data).split(DatapackEditorConstants.PATH_SEPARATOR, DatapackEditorConstants.ADVANCEMENT_RESOURCE_PATH_PARTS);
        return parts.length == DatapackEditorConstants.ADVANCEMENT_RESOURCE_PATH_PARTS && parts[0].equals(DatapackEditorConstants.PATH_DATA)
                && (parts[2].equals(DatapackEditorConstants.JSON_ADVANCEMENT)
                || parts[2].equals("advancements"))
                ? parts[1] + ":" + parts[3].substring(0, parts[3].length() - DatapackEditorConstants.PATH_JSON_EXTENSION.length()) : null;
    }

    private record Node(String id, String path, String parent, boolean display) {
    }

    private record View(List<Node> roots, Map<String, List<Node>> groups, Map<String, String> rootById,
                        int unavailable) {
    }

    public static class SelectionException extends CapacityException {
        private final JsonArray roots;

        public SelectionException(String message, JsonArray roots) {
            super(message);
            this.roots = roots;
        }

        public JsonArray roots() {
            return roots;
        }
    }
}
