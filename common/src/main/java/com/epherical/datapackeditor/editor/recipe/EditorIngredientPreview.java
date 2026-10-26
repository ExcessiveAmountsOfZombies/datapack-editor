package com.epherical.datapackeditor.editor.recipe;

import com.epherical.datapackeditor.CommonPlatform;
import com.epherical.datapackeditor.DatapackEditorConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.epherical.datapackeditor.editor.http.HttpRoutes;
import com.epherical.datapackeditor.editor.http.HttpContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;


public class EditorIngredientPreview {
    private final MinecraftServer server;

    public EditorIngredientPreview(MinecraftServer server) {
        this.server = server;
    }

    public void registerRoutes(HttpRoutes routes) {
        routes.post("/api/ingredient-preview", this::preview);
    }

    private void preview(HttpContext context) {
        try {
            JsonObject request = JsonParser.parseString(context.body()).getAsJsonObject();
            JsonObject ingredients = request.getAsJsonObject(DatapackEditorConstants.JSON_INGREDIENTS);
            if (ingredients == null || !request.has(DatapackEditorConstants.JSON_RECIPE))
                throw new IllegalArgumentException("Expected recipe and ingredients");
            if (ingredients.size() > DatapackEditorConstants.RECIPE_MAX_SLOTS)
                throw new IllegalArgumentException("At most " + DatapackEditorConstants.RECIPE_MAX_SLOTS + " ingredient slots are allowed");
            JsonObject response = CompletableFuture.supplyAsync(() -> {
                JsonObject result = new JsonObject();
                result.addProperty(DatapackEditorConstants.JSON_ENABLED, CommonPlatform.platform.previewConditionsMatch(server, request.get(DatapackEditorConstants.JSON_RECIPE)));
                result.add(DatapackEditorConstants.JSON_SLOTS, resolve(ingredients));
                return result;
            }, server).get(DatapackEditorConstants.WORK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            context.header(DatapackEditorConstants.HTTP_CACHE_CONTROL, DatapackEditorConstants.HTTP_NO_STORE).contentType(DatapackEditorConstants.HTTP_JSON).result(response.toString());
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException exception) {
            error(context, DatapackEditorConstants.HTTP_BAD_REQUEST, "Invalid ingredients: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            error(context, DatapackEditorConstants.HTTP_SERVICE_UNAVAILABLE, "Ingredient preview interrupted");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            error(context, cause instanceof IllegalArgumentException || cause instanceof JsonParseException
                            ? DatapackEditorConstants.HTTP_BAD_REQUEST : DatapackEditorConstants.HTTP_INTERNAL_SERVER_ERROR,
                    "Ingredient preview failed: " + cause.getMessage());
        } catch (TimeoutException exception) {
            error(context, DatapackEditorConstants.HTTP_SERVICE_UNAVAILABLE, "The server could not resolve the ingredients");
        }
    }

    public JsonObject resolve(JsonObject ingredients) {
        var ops = CommonPlatform.platform.previewOps(server);
        JsonObject response = new JsonObject();
        ingredients.entrySet().forEach(entry -> {
            JsonObject result = new JsonObject();
            try {
                Ingredient ingredient = Ingredient.CODEC.parse(ops, entry.getValue()).getOrThrow(JsonParseException::new);
                JsonArray stacks = encodeStacks(displayStacks(ingredient.display())
                        .filter(ingredient::test), true);
                result.add(DatapackEditorConstants.SCHEMA_ITEMS, stacks);
                if (stacks.isEmpty())
                    result.addProperty("warning", "This ingredient has no matching display items in the running server");
            } catch (RuntimeException exception) {
                result.add(DatapackEditorConstants.SCHEMA_ITEMS, new JsonArray());
                result.addProperty(DatapackEditorConstants.JSON_ERROR, exception.getMessage() == null ? "Ingredient display unavailable" : exception.getMessage());
            }
            response.add(entry.getKey(), result);
        });
        return response;
    }

    public JsonArray resolveDisplay(SlotDisplay display) {
        return encodeStacks(displayStacks(display), false);
    }

    private Stream<ItemStack> displayStacks(SlotDisplay display) {
        var displayContext = SlotDisplayContext.fromLevel(server.overworld());
        return display.resolve(displayContext, SlotDisplay.ItemStackContentsFactory.INSTANCE)
                .filter(stack -> !stack.isEmpty());
    }

    private JsonArray encodeStacks(Stream<ItemStack> stacks, boolean distinct) {
        var ops = CommonPlatform.platform.previewOps(server);
        Stream<JsonObject> encoded = stacks.map(stack -> {
            JsonObject value = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow().getAsJsonObject();
            value.addProperty(DatapackEditorConstants.JSON_COUNT, stack.getCount());
            return value;
        });
        // Deduplicate before the cap so repeated intersection candidates do not crowd out alternatives.
        if (distinct) encoded = encoded.distinct();
        JsonArray result = new JsonArray();
        encoded.limit(128).forEach(result::add);
        return result;
    }

    private void error(HttpContext context, int status, String message) {
        JsonObject response = new JsonObject();
        response.addProperty(DatapackEditorConstants.JSON_ERROR, message);
        context.status(status).contentType(DatapackEditorConstants.HTTP_JSON).result(response.toString());
    }
}
