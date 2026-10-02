package com.epherical.datapackeditor.impl;

import com.epherical.datapackeditor.CommonPlatform;
import com.epherical.datapackeditor.api.schema.CodecJsonSchema;
import com.epherical.datapackeditor.api.schema.DataPackSchemaRegistration;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.minecraft.world.level.storage.loot.Validatable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class DataPackSchemaImpl {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String REGISTRY_DESCRIPTION = "Generated from Minecrafts registry data codec and datapack registries.";
    private static final Map<String, List<DataPackSchemaRegistration>> REGISTERED_SCHEMAS = new ConcurrentHashMap<>();

    public static void register(DataPackSchemaRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        String directory = validateDirectory(registration.directory());
        if (registration.codec() == null
                && !isDefaultDirectory(directory)
                && !REGISTERED_SCHEMAS.containsKey(directory)) {
            throw new IllegalArgumentException("No codec or automatically discovered datapack schema for: "
                    + directory);
        }
        REGISTERED_SCHEMAS.merge(directory, List.of(registration), (existing, added) -> {
            List<DataPackSchemaRegistration> combined = new ArrayList<>(existing);
            combined.addAll(added);
            return List.copyOf(combined);
        });
    }

    public static String build(RegistryAccess registries) {
        return GSON.toJson(buildResponse(registries));
    }

    /** Resource directories registered by addons, excluding exact-file registrations. */
    public static List<String> registeredDirectories() {
        return REGISTERED_SCHEMAS.entrySet().stream()
                .filter(entry -> entry.getValue().stream().allMatch(registration -> registration.fileMatches().isEmpty()))
                .map(Map.Entry::getKey).sorted().toList();
    }

    public static JsonObject buildResponse(RegistryAccess registries) {
        Map<String, JsonObject> registrations = new LinkedHashMap<>();
        registrations.put("advancement", advancementRegistration(registries));
        datapackRegistries().forEach(data -> addRegistry(registrations, registries, data));
        RegistryDataLoader.DIMENSION_REGISTRIES.forEach(data -> addRegistry(registrations, registries, data));
        LootDataType.values().forEach(data -> addLootRegistry(registrations, registries, data));
        if (CommonPlatform.platform != null) CommonPlatform.platform.datapackSchemas().forEach(registration ->
                applyRegisteredSchema(registrations, registries, registration.directory(), List.of(registration)));
        REGISTERED_SCHEMAS.forEach((directory, registered) ->
                applyRegisteredSchema(registrations, registries, directory, registered));

        JsonArray schemas = new JsonArray();
        registrations.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .forEach(schemas::add);
        JsonObject response = new JsonObject();
        response.add("schemas", schemas);
        return response;
    }

    private static JsonObject advancementRegistration(RegistryAccess registries) {
        JsonObject schema = CodecJsonSchemaImpl.create(Advancement.CODEC, registries);
        schema.addProperty("title", "Minecraft advancement");
        schema.addProperty("description", "Generated from Advancement.CODEC and the running game's registries.");
        return registration("advancement", schema, true);
    }

    private static <T> void addRegistry(Map<String, JsonObject> registrations,
                                        RegistryAccess registries,
                                        RegistryDataLoader.RegistryData<T> data) {
        ResourceKey<? extends Registry<T>> key = data.key();
        String directory = Registries.elementsDirPath(key);
        JsonObject schema = schema(registries, data.elementCodec(), key);
        registrations.put(directory, registration(directory, schema, false));
    }

    private static <T extends Validatable> void addLootRegistry(
            Map<String, JsonObject> registrations,
            RegistryAccess registries,
            LootDataType<T> data) {
        ResourceKey<? extends Registry<T>> key = data.registryKey();
        String directory = Registries.elementsDirPath(key);
        JsonObject schema = schema(registries, data.codec(), key);
        registrations.put(directory, registration(directory, schema, false));
    }

    private static JsonObject schema(RegistryAccess registries,
                                     Codec<?> codec,
                                     ResourceKey<? extends Registry<?>> key) {
        CodecJsonSchema.Builder builder = CodecJsonSchemaImpl.builder(registries);
        if (CommonPlatform.platform != null) CommonPlatform.platform.configureDatapackSchema(builder, codec);
        if (key.equals(Registries.TRIAL_SPAWNER_CONFIG)) {
            builder.register(CompoundTag.CODEC, trialSpawnerEntityTagSchema(registries));
        }
        JsonObject schema = CodecJsonSchemaImpl.create(builder, codec);
        schema.addProperty("title", key.identifier() + " datapack entry");
        schema.addProperty("description", REGISTRY_DESCRIPTION);
        return schema;
    }

    private static JsonObject trialSpawnerEntityTagSchema(RegistryAccess registries) {
        JsonObject entityId = new JsonObject();
        entityId.addProperty("type", "string");
        JsonArray entityTypes = new JsonArray();
        registries.lookup(Registries.ENTITY_TYPE).ifPresent(lookup -> lookup.listElementIds()
                .map(ResourceKey::identifier)
                .map(Object::toString)
                .sorted()
                .forEach(entityTypes::add));
        if (!entityTypes.isEmpty()) entityId.add("enum", entityTypes);

        JsonObject properties = new JsonObject();
        properties.add("id", entityId);
        JsonObject entityTag = new JsonObject();
        entityTag.addProperty("type", "object");
        entityTag.add("properties", properties);
        entityTag.addProperty("additionalProperties", true);
        return entityTag;
    }

    private static void applyRegisteredSchema(Map<String, JsonObject> registrations,
                                              RegistryAccess registries,
                                              String directory,
                                              List<DataPackSchemaRegistration> registered) {
        JsonObject existing = registrations.get(directory);
        CodecJsonSchema.Builder schemaBuilder = CodecJsonSchemaImpl.builder(registries);
        Codec<?> codec = null;
        String title = null;
        String description = null;
        List<String> fileMatches = List.of();
        for (DataPackSchemaRegistration registration : registered) {
            if (registration.codec() != null) codec = registration.codec();
            if (registration.title() != null) title = registration.title();
            if (registration.description() != null) description = registration.description();
            if (!registration.fileMatches().isEmpty()) fileMatches = registration.fileMatches();
            registration.configure(schemaBuilder);
        }

        JsonObject schema;
        if (codec != null) {
            schema = CodecJsonSchemaImpl.create(schemaBuilder, codec);
            schema.addProperty("title", existing == null
                    ? directory + " datapack entry"
                    : existing.getAsJsonObject("schema").get("title").getAsString());
            schema.addProperty("description", existing == null
                    ? REGISTRY_DESCRIPTION
                    : existing.getAsJsonObject("schema").get("description").getAsString());
        } else {
            schema = CodecJsonSchemaImpl.enhance(schemaBuilder, existing.getAsJsonObject("schema"));
        }
        if (title != null) schema.addProperty("title", title);
        if (description != null) schema.addProperty("description", description);
        JsonObject result = registration(directory, schema, directory.equals("advancement"));
        if (!fileMatches.isEmpty()) {
            JsonArray matches = new JsonArray();
            fileMatches.forEach(matches::add);
            result.add("fileMatch", matches);
        } else if (existing != null) {
            result.add("fileMatch", existing.get("fileMatch").deepCopy());
        }
        registrations.put(directory, result);
    }

    private static JsonObject registration(String directory, JsonObject schema, boolean includeLegacyPlural) {
        JsonArray fileMatch = new JsonArray();
        addFileMatches(fileMatch, directory);
        if (includeLegacyPlural) addFileMatches(fileMatch, directory + "s");

        JsonObject registration = new JsonObject();
        registration.addProperty("uri", "datapack-editor://schemas/datapack/" + directory.replace('/', '_') + ".json");
        registration.add("fileMatch", fileMatch);
        registration.add("schema", schema);
        return registration;
    }

    private static void addFileMatches(JsonArray fileMatch, String directory) {
        fileMatch.add("file:///**/data/*/" + directory + "/*.json");
        fileMatch.add("file:///**/data/*/" + directory + "/**/*.json");
    }

    private static String validateDirectory(String directory) {
        String normalized = Objects.requireNonNull(directory, "directory").replace('\\', '/');
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.endsWith("/")
                || normalized.contains("//") || normalized.contains("..")
                || normalized.contains("*") || normalized.contains("?")) {
            throw new IllegalArgumentException("Invalid datapack resource directory: " + directory);
        }
        return normalized;
    }

    private static boolean isDefaultDirectory(String directory) {
        if (directory.equals("advancement")) return true;
        return datapackRegistries().stream()
                .anyMatch(data -> Registries.elementsDirPath(data.key()).equals(directory))
                || RegistryDataLoader.DIMENSION_REGISTRIES.stream()
                .anyMatch(data -> Registries.elementsDirPath(data.key()).equals(directory))
                || LootDataType.values()
                .anyMatch(data -> Registries.elementsDirPath(data.registryKey()).equals(directory));
    }

    private static List<RegistryDataLoader.RegistryData<?>> datapackRegistries() {
        return CommonPlatform.platform == null ? RegistryDataLoader.WORLDGEN_REGISTRIES
                : CommonPlatform.platform.datapackRegistries();
    }
}
