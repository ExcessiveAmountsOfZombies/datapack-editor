package com.epherical.datapackeditor.impl.schemas;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.epherical.datapackeditor.api.schema.CodecJsonSchema.SchemaContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.*;


public class BlockStateSchema {
    public static JsonElement debugStick(SchemaContext context) {
        JsonObject root = object();
        root.addProperty(DatapackEditorConstants.SCHEMA_ADDITIONAL_PROPERTIES, false);
        root.add(DatapackEditorConstants.SCHEMA_PROPERTY_NAMES, context.registry(Registries.BLOCK, false));
        HolderLookup.RegistryLookup<Block> registry = context.registries().lookup(Registries.BLOCK).orElse(null);
        if (registry == null) registry = BuiltInRegistries.BLOCK.asLookup();
        registry.listElements().sorted(Comparator.comparing(holder -> holder.key().location().toString())).forEach(holder -> {
            context.checkpoint();
            List<String> names = holder.value().getStateDefinition().getProperties().stream().map(Property::getName).sorted().toList();
            JsonElement domain = context.sharedDefinition(new PropertyNames(names), () -> {
                if (names.isEmpty()) {
                    JsonObject impossible = new JsonObject();
                    impossible.add(DatapackEditorConstants.SCHEMA_NOT, new JsonObject());
                    return impossible;
                }
                JsonObject value = new JsonObject();
                value.addProperty(DatapackEditorConstants.JSON_TYPE, DatapackEditorConstants.SCHEMA_STRING);
                JsonArray values = new JsonArray();
                names.forEach(values::add);
                value.add(DatapackEditorConstants.SCHEMA_ENUM, values);
                return value;
            });
            root.getAsJsonObject(DatapackEditorConstants.JSON_PROPERTIES).add(holder.key().location().toString(), domain);
        });
        return root;
    }

    public static JsonElement create(SchemaContext context) {
        Map<Property<?>, JsonObject> domains = new IdentityHashMap<>();
        Map<JsonObject, JsonArray> groups = new LinkedHashMap<>();
        HolderLookup.RegistryLookup<Block> registry = context.registries().lookup(Registries.BLOCK).orElse(null);
        if (registry == null) registry = BuiltInRegistries.BLOCK.asLookup();
        registry.listElements()
                .sorted(Comparator.comparing(holder -> holder.key().location().toString())).forEach(holder -> {
                    context.checkpoint();
                    JsonObject properties = properties(holder, domains);
                    groups.computeIfAbsent(properties, ignored -> new JsonArray()).add(holder.key().location().toString());
                });
        JsonObject root = object();
        root.getAsJsonObject(DatapackEditorConstants.JSON_PROPERTIES).add(DatapackEditorConstants.MINECRAFT_BLOCK_STATE_NAME, context.registry(Registries.BLOCK, false));
        JsonArray required = new JsonArray();
        required.add(DatapackEditorConstants.MINECRAFT_BLOCK_STATE_NAME);
        root.add(DatapackEditorConstants.SCHEMA_REQUIRED, required);
        JsonArray variants = new JsonArray();
        int index = 0;
        for (Map.Entry<JsonObject, JsonArray> group : groups.entrySet()) {
            context.checkpoint();
            JsonObject variant = object();
            JsonObject name = new JsonObject();
            name.addProperty(DatapackEditorConstants.JSON_TYPE, DatapackEditorConstants.SCHEMA_STRING);
            name.add(DatapackEditorConstants.SCHEMA_ENUM, group.getValue());
            variant.getAsJsonObject(DatapackEditorConstants.JSON_PROPERTIES).add(DatapackEditorConstants.MINECRAFT_BLOCK_STATE_NAME, name);
            if (!group.getKey().isEmpty()) {
                JsonObject properties = object();
                properties.add(DatapackEditorConstants.JSON_PROPERTIES, group.getKey());
                variant.getAsJsonObject(DatapackEditorConstants.JSON_PROPERTIES).add(DatapackEditorConstants.MINECRAFT_BLOCK_STATE_PROPERTIES,
                        context.definition("minecraft:block_state:properties:" + index, () -> properties));
            }
            variants.add(variant);
            index++;
        }
        if (!variants.isEmpty()) root.add(DatapackEditorConstants.SCHEMA_ONE_OF, variants);
        return root;
    }

    private static JsonObject properties(Holder.Reference<Block> holder, Map<Property<?>, JsonObject> domains) {
        JsonObject properties = new JsonObject();
        holder.value().getStateDefinition().getProperties().stream().sorted(Comparator.comparing(Property::getName))
                .forEach(property -> properties.add(property.getName(), domains.computeIfAbsent(property, BlockStateSchema::domain)));
        return properties;
    }

    private static <T extends Comparable<T>> JsonObject domain(Property<T> property) {
        JsonObject schema = new JsonObject();
        schema.addProperty(DatapackEditorConstants.JSON_TYPE, DatapackEditorConstants.SCHEMA_STRING);
        JsonArray values = new JsonArray();
        property.getPossibleValues().stream().map(property::getName).sorted().forEach(values::add);
        schema.add(DatapackEditorConstants.SCHEMA_ENUM, values);
        return schema;
    }

    private static JsonObject object() {
        JsonObject schema = new JsonObject();
        schema.addProperty(DatapackEditorConstants.JSON_TYPE, DatapackEditorConstants.SCHEMA_OBJECT);
        schema.add(DatapackEditorConstants.JSON_PROPERTIES, new JsonObject());
        return schema;
    }

    private record PropertyNames(List<String> names) {
    }
}
