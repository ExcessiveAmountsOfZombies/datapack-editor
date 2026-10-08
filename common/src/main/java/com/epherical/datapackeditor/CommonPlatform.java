package com.epherical.datapackeditor;

import com.epherical.datapackeditor.api.DataPackRegistration;
import com.epherical.datapackeditor.api.schema.CodecJsonSchema;
import com.mojang.serialization.Codec;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.loot.LootTable;

import java.nio.file.Path;
import java.util.List;

/**
 * A way to abstract away platform specific code into something more generic for our root projects.
 * @param <T> Which platform are we supporting right now?
 */
public abstract class CommonPlatform<T> {
    public static CommonPlatform<?> platform;

    protected CommonPlatform() {
        platform = this;
    }

    public static <T> void create(CommonPlatform<T> value) {
        platform = value;
    }

    public abstract T getPlatform();

    public abstract boolean isClientEnvironment();

    public abstract boolean isServerEnvironment();

    /**
     * If we want multiple config files for a single mod, we can stick them into a folder that uses the mod ID as its name.
     * @param modID The folder in which to check, that becomes the root for the config path.
     * @return A path to the config file in a modIDed folder.
     */
    public abstract Path getRootConfigPath(String modID);

    /**
     * If we want to just put a file in the root config path of a particular loader, this method can be used.
     * @return A path to the config file.
     */
    public abstract Path getRootConfigPath();

    /** Datapack registries actually loaded by this platform, including mod registries. */
    public List<RegistryDataLoader.RegistryData<?>> datapackRegistries() {
        return RegistryDataLoader.WORLD_REGISTRIES;
    }

    /** Reloadable datapack registries, including loader registrations. */
    public List<RegistryDataLoader.RegistryData<?>> reloadableDatapackRegistries() {
        return RegistryDataLoader.RELOADABLE_REGISTRIES;
    }

    /** Loader resources discovered at runtime, with their optional schemas in each registration. */
    public List<DataPackRegistration> datapackResources() {
        return List.of();
    }

    public void configureDatapackSchema(CodecJsonSchema.Builder builder, Codec<?> codec) {
    }

    public LootTable previewLootTable(MinecraftServer server, Identifier id, JsonElement definition) {
        return LootTable.DIRECT_CODEC.parse(previewOps(server), definition).getOrThrow(JsonParseException::new);
    }

    public RegistryOps<JsonElement> previewOps(MinecraftServer server) {
        return server.reloadableRegistries().lookup().createSerializationContext(JsonOps.INSTANCE);
    }

    public boolean previewConditionsMatch(MinecraftServer server, JsonElement definition) {
        return true;
    }

}
