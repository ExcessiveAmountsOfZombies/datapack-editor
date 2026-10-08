package com.epherical.datapackeditor.editor.feature;

import com.epherical.datapackeditor.DatapackEditorConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LoggerChunkProgressListener;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public class EditorFeatureServerLevel extends ServerLevel {
    private final EditorFeatureLevel preview;
    private final LevelStorageSource.LevelStorageAccess storage;
    private final Path directory;

    public EditorFeatureServerLevel(ServerLevel source, EditorFeatureLevel preview,
                                    LevelStorageSource.LevelStorageAccess storage, Path directory) {
        super(source.getServer(), Runnable::run, storage,
                new DerivedLevelData(source.getServer().getWorldData(), source.getServer().getWorldData().overworldData()),
                Level.OVERWORLD, new LevelStem(source.dimensionTypeRegistration(), source.getChunkSource().getGenerator()),
                LoggerChunkProgressListener.createCompleted(), false, preview.getSeed(), List.of(), false,
                new RandomSequences(preview.getSeed()));
        this.preview = preview;
        this.storage = storage;
        this.directory = directory;
        this.noSave = true;
    }

    public static EditorFeatureServerLevel create(ServerLevel source, EditorFeatureLevel preview) {
        Path directory = null;
        LevelStorageSource.LevelStorageAccess storage = null;
        try {
            directory = Files.createTempDirectory(DatapackEditorConstants.PATH_FEATURE_PREVIEW_TEMP_PREFIX).toAbsolutePath().normalize();
            storage = LevelStorageSource.createDefault(directory).createAccess(DatapackEditorConstants.JSON_PREVIEW);
            return new EditorFeatureServerLevel(source, preview, storage, directory);
        } catch (IOException | RuntimeException exception) {
            if (storage != null) {
                try {
                    storage.close();
                } catch (IOException suppressed) {
                    exception.addSuppressed(suppressed);
                }
            }
            if (directory != null) {
                try {
                    deleteTemporaryDirectory(directory);
                } catch (IOException suppressed) {
                    exception.addSuppressed(suppressed);
                }
            }
            throw new IllegalArgumentException("Could not create the detached feature level", exception);
        }
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return preview.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return preview.getFluidState(pos);
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags) {
        return preview.setBlock(pos, state, flags);
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int limit) {
        return preview.setBlock(pos, state, flags, limit);
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean moved) {
        return preview.removeBlock(pos, moved);
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean drops, Entity breaker, int limit) {
        return preview.destroyBlock(pos, drops, breaker, limit);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return preview.getBlockEntity(pos);
    }

    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean load) {
        return preview.getChunk(x, z, status, load);
    }

    @Override
    public LevelChunk getChunk(int x, int z) {
        throw new UnsupportedOperationException("Feature preview provides generation chunks, not ticking level chunks");
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        return preview.getHeight(type, x, z);
    }

    @Override
    public Holder<Biome> getBiome(BlockPos pos) {
        return preview.getBiome(pos);
    }

    @Override
    public boolean addFreshEntity(Entity entity) {
        return false;
    }

    @Override
    public MinecraftServer getServer() {
        if (preview == null)
            return super.getServer(); // ServerLevel's constructor needs the server's registries and services.
        throw new UnsupportedOperationException("Feature preview does not expose the live Minecraft server");
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            try {
                storage.close();
            } finally {
                deleteTemporaryDirectory(directory);
            }
        }
    }

    private static void deleteTemporaryDirectory(Path directory) throws IOException {
        Path resolved = directory.toAbsolutePath().normalize();
        if (!resolved.getFileName().toString().startsWith(DatapackEditorConstants.PATH_FEATURE_PREVIEW_TEMP_PREFIX)
                || !resolved.getParent().equals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())) {
            throw new IOException("Refusing to remove an unexpected preview directory");
        }
        try (var files = Files.walk(resolved)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.toAbsolutePath().normalize().startsWith(resolved))
                    throw new IOException("Unexpected preview path");
                Files.delete(path);
            }
        }
    }
}
