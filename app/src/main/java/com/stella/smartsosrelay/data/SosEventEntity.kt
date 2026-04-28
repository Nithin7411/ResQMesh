package com.stella.smartsosrelay.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sos_event_table")
data class SosEventEntity(
    @PrimaryKey
    val eventId: String,
    val userId: String,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val triggerReason: String = "MANUAL", // MANUAL, SENSOR, VOLUME_GESTURE, RELAY
    val status: String, // PENDING, SENT_SMS, SENT_FIREBASE, PENDING_RELAY
    /** Deterministic 4-byte SHA-256 hash — identical across all relay hops. Used for BLE dedup. */
    val eventIdHash: Int = 0,
    /** Sync status with server: LOCAL, SYNCED, SYNC_FAILED */
    val syncStatus: String = "LOCAL",
    /** Number of relay hops when this event was received. 0 = originated locally. */
    val relayCount: Int = 0
)
