package com.epherical.datapackeditor.editor.recipe;

import com.epherical.datapackeditor.CommonPlatform;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.javalin.config.JavalinConfig;
import io.javalin.http.HttpStatus;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

import static com.epherical.datapackeditor.DatapackEditorConstants.*;

public class EditorRecipePreview {
    private static final Map<Identifier, BiFunction<MinecraftServer, Recipe<?>, List<Display>>> ADAPTERS = new ConcurrentHashMap<>();
    private final MinecraftServer server;

    public EditorRecipePreview(MinecraftServer server) {
        this.server = server;
    }

    public static void register(Identifier serializer, BiFunction<MinecraftServer, Recipe<?>, List<Display>> adapter) {
        ADAPTERS.put(Objects.requireNonNull(serializer), Objects.requireNonNull(adapter));
    }

    public record Slot(String label, SlotDisplay display) {}
    public record Display(String title, List<Slot> slots) {}

    public void registerRoutes(JavalinConfig config) {
        config.routes.post("/api/recipe-preview", context -> {
            try {
                JsonObject definition = JsonParser.parseString(context.body()).getAsJsonObject();
                JsonObject result = CompletableFuture.supplyAsync(() -> resolve(definition), server).get(WORK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                context.header(HTTP_CACHE_CONTROL, HTTP_NO_STORE).contentType(HTTP_JSON).result(result.toString());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                context.status(HttpStatus.SERVICE_UNAVAILABLE);
            } catch (JsonParseException | IllegalStateException | IllegalArgumentException | ExecutionException | TimeoutException exception) {
                JsonObject error = new JsonObject();
                Throwable cause = exception instanceof ExecutionException ? exception.getCause() : exception;
                error.addProperty(JSON_ERROR, cause.getMessage());
                context.status(HttpStatus.BAD_REQUEST).contentType(HTTP_JSON).result(error.toString());
            }
        });
    }

    public JsonObject resolve(JsonObject definition) {
        JsonObject result = new JsonObject();
        boolean enabled = CommonPlatform.platform.previewConditionsMatch(server, definition);
        result.addProperty(JSON_ENABLED, enabled);
        if (!enabled) return result;
        Recipe<?> recipe = Recipe.CODEC.parse(CommonPlatform.platform.previewOps(server), definition).getOrThrow(JsonParseException::new);
        var adapter = ADAPTERS.get(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()));
        List<Display> displays = adapter == null ? recipe.display().stream().map(EditorRecipePreview::standardDisplay).toList()
                : adapter.apply(server, recipe);
        EditorIngredientPreview resolver = new EditorIngredientPreview(server);
        JsonArray encodedDisplays = new JsonArray();
        displays.stream().limit(16).forEach(display -> {
            JsonObject encoded = new JsonObject();
            encoded.addProperty(JSON_TITLE, display.title());
            JsonArray slots = new JsonArray();
            display.slots().stream().limit(RECIPE_MAX_SLOTS).forEach(slot -> {
                JsonObject encodedSlot = new JsonObject();
                encodedSlot.addProperty("label", slot.label());
                try {
                    encodedSlot.add(SCHEMA_ITEMS, resolver.resolveDisplay(slot.display()));
                } catch (RuntimeException exception) {
                    encodedSlot.add(SCHEMA_ITEMS, new JsonArray());
                    encodedSlot.addProperty(JSON_ERROR, "Display unavailable: " + exception.getMessage());
                }
                slots.add(encodedSlot);
            });
            encoded.add(JSON_SLOTS, slots);
            encodedDisplays.add(encoded);
        });
        result.add("displays", encodedDisplays);
        return result;
    }

    private static Display standardDisplay(RecipeDisplay display) {
        List<Slot> slots = new ArrayList<>();
        String title = "Recipe display";
        switch (display) {
            case ShapedCraftingRecipeDisplay shaped -> {
                title = "Crafting " + shaped.width() + " × " + shaped.height();
                for (int index = 0; index < shaped.ingredients().size(); index++) {
                    slots.add(new Slot("Row " + (index / shaped.width() + 1) + ", column " + (index % shaped.width() + 1), shaped.ingredients().get(index)));
                }
            }
            case ShapelessCraftingRecipeDisplay shapeless -> {
                title = "Shapeless crafting";
                for (int index = 0; index < shapeless.ingredients().size(); index++) slots.add(new Slot("Ingredient " + (index + 1), shapeless.ingredients().get(index)));
            }
            case FurnaceRecipeDisplay furnace -> {
                title = "Cooking · " + furnace.duration() + " ticks · " + furnace.experience() + " XP";
                slots.add(new Slot(MESSAGE_INGREDIENT, furnace.ingredient()));
                slots.add(new Slot("Fuel", furnace.fuel()));
            }
            case SmithingRecipeDisplay smithing -> {
                slots.add(new Slot("Template", smithing.template()));
                slots.add(new Slot("Base", smithing.base()));
                slots.add(new Slot("Addition", smithing.addition()));
            }
            case StonecutterRecipeDisplay stonecutter -> slots.add(new Slot(MESSAGE_INGREDIENT, stonecutter.input()));
            default -> title = "Custom display · result and station only";
        }
        slots.add(new Slot("Result", display.result()));
        slots.add(new Slot("Station", display.craftingStation()));
        return new Display(title, slots);
    }
}
