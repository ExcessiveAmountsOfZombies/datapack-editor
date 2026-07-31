package com.epherical.epherolib;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

public class ForgePlatform extends CommonPlatform<ForgePlatform> {

    @Override
    public ForgePlatform getPlatform() {
        return this;
    }

    @Override
    public boolean isClientEnvironment() {
        return FMLEnvironment.getDist() == Dist.CLIENT;
    }

    @Override
    public boolean isServerEnvironment() {
        return FMLEnvironment.getDist() == Dist.DEDICATED_SERVER;
    }

    @Override
    public Path getRootConfigPath(String modID) {
        return FMLPaths.CONFIGDIR.get().resolve(modID);
    }

    @Override
    public Path getRootConfigPath() {
        return FMLPaths.CONFIGDIR.get();
    }

}
