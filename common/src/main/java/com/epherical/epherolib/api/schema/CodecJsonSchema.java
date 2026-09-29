package com.epherical.epherolib.api.schema;

import com.epherical.epherolib.impl.CodecJsonSchemaImpl;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registers additions to the JSON schemas generated from DataFixerUpper codecs.
 * Most codecs require no integration. Addons can supply exact schemas, live
 * registry associations, post-inference adapters, or complete runtime providers
 * for opaque and recursive codecs. {@link Builder} configures one datapack
 * registration through {@link DataPackSchemaRegistration.Builder#configure(Consumer)}.
 * Register global extensions during common mod initialization, before the
 * editor requests schemas. Registration keys use codec instance identity:
 * pass the exact codec instance retained by the containing codec graph.
 * An exact schema, registry source, and provider replace one another at the
 * same scope; the last registered source wins. Adapters compose in registration
 * order, with global adapters running before per-build adapters. These schemas
 * assist editor users and do not change codec serialization or validation.
 */
public class CodecJsonSchema {
    public static final Map<Object, JsonElement> OVERRIDES = Collections.synchronizedMap(new IdentityHashMap<>());
    public static final Map<Object, CodecJsonSchemaImpl.RegistryOverride> REGISTRY_OVERRIDES = Collections.synchronizedMap(new IdentityHashMap<>());
    public static final Map<Object, SchemaProvider> PROVIDERS = Collections.synchronizedMap(new IdentityHashMap<>());
    public static final Map<Object, SchemaAdapter> ADAPTERS = Collections.synchronizedMap(new IdentityHashMap<>());
    public static final List<SchemaInspector> INSPECTORS = new CopyOnWriteArrayList<>();

    /**
     * Adds inference for multiple codec instances created by the same factory or class, with different settings
     * (This is really the HolderSetCodec). Inspectors run in
     * registration order before built-in inference; return null to decline a codec.
     * Exact instance registrations take precedence. Use context.definition for
     * recursive families, and never request the same codec from its own inspector.
     */
    public static void registerInspector(SchemaInspector inspector) {
        INSPECTORS.add(Objects.requireNonNull(inspector, "inspector"));
    }

    @FunctionalInterface
    public interface SchemaInspector {
        JsonElement inspect(SchemaContext context, Object codec);
    }


    public static final String CODEC = "codec";
    public static final String SCHEMA = "schema";

    /**
     * Supplies an exact schema for a codec whose internal structure is opaque.
     *
     * @param codec  codec instance to override
     * @param schema exact JSON schema to use for that codec
     *               (copied when registered)
     */
    public static void register(Codec<?> codec, JsonElement schema) {
        registerOverride(codec, schema);
    }

    /**
     * Supplies an exact schema for a map codec whose internal structure is opaque.
     *
     * @param codec  map codec instance to override
     * @param schema exact JSON schema to use for that codec
     *               (copied when registered)
     */
    public static void register(MapCodec<?> codec, JsonElement schema) {
        registerOverride(codec, schema);
    }

    /**
     * Adds an adapter that can enrich or replace the schema inferred for a codec.
     * The adapter is evaluated when a schema is built, so it can use the current
     * game's registries and infer schemas for codecs referenced by the target.
     *
     * @param codec   codec instance to adapt
     * @param adapter adapter applied to the inferred schema
     */
    public static void registerAdapter(Codec<?> codec, SchemaAdapter adapter) {
        registerGlobalAdapter(codec, adapter);
    }

    /**
     * Adds a runtime adapter for a map codec. Adapters compose in registration order.
     *
     * @param codec map codec instance to adapt
     * @param adapter adapter applied to the inferred schema
     */
    public static void registerAdapter(MapCodec<?> codec, SchemaAdapter adapter) {
        registerGlobalAdapter(codec, adapter);
    }

    /**
     * Supplies a schema at build time without first inspecting the target codec.
     * Providers are intended for opaque, dynamic, or recursive codecs. Use
     * {@link SchemaContext#definition(String, Supplier)} for recursive schemas.
     *
     * @param codec codec instance to provide a schema for
     * @param provider callback evaluated with the current registry view
     */
    public static void registerProvider(Codec<?> codec, SchemaProvider provider) {
        registerGlobalProvider(codec, provider);
    }

    /**
     * Supplies a runtime schema provider for an opaque map codec.
     *
     * @param codec map codec instance to provide a schema for
     * @param provider callback evaluated with the current registry view
     */
    public static void registerProvider(MapCodec<?> codec, SchemaProvider provider) {
        registerGlobalProvider(codec, provider);
    }

    /**
     * Associates an opaque codec with live values from a game registry.
     *
     * @param codec       codec instance to associate with the registry
     * @param registry    registry whose identifiers should become completion values
     * @param includeTags whether tag identifiers should also become completion values
     */
    public static void registerRegistry(Codec<?> codec, ResourceKey<? extends Registry<?>> registry, boolean includeTags) {
        registerGlobalRegistry(codec, registry, includeTags);
    }

    /**
     * Associates an opaque map codec with live values from a game registry.
     *
     * @param codec       map codec instance to associate with the registry
     * @param registry    registry whose identifiers should become completion values
     * @param includeTags whether tag identifiers should also become completion values
     */
    public static void registerRegistry(MapCodec<?> codec, ResourceKey<? extends Registry<?>> registry, boolean includeTags) {
        registerGlobalRegistry(codec, registry, includeTags);
    }

    private static void registerOverride(Object codec, JsonElement schema) {
        Object key = Objects.requireNonNull(codec, CODEC);
        JsonElement value = Objects.requireNonNull(schema, SCHEMA).deepCopy();
        synchronized (CodecJsonSchema.class) {
            OVERRIDES.put(key, value);
            REGISTRY_OVERRIDES.remove(key);
            PROVIDERS.remove(key);
        }
    }

    private static void registerGlobalProvider(Object codec, SchemaProvider provider) {
        Object key = Objects.requireNonNull(codec, CODEC);
        SchemaProvider value = Objects.requireNonNull(provider, "provider");
        synchronized (CodecJsonSchema.class) {
            PROVIDERS.put(key, value);
            OVERRIDES.remove(key);
            REGISTRY_OVERRIDES.remove(key);
        }
    }

    private static void registerGlobalAdapter(Object codec, SchemaAdapter adapter) {
        Object key = Objects.requireNonNull(codec, CODEC);
        SchemaAdapter value = Objects.requireNonNull(adapter, "adapter");
        ADAPTERS.merge(key, value, CodecJsonSchema::composeAdapters);
    }

    private static SchemaAdapter composeAdapters(SchemaAdapter first, SchemaAdapter second) {
        return (context, inferred) -> second.adapt(context, Objects.requireNonNull(first.adapt(context, inferred), "Schema adapter returned null"));
    }

    private static void registerGlobalRegistry(Object codec, ResourceKey<? extends Registry<?>> registry, boolean includeTags) {
        Object key = Objects.requireNonNull(codec, CODEC);
        CodecJsonSchemaImpl.RegistryOverride value = new CodecJsonSchemaImpl.RegistryOverride(Objects.requireNonNull(registry, "registry"), includeTags);
        synchronized (CodecJsonSchema.class) {
            REGISTRY_OVERRIDES.put(key, value);
            OVERRIDES.remove(key);
            PROVIDERS.remove(key);
        }
    }

    /**
     * Collects overrides and enhancements for one schema run, local sources take precedence over global sources for the same codec.
     * Global adapters still run before adapters registered on this builder.
     */
    public static class Builder {
        private final HolderLookup.Provider registries;
        private final Map<Object, JsonElement> overrides = new IdentityHashMap<>();
        private final Map<Object, CodecJsonSchemaImpl.RegistryOverride> registryOverrides = new IdentityHashMap<>();
        private final Map<Object, SchemaProvider> providers = new IdentityHashMap<>();
        private final Map<Object, SchemaAdapter> adapters = new IdentityHashMap<>();
        private final List<SchemaEnhancer> enhancers = new ArrayList<>();

        public Builder(HolderLookup.Provider registries) {
            this.registries = registries;
        }

        /**
         * Supplies an exact schema override for this build only.
         *
         * @param codec  codec instance to override
         * @param schema exact JSON schema to use for that codec
         * @return this builder
         */
        public Builder register(Codec<?> codec, JsonElement schema) {
            registerOverride(codec, schema);
            return this;
        }

        /**
         * Supplies an exact map-codec schema override for this build only.
         *
         * @param codec  map codec instance to override
         * @param schema exact JSON schema to use for that codec
         * @return this builder
         */
        public Builder register(MapCodec<?> codec, JsonElement schema) {
            registerOverride(codec, schema);
            return this;
        }

        /**
         * Adds a runtime schema adapter for this build only.
         * Adapters for the same codec compose in registration order.
         *
         * @param codec codec instance to adapt
         * @param adapter callback applied after schema source selection
         * @return this builder
         */
        public Builder registerAdapter(Codec<?> codec, SchemaAdapter adapter) {
            selectAdapter(codec, adapter);
            return this;
        }

        /**
         * Adds a runtime map-codec schema adapter for this build only.
         * Adapters for the same codec compose in registration order.
         *
         * @param codec map codec instance to adapt
         * @param adapter callback applied after schema source selection
         * @return this builder
         */
        public Builder registerAdapter(MapCodec<?> codec, SchemaAdapter adapter) {
            selectAdapter(codec, adapter);
            return this;
        }

        /**
         * Supplies a runtime schema provider for an opaque codec in this build only.
         *
         * @param codec codec instance to provide a schema for
         * @param provider callback that creates the complete schema
         * @return this builder
         */
        public Builder registerProvider(Codec<?> codec, SchemaProvider provider) {
            selectProvider(codec, provider);
            return this;
        }

        /**
         * Supplies a runtime schema provider for an opaque map codec in this build only.
         *
         * @param codec map codec instance to provide a schema for
         * @param provider callback that creates the complete schema
         * @return this builder
         */
        public Builder registerProvider(MapCodec<?> codec, SchemaProvider provider) {
            selectProvider(codec, provider);
            return this;
        }

        /**
         * Associates a codec with live registry identifiers for this build only.
         *
         * @param codec       codec instance to associate with the registry
         * @param registry    registry whose identifiers should become completion values
         * @param includeTags whether tag identifiers should also become completion values
         * @return this builder
         */
        public Builder registerRegistry(Codec<?> codec, ResourceKey<? extends Registry<?>> registry, boolean includeTags) {
            selectRegistry(codec, registry, includeTags);
            return this;
        }

        /**
         * Associates a map codec with live registry identifiers for this build only.
         *
         * @param codec       map codec instance to associate with the registry
         * @param registry    registry whose identifiers should become completion values
         * @param includeTags whether tag identifiers should also become completion values
         * @return this builder
         */
        public Builder registerRegistry(MapCodec<?> codec, ResourceKey<? extends Registry<?>> registry, boolean includeTags) {
            selectRegistry(codec, registry, includeTags);
            return this;
        }

        private void registerOverride(Object codec, JsonElement schema) {
            Object key = Objects.requireNonNull(codec, CODEC);
            overrides.put(key, Objects.requireNonNull(schema, SCHEMA).deepCopy());
            registryOverrides.remove(key);
            providers.remove(key);
        }

        private void selectProvider(Object codec, SchemaProvider provider) {
            Object key = Objects.requireNonNull(codec, CODEC);
            providers.put(key, Objects.requireNonNull(provider, "provider"));
            overrides.remove(key);
            registryOverrides.remove(key);
        }

        private void selectAdapter(Object codec, SchemaAdapter adapter) {
            Object key = Objects.requireNonNull(codec, CODEC);
            adapters.merge(key, Objects.requireNonNull(adapter, "adapter"), CodecJsonSchema::composeAdapters);
        }

        private void selectRegistry(Object codec, ResourceKey<? extends Registry<?>> registry, boolean includeTags) {
            Object key = Objects.requireNonNull(codec, CODEC);
            registryOverrides.put(key, new CodecJsonSchemaImpl.RegistryOverride(Objects.requireNonNull(registry, "registry"), includeTags));
            overrides.remove(key);
            providers.remove(key);
        }

        /**
         * Annotates every generated property matching a slash-separated path.
         * References, alternatives, and array items are followed automatically.
         * Escape {@code /} within a property name as {@code ~1} and {@code ~} as {@code ~0}.
         *
         * @param propertyPath slash-separated property names
         * @param annotation metadata to apply at each matching location
         * @return this builder
         */
        public Builder annotate(String propertyPath, JsonSchemaAnnotation annotation) {
            Objects.requireNonNull(annotation, "annotation");
            return enhance((schema, ignored) -> annotation.apply(schema, propertyPath));
        }

        /**
         * Adds a description to a generated property to be shown in the editor.
         *
         * @param propertyPath slash-separated property names
         * @param description plain-text description
         * @return this builder
         */
        public Builder describe(String propertyPath, String description) {
            return annotate(propertyPath, JsonSchemaAnnotation.builder().description(description).build());
        }

        /**
         * Adds a final, in-place enhancement to the completed root schema.
         *
         * @param enhancer callback receiving the completed schema and registry view
         * @return this builder
         */
        public Builder enhance(SchemaEnhancer enhancer) {
            enhancers.add(Objects.requireNonNull(enhancer, "enhancer"));
            return this;
        }

        /**
         * Generates a complete json schema for the codec using this builder's configuration.
         *
         * @param codec root codec
         * @return complete schema document
         */
        public JsonObject create(Codec<?> codec) {
            return finish(new CodecJsonSchemaImpl.Generator(registries, overrides, registryOverrides, providers, adapters).create(codec));
        }

        /**
         * Generates a complete json schema for the map codec using this builder's configuration.
         *
         * @param codec root map codec
         * @return complete schema document
         */
        public JsonObject create(MapCodec<?> codec) {
            return finish(new CodecJsonSchemaImpl.Generator(registries, overrides, registryOverrides, providers, adapters).create(codec));
        }

        /**
         * Applies this builder's root annotations and enhancers to an existing schema.
         * The given object is modified in place.
         *
         * @param schema existing root schema
         * @return the modified root schema
         */
        public JsonObject enhance(JsonObject schema) {
            return finish(Objects.requireNonNull(schema, SCHEMA));
        }

        private JsonObject finish(JsonObject schema) {
            enhancers.forEach(enhancer -> enhancer.enhance(schema, registries));
            return schema;
        }
    }

    @FunctionalInterface
    public interface SchemaAdapter {
        /**
         * Adapts the inferred schema for the registered codec.
         *
         * @param context        runtime schema services and registry access
         * @param inferredSchema the result produced by normal inference
         * @return the enriched or replacement schema
         */
        JsonElement adapt(SchemaContext context, JsonElement inferredSchema);
    }

    @FunctionalInterface
    public interface SchemaProvider {
        /**
         * Creates the complete schema for the registered codec.
         *
         * @param context runtime schema services and registry access
         * @return the complete schema for the registered codec
         */
        JsonElement create(SchemaContext context);
    }

    @FunctionalInterface
    public interface SchemaEnhancer {
        /**
         * Enhances a generated root schema in place.
         *
         * @param schema     completed root schema to modify in place
         * @param registries current registry view
         */
        void enhance(JsonObject schema, HolderLookup.Provider registries);
    }

    /**
     * Runtime services available to a {@link SchemaAdapter} or {@link SchemaProvider}.
     */
    public static class SchemaContext {
        private final CodecJsonSchemaImpl.Generator generator;

        public SchemaContext(CodecJsonSchemaImpl.Generator generator) {
            this.generator = generator;
        }

        /**
         * Returns the registry view used for this schema generation run.
         *
         * @return current registry view
         */
        public HolderLookup.Provider registries() {
            return generator.registries;
        }

        /**
         * Infers a schema for a codec referenced by the adapter.
         *
         * @param codec referenced codec
         * @return a copy of the inferred schema
         */
        public JsonElement schema(Codec<?> codec) {
            return generator.infer(Objects.requireNonNull(codec, CODEC)).deepCopy();
        }

        /**
         * Infers a schema for a map codec referenced by the adapter
         *
         * @param codec referenced map codec
         * @return a copy of the inferred schema
         */
        public JsonElement schema(MapCodec<?> codec) {
            return generator.infer(Objects.requireNonNull(codec, CODEC)).deepCopy();
        }

        /**
         * Resolves local definition references to their schema body
         *
         * @param schema schema or local reference to resolve
         * @return a copy of the resolved schema
         */
        public JsonElement resolve(JsonElement schema) {
            return generator.resolve(Objects.requireNonNull(schema, SCHEMA)).deepCopy();
        }

        /**
         *
         * @param registry registry whose identifiers become suggestions
         * @param includeTags whether to include tag identifiers prefixed with {@code #}
         * @return a registry-backed schema reference
         */
        public JsonElement registry(ResourceKey<? extends Registry<?>> registry, boolean includeTags) {
            return generator.registryReference(Objects.requireNonNull(registry, "registry"), includeTags).deepCopy();
        }

        /**
         * Defines a reusable or recursive schema and returns a local reference to it.
         * Names should be namespace-qualified when an addon may coexist with others.
         * A second call with the same name reuses the definition without evaluating
         * its supplier, including while a recursive definition is being built.
         *
         * @param name unique definition name in the generated document
         * @param schema supplier for the definition body
         * @return a local {@code $ref} to the definition
         */
        public JsonElement definition(String name, Supplier<? extends JsonElement> schema) {
            return generator.definition(name, schema);
        }

        /**
         * Adds metadata to properties reachable from an adapter's schema. Local
         * definitions owned by the current generation run are resolved as part
         * of the property path.
         *
         * @param schema       schema returned by inference or a provider
         * @param propertyPath slash-separated property path
         * @param annotation   metadata to add
         * @return the number of matching schema properties
         */
        public int annotate(JsonElement schema, String propertyPath, JsonSchemaAnnotation annotation) {
            Objects.requireNonNull(schema, SCHEMA);
            Objects.requireNonNull(propertyPath, "propertyPath");
            Objects.requireNonNull(annotation, "annotation");
            if (propertyPath.isEmpty()) {
                if (!schema.isJsonObject()) return 0;
                JsonObject target = schema.getAsJsonObject();
                if (target.has("$ref")) {
                    JsonObject referenced = target.deepCopy();
                    target.entrySet().clear();
                    JsonArray composition = new JsonArray();
                    composition.add(referenced);
                    target.add("allOf", composition);
                }
                return annotation.apply(target, "");
            }
            JsonObject document = new JsonObject();
            JsonArray allOf = new JsonArray();
            allOf.add(schema);
            document.add("allOf", allOf);
            document.add("definitions", generator.definitions);
            return annotation.apply(document, propertyPath);
        }
    }

}
