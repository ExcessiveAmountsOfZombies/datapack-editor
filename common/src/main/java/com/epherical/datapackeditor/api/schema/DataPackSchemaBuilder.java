package com.epherical.datapackeditor.api.schema;

import com.epherical.datapackeditor.impl.DataPackSchemaImpl;
import com.mojang.serialization.Codec;

import java.util.Objects;

/**
 * Registers datapack resource schemas for the embedded editor. Call a {@code register}
 * method during common mod initialization, before the editor requests schemas.
 * A directory such as {@code example/widget} matches JSON files under
 * {@code data/<namespace>/example/widget/}, including subdirectories.
 * Registrations for one directory compose in call order.
 * Schema registration changes editor assistance only; Minecraft's codec remains
 * the authority for decoding and validation.
 */
public class DataPackSchemaBuilder {

    /**
     * Overrides the displayed title and description of an automatically discovered datapack schema.
     * A {@code null} title or description keeps that schema's generated default.
     *
     * @param directory   datapack resource directory relative to {@code data/<namespace>/}
     * @param title       custom schema title, or {@code null} to keep the generated title
     * @param description custom schema description, or {@code null} to keep the generated description
     * @throws IllegalArgumentException if the directory is not one of Minecraft's discovered datapack types
     */
    public static void register(String directory, String title, String description) {
        DataPackSchemaRegistration.Builder registration = DataPackSchemaRegistration.builder(directory);
        if (title != null) registration.title(title);
        if (description != null) registration.description(description);
        register(registration.build());
    }

    /**
     * Registers or replaces a codec-backed datapack schema using generated title and description values.
     * Replacing an automatically discovered schema retains its existing generated metadata.
     *
     * @param directory datapack resource directory relative to {@code data/<namespace>/}
     * @param codec     codec that defines the resource JSON structure
     * @throws NullPointerException if {@code codec} is null
     */
    public static void register(String directory, Codec<?> codec) {
        register(directory, codec, null, null);
    }

    /**
     * Registers or replaces a codec-backed datapack schema with optional display metadata overrides.
     * A {@code null} title or description keeps the generated default.
     *
     * @param directory   datapack resource directory relative to {@code data/<namespace>/}
     * @param codec       codec that defines the resource JSON structure
     * @param title       custom schema title, or {@code null} to keep the generated title
     * @param description custom schema description, or {@code null} to keep the generated description
     * @throws NullPointerException if {@code codec} is null
     */
    public static void register(String directory, Codec<?> codec, String title, String description) {
        DataPackSchemaRegistration.Builder registration =
                DataPackSchemaRegistration.builder(directory, Objects.requireNonNull(codec, "codec"));
        if (title != null) registration.title(title);
        if (description != null) registration.description(description);
        register(registration.build());
    }

    /**
     * Registers an addon-defined schema with optional property documentation,
     * registry-backed values, codec adapters, and final schema enhancements.
     * A registration without a codec must target an automatically discovered or
     * previously registered resource directory.
     *
     * @param registration resource schema configuration
     * @throws IllegalArgumentException if the directory is invalid or no schema exists to enrich
     */
    public static void register(DataPackSchemaRegistration registration) {
        DataPackSchemaImpl.register(registration);
    }

}
