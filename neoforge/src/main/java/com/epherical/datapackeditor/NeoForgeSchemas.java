package com.epherical.datapackeditor;

import com.epherical.datapackeditor.api.schema.CodecJsonSchema;
import com.epherical.datapackeditor.api.schema.CodecJsonSchema.SchemaContext;
import com.epherical.datapackeditor.api.schema.DataPackSchemaBuilder;
import com.epherical.datapackeditor.api.schema.DataPackSchemaRegistration;
import com.epherical.datapackeditor.editor.EditorDatapacks;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
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
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** Loader-specific codec bridges; variants always come from the running registries. */
public class NeoForgeSchemas {
    public static List<DataPackSchemaRegistration> dataMaps() {
        return RegistryManager.getDataMaps().values().stream().flatMap(maps -> maps.values().stream())
                .map(NeoForgeSchemas::dataMapRegistration).toList();
    }

    private static <R, T> DataPackSchemaRegistration dataMapRegistration(DataMapType<R, T> type) {
        Identifier registry = type.registryKey().identifier();
        String folder = (registry.getNamespace().equals("minecraft") ? "" : registry.getNamespace() + "/") + registry.getPath();
        String path = "data/" + type.id().getNamespace() + "/data_maps/" + folder + "/" + type.id().getPath() + ".json";
        Codec<?> codec = DataMapFile.codec(type.registryKey(), type);
        return DataPackSchemaRegistration.builder("data_maps/" + folder + "/" + type.id().getNamespace() + "/" + type.id().getPath(), codec)
                .matchFiles("file:///**/" + path)
                .title(type.id() + " data map for " + registry)
                .provide(codec, context -> dataMapSchema(context, type)).build();
    }

    private static JsonElement dataMapSchema(SchemaContext context, DataMapType<?, ?> type) {
        JsonElement value = context.schema(type.codec());
        JsonObject replaced = object();
        replaced.getAsJsonObject("properties").add("value", value);
        replaced.getAsJsonObject("properties").add("replace", context.schema(Codec.BOOL));
        replaced.add("required", strings("value"));
        JsonElement entry = alternatives(value, replaced);
        JsonObject conditional = object();
        conditional.getAsJsonObject("properties").add("neoforge:value", entry);
        conditional.getAsJsonObject("properties").add("neoforge:conditions", context.schema(ICondition.LIST_CODEC));
        conditional.add("required", strings("neoforge:value"));
        JsonObject values = object();
        values.add("propertyNames", context.registry(type.registryKey(), true));
        values.add("additionalProperties", alternatives(entry, withConditions(context, replaced), conditional));
        JsonObject schema = object();
        JsonObject properties = schema.getAsJsonObject("properties");
        properties.add("replace", context.schema(Codec.BOOL));
        properties.add("values", values);
        JsonElement key = context.registry(type.registryKey(), true);
        JsonElement removals = array(key);
        if (type instanceof AdvancedDataMapType<?, ?, ?> advanced) {
            JsonElement remover = context.schema(advanced.remover());
            JsonObject removal = object();
            removal.getAsJsonObject("properties").add("key", key);
            removal.getAsJsonObject("properties").add("remover", remover);
            removal.add("required", strings("key"));
            JsonObject removalMap = object();
            removalMap.add("propertyNames", key);
            removalMap.add("additionalProperties", remover);
            removals = alternatives(removals, array(removal), removalMap);
        }
        properties.add("remove", removals);
        schema.add("required", strings("values"));
        return withConditions(context, schema);
    }

    private static JsonObject object() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    private static JsonArray strings(String... values) {
        JsonArray result = new JsonArray();
        for (String value : values) result.add(value);
        return result;
    }

    private static JsonElement array(JsonElement items) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "array");
        schema.add("items", items);
        return schema;
    }

    private static JsonElement alternatives(JsonElement... variants) {
        JsonArray alternatives = new JsonArray();
        for (JsonElement variant : variants) alternatives.add(variant);
        JsonObject schema = new JsonObject();
        schema.add("anyOf", alternatives);
        return schema;
    }

    public static void register() {
        EditorDatapacks.registerDirectory("data_maps");
        EditorDatapacks.registerFile(Identifier.parse("neoforge:recipe_priorities.json"));
        CodecJsonSchema.registerInspector(NeoForgeSchemas::inspectHolderSet);
        CodecJsonSchema.registerProvider(Ingredient.CODEC, context -> context.definition("neoforge:ingredient", () -> {
            JsonArray alternatives = new JsonArray();
            alternatives.add(context.schema(Ingredient.NON_AIR_HOLDER_SET_CODEC));
            alternatives.add(dispatch(context, NeoForgeRegistries.INGREDIENT_TYPES,
                    "neoforge:ingredient_type", IngredientType::codec));
            JsonObject schema = new JsonObject();
            schema.add("anyOf", alternatives);
            return schema;
        }));
        CodecJsonSchema.registerProvider(ICondition.CODEC, context -> context.definition("neoforge:condition",
                () -> dispatch(context, NeoForgeRegistries.CONDITION_SERIALIZERS, "type", Function.identity())));
        CodecJsonSchema.registerProvider(FluidIngredient.CODEC, context -> context.definition("neoforge:fluid_ingredient", () ->
                alternatives(context.schema(HolderSetCodec.create(Registries.FLUID, FluidInstance.FLUID_HOLDER_CODEC, false)),
                        dispatch(context, NeoForgeRegistries.FLUID_INGREDIENT_TYPES, "neoforge:ingredient_type",
                                type -> type == NeoForgeMod.SIMPLE_FLUID_INGREDIENT_TYPE.get() ? null : type.codec()))));
        // aliasedFieldOf accepts either spelling, not both required fields.
        CodecJsonSchema.registerProvider(CompoundFluidIngredient.CODEC, context -> {
            JsonElement children = context.schema(FluidIngredient.CODEC.listOf(1, Integer.MAX_VALUE));
            JsonObject current = object();
            current.getAsJsonObject("properties").add("children", children);
            current.add("required", strings("children"));
            JsonObject legacy = object();
            legacy.getAsJsonObject("properties").add("ingredients", children);
            legacy.add("required", strings("ingredients"));
            return alternatives(current, legacy);
        });
        CodecJsonSchema.registerProvider(IGlobalLootModifier.DIRECT_CODEC, context ->
                dispatch(context, NeoForgeRegistries.GLOBAL_LOOT_MODIFIER_SERIALIZERS, "type", Function.identity()));
        CodecJsonSchema.registerProvider(BiomeModifier.DIRECT_CODEC, context ->
                dispatch(context, NeoForgeRegistries.BIOME_MODIFIER_SERIALIZERS, "type", Function.identity()));
        CodecJsonSchema.registerProvider(StructureModifier.DIRECT_CODEC, context ->
                dispatch(context, NeoForgeRegistries.STRUCTURE_MODIFIER_SERIALIZERS, "type", Function.identity()));
        for (Codec<?> codec : new Codec<?>[]{Recipe.CODEC, Advancement.CODEC, LootTable.DIRECT_CODEC,
                LootPool.CODEC, LootPoolEntries.CODEC, LootItemFunctions.ROOT_CODEC,
                IGlobalLootModifier.DIRECT_CODEC, BiomeModifier.DIRECT_CODEC, StructureModifier.DIRECT_CODEC}) {
            CodecJsonSchema.registerAdapter(codec, NeoForgeSchemas::withConditions);
        }
        DataPackSchemaBuilder.register(DataPackSchemaRegistration.builder("loot_modifiers", IGlobalLootModifier.DIRECT_CODEC)
                .title("NeoForge global loot modifier")
                .description("Loaded automatically from loot_modifiers in NeoForge 26.1.2; higher priority runs first.")
                .build());
        DataPackSchemaBuilder.register(DataPackSchemaRegistration.builder("neoforge_recipe_priorities", Codec.PASSTHROUGH)
                .matchFiles("file:///**/data/neoforge/recipe_priorities.json")
                .title("NeoForge recipe priorities")
                .provide(Codec.PASSTHROUGH, context -> {
                    JsonObject schema = object();
                    schema.getAsJsonObject("properties").add("replace", context.schema(Codec.BOOL));
                    JsonObject entries = object();
                    entries.add("additionalProperties", context.schema(Codec.INT));
                    schema.getAsJsonObject("properties").add("entries", entries);
                    schema.add("required", strings("entries"));
                    return schema;
                }).build());
    }

    private static <T> JsonElement dispatch(SchemaContext context, Registry<T> registry,
                                            String discriminator, Function<T, ? extends MapCodec<?>> codec) {
        JsonArray variants = new JsonArray();
        registry.entrySet().stream().sorted(Comparator.comparing(left -> left.getKey().identifier().toString())).forEach(entry -> {
            MapCodec<?> variantCodec = codec.apply(entry.getValue());
            if (variantCodec == null) return;
            JsonObject variant = context.resolve(context.schema(variantCodec)).getAsJsonObject();
            JsonObject properties = variant.has("properties") ? variant.getAsJsonObject("properties") : new JsonObject();
            JsonObject type = new JsonObject();
            type.addProperty("const", entry.getKey().identifier().toString());
            properties.add(discriminator, type);
            variant.add("properties", properties);
            variant.addProperty("type", "object");
            JsonArray required = variant.has("required") ? variant.getAsJsonArray("required") : new JsonArray();
            required.add(discriminator);
            variant.add("required", required);
            variants.add(variant);
        });
        JsonObject schema = new JsonObject();
        if (variants.isEmpty()) schema.addProperty("not", true);
        else schema.add("oneOf", variants);
        return schema;
    }

    public static JsonElement withConditions(SchemaContext context, JsonElement inferred) {
        JsonObject schema = context.resolve(inferred).getAsJsonObject();
        JsonObject properties = schema.has("properties") ? schema.getAsJsonObject("properties") : new JsonObject();
        properties.add("neoforge:conditions", context.schema(ICondition.LIST_CODEC));
        schema.add("properties", properties);
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static JsonElement inspectHolderSet(SchemaContext context, Object codec) {
        if (!(codec instanceof HolderSetCodec<?>)) return null;
        // Preserve the actual element/list and custom dispatch codecs, but describe
        // hashed tags explicitly: generic string-codec inference loses their '#'.
        Codec<?> custom = (Codec<?>) field(codec, "forgeDispatchCodec");
        ResourceKey<? extends Registry<?>> registry = (ResourceKey<? extends Registry<?>>) field(codec, "registryKey");
        Object element = field(codec, "elementCodec");
        Codec<?> list = (Codec<?>) field(codec, "homogenousListCodec");
        // Both compact and required lists use Codec$2; their structures, not their
        // wrapper classes, must distinguish recursive definitions.
        String name = "neoforge:holder_set:" + registry.toString().replaceAll("[^A-Za-z0-9_.:-]", "_")
                + ":" + Integer.toUnsignedString(element.toString().hashCode())
                + ":" + Integer.toUnsignedString(list.toString().hashCode());
        return context.definition(name, () -> alternatives(context.schema(custom),
                holderSetTag(context, registry), context.schema(list)));
    }

    private static JsonElement holderSetTag(SchemaContext context, ResourceKey<? extends Registry<?>> registry) {
        JsonObject tag = new JsonObject();
        tag.addProperty("type", "string");
        tag.addProperty("pattern", "^#(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+$");
        JsonObject values = context.resolve(context.registry(registry, true)).getAsJsonObject();
        JsonArray tags = new JsonArray();
        if (values.has("enum")) {
            for (JsonElement value : values.getAsJsonArray("enum")) {
                if (value.getAsString().startsWith("#")) tags.add(value);
            }
        }
        if (!tags.isEmpty()) tag.add("enum", tags);
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
