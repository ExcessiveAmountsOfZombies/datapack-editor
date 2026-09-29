package com.epherical.epherolib.editor;

import com.epherical.epherolib.CommonPlatform;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class EditorLootSimulation {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHEST_SLOTS = 27;
    private static final int MAX_REFERENCE_DEPTH = 32;
    private static final long MAX_REFERENCE_BYTES = 2L * 1024L * 1024L;


    private static final Path WORKSPACE = Path.of(EditorWorkspace.DIRECTORY_NAME).toAbsolutePath().normalize();

    public static final String VALUE = "value";
    public static final String TYPE = "type";
    public static final String LOOT_TABLE = "loot_table";
    public static final String LOOT_TABLES = "loot_tables";
    public static final String DATA = "data";
    public static final String CONTENT_TYPE = "application/json";

    private final MinecraftServer server;

    public EditorLootSimulation(MinecraftServer server) {
        this.server = server;
    }

    public void registerRoutes(JavalinConfig config) {
        config.routes.post("/api/chest-simulation", this::simulateChest);
    }

    private void simulateChest(Context context) {
        JsonObject definition;
        Identifier tableId;
        try {
            String id = context.queryParam("id");
            tableId = id == null ? Identifier.fromNamespaceAndPath("epherolib", "preview") : Identifier.tryParse(id);
            if (tableId == null) throw new IllegalArgumentException("Invalid loot table ID");
            definition = JsonParser.parseString(context.body()).getAsJsonObject();
            JsonElement typeValue = definition.get(TYPE);
            String type = typeValue != null && typeValue.isJsonPrimitive()
                    && typeValue.getAsJsonPrimitive().isString() ? typeValue.getAsString() : "";
            if (!"minecraft:chest".equals(type) && !"chest".equals(type)) {
                error(context, HttpStatus.BAD_REQUEST, "Expected a chest loot table");
                return;
            }
            workspaceReferences(definition, new HashSet<>(), 0);
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException exception) {
            error(context, HttpStatus.BAD_REQUEST, "Invalid loot table: " + exception.getMessage());
            return;
        } catch (IOException exception) {
            LOGGER.warn("Could not read a workspace loot table for the editor preview", exception);
            error(context, HttpStatus.INTERNAL_SERVER_ERROR, "Could not read a referenced workspace loot table");
            return;
        }

        try {
            JsonObject result = CompletableFuture.supplyAsync(() -> roll(definition, tableId), server)
                    .get(15, TimeUnit.SECONDS);
            context.header("Cache-Control", "no-store")
                    .contentType(CONTENT_TYPE)
                    .result(result.toString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            error(context, HttpStatus.SERVICE_UNAVAILABLE, "Loot simulation was interrupted");
        } catch (TimeoutException exception) {
            error(context, HttpStatus.SERVICE_UNAVAILABLE, "The game server did not respond to the loot simulation");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof JsonParseException || cause instanceof IllegalArgumentException) {
                error(context, HttpStatus.BAD_REQUEST, "Invalid loot table: " + cause.getMessage());
            } else {
                LOGGER.warn("Could not simulate a chest loot table for the editor", cause);
                error(context, HttpStatus.INTERNAL_SERVER_ERROR, "Could not simulate this loot table");
            }
        }
    }

    private JsonObject roll(JsonObject definition, Identifier tableId) {
        var registries = server.reloadableRegistries().lookup();
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        LootTable table = CommonPlatform.platform.previewLootTable(server, tableId, definition);
        ServerLevel level = server.overworld();
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(BlockPos.ZERO))
                .create(LootContextParamSets.CHEST);
        long seed = ThreadLocalRandom.current().nextLong();
        if (seed == 0L) seed = 1L;
        SimpleContainer chest = new SimpleContainer(CHEST_SLOTS);
        table.fill(chest, params, seed);

        JsonArray slots = new JsonArray();
        for (int index = 0; index < CHEST_SLOTS; index++) {
            ItemStack stack = chest.getItem(index);
            if (stack.isEmpty()) {
                slots.add(JsonNull.INSTANCE);
                continue;
            }
            JsonObject slot = new JsonObject();
            slot.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            slot.addProperty("count", stack.getCount());
            JsonElement encoded = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(JsonNull.INSTANCE);
            if (encoded.isJsonObject() && encoded.getAsJsonObject().has("components")) {
                slot.add("components", encoded.getAsJsonObject().get("components"));
            }
            slots.add(slot);
        }

        JsonObject result = new JsonObject();
        result.addProperty("seed", Long.toUnsignedString(seed));
        result.add("slots", slots);
        return result;
    }

    // since these don't technically exist, but they exist in the workspace, we need to simulate them.
    // todo; add a way to save the workspace so it exports a datapack
    private void workspaceReferences(JsonElement element, Set<Identifier> chain, int depth) throws IOException {
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                workspaceReferences(child, chain, depth);
            }
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        for (Map.Entry<String, JsonElement> child : object.entrySet()) {
            workspaceReferences(child.getValue(), chain, depth);
        }
        if (!object.has(TYPE) || !object.get(TYPE).isJsonPrimitive()
                || !object.get(TYPE).getAsJsonPrimitive().isString()
                || !object.has(VALUE) || !object.get(VALUE).isJsonPrimitive()
                || !object.get(VALUE).getAsJsonPrimitive().isString()) return;
        String type = object.get(TYPE).getAsString();
        if (!"minecraft:loot_table".equals(type) && !LOOT_TABLE.equals(type)) return;
        Identifier identifier = Identifier.tryParse(object.get(VALUE).getAsString());
        if (identifier == null) return;
        String content = savedWorkspaceTable(identifier);
        if (content == null) return;
        if (depth >= MAX_REFERENCE_DEPTH) {
            throw new IllegalArgumentException("Your loot table reference is too deep, only 32 layers are allowed: " + identifier);
        } else if (!chain.add(identifier)) {
            throw new IllegalArgumentException("Circular workspace loot table detected for: " + identifier);
        }
        try {
            JsonElement nested = JsonParser.parseString(content);
            workspaceReferences(nested, chain, depth + 1);
            object.add(VALUE, nested);
        } finally {
            chain.remove(identifier);
        }
    }

    private String savedWorkspaceTable(Identifier identifier) throws IOException {
        for (String segment : identifier.getPath().split("/")) {
            if (".".equals(segment) || "..".equals(segment)) return null;
        }
        for (String directory : new String[]{LOOT_TABLE, LOOT_TABLES}) {
            Path candidate = WORKSPACE.resolve(DATA).resolve(identifier.getNamespace())
                    .resolve(directory).resolve(identifier.getPath() + ".json").normalize();
            if (!candidate.startsWith(WORKSPACE) || !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!candidate.toRealPath().startsWith(WORKSPACE.toRealPath())
                    || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(candidate) > MAX_REFERENCE_BYTES) {
                throw new IOException("Unsafe or oversized workspace loot table: " + identifier);
            }
            return Files.readString(candidate, StandardCharsets.UTF_8);
        }
        return null;
    }

    private void error(Context context, HttpStatus status, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("error", message);
        context.status(status).contentType(CONTENT_TYPE).result(response.toString());
    }
}
