package com.epherical.epherolib.config;

import com.epherical.epherolib.CommonPlatform;
import com.epherical.epherolib.ModConstants;
import com.epherical.epherolib.lang.ServerLanguage;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A server side class to load translations for specific mods
 */
public class LanguageConfig {

    private static final Pattern UNSUPPORTED_FORMAT_PATTERN = Pattern.compile("%(\\d+\\$)?[\\d.]*[df]");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    protected static final Logger LOGGER = LogUtils.getLogger();
    private final boolean devEnvironment = Boolean.getBoolean(ModConstants.MOD_ID + ".dev");

    private final String folderName;

    private static final Map<String, ServerLanguage> languages = new HashMap<>();


    public LanguageConfig(String folderName) {
        this.folderName = folderName;
    }

    public boolean loadTranslations(String modId, String... files) {
        Path output = getConfigPath(modId).resolve(folderName);
        if (!Files.exists(output)) {
            try {
                Files.createDirectories(output);
                for (String file : files) {
                    try (InputStream fileStream = getClass().getClassLoader().getResourceAsStream("translations/" + file + ".json")) {
                        JsonObject jsonobject = GSON.fromJson(new InputStreamReader(fileStream, StandardCharsets.UTF_8), JsonObject.class);
                        Map<String, String> map = Maps.newHashMap();
                        loadFromJson(jsonobject, map::put);
                        languages.put(file, new ServerLanguage(map));
                        Files.writeString(output.resolve(file + ".json"), GSON.toJson(jsonobject));
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("", e);
            }
        } else {
            // stupid code
            try {
                try (Stream<Path> walk = Files.walk(output)) {
                    walk.forEach(path -> {
                        if (path.toFile().getName().endsWith(".json")) {
                            try {
                                JsonObject jsonObject = GSON.fromJson(Files.readString(path), JsonObject.class);
                                Map<String, String> map = Maps.newHashMap();
                                loadFromJson(jsonObject, map::put);
                                languages.put(FilenameUtils.getBaseName(path.toFile().getName()), new ServerLanguage(map));
                            } catch (IOException e) {
                                LOGGER.warn("", e);
                            }
                        }
                    });
                }
            } catch (IOException e) {
                LOGGER.warn("", e);
            }
        }
        return true;
    }

    public Path getConfigPath(String modID) {
        return CommonPlatform.platform.getRootConfigPath(modID);
    }

    public static void loadFromJson(JsonObject jsonObject, BiConsumer<String, String> consumer) {
        for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
            String replacement = UNSUPPORTED_FORMAT_PATTERN.matcher(GsonHelper.convertToString(entry.getValue(), entry.getKey())).replaceAll("%$1s");
            consumer.accept(entry.getKey(), replacement);
        }
    }

    public static Map<String, ServerLanguage> getLanguages() {
        return languages;
    }
}
