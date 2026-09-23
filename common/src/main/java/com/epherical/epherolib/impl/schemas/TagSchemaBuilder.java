package com.epherical.epherolib.impl.schemas;

import com.epherical.epherolib.impl.CodecJsonSchemaImpl;
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

public class TagSchemaBuilder {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String IDENTIFIER_PATTERN = "^#?(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+$";
    public static final String DEFINITIONS = "definitions";

    public static String build(RegistryAccess registries) {
        return GSON.toJson(buildResponse(registries));
    }

    public static JsonObject buildResponse(RegistryAccess registries) {
        Map<String, TagRegistry> byDirectory = new LinkedHashMap<>();
        registries.registries().forEach(entry -> addRegistry(byDirectory, entry));

        JsonArray schemas = new JsonArray();
        byDirectory.values().stream()
                .sorted(Comparator.comparing(TagRegistry::directory))
                .map(registry -> schemaRegistration(registries, registry))
                .forEach(schemas::add);

        JsonObject response = new JsonObject();
        response.add("schemas", schemas);
        return response;
    }

    private static <T> void addRegistry(Map<String, TagRegistry> byDirectory,
                                        RegistryAccess.RegistryEntry<T> entry) {
        ResourceKey<? extends Registry<T>> key = entry.key();
        String directory = Registries.tagsDirPath(key);
        TagRegistry tagRegistry = byDirectory.computeIfAbsent(directory, TagRegistry::new);
        tagRegistry.registryNames.add(key.identifier().toString());
        entry.value().listElementIds()
                .map(element -> element.identifier().toString())
                .forEach(tagRegistry.values::add);
        entry.value().listTagIds()
                .map(tag -> "#" + tag.location())
                .forEach(tagRegistry.values::add);
    }

    private static JsonObject schemaRegistration(RegistryAccess registries, TagRegistry registry) {
        JsonObject identifierSchema = identifierSchema(registry.values);
        JsonObject identifierReference = new JsonObject();
        identifierReference.addProperty("$ref", "#/definitions/tag_value");
        var builder = CodecJsonSchemaImpl.builder(registries)
                .register(ExtraCodecs.TAG_OR_ELEMENT_ID, identifierReference);
        JsonObject schema = CodecJsonSchemaImpl.create(builder, TagFile.CODEC);
        JsonObject definitions = schema.has(DEFINITIONS)
                ? schema.getAsJsonObject(DEFINITIONS) : new JsonObject();
        definitions.add("tag_value", identifierSchema);
        schema.add(DEFINITIONS, definitions);
        schema.addProperty("title", String.join(", ", registry.registryNames) + " tag");
        schema.addProperty("description",
                "Generated from TagFile.CODEC and the running game's registry values and tags.");

        JsonArray fileMatch = new JsonArray();
        fileMatch.add("file:///**/data/*/" + registry.directory + "/*.json");
        fileMatch.add("file:///**/data/*/" + registry.directory + "/**/*.json");

        JsonObject registration = new JsonObject();
        registration.addProperty("uri", "epherolib://schemas/" + registry.directory.replace('/', '_') + ".json");
        registration.add("fileMatch", fileMatch);
        registration.add("schema", schema);
        return registration;
    }

    private static JsonObject identifierSchema(Set<String> values) {
        JsonObject syntax = new JsonObject();
        syntax.addProperty("type", "string");
        syntax.addProperty("pattern", IDENTIFIER_PATTERN);
        if (values.isEmpty()) return syntax;

        JsonArray knownValues = new JsonArray();
        values.stream().sorted().forEach(knownValues::add);
        JsonObject known = new JsonObject();
        known.addProperty("type", "string");
        known.add("enum", knownValues);

        JsonArray alternatives = new JsonArray();
        alternatives.add(known);
        alternatives.add(syntax);
        JsonObject result = new JsonObject();
        result.addProperty("type", "string");
        result.add("anyOf", alternatives);
        return result;
    }

    private static class TagRegistry {
        private final String directory;
        private final Set<String> registryNames = new LinkedHashSet<>();
        private final Set<String> values = new LinkedHashSet<>();

        public TagRegistry(String directory) {
            this.directory = directory;
        }

        private String directory() {
            return directory;
        }
    }
}
