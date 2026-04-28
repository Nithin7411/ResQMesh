package com.stella.smartsosrelay.services

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BlePayloadCodec — Deterministic binary encoder/decoder for BLE advertisement payloads.
 *
 * Supports two frame versions:
 *
 * ─── v1 (legacy) — 17 bytes ───────────────────────────────────────────
 * ┌────────┬────────┬──────────────┬──────────────────┬──────────┐
 * │ Bytes  │ Field  │ Type         │ Range            │ Notes    │
 * ├────────┼────────┼──────────────┼──────────────────┼──────────┤
 * │  0–3   │ lat    │ Float32 BE   │ -90.0 … +90.0    │ ~5m acc  │
 * │  4–7   │ lng    │ Float32 BE   │ -180.0 … +180.0  │ ~5m acc  │
 * │  8–11  │ epoch  │ Int32 BE     │ Unix time /1000s │ year2038 │
 * │ 12–15  │ devHash│ Int32 BE     │ hashCode(ANDROID_ID) │      │
 * │  16    │ trigger│ UByte        │ 0=U,1=M,2=S,3=V  │          │
 * └────────┴────────┴──────────────┴──────────────────┴──────────┘
 *
 * ─── v2 (production) — 22 bytes ───────────────────────────────────────
 * ┌────────┬──────────┬──────────────┬──────────────────┬──────────┐
 * │ Bytes  │ Field    │ Type         │ Range            │ Notes    │
 * ├────────┼──────────┼──────────────┼──────────────────┼──────────┤
 * │  0     │ version  │ UByte        │ 0x02             │ v2 flag  │
 * │  1–4   │ eventId  │ Int32 BE     │ SHA-256 trunc    │ dedup key│
 * │  5–8   │ devHash  │ Int32 BE     │ SHA-256 trunc    │ identity │
 * │  9–12  │ lat      │ Float32 BE   │ -90.0 … +90.0   │ ~5m acc  │
 * │  13–16 │ lng      │ Float32 BE   │ -180.0 … +180.0 │ ~5m acc  │
 * │  17–20 │ epoch    │ Int32 BE     │ Unix time /1s    │ year2038 │
 * │  21    │ flags    │ UByte        │ [trig:4][hop:4]  │ packed   │
 * └────────┴──────────┴──────────────┴──────────────────┴──────────┘
 *
 * Flags byte (21):
 *   - Bits 7–4: trigger code (0=UNKNOWN, 1=MANUAL, 2=SENSOR, 3=VOLUME_GESTURE)
 *   - Bits 3–0: hop count (0–15)
 *
 * Encoding is fully deterministic and lossless for the fields stored.
 * Lat/lng precision is ~5 metres (Float32 gives 7 significant digits).
 */
object BlePayloadCodec {

    const val FRAME_SIZE_V1 = 17
    const val FRAME_SIZE_V2 = 22
    const val VERSION_2: Byte = 0x02
    const val MAX_HOP_COUNT = 3

    /** Legacy alias for backward compatibility */
    const val FRAME_SIZE = FRAME_SIZE_V1

    private val triggerToCode: Map<String, Int> = mapOf(
        "MANUAL"         to 1,
        "SENSOR"         to 2,
        "VOLUME_GESTURE" to 3
    )

    private val codeToTrigger: Map<Int, String> = mapOf(
        0 to "UNKNOWN",
        1 to "MANUAL",
        2 to "SENSOR",
        3 to "VOLUME_GESTURE"
    )

    /**
     * Decoded BLE payload — mirrors all fields recoverable from the binary frame.
     * Works for both v1 and v2 frames.
     */
    data class BlePayload(
        val latitude: Double,
        val longitude: Double,
        /** Epoch in SECONDS (not ms). Multiply by 1000 for System.currentTimeMillis() style. */
        val epochSeconds: Long,
        /** SHA-256-truncated hash of the sender's device UUID (v2) or hashCode of ANDROID_ID (v1). */
        val deviceIdHash: Int,
        val triggerCode: Int,
        /** Deterministic event ID hash — identical across all relay hops. 0 for v1 packets. */
        val eventIdHash: Int = 0,
        /** Number of relay hops this packet has traveled. 0 = direct from sender. */
        val hopCount: Int = 0,
        /** Frame version: 1 for legacy, 2 for production. */
        val version: Int = 1
    ) {
        val triggerReason: String get() = codeToTrigger[triggerCode] ?: "UNKNOWN"
        /** Hex string for display / dedup key — e.g. "A3F9C012" */
        val deviceHex: String get() = String.format("%08X", deviceIdHash)
        /** Hex string of eventIdHash — e.g. "B7E2F4A1" */
        val eventHex: String get() = String.format("%08X", eventIdHash)
    }

    // ── V2 ENCODE / DECODE (production) ─────────────────────────────────

    /**
     * Encode SOS data into a 22-byte v2 binary frame.
     *
     * @param lat           GPS latitude
     * @param lng           GPS longitude
     * @param epochMillis   System.currentTimeMillis() — stored as seconds (÷1000)
     * @param trigger       Trigger reason string ("MANUAL", "SENSOR", "VOLUME_GESTURE")
     * @param deviceIdHash  SHA-256-truncated device identity hash
     * @param eventIdHash   SHA-256-truncated event identity hash
     * @param hopCount      Current hop count (0 = originating device)
     * @return Exactly 22 bytes, ready for BLE advertisement service data
     */
    fun encodeV2(
        lat: Double,
        lng: Double,
        epochMillis: Long,
        trigger: String,
        deviceIdHash: Int,
        eventIdHash: Int,
        hopCount: Int = 0
    ): ByteArray {
        val buf = ByteBuffer.allocate(FRAME_SIZE_V2).order(ByteOrder.BIG_ENDIAN)
        buf.put(VERSION_2)                                                // byte  0
        buf.putInt(eventIdHash)                                           // bytes 1–4
        buf.putInt(deviceIdHash)                                          // bytes 5–8
        buf.putFloat(lat.toFloat())                                       // bytes 9–12
        buf.putFloat(lng.toFloat())                                       // bytes 13–16
        buf.putInt((epochMillis / 1000L).toInt())                        // bytes 17–20

        // Pack trigger (high nibble) + hopCount (low nibble) into 1 byte
        val trigCode = triggerToCode.getOrDefault(trigger, 0) and 0x0F
        val hopClamped = hopCount.coerceIn(0, 15) and 0x0F
        val flags = ((trigCode shl 4) or hopClamped).toByte()
        buf.put(flags)                                                    // byte  21

        return buf.array()
    }

    /**
     * Encode a v2 frame for relay — increments hop count by 1.
     * All other fields are preserved exactly as received.
     */
    fun encodeV2ForRelay(payload: BlePayload): ByteArray {
        return encodeV2(
            lat = payload.latitude,
            lng = payload.longitude,
            epochMillis = payload.epochSeconds * 1000L,
            trigger = payload.triggerReason,
            deviceIdHash = payload.deviceIdHash,
            eventIdHash = payload.eventIdHash,
            hopCount = payload.hopCount + 1
        )
    }

    // ── V1 ENCODE / DECODE (legacy, preserved for backward compat) ──────

    /**
     * Encode SOS data into a 17-byte v1 binary frame (legacy format).
     */
    fun encode(
        lat: Double,
        lng: Double,
        epochMillis: Long,
        trigger: String,
        deviceId: String
    ): ByteArray {
        return encodeFromHash(lat, lng, epochMillis, trigger, deviceId.hashCode())
    }

    /**
     * Encode using a raw deviceIdHash — used when re-advertising a relayed SOS
     * so the original sender's identity is preserved in the frame.
     */
    fun encodeFromHash(
        lat: Double,
        lng: Double,
        epochMillis: Long,
        trigger: String,
        deviceIdHash: Int
    ): ByteArray {
        val buf = ByteBuffer.allocate(FRAME_SIZE_V1).order(ByteOrder.BIG_ENDIAN)
        buf.putFloat(lat.toFloat())                              // bytes 0–3
        buf.putFloat(lng.toFloat())                              // bytes 4–7
        buf.putInt((epochMillis / 1000L).toInt())               // bytes 8–11
        buf.putInt(deviceIdHash)                                 // bytes 12–15
        buf.put((triggerToCode.getOrDefault(trigger, 0) and 0xFF).toByte()) // byte  16
        return buf.array()
    }

    // ── UNIFIED DECODE ──────────────────────────────────────────────────

    /**
     * Decode a binary frame — auto-detects v1 vs v2 by inspecting byte 0.
     *
     * - If byte 0 == 0x02 and length >= 22 → decode as v2
     * - Otherwise → decode as v1 (legacy)
     *
     * Returns null if the byte array is too short or malformed.
     */
    fun decode(bytes: ByteArray): BlePayload? {
        if (bytes.isEmpty()) {
            Log.w("BlePayloadCodec", "Empty payload")
            return null
        }

        // Check for v2 header
        if (bytes[0] == VERSION_2 && bytes.size >= FRAME_SIZE_V2) {
            return decodeV2(bytes)
        }

        // Fall back to v1
        if (bytes.size >= FRAME_SIZE_V1) {
            return decodeV1(bytes)
        }

        Log.w("BlePayloadCodec", "Frame too short: ${bytes.size}")
        return null
    }

    private fun decodeV2(bytes: ByteArray): BlePayload? {
        return try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            buf.get() // skip version byte (0)

            val eventIdHash = buf.int                                     // bytes 1–4
            val deviceIdHash = buf.int                                    // bytes 5–8
            val lat = buf.float.toDouble()                                // bytes 9–12
            val lng = buf.float.toDouble()                                // bytes 13–16
            val epochSeconds = (buf.int.toLong()) and 0xFFFFFFFFL         // bytes 17–20
            val flags = buf.get().toInt() and 0xFF                        // byte  21

            val triggerCode = (flags shr 4) and 0x0F
            val hopCount = flags and 0x0F

            BlePayload(
                latitude = lat,
                longitude = lng,
                epochSeconds = epochSeconds,
                deviceIdHash = deviceIdHash,
                triggerCode = triggerCode,
                eventIdHash = eventIdHash,
                hopCount = hopCount,
                version = 2
            )
        } catch (e: Exception) {
            Log.e("BlePayloadCodec", "v2 decode failed", e)
            null
        }
    }

    private fun decodeV1(bytes: ByteArray): BlePayload? {
        return try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            BlePayload(
                latitude     = buf.float.toDouble(),
                longitude    = buf.float.toDouble(),
                epochSeconds = (buf.int.toLong()) and 0xFFFFFFFFL, // unsigned
                deviceIdHash = buf.int,
                triggerCode  = buf.get().toInt() and 0xFF,         // unsigned byte
                eventIdHash  = 0,  // v1 has no eventId in payload
                hopCount     = 0,
                version      = 1
            )
        } catch (e: Exception) {
            Log.e("BlePayloadCodec", "v1 decode failed", e)
            null
        }
    }
}
