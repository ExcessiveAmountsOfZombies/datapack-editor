package com.epherical.datapackeditor;

import com.epherical.datapackeditor.api.DataPackRegistration;
import com.epherical.datapackeditor.api.schema.CodecJsonSchema;
import com.mojang.serialization.Codec;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.common.conditions.ConditionalOps;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.registries.DataPackRegistriesHooks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ForgePlatform extends CommonPlatform<ForgePlatform> {

    @Override
    public ForgePlatform getPlatform() {
        return this;
    }

    @Override
    public boolean isClientEnvironment() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }

    @Override
    public boolean isServerEnvironment() {
        return FMLEnvironment.dist == Dist.DEDICATED_SERVER;
    }

    @Override
    public Path getRootConfigPath(String modID) {
        return FMLPaths.CONFIGDIR.get().resolve(modID);
    }

    @Override
    public Path getRootConfigPath() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public List<RegistryDataLoader.RegistryData<?>> datapackRegistries() {
        return DataPackRegistriesHooks.getDataPackRegistries();
    }

    @Override
    public List<DataPackRegistration> datapackResources() {
        return NeoForgeSchemas.dataMaps();
    }

    @Override
    public void configureDatapackSchema(CodecJsonSchema.Builder builder, Codec<?> codec) {
        builder.registerAdapter(codec, NeoForgeSchemas::withConditions);
    }

    @Override
    public RegistryOps<JsonElement> previewOps(MinecraftServer server) {
        var registries = server.reloadableRegistries().get();
        return new ConditionalOps<>(super.previewOps(server), new ICondition.IContext() {
            @Override
            public <T> Map<ResourceLocation, Collection<Holder<T>>> getAllTags(ResourceKey<? extends Registry<T>> registry) {
                Map<ResourceLocation, Collection<Holder<T>>> tags = new LinkedHashMap<>();
                registries.lookup(registry).ifPresent(lookup -> lookup.listTags()
                        .forEach(tag -> tags.put(tag.key().location(), tag.stream().toList())));
                return Collections.unmodifiableMap(tags);
            }
        });
    }

    @Override
    public LootTable previewLootTable(MinecraftServer server, ResourceLocation id, JsonElement definition) {
        LootTable table = ConditionalOps.createConditionalCodec(LootTable.DIRECT_CODEC)
                .parse(previewOps(server), definition).getOrThrow(JsonParseException::new)
                .orElseThrow(() -> new IllegalArgumentException("This loot table's NeoForge conditions do not match the running server"));
        table.setLootTableId(id);
        return table;
    }

    @Override
    public boolean previewConditionsMatch(MinecraftServer server, JsonElement definition) {
        return ICondition.conditionsMatched(previewOps(server), definition);
    }

}
