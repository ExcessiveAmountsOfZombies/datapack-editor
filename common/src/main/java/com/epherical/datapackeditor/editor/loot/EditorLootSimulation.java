package com.epherical.datapackeditor.editor.loot;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.epherical.datapackeditor.CommonPlatform;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
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

    private static final Path WORKSPACE = Path.of(DatapackEditorConstants.PATH_WORKSPACE).toAbsolutePath().normalize();

    private final MinecraftServer server;

    public EditorLootSimulation(MinecraftServer server) {
        this.server = server;
    }

    public void registerRoutes(JavalinConfig config) {
        config.routes.post("/api/chest-simulation", this::simulateChest);
    }

    private void simulateChest(Context context) {
        JsonObject definition;
        ResourceLocation tableId;
        try {
            String id = context.queryParam(DatapackEditorConstants.JSON_ID);
            tableId = id == null ? ResourceLocation.fromNamespaceAndPath("datapack-editor", DatapackEditorConstants.JSON_PREVIEW) : ResourceLocation.tryParse(id);
            if (tableId == null) throw new IllegalArgumentException("Invalid loot table ID");
            definition = JsonParser.parseString(context.body()).getAsJsonObject();
            JsonElement typeValue = definition.get(DatapackEditorConstants.JSON_TYPE);
            String type = typeValue != null && typeValue.isJsonPrimitive()
                    && typeValue.getAsJsonPrimitive().isString() ? typeValue.getAsString() : "";
            if (!"minecraft:chest".equals(type) && !"chest".equals(type)) {
                error(context, HttpStatus.BAD_REQUEST, "Expected a chest loot table");
                return;
            }
            workspaceReferences(definition, new HashSet<>(), 0);
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException exception) {
            error(context, HttpStatus.BAD_REQUEST, DatapackEditorConstants.MESSAGE_INVALID_LOOT_TABLE_PREFIX + exception.getMessage());
            return;
        } catch (IOException exception) {
            LOGGER.warn("Could not read a workspace loot table for the editor preview", exception);
            error(context, HttpStatus.INTERNAL_SERVER_ERROR, "Could not read a referenced workspace loot table");
            return;
        }

        try {
            JsonObject result = CompletableFuture.supplyAsync(() -> roll(definition, tableId), server)
                    .get(DatapackEditorConstants.WORK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            context.header(DatapackEditorConstants.HTTP_CACHE_CONTROL, DatapackEditorConstants.HTTP_NO_STORE)
                    .contentType(DatapackEditorConstants.HTTP_JSON)
                    .result(result.toString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            error(context, HttpStatus.SERVICE_UNAVAILABLE, "Loot simulation was interrupted");
        } catch (TimeoutException exception) {
            error(context, HttpStatus.SERVICE_UNAVAILABLE, "The game server did not respond to the loot simulation");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof JsonParseException || cause instanceof IllegalArgumentException) {
                error(context, HttpStatus.BAD_REQUEST, DatapackEditorConstants.MESSAGE_INVALID_LOOT_TABLE_PREFIX + cause.getMessage());
            } else {
                LOGGER.warn("Could not simulate a chest loot table for the editor", cause);
                error(context, HttpStatus.INTERNAL_SERVER_ERROR, "Could not simulate this loot table");
            }
        }
    }

    private JsonObject roll(JsonObject definition, ResourceLocation tableId) {
        var ops = CommonPlatform.platform.previewOps(server);
        LootTable table = CommonPlatform.platform.previewLootTable(server, tableId, definition);
        ServerLevel level = server.overworld();
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(BlockPos.ZERO))
                .create(LootContextParamSets.CHEST);
        long seed = ThreadLocalRandom.current().nextLong();
        if (seed == 0L) seed = 1L;
        SimpleContainer chest = new SimpleContainer(DatapackEditorConstants.LOOT_CHEST_SLOTS);
        table.fill(chest, params, seed);

        JsonArray slots = new JsonArray();
        for (int index = 0; index < DatapackEditorConstants.LOOT_CHEST_SLOTS; index++) {
            ItemStack stack = chest.getItem(index);
            if (stack.isEmpty()) {
                slots.add(JsonNull.INSTANCE);
                continue;
            }
            JsonObject slot = new JsonObject();
            slot.addProperty(DatapackEditorConstants.JSON_ID, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            slot.addProperty(DatapackEditorConstants.JSON_COUNT, stack.getCount());
            JsonElement encoded = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(JsonNull.INSTANCE);
            if (encoded.isJsonObject() && encoded.getAsJsonObject().has(DatapackEditorConstants.JSON_COMPONENTS)) {
                slot.add(DatapackEditorConstants.JSON_COMPONENTS, encoded.getAsJsonObject().get(DatapackEditorConstants.JSON_COMPONENTS));
            }
            slots.add(slot);
        }

        JsonObject result = new JsonObject();
        result.addProperty(DatapackEditorConstants.JSON_SEED, Long.toUnsignedString(seed));
        result.add(DatapackEditorConstants.JSON_SLOTS, slots);
        return result;
    }

    // since these don't technically exist, but they exist in the workspace, we need to simulate them.
    // todo; add a way to save the workspace so it exports a datapack
    private void workspaceReferences(JsonElement element, Set<ResourceLocation> chain, int depth) throws IOException {
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
        if (!object.has(DatapackEditorConstants.JSON_TYPE) || !object.get(DatapackEditorConstants.JSON_TYPE).isJsonPrimitive()
                || !object.get(DatapackEditorConstants.JSON_TYPE).getAsJsonPrimitive().isString()
                || !object.has(DatapackEditorConstants.JSON_VALUE) || !object.get(DatapackEditorConstants.JSON_VALUE).isJsonPrimitive()
                || !object.get(DatapackEditorConstants.JSON_VALUE).getAsJsonPrimitive().isString()) return;
        String type = object.get(DatapackEditorConstants.JSON_TYPE).getAsString();
        if (!"minecraft:loot_table".equals(type) && !DatapackEditorConstants.PATH_LOOT_TABLE.equals(type)) return;
        ResourceLocation identifier = ResourceLocation.tryParse(object.get(DatapackEditorConstants.JSON_VALUE).getAsString());
        if (identifier == null) return;
        String content = savedWorkspaceTable(identifier);
        if (content == null) return;
        if (depth >= DatapackEditorConstants.PREVIEW_MAX_REFERENCE_DEPTH) {
            throw new IllegalArgumentException("Your loot table reference is too deep, only 32 layers are allowed: " + identifier);
        } else if (!chain.add(identifier)) {
            throw new IllegalArgumentException("Circular workspace loot table detected for: " + identifier);
        }
        try {
            JsonElement nested = JsonParser.parseString(content);
            workspaceReferences(nested, chain, depth + 1);
            object.add(DatapackEditorConstants.JSON_VALUE, nested);
        } finally {
            chain.remove(identifier);
        }
    }

    private String savedWorkspaceTable(ResourceLocation identifier) throws IOException {
        for (String segment : identifier.getPath().split(DatapackEditorConstants.PATH_SEPARATOR)) {
            if (DatapackEditorConstants.PATH_CURRENT_DIRECTORY.equals(segment) || DatapackEditorConstants.PATH_PARENT_DIRECTORY.equals(segment)) return null;
        }
        for (String directory : new String[]{DatapackEditorConstants.PATH_LOOT_TABLE, "loot_tables"}) {
            Path candidate = WORKSPACE.resolve(DatapackEditorConstants.PATH_DATA).resolve(identifier.getNamespace())
                    .resolve(directory).resolve(identifier.getPath() + DatapackEditorConstants.PATH_JSON_EXTENSION).normalize();
            if (!candidate.startsWith(WORKSPACE) || !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!candidate.toRealPath().startsWith(WORKSPACE.toRealPath())
                    || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(candidate) > DatapackEditorConstants.EDITOR_JSON_MAX_BYTES) {
                throw new IOException("Unsafe or oversized workspace loot table: " + identifier);
            }
            return Files.readString(candidate, StandardCharsets.UTF_8);
        }
        return null;
    }

    private void error(Context context, HttpStatus status, String message) {
        JsonObject response = new JsonObject();
        response.addProperty(DatapackEditorConstants.JSON_ERROR, message);
        context.status(status).contentType(DatapackEditorConstants.HTTP_JSON).result(response.toString());
    }
}
