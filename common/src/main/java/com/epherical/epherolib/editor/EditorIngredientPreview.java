package com.epherical.epherolib.editor;

import com.epherical.epherolib.CommonPlatform;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class EditorIngredientPreview {
    private final MinecraftServer server;

    public EditorIngredientPreview(MinecraftServer server) {
        this.server = server;
    }

    public void registerRoutes(JavalinConfig config) {
        config.routes.post("/api/ingredient-preview", this::preview);
    }

    private void preview(Context context) {
        try {
            JsonObject request = JsonParser.parseString(context.body()).getAsJsonObject();
            JsonObject ingredients = request.getAsJsonObject("ingredients");
            if (ingredients == null || !request.has("recipe")) throw new IllegalArgumentException("Expected recipe and ingredients");
            if (ingredients.size() > 64) throw new IllegalArgumentException("At most 64 ingredient slots are allowed");
            JsonObject response = CompletableFuture.supplyAsync(() -> {
                JsonObject result = new JsonObject();
                result.addProperty("enabled", CommonPlatform.platform.previewConditionsMatch(server, request.get("recipe")));
                result.add("slots", resolve(ingredients));
                return result;
            }, server).get(15, TimeUnit.SECONDS);
            context.header("Cache-Control", "no-store").contentType("application/json").result(response.toString());
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException exception) {
            error(context, HttpStatus.BAD_REQUEST, "Invalid ingredients: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            error(context, HttpStatus.SERVICE_UNAVAILABLE, "Ingredient preview interrupted");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            error(context, cause instanceof IllegalArgumentException || cause instanceof JsonParseException
                    ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR,
                    "Ingredient preview failed: " + cause.getMessage());
        } catch (TimeoutException exception) {
            error(context, HttpStatus.SERVICE_UNAVAILABLE, "The server could not resolve the ingredients");
        }
    }

    public JsonObject resolve(JsonObject ingredients) {
        var ops = CommonPlatform.platform.previewOps(server);
        JsonObject response = new JsonObject();
        ingredients.entrySet().forEach(entry -> {
            JsonObject result = new JsonObject();
            try {
                Ingredient ingredient = Ingredient.CODEC.parse(ops, entry.getValue()).getOrThrow(JsonParseException::new);
                JsonArray stacks = resolveDisplay(ingredient.display());
                result.add("items", stacks);
                if (stacks.isEmpty()) result.addProperty("warning", "This ingredient has no display items in the running server");
            } catch (RuntimeException exception) {
                result.add("items", new JsonArray());
                result.addProperty("error", exception.getMessage() == null ? "Ingredient display unavailable" : exception.getMessage());
            }
            response.add(entry.getKey(), result);
        });
        return response;
    }

    public JsonArray resolveDisplay(SlotDisplay display) {
        var displayBuilder = new ContextMap.Builder().withParameter(SlotDisplayContext.REGISTRIES, server.registryAccess());
        if (server.fuelValues() != null) displayBuilder.withParameter(SlotDisplayContext.FUEL_VALUES, server.fuelValues());
        var displayContext = displayBuilder.create(SlotDisplayContext.CONTEXT);
        var ops = CommonPlatform.platform.previewOps(server);
        JsonArray stacks = new JsonArray();
        display.resolve(displayContext, SlotDisplay.ItemStackContentsFactory.INSTANCE)
                .filter(stack -> !stack.isEmpty()).limit(128).forEach(stack -> {
                    JsonObject encoded = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow().getAsJsonObject();
                    encoded.addProperty("count", stack.getCount());
                    stacks.add(encoded);
                });
        return stacks;
    }

    private void error(Context context, HttpStatus status, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("error", message);
        context.status(status).contentType("application/json").result(response.toString());
    }
}
