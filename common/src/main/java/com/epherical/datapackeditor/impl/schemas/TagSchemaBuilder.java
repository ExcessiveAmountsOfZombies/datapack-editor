package com.epherical.datapackeditor.impl.schemas;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.epherical.datapackeditor.impl.CodecJsonSchemaImpl;
import com.epherical.datapackeditor.impl.RegistrySchemaCache;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagFile;
import net.minecraft.util.ExtraCodecs;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.epherical.datapackeditor.DatapackEditorConstants.*;

public class TagSchemaBuilder {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public static String build(RegistryAccess registries) {
        return GSON.toJson(buildResponse(registries));
    }

    public static JsonObject buildResponse(RegistryAccess registries) {
        return buildResponse(registries, null, null);
    }

    public static JsonObject buildResponse(RegistryAccess registries, String selected, RegistrySchemaCache shared) {
        Map<String, TagRegistry> byDirectory = new LinkedHashMap<>();
        registries.registries().filter(entry -> selected == null || selected.equals(Registries.tagsDirPath(entry.key())))
                .forEach(entry -> addRegistry(byDirectory, entry, shared == null));

        JsonArray schemas = new JsonArray();
        byDirectory.values().stream()
                .sorted(Comparator.comparing(TagRegistry::directory))
                .map(registry -> schemaRegistration(registries, registry, shared))
                .forEach(schemas::add);

        JsonObject response = new JsonObject();
        response.add(JSON_SCHEMAS, schemas);
        return response;
    }

    private static <T> void addRegistry(Map<String, TagRegistry> byDirectory,
                                        RegistryAccess.RegistryEntry<T> entry, boolean enumerate) {
        ResourceKey<? extends Registry<T>> key = entry.key();
        String directory = Registries.tagsDirPath(key);
        TagRegistry tagRegistry = byDirectory.computeIfAbsent(directory, TagRegistry::new);
        tagRegistry.registryNames.add(key.identifier().toString());
        tagRegistry.keys.add(key);
        if (!enumerate) return;
        entry.value().listElementIds()
                .map(element -> element.identifier().toString())
                .forEach(tagRegistry.values::add);
        entry.value().listTagIds()
                .map(tag -> MINECRAFT_TAG_PREFIX + tag.location())
                .forEach(tagRegistry.values::add);
    }

    private static JsonObject schemaRegistration(RegistryAccess registries, TagRegistry registry, RegistrySchemaCache shared) {
        JsonObject identifierSchema = identifierSchema(registry.values);
        if (shared != null) {
            JsonArray alternatives = new JsonArray();
            registry.keys.forEach(key -> alternatives.add(shared.reference(key, true)));
            alternatives.add(identifierSchema);
            identifierSchema = new JsonObject();
            identifierSchema.add(SCHEMA_ANY_OF, alternatives);
        }
        JsonObject identifierReference = new JsonObject();
        identifierReference.addProperty(SCHEMA_REF, "#/definitions/tag_value");
        var builder = CodecJsonSchemaImpl.builder(registries)
                .register(ExtraCodecs.TAG_OR_ELEMENT_ID, identifierReference);
        JsonObject schema = CodecJsonSchemaImpl.create(builder, TagFile.CODEC);
        JsonObject definitions = schema.has(SCHEMA_DEFINITIONS)
                ? schema.getAsJsonObject(SCHEMA_DEFINITIONS) : new JsonObject();
        definitions.add("tag_value", identifierSchema);
        schema.add(SCHEMA_DEFINITIONS, definitions);
        schema.addProperty(JSON_TITLE, String.join(", ", registry.registryNames) + " tag");
        schema.addProperty(JSON_DESCRIPTION,
                "Generated from TagFile.CODEC and the running game's registry values and tags.");

        JsonArray fileMatch = new JsonArray();
        fileMatch.add(PATH_DATA_FILE_GLOB_PREFIX + registry.directory + PATH_JSON_FILE_GLOB);
        fileMatch.add(PATH_DATA_FILE_GLOB_PREFIX + registry.directory + PATH_RECURSIVE_JSON_FILE_GLOB);

        JsonObject registration = new JsonObject();
        registration.addProperty(JSON_URI, "datapack-editor://schemas/" + registry.directory.replace('/', '_') + PATH_JSON_EXTENSION);
        registration.add(JSON_FILE_MATCH, fileMatch);
        registration.add(JSON_SCHEMA, schema);
        return registration;
    }

    private static JsonObject identifierSchema(Set<String> values) {
        JsonObject syntax = new JsonObject();
        syntax.addProperty(JSON_TYPE, SCHEMA_STRING);
        syntax.addProperty(SCHEMA_PATTERN, "^#?(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+$");
        if (values.isEmpty()) return syntax;

        JsonArray knownValues = new JsonArray();
        values.stream().sorted().forEach(knownValues::add);
        JsonObject known = new JsonObject();
        known.addProperty(JSON_TYPE, SCHEMA_STRING);
        known.add(SCHEMA_ENUM, knownValues);

        JsonArray alternatives = new JsonArray();
        alternatives.add(known);
        alternatives.add(syntax);
        JsonObject result = new JsonObject();
        result.addProperty(JSON_TYPE, SCHEMA_STRING);
        result.add(SCHEMA_ANY_OF, alternatives);
        return result;
    }

    private static class TagRegistry {
        private final String directory;
        private final Set<String> registryNames = new LinkedHashSet<>();
        private final Set<String> values = new LinkedHashSet<>();
        private final Set<ResourceKey<? extends Registry<?>>> keys = new LinkedHashSet<>();

        public TagRegistry(String directory) {
            this.directory = directory;
        }

        private String directory() {
            return directory;
        }
    }
}
