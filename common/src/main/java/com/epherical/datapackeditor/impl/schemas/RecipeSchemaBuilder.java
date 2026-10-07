package com.epherical.datapackeditor.impl.schemas;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.epherical.datapackeditor.impl.CodecJsonSchemaImpl;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;

public class RecipeSchemaBuilder {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public static String build(MinecraftServer server) {
        return GSON.toJson(buildSchema(server));
    }

    public static JsonObject buildSchema(MinecraftServer server) {
        JsonObject schema = CodecJsonSchemaImpl.create(Recipe.CODEC, server.registryAccess());
        schema.addProperty(DatapackEditorConstants.JSON_TITLE, "Minecraft recipe");
        // todo; provide better description
        schema.addProperty(DatapackEditorConstants.JSON_DESCRIPTION, "Minecraft recipe schema, for vanilla and modded recipes if they're registered. Pulls items, tags, and recipe types from the live server.");
        return schema;
    }
}
