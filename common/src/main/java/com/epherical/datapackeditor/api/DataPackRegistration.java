package com.epherical.datapackeditor.api;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.epherical.datapackeditor.api.schema.CodecJsonSchema;
import com.epherical.datapackeditor.api.schema.JsonSchemaAnnotation;
import com.epherical.datapackeditor.impl.DataPackRegistrationImpl;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Registers a datapack directory, registry path or named JSON file for the editor,
 * optionally with its schema in the same registration. Call {@link Builder#register()}
 * during mod initialization. This exposes existing resources and editor assistance;
 * Minecraft and the loader still own registry creation, decoding and validation.
 *
 * <pre>{@code
 * DataPackRegistration.directory("example/widget").schema(Widget.CODEC).register();
 * DataPackRegistration.file(ResourceLocation.parse("example:settings.json")).register();
 * }</pre>
 */
public class DataPackRegistration {
    private final String directory;
    private final ResourceLocation file;
    private final Codec<?> codec;
    private final boolean hasSchema;
    private final String title;
    private final String description;
    private final List<Consumer<CodecJsonSchema.Builder>> schemaConfiguration;
    private final List<String> fileMatches;

    public DataPackRegistration(Builder builder) {
        directory = builder.directory;
        file = builder.file;
        codec = builder.codec;
        hasSchema = builder.hasSchema;
        title = builder.title;
        description = builder.description;
        schemaConfiguration = List.copyOf(builder.schemaConfiguration);
        fileMatches = List.copyOf(builder.fileMatches);
    }

    /** Starts discovery below {@code data/<namespace>/<directory>/}, without adding a schema. */
    public static Builder directory(String directory) {
        return new Builder(directory);
    }

    /** Starts discovery of one named file across all contributing resource packs. */
    public static Builder file(ResourceLocation file) {
        return new Builder(file);
    }

    /** Uses Minecraft's resource-directory convention for an already registered registry. */
    public static Builder registry(ResourceKey<? extends Registry<?>> registry) {
        return directory(Registries.elementsDirPath(Objects.requireNonNull(registry, DatapackEditorConstants.JSON_REGISTRY)));
    }

    /** Publishes one immutable resource registration and its optional schema together. */
    public static void register(DataPackRegistration registration) {
        DataPackRegistrationImpl.register(registration);
    }

    /** Stable schema/discovery identity: the directory, or a named file's full identifier. */
    public String id() {
        return file == null ? directory : file.toString();
    }

    /** Directory relative to {@code data/<namespace>/}, or null for a named file. */
    public String directory() {
        return directory;
    }

    /** Named JSON file, or null for a directory. */
    public ResourceLocation file() {
        return file;
    }

    /** Whether this registration supplies or enriches a schema. */
    public boolean hasSchema() {
        return hasSchema;
    }

    /** Explicit Monaco file patterns, or an empty list for the directory defaults. */
    public List<String> fileMatches() {
        return fileMatches;
    }

    /** Root codec, or null when preserving an existing schema or only discovering resources. */
    public Codec<?> codec() {
        return codec;
    }

    /** Root title override, or null to keep generated metadata. */
    public String title() {
        return title;
    }

    /** Root description override, or null to keep generated metadata. */
    public String description() {
        return description;
    }

    /** Applies configuration only when this registration's schema is built. */
    public void configure(CodecJsonSchema.Builder builder) {
        schemaConfiguration.forEach(configuration -> configuration.accept(builder));
    }

    private static String validatePath(String path) {
        String normalized = Objects.requireNonNull(path, DatapackEditorConstants.JSON_PATH)
                .replace(DatapackEditorConstants.PATH_WINDOWS_SEPARATOR, DatapackEditorConstants.PATH_SEPARATOR);
        if (!normalized.matches("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*")
                || Stream.of(normalized.split(DatapackEditorConstants.PATH_SEPARATOR)).anyMatch(segment ->
                segment.equals(DatapackEditorConstants.PATH_CURRENT_DIRECTORY) || segment.equals(DatapackEditorConstants.PATH_PARENT_DIRECTORY))) {
            throw new IllegalArgumentException("Invalid datapack resource path: " + path);
        }
        return normalized;
    }

    public static class Builder {
        private final String directory;
        private final ResourceLocation file;
        private Codec<?> codec;
        private boolean hasSchema;
        private String title;
        private String description;
        private final List<Consumer<CodecJsonSchema.Builder>> schemaConfiguration = new ArrayList<>();
        private List<String> fileMatches = List.of();

        public Builder(String directory) {
            this.directory = validatePath(directory);
            file = null;
        }

        public Builder(ResourceLocation file) {
            this.file = Objects.requireNonNull(file, "file");
            directory = null;
            validatePath(file.getPath());
            if (!file.getPath().endsWith(DatapackEditorConstants.PATH_JSON_EXTENSION)) {
                throw new IllegalArgumentException("Expected a JSON datapack resource");
            }
            fileMatches = List.of("file:///**/" + DatapackEditorConstants.PATH_DATA_PREFIX
                    + file.getNamespace() + DatapackEditorConstants.PATH_SEPARATOR + file.getPath());
        }

        /** Adds a codec-backed schema to this resource registration. */
        public Builder schema(Codec<?> codec) {
            this.codec = Objects.requireNonNull(codec, DatapackEditorConstants.JSON_CODEC);
            return schema();
        }

        /** Enriches the schema already discovered by Minecraft or registered for this resource. */
        public Builder schema() {
            hasSchema = true;
            return this;
        }

        /**
         * Sets the title shown for files in this resource directory.
         * @param title root schema title
         * @return this builder
         */
        public Builder title(String title) {
            schema();
            this.title = Objects.requireNonNull(title, DatapackEditorConstants.JSON_TITLE);
            return this;
        }

        /**
         * Replaces a directory's default schema globs using Monaco's file URI glob syntax.
         * Named files already have exact matching; use a directory for custom patterns.
         */
        public Builder matchFiles(String... patterns) {
            if (file != null) {
                throw new IllegalArgumentException("Named files already have exact matching; use a directory for custom patterns");
            }
            fileMatches = List.of(patterns);
            if (fileMatches.isEmpty() || fileMatches.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("At least one nonblank file pattern is required");
            }
            return this;
        }

        /**
         * Sets the root schema description shown for these files.
         * @param description root schema description
         * @return this builder
         */
        public Builder description(String description) {
            schema();
            this.description = Objects.requireNonNull(description, DatapackEditorConstants.JSON_DESCRIPTION);
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
            return configure(builder -> builder.annotate(propertyPath, annotation));
        }

        /**
         * Adds a final in-place enhancement after codec inference and root annotations.
         * @param enhancer callback receiving the complete root schema
         * @return this builder
         */
        public Builder enhance(CodecJsonSchema.SchemaEnhancer enhancer) {
            return configure(builder -> builder.enhance(enhancer));
        }

        /**
         * Gives an addon direct access to codec schema configuration scoped to this resource.
         * @param configuration callback applied when the editor builds the schema
         * @return this builder
         */
        public Builder configure(Consumer<CodecJsonSchema.Builder> configuration) {
            schema();
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

        /** Publishes this resource and its optional schema as one immutable registration. */
        public void register() {
            DataPackRegistration.register(build());
        }

        /** Returns an immutable registration without publishing it, for loader resource discovery. */
        public DataPackRegistration build() {
            return new DataPackRegistration(this);
        }
    }
}
