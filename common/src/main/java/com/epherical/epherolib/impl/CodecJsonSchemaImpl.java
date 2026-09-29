package com.epherical.epherolib.impl;

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
import java.util.stream.Collectors;

import com.epherical.epherolib.api.schema.CodecJsonSchema.Builder;
import com.epherical.epherolib.api.schema.CodecJsonSchema.SchemaAdapter;
import com.epherical.epherolib.api.schema.CodecJsonSchema.SchemaContext;
import com.epherical.epherolib.api.schema.CodecJsonSchema.SchemaProvider;
import com.epherical.epherolib.api.schema.CodecJsonSchema.SchemaInspector;

import static com.epherical.epherolib.api.schema.CodecJsonSchema.ADAPTERS;
import static com.epherical.epherolib.api.schema.CodecJsonSchema.INSPECTORS;
import static com.epherical.epherolib.api.schema.CodecJsonSchema.OVERRIDES;
import static com.epherical.epherolib.api.schema.CodecJsonSchema.PROVIDERS;
import static com.epherical.epherolib.api.schema.CodecJsonSchema.REGISTRY_OVERRIDES;

public class CodecJsonSchemaImpl {

    public static final String NUMBER = "number";
    public static final String STRING = "string";
    public static final String INTEGER = "integer";
    public static final String BOOLEAN = "boolean";
    public static final String $_REF = "$ref";
    public static final String DEFINITIONS = "#/definitions/";
    public static final String PROPERTIES = "properties";
    public static final String ADDITIONAL_PROPERTIES = "additionalProperties";
    public static final String TITLE = "title";
    public static final String NAME = "name";
    public static final String ENUM = "enum";
    public static final String REQUIRED = "required";
    public static final String TYPE = "type";

    public static final String SCHEMA_ADAPTER_RETURNED_NULL = "Schema adapter returned null";

    // codec definitions
    public static final String FIRST = "first";
    public static final String SECOND = "second";
    public static final String KEY_CODEC = "keyCodec";
    public static final String ELEMENT_CODEC = "elementCodec";
    public static final String ALLOW_INLINE = "allowInline";
    public static final String WRAPPED = "wrapped";
    public static final String VAL_$_TYPE_KEY = "val$typeKey";
    public static final String VAL_$_VALUE_KEY = "val$valueKey";
    public static final String VAL_$_TYPE_CODEC = "val$typeCodec";
    public static final String VAL_$_VALUE_CODEC = "val$valueCodec";
    public static final String DECODER = "decoder";
    public static final String REGISTRY_KEY = "registryKey";



    public static JsonObject create(Codec<?> codec, HolderLookup.Provider registries) {
        return new Generator(registries).create(codec);
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
        private static final int REFLECTION_DEPTH = 32;
        public final HolderLookup.Provider registries;
        private final DynamicOps<JsonElement> ops;
        private final Map<Object, JsonElement> localOverrides;
        private final Map<Object, RegistryOverride> localRegistryOverrides;
        private final Map<Object, SchemaProvider> localProviders;
        private final Map<Object, SchemaAdapter> localAdapters;
        public final JsonObject definitions = new JsonObject();
        private final Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<Object, JsonElement> memoized = new IdentityHashMap<>();
        private final Map<Object, String> memoizedDefinitions = new IdentityHashMap<>();
        private int nextMemoizedDefinition;

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
            JsonObject schema = asObject(infer(codec));
            schema.addProperty("$schema", "http://json-schema.org/draft-07/schema#");
            if (!definitions.isEmpty()) schema.add("definitions", definitions);
            return schema;
        }

        public JsonElement infer(Object codec) {
            if (codec == null) return unknown(null);
            JsonElement exact = localOverrides.get(codec);
            if (exact != null) return adapt(codec, exact.deepCopy());
            RegistryOverride registryOverride = localRegistryOverrides.get(codec);
            if (registryOverride != null) {
                return adapt(codec, registryReference(registryOverride.key(), registryOverride.includeTags()));
            }
            SchemaProvider provider = localProviders.get(codec);
            if (provider != null) return adapt(codec, provide(provider));

            exact = OVERRIDES.get(codec);
            if (exact != null) return adapt(codec, exact.deepCopy());
            registryOverride = REGISTRY_OVERRIDES.get(codec);
            if (registryOverride != null) {
                return adapt(codec, registryReference(registryOverride.key(), registryOverride.includeTags()));
            }
            provider = PROVIDERS.get(codec);
            if (provider != null) return adapt(codec, provide(provider));

            JsonElement cached = memoized.get(codec);
            if (cached != null) return memoizedReference(codec, cached);
            if (!active.add(codec)) return unknown(codec);
            try {
                JsonElement result = adapt(codec, inferUncached(codec));
                memoized.put(codec, result.deepCopy());
                return result;
            } finally {
                active.remove(codec);
            }
        }

        private JsonElement provide(SchemaProvider provider) {
            return Objects.requireNonNull(provider.create(new SchemaContext(this)), "Schema provider returned null");
        }

        private JsonElement adapt(Object codec, JsonElement inferred) {
            SchemaContext context = new SchemaContext(this);
            JsonElement adapted = inferred;
            SchemaAdapter global = ADAPTERS.get(codec);
            if (global != null)
                adapted = Objects.requireNonNull(global.adapt(context, adapted), SCHEMA_ADAPTER_RETURNED_NULL);
            SchemaAdapter local = localAdapters.get(codec);
            if (local != null)
                adapted = Objects.requireNonNull(local.adapt(context, adapted), SCHEMA_ADAPTER_RETURNED_NULL);
            return adapted;
        }

        public JsonElement definition(String name, Supplier<? extends JsonElement> schema) {
            String definition = validateDefinitionName(name);
            if (!definitions.has(definition)) {
                definitions.add(definition, new JsonObject());
                JsonElement value = Objects.requireNonNull(schema.get(), "Definition supplier returned null");
                definitions.add(definition, value.deepCopy());
            }
            return object($_REF, DEFINITIONS + definition);
        }

        private static String validateDefinitionName(String name) {
            String value = Objects.requireNonNull(name, NAME);
            if (value.isBlank() || !value.matches("[A-Za-z0-9_.:-]+")) {
                throw new IllegalArgumentException("Invalid schema definition name: " + name);
            }
            return value;
        }

        private JsonElement memoizedReference(Object codec, JsonElement cached) {
            String definition = memoizedDefinitions.computeIfAbsent(codec, ignored -> "codec_" + ++nextMemoizedDefinition);
            if (!definitions.has(definition)) definitions.add(definition, cached);
            return object($_REF, DEFINITIONS + definition);
        }

        public JsonElement resolve(JsonElement schema) {
            Set<String> visited = new LinkedHashSet<>();
            JsonElement current = schema;
            while (current != null && current.isJsonObject() && current.getAsJsonObject().has($_REF)) {
                String reference = current.getAsJsonObject().get($_REF).getAsString();
                String prefix = DEFINITIONS;
                if (!reference.startsWith(prefix) || !visited.add(reference)) break;
                String definition = reference.substring(prefix.length());
                if (!definitions.has(definition)) break;
                current = definitions.get(definition);
            }
            return current;
        }

        private JsonElement inferUncached(Object codec) {
            for (SchemaInspector inspector : INSPECTORS) {
                JsonElement inspected = inspector.inspect(new SchemaContext(this), codec);
                if (inspected != null) return inspected;
            }
            if (codec == Codec.BOOL) return type(BOOLEAN);
            if (codec == Codec.BYTE || codec == Codec.SHORT || codec == Codec.INT || codec == Codec.LONG) {
                return type(INTEGER);
            }
            if (codec == Codec.FLOAT || codec == Codec.DOUBLE) return type(NUMBER);
            if (codec == Codec.STRING) return type(STRING);
            if (codec == Codec.BYTE_BUFFER || codec == Codec.INT_STREAM || codec == Codec.LONG_STREAM) {
                return array(type(INTEGER), null, null);
            }
            if (codec == Codec.PASSTHROUGH) return new JsonObject();
            if (codec.getClass().getName().startsWith("com.mojang.serialization.MapCodec$") && codec.toString().startsWith("Unit["))
                return objectSchema();

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
                    return tuple(List.of(infer(readField(pair, FIRST)), infer(readField(pair, SECOND))));
                }
                case CompoundListCodec<?, ?> compound -> {
                    return mapSchema(infer(readField(compound, KEY_CODEC)), infer(readField(compound, ELEMENT_CODEC)));
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
                    JsonElement reference = key == null ? type(STRING) : registryReference(key, false);
                    Object inline = readField(file, ELEMENT_CODEC);
                    boolean allowInline = Boolean.TRUE.equals(readField(file, ALLOW_INLINE));
                    return allowInline && inline != null ? alternatives(List.of(reference, infer(inline))) : reference;
                }
                default -> {
                }
            }

            Registry<?> capturedRegistry = findCapturedRegistry(codec);
            ResourceKey<? extends Registry<?>> capturedKey = findCapturedRegistryKey(codec);
            Object nestedStructure = findNestedStructuralCodec(codec);
            if (nestedStructure != null) {
                JsonElement nestedSchema = infer(nestedStructure);
                if (isStringSchema(nestedSchema)) {
                    if (capturedRegistry != null) {
                        return registryReference(capturedRegistry.key(), false);
                    }
                    if (capturedKey != null) {
                        return registryReference(capturedKey, false);
                    }
                }
                return nestedSchema;
            }

            List<String> enumValues = findSerializedValues(codec);
            if (!enumValues.isEmpty()) return stringEnum(enumValues);

            if (codec instanceof Codec.RecursiveCodec<?>) {
                Object supplier = readField(codec, WRAPPED);
                if (supplier instanceof Supplier<?> wrapped) {
                    return infer(safeGet(wrapped));
                }
            }

            List<JsonElement> inferred = distinctSchemas(structuralChildren(codec).stream().map(this::infer).toList());
            if (inferred.size() == 1 && isStringSchema(inferred.getFirst())) {
                if (capturedRegistry != null) {
                    return registryReference(capturedRegistry.key(), false);
                }
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
            if (capturedRegistry != null) {
                return registryReference(capturedRegistry.key(), false);
            }
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
            Object wrapped = readField(codec, WRAPPED);
            if (wrapped instanceof Supplier<?> supplier) {
                Object value = safeGet(supplier);
                if (value != null && value != codec) return infer(value);
            }
            try {
                if (codec.keys(JsonOps.INSTANCE).findAny().isEmpty()) return objectSchema();
            } catch (RuntimeException ignored) {
                // Fall through to an open schema for codecs that cannot expose keys.
            }
            return unknown(codec);
        }

        private static boolean isOptionalValueDispatch(Object codec) {
            if (!(codec instanceof MapCodec<?>)) return false;
            String className = codec.getClass().getName();
            if (!className.startsWith("net.minecraft.util.ExtraCodecs$")) return false;
            try {
                Set<String> keys = codec instanceof MapCodec<?> mapCodec ? mapCodec.keys(JsonOps.INSTANCE).flatMap(key -> JsonOps.INSTANCE.getStringValue(key).result().stream()).collect(Collectors.toSet()) : Set.of();
                return keys.size() == 2 && keys.contains(readField(codec, VAL_$_TYPE_KEY)) && keys.contains(readField(codec, VAL_$_VALUE_KEY));
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        /**
         * Reads ExtraCodecs.dispatchOptionalValue without depending on its anonymous class number.
         */
        @SuppressWarnings({"rawtypes", "unchecked"})
        private JsonElement inferOptionalValueDispatch(Object dispatch) {
            String typeKey = (String) readField(dispatch, VAL_$_TYPE_KEY);
            String valueKey = (String) readField(dispatch, VAL_$_VALUE_KEY);
            Codec typeCodec = (Codec) readField(dispatch, VAL_$_TYPE_CODEC);
            Function valueCodec = (Function) readField(dispatch, VAL_$_VALUE_CODEC);
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
            root.add(PROPERTIES, rootProperties);
            root.add(REQUIRED, strings(List.of(typeKey)));

            JsonArray variants = new JsonArray();
            for (String value : values) {
                JsonObject variant = objectSchema();
                JsonObject properties = new JsonObject();
                properties.add(typeKey, object("const", value));

                Optional<?> decodedType = typeCodec.parse(ops, new JsonPrimitive(value)).result();
                if (decodedType.isPresent()) {
                    try {
                        Object selectedCodec = valueCodec.apply(decodedType.get());
                        if (selectedCodec != null) properties.add(valueKey, infer(selectedCodec));
                    } catch (RuntimeException ignored) {
                        // Keep the discriminator completion even if one variant cannot expose its value codec.
                    }
                }

                variant.add(PROPERTIES, properties);
                variant.add(REQUIRED, strings(List.of(typeKey)));
                variant.addProperty(TITLE, value);
                variants.add(variant);
            }
            root.add("oneOf", variants);
            return root;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private JsonElement inferDispatch(KeyDispatchCodec<?, ?> dispatch) {
            MapCodec keyCodec = (MapCodec) readField(dispatch, KEY_CODEC);
            Function decoder = (Function) readField(dispatch, DECODER);
            if (keyCodec == null || decoder == null) {
                return unknown(dispatch);
            }
            List<RecordField> discriminatorFields = collectRecordFields(keyCodec);
            if (discriminatorFields.size() != 1) {
                return unknown(dispatch);
            }
            RecordField discriminator = discriminatorFields.getFirst();
            List<String> values = enumValues(infer(discriminator.codec()));
            if (values.isEmpty()) {
                return unknown(dispatch);
            }

            JsonObject root = objectSchema();
            JsonObject rootProperties = new JsonObject();
            rootProperties.add(discriminator.name(), stringEnum(values));
            root.add(PROPERTIES, rootProperties);
            root.add(REQUIRED, strings(List.of(discriminator.name())));

            JsonArray variants = new JsonArray();
            for (String value : values) {
                JsonObject encodedKey = new JsonObject();
                encodedKey.addProperty(discriminator.name(), value);
                Optional<?> decodedKey = keyCodec.codec().parse(ops, encodedKey).result();
                if (decodedKey.isEmpty()) {
                    variants.add(discriminatorOnly(discriminator.name(), value));
                    continue;
                }
                Object result;
                try {
                    result = decoder.apply(decodedKey.get());
                } catch (RuntimeException exception) {
                    variants.add(discriminatorOnly(discriminator.name(), value));
                    continue;
                }
                Object variantDecoder = result instanceof DataResult<?> dataResult ? dataResult.result().orElse(null) : result;
                JsonObject variant = variantDecoder == null ? discriminatorOnly(discriminator.name(), value) : asObject(inferMapDecoder(variantDecoder));
                addDiscriminator(variant, discriminator.name(), value);
                variant.addProperty(TITLE, value);
                variants.add(variant);
            }
            root.add("oneOf", variants);
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
            keys.forEach(key -> properties.add(key, valueSchema.deepCopy()));
            schema.add(PROPERTIES, properties);
            schema.addProperty(ADDITIONAL_PROPERTIES, false);
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
                Optional<?> decoded = ((Codec) map.keyCodec()).parse(ops, new JsonPrimitive(key)).result();
                if (decoded.isEmpty()) continue;
                try {
                    properties.add(key, infer(valueCodec.apply(decoded.get())));
                } catch (RuntimeException ignored) {
                    properties.add(key, new JsonObject());
                }
            }
            schema.add(PROPERTIES, properties);
            schema.addProperty(ADDITIONAL_PROPERTIES, true);
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
            schema.add(PROPERTIES, properties);
            if (!required.isEmpty()) schema.add(REQUIRED, required);
            return schema;
        }

        private List<RecordField> collectRecordFields(Object root) {
            List<RecordField> result = new ArrayList<>();
            collectRecordFields(root, result, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
            return result;
        }

        private void collectRecordFields(Object node, List<RecordField> result, Set<Object> seen, int depth) {
            if (node == null || depth > REFLECTION_DEPTH || !seen.add(node)) return;
            switch (node) {
                case OptionalFieldCodec<?> _ -> {
                    Object name = readField(node, NAME);
                    Object element = readField(node, ELEMENT_CODEC);
                    if (name instanceof String string && element != null)
                        result.add(new RecordField(string, element, true));
                    return;
                }
                case FieldDecoder<?> _ -> {
                    Object name = readField(node, NAME);
                    Object element = readField(node, ELEMENT_CODEC);
                    if (name instanceof String string && element != null)
                        result.add(new RecordField(string, element, false));
                    return;
                }
                case KeyDispatchCodec<?, ?> _ -> {
                    return;
                }
                default -> {
                }
            }
            int firstField = result.size();
            for (Object child : reflectionChildren(node, false)) {
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
                if (candidate instanceof ListCodec<?> || candidate instanceof EitherCodec<?, ?> || candidate instanceof XorCodec<?, ?> || candidate instanceof UnboundedMapCodec<?, ?> || candidate instanceof SimpleMapCodec<?, ?> || candidate instanceof DispatchedMapCodec<?, ?> || candidate instanceof PairCodec<?, ?> || candidate instanceof CompoundListCodec<?, ?> || candidate instanceof KeyDispatchCodec<?, ?> || candidate instanceof MapCodec.MapCodecCodec<?> || isMinecraftStructuralCodec(candidate)) {
                    return candidate;
                }
                pending.addAll(structuralChildren(candidate));
            }
            return null;
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
            if (node == null || depth > REFLECTION_DEPTH || !seen.add(node)) return null;
            if (node instanceof Registry<?> registry) return registry;
            if (!isInspectable(node)) return null;
            for (Field field : fieldsOf(node.getClass())) {
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
            if (node == null || depth > REFLECTION_DEPTH || !seen.add(node)) return null;
            if (node instanceof ResourceKey<?> key && isRegistryKey(key)) return castRegistryKey(key);
            if (!isInspectable(node)) return null;
            for (Field field : fieldsOf(node.getClass())) {
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
                if (!Modifier.isStatic(field.getModifiers())) {
                    findSerializedValues(readField(node, field), values, seen, depth + 1);
                }
            }
        }

        private ResourceKey<? extends Registry<?>> registryKeyFrom(Object codec) {
            Object key = readField(codec, REGISTRY_KEY);
            return key instanceof ResourceKey<?> resourceKey && isRegistryKey(resourceKey) ? castRegistryKey(resourceKey) : null;
        }

        private JsonElement holderSetSchema(ResourceKey<? extends Registry<?>> key) {
            JsonArray alternatives = new JsonArray();
            alternatives.add(registryReference(key, true));
            alternatives.add(array(registryReference(key, false), 1, null));
            alternatives.add(object(TYPE, "object", "description", "Registered custom holder set"));
            return object("anyOf", alternatives);
        }

        public JsonElement registryReference(ResourceKey<? extends Registry<?>> key, boolean includeTags) {
            String definition = "registry_" + sanitize(key.identifier().toString()) + (includeTags ? "_or_tag" : "");
            if (!definitions.has(definition)) {
                List<String> values = registryValues(key, includeTags);
                definitions.add(definition, values.isEmpty() ? type(STRING) : stringEnum(values));
            }
            return object($_REF, DEFINITIONS + definition);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private List<String> registryValues(ResourceKey<? extends Registry<?>> key, boolean includeTags) {
            Optional<? extends HolderLookup.RegistryLookup<Object>> lookup = (Optional) registries.lookup((ResourceKey) key);
            if (lookup.isEmpty()) return List.of();
            Set<String> values = new LinkedHashSet<>();
            lookup.get().listElementIds().map(ResourceKey::identifier).map(Identifier::toString).forEach(values::add);
            if (includeTags) lookup.get().listTagIds().map(tag -> "#" + tag.location()).forEach(values::add);
            return values.stream().sorted().toList();
        }

        private JsonObject mapSchema(JsonElement key, JsonElement value) {
            JsonObject schema = objectSchema();
            schema.add("propertyNames", key);
            schema.add(ADDITIONAL_PROPERTIES, value);
            return schema;
        }

        private JsonObject tuple(List<JsonElement> entries) {
            JsonObject schema = object(TYPE, "array", "minItems", entries.size(), "maxItems", entries.size());
            JsonArray items = new JsonArray();
            entries.forEach(items::add);
            schema.add("items", items);
            return schema;
        }

        private JsonObject discriminatorOnly(String field, String value) {
            JsonObject variant = objectSchema();
            addDiscriminator(variant, field, value);
            return variant;
        }

        private void addDiscriminator(JsonObject schema, String field, String value) {
            schema.addProperty(TYPE, "object");
            JsonObject properties = schema.has(PROPERTIES) && schema.get(PROPERTIES).isJsonObject() ? schema.getAsJsonObject(PROPERTIES) : new JsonObject();
            properties.add(field, object("const", value));
            schema.add(PROPERTIES, properties);
            Set<String> required = new LinkedHashSet<>();
            if (schema.has(REQUIRED) && schema.get(REQUIRED).isJsonArray()) {
                schema.getAsJsonArray(REQUIRED).forEach(element -> required.add(element.getAsString()));
            }
            required.add(field);
            schema.add(REQUIRED, strings(required));
        }

        private List<String> enumValues(JsonElement schema) {
            if (schema == null || !schema.isJsonObject()) return List.of();
            JsonObject object = schema.getAsJsonObject();
            if (object.has(ENUM) && object.get(ENUM).isJsonArray()) {
                return object.getAsJsonArray(ENUM).asList().stream().map(JsonElement::getAsString).toList();
            }
            if (object.has($_REF)) {
                String ref = object.get($_REF).getAsString();
                String prefix = DEFINITIONS;
                if (ref.startsWith(prefix) && definitions.has(ref.substring(prefix.length()))) {
                    return enumValues(definitions.get(ref.substring(prefix.length())));
                }
            }
            return List.of();
        }

        private static List<JsonElement> distinctSchemas(List<JsonElement> schemas) {
            Map<String, JsonElement> unique = new LinkedHashMap<>();
            schemas.forEach(schema -> unique.putIfAbsent(schema.toString(), schema));
            return List.copyOf(unique.values());
        }

        private static JsonObject alternatives(List<JsonElement> schemas) {
            List<JsonElement> unique = distinctSchemas(schemas);
            if (unique.size() == 1) return asObject(unique.getFirst());
            JsonArray alternatives = new JsonArray();
            unique.forEach(alternatives::add);
            return object("anyOf", alternatives);
        }

        private static JsonObject unknown(Object codec) {
            JsonObject schema = new JsonObject();
            if (codec != null) schema.addProperty("$comment", "Unresolved codec: " + codec.getClass().getName());
            return schema;
        }

        private static JsonObject type(String type) {
            return object(TYPE, type);
        }

        private static JsonObject objectSchema() {
            return type("object");
        }

        private static JsonObject stringEnum(Collection<String> values) {
            JsonObject schema = type(STRING);
            schema.add(ENUM, strings(values));
            return schema;
        }

        private static JsonObject array(JsonElement items, Integer minimum, Integer maximum) {
            JsonObject schema = type("array");
            schema.add("items", items);
            if (minimum != null) schema.addProperty("minItems", minimum);
            if (maximum != null) schema.addProperty("maxItems", maximum);
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
            if (schema == null || !schema.isJsonObject()) return false;
            JsonObject object = schema.getAsJsonObject();
            if (object.has(TYPE) && STRING.equals(object.get(TYPE).getAsString())) return true;
            if (!object.has($_REF)) return false;
            String reference = object.get($_REF).getAsString();
            String prefix = DEFINITIONS;
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
                return null;
            }
        }

        private static List<Field> fieldsOf(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
                Collections.addAll(fields, current.getDeclaredFields());
            }
            return fields;
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
