package com.epherical.datapackeditor.impl;

import com.epherical.datapackeditor.CommonPlatform;
import com.epherical.datapackeditor.DatapackEditorConstants;
import com.epherical.datapackeditor.api.DataPackRegistration;
import com.epherical.datapackeditor.api.schema.CodecJsonSchema;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
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
import java.util.stream.Collectors;

public class DataPackRegistrationImpl {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Map<String, List<DataPackRegistration>> REGISTERED_DATAPACKS = new ConcurrentHashMap<>();

    public static void register(DataPackRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        REGISTERED_DATAPACKS.compute(registration.id(), (id, existing) -> {
            if (registration.hasSchema() && registration.codec() == null && !isDefaultDirectory(id)
                    && (existing == null || existing.stream().noneMatch(value -> value.codec() != null))
                    && platformResources().stream().noneMatch(value -> value.id().equals(id) && value.codec() != null)) {
                throw new IllegalArgumentException("No codec or automatically discovered datapack schema for: " + id);
            }
            List<DataPackRegistration> combined = new ArrayList<>();
            if (existing != null) combined.addAll(existing);
            combined.add(registration);
            return List.copyOf(combined);
        });
    }

    public static String build(RegistryAccess registries) {
        return GSON.toJson(buildResponse(registries));
    }

    public static List<DataPackRegistration> resources() {
        List<DataPackRegistration> resources = new ArrayList<>(platformResources());
        REGISTERED_DATAPACKS.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> resources.addAll(entry.getValue()));
        return List.copyOf(resources);
    }

    private static List<DataPackRegistration> platformResources() {
        return CommonPlatform.platform == null ? List.of() : CommonPlatform.platform.datapackResources();
    }

    public static List<String> registeredDirectories() {
        return resources().stream().map(DataPackRegistration::directory).filter(Objects::nonNull)
                .distinct().sorted().toList();
    }

    public static List<Identifier> registeredFiles() {
        return resources().stream().map(DataPackRegistration::file).filter(Objects::nonNull)
                .distinct().toList();
    }

    private static Map<String, List<DataPackRegistration>> registeredSchemas() {
        return resources().stream().filter(DataPackRegistration::hasSchema)
                .collect(Collectors.groupingBy(DataPackRegistration::id, LinkedHashMap::new, Collectors.toList()));
    }

    public static JsonObject buildResponse(RegistryAccess registries) {
        return buildResponse(registries, null);
    }

    public static JsonObject buildResponse(RegistryAccess registries, String selected) {
        Map<String, JsonObject> registrations = new LinkedHashMap<>();
        if (selected == null || selected.equals(DatapackEditorConstants.JSON_ADVANCEMENT)) registrations.put(DatapackEditorConstants.JSON_ADVANCEMENT, advancementRegistration(registries));
        datapackRegistries().stream().filter(data -> selected == null || selected.equals(Registries.elementsDirPath(data.key())))
                .forEach(data -> addRegistry(registrations, registries, data));
        RegistryDataLoader.DIMENSION_REGISTRIES.stream().filter(data -> selected == null || selected.equals(Registries.elementsDirPath(data.key())))
                .forEach(data -> addRegistry(registrations, registries, data));
        LootDataType.values().filter(data -> selected == null || selected.equals(Registries.elementsDirPath(data.registryKey())))
                .forEach(data -> addLootRegistry(registrations, registries, data));
        registeredSchemas().forEach((id, registered) -> {
            if (selected == null || selected.equals(id)) applyRegisteredSchema(registrations, registries, id, registered);
        });

        JsonArray schemas = new JsonArray();
        registrations.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .forEach(schemas::add);
        JsonObject response = new JsonObject();
        response.add(DatapackEditorConstants.JSON_SCHEMAS, schemas);
        return response;
    }

    public static Map<String, JsonObject> catalog() {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        result.put(DatapackEditorConstants.JSON_ADVANCEMENT, registration(DatapackEditorConstants.JSON_ADVANCEMENT, null, true));
        datapackRegistries().forEach(data -> result.put(Registries.elementsDirPath(data.key()),
                registration(Registries.elementsDirPath(data.key()), null, false)));
        RegistryDataLoader.DIMENSION_REGISTRIES.forEach(data -> result.put(Registries.elementsDirPath(data.key()),
                registration(Registries.elementsDirPath(data.key()), null, false)));
        LootDataType.values().forEach(data -> result.put(Registries.elementsDirPath(data.registryKey()),
                registration(Registries.elementsDirPath(data.registryKey()), null, false)));
        registeredSchemas().values().forEach(values -> values.forEach(value -> catalogRegistration(result, value)));
        return result;
    }

    private static void catalogRegistration(Map<String, JsonObject> result, DataPackRegistration value) {
        JsonObject entry = result.computeIfAbsent(value.id(), id -> registration(id, null, id.equals(DatapackEditorConstants.JSON_ADVANCEMENT)));
        if (!value.fileMatches().isEmpty()) {
            JsonArray matches = new JsonArray();
            value.fileMatches().forEach(matches::add);
            entry.add(DatapackEditorConstants.JSON_FILE_MATCH, matches);
        }
    }

    private static JsonObject advancementRegistration(RegistryAccess registries) {
        JsonObject schema = CodecJsonSchemaImpl.create(Advancement.CODEC, registries);
        schema.addProperty(DatapackEditorConstants.JSON_TITLE, "Minecraft advancement");
        schema.addProperty(DatapackEditorConstants.JSON_DESCRIPTION, "Generated from Advancement.CODEC and the running game's registries.");
        return registration(DatapackEditorConstants.JSON_ADVANCEMENT, schema, true);
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
        schema.addProperty(DatapackEditorConstants.JSON_TITLE, key.identifier() + " datapack entry");
        schema.addProperty(DatapackEditorConstants.JSON_DESCRIPTION, DatapackEditorConstants.MESSAGE_REGISTRY_DESCRIPTION);
        return schema;
    }

    private static JsonObject trialSpawnerEntityTagSchema(RegistryAccess registries) {
        JsonElement entityId = CodecJsonSchemaImpl.registrySchema(registries, Registries.ENTITY_TYPE, false);

        JsonObject properties = new JsonObject();
        properties.add(DatapackEditorConstants.JSON_ID, entityId);
        JsonObject entityTag = new JsonObject();
        entityTag.addProperty(DatapackEditorConstants.JSON_TYPE, DatapackEditorConstants.SCHEMA_OBJECT);
        entityTag.add(DatapackEditorConstants.JSON_PROPERTIES, properties);
        entityTag.addProperty(DatapackEditorConstants.SCHEMA_ADDITIONAL_PROPERTIES, true);
        return entityTag;
    }

    private static void applyRegisteredSchema(Map<String, JsonObject> registrations,
                                              RegistryAccess registries,
                                              String id,
                                              List<DataPackRegistration> registered) {
        JsonObject existing = registrations.get(id);
        CodecJsonSchema.Builder schemaBuilder = CodecJsonSchemaImpl.builder(registries);
        Codec<?> codec = null;
        String title = null;
        String description = null;
        List<String> fileMatches = List.of();
        for (DataPackRegistration registration : registered) {
            if (registration.codec() != null) codec = registration.codec();
            if (registration.title() != null) title = registration.title();
            if (registration.description() != null) description = registration.description();
            if (!registration.fileMatches().isEmpty()) fileMatches = registration.fileMatches();
        }
        if (codec != null && CommonPlatform.platform != null) {
            CommonPlatform.platform.configureDatapackSchema(schemaBuilder, codec);
        }
        registered.forEach(registration -> registration.configure(schemaBuilder));

        JsonObject schema;
        if (codec != null) {
            schema = CodecJsonSchemaImpl.create(schemaBuilder, codec);
            schema.addProperty(DatapackEditorConstants.JSON_TITLE, existing == null
                    ? id + " datapack entry"
                    : existing.getAsJsonObject(DatapackEditorConstants.JSON_SCHEMA).get(DatapackEditorConstants.JSON_TITLE).getAsString());
            schema.addProperty(DatapackEditorConstants.JSON_DESCRIPTION, existing == null
                    ? DatapackEditorConstants.MESSAGE_REGISTRY_DESCRIPTION
                    : existing.getAsJsonObject(DatapackEditorConstants.JSON_SCHEMA).get(DatapackEditorConstants.JSON_DESCRIPTION).getAsString());
        } else {
            if (existing == null) throw new IllegalArgumentException("No schema to enrich for datapack resource: " + id);
            schema = CodecJsonSchemaImpl.enhance(schemaBuilder, existing.getAsJsonObject(DatapackEditorConstants.JSON_SCHEMA));
        }
        if (title != null) schema.addProperty(DatapackEditorConstants.JSON_TITLE, title);
        if (description != null) schema.addProperty(DatapackEditorConstants.JSON_DESCRIPTION, description);
        JsonObject result = registration(id, schema, id.equals(DatapackEditorConstants.JSON_ADVANCEMENT));
        if (!fileMatches.isEmpty()) {
            JsonArray matches = new JsonArray();
            fileMatches.forEach(matches::add);
            result.add(DatapackEditorConstants.JSON_FILE_MATCH, matches);
        } else if (existing != null) {
            result.add(DatapackEditorConstants.JSON_FILE_MATCH, existing.get(DatapackEditorConstants.JSON_FILE_MATCH).deepCopy());
        }
        registrations.put(id, result);
    }

    private static JsonObject registration(String directory, JsonObject schema, boolean includeLegacyPlural) {
        JsonArray fileMatch = new JsonArray();
        addFileMatches(fileMatch, directory);
        if (includeLegacyPlural) addFileMatches(fileMatch, directory + "s");

        JsonObject registration = new JsonObject();
        registration.addProperty(DatapackEditorConstants.JSON_URI, "datapack-editor://schemas/datapack/" + directory.replace('/', '_') + DatapackEditorConstants.PATH_JSON_EXTENSION);
        registration.add(DatapackEditorConstants.JSON_FILE_MATCH, fileMatch);
        if (schema != null) registration.add(DatapackEditorConstants.JSON_SCHEMA, schema);
        return registration;
    }

    private static void addFileMatches(JsonArray fileMatch, String directory) {
        fileMatch.add(DatapackEditorConstants.PATH_DATA_FILE_GLOB_PREFIX + directory + DatapackEditorConstants.PATH_JSON_FILE_GLOB);
        fileMatch.add(DatapackEditorConstants.PATH_DATA_FILE_GLOB_PREFIX + directory + DatapackEditorConstants.PATH_RECURSIVE_JSON_FILE_GLOB);
    }

    private static boolean isDefaultDirectory(String directory) {
        if (directory.equals(DatapackEditorConstants.JSON_ADVANCEMENT)) return true;
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
