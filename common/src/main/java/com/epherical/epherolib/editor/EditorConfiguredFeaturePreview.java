package com.epherical.epherolib.editor;

import com.epherical.epherolib.CommonPlatform;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class EditorConfiguredFeaturePreview {
    private static final int MAX_REFERENCE_DEPTH = 32;
    private static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;
    private static final Set<String> HOLDER_FIELDS = Set.of("feature", "default", "feature_true", "feature_false", "vegetation_feature");
    private final MinecraftServer server;

    public EditorConfiguredFeaturePreview(MinecraftServer server) {
        this.server = server;
    }

    public void registerRoutes(JavalinConfig config) {
        config.routes.post("/api/configured-feature-preview", this::preview);
    }

    private void preview(Context context) {
        CompletableFuture<JsonObject> pending = null;
        try {
            if (context.body().getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES)
                throw new IllegalArgumentException("Feature preview request exceeds 2 MiB");
            JsonObject request = JsonParser.parseString(context.body()).getAsJsonObject();
            pending = server.submit(() -> generate(request));
            JsonObject result = pending.get(15, TimeUnit.SECONDS);
            context.header("Cache-Control", "no-store").contentType("application/json").result(result.toString());
        } catch (InterruptedException exception) {
            pending.cancel(false);
            Thread.currentThread().interrupt();
            error(context, HttpStatus.SERVICE_UNAVAILABLE, "Feature preview was interrupted");
        } catch (TimeoutException exception) {
            pending.cancel(false);
            error(context, HttpStatus.SERVICE_UNAVAILABLE, "The game server did not respond to the feature preview");
        } catch (ExecutionException exception) {
            error(context, HttpStatus.BAD_REQUEST, "Could not generate feature: " + exception.getCause().getMessage());
        } catch (RuntimeException exception) {
            error(context, HttpStatus.BAD_REQUEST, "Invalid feature preview: " + exception.getMessage());
        }
    }

    public JsonObject generate(JsonObject request) {
        long seed = Long.parseLong(request.get("seed").getAsString());
        JsonObject overrides = request.has("overrides") ? request.getAsJsonObject("overrides") : new JsonObject();
        JsonElement definition = request.getAsJsonObject("feature").deepCopy();
        inlineReferences(definition, overrides, new HashSet<>(), 0, new int[]{0});
        ConfiguredFeature<?, ?> feature = ConfiguredFeature.DIRECT_CODEC
                .parse(CommonPlatform.platform.previewOps(server), definition).getOrThrow();
        int originY = EditorFeatureLevel.SURFACE_Y + 1 - (feature.config() instanceof OreConfiguration ? 10 : 0);
        BlockState blockTerrain = buildTerrain(feature, seed);
        ServerLevel source = server.overworld();
        try (EditorFeatureLevel level = new EditorFeatureLevel(source, seed, blockTerrain)) {
            BlockPos origin = new BlockPos(0, originY, 0);
            boolean placed = feature.place(level, source.getChunkSource().getGenerator(), level.getRandom(), origin);
            Map<BlockPos, BlockState> changes = level.changes();
            JsonArray blocks = new JsonArray();
            changes.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<BlockPos, BlockState> entry) -> entry.getKey().getY())
                    .thenComparingInt(entry -> entry.getKey().getX()).thenComparingInt(entry -> entry.getKey().getZ())).forEach(entry -> {
                if (entry.getValue().isAir()) return;
                JsonObject encoded = BlockState.CODEC.encodeStart(JsonOps.INSTANCE, entry.getValue()).getOrThrow().getAsJsonObject();
                JsonObject block = new JsonObject();
                block.addProperty("id", BuiltInRegistries.BLOCK.getKey(entry.getValue().getBlock()).toString());
                if (encoded.has("Properties")) block.add("properties", encoded.get("Properties"));
                JsonArray pos = new JsonArray();
                pos.add(entry.getKey().getX());
                pos.add(entry.getKey().getY() - EditorFeatureLevel.SURFACE_Y - 1);
                pos.add(entry.getKey().getZ());
                block.add("pos", pos);
                blocks.add(block);
            });
            JsonObject result = new JsonObject();
            result.addProperty("seed", Long.toString(seed));
            result.addProperty("placed", placed);
            result.addProperty("changedBlocks", changes.size());
            result.add("blocks", blocks);
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not close the detached feature level", exception);
        }
    }

    private BlockState buildTerrain(ConfiguredFeature<?, ?> feature, long seed) {
        if (feature.config() instanceof TreeConfiguration) return Blocks.DIRT.defaultBlockState();
        if (feature.config() instanceof OreConfiguration ore) {
            RandomSource random = RandomSource.create(seed);
            for (BlockState candidate : List.of(Blocks.STONE.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState(),
                    Blocks.NETHERRACK.defaultBlockState(), Blocks.END_STONE.defaultBlockState())) {
                for (OreConfiguration.TargetBlockState target : ore.targetStates) {
                    if (target.target.test(candidate, random)) return candidate;
                }
            }
        }
        return null;
    }

    private void inlineReferences(JsonElement element, JsonObject overrides, Set<String> chain, int depth, int[] count) {
        if (depth > MAX_REFERENCE_DEPTH || ++count[0] > 65536) {
            throw new IllegalArgumentException("Feature references exceed the preview resolution limit");
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) inlineReferences(child, overrides, chain, depth, count);
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                String field = entry.getKey();
                JsonElement child = entry.getValue();
                String kind = "feature".equals(field) && object.has("placement") ? "configured_feature" : "placed_feature";
                boolean holder = HOLDER_FIELDS.contains(field);
                if (holder && child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                    entry.setValue(resolveReference(child.getAsString(), kind, overrides, chain, depth, count));
                } else if ("features".equals(field) && child.isJsonArray()) {
                    JsonArray features = child.getAsJsonArray();
                    for (int i = 0; i < features.size(); i++) {
                        JsonElement nested = features.get(i);
                        if (nested.isJsonPrimitive() && nested.getAsJsonPrimitive().isString()) {
                            features.set(i, resolveReference(nested.getAsString(), "placed_feature", overrides, chain, depth, count));
                        } else inlineReferences(nested, overrides, chain, depth, count);
                    }
                } else inlineReferences(child, overrides, chain, depth, count);
            }
        }
    }

    private JsonElement resolveReference(String value, String kind, JsonObject overrides, Set<String> chain, int depth, int[] count) {
        Identifier id = Identifier.tryParse(value);
        if (id == null) return new JsonPrimitive(value);
        String key = kind + "/" + id;
        if (!chain.add(key)) throw new IllegalArgumentException("Circular feature reference: " + key);
        try {
            JsonElement definition = overrides.get(key);
            if (definition == null) definition = savedReference(id, kind);
            var ops = CommonPlatform.platform.previewOps(server);
            if (definition == null && "configured_feature".equals(kind)) {
                var loaded = server.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE).get(ResourceKey.create(Registries.CONFIGURED_FEATURE, id));
                if (loaded.isPresent())
                    definition = ConfiguredFeature.DIRECT_CODEC.encodeStart(ops, loaded.get().value()).getOrThrow();
            } else if (definition == null) {
                var loaded = server.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).get(ResourceKey.create(Registries.PLACED_FEATURE, id));
                if (loaded.isPresent())
                    definition = PlacedFeature.DIRECT_CODEC.encodeStart(ops, loaded.get().value()).getOrThrow();
            }
            if (definition == null) throw new IllegalArgumentException("Missing " + kind + ": " + id);
            if (!definition.isJsonObject())
                throw new IllegalArgumentException("Invalid unsaved feature reference: " + key);
            definition = definition.deepCopy();
            inlineReferences(definition, overrides, chain, depth + 1, count);
            return definition;
        } finally {
            chain.remove(key);
        }
    }

    private JsonElement savedReference(Identifier id, String kind) {
        Path root = Path.of(EditorWorkspace.DIRECTORY_NAME).toAbsolutePath().normalize();
        Path candidate = root.resolve("data").resolve(id.getNamespace()).resolve("worldgen").resolve(kind).resolve(id.getPath() + ".json").normalize();
        if (!candidate.startsWith(root) || !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) || !candidate.toRealPath().startsWith(root.toRealPath())
                    || Files.size(candidate) > MAX_REQUEST_BYTES)
                throw new IllegalArgumentException("Unsafe or oversized feature reference: " + id);
            return JsonParser.parseString(Files.readString(candidate));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot read feature reference: " + id, exception);
        }
    }

    private void error(Context context, HttpStatus status, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("error", message);
        context.status(status).contentType("application/json").result(response.toString());
    }
}
