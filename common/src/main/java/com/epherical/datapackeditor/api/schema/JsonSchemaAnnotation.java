package com.epherical.datapackeditor.api.schema;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * descriptions and editor-specific keywords that can be added to an inferred
 * JSON schema, property paths use slash-separated property names, for example
 * {@code display/title}. Annotations affect the generated schema only, they do not change codec decoding.
 */
public class JsonSchemaAnnotation {

    private final JsonObject keywords;

    public JsonSchemaAnnotation(JsonObject keywords) {
        this.keywords = keywords.deepCopy();
    }

    /**
     * Starts an annotation containing JSON Schema or Monaco metadata.
     * @return a new annotation builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Adds this annotation to every schema property matching {@code propertyPath}.
     * References, alternatives, and array item schemas are followed automatically.
     * An empty path annotates the schema root.
     *
     * @param schema complete root schema to modify in place
     * @param propertyPath slash-separated property names, optionally starting with {@code /}
     * @return the number of schema locations that were annotated
     */
    public int apply(JsonObject schema, String propertyPath) {
        Objects.requireNonNull(schema, DatapackEditorConstants.JSON_SCHEMA);
        List<String> path = parsePath(propertyPath);
        if (path.isEmpty()) {
            applyKeywords(schema);
            return 1;
        }

        Set<JsonObject> current = identitySet();
        expand(schema, schema, current, identitySet());
        for (int index = 0; index < path.size(); index++) {
            String property = path.get(index);
            boolean last = index == path.size() - 1;
            Set<JsonObject> next = identitySet();
            Set<JsonObject> annotated = identitySet();
            for (JsonObject container : current) {
                if (!container.has(DatapackEditorConstants.JSON_PROPERTIES) || !container.get(DatapackEditorConstants.JSON_PROPERTIES).isJsonObject()) continue;
                JsonObject properties = container.getAsJsonObject(DatapackEditorConstants.JSON_PROPERTIES);
                JsonElement child = properties.get(property);
                if (child == null) continue;
                if (last) {
                    JsonObject target = annotatable(child);
                    if (target != child) properties.add(property, target);
                    if (annotated.add(target)) applyKeywords(target);
                } else {
                    expand(schema, child, next, identitySet());
                }
            }
            if (last) return annotated.size();
            current = next;
            if (current.isEmpty()) return 0;
        }
        return 0;
    }

    private void applyKeywords(JsonObject target) {
        keywords.entrySet().forEach(entry -> target.add(entry.getKey(), entry.getValue().deepCopy()));
    }

    private static JsonObject annotatable(JsonElement schema) {
        if (schema.isJsonObject() && !schema.getAsJsonObject().has(DatapackEditorConstants.SCHEMA_REF)) return schema.getAsJsonObject();
        JsonObject wrapper = new JsonObject();
        JsonArray allOf = new JsonArray();
        allOf.add(schema.deepCopy());
        wrapper.add(DatapackEditorConstants.SCHEMA_ALL_OF, allOf);
        return wrapper;
    }

    private static void expand(JsonObject root,
                               JsonElement element,
                               Set<JsonObject> output,
                               Set<JsonObject> visited) {
        expand(root, element, output, visited, new HashSet<>());
    }

    private static void expand(JsonObject root, JsonElement element, Set<JsonObject> output,
                               Set<JsonObject> visited, Set<String> resolving) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> expand(root, child, output, visited, resolving));
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        if (!visited.add(object)) return;
        output.add(object);

        if (object.has(DatapackEditorConstants.SCHEMA_REF) && object.get(DatapackEditorConstants.SCHEMA_REF).isJsonPrimitive()) {
            String reference = object.get(DatapackEditorConstants.SCHEMA_REF).getAsString();
            if (reference.startsWith(DatapackEditorConstants.SCHEMA_DEFINITIONS_PREFIX)
                    && root.has(DatapackEditorConstants.SCHEMA_DEFINITIONS) && root.get(DatapackEditorConstants.SCHEMA_DEFINITIONS).isJsonObject()) {
                JsonObject definitions = root.getAsJsonObject(DatapackEditorConstants.SCHEMA_DEFINITIONS);
                JsonElement body = definitions.get(unescape(reference.substring(DatapackEditorConstants.SCHEMA_DEFINITIONS_PREFIX.length())));
                if (body != null && resolving.add(reference)) {
                    // Detach only the finite body on this path. Its children stay
                    // references, and annotations cannot change other codec uses.
                    String name = "annotation_" + definitions.size();
                    while (definitions.has(name)) name += "_";
                    JsonElement owned = body.deepCopy();
                    definitions.add(name, owned);
                    object.addProperty(DatapackEditorConstants.SCHEMA_REF, DatapackEditorConstants.SCHEMA_DEFINITIONS_PREFIX + name);
                    expand(root, owned, output, visited, resolving);
                    resolving.remove(reference);
                }
            }
        }
        for (String composition : List.of(DatapackEditorConstants.SCHEMA_ALL_OF, DatapackEditorConstants.SCHEMA_ANY_OF, DatapackEditorConstants.SCHEMA_ONE_OF)) {
            if (object.has(composition)) expand(root, object.get(composition), output, visited, resolving);
        }
        if (object.has(DatapackEditorConstants.SCHEMA_ITEMS)) expand(root, object.get(DatapackEditorConstants.SCHEMA_ITEMS), output, visited, resolving);
    }

    private static List<String> parsePath(String propertyPath) {
        String path = Objects.requireNonNull(propertyPath, DatapackEditorConstants.SCHEMA_PROPERTY_PATH);
        if (path.isEmpty()) return List.of();
        if (path.startsWith(DatapackEditorConstants.PATH_SEPARATOR)) path = path.substring(1);
        if (path.endsWith(DatapackEditorConstants.PATH_SEPARATOR)) throw new IllegalArgumentException("Property path must not end with '/': " + propertyPath);
        List<String> result = new ArrayList<>();
        for (String part : path.split(DatapackEditorConstants.PATH_SEPARATOR, -1)) {
            if (part.isEmpty()) throw new IllegalArgumentException("Property path contains an empty name: " + propertyPath);
            result.add(unescape(part));
        }
        return result;
    }

    private static String unescape(String value) {
        return value.replace(DatapackEditorConstants.SCHEMA_ESCAPED_SLASH, DatapackEditorConstants.PATH_SEPARATOR).replace(DatapackEditorConstants.SCHEMA_ESCAPED_TILDE, DatapackEditorConstants.SCHEMA_POINTER_TILDE);
    }

    private static Set<JsonObject> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    public static class Builder {
        private final JsonObject keywords = new JsonObject();
        private final JsonArray examples = new JsonArray();

        public Builder() {
        }

        /**
         * Adds the standard JSON Schema title keyword.
         * @param title display title
         * @return this builder
         */
        public Builder title(String title) {
            keywords.addProperty(DatapackEditorConstants.JSON_TITLE, Objects.requireNonNull(title, DatapackEditorConstants.JSON_TITLE));
            return this;
        }

        /**
         * Adds the standard plain-text description keyword.
         * @param description plain-text description
         * @return this builder
         */
        public Builder description(String description) {
            keywords.addProperty(DatapackEditorConstants.JSON_DESCRIPTION, Objects.requireNonNull(description, DatapackEditorConstants.JSON_DESCRIPTION));
            return this;
        }

        /**
         * Adds Monaco's Markdown-capable description extension.
         * @param description Markdown description
         * @return this builder
         */
        public Builder markdownDescription(String description) {
            keywords.addProperty("markdownDescription", Objects.requireNonNull(description, DatapackEditorConstants.JSON_DESCRIPTION));
            return this;
        }

        /**
         * Adds a JSON default value. The element is copied.
         * @param value default JSON value
         * @return this builder
         */
        public Builder defaultValue(JsonElement value) {
            keywords.add(DatapackEditorConstants.SCHEMA_DEFAULT, Objects.requireNonNull(value, DatapackEditorConstants.JSON_VALUE).deepCopy());
            return this;
        }

        /**
         * Adds a string default value.
         * @param value default string
         * @return this builder
         */
        public Builder defaultValue(String value) {
            return defaultValue(new JsonPrimitive(value));
        }

        /**
         * Adds a numeric default value.
         * @param value default number
         * @return this builder
         */
        public Builder defaultValue(Number value) {
            return defaultValue(new JsonPrimitive(value));
        }

        /**
         * Adds a boolean default value.
         * @param value default boolean
         * @return this builder
         */
        public Builder defaultValue(boolean value) {
            return defaultValue(new JsonPrimitive(value));
        }

        /**
         * Appends one JSON example to the {@code examples} keyword. The element is copied.
         * @param value example JSON value
         * @return this builder
         */
        public Builder example(JsonElement value) {
            examples.add(Objects.requireNonNull(value, DatapackEditorConstants.JSON_VALUE).deepCopy());
            return this;
        }

        /**
         * Appends a string example.
         * @param value example string
         * @return this builder
         */
        public Builder example(String value) {
            return example(new JsonPrimitive(value));
        }

        /**
         * Appends a numeric example.
         * @param value example number
         * @return this builder
         */
        public Builder example(Number value) {
            return example(new JsonPrimitive(value));
        }

        /**
         * Appends a boolean example.
         * @param value example boolean
         * @return this builder
         */
        public Builder example(boolean value) {
            return example(new JsonPrimitive(value));
        }

        /**
         * Appends JSON examples in iteration order.
         * @param values example values
         * @return this builder
         */
        public Builder examples(Collection<? extends JsonElement> values) {
            Objects.requireNonNull(values, DatapackEditorConstants.JSON_VALUES).forEach(this::example);
            return this;
        }

        /**
         * Marks the schema location as deprecated for editor tooling.
         * @param deprecated whether the location is deprecated
         * @return this builder
         */
        public Builder deprecated(boolean deprecated) {
            keywords.addProperty("deprecated", deprecated);
            return this;
        }

        /**
         * Adds Monaco's descriptions for values in the schema's {@code enum}, in the same order.
         * @param descriptions descriptions aligned with enum values
         * @return this builder
         */
        public Builder enumDescriptions(Collection<String> descriptions) {
            JsonArray values = new JsonArray();
            Objects.requireNonNull(descriptions, DatapackEditorConstants.JSON_DESCRIPTIONS).forEach(values::add);
            keywords.add("enumDescriptions", values);
            return this;
        }

        /**
         * Adds Monaco's Markdown-capable descriptions for values in the schema's {@code enum}.
         * @param descriptions Markdown descriptions aligned with enum values
         * @return this builder
         */
        public Builder markdownEnumDescriptions(Collection<String> descriptions) {
            JsonArray values = new JsonArray();
            Objects.requireNonNull(descriptions, DatapackEditorConstants.JSON_DESCRIPTIONS).forEach(values::add);
            keywords.add("markdownEnumDescriptions", values);
            return this;
        }

        /**
         * Adds or replaces any JSON Schema or Monaco-specific keyword.
         * @param name keyword name
         * @param value keyword value, copied into the annotation
         * @return this builder
         */
        public Builder keyword(String name, JsonElement value) {
            keywords.add(Objects.requireNonNull(name, DatapackEditorConstants.JSON_NAME),
                    Objects.requireNonNull(value, DatapackEditorConstants.JSON_VALUE).deepCopy());
            return this;
        }

        /**
         * Returns an immutable annotation whose keyword values are copied.
         * @return completed annotation
         */
        public JsonSchemaAnnotation build() {
            if (!examples.isEmpty()) keywords.add("examples", examples.deepCopy());
            return new JsonSchemaAnnotation(keywords);
        }
    }
}
