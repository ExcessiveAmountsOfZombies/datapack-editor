/**
 * Public schema integration API for mods using datapack-editor.
 * Use {@link DataPackRegistration} to expose datapack resources and optionally
 * associate a codec or describe fields in the same registration.
 * Use {@link CodecJsonSchema} to register
 * information that applies to a codec everywhere it appears.
 * {@link JsonSchemaAnnotation} supplies property
 * metadata at either scope. Registration affects editor assistance and does not
 * change Minecraft serialization or validation.
 *
 * @see DataPackRegistration
 * @see CodecJsonSchema
 */
package com.epherical.datapackeditor.api.schema;

import com.epherical.datapackeditor.api.DataPackRegistration;
