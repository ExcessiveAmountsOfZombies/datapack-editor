package com.epherical.epherolib.api.schema;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Addon-provided configuration for one datapack resource schema. A registration
 * can supply a codec, enrich an automatically discovered Minecraft schema, or do
 * both. All schema configuration is deferred until the server's live registries
 * are available. Register the built value with {@link DataPackSchemaBuilder#register(DataPackSchemaRegistration)}
 * during common mod initialization. Registrations for the same directory compose in order:
 * later non-null root metadata and codecs win, while schema configuration is retained.
 */
public class DataPackSchemaRegistration {
    private final String directory;
    private final Codec<?> codec;
    private final String title;
    private final String description;
    private final List<Consumer<CodecJsonSchema.Builder>> schemaConfiguration;

    public DataPackSchemaRegistration(Builder builder) {
        directory = builder.directory;
        codec = builder.codec;
        title = builder.title;
        description = builder.description;
        schemaConfiguration = List.copyOf(builder.schemaConfiguration);
    }

    /**
     * Starts a registration for JSON files below {@code data/<namespace>/<directory>/}.
     * The directory may contain slashes, for example {@code example/widget}.
     *
     * @param directory resource directory relative to the datapack namespace
     * @param codec codec that decodes the resource
     * @return a new registration builder
     */
    public static Builder builder(String directory, Codec<?> codec) {
        return new Builder(directory, Objects.requireNonNull(codec, "codec"));
    }

    /**
     * Enriches an automatically discovered or previously registered resource type.
     * Use this form when its existing codec should be kept.
     *
     * @param directory resource directory relative to the datapack namespace
     * @return a new registration builder
     */
    public static Builder builder(String directory) {
        return new Builder(directory, null);
    }

    /**
     * Returns the datapack directory relative to {@code data/<namespace>/}.
     * @return the registered directory
     */
    public String directory() {
        return directory;
    }

    /**
     * Returns the codec supplied by this registration, if any.
     * @return the codec, or {@code null} when enriching an existing resource type
     */
    public Codec<?> codec() {
        return codec;
    }

    /**
     * Returns the root title override, if any.
     * @return the title, or {@code null} to keep the generated title
     */
    public String title() {
        return title;
    }

    /**
     * Returns the root description override, if any.
     * @return the description, or {@code null} to keep the generated description
     */
    public String description() {
        return description;
    }

    /**
     * Applies this registration's codec configuration to one schema build.
     * @param builder generator configuration for the current build
     */
    public void configure(CodecJsonSchema.Builder builder) {
        schemaConfiguration.forEach(configuration -> configuration.accept(builder));
    }

    public static class Builder {
        private final String directory;
        private final Codec<?> codec;
        private String title;
        private String description;
        private final List<Consumer<CodecJsonSchema.Builder>> schemaConfiguration = new ArrayList<>();

        public Builder(String directory, Codec<?> codec) {
            this.directory = Objects.requireNonNull(directory, "directory");
            this.codec = codec;
        }

        /**
         * Sets the title shown for files in this resource directory.
         * @param title root schema title
         * @return this builder
         */
        public Builder title(String title) {
            this.title = Objects.requireNonNull(title, "title");
            return this;
        }

        /**
         * Sets the root schema description shown for these files.
         * @param description root schema description
         * @return this builder
         */
        public Builder description(String description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        /**
         * Adds a plain-text tooltip to every property matching a slash-separated path.
         * Paths follow references, composition nodes, and array items; escape {@code /}
         * within a property name as {@code ~1} and {@code ~} as {@code ~0}.
         *
         * @param propertyPath slash-separated property names
         * @param description plain-text tooltip
         * @return this builder
         */
        public Builder describe(String propertyPath, String description) {
            return annotate(propertyPath, JsonSchemaAnnotation.builder().description(description).build());
        }

        /**
         * Adds titles, descriptions, examples, defaults, or other keywords at a property path.
         * @param propertyPath slash-separated property names
         * @param annotation metadata to add
         * @return this builder
         */
        public Builder annotate(String propertyPath, JsonSchemaAnnotation annotation) {
            schemaConfiguration.add(builder -> builder.annotate(propertyPath, annotation));
            return this;
        }

        /**
         * Adds a final in-place enhancement after codec inference and root annotations.
         * @param enhancer callback receiving the complete root schema
         * @return this builder
         */
        public Builder enhance(CodecJsonSchema.SchemaEnhancer enhancer) {
            schemaConfiguration.add(builder -> builder.enhance(enhancer));
            return this;
        }

        /**
         * Gives an addon direct access to codec schema configuration scoped to this resource.
         * @param configuration callback applied when the editor builds the schema
         * @return this builder
         */
        public Builder configure(Consumer<CodecJsonSchema.Builder> configuration) {
            schemaConfiguration.add(Objects.requireNonNull(configuration, "configuration"));
            return this;
        }

        /**
         * Supplies an exact schema for a nested codec in this resource type only.
         * @param codec nested codec instance
         * @param schema exact schema to use
         * @return this builder
         */
        public Builder schema(Codec<?> codec, JsonElement schema) {
            return configure(builder -> builder.register(codec, schema));
        }

        /**
         * Supplies an exact schema for a nested map codec in this resource type only.
         * @param codec nested map codec instance
         * @param schema exact schema to use
         * @return this builder
         */
        public Builder schema(MapCodec<?> codec, JsonElement schema) {
            return configure(builder -> builder.register(codec, schema));
        }

        /**
         * Enriches or replaces a nested codec schema when the editor requests it.
         * @param codec nested codec instance
         * @param adapter callback receiving the inferred schema
         * @return this builder
         */
        public Builder adapt(Codec<?> codec, CodecJsonSchema.SchemaAdapter adapter) {
            return configure(builder -> builder.registerAdapter(codec, adapter));
        }

        /**
         * Enriches or replaces a nested map codec schema when the editor requests it.
         * @param codec nested map codec instance
         * @param adapter callback receiving the inferred schema
         * @return this builder
         */
        public Builder adapt(MapCodec<?> codec, CodecJsonSchema.SchemaAdapter adapter) {
            return configure(builder -> builder.registerAdapter(codec, adapter));
        }

        /**
         * Provides a complete schema for an opaque or recursive nested codec.
         * @param codec nested codec instance
         * @param provider callback that creates the schema
         * @return this builder
         */
        public Builder provide(Codec<?> codec, CodecJsonSchema.SchemaProvider provider) {
            return configure(builder -> builder.registerProvider(codec, provider));
        }

        /**
         * Provides a complete schema for an opaque or recursive nested map codec.
         * @param codec nested map codec instance
         * @param provider callback that creates the schema
         * @return this builder
         */
        public Builder provide(MapCodec<?> codec, CodecJsonSchema.SchemaProvider provider) {
            return configure(builder -> builder.registerProvider(codec, provider));
        }

        /**
         * Suggests identifiers from a live registry for a nested codec.
         * When {@code includeTags} is true, tag identifiers prefixed with {@code #} are included.
         *
         * @param codec nested codec instance
         * @param registry registry to read when the schema is requested
         * @param includeTags whether tag identifiers should be suggested
         * @return this builder
         */
        public Builder registryValues(Codec<?> codec,
                                      ResourceKey<? extends Registry<?>> registry,
                                      boolean includeTags) {
            return configure(builder -> builder.registerRegistry(codec, registry, includeTags));
        }

        /**
         * Suggests identifiers from a live registry for a nested map codec.
         * When {@code includeTags} is true, tag identifiers prefixed with {@code #} are included.
         *
         * @param codec nested map codec instance
         * @param registry registry to read when the schema is requested
         * @param includeTags whether tag identifiers should be suggested
         * @return this builder
         */
        public Builder registryValues(MapCodec<?> codec,
                                      ResourceKey<? extends Registry<?>> registry,
                                      boolean includeTags) {
            return configure(builder -> builder.registerRegistry(codec, registry, includeTags));
        }

        /**
         * Returns the immutable registration to pass to {@link DataPackSchemaBuilder#register(DataPackSchemaRegistration)}.
         * @return the configured registration
         */
        public DataPackSchemaRegistration build() {
            return new DataPackSchemaRegistration(this);
        }
    }
}
