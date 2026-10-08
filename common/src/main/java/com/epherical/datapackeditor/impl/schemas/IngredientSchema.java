package com.epherical.datapackeditor.impl.schemas;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.epherical.datapackeditor.api.schema.CodecJsonSchema.SchemaContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

public class IngredientSchema {
    public static JsonObject create(SchemaContext context, boolean allowEmpty) {
        return forms(value(context), allowEmpty);
    }

    public static JsonObject forms(JsonElement value, boolean allowEmpty) {
        JsonArray variants = new JsonArray();
        variants.add(value);
        variants.add(list(value, allowEmpty));
        JsonObject schema = new JsonObject();
        schema.add(DatapackEditorConstants.SCHEMA_ANY_OF, variants);
        return schema;
    }

    public static JsonObject list(JsonElement value, boolean allowEmpty) {
        JsonObject array = new JsonObject();
        array.addProperty(DatapackEditorConstants.JSON_TYPE, DatapackEditorConstants.SCHEMA_ARRAY);
        array.add(DatapackEditorConstants.SCHEMA_ITEMS, value);
        if (!allowEmpty) array.addProperty(DatapackEditorConstants.SCHEMA_MIN_ITEMS, 1);
        return array;
    }

    public static JsonObject value(SchemaContext context) {
        return value(context, Registries.ITEM, "item");
    }

    public static JsonObject value(SchemaContext context, ResourceKey<? extends Registry<?>> registry, String element) {
        JsonObject tag = new JsonObject();
        tag.addProperty(DatapackEditorConstants.JSON_TYPE, DatapackEditorConstants.SCHEMA_STRING);
        tag.addProperty(DatapackEditorConstants.SCHEMA_PATTERN, "^([a-z0-9_.-]+:)?[a-z0-9/._-]+$");
        JsonArray suggestions = new JsonArray();
        context.registries().lookup(registry).ifPresent(lookup -> lookup.listTagIds().forEach(id -> {
            context.checkpoint();
            suggestions.add(id.location().toString());
        }));
        JsonElement tagSchema = tag;
        if (!suggestions.isEmpty()) {
            JsonObject known = new JsonObject();
            known.add(DatapackEditorConstants.SCHEMA_ENUM, suggestions);
            JsonArray alternatives = new JsonArray();
            alternatives.add(known);
            alternatives.add(tag);
            JsonObject combined = new JsonObject();
            combined.add(DatapackEditorConstants.SCHEMA_ANY_OF, alternatives);
            tagSchema = combined;
        }
        JsonArray values = new JsonArray();
        values.add(field(element, context.registry(registry, false)));
        values.add(field("tag", tagSchema));
        JsonObject schema = new JsonObject();
        schema.add(DatapackEditorConstants.SCHEMA_ONE_OF, values);
        return schema;
    }

    private static JsonObject field(String name, JsonElement value) {
        JsonObject properties = new JsonObject();
        properties.add(name, value);
        JsonArray required = new JsonArray();
        required.add(name);
        JsonObject schema = new JsonObject();
        schema.addProperty(DatapackEditorConstants.JSON_TYPE, DatapackEditorConstants.SCHEMA_OBJECT);
        schema.add(DatapackEditorConstants.JSON_PROPERTIES, properties);
        schema.add(DatapackEditorConstants.SCHEMA_REQUIRED, required);
        schema.addProperty(DatapackEditorConstants.SCHEMA_ADDITIONAL_PROPERTIES, false);
        return schema;
    }
}
