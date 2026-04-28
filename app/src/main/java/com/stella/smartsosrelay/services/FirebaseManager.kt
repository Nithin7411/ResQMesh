package com.stella.smartsosrelay.services

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.stella.smartsosrelay.data.ContactEntity
import com.stella.smartsosrelay.data.SosEventEntity
import com.stella.smartsosrelay.data.UserEntity
import kotlinx.coroutines.tasks.await

object FirebaseManager {
    private val db = FirebaseFirestore.getInstance()

    // ── Device Registry ──────────────────────────────────────────────────────

    /**
     * Register this device's identity hash → user mapping in Firestore.
     * This allows the server to look up who owns a deviceIdHash when
     * receiving relayed SOS events from the BLE mesh.
     *
     * Document key = deviceHex (e.g. "A3F9C012")
     */
    suspend fun registerDevice(
        userName: String,
        phoneNumber: String,
        firestoreUserId: String = "",
        deviceUuid: String = DeviceIdentityManager.deviceUuid,
        deviceHex: String = DeviceIdentityManager.deviceHex
    ): Boolean {
        return try {
            val deviceMap = hashMapOf<String, Any>(
                "deviceHex"    to deviceHex,
                "deviceUuid"   to deviceUuid,
                "userName"     to userName,
                "phoneNumber"  to phoneNumber,
                "registeredAt" to System.currentTimeMillis(),
                "lastSeen"     to System.currentTimeMillis()
            )

            // Include firestoreUserId for server-side user lookup
            if (firestoreUserId.isNotBlank()) {
                deviceMap["firestoreUserId"] = firestoreUserId
            }

            // Include Google account if linked
            DeviceIdentityManager.googleAccountId?.let {
                deviceMap["googleAccountId"] = it
            }

            db.collection("device_registry")
                .document(deviceHex)
                .set(deviceMap, SetOptions.merge())
                .await()

            Log.d("FirebaseManager", "Device registered: $deviceHex → $userName ($phoneNumber) [userId=$firestoreUserId]")
            true
        } catch (e: Exception) {
            Log.e("FirebaseManager", "Failed to register device", e)
            false
        }
    }

    // ── User Registration ────────────────────────────────────────────────────

    /**
     * Upsert (insert-or-update) a user document in Firestore.
     *
     * Document key strategy:
     * - If user has a Google account ID → use googleAccountId as doc key
     *   (consistent across devices for the same Google user)
     * - Otherwise → use phoneNumber as doc key (legacy path)
     *
     * Returns the Firestore document ID (firestoreUserId) on success, null on failure.
     * This ID is the canonical user identifier across all SOS events and relay chains.
     */
    suspend fun upsertUser(user: UserEntity, contacts: List<ContactEntity>): String? {
        return try {
            val contactsList = contacts.map {
                mapOf("name" to it.name, "phone" to it.phoneNumber)
            }

            // Determine the document key — prefer Google ID, fall back to phone
            val docKey = when {
                !user.googleAccountId.isNullOrBlank() -> user.googleAccountId
                user.phoneNumber.isNotBlank() -> user.phoneNumber
                else -> DeviceIdentityManager.deviceUuid // last resort
            }

            // These fields are always written (merge keeps registeredAt on updates)
            val userMap = hashMapOf<String, Any>(
                "userId"          to docKey,
                "name"            to user.name,
                "phoneNumber"     to user.phoneNumber,
                "trustedContacts" to contactsList,
                "deviceUuid"      to user.deviceUuid,
                "deviceHex"       to DeviceIdentityManager.deviceHex,
                "updatedAt"       to System.currentTimeMillis()
            )

            // Include Google account ID if linked
            user.googleAccountId?.let {
                userMap["googleAccountId"] = it
            }

            // Include existing firestoreUserId if already assigned
            if (user.firestoreUserId.isNotBlank()) {
                userMap["firestoreUserId"] = user.firestoreUserId
            }

            val docRef = db.collection("users").document(docKey)

            // Check if first-time registration — set registeredAt + generate firestoreUserId
            val snapshot = docRef.get().await()
            val firestoreUserId: String
            if (!snapshot.exists()) {
                // Generate a new unique firestoreUserId
                firestoreUserId = if (user.firestoreUserId.isNotBlank()) {
                    user.firestoreUserId
                } else {
                    // Use first 12 chars of a Firestore auto-ID for a short, unique user ID
                    db.collection("users").document().id.take(12).uppercase()
                }
                userMap["registeredAt"] = System.currentTimeMillis()
                userMap["firestoreUserId"] = firestoreUserId
                Log.d("FirebaseManager", "New user registration: $docKey → firestoreUserId=$firestoreUserId")
            } else {
                // Retrieve existing firestoreUserId from server
                firestoreUserId = snapshot.getString("firestoreUserId")
                    ?: if (user.firestoreUserId.isNotBlank()) user.firestoreUserId
                    else db.collection("users").document().id.take(12).uppercase()
                userMap["firestoreUserId"] = firestoreUserId
                Log.d("FirebaseManager", "Updating existing user: $docKey (firestoreUserId=$firestoreUserId)")
            }

            // SetOptions.merge() = only update supplied fields, leave the rest intact
            docRef.set(userMap, SetOptions.merge()).await()

            Log.d("FirebaseManager", "User upserted OK: $docKey (firestoreUserId=$firestoreUserId)")
            firestoreUserId
        } catch (e: Exception) {
            Log.e("FirebaseManager", "Failed to upsert user", e)
            null
        }
    }

    // ── SOS Events ───────────────────────────────────────────────────────────

    /**
     * Upload an SOS event from this device to the global sos_events collection.
     *
     * Uses eventIdHash hex as the Firestore document ID for natural deduplication —
     * multiple uploads of the same event (from different relays or retries) will
     * merge into a single document.
     */
    suspend fun uploadSosEvent(
        event: SosEventEntity,
        userName: String = "",
        phoneNumber: String = "",
        firestoreUserId: String = ""
    ): Boolean {
        return try {
            // Use eventIdHash hex as document ID for natural dedup
            val docId = if (event.eventIdHash != 0) {
                String.format("%08X", event.eventIdHash)
            } else {
                event.eventId  // fallback for legacy events
            }

            val eventMap = hashMapOf<String, Any>(
                "eventId"       to event.eventId,
                "eventIdHash"   to event.eventIdHash,
                "userId"        to event.userId,
                "deviceUuid"    to DeviceIdentityManager.deviceUuid,
                "deviceHex"     to DeviceIdentityManager.deviceHex,
                "timestamp"     to event.timestamp,
                "latitude"      to event.latitude,
                "longitude"     to event.longitude,
                "triggerReason" to event.triggerReason,
                "status"        to "DELIVERED_FIREBASE",
                "hopCount"      to event.relayCount,
                "gpsLink"       to "https://maps.google.com/maps?q=${event.latitude},${event.longitude}"
            )

            // Include user identity if available
            if (userName.isNotBlank()) eventMap["userName"] = userName
            if (phoneNumber.isNotBlank()) eventMap["userPhone"] = phoneNumber

            // Include firestoreUserId for server-side user lookup
            if (firestoreUserId.isNotBlank()) {
                eventMap["firestoreUserId"] = firestoreUserId
            }

            // Write to global sos_events (merge guards against relay overwrites)
            db.collection("sos_events")
                .document(docId)
                .set(eventMap, SetOptions.merge())
                .await()

            // Also append event reference to the user's own document
            try {
                // Try firestoreUserId first, then phoneNumber
                val userDocKey = when {
                    firestoreUserId.isNotBlank() -> {
                        // Find the user doc by firestoreUserId field
                        val query = db.collection("users")
                            .whereEqualTo("firestoreUserId", firestoreUserId)
                            .limit(1)
                            .get()
                            .await()
                        query.documents.firstOrNull()?.id
                    }
                    phoneNumber.isNotBlank() -> phoneNumber
                    else -> null
                }

                if (userDocKey != null) {
                    db.collection("users")
                        .document(userDocKey)
                        .update("myEvents", FieldValue.arrayUnion(eventMap))
                        .await()
                }
            } catch (e: Exception) {
                // Non-critical: user doc may not exist yet for relay events
                Log.w("FirebaseManager", "Could not append to user events: ${e.message}")
            }

            Log.d("FirebaseManager", "SOS event uploaded: ${event.eventId} (doc=$docId, userId=$firestoreUserId)")
            true
        } catch (e: Exception) {
            Log.e("FirebaseManager", "Failed to upload SOS event", e)
            false
        }
    }

    /**
     * Upload a relayed SOS event received via BLE from another device.
     *
     * Uses merge so a relay upload never overwrites a direct upload already
     * marked DELIVERED_FIREBASE. Appends this relay node to the relayChain array.
     */
    suspend fun uploadRelayedSos(
        event: SosEventEntity,
        relayDeviceUuid: String,
        relayDeviceHex: String,
        relayFirestoreUserId: String = ""
    ): Boolean {
        return try {
            // Use eventIdHash hex as document ID for natural dedup
            val docId = if (event.eventIdHash != 0) {
                String.format("%08X", event.eventIdHash)
            } else {
                event.eventId
            }

            val eventMap = hashMapOf<String, Any>(
                "eventId"       to event.eventId,
                "eventIdHash"   to event.eventIdHash,
                "userId"        to event.userId,
                "timestamp"     to event.timestamp,
                "latitude"      to event.latitude,
                "longitude"     to event.longitude,
                "triggerReason" to event.triggerReason,
                "status"        to "RELAYED_TO_FIREBASE",
                "hopCount"      to event.relayCount,
                "gpsLink"       to "https://maps.google.com/maps?q=${event.latitude},${event.longitude}"
            )

            // Write/merge the event document
            db.collection("sos_events")
                .document(docId)
                .set(eventMap, SetOptions.merge())
                .await()

            // Append this relay node to the relay chain (array of relay nodes)
            val relayNode = hashMapOf<String, Any>(
                "relayDeviceUuid" to relayDeviceUuid,
                "relayDeviceHex"  to relayDeviceHex,
                "relayedAt"       to System.currentTimeMillis(),
                "hopAtRelay"      to event.relayCount
            )

            // Include relay device's firestoreUserId for traceability
            if (relayFirestoreUserId.isNotBlank()) {
                relayNode["relayFirestoreUserId"] = relayFirestoreUserId
            }

            db.collection("sos_events")
                .document(docId)
                .update("relayChain", FieldValue.arrayUnion(relayNode))
                .await()

            Log.d("FirebaseManager", "Relayed SOS uploaded: ${event.eventId} (doc=$docId, relayUserId=$relayFirestoreUserId)")
            true
        } catch (e: Exception) {
            Log.e("FirebaseManager", "Failed to upload relayed SOS", e)
            false
        }
    }

    /**
     * Check if an event already exists on the server — used for server-side dedup.
     */
    suspend fun checkEventExists(eventIdHash: Int): Boolean {
        return try {
            val docId = String.format("%08X", eventIdHash)
            val snapshot = db.collection("sos_events").document(docId).get().await()
            snapshot.exists()
        } catch (e: Exception) {
            Log.w("FirebaseManager", "Failed to check event existence", e)
            false // assume not exists to allow upload
        }
    }

    /**
     * Look up a user document by firestoreUserId.
     * Returns a map of user data, or null if not found.
     */
    suspend fun getUserByFirestoreId(firestoreUserId: String): Map<String, Any>? {
        return try {
            val query = db.collection("users")
                .whereEqualTo("firestoreUserId", firestoreUserId)
                .limit(1)
                .get()
                .await()
            query.documents.firstOrNull()?.data
        } catch (e: Exception) {
            Log.w("FirebaseManager", "Failed to look up user by firestoreUserId", e)
            null
        }
    }
}
