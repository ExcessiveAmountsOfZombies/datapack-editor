package com.epherical.datapackeditor.editor.recipe;

import com.epherical.datapackeditor.DatapackEditorConstants;

import com.epherical.datapackeditor.CommonPlatform;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.javalin.config.JavalinConfig;
import io.javalin.http.HttpStatus;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;


public class EditorRecipePreview {
    private static final Map<ResourceLocation, BiFunction<MinecraftServer, Recipe<?>, List<Display>>> ADAPTERS = new ConcurrentHashMap<>();
    private final MinecraftServer server;

    public EditorRecipePreview(MinecraftServer server) {
        this.server = server;
    }

    public static void register(ResourceLocation serializer, BiFunction<MinecraftServer, Recipe<?>, List<Display>> adapter) {
        ADAPTERS.put(Objects.requireNonNull(serializer), Objects.requireNonNull(adapter));
    }

    public record Slot(String label, List<ItemStack> stacks) {
        public Slot {
            stacks = List.copyOf(stacks.subList(0, Math.min(stacks.size(), DatapackEditorConstants.RECIPE_MAX_CANDIDATES)));
        }
    }
    public record Display(String title, List<Slot> slots) {}

    public void registerRoutes(JavalinConfig config) {
        config.routes.post("/api/recipe-preview", context -> {
            try {
                JsonObject definition = JsonParser.parseString(context.body()).getAsJsonObject();
                JsonObject result = CompletableFuture.supplyAsync(() -> resolve(definition), server).get(DatapackEditorConstants.WORK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                context.header(DatapackEditorConstants.HTTP_CACHE_CONTROL, DatapackEditorConstants.HTTP_NO_STORE).contentType(DatapackEditorConstants.HTTP_JSON).result(result.toString());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                context.status(HttpStatus.SERVICE_UNAVAILABLE);
            } catch (JsonParseException | IllegalStateException | IllegalArgumentException | ExecutionException | TimeoutException exception) {
                JsonObject error = new JsonObject();
                Throwable cause = exception instanceof ExecutionException ? exception.getCause() : exception;
                error.addProperty(DatapackEditorConstants.JSON_ERROR, cause.getMessage());
                context.status(HttpStatus.BAD_REQUEST).contentType(DatapackEditorConstants.HTTP_JSON).result(error.toString());
            }
        });
    }

    public JsonObject resolve(JsonObject definition) {
        JsonObject result = new JsonObject();
        boolean enabled = CommonPlatform.platform.previewConditionsMatch(server, definition);
        result.addProperty(DatapackEditorConstants.JSON_ENABLED, enabled);
        if (!enabled) return result;
        Recipe<?> recipe = Recipe.CODEC.parse(CommonPlatform.platform.previewOps(server), definition).getOrThrow(JsonParseException::new);
        var adapter = ADAPTERS.get(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()));
        List<Display> displays = adapter == null ? List.of(standardDisplay(recipe))
                : adapter.apply(server, recipe);
        EditorIngredientPreview resolver = new EditorIngredientPreview(server);
        JsonArray encodedDisplays = new JsonArray();
        displays.stream().limit(16).forEach(display -> {
            JsonObject encoded = new JsonObject();
            encoded.addProperty(DatapackEditorConstants.JSON_TITLE, display.title());
            JsonArray slots = new JsonArray();
            display.slots().stream().limit(DatapackEditorConstants.RECIPE_MAX_SLOTS).forEach(slot -> {
                JsonObject encodedSlot = new JsonObject();
                encodedSlot.addProperty("label", slot.label());
                try {
                    encodedSlot.add(DatapackEditorConstants.SCHEMA_ITEMS, resolver.resolveDisplay(slot.stacks()));
                } catch (RuntimeException exception) {
                    encodedSlot.add(DatapackEditorConstants.SCHEMA_ITEMS, new JsonArray());
                    encodedSlot.addProperty(DatapackEditorConstants.JSON_ERROR, "Display unavailable: " + exception.getMessage());
                }
                slots.add(encodedSlot);
            });
            encoded.add(DatapackEditorConstants.JSON_SLOTS, slots);
            encodedDisplays.add(encoded);
        });
        result.add("displays", encodedDisplays);
        return result;
    }

    public static Slot ingredient(String label, Ingredient ingredient) {
        return new Slot(label, Arrays.stream(ingredient.getItems()).filter(ingredient::test)
                .limit(DatapackEditorConstants.RECIPE_MAX_CANDIDATES).toList());
    }

    private Display standardDisplay(Recipe<?> recipe) {
        List<Slot> slots = new ArrayList<>();
        String title = "Recipe · " + BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        if (recipe instanceof ShapedRecipe shaped) {
            title = "Crafting " + shaped.getWidth() + " × " + shaped.getHeight();
        } else if (recipe instanceof AbstractCookingRecipe cooking) {
            title = "Cooking · " + cooking.getCookingTime() + " ticks · " + cooking.getExperience() + " XP";
        }
        List<Ingredient> ingredients = recipe.getIngredients();
        for (int index = 0; index < Math.min(ingredients.size(), DatapackEditorConstants.RECIPE_MAX_SLOTS - 2); index++) {
            String label = recipe instanceof ShapedRecipe shaped
                    ? "Row " + (index / shaped.getWidth() + 1) + ", column " + (index % shaped.getWidth() + 1)
                    : "Ingredient" + " " + (index + 1);
            slots.add(ingredient(label, ingredients.get(index)));
        }
        slots.add(new Slot("Result", List.of(recipe.getResultItem(server.registryAccess()))));
        slots.add(new Slot("Station", List.of(recipe.getToastSymbol())));
        return new Display(title, slots);
    }
}
