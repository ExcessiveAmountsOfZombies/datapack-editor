package com.epherical.datapackeditor.editor.feature;

import com.epherical.datapackeditor.DatapackEditorConstants;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class EditorFeatureLevel implements WorldGenLevel, AutoCloseable {
    private final RegistryAccess registries;
    private final ServerLevel source;
    private EditorFeatureServerLevel serverLevel;
    private final DimensionType dimension;
    private final FeatureFlagSet features;
    private final Holder<Biome> biome;
    private final BiomeManager biomeManager;
    private final PalettedContainerFactory containers;
    private final RandomSource random;
    private final long seed;
    private final BlockState terrainState;
    private final WorldBorder border = new WorldBorder();
    private final Map<BlockPos, BlockState> changes = new LinkedHashMap<>();
    private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
    private final Map<ChunkPos, PreviewChunk> chunks = new HashMap<>();
    private final long deadline = System.nanoTime() + 3000000000L;
    private int operations;
    private long subTick;

    public EditorFeatureLevel(ServerLevel source, long seed, BlockState terrainState) {
        this.source = source;
        this.registries = source.registryAccess();
        this.dimension = source.dimensionType();
        this.features = source.enabledFeatures();
        this.biome = registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        this.biomeManager = new BiomeManager(this, seed);
        this.containers = PalettedContainerFactory.create(registries);
        this.random = RandomSource.create(seed);
        this.seed = seed;
        this.terrainState = terrainState;
        border.setSize(DatapackEditorConstants.FEATURE_RADIUS * 2 + 1);
    }

    public Map<BlockPos, BlockState> changes() {
        return Map.copyOf(changes);
    }

    private void checkBudget() {
        if (++operations > 2_000_000 || (operations % 1024 == 0 && System.nanoTime() > deadline)) {
            throw new IllegalArgumentException("Feature exceeded the preview generation budget");
        }
    }

    private boolean inBounds(BlockPos pos) {
        return pos.getX() >= -DatapackEditorConstants.FEATURE_RADIUS && pos.getX() <= DatapackEditorConstants.FEATURE_RADIUS && pos.getZ() >= -DatapackEditorConstants.FEATURE_RADIUS
                && pos.getZ() <= DatapackEditorConstants.FEATURE_RADIUS && pos.getY() >= DatapackEditorConstants.FEATURE_MIN_PREVIEW_Y && pos.getY() <= DatapackEditorConstants.FEATURE_MAX_PREVIEW_Y;
    }

    private BlockState terrainState(BlockPos pos) {
        int y = pos.getY();
        if (y > DatapackEditorConstants.FEATURE_SURFACE_Y) return Blocks.AIR.defaultBlockState();
        if (terrainState != null) return terrainState;
        return (y == DatapackEditorConstants.FEATURE_SURFACE_Y ? Blocks.GRASS_BLOCK : y >= DatapackEditorConstants.FEATURE_SURFACE_Y - 3 ? Blocks.DIRT : Blocks.STONE).defaultBlockState();
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        checkBudget();
        if (!inBounds(pos)) return Blocks.VOID_AIR.defaultBlockState();
        return changes.getOrDefault(pos, terrainState(pos));
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int updateLimit) {
        checkBudget();
        if (!inBounds(pos))
            throw new IllegalArgumentException("Feature extends outside the 65 × 192 × 65 preview area");
        BlockState previous = getBlockState(pos);
        if (previous == state) return false;
        BlockPos key = pos.immutable();
        if (!changes.containsKey(key) && changes.size() >= DatapackEditorConstants.FEATURE_MAX_CHANGED_BLOCKS) {
            throw new IllegalArgumentException("Feature exceeded the " + DatapackEditorConstants.FEATURE_MAX_CHANGED_BLOCKS + " changed-block preview limit");
        }
        changes.put(key, state);
        if (previous.getBlock() != state.getBlock()) {
            blockEntities.remove(key);
            if (state.getBlock() instanceof EntityBlock entityBlock) {
                BlockEntity entity = entityBlock.newBlockEntity(key, state);
                if (entity != null) blockEntities.put(key, entity);
            }
        }
        return true;
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean movedByPiston) {
        return setBlock(pos, getFluidState(pos).createLegacyBlock(), Block.UPDATE_ALL);
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean dropResources, Entity breaker, int updateLimit) {
        return removeBlock(pos, false);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        checkBudget();
        return blockEntities.get(pos);
    }

    @Override
    public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
        return predicate.test(getBlockState(pos));
    }

    @Override
    public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
        return predicate.test(getFluidState(pos));
    }

    @Override
    public boolean ensureCanWrite(BlockPos pos) {
        checkBudget();
        return inBounds(pos);
    }

    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean load) {
        checkBudget();
        if (!hasChunk(x, z)) throw new IllegalArgumentException("Feature requested a chunk outside the preview area");
        ChunkPos pos = new ChunkPos(x, z);
        return chunks.computeIfAbsent(pos, key -> new PreviewChunk(key, this));
    }

    @Override
    public boolean hasChunk(int x, int z) {
        return x >= -2 && x <= 2 && z >= -2 && z <= 2;
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        for (int y = DatapackEditorConstants.FEATURE_MAX_PREVIEW_Y; y >= DatapackEditorConstants.FEATURE_MIN_PREVIEW_Y; y--) {
            if (type.isOpaque().test(getBlockState(new BlockPos(x, y, z)))) return y + 1;
        }
        return DatapackEditorConstants.FEATURE_MIN_PREVIEW_Y;
    }

    @Override
    public Holder<Biome> getBiome(BlockPos pos) {
        return biome;
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z) {
        return biome;
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return biome;
    }

    @Override
    public BiomeManager getBiomeManager() {
        return biomeManager;
    }

    @Override
    public RegistryAccess registryAccess() {
        return registries;
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return features;
    }

    @Override
    public DimensionType dimensionType() {
        return dimension;
    }

    @Override
    public EnvironmentAttributeReader environmentAttributes() {
        return EnvironmentAttributeReader.EMPTY;
    }

    @Override
    public long getSeed() {
        return seed;
    }

    @Override
    public RandomSource getRandom() {
        return random;
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    @Override
    public int getSeaLevel() {
        return DatapackEditorConstants.FEATURE_SURFACE_Y + 1;
    }

    @Override
    public int getSkyDarken() {
        return 0;
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return layer == LightLayer.SKY && pos.getY() >= getHeight(Heightmap.Types.WORLD_SURFACE, pos) ? 15 : 0;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int darkening) {
        return Math.max(0, getBrightness(LightLayer.SKY, pos) - darkening);
    }

    @Override
    public WorldBorder getWorldBorder() {
        return border;
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public long nextSubTickCount() {
        return subTick++;
    }

    @Override
    public long getGameTime() {
        return 0;
    }

    @Override
    public List<Entity> getEntities(Entity except, AABB box, Predicate<? super Entity> selector) {
        return List.of();
    }

    @Override
    public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> type, AABB box, Predicate<? super T> selector) {
        return List.of();
    }

    @Override
    public List<? extends Player> players() {
        return List.of();
    }

    @Override
    public void playSound(Entity except, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {
    }

    @Override
    public void addParticle(ParticleOptions particle, double x, double y, double z, double xd, double yd, double zd) {
    }

    @Override
    public void levelEvent(Entity source, int type, BlockPos pos, int data) {
    }

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {
    }

    @Override
    public ServerLevel getLevel() {
        if (serverLevel == null) serverLevel = EditorFeatureServerLevel.create(source, this);
        return serverLevel;
    }

    @Override
    public void close() throws IOException {
        if (serverLevel != null) serverLevel.close();
    }

    @Override
    public MinecraftServer getServer() {
        throw unsupported("a live Minecraft server");
    }

    @Override
    public LevelData getLevelData() {
        throw unsupported("mutable level data");
    }

    @Override
    public ChunkSource getChunkSource() {
        throw unsupported("a live chunk source");
    }

    @Override
    public LevelLightEngine getLightEngine() {
        throw unsupported("a live light engine");
    }

    @Override
    public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
        throw unsupported("world difficulty");
    }

    private UnsupportedOperationException unsupported(String operation) {
        return new UnsupportedOperationException("This feature requires " + operation + ", which the isolated preview does not provide");
    }

    /**
     * Chunk reads and writes share the same capture map, including ore features' bulk section writes.
     */
    public static class PreviewChunk extends ProtoChunk {
        private final EditorFeatureLevel preview;
        private final Map<Integer, PreviewSection> previewSections = new HashMap<>();

        public PreviewChunk(ChunkPos pos, EditorFeatureLevel preview) {
            super(pos, UpgradeData.EMPTY, preview, preview.containers, null);
            this.preview = preview;
        }

        @Override
        public LevelChunkSection getSection(int index) {
            return previewSections.computeIfAbsent(index, key -> new PreviewSection(preview,
                    new BlockPos(getPos().getMinBlockX(), getSectionYFromSectionIndex(key) * 16, getPos().getMinBlockZ())));
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
        public BlockState setBlockState(BlockPos pos, BlockState state, int flags) {
            BlockState old = preview.getBlockState(pos);
            preview.setBlock(pos, state, flags);
            return old;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return preview.getBlockEntity(pos);
        }

        @Override
        public Holder<Biome> getNoiseBiome(int x, int y, int z) {
            return preview.biome;
        }
    }

    public static class PreviewSection extends LevelChunkSection {
        private final EditorFeatureLevel preview;
        private final BlockPos origin;

        public PreviewSection(EditorFeatureLevel preview, BlockPos origin) {
            super(preview.containers);
            this.preview = preview;
            this.origin = origin;
        }

        @Override
        public BlockState getBlockState(int x, int y, int z) {
            return preview.getBlockState(origin.offset(x, y, z));
        }

        @Override
        public FluidState getFluidState(int x, int y, int z) {
            return getBlockState(x, y, z).getFluidState();
        }

        @Override
        public BlockState setBlockState(int x, int y, int z, BlockState state, boolean checkThreading) {
            BlockPos pos = origin.offset(x, y, z);
            BlockState old = preview.getBlockState(pos);
            preview.setBlock(pos, state, 19);
            return old;
        }

        @Override
        public boolean hasOnlyAir() {
            return false;
        }
    }
}
