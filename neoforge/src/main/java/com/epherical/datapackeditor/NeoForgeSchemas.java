package com.epherical.datapackeditor;

import com.epherical.datapackeditor.api.schema.CodecJsonSchema;
import com.epherical.datapackeditor.api.schema.CodecJsonSchema.SchemaContext;
import com.epherical.datapackeditor.api.DataPackRegistration;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.Encoder;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.EitherCodec;
import com.mojang.serialization.codecs.ListCodec;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.HolderSetCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntries;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctions;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.common.crafting.IngredientType;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.StructureModifier;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.RegistryManager;
import net.neoforged.neoforge.registries.datamaps.AdvancedDataMapType;
import net.neoforged.neoforge.registries.datamaps.DataMapFile;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.fluids.FluidInstance;
import net.neoforged.neoforge.fluids.crafting.FluidIngredient;
import net.neoforged.neoforge.fluids.crafting.CompoundFluidIngredient;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.epherical.datapackeditor.DatapackEditorConstants.*;


public class NeoForgeSchemas {
    public static List<DataPackRegistration> dataMaps() {
        return Stream.concat(Stream.of(DataPackRegistration.directory("data_maps").build()),
                RegistryManager.getDataMaps().values().stream().flatMap(maps -> maps.values().stream())
                        .map(NeoForgeSchemas::dataMapRegistration)).toList();
    }

    private static <R, T> DataPackRegistration dataMapRegistration(DataMapType<R, T> type) {
        Identifier registry = type.registryKey().identifier();
        String folder = (registry.getNamespace().equals("minecraft") ? "" : registry.getNamespace() + PATH_SEPARATOR) + registry.getPath();
        Identifier file = Identifier.fromNamespaceAndPath(type.id().getNamespace(), "data_maps/"
                + folder + PATH_SEPARATOR + type.id().getPath() + PATH_JSON_EXTENSION);
        Codec<?> codec = DataMapFile.codec(type.registryKey(), type);
        return DataPackRegistration.file(file).schema(codec)
                .title(type.id() + " data map for " + registry)
                .provide(codec, context -> dataMapSchema(context, type)).build();
    }

    private static JsonElement dataMapSchema(SchemaContext context, DataMapType<?, ?> type) {
        JsonElement value = context.schema(type.codec());
        JsonObject replaced = object();
        replaced.getAsJsonObject(JSON_PROPERTIES).add(JSON_VALUE, value);
        replaced.getAsJsonObject(JSON_PROPERTIES).add(JSON_REPLACE, context.schema(Codec.BOOL));
        replaced.add(SCHEMA_REQUIRED, strings(JSON_VALUE));
        JsonElement entry = alternatives(value, replaced);
        JsonObject conditional = object();
        conditional.getAsJsonObject(JSON_PROPERTIES).add(MINECRAFT_NEOFORGE_VALUE, entry);
        conditional.getAsJsonObject(JSON_PROPERTIES).add(MINECRAFT_NEOFORGE_CONDITIONS, context.schema(ICondition.LIST_CODEC));
        conditional.add(SCHEMA_REQUIRED, strings(MINECRAFT_NEOFORGE_VALUE));
        JsonObject values = object();
        values.add(SCHEMA_PROPERTY_NAMES, context.registry(type.registryKey(), true));
        values.add(SCHEMA_ADDITIONAL_PROPERTIES, alternatives(entry, withConditions(context, replaced), conditional));
        JsonObject schema = object();
        JsonObject properties = schema.getAsJsonObject(JSON_PROPERTIES);
        properties.add(JSON_REPLACE, context.schema(Codec.BOOL));
        properties.add(JSON_VALUES, values);
        JsonElement key = context.registry(type.registryKey(), true);
        JsonElement removals = array(key);
        if (type instanceof AdvancedDataMapType<?, ?, ?> advanced) {
            JsonElement remover = context.schema(advanced.remover());
            JsonObject removal = object();
            removal.getAsJsonObject(JSON_PROPERTIES).add(JSON_KEY, key);
            removal.getAsJsonObject(JSON_PROPERTIES).add("remover", remover);
            removal.add(SCHEMA_REQUIRED, strings(JSON_KEY));
            JsonObject removalMap = object();
            removalMap.add(SCHEMA_PROPERTY_NAMES, key);
            removalMap.add(SCHEMA_ADDITIONAL_PROPERTIES, remover);
            removals = alternatives(removals, array(removal), removalMap);
        }
        properties.add("remove", removals);
        schema.add(SCHEMA_REQUIRED, strings(JSON_VALUES));
        return withConditions(context, schema);
    }

    private static JsonObject object() {
        JsonObject schema = new JsonObject();
        schema.addProperty(JSON_TYPE, SCHEMA_OBJECT);
        schema.add(JSON_PROPERTIES, new JsonObject());
        return schema;
    }

    private static JsonArray strings(String... values) {
        JsonArray result = new JsonArray();
        for (String value : values) result.add(value);
        return result;
    }

    private static JsonElement array(JsonElement items) {
        JsonObject schema = new JsonObject();
        schema.addProperty(JSON_TYPE, SCHEMA_ARRAY);
        schema.add(SCHEMA_ITEMS, items);
        return schema;
    }

    private static JsonElement alternatives(JsonElement... variants) {
        JsonArray alternatives = new JsonArray();
        for (JsonElement variant : variants) alternatives.add(variant);
        JsonObject schema = new JsonObject();
        schema.add(SCHEMA_ANY_OF, alternatives);
        return schema;
    }

    public static void register() {
        CodecJsonSchema.registerInspector(NeoForgeSchemas::inspectHolderSet);
        CodecJsonSchema.registerProvider(Ingredient.CODEC, context -> context.definition("neoforge:ingredient", () -> {
            JsonArray alternatives = new JsonArray();
            alternatives.add(context.schema(Ingredient.NON_AIR_HOLDER_SET_CODEC));
            alternatives.add(dispatch(context, NeoForgeRegistries.INGREDIENT_TYPES,
                    MINECRAFT_NEOFORGE_INGREDIENT_TYPE, IngredientType::codec));
            JsonObject schema = new JsonObject();
            schema.add(SCHEMA_ANY_OF, alternatives);
            return schema;
        }));
        CodecJsonSchema.registerProvider(ICondition.CODEC, context -> context.definition("neoforge:condition",
                () -> dispatch(context, NeoForgeRegistries.CONDITION_SERIALIZERS, JSON_TYPE, Function.identity())));
        CodecJsonSchema.registerProvider(FluidIngredient.CODEC, context -> context.definition("neoforge:fluid_ingredient", () ->
                alternatives(context.schema(HolderSetCodec.create(Registries.FLUID, FluidInstance.FLUID_HOLDER_CODEC, false)),
                        dispatch(context, NeoForgeRegistries.FLUID_INGREDIENT_TYPES, MINECRAFT_NEOFORGE_INGREDIENT_TYPE,
                                type -> type == NeoForgeMod.SIMPLE_FLUID_INGREDIENT_TYPE.get() ? null : type.codec()))));
        // aliasedFieldOf accepts either spelling, not both required fields.
        CodecJsonSchema.registerProvider(CompoundFluidIngredient.CODEC, context -> {
            JsonElement children = context.schema(FluidIngredient.CODEC.listOf(1, Integer.MAX_VALUE));
            JsonObject current = object();
            current.getAsJsonObject(JSON_PROPERTIES).add(JSON_CHILDREN, children);
            current.add(SCHEMA_REQUIRED, strings(JSON_CHILDREN));
            JsonObject legacy = object();
            legacy.getAsJsonObject(JSON_PROPERTIES).add(JSON_INGREDIENTS, children);
            legacy.add(SCHEMA_REQUIRED, strings(JSON_INGREDIENTS));
            return alternatives(current, legacy);
        });
        CodecJsonSchema.registerProvider(IGlobalLootModifier.DIRECT_CODEC, context ->
                dispatch(context, NeoForgeRegistries.GLOBAL_LOOT_MODIFIER_SERIALIZERS, JSON_TYPE, Function.identity()));
        CodecJsonSchema.registerProvider(BiomeModifier.DIRECT_CODEC, context ->
                dispatch(context, NeoForgeRegistries.BIOME_MODIFIER_SERIALIZERS, JSON_TYPE, Function.identity()));
        CodecJsonSchema.registerProvider(StructureModifier.DIRECT_CODEC, context ->
                dispatch(context, NeoForgeRegistries.STRUCTURE_MODIFIER_SERIALIZERS, JSON_TYPE, Function.identity()));
        for (Codec<?> codec : new Codec<?>[]{Recipe.CODEC, Advancement.CODEC, LootTable.DIRECT_CODEC,
                LootPool.CODEC, LootPoolEntries.CODEC, LootItemFunctions.ROOT_CODEC,
                IGlobalLootModifier.DIRECT_CODEC, BiomeModifier.DIRECT_CODEC, StructureModifier.DIRECT_CODEC}) {
            CodecJsonSchema.registerAdapter(codec, NeoForgeSchemas::withConditions);
        }
        DataPackRegistration.directory("loot_modifiers").schema(IGlobalLootModifier.DIRECT_CODEC)
                .title("NeoForge global loot modifier")
                .description("Loaded automatically from loot_modifiers in NeoForge 26.1.2; higher priority runs first.")
                .register();
        DataPackRegistration.file(Identifier.parse("neoforge:recipe_priorities.json")).schema(Codec.PASSTHROUGH)
                .title("NeoForge recipe priorities")
                .provide(Codec.PASSTHROUGH, context -> {
                    JsonObject schema = object();
                    schema.getAsJsonObject(JSON_PROPERTIES).add(JSON_REPLACE, context.schema(Codec.BOOL));
                    JsonObject entries = object();
                    entries.add(SCHEMA_ADDITIONAL_PROPERTIES, context.schema(Codec.INT));
                    schema.getAsJsonObject(JSON_PROPERTIES).add(JSON_ENTRIES, entries);
                    schema.add(SCHEMA_REQUIRED, strings(JSON_ENTRIES));
                    return schema;
                }).register();
    }

    private static <T> JsonElement dispatch(SchemaContext context, Registry<T> registry,
                                            String discriminator, Function<T, ? extends MapCodec<?>> codec) {
        JsonArray variants = new JsonArray();
        registry.entrySet().stream().sorted(Comparator.comparing(left -> left.getKey().identifier().toString())).forEach(entry -> {
            MapCodec<?> variantCodec = codec.apply(entry.getValue());
            if (variantCodec == null) return;
            JsonObject variant = context.resolve(context.schema(variantCodec)).getAsJsonObject();
            JsonObject properties = variant.has(JSON_PROPERTIES) ? variant.getAsJsonObject(JSON_PROPERTIES) : new JsonObject();
            JsonObject type = new JsonObject();
            type.addProperty(SCHEMA_CONST, entry.getKey().identifier().toString());
            properties.add(discriminator, type);
            variant.add(JSON_PROPERTIES, properties);
            variant.addProperty(JSON_TYPE, SCHEMA_OBJECT);
            JsonArray required = variant.has(SCHEMA_REQUIRED) ? variant.getAsJsonArray(SCHEMA_REQUIRED) : new JsonArray();
            required.add(discriminator);
            variant.add(SCHEMA_REQUIRED, required);
            variants.add(variant);
        });
        JsonObject schema = new JsonObject();
        if (variants.isEmpty()) schema.addProperty(SCHEMA_NOT, true);
        else schema.add(SCHEMA_ONE_OF, variants);
        return schema;
    }

    public static JsonElement withConditions(SchemaContext context, JsonElement inferred) {
        JsonObject schema = context.resolve(inferred).getAsJsonObject();
        JsonObject properties = schema.has(JSON_PROPERTIES) ? schema.getAsJsonObject(JSON_PROPERTIES) : new JsonObject();
        properties.add(MINECRAFT_NEOFORGE_CONDITIONS, context.schema(ICondition.LIST_CODEC));
        schema.add(JSON_PROPERTIES, properties);
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static JsonElement inspectHolderSet(SchemaContext context, Object codec) {
        if (!(codec instanceof HolderSetCodec<?>)) return null;
        // Preserve the actual element/list and custom dispatch codecs, but describe
        // hashed tags explicitly: generic string-codec inference loses their '#'.
        Codec<?> custom = (Codec<?>) field(codec, "forgeDispatchCodec");
        ResourceKey<? extends Registry<?>> registry = (ResourceKey<? extends Registry<?>>) field(codec, CODEC_REGISTRY_KEY);
        Codec<?> list = (Codec<?>) field(codec, "homogenousListCodec");
        Codec<?> element = (Codec<?>) field(codec, CODEC_ELEMENT_CODEC);
        HolderSetSchemaKey key = new HolderSetSchemaKey(registry, element, holderSetRequiresList(list));
        // HolderSetType.makeCodec creates new child holder-set instances. Share
        // their semantic family before dispatching into those recursive children.
        return context.sharedDefinition(key, () ->
                alternatives(context.schema(custom), holderSetTag(context, registry), context.schema(list)));
    }

    private static boolean holderSetRequiresList(Codec<?> list) {
        ArrayDeque<Object> wrappers = new ArrayDeque<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        wrappers.add(list);
        for (int visited = 0; !wrappers.isEmpty() && visited < CODEC_REFLECTION_MAX_DEPTH; visited++) {
            Object current = wrappers.removeFirst();
            if (!seen.add(current)) continue;
            if (current instanceof ListCodec<?>) return true;
            if (current instanceof EitherCodec<?, ?>) return false;
            for (Class<?> type = current.getClass(); type != null; type = type.getSuperclass()) {
                for (Field candidate : type.getDeclaredFields()) {
                    if (Modifier.isStatic(candidate.getModifiers())) continue;
                    try {
                        candidate.setAccessible(true);
                        Object value = candidate.get(current);
                        if (value instanceof Decoder<?> || value instanceof Encoder<?>) wrappers.addLast(value);
                    } catch (ReflectiveOperationException exception) {
                        throw new IllegalStateException("NeoForge holder-set list codec changed", exception);
                    }
                }
            }
        }
        throw new IllegalStateException("Unrecognized NeoForge holder-set list codec");
    }

    private record HolderSetSchemaKey(ResourceKey<? extends Registry<?>> registry, Codec<?> element, boolean requiresList) {
        public boolean equals(Object other) {
            return other instanceof HolderSetSchemaKey key && registry.equals(key.registry)
                    && element == key.element && requiresList == key.requiresList;
        }

        public int hashCode() {
            return HASH_MULTIPLIER * (HASH_MULTIPLIER * registry.hashCode() + System.identityHashCode(element)) + Boolean.hashCode(requiresList);
        }
    }

    private static JsonElement holderSetTag(SchemaContext context, ResourceKey<? extends Registry<?>> registry) {
        JsonObject tag = new JsonObject();
        tag.addProperty(JSON_TYPE, SCHEMA_STRING);
        tag.addProperty(SCHEMA_PATTERN, "^#(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+$");
        JsonObject values = context.resolve(context.registry(registry, true)).getAsJsonObject();
        JsonArray tags = new JsonArray();
        if (values.has(SCHEMA_ENUM)) {
            for (JsonElement value : values.getAsJsonArray(SCHEMA_ENUM)) {
                if (value.getAsString().startsWith(MINECRAFT_TAG_PREFIX)) tags.add(value);
            }
        }
        if (!tags.isEmpty()) tag.add(SCHEMA_ENUM, tags);
        return tag;
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("NeoForge holder set codec changed: " + name, exception);
        }
    }
}
