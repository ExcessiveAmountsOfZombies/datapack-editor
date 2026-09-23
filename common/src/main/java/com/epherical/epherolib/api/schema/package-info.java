/**
 * Public schema integration API for mods using the EpheroLib datapack editor.
 * Use {@link DataPackSchemaRegistration}
 * and {@link DataPackSchemaBuilder} to associate
 * a codec with a datapack resource directory or describe fields in one resource.
 * Use {@link CodecJsonSchema} to register
 * information that applies to a codec everywhere it appears.
 * {@link JsonSchemaAnnotation} supplies property
 * metadata at either scope. Registration affects editor assistance and does not
 * change Minecraft serialization or validation.
 *
 * @see DataPackSchemaBuilder
 * @see CodecJsonSchema
 */
package com.epherical.epherolib.api.schema;
