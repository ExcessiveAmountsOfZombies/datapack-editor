package com.epherical.datapackeditor.impl.schemas;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.epherical.datapackeditor.api.schema.CodecJsonSchema;
import com.epherical.datapackeditor.api.schema.CodecJsonSchema.SchemaContext;
import com.epherical.datapackeditor.api.schema.JsonSchemaAnnotation;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.KeybindContents;
import net.minecraft.network.chat.contents.NbtContents;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.ScoreContents;
import net.minecraft.network.chat.contents.SelectorContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.chat.contents.BlockDataSource;
import net.minecraft.network.chat.contents.EntityDataSource;
import net.minecraft.network.chat.contents.StorageDataSource;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class ComponentSchemaRegister {
    private static final Map<String, String> PROPERTY_DESCRIPTIONS = Map.ofEntries(
            Map.entry(DatapackEditorConstants.JSON_BLOCK, "The block position used as the NBT source."),
            Map.entry("bold", "Whether the component uses bold text."),
            Map.entry("clickEvent", "The event run when the component is clicked."),
            Map.entry("color", "The component's text color."),
            Map.entry(DatapackEditorConstants.JSON_ENTITY, "The entity selector used as the NBT source."),
            Map.entry(DatapackEditorConstants.JSON_EXTRA, "Additional components appended after this component."),
            Map.entry("fallback", "Text used when the translation key cannot be resolved."),
            Map.entry("font", "The font used to render the component."),
            Map.entry("hoverEvent", "The content shown when the component is hovered."),
            Map.entry("insertion", "Text inserted into chat when the component is shift-clicked."),
            Map.entry("interpret", "Whether an NBT value is interpreted as a text component."),
            Map.entry("italic", "Whether the component uses italic text."),
            Map.entry(DatapackEditorConstants.JSON_KEYBIND, "The key mapping whose current binding is displayed."),
            Map.entry(DatapackEditorConstants.JSON_NBT, "The NBT path whose selected value is displayed."),
            Map.entry("obfuscated", "Whether the component uses obfuscated text."),
            Map.entry(DatapackEditorConstants.JSON_SCORE, "The scoreboard value displayed by this component."),
            Map.entry(DatapackEditorConstants.JSON_SELECTOR, "The entity selector whose matched names are displayed."),
            Map.entry("separator", "The component placed between multiple selected values."),
            Map.entry(DatapackEditorConstants.JSON_SOURCE, "The source type used to read an NBT value."),
            Map.entry(DatapackEditorConstants.JSON_STORAGE, "The command-storage identifier used as the NBT source."),
            Map.entry("strikethrough", "Whether the component uses strikethrough text."),
            Map.entry(DatapackEditorConstants.JSON_TEXT, "The literal text displayed by this component."),
            Map.entry("translate", "The translation key resolved from language resources."),
            Map.entry(DatapackEditorConstants.JSON_TYPE, "The explicit content type of this component."),
            Map.entry("underlined", "Whether the component uses underlined text."),
            Map.entry(DatapackEditorConstants.JSON_WITH, "Values inserted into placeholders in the translated text.")
    );

    private static boolean registered;

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        CodecJsonSchema.registerProvider(ComponentSerialization.CODEC, context ->
                context.definition(DatapackEditorConstants.EDITOR_COMPONENT_DEFINITION, () -> componentSchema(context)));
        CodecJsonSchema.registerAdapter(ComponentSerialization.CODEC, (context, inferred) -> {
            PROPERTY_DESCRIPTIONS.forEach((property, description) -> context.annotate(
                    inferred,
                    property,
                    JsonSchemaAnnotation.builder().description(description).build()
            ));
            return describedComponent(inferred);
        });
    }

    private static JsonObject componentSchema(SchemaContext context) {
        List<ComponentVariant> variants = List.of(
                new ComponentVariant(DatapackEditorConstants.JSON_TEXT, PlainTextContents.CODEC),
                new ComponentVariant("translatable", TranslatableContents.CODEC),
                new ComponentVariant(DatapackEditorConstants.JSON_KEYBIND, KeybindContents.CODEC),
                new ComponentVariant(DatapackEditorConstants.JSON_SCORE, ScoreContents.CODEC),
                new ComponentVariant(DatapackEditorConstants.JSON_SELECTOR, SelectorContents.CODEC),
                new ComponentVariant(DatapackEditorConstants.JSON_NBT, NbtContents.CODEC));

        JsonObject componentObject = type(DatapackEditorConstants.SCHEMA_OBJECT);
        JsonObject properties = new JsonObject();
        JsonArray contentAlternatives = new JsonArray();
        for (ComponentVariant variant : variants) {
            JsonObject compact = resolvedSchemaObject(context, variant.codec());
            if (variant.type().equals(DatapackEditorConstants.JSON_NBT)) {
                augmentDispatchedFields(context, compact, DatapackEditorConstants.JSON_SOURCE, List.of(
                        new ComponentVariant(DatapackEditorConstants.JSON_ENTITY, EntityDataSource.CODEC),
                        new ComponentVariant(DatapackEditorConstants.JSON_BLOCK, BlockDataSource.CODEC),
                        new ComponentVariant(DatapackEditorConstants.JSON_STORAGE, StorageDataSource.CODEC)));
            }
            mergeProperties(properties, compact);
            contentAlternatives.add(compact);

            JsonObject typed = compact.deepCopy();
            addDiscriminator(typed, DatapackEditorConstants.JSON_TYPE, variant.type());
            typed.addProperty(DatapackEditorConstants.JSON_TITLE, variant.type());
            contentAlternatives.add(typed);
        }

        mergeProperties(properties, resolvedSchemaObject(context, Style.Serializer.MAP_CODEC));
        properties.add(DatapackEditorConstants.JSON_TYPE, stringEnum(variants.stream().map(ComponentVariant::type).toList()));

        // Build one recursive reference, then copy it for independent schema branches.
        JsonObject componentReference = new JsonObject();
        componentReference.addProperty(DatapackEditorConstants.SCHEMA_REF, DatapackEditorConstants.SCHEMA_DEFINITIONS_PREFIX + DatapackEditorConstants.EDITOR_COMPONENT_DEFINITION);
        properties.add(DatapackEditorConstants.JSON_WITH, array(alternatives(List.of(
                type(DatapackEditorConstants.SCHEMA_STRING), type(DatapackEditorConstants.SCHEMA_NUMBER), type(DatapackEditorConstants.SCHEMA_BOOLEAN), componentReference.deepCopy())), null));
        properties.add(DatapackEditorConstants.JSON_EXTRA, array(componentReference.deepCopy(), 1));
        componentObject.add(DatapackEditorConstants.JSON_PROPERTIES, properties);
        componentObject.add(DatapackEditorConstants.SCHEMA_ANY_OF, contentAlternatives);

        JsonArray forms = new JsonArray();
        forms.add(type(DatapackEditorConstants.SCHEMA_STRING));
        forms.add(array(componentReference.deepCopy(), 1));
        forms.add(componentObject);
        JsonObject schema = new JsonObject();
        schema.add(DatapackEditorConstants.SCHEMA_ANY_OF, forms);
        return schema;
    }

    private static void augmentDispatchedFields(SchemaContext context, JsonObject schema,
                                                String discriminator, List<ComponentVariant> variants) {
        JsonObject properties = schema.has(DatapackEditorConstants.JSON_PROPERTIES) && schema.get(DatapackEditorConstants.JSON_PROPERTIES).isJsonObject()
                ? schema.getAsJsonObject(DatapackEditorConstants.JSON_PROPERTIES) : new JsonObject();
        JsonArray alternatives = new JsonArray();
        for (ComponentVariant variant : variants) {
            JsonObject compact = resolvedSchemaObject(context, variant.codec());
            mergeProperties(properties, compact);
            alternatives.add(compact);

            JsonObject typed = compact.deepCopy();
            addDiscriminator(typed, discriminator, variant.type());
            typed.addProperty(DatapackEditorConstants.JSON_TITLE, variant.type());
            alternatives.add(typed);
        }
        properties.add(discriminator, stringEnum(variants.stream().map(ComponentVariant::type).toList()));
        schema.add(DatapackEditorConstants.JSON_PROPERTIES, properties);

        JsonArray allOf = schema.has(DatapackEditorConstants.SCHEMA_ALL_OF) && schema.get(DatapackEditorConstants.SCHEMA_ALL_OF).isJsonArray()
                ? schema.getAsJsonArray(DatapackEditorConstants.SCHEMA_ALL_OF) : new JsonArray();
        JsonObject alternativesSchema = new JsonObject();
        alternativesSchema.add(DatapackEditorConstants.SCHEMA_ANY_OF, alternatives);
        allOf.add(alternativesSchema);
        schema.add(DatapackEditorConstants.SCHEMA_ALL_OF, allOf);
    }

    private static JsonObject resolvedSchemaObject(SchemaContext context, MapCodec<?> codec) {
        JsonElement schema = context.resolve(context.schema(codec));
        return schema.isJsonObject() ? schema.getAsJsonObject() : new JsonObject();
    }

    private static void mergeProperties(JsonObject target, JsonObject schema) {
        if (!schema.has(DatapackEditorConstants.JSON_PROPERTIES) || !schema.get(DatapackEditorConstants.JSON_PROPERTIES).isJsonObject()) return;
        schema.getAsJsonObject(DatapackEditorConstants.JSON_PROPERTIES).entrySet().forEach(entry -> {
            JsonElement existing = target.get(entry.getKey());
            JsonElement addition = entry.getValue();
            if (existing == null) {
                target.add(entry.getKey(), addition.deepCopy());
            } else if (!existing.equals(addition)) {
                target.add(entry.getKey(), alternatives(List.of(existing, addition)));
            }
        });
    }

    private static void addDiscriminator(JsonObject schema, String field, String value) {
        schema.addProperty(DatapackEditorConstants.JSON_TYPE, DatapackEditorConstants.SCHEMA_OBJECT);
        JsonObject properties = schema.has(DatapackEditorConstants.JSON_PROPERTIES) && schema.get(DatapackEditorConstants.JSON_PROPERTIES).isJsonObject()
                ? schema.getAsJsonObject(DatapackEditorConstants.JSON_PROPERTIES) : new JsonObject();
        JsonObject exact = new JsonObject();
        exact.addProperty(DatapackEditorConstants.SCHEMA_CONST, value);
        properties.add(field, exact);
        schema.add(DatapackEditorConstants.JSON_PROPERTIES, properties);
        Set<String> required = new LinkedHashSet<>();
        if (schema.has(DatapackEditorConstants.SCHEMA_REQUIRED) && schema.get(DatapackEditorConstants.SCHEMA_REQUIRED).isJsonArray()) {
            schema.getAsJsonArray(DatapackEditorConstants.SCHEMA_REQUIRED).forEach(element -> required.add(element.getAsString()));
        }
        required.add(field);
        JsonArray names = new JsonArray();
        required.forEach(names::add);
        schema.add(DatapackEditorConstants.SCHEMA_REQUIRED, names);
    }

    private static JsonObject alternatives(List<JsonElement> schemas) {
        Set<JsonElement> unique = new LinkedHashSet<>(schemas);
        if (unique.size() == 1) return unique.iterator().next().getAsJsonObject();
        JsonArray choices = new JsonArray();
        unique.forEach(choices::add);
        JsonObject schema = new JsonObject();
        schema.add(DatapackEditorConstants.SCHEMA_ANY_OF, choices);
        return schema;
    }

    private static JsonObject type(String type) {
        JsonObject schema = new JsonObject();
        schema.addProperty(DatapackEditorConstants.JSON_TYPE, type);
        return schema;
    }

    private static JsonObject array(JsonElement items, Integer minimum) {
        JsonObject schema = type(DatapackEditorConstants.SCHEMA_ARRAY);
        schema.add(DatapackEditorConstants.SCHEMA_ITEMS, items);
        if (minimum != null) schema.addProperty(DatapackEditorConstants.SCHEMA_MIN_ITEMS, minimum);
        return schema;
    }

    private static JsonObject stringEnum(Collection<String> values) {
        JsonObject schema = type(DatapackEditorConstants.SCHEMA_STRING);
        JsonArray entries = new JsonArray();
        values.forEach(entries::add);
        schema.add(DatapackEditorConstants.SCHEMA_ENUM, entries);
        return schema;
    }

    private static JsonObject describedComponent(JsonElement inferred) {
        JsonObject schema = new JsonObject();
        JsonArray allOf = new JsonArray();
        allOf.add(inferred);
        schema.add(DatapackEditorConstants.SCHEMA_ALL_OF, allOf);
        schema.addProperty(DatapackEditorConstants.JSON_TITLE, "Minecraft text component");
        schema.addProperty(DatapackEditorConstants.JSON_DESCRIPTION, "A Minecraft text component that represents text and its style.");
        return schema;
    }

    private record ComponentVariant(String type, MapCodec<?> codec) { }
}
