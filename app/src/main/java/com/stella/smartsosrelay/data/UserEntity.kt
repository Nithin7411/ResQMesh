package com.stella.smartsosrelay.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_table")
data class UserEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    val phoneNumber: String,
    /** Local BLE fingerprint — used as a BLE advertisement device identifier. */
    val temporaryUserId: String,
    /**
     * True once a successful Firestore upsert has confirmed this profile exists
     * on the server. False if offline registration or upload failed.
     */
    val isRegistered: Boolean = false,
    /** Stable device UUID from DeviceIdentityManager — persists across app installs. */
    val deviceUuid: String = "",
    /** Optional Google account ID — null when in anonymous mode. */
    val googleAccountId: String? = null,
    /**
     * Firestore-generated user document ID. Used as the canonical user identifier
     * across all SOS events, relay chains, and server-side lookups.
     * Empty string until first successful Firestore registration.
     */
    val firestoreUserId: String = ""
)
