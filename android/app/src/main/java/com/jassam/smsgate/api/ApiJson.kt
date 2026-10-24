package com.jassam.smsgate.api

import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * The one JSON configuration for the device API: tolerant of new server fields,
 * omits nulls (the backend rejects unknown fields, so optional ones are left out
 * rather than sent as null), and encodes defaults so required fields always go out.
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * RFC 3339 instants as Go's `time.Time` renders them (`2026-10-24T10:00:00.123456Z`,
 * fractional seconds optional, `Z` or a numeric offset). kotlin.time.Instant is pure
 * Kotlin, so this works on API 24 without java.time desugaring.
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.jassam.smsgate.api.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant {
        val s = decoder.decodeString()
        return try {
            Instant.parse(s)
        } catch (e: IllegalArgumentException) {
            throw SerializationException("invalid RFC 3339 instant: $s", e)
        }
    }
}
