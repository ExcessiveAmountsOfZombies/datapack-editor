package com.epherical.epherolib;

import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

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

}
