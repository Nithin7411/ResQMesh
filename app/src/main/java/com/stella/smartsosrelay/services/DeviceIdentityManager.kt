package com.stella.smartsosrelay.services

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * DeviceIdentityManager — Provides a stable, deterministic device identity
 * across app launches and devices.
 *
 * The core problem: Android's `ANDROID_ID.hashCode()` produces different values
 * on different devices/JVM versions, causing eventId mismatches in the relay chain.
 *
 * Solution:
 *  1. Generate a UUID once on first launch → persist in SharedPreferences
 *  2. Derive a deterministic 4-byte hash via SHA-256 truncation
 *  3. Optionally link to a Google account ID for cross-device consistency
 *
 * All hashing uses SHA-256 (first 4 bytes) for determinism — never `hashCode()`.
 */
object DeviceIdentityManager {

    private const val TAG = "DeviceIdentityManager"
    private const val PREFS_NAME = "stella_device_identity"
    private const val KEY_DEVICE_UUID = "device_uuid"
    private const val KEY_GOOGLE_ACCOUNT_ID = "google_account_id"

    /** Full UUID string — used for server sync and as the canonical device identifier. */
    @Volatile
    var deviceUuid: String = ""
        private set

    /**
     * Deterministic 4-byte hash of [deviceUuid] via SHA-256 truncation.
     * Used as the compact device identifier in BLE payloads.
     */
    @Volatile
    var deviceIdHash: Int = 0
        private set

    /** Hex representation of [deviceIdHash] for display / logging — e.g. "A3F9C012" */
    val deviceHex: String
        get() = String.format("%08X", deviceIdHash)

    /** Optional linked Google account ID. Null when in anonymous mode. */
    @Volatile
    var googleAccountId: String? = null
        private set

    /**
     * Initialize the identity manager. Must be called once from
     * [com.stella.smartsosrelay.SmartSosApplication.onCreate].
     *
     * - First launch: generates a new UUID and persists it.
     * - Subsequent launches: loads the existing UUID.
     */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Load or generate UUID
        var uuid = prefs.getString(KEY_DEVICE_UUID, null)
        if (uuid.isNullOrBlank()) {
            uuid = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_UUID, uuid).apply()
            Log.i(TAG, "Generated new device UUID: $uuid")
        } else {
            Log.d(TAG, "Loaded existing device UUID: $uuid")
        }

        deviceUuid = uuid
        deviceIdHash = sha256TruncatedInt(uuid)

        // Load optional Google link
        googleAccountId = prefs.getString(KEY_GOOGLE_ACCOUNT_ID, null)

        Log.i(TAG, "Device identity ready — UUID=$uuid, hash=$deviceHex, google=$googleAccountId")
    }

    /**
     * Link this device to a Google account. The Google account ID provides
     * cross-device identity consistency and server-side user mapping.
     */
    fun linkGoogleAccount(context: Context, googleId: String) {
        googleAccountId = googleId
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GOOGLE_ACCOUNT_ID, googleId)
            .apply()
        Log.i(TAG, "Linked Google account: $googleId")
    }

    /**
     * Unlink the Google account (revert to anonymous BLE mode).
     */
    fun unlinkGoogleAccount(context: Context) {
        googleAccountId = null
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_GOOGLE_ACCOUNT_ID)
            .apply()
        Log.i(TAG, "Google account unlinked")
    }

    /**
     * Generate a deterministic event ID hash from deviceUuid + timestamp.
     *
     * This hash is embedded in the BLE payload and NEVER changes across relay hops.
     * Every device in the relay chain will see the same eventIdHash for the same event.
     *
     * @param timestampMs The event timestamp in milliseconds (System.currentTimeMillis())
     * @return A deterministic 4-byte Int hash
     */
    fun generateEventHash(timestampMs: Long): Int {
        val input = "$deviceUuid:$timestampMs"
        return sha256TruncatedInt(input)
    }

    /**
     * Compute SHA-256 of [input] and return the first 4 bytes as a big-endian Int.
     *
     * This is deterministic across all platforms, JVM versions, and devices —
     * unlike Kotlin's [String.hashCode] which is implementation-dependent.
     */
    private fun sha256TruncatedInt(input: String): Int {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(hash, 0, 4).int
    }
}
