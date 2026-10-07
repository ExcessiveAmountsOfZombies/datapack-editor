package com.epherical.datapackeditor.editor.recipe;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.google.gson.Gson;
import io.javalin.config.JavalinConfig;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class EditorRecipeCatalog {
    private static final Gson GSON = new Gson();
    private final MinecraftServer server;

    public EditorRecipeCatalog(MinecraftServer server) {
        this.server = server;
    }

    public void registerRoutes(JavalinConfig config) {
        config.routes.get("/api/recipe-items", context -> context
                .header(DatapackEditorConstants.HTTP_CACHE_CONTROL, DatapackEditorConstants.HTTP_NO_STORE)
                .contentType(DatapackEditorConstants.HTTP_JSON)
                .result(GSON.toJson(BuiltInRegistries.ITEM.keySet().stream()
                        .map(Object::toString)
                        .filter(id -> !id.equals("minecraft:air"))
                        .sorted()
                        .toList())));
        config.routes.get("/api/recipe-tags", context -> {
            Registry<Item> items = server.registryAccess().lookupOrThrow(Registries.ITEM);
            Map<String, List<String>> tags = new TreeMap<>();
            items.getTags().forEach(tag -> tags.put(tag.key().location().toString(),
                    tag.stream().map(holder -> items.getKey(holder.value()).toString()).sorted().toList()));
            context.header(DatapackEditorConstants.HTTP_CACHE_CONTROL, DatapackEditorConstants.HTTP_NO_STORE)
                    .contentType(DatapackEditorConstants.HTTP_JSON)
                    .result(GSON.toJson(tags));
        });
    }
}
