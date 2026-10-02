package com.epherical.datapackeditor.impl.schemas;

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
import net.minecraft.network.chat.contents.ObjectContents;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.ScoreContents;
import net.minecraft.network.chat.contents.SelectorContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.chat.contents.data.BlockDataSource;
import net.minecraft.network.chat.contents.data.EntityDataSource;
import net.minecraft.network.chat.contents.data.StorageDataSource;
import net.minecraft.network.chat.contents.objects.AtlasSprite;
import net.minecraft.network.chat.contents.objects.PlayerSprite;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ComponentSchemaRegister {
    private static final String COMPONENT_DEFINITION = "minecraft_component";
    private static final String COMPONENT_DESCRIPTION = "A Minecraft text component that represents text and its style.";
    private static final Map<String, String> PROPERTY_DESCRIPTIONS = Map.ofEntries(
            Map.entry("atlas", "The texture atlas used by an atlas sprite component."),
            Map.entry("block", "The block position used as the NBT source."),
            Map.entry("bold", "Whether the component uses bold text."),
            Map.entry("click_event", "The event run when the component is clicked."),
            Map.entry("color", "The component's text color."),
            Map.entry("entity", "The entity selector used as the NBT source."),
            Map.entry("extra", "Additional components appended after this component."),
            Map.entry("fallback", "Text used when the translation key cannot be resolved."),
            Map.entry("font", "The font used to render the component."),
            Map.entry("hat", "Whether the component uses the hat text style."),
            Map.entry("hover_event", "The content shown when the component is hovered."),
            Map.entry("insertion", "Text inserted into chat when the component is shift-clicked."),
            Map.entry("interpret", "Whether an NBT value is interpreted as a text component."),
            Map.entry("italic", "Whether the component uses italic text."),
            Map.entry("keybind", "The key mapping whose current binding is displayed."),
            Map.entry("nbt", "The NBT path whose selected value is displayed."),
            Map.entry("object", "The object component type."),
            Map.entry("obfuscated", "Whether the component uses obfuscated text."),
            Map.entry("plain", "Whether a player sprite uses its plain rendering form."),
            Map.entry("player", "The player profile used by a player sprite component."),
            Map.entry("score", "The scoreboard value displayed by this component."),
            Map.entry("selector", "The entity selector whose matched names are displayed."),
            Map.entry("separator", "The component placed between multiple selected values."),
            Map.entry("shadow_color", "The component's text-shadow color."),
            Map.entry("source", "The source type used to read an NBT value."),
            Map.entry("sprite", "The sprite displayed by an atlas sprite component."),
            Map.entry("storage", "The command-storage identifier used as the NBT source."),
            Map.entry("strikethrough", "Whether the component uses strikethrough text."),
            Map.entry("text", "The literal text displayed by this component."),
            Map.entry("translate", "The translation key resolved from language resources."),
            Map.entry("type", "The explicit content type of this component."),
            Map.entry("underlined", "Whether the component uses underlined text."),
            Map.entry("with", "Values inserted into placeholders in the translated text.")
    );

    private static boolean registered;

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        CodecJsonSchema.registerProvider(ComponentSerialization.CODEC, context ->
                context.definition(COMPONENT_DEFINITION, () -> componentSchema(context)));
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
                new ComponentVariant("text", PlainTextContents.MAP_CODEC),
                new ComponentVariant("translatable", TranslatableContents.MAP_CODEC),
                new ComponentVariant("keybind", KeybindContents.MAP_CODEC),
                new ComponentVariant("score", ScoreContents.MAP_CODEC),
                new ComponentVariant("selector", SelectorContents.MAP_CODEC),
                new ComponentVariant("nbt", NbtContents.MAP_CODEC),
                new ComponentVariant("object", ObjectContents.MAP_CODEC));

        JsonObject componentObject = type("object");
        JsonObject properties = new JsonObject();
        JsonArray contentAlternatives = new JsonArray();
        for (ComponentVariant variant : variants) {
            JsonObject compact = resolvedSchemaObject(context, variant.codec());
            if (variant.type().equals("nbt")) {
                augmentDispatchedFields(context, compact, "source", List.of(
                        new ComponentVariant("entity", EntityDataSource.MAP_CODEC),
                        new ComponentVariant("block", BlockDataSource.MAP_CODEC),
                        new ComponentVariant("storage", StorageDataSource.MAP_CODEC)));
            } else if (variant.type().equals("object")) {
                augmentDispatchedFields(context, compact, "object", List.of(
                        new ComponentVariant("atlas", AtlasSprite.MAP_CODEC),
                        new ComponentVariant("player", PlayerSprite.MAP_CODEC)));
            }
            mergeProperties(properties, compact);
            contentAlternatives.add(compact);

            JsonObject typed = compact.deepCopy();
            addDiscriminator(typed, "type", variant.type());
            typed.addProperty("title", variant.type());
            contentAlternatives.add(typed);
        }

        mergeProperties(properties, resolvedSchemaObject(context, Style.Serializer.MAP_CODEC));
        properties.add("type", stringEnum(variants.stream().map(ComponentVariant::type).toList()));

        // Build one recursive reference, then copy it for independent schema branches.
        JsonObject componentReference = new JsonObject();
        componentReference.addProperty("$ref", "#/definitions/" + COMPONENT_DEFINITION);
        properties.add("with", array(alternatives(List.of(
                type("string"), type("number"), type("boolean"), componentReference.deepCopy())), null));
        properties.add("extra", array(componentReference.deepCopy(), 1));
        componentObject.add("properties", properties);
        componentObject.add("anyOf", contentAlternatives);

        JsonArray forms = new JsonArray();
        forms.add(type("string"));
        forms.add(array(componentReference.deepCopy(), 1));
        forms.add(componentObject);
        JsonObject schema = new JsonObject();
        schema.add("anyOf", forms);
        return schema;
    }

    private static void augmentDispatchedFields(SchemaContext context, JsonObject schema,
                                                String discriminator, List<ComponentVariant> variants) {
        JsonObject properties = schema.has("properties") && schema.get("properties").isJsonObject()
                ? schema.getAsJsonObject("properties") : new JsonObject();
        JsonArray alternatives = new JsonArray();
        for (ComponentVariant variant : variants) {
            JsonObject compact = resolvedSchemaObject(context, variant.codec());
            mergeProperties(properties, compact);
            alternatives.add(compact);

            JsonObject typed = compact.deepCopy();
            addDiscriminator(typed, discriminator, variant.type());
            typed.addProperty("title", variant.type());
            alternatives.add(typed);
        }
        properties.add(discriminator, stringEnum(variants.stream().map(ComponentVariant::type).toList()));
        schema.add("properties", properties);

        JsonArray allOf = schema.has("allOf") && schema.get("allOf").isJsonArray()
                ? schema.getAsJsonArray("allOf") : new JsonArray();
        JsonObject alternativesSchema = new JsonObject();
        alternativesSchema.add("anyOf", alternatives);
        allOf.add(alternativesSchema);
        schema.add("allOf", allOf);
    }

    private static JsonObject resolvedSchemaObject(SchemaContext context, MapCodec<?> codec) {
        JsonElement schema = context.resolve(context.schema(codec));
        return schema.isJsonObject() ? schema.getAsJsonObject() : new JsonObject();
    }

    private static void mergeProperties(JsonObject target, JsonObject schema) {
        if (!schema.has("properties") || !schema.get("properties").isJsonObject()) return;
        schema.getAsJsonObject("properties").entrySet().forEach(entry -> {
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
        schema.addProperty("type", "object");
        JsonObject properties = schema.has("properties") && schema.get("properties").isJsonObject()
                ? schema.getAsJsonObject("properties") : new JsonObject();
        JsonObject exact = new JsonObject();
        exact.addProperty("const", value);
        properties.add(field, exact);
        schema.add("properties", properties);
        Set<String> required = new LinkedHashSet<>();
        if (schema.has("required") && schema.get("required").isJsonArray()) {
            schema.getAsJsonArray("required").forEach(element -> required.add(element.getAsString()));
        }
        required.add(field);
        JsonArray names = new JsonArray();
        required.forEach(names::add);
        schema.add("required", names);
    }

    private static JsonObject alternatives(List<JsonElement> schemas) {
        Map<String, JsonElement> unique = new LinkedHashMap<>();
        schemas.forEach(schema -> unique.putIfAbsent(schema.toString(), schema));
        if (unique.size() == 1) return unique.values().iterator().next().getAsJsonObject();
        JsonArray choices = new JsonArray();
        unique.values().forEach(choices::add);
        JsonObject schema = new JsonObject();
        schema.add("anyOf", choices);
        return schema;
    }

    private static JsonObject type(String type) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", type);
        return schema;
    }

    private static JsonObject array(JsonElement items, Integer minimum) {
        JsonObject schema = type("array");
        schema.add("items", items);
        if (minimum != null) schema.addProperty("minItems", minimum);
        return schema;
    }

    private static JsonObject stringEnum(Collection<String> values) {
        JsonObject schema = type("string");
        JsonArray entries = new JsonArray();
        values.forEach(entries::add);
        schema.add("enum", entries);
        return schema;
    }

    private static JsonObject describedComponent(JsonElement inferred) {
        JsonObject schema = new JsonObject();
        JsonArray allOf = new JsonArray();
        allOf.add(inferred);
        schema.add("allOf", allOf);
        schema.addProperty("title", "Minecraft text component");
        schema.addProperty("description", COMPONENT_DESCRIPTION);
        return schema;
    }

    private record ComponentVariant(String type, MapCodec<?> codec) { }
}
