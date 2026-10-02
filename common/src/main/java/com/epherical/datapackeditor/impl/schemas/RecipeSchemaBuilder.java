package com.epherical.datapackeditor.impl.schemas;

import com.epherical.datapackeditor.impl.CodecJsonSchemaImpl;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;

public class RecipeSchemaBuilder {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public static String build(MinecraftServer server) {
        JsonObject schema = CodecJsonSchemaImpl.create(Recipe.CODEC, server.registryAccess());
        schema.addProperty("title", "Minecraft recipe");
        // todo; provide better description
        schema.addProperty("description", "Generated from the recipe codecs and registries in the running Minecraft server.");
        return GSON.toJson(schema);
    }
}
