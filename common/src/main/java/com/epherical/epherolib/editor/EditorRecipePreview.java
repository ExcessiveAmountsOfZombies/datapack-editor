package com.epherical.epherolib.editor;

import com.epherical.epherolib.CommonPlatform;
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

/** Generic recipe displays and an opt-in bridge for recipes with custom machines. */
public class EditorRecipePreview {
    private static final Map<Identifier, BiFunction<MinecraftServer, Recipe<?>, List<Display>>> ADAPTERS = new ConcurrentHashMap<>();
    private final MinecraftServer server;

    public EditorRecipePreview(MinecraftServer server) {
        this.server = server;
    }

    /** Register during mod initialization. The adapter runs on the server thread. */
    public static void register(Identifier serializer, BiFunction<MinecraftServer, Recipe<?>, List<Display>> adapter) {
        ADAPTERS.put(Objects.requireNonNull(serializer), Objects.requireNonNull(adapter));
    }

    public record Slot(String label, SlotDisplay display) {}
    public record Display(String title, List<Slot> slots) {}

    public void registerRoutes(JavalinConfig config) {
        config.routes.post("/api/recipe-preview", context -> {
            try {
                JsonObject definition = JsonParser.parseString(context.body()).getAsJsonObject();
                JsonObject result = CompletableFuture.supplyAsync(() -> resolve(definition), server).get(15, TimeUnit.SECONDS);
                context.header("Cache-Control", "no-store").contentType("application/json").result(result.toString());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                context.status(HttpStatus.SERVICE_UNAVAILABLE);
            } catch (JsonParseException | IllegalStateException | IllegalArgumentException | ExecutionException | TimeoutException exception) {
                JsonObject error = new JsonObject();
                Throwable cause = exception instanceof ExecutionException ? exception.getCause() : exception;
                error.addProperty("error", cause.getMessage());
                context.status(HttpStatus.BAD_REQUEST).contentType("application/json").result(error.toString());
            }
        });
    }

    public JsonObject resolve(JsonObject definition) {
        JsonObject result = new JsonObject();
        boolean enabled = CommonPlatform.platform.previewConditionsMatch(server, definition);
        result.addProperty("enabled", enabled);
        if (!enabled) return result;
        Recipe<?> recipe = Recipe.CODEC.parse(CommonPlatform.platform.previewOps(server), definition).getOrThrow(JsonParseException::new);
        var adapter = ADAPTERS.get(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()));
        List<Display> displays = adapter == null ? recipe.display().stream().map(EditorRecipePreview::standardDisplay).toList()
                : adapter.apply(server, recipe);
        EditorIngredientPreview resolver = new EditorIngredientPreview(server);
        JsonArray encodedDisplays = new JsonArray();
        displays.stream().limit(16).forEach(display -> {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("title", display.title());
            JsonArray slots = new JsonArray();
            display.slots().stream().limit(64).forEach(slot -> {
                JsonObject encodedSlot = new JsonObject();
                encodedSlot.addProperty("label", slot.label());
                try {
                    encodedSlot.add("items", resolver.resolveDisplay(slot.display()));
                } catch (RuntimeException exception) {
                    encodedSlot.add("items", new JsonArray());
                    encodedSlot.addProperty("error", "Display unavailable: " + exception.getMessage());
                }
                slots.add(encodedSlot);
            });
            encoded.add("slots", slots);
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
                slots.add(new Slot("Ingredient", furnace.ingredient()));
                slots.add(new Slot("Fuel", furnace.fuel()));
            }
            case SmithingRecipeDisplay smithing -> {
                slots.add(new Slot("Template", smithing.template()));
                slots.add(new Slot("Base", smithing.base()));
                slots.add(new Slot("Addition", smithing.addition()));
            }
            case StonecutterRecipeDisplay stonecutter -> slots.add(new Slot("Ingredient", stonecutter.input()));
            default -> title = "Custom display · result and station only";
        }
        slots.add(new Slot("Result", display.result()));
        slots.add(new Slot("Station", display.craftingStation()));
        return new Display(title, slots);
    }
}
