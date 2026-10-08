package com.epherical.datapackeditor.impl;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapDecoder;
import com.mojang.serialization.codecs.BaseMapCodec;
import com.mojang.serialization.codecs.CompoundListCodec;
import com.mojang.serialization.codecs.DispatchedMapCodec;
import com.mojang.serialization.codecs.EitherCodec;
import com.mojang.serialization.codecs.FieldDecoder;
import com.mojang.serialization.codecs.KeyDispatchCodec;
import com.mojang.serialization.codecs.ListCodec;
import com.mojang.serialization.codecs.OptionalFieldCodec;
import com.mojang.serialization.codecs.PairCodec;
import com.mojang.serialization.codecs.SimpleMapCodec;
import com.mojang.serialization.codecs.UnboundedMapCodec;
import com.mojang.serialization.codecs.XorCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.component.DebugStickState;
import com.epherical.datapackeditor.impl.schemas.BlockStateSchema;
import com.epherical.datapackeditor.impl.schemas.IngredientSchema;
import net.minecraft.world.item.crafting.Ingredient;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.epherical.datapackeditor.api.schema.CodecJsonSchema.Builder;
import com.epherical.datapackeditor.api.schema.CodecJsonSchema.SchemaAdapter;
import com.epherical.datapackeditor.api.schema.CodecJsonSchema.SchemaContext;
import com.epherical.datapackeditor.api.schema.CodecJsonSchema.SchemaProvider;
import com.epherical.datapackeditor.api.schema.CodecJsonSchema.SchemaInspector;

import static com.epherical.datapackeditor.api.schema.CodecJsonSchema.ADAPTERS;
import static com.epherical.datapackeditor.api.schema.CodecJsonSchema.INSPECTORS;
import static com.epherical.datapackeditor.api.schema.CodecJsonSchema.OVERRIDES;
import static com.epherical.datapackeditor.api.schema.CodecJsonSchema.PROVIDERS;
import static com.epherical.datapackeditor.api.schema.CodecJsonSchema.REGISTRY_OVERRIDES;

public class CodecJsonSchemaImpl {
    private static final ThreadLocal<RegistrySchemaCache> SHARED_REGISTRIES = new ThreadLocal<>();

    public static <T> T withRegistrySchemas(RegistrySchemaCache schemas, Supplier<T> action) {
        RegistrySchemaCache previous = SHARED_REGISTRIES.get();
        SHARED_REGISTRIES.set(schemas);
        try { return action.get(); }
        finally {
            if (previous == null) SHARED_REGISTRIES.remove();
            else SHARED_REGISTRIES.set(previous);
        }
    }

    public static JsonObject pruneDefinitions(JsonObject schema) {
        JsonObject definitions = schema.getAsJsonObject(DatapackEditorConstants.SCHEMA_DEFINITIONS);
        if (definitions == null) return schema;
        Set<String> reachable = new LinkedHashSet<>();
        Set<JsonElement> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<JsonElement> pending = new ArrayDeque<>();
        pending.add(schema);
        while (!pending.isEmpty()) {
            JsonElement value = pending.removeFirst();
            if (!visited.add(value)) continue;
            if (value.isJsonArray()) value.getAsJsonArray().forEach(pending::add);
            if (!value.isJsonObject()) continue;
            JsonObject object = value.getAsJsonObject();
            if (object != schema && (object.has("$id") || object.has(DatapackEditorConstants.SCHEMA_DEFINITIONS))) return schema;
            if (object.has(DatapackEditorConstants.SCHEMA_REF) && object.get(DatapackEditorConstants.SCHEMA_REF).isJsonPrimitive()) {
                String ref = object.get(DatapackEditorConstants.SCHEMA_REF).getAsString();
                if (ref.startsWith(DatapackEditorConstants.SCHEMA_DEFINITIONS_PREFIX)) {
                    String name = ref.substring(DatapackEditorConstants.SCHEMA_DEFINITIONS_PREFIX.length()).split(DatapackEditorConstants.PATH_SEPARATOR, 2)[0].replace(DatapackEditorConstants.SCHEMA_ESCAPED_SLASH, DatapackEditorConstants.PATH_SEPARATOR).replace(DatapackEditorConstants.SCHEMA_ESCAPED_TILDE, DatapackEditorConstants.SCHEMA_POINTER_TILDE);
                    if (reachable.add(name) && definitions.has(name)) pending.add(definitions.get(name));
                }
            }
            object.entrySet().forEach(entry -> {
                if (object != schema || !entry.getKey().equals(DatapackEditorConstants.SCHEMA_DEFINITIONS)) pending.add(entry.getValue());
            });
        }
        definitions.keySet().removeIf(name -> !reachable.contains(name));
        return schema;
    }

    public static JsonObject create(Codec<?> codec, HolderLookup.Provider registries) {
        return new Generator(registries).create(codec);
    }

    public static JsonElement registrySchema(HolderLookup.Provider registries, ResourceKey<? extends Registry<?>> key, boolean tags) {
        RegistrySchemaCache shared = SHARED_REGISTRIES.get();
        if (shared != null) return shared.reference(key, tags);
        Generator generator = new Generator(registries);
        return generator.resolve(generator.registryReference(key, tags)).deepCopy();
    }

    public static JsonObject create(MapCodec<?> codec, HolderLookup.Provider registries) {
        return new Generator(registries).create(codec);
    }

    public static Builder builder(HolderLookup.Provider registries) {
        return new Builder(registries);
    }

    public static JsonObject create(Builder builder, Codec<?> codec) {
        return builder.create(codec);
    }

    public static JsonObject create(Builder builder, MapCodec<?> codec) {
        return builder.create(codec);
    }

    public static JsonObject enhance(Builder builder, JsonObject schema) {
        return builder.enhance(schema);
    }

    public record RegistryOverride(ResourceKey<? extends Registry<?>> key, boolean includeTags) {
    }

    private record RecordField(String name, Object codec, boolean optional) {
    }

    public static class Generator {
        private static final ClassValue<List<Field>> FIELDS = new ClassValue<>() {
            public List<Field> computeValue(Class<?> type) {
                List<Field> fields = new ArrayList<>();
                for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass())
                    Collections.addAll(fields, current.getDeclaredFields());
                return List.copyOf(fields);
            }
        };
        public final HolderLookup.Provider registries;
        private final DynamicOps<JsonElement> ops;
        private final RegistrySchemaCache sharedRegistries = SHARED_REGISTRIES.get();
        private final Map<Object, JsonElement> localOverrides;
        private final Map<Object, RegistryOverride> localRegistryOverrides;
        private final Map<Object, SchemaProvider> localProviders;
        private final Map<Object, SchemaAdapter> localAdapters;
        public final JsonObject definitions = new JsonObject();
        private final Map<Object, String> memoizedDefinitions = new IdentityHashMap<>();
        private final Map<Object, String> familyDefinitions = new LinkedHashMap<>();
        private int nextMemoizedDefinition;
        private final long started = System.nanoTime();

        public Generator(HolderLookup.Provider registries) {
            this(registries, Map.of(), Map.of(), Map.of(), Map.of());
        }

        public Generator(HolderLookup.Provider registries, Map<Object, JsonElement> localOverrides, Map<Object, RegistryOverride> localRegistryOverrides, Map<Object, SchemaProvider> localProviders, Map<Object, SchemaAdapter> localAdapters) {
            this.registries = registries;
            this.ops = RegistryOps.create(JsonOps.INSTANCE, registries);
            this.localOverrides = localOverrides;
            this.localRegistryOverrides = localRegistryOverrides;
            this.localProviders = localProviders;
            this.localAdapters = localAdapters;
        }

        public JsonObject create(Object codec) {
            JsonObject schema = object(DatapackEditorConstants.SCHEMA_ALL_OF, arrayOf(infer(codec)));
            schema.addProperty("$schema", "http://json-schema.org/draft-07/schema#");
            if (!definitions.isEmpty()) schema.add(DatapackEditorConstants.SCHEMA_DEFINITIONS, definitions);
            return pruneDefinitions(schema);
        }

        public JsonElement infer(Object codec) {
            checkpoint();
            if (codec == null) return unknown(null);
            String existing = memoizedDefinitions.get(codec);
            if (existing != null) return object(DatapackEditorConstants.SCHEMA_REF, DatapackEditorConstants.SCHEMA_DEFINITIONS_PREFIX + existing);
            String definition = "codec_" + ++nextMemoizedDefinition;
            memoizedDefinitions.put(codec, definition);
            definitions.add(definition, new JsonObject());
            JsonElement result = adapt(codec, inferSource(codec));
            definitions.add(definition, result);
            return object(DatapackEditorConstants.SCHEMA_REF, DatapackEditorConstants.SCHEMA_DEFINITIONS_PREFIX + definition);
        }

        private JsonElement inferSource(Object codec) {
            JsonElement exact = localOverrides.get(codec);
            if (exact != null) return exact.deepCopy();
            RegistryOverride registryOverride = localRegistryOverrides.get(codec);
            if (registryOverride != null) {
                return registryReference(registryOverride.key(), registryOverride.includeTags());
            }
            SchemaProvider provider = localProviders.get(codec);
            if (provider != null) return provide(provider);

            exact = OVERRIDES.get(codec);
            if (exact != null) return exact.deepCopy();
            registryOverride = REGISTRY_OVERRIDES.get(codec);
            if (registryOverride != null) {
                return registryReference(registryOverride.key(), registryOverride.includeTags());
            }
            provider = PROVIDERS.get(codec);
            if (provider != null) return provide(provider);

            return inferUncached(codec);
        }

        private JsonElement provide(SchemaProvider provider) {
            return Objects.requireNonNull(provider.create(new SchemaContext(this)), "Schema provider returned null").deepCopy();
        }

        private JsonElement adapt(Object codec, JsonElement inferred) {
            SchemaContext context = new SchemaContext(this);
            JsonElement adapted = inferred;
            SchemaAdapter global = ADAPTERS.get(codec);
            if (global != null) {
                JsonElement replacement = Objects.requireNonNull(global.adapt(context, adapted), DatapackEditorConstants.MESSAGE_SCHEMA_ADAPTER_RETURNED_NULL);
                adapted = replacement == adapted ? adapted : replacement.deepCopy();
            }
            SchemaAdapter local = localAdapters.get(codec);
            if (local != null) {
                JsonElement replacement = Objects.requireNonNull(local.adapt(context, adapted), DatapackEditorConstants.MESSAGE_SCHEMA_ADAPTER_RETURNED_NULL);
                adapted = replacement == adapted ? adapted : replacement.deepCopy();
            }
            return adapted;
        }

        public JsonElement definition(String name, Supplier<? extends JsonElement> schema) {
            String definition = validateDefinitionName(name);
            if (!definitions.has(definition)) {
                definitions.add(definition, new JsonObject());
                JsonElement value = Objects.requireNonNull(schema.get(), "Definition supplier returned null");
                definitions.add(definition, value.deepCopy());
            }
            return object(DatapackEditorConstants.SCHEMA_REF, DatapackEditorConstants.SCHEMA_DEFINITIONS_PREFIX + definition);
        }

        public JsonElement sharedDefinition(Object key, Supplier<? extends JsonElement> schema) {
            checkpoint();
            Objects.requireNonNull(key, "Definition key");
            String existing = familyDefinitions.get(key);
            if (existing != null) return object(DatapackEditorConstants.SCHEMA_REF, DatapackEditorConstants.SCHEMA_DEFINITIONS_PREFIX + existing);
            String name = "family_" + familyDefinitions.size();
            while (definitions.has(name)) name += "_";
            familyDefinitions.put(key, name);
            return definition(name, schema);
        }

        private static String validateDefinitionName(String name) {
            String value = Objects.requireNonNull(name, DatapackEditorConstants.JSON_NAME);
            if (value.isBlank() || !value.matches("[A-Za-z0-9_.:-]+")) {
                throw new IllegalArgumentException("Invalid schema definition name: " + name);
            }
            return value;
        }

        public JsonElement resolve(JsonElement schema) {
            Set<String> visited = new LinkedHashSet<>();
            JsonElement current = schema;
            while (current != null && current.isJsonObject() && current.getAsJsonObject().has(DatapackEditorConstants.SCHEMA_REF)) {
                String reference = current.getAsJsonObject().get(DatapackEditorConstants.SCHEMA_REF).getAsString();
                String prefix = DatapackEditorConstants.SCHEMA_DEFINITIONS_PREFIX;
                if (!visited.add(reference)) break;
                if (!reference.startsWith(prefix)) {
                    JsonElement shared = sharedRegistries == null ? null : sharedRegistries.resolve(reference);
                    if (shared == null) break;
                    current = shared;
                    continue;
                }
                String definition = reference.substring(prefix.length());
                if (!definitions.has(definition)) break;
                current = definitions.get(definition);
            }
            return current;
        }

        private JsonElement inferUncached(Object codec) {
            for (SchemaInspector inspector : INSPECTORS) {
                JsonElement inspected = inspector.inspect(new SchemaContext(this), codec);
                if (inspected != null) return inspected.deepCopy();
            }
            if (codec == BlockState.CODEC) return BlockStateSchema.create(new SchemaContext(this));
            if (codec == DebugStickState.CODEC) return BlockStateSchema.debugStick(new SchemaContext(this));
            if (codec == Ingredient.CODEC) return IngredientSchema.create(new SchemaContext(this), true);
            if (codec == Ingredient.CODEC_NONEMPTY) return IngredientSchema.create(new SchemaContext(this), false);
            if (codec == Codec.BOOL) return type(DatapackEditorConstants.SCHEMA_BOOLEAN);
            if (codec == Codec.BYTE || codec == Codec.SHORT || codec == Codec.INT || codec == Codec.LONG) {
                return type(DatapackEditorConstants.SCHEMA_INTEGER);
            }
            if (codec == Codec.FLOAT || codec == Codec.DOUBLE) return type(DatapackEditorConstants.SCHEMA_NUMBER);
            if (codec == Codec.STRING) return type(DatapackEditorConstants.SCHEMA_STRING);
            if (codec == Codec.BYTE_BUFFER || codec == Codec.INT_STREAM || codec == Codec.LONG_STREAM) {
                return array(type(DatapackEditorConstants.SCHEMA_INTEGER), null, null);
            }
            if (codec == Codec.PASSTHROUGH) return new JsonObject();
            if (isOptionalValueDispatch(codec)) return inferOptionalValueDispatch(codec);

            switch (codec) {
                case MapCodec.MapCodecCodec<?>(MapCodec<?> codec1) -> {
                    return infer(codec1);
                }
                case KeyDispatchCodec<?, ?> dispatch -> {
                    return inferDispatch(dispatch);
                }
                case MapCodec<?> mapCodec -> {
                    return inferMap(mapCodec);
                }
                case ListCodec<?>(Codec<?> elementCodec, int minSize, int maxSize) -> {
                    return array(infer(elementCodec), finiteMinimum(minSize), finiteMaximum(maxSize));
                }
                case EitherCodec<?, ?>(Codec<?> first, Codec<?> second) -> {
                    return alternatives(List.of(infer(first), infer(second)));
                }
                case XorCodec<?, ?>(Codec<?> first, Codec<?> second) -> {
                    return alternatives(List.of(infer(first), infer(second)));
                }
                case BaseMapCodec<?, ?> map -> {
                    return mapSchema(infer(map.keyCodec()), infer(map.elementCodec()));
                }
                case DispatchedMapCodec<?, ?> map -> {
                    return inferDispatchedMap(map);
                }
                case PairCodec<?, ?> pair -> {
                    return tuple(List.of(infer(readField(pair, "first")), infer(readField(pair, "second"))));
                }
                case CompoundListCodec<?, ?> compound -> {
                    return mapSchema(infer(readField(compound, DatapackEditorConstants.CODEC_KEY_CODEC)), infer(readField(compound, DatapackEditorConstants.CODEC_ELEMENT_CODEC)));
                }
                case HolderSetCodec<?> holderSet -> {
                    ResourceKey<? extends Registry<?>> key = registryKeyFrom(holderSet);
                    if (key != null) return holderSetSchema(key);
                }
                case RegistryFixedCodec<?> fixed -> {
                    ResourceKey<? extends Registry<?>> key = registryKeyFrom(fixed);
                    if (key != null) return registryReference(key, false);
                }
                case RegistryFileCodec<?> file -> {
                    ResourceKey<? extends Registry<?>> key = registryKeyFrom(file);
                    JsonElement reference = key == null ? type(DatapackEditorConstants.SCHEMA_STRING) : registryReference(key, false);
                    Object inline = readField(file, DatapackEditorConstants.CODEC_ELEMENT_CODEC);
                    boolean allowInline = Boolean.TRUE.equals(readField(file, "allowInline"));
                    return allowInline && inline != null ? alternatives(List.of(reference, infer(inline))) : reference;
                }
                default -> {
                }
            }

            Object nestedStructure = findNestedStructuralCodec(codec);
            if (nestedStructure != null) {
                JsonElement nestedSchema = infer(nestedStructure);
                if (isStringSchema(nestedSchema)) {
                    Registry<?> capturedRegistry = findCapturedRegistry(codec);
                    if (capturedRegistry != null) {
                        return registryReference(capturedRegistry.key(), false);
                    }
                    ResourceKey<? extends Registry<?>> capturedKey = findCapturedRegistryKey(codec);
                    if (capturedKey != null) {
                        return registryReference(capturedKey, false);
                    }
                }
                return nestedSchema;
            }

            List<String> enumValues = findSerializedValues(codec);
            if (!enumValues.isEmpty()) return stringEnum(enumValues);

            if (codec instanceof Codec.RecursiveCodec<?>) {
                Object supplier = readField(codec, DatapackEditorConstants.CODEC_WRAPPED);
                if (supplier instanceof Supplier<?> wrapped) {
                    return infer(safeGet(wrapped));
                }
            }

            List<JsonElement> inferred = distinctSchemas(structuralChildren(codec).stream().map(this::infer).toList());
            if (inferred.size() == 1 && isStringSchema(inferred.getFirst())) {

                Registry<?> capturedRegistry = findCapturedRegistry(codec);
                if (capturedRegistry != null) {
                    return registryReference(capturedRegistry.key(), false);
                }
                ResourceKey<? extends Registry<?>> capturedKey = findCapturedRegistryKey(codec);
                if (capturedKey != null) {
                    return registryReference(capturedKey, false);
                }
            }
            if (inferred.size() == 1 && !isUnconstrained(inferred.getFirst())) {
                return inferred.getFirst();
            }
            if (inferred.size() > 1) {
                return alternatives(inferred);
            }
            Registry<?> capturedRegistry = findCapturedRegistry(codec);
            if (capturedRegistry != null) {
                return registryReference(capturedRegistry.key(), false);
            }
            ResourceKey<? extends Registry<?>> capturedKey = findCapturedRegistryKey(codec);
            if (capturedKey != null) {
                return registryReference(capturedKey, false);
            }
            if (inferred.size() == 1) {
                return inferred.getFirst();
            }
            return unknown(codec);
        }

        private JsonElement inferMap(MapCodec<?> codec) {
            JsonElement exact = localOverrides.get(codec);
            if (exact == null) exact = OVERRIDES.get(codec);
            if (exact != null) return exact.deepCopy();
            if (codec instanceof KeyDispatchCodec<?, ?> dispatch) return inferDispatch(dispatch);
            if (codec instanceof SimpleMapCodec<?, ?> map) return inferSimpleMap(map);
            List<RecordField> fields = collectRecordFields(codec);
            if (!fields.isEmpty()) return recordSchema(fields);
            Object wrapped = readField(codec, DatapackEditorConstants.CODEC_WRAPPED);
            if (wrapped instanceof Supplier<?> supplier) {
                Object value = safeGet(supplier);
                if (value != null && value != codec) return infer(value);
            }
            try {
                if (codec.keys(JsonOps.INSTANCE).findAny().isEmpty()) return objectSchema();
            } catch (RuntimeException ignored) {
                if (ignored instanceof CancellationException) throw ignored;
                // Fall through to an open schema for codecs that cannot expose keys.
            }
            return unknown(codec);
        }

        private boolean isOptionalValueDispatch(Object codec) {
            if (!(codec instanceof MapCodec<?>)) return false;
            String className = codec.getClass().getName();
            if (!className.startsWith("net.minecraft.util.ExtraCodecs$")) return false;
            try {
                Set<String> keys = codec instanceof MapCodec<?> mapCodec ? mapCodec.keys(JsonOps.INSTANCE).flatMap(key -> JsonOps.INSTANCE.getStringValue(key).result().stream()).collect(Collectors.toSet()) : Set.of();
                return keys.size() == 2 && keys.contains(readField(codec, DatapackEditorConstants.CODEC_TYPE_KEY)) && keys.contains(readField(codec, DatapackEditorConstants.CODEC_VALUE_KEY));
            } catch (RuntimeException ignored) {
                if (ignored instanceof CancellationException) throw ignored;
                return false;
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private JsonElement inferOptionalValueDispatch(Object dispatch) {
            String typeKey = (String) readField(dispatch, DatapackEditorConstants.CODEC_TYPE_KEY);
            String valueKey = (String) readField(dispatch, DatapackEditorConstants.CODEC_VALUE_KEY);
            Codec typeCodec = (Codec) readField(dispatch, "val$typeCodec");
            Function valueCodec = (Function) readField(dispatch, "val$valueCodec");
            if (typeKey == null || valueKey == null || typeCodec == null || valueCodec == null) {
                return unknown(dispatch);
            }

            Registry<?> capturedRegistry = findCapturedRegistry(typeCodec);
            ResourceKey<? extends Registry<?>> capturedKey = findCapturedRegistryKey(typeCodec);
            JsonElement typeSchema;
            if (capturedRegistry != null) {
                typeSchema = registryReference(capturedRegistry.key(), false);
            } else {
                if (capturedKey != null) typeSchema = registryReference(capturedKey, false);
                else typeSchema = infer(typeCodec);
            }
            List<String> values = enumValues(typeSchema);
            if (values.isEmpty()) return unknown(dispatch);

            JsonObject root = objectSchema();
            JsonObject rootProperties = new JsonObject();
            rootProperties.add(typeKey, typeSchema);
            root.add(DatapackEditorConstants.JSON_PROPERTIES, rootProperties);
            root.add(DatapackEditorConstants.SCHEMA_REQUIRED, strings(List.of(typeKey)));

            JsonArray variants = new JsonArray();
            for (String value : values) {
                checkpoint();
                JsonObject variant = objectSchema();
                JsonObject properties = new JsonObject();
                properties.add(typeKey, object(DatapackEditorConstants.SCHEMA_CONST, value));

                Optional<?> decodedType = typeCodec.parse(ops, new JsonPrimitive(value)).result();
                if (decodedType.isPresent()) {
                    try {
                        Object selectedCodec = valueCodec.apply(decodedType.get());
                        if (selectedCodec != null) properties.add(valueKey, infer(selectedCodec));
                    } catch (RuntimeException ignored) {
                        if (ignored instanceof CancellationException) throw ignored;
                        // Keep the discriminator completion even if one variant cannot expose its value codec.
                    }
                }

                variant.add(DatapackEditorConstants.JSON_PROPERTIES, properties);
                variant.add(DatapackEditorConstants.SCHEMA_REQUIRED, strings(List.of(typeKey)));
                variant.addProperty(DatapackEditorConstants.JSON_TITLE, value);
                variants.add(variant);
            }
            root.add(DatapackEditorConstants.SCHEMA_ONE_OF, variants);
            return root;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private JsonElement inferDispatch(KeyDispatchCodec<?, ?> dispatch) {
            Codec keyCodec = (Codec) readField(dispatch, DatapackEditorConstants.CODEC_KEY_CODEC);
            String typeKey = (String) readField(dispatch, "typeKey");
            Function decoder = (Function) readField(dispatch, "decoder");
            if (keyCodec == null || typeKey == null || decoder == null) {
                return unknown(dispatch);
            }
            List<String> values = enumValues(infer(keyCodec));
            if (values.isEmpty()) {
                return unknown(dispatch);
            }

            JsonObject root = objectSchema();
            JsonObject rootProperties = new JsonObject();
            rootProperties.add(typeKey, stringEnum(values));
            root.add(DatapackEditorConstants.JSON_PROPERTIES, rootProperties);
            root.add(DatapackEditorConstants.SCHEMA_REQUIRED, strings(List.of(typeKey)));

            JsonArray variants = new JsonArray();
            for (String value : values) {
                checkpoint();
                Optional<?> decodedKey = keyCodec.parse(ops, new JsonPrimitive(value)).result();
                if (decodedKey.isEmpty()) {
                    variants.add(discriminatorOnly(typeKey, value));
                    continue;
                }
                Object result;
                try {
                    result = decoder.apply(decodedKey.get());
                } catch (RuntimeException exception) {
                    if (exception instanceof CancellationException) throw exception;
                    variants.add(discriminatorOnly(typeKey, value));
                    continue;
                }
                Object variantDecoder = result instanceof DataResult<?> dataResult ? dataResult.result().orElse(null) : result;
                JsonObject constraint = discriminatorOnly(typeKey, value);
                JsonElement body = variantDecoder == null ? new JsonObject() : inferMapDecoder(variantDecoder);
                JsonElement resolved = resolve(body);
                JsonObject variant;
                if (resolved.isJsonObject() && resolved.getAsJsonObject().has(DatapackEditorConstants.SCHEMA_ADDITIONAL_PROPERTIES)
                        && resolved.getAsJsonObject().get(DatapackEditorConstants.SCHEMA_ADDITIONAL_PROPERTIES).equals(new JsonPrimitive(false))) {
                    variant = resolved.getAsJsonObject().deepCopy();
                    addDiscriminator(variant, typeKey, value);
                } else {
                    variant = object(DatapackEditorConstants.SCHEMA_ALL_OF, arrayOf(body, constraint));
                }
                variant.addProperty(DatapackEditorConstants.JSON_TITLE, value);
                variants.add(variant);
            }
            root.add(DatapackEditorConstants.SCHEMA_ONE_OF, variants);
            return root;
        }

        private JsonElement inferMapDecoder(Object decoder) {
            if (decoder instanceof KeyDispatchCodec<?, ?> dispatch) return inferDispatch(dispatch);
            if (decoder instanceof MapCodec<?> mapCodec) return infer(mapCodec);
            List<RecordField> fields = collectRecordFields(decoder);
            return fields.isEmpty() ? unknown(decoder) : recordSchema(fields);
        }

        private JsonElement inferSimpleMap(SimpleMapCodec<?, ?> map) {
            List<String> keys = map.keys(JsonOps.INSTANCE).flatMap(key -> JsonOps.INSTANCE.getStringValue(key).result().stream()).distinct().sorted().toList();
            JsonObject schema = objectSchema();
            JsonObject properties = new JsonObject();
            JsonElement valueSchema = infer(map.elementCodec());
            // valueSchema is a small first-use reference; never copy the value body.
            keys.forEach(key -> { checkpoint(); properties.add(key, valueSchema.deepCopy()); });
            schema.add(DatapackEditorConstants.JSON_PROPERTIES, properties);
            schema.addProperty(DatapackEditorConstants.SCHEMA_ADDITIONAL_PROPERTIES, false);
            return schema;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private JsonElement inferDispatchedMap(DispatchedMapCodec<?, ?> map) {
            JsonElement keySchema = infer(map.keyCodec());
            List<String> keys = enumValues(keySchema);
            if (keys.isEmpty()) return mapSchema(keySchema, new JsonObject());
            JsonObject schema = objectSchema();
            JsonObject properties = new JsonObject();
            Function valueCodec = map.valueCodecFunction();
            for (String key : keys) {
                checkpoint();
                Optional<?> decoded = ((Codec) map.keyCodec()).parse(ops, new JsonPrimitive(key)).result();
                if (decoded.isEmpty()) continue;
                try {
                    properties.add(key, infer(valueCodec.apply(decoded.get())));
                } catch (RuntimeException ignored) {
                    if (ignored instanceof CancellationException) throw ignored;
                    properties.add(key, new JsonObject());
                }
            }
            schema.add(DatapackEditorConstants.JSON_PROPERTIES, properties);
            schema.addProperty(DatapackEditorConstants.SCHEMA_ADDITIONAL_PROPERTIES, true);
            return schema;
        }

        private JsonElement recordSchema(List<RecordField> fields) {
            Map<String, RecordField> unique = new LinkedHashMap<>();
            for (RecordField field : fields) {
                unique.merge(field.name(), field, (left, right) -> new RecordField(left.name(), left.codec(), left.optional() || right.optional()));
            }
            JsonObject schema = objectSchema();
            JsonObject properties = new JsonObject();
            JsonArray required = new JsonArray();
            unique.values().stream().sorted(Comparator.comparing(RecordField::name)).forEach(field -> {
                properties.add(field.name(), infer(field.codec()));
                if (!field.optional()) required.add(field.name());
            });
            schema.add(DatapackEditorConstants.JSON_PROPERTIES, properties);
            if (!required.isEmpty()) schema.add(DatapackEditorConstants.SCHEMA_REQUIRED, required);
            return schema;
        }

        private List<RecordField> collectRecordFields(Object root) {
            List<RecordField> result = new ArrayList<>();
            collectRecordFields(root, result, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
            return result;
        }

        private void collectRecordFields(Object node, List<RecordField> result, Set<Object> seen, int depth) {
            checkpoint();
            if (node == null || depth > DatapackEditorConstants.CODEC_REFLECTION_MAX_DEPTH || !seen.add(node)) return;
            switch (node) {
                case OptionalFieldCodec<?> unused1 -> {
                    Object name = readField(node, DatapackEditorConstants.JSON_NAME);
                    Object element = readField(node, DatapackEditorConstants.CODEC_ELEMENT_CODEC);
                    if (name instanceof String string && element != null)
                        result.add(new RecordField(string, element, true));
                    return;
                }
                case FieldDecoder<?> unused2 -> {
                    Object name = readField(node, DatapackEditorConstants.JSON_NAME);
                    Object element = readField(node, DatapackEditorConstants.CODEC_ELEMENT_CODEC);
                    if (name instanceof String string && element != null)
                        result.add(new RecordField(string, element, false));
                    return;
                }
                case KeyDispatchCodec<?, ?> unused3 -> {
                    return;
                }
                default -> {
                }
            }
            int firstField = result.size();
            for (Object child : reflectionChildren(node, false)) {
                checkpoint();
                if (child instanceof MapDecoder<?> || child instanceof Decoder<?> || isRecordBuilder(child)) {
                    collectRecordFields(child, result, seen, depth + 1);
                }
            }
            // MapCodec.orElse wraps a required FieldDecoder, but accepts an absent
            // field. Probe the containing map codec instead of relying on DFU's
            // anonymous wrapper class names; this also covers mod fallback codecs.
            if (node instanceof MapCodec<?> map && result.size() > firstField
                    && result.subList(firstField, result.size()).stream().anyMatch(field -> !field.optional())) {
                try {
                    if (map.codec().parse(ops, new JsonObject()).isSuccess()) {
                        for (int index = firstField; index < result.size(); index++) {
                            RecordField field = result.get(index);
                            result.set(index, new RecordField(field.name(), field.codec(), true));
                        }
                    }
                } catch (RuntimeException ignored) {
                    if (ignored instanceof CancellationException) throw ignored;
                    // A failed decode provides no evidence that the fields are optional.
                }
            }
        }

        private List<Object> structuralChildren(Object node) {
            return reflectionChildren(node, true).stream().filter(child -> child instanceof Decoder<?> || child instanceof MapDecoder<?>).toList();
        }

        private Object findNestedStructuralCodec(Object root) {
            ArrayDeque<Object> pending = new ArrayDeque<>(structuralChildren(root));
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            int visited = 0;
            while (!pending.isEmpty() && visited++ < 256) {
                Object candidate = pending.removeFirst();
                if (!seen.add(candidate)) continue;
                if (candidate == BlockState.CODEC || candidate == DebugStickState.CODEC || candidate == Ingredient.CODEC || candidate == Ingredient.CODEC_NONEMPTY || hasCustomization(candidate)
                        || candidate instanceof ListCodec<?> || candidate instanceof EitherCodec<?, ?> || candidate instanceof XorCodec<?, ?> || candidate instanceof UnboundedMapCodec<?, ?> || candidate instanceof SimpleMapCodec<?, ?> || candidate instanceof DispatchedMapCodec<?, ?> || candidate instanceof PairCodec<?, ?> || candidate instanceof CompoundListCodec<?, ?> || candidate instanceof KeyDispatchCodec<?, ?> || candidate instanceof MapCodec.MapCodecCodec<?> || isMinecraftStructuralCodec(candidate)) {
                    return candidate;
                }
                pending.addAll(structuralChildren(candidate));
            }
            return null;
        }

        private boolean hasCustomization(Object codec) {
            return localOverrides.containsKey(codec) || localRegistryOverrides.containsKey(codec)
                    || localProviders.containsKey(codec) || localAdapters.containsKey(codec)
                    || OVERRIDES.containsKey(codec) || REGISTRY_OVERRIDES.containsKey(codec)
                    || PROVIDERS.containsKey(codec) || ADAPTERS.containsKey(codec);
        }

        private static boolean isMinecraftStructuralCodec(Object codec) {
            String name = codec.getClass().getName();
            return name.equals("net.minecraft.resources.HolderSetCodec") ||
                    name.equals("net.minecraft.resources.RegistryFixedCodec") ||
                    name.equals("net.minecraft.resources.RegistryFileCodec");
        }

        private List<Object> reflectionChildren(Object node, boolean includeCodecs) {
            List<Object> children = new ArrayList<>();
            for (Field field : fieldsOf(node.getClass())) {
                checkpoint();
                if (Modifier.isStatic(field.getModifiers()) || field.getName().equals("compressors")) continue;
                Object value = readField(node, field);
                if (value == null || value == node) {
                    continue;
                }
                if (value instanceof MapDecoder<?> || value instanceof Decoder<?> || isRecordBuilder(value)) {
                    children.add(value);
                }
            }
            return children;
        }

        private Registry<?> findCapturedRegistry(Object root) {
            return findCaptured(root, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        }

        private Registry<?> findCaptured(Object node, Set<Object> seen, int depth) {
            checkpoint();
            if (node == null || depth > DatapackEditorConstants.CODEC_REFLECTION_MAX_DEPTH || !seen.add(node)) return null;
            if (node instanceof Registry<?> registry) return registry;
            if (!isInspectable(node)) return null;
            for (Field field : fieldsOf(node.getClass())) {
                checkpoint();
                if (Modifier.isStatic(field.getModifiers())) continue;
                Object child = readField(node, field);
                if (child instanceof Registry<?> registry) return registry;
                Registry<?> found = findCaptured(child, seen, depth + 1);
                if (found != null) return found;
            }
            return null;
        }

        private ResourceKey<? extends Registry<?>> findCapturedRegistryKey(Object root) {
            return findRegistryKey(root, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        }

        private ResourceKey<? extends Registry<?>> findRegistryKey(Object node, Set<Object> seen, int depth) {
            checkpoint();
            if (node == null || depth > DatapackEditorConstants.CODEC_REFLECTION_MAX_DEPTH || !seen.add(node)) return null;
            if (node instanceof ResourceKey<?> key && isRegistryKey(key)) return castRegistryKey(key);
            if (!isInspectable(node)) return null;
            for (Field field : fieldsOf(node.getClass())) {
                checkpoint();
                if (Modifier.isStatic(field.getModifiers())) continue;
                ResourceKey<? extends Registry<?>> found = findRegistryKey(readField(node, field), seen, depth + 1);
                if (found != null) return found;
            }
            return null;
        }

        private List<String> findSerializedValues(Object root) {
            Set<String> values = new LinkedHashSet<>();
            findSerializedValues(root, values, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
            return values.stream().sorted().toList();
        }

        private void findSerializedValues(Object node, Set<String> values, Set<Object> seen, int depth) {
            checkpoint();
            if (node == null || depth > 8 || !seen.add(node) || node instanceof Registry<?>) return;
            Class<?> type = node.getClass();
            if (type.isArray()) {
                int length = Array.getLength(node);
                for (int index = 0; index < length; index++) {
                    Object value = Array.get(node, index);
                    if (value instanceof StringRepresentable representable)
                        values.add(representable.getSerializedName());
                }
                return;
            }
            if (node instanceof Map<?, ?> map && map.size() <= 512 && map.keySet().stream().allMatch(String.class::isInstance)) {
                map.keySet().forEach(key -> values.add((String) key));
                return;
            }
            if (!isInspectable(node)) return;
            for (Field field : fieldsOf(type)) {
                checkpoint();
                if (!Modifier.isStatic(field.getModifiers())) {
                    findSerializedValues(readField(node, field), values, seen, depth + 1);
                }
            }
        }

        private ResourceKey<? extends Registry<?>> registryKeyFrom(Object codec) {
            Object key = readField(codec, DatapackEditorConstants.CODEC_REGISTRY_KEY);
            return key instanceof ResourceKey<?> resourceKey && isRegistryKey(resourceKey) ? castRegistryKey(resourceKey) : null;
        }

        private JsonElement holderSetSchema(ResourceKey<? extends Registry<?>> key) {
            JsonArray alternatives = new JsonArray();
            alternatives.add(registryReference(key, true));
            alternatives.add(array(registryReference(key, false), 1, null));
            alternatives.add(object(DatapackEditorConstants.JSON_TYPE, DatapackEditorConstants.SCHEMA_OBJECT, DatapackEditorConstants.JSON_DESCRIPTION, "Registered custom holder set"));
            return object(DatapackEditorConstants.SCHEMA_ANY_OF, alternatives);
        }

        public JsonElement registryReference(ResourceKey<? extends Registry<?>> key, boolean includeTags) {
            checkpoint();
            if (sharedRegistries != null) return sharedRegistries.reference(key, includeTags);
            String definition = "registry_" + sanitize(key.location().toString()) + (includeTags ? "_or_tag" : "");
            if (!definitions.has(definition)) {
                List<String> values = registryValues(key, includeTags);
                definitions.add(definition, values.isEmpty() ? type(DatapackEditorConstants.SCHEMA_STRING) : stringEnum(values));
            }
            return object(DatapackEditorConstants.SCHEMA_REF, DatapackEditorConstants.SCHEMA_DEFINITIONS_PREFIX + definition);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private List<String> registryValues(ResourceKey<? extends Registry<?>> key, boolean includeTags) {
            Optional<? extends HolderLookup.RegistryLookup<Object>> lookup = (Optional) registries.lookup((ResourceKey) key);
            if (lookup.isEmpty()) return List.of();
            Set<String> values = new LinkedHashSet<>();
            lookup.get().listElementIds().map(ResourceKey::location).map(ResourceLocation::toString).forEach(values::add);
            if (includeTags) lookup.get().listTagIds().map(tag -> DatapackEditorConstants.MINECRAFT_TAG_PREFIX + tag.location()).forEach(values::add);
            return values.stream().sorted().toList();
        }

        private JsonObject mapSchema(JsonElement key, JsonElement value) {
            JsonObject schema = objectSchema();
            schema.add(DatapackEditorConstants.SCHEMA_PROPERTY_NAMES, key);
            schema.add(DatapackEditorConstants.SCHEMA_ADDITIONAL_PROPERTIES, value);
            return schema;
        }

        private JsonObject tuple(List<JsonElement> entries) {
            JsonObject schema = object(DatapackEditorConstants.JSON_TYPE, DatapackEditorConstants.SCHEMA_ARRAY, DatapackEditorConstants.SCHEMA_MIN_ITEMS, entries.size(), DatapackEditorConstants.SCHEMA_MAX_ITEMS, entries.size());
            JsonArray items = new JsonArray();
            entries.forEach(items::add);
            schema.add(DatapackEditorConstants.SCHEMA_ITEMS, items);
            return schema;
        }

        private JsonObject discriminatorOnly(String field, String value) {
            JsonObject variant = objectSchema();
            addDiscriminator(variant, field, value);
            return variant;
        }

        private void addDiscriminator(JsonObject schema, String field, String value) {
            schema.addProperty(DatapackEditorConstants.JSON_TYPE, DatapackEditorConstants.SCHEMA_OBJECT);
            JsonObject properties = schema.has(DatapackEditorConstants.JSON_PROPERTIES) && schema.get(DatapackEditorConstants.JSON_PROPERTIES).isJsonObject() ? schema.getAsJsonObject(DatapackEditorConstants.JSON_PROPERTIES) : new JsonObject();
            properties.add(field, object(DatapackEditorConstants.SCHEMA_CONST, value));
            schema.add(DatapackEditorConstants.JSON_PROPERTIES, properties);
            Set<String> required = new LinkedHashSet<>();
            if (schema.has(DatapackEditorConstants.SCHEMA_REQUIRED) && schema.get(DatapackEditorConstants.SCHEMA_REQUIRED).isJsonArray()) {
                schema.getAsJsonArray(DatapackEditorConstants.SCHEMA_REQUIRED).forEach(element -> required.add(element.getAsString()));
            }
            required.add(field);
            schema.add(DatapackEditorConstants.SCHEMA_REQUIRED, strings(required));
        }

        private List<String> enumValues(JsonElement schema) {
            schema = resolve(schema);
            if (schema == null || !schema.isJsonObject()) return List.of();
            JsonObject object = schema.getAsJsonObject();
            if (object.has(DatapackEditorConstants.SCHEMA_ENUM) && object.get(DatapackEditorConstants.SCHEMA_ENUM).isJsonArray()) {
                return object.getAsJsonArray(DatapackEditorConstants.SCHEMA_ENUM).asList().stream().map(JsonElement::getAsString).toList();
            }
            if (object.has(DatapackEditorConstants.SCHEMA_REF)) {
                String ref = object.get(DatapackEditorConstants.SCHEMA_REF).getAsString();
                String prefix = DatapackEditorConstants.SCHEMA_DEFINITIONS_PREFIX;
                if (ref.startsWith(prefix) && definitions.has(ref.substring(prefix.length()))) {
                    return enumValues(definitions.get(ref.substring(prefix.length())));
                }
            }
            return List.of();
        }

        private static List<JsonElement> distinctSchemas(List<JsonElement> schemas) {
            return List.copyOf(new LinkedHashSet<>(schemas));
        }

        private static JsonArray arrayOf(JsonElement... schemas) {
            JsonArray array = new JsonArray();
            for (JsonElement schema : schemas) array.add(schema);
            return array;
        }

        public void checkpoint() {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException(DatapackEditorConstants.MESSAGE_SCHEMA_GENERATION_CANCELLED);
            if (memoizedDefinitions.size() > DatapackEditorConstants.SCHEMA_MAX_DEFINITIONS)
                throw new CancellationException("Schema generation exceeded its definition budget (" + memoizedDefinitions.size() + "/" + DatapackEditorConstants.SCHEMA_MAX_DEFINITIONS + ")");
            long elapsed = System.nanoTime() - started;
            if (elapsed > TimeUnit.SECONDS.toNanos(DatapackEditorConstants.WORK_TIMEOUT_SECONDS))
                throw new CancellationException("Schema generation exceeded its time budget (" + TimeUnit.NANOSECONDS.toMillis(elapsed)
                        + " ms, " + memoizedDefinitions.size() + " codec definitions)");
        }

        private static JsonObject alternatives(List<JsonElement> schemas) {
            List<JsonElement> unique = distinctSchemas(schemas);
            if (unique.size() == 1) return asObject(unique.getFirst());
            JsonArray alternatives = new JsonArray();
            unique.forEach(alternatives::add);
            return object(DatapackEditorConstants.SCHEMA_ANY_OF, alternatives);
        }

        private static JsonObject unknown(Object codec) {
            JsonObject schema = new JsonObject();
            if (codec != null) schema.addProperty("$comment", "Unresolved codec: " + codec.getClass().getName());
            return schema;
        }

        private static JsonObject type(String type) {
            return object(DatapackEditorConstants.JSON_TYPE, type);
        }

        private static JsonObject objectSchema() {
            return type(DatapackEditorConstants.SCHEMA_OBJECT);
        }

        private static JsonObject stringEnum(Collection<String> values) {
            JsonObject schema = type(DatapackEditorConstants.SCHEMA_STRING);
            schema.add(DatapackEditorConstants.SCHEMA_ENUM, strings(values));
            return schema;
        }

        private static JsonObject array(JsonElement items, Integer minimum, Integer maximum) {
            JsonObject schema = type(DatapackEditorConstants.SCHEMA_ARRAY);
            schema.add(DatapackEditorConstants.SCHEMA_ITEMS, items);
            if (minimum != null) schema.addProperty(DatapackEditorConstants.SCHEMA_MIN_ITEMS, minimum);
            if (maximum != null) schema.addProperty(DatapackEditorConstants.SCHEMA_MAX_ITEMS, maximum);
            return schema;
        }

        private static JsonArray strings(Collection<String> values) {
            JsonArray array = new JsonArray();
            values.forEach(array::add);
            return array;
        }

        private static JsonObject object(Object... entries) {
            JsonObject object = new JsonObject();
            for (int index = 0; index < entries.length; index += 2) {
                String key = (String) entries[index];
                Object value = entries[index + 1];
                if (value instanceof JsonElement json) object.add(key, json);
                else if (value instanceof String string) object.addProperty(key, string);
                else if (value instanceof Number number) object.addProperty(key, number);
                else if (value instanceof Boolean bool) object.addProperty(key, bool);
            }
            return object;
        }

        private static JsonObject asObject(JsonElement schema) {
            return schema != null && schema.isJsonObject() ? schema.getAsJsonObject() : new JsonObject();
        }

        private static boolean isUnconstrained(JsonElement schema) {
            return schema != null && schema.isJsonObject() && schema.getAsJsonObject().isEmpty();
        }

        private boolean isStringSchema(JsonElement schema) {
            return isStringSchema(schema, new LinkedHashSet<>());
        }

        private boolean isStringSchema(JsonElement schema, Set<String> visitedReferences) {
            schema = resolve(schema);
            if (schema == null || !schema.isJsonObject()) return false;
            JsonObject object = schema.getAsJsonObject();
            if (object.has(DatapackEditorConstants.JSON_TYPE) && DatapackEditorConstants.SCHEMA_STRING.equals(object.get(DatapackEditorConstants.JSON_TYPE).getAsString())) return true;
            if (!object.has(DatapackEditorConstants.SCHEMA_REF)) return false;
            String reference = object.get(DatapackEditorConstants.SCHEMA_REF).getAsString();
            String prefix = DatapackEditorConstants.SCHEMA_DEFINITIONS_PREFIX;
            if (!reference.startsWith(prefix) || !visitedReferences.add(reference)) return false;
            String definition = reference.substring(prefix.length());
            return definitions.has(definition) && isStringSchema(definitions.get(definition), visitedReferences);
        }

        private static Integer finiteMinimum(int value) {
            return value <= 0 ? null : value;
        }

        private static Integer finiteMaximum(int value) {
            return value == Integer.MAX_VALUE ? null : value;
        }

        private static String sanitize(String value) {
            return value.replaceAll("[^A-Za-z0-9_]", "_");
        }

        private static boolean isRecordBuilder(Object value) {
            return value != null && value.getClass().getName().startsWith("com.mojang.serialization.codecs.RecordCodecBuilder");
        }

        private static boolean isInspectable(Object value) {
            if (value == null) return false;
            Class<?> type = value.getClass();
            String name = type.getName();
            return name.startsWith("com.mojang.serialization") || name.startsWith("net.minecraft") || type.isSynthetic() || name.contains("$$Lambda");
        }

        private static boolean isRegistryKey(ResourceKey<?> key) {
            return key.registry().equals(Registries.ROOT_REGISTRY_NAME);
        }

        @SuppressWarnings("unchecked")
        private static ResourceKey<? extends Registry<?>> castRegistryKey(ResourceKey<?> key) {
            return (ResourceKey<? extends Registry<?>>) key;
        }

        private static Object safeGet(Supplier<?> supplier) {
            try {
                return supplier.get();
            } catch (RuntimeException exception) {
                if (exception instanceof CancellationException) throw exception;
                return null;
            }
        }

        private static List<Field> fieldsOf(Class<?> type) {
            return FIELDS.get(type);
        }

        private static Object readField(Object owner, String name) {
            if (owner == null) return null;
            for (Field field : fieldsOf(owner.getClass())) {
                if (field.getName().equals(name)) return readField(owner, field);
            }
            return null;
        }

        private static Object readField(Object owner, Field field) {
            try {
                if (!field.trySetAccessible()) return null;
                return field.get(owner);
            } catch (IllegalAccessException | RuntimeException exception) {
                return null;
            }
        }
    }
}
