package com.epherical.datapackeditor.impl;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;

public class RegistrySchemaCache {
    private final HolderLookup.Provider registries;
    private final String baseUri;
    private final Map<String, JsonObject> schemas = new LinkedHashMap<>();
    private long estimatedBytes;

    public RegistrySchemaCache(HolderLookup.Provider registries, String baseUri) {
        this.registries = registries;
        this.baseUri = baseUri;
    }

    public JsonObject reference(ResourceKey<? extends Registry<?>> key, boolean tags) {
        String uri = baseUri + "&registry=" + URLEncoder.encode(key.identifier().toString(), StandardCharsets.UTF_8)
                + "&tags=" + tags;
        if (!schemas.containsKey(uri)) {
            long previous = estimatedBytes;
            try {
                estimatedBytes += DatapackEditorConstants.KIBIBYTE + uri.length() * (long) DatapackEditorConstants.UTF16_BYTES_PER_CHAR;
                checkBudget();
                schemas.put(uri, build(key, tags));
            } catch (RuntimeException error) {
                estimatedBytes = previous;
                throw error;
            }
        }
        JsonObject reference = new JsonObject();
        reference.addProperty(DatapackEditorConstants.SCHEMA_REF, uri);
        return reference;
    }

    public JsonElement resolve(String uri) {
        return schemas.get(uri);
    }

    public JsonObject document(ResourceKey<? extends Registry<?>> key, boolean tags) {
        return schemas.get(reference(key, tags).get(DatapackEditorConstants.SCHEMA_REF).getAsString());
    }

    private JsonObject build(ResourceKey<? extends Registry<?>> key, boolean tags) {
        TreeSet<String> values = new TreeSet<>();
        long previous = estimatedBytes;
        try {
            registries.lookup(key).ifPresent(lookup -> {
                lookup.listElementIds().forEach(id -> add(values, id.identifier().toString()));
                if (tags) lookup.listTagIds().forEach(id -> add(values, DatapackEditorConstants.MINECRAFT_TAG_PREFIX + id.location()));
            });
            JsonObject result = new JsonObject();
            result.addProperty(DatapackEditorConstants.JSON_TYPE, DatapackEditorConstants.SCHEMA_STRING);
            if (!values.isEmpty()) {
                JsonArray entries = new JsonArray();
                values.forEach(entries::add);
                result.add(DatapackEditorConstants.SCHEMA_ENUM, entries);
            }
            return result;
        } catch (RuntimeException error) {
            estimatedBytes = previous;
            throw error;
        }
    }

    private void add(TreeSet<String> values, String value) {
        check();
        if (!values.add(value)) return;
        estimatedBytes += 128L + value.length() * (long) DatapackEditorConstants.UTF16_BYTES_PER_CHAR;
        checkBudget();
    }

    private void checkBudget() {
        if (estimatedBytes > 32 * DatapackEditorConstants.MEBIBYTE)
            throw new CancellationException("Shared registry schemas exceed their metadata budget");
    }

    private static void check() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Registry schema cancelled");
    }
}
