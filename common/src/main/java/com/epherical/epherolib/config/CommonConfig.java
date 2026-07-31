package com.epherical.epherolib.config;

import com.epherical.epherolib.CommonPlatform;
import com.epherical.epherolib.ModConstants;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.loader.AbstractConfigurationLoader;
import org.spongepowered.configurate.serialize.TypeSerializer;
import org.spongepowered.configurate.serialize.TypeSerializerCollection;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;

public abstract class CommonConfig {

    private final boolean devEnvironment = Boolean.getBoolean(ModConstants.MOD_ID + ".dev");


    protected int configVersion = 1;

    protected static final Logger LOGGER = LogUtils.getLogger();

    protected AbstractConfigurationLoader.Builder<?,?> loaderBuilder;
    protected AbstractConfigurationLoader<?> loader;
    protected ConfigurationNode rootNode;

    protected final String configName;

    private TypeSerializerCollection.Builder serializers;


    public CommonConfig(AbstractConfigurationLoader.Builder<?,?> loaderBuilder, String configName) {
        this.loaderBuilder = loaderBuilder;
        this.configName = configName;
        this.serializers = TypeSerializerCollection.builder();
    }

    public <T, V extends TypeSerializer<T>> void addSerializer(Class<T> clazz, V instance) {
        serializers.register(clazz, instance);
    }

    public void addSerializer(TypeSerializerCollection collection) {
        serializers.registerAll(collection);
    }

    public boolean loadConfig(String modID) {
        File file = new File(getConfigPath(modID).toFile(), configName);

        boolean createdFile = false;
        URL path = null;
        if (devEnvironment) {
            path = getClass().getClassLoader().getResource(configName);
        } else {
            try (InputStream stream = getClass().getClassLoader().getResourceAsStream(configName)) {
                byte[] bytes = new byte[stream.available()];
                stream.read(bytes);
                LOGGER.info("Creating default config file: " + configName);
                createdFile = createdFile(file);
                if (createdFile) {
                    try (FileOutputStream outputStream = new FileOutputStream(file)) {
                        outputStream.write(bytes);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Could not find an internal config file for {}", configName);
            } finally {
                try {
                    createdFile = createdFile(file);
                    path = file.toURI().toURL();
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                }
            }
        }
        this.loader = this.loaderBuilder
                .sink(() -> new BufferedWriter(new FileWriter(file)))
                .defaultOptions(options -> options.serializers(builder -> builder.registerAll(serializers.build())))
                .url(path)
                .build();
        try {
            if (createdFile) {
                this.loader.save(generateConfig(CommentedConfigurationNode.root(loader.defaultOptions())));
            }
        } catch (ConfigurateException e) {
            e.printStackTrace();
        }
        try {
            int currentConfigVersion = getConfigVersion();
            this.rootNode = this.loader.load();
            // Now we will parse out the config and potentially assign any values from the config to variables to be used.
            parseConfig((CommentedConfigurationNode) rootNode);
            int value = this.rootNode.node("version").getInt();
            if (value != currentConfigVersion) {
                LOGGER.info("Upgrading professions config from {} to {}", getConfigVersion(), currentConfigVersion);
                setConfigVersion(currentConfigVersion);
                this.loader.save(generateConfig(CommentedConfigurationNode.root(rootNode.options())));
                this.rootNode = this.loader.load();
                parseConfig((CommentedConfigurationNode) rootNode);
            }
        } catch (ConfigurateException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private boolean canCreateFile(File file) {
        return file.exists();
    }

    public static boolean createdFile(File file) {
        try {
            if (!file.getParentFile().exists() && file.getParentFile().mkdirs()) {
                LOGGER.info("Created directory for: " + file.getParentFile().getCanonicalPath());
            }

            if (!file.exists() && file.createNewFile()) {
                return true;
            }
        } catch (IOException e) {
            LOGGER.warn("Error creating new config file ", e);
            return false;
        }
        return false;
    }

    public boolean reloadConfig() {
        try {
            rootNode = this.loader.load();
            parseConfig((CommentedConfigurationNode) rootNode);
            loader.save(rootNode);
            return true;
        } catch (ConfigurateException e) {
            e.printStackTrace();
            return false;
        }
    }

    public Path getConfigPath(String modID) {
        return CommonPlatform.platform.getRootConfigPath(modID);
    }

    public abstract void parseConfig(CommentedConfigurationNode node);

    public abstract CommentedConfigurationNode generateConfig(CommentedConfigurationNode node);

    public int getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(int configVersion) {
        this.configVersion = configVersion;
    }
}
