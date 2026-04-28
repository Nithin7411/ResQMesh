package com.stella.smartsosrelay.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SosDao {

    // ── User ────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    /** Update an existing user row (used for re-registration / sync status). */
    @Update
    suspend fun updateUser(user: UserEntity)

    @Query("SELECT * FROM user_table LIMIT 1")
    fun getUser(): Flow<UserEntity?>

    /**
     * Look up a user by phone number — used before inserting to prevent
     * duplicate registrations (one phone = one user).
     */
    @Query("SELECT * FROM user_table WHERE phoneNumber = :phone LIMIT 1")
    suspend fun getUserByPhone(phone: String): UserEntity?

    /**
     * Look up a user by Google account ID — used for dedup when registering via Google.
     */
    @Query("SELECT * FROM user_table WHERE googleAccountId = :googleId LIMIT 1")
    suspend fun getUserByGoogleId(googleId: String): UserEntity?

    /**
     * Look up a user by their Firestore document ID — used for server-side user lookups.
     */
    @Query("SELECT * FROM user_table WHERE firestoreUserId = :firestoreId LIMIT 1")
    suspend fun getUserByFirestoreId(firestoreId: String): UserEntity?

    /**
     * Update the firestoreUserId after successful Firestore registration.
     */
    @Query("UPDATE user_table SET firestoreUserId = :firestoreId WHERE id = :localId")
    suspend fun updateFirestoreUserId(localId: Int, firestoreId: String)

    // ── Contacts ────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: ContactEntity)

    @Query("SELECT * FROM contact_table")
    fun getContacts(): Flow<List<ContactEntity>>

    @Query("DELETE FROM contact_table WHERE id = :id")
    suspend fun deleteContact(id: Int)

    // ── SOS Events ──────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSosEvent(event: SosEventEntity)

    @Query("UPDATE sos_event_table SET status = :status WHERE eventId = :eventId")
    suspend fun updateSosEventStatus(eventId: String, status: String)

    @Query("SELECT * FROM sos_event_table")
    fun getAllSosEvents(): Flow<List<SosEventEntity>>

    // ── New queries for production architecture ─────────────────────────────

    /** Look up an event by its deterministic hash — used for BLE dedup before inserting. */
    @Query("SELECT * FROM sos_event_table WHERE eventIdHash = :hash LIMIT 1")
    suspend fun getEventByHash(hash: Int): SosEventEntity?

    /** Get all events that haven't been synced to the server yet. */
    @Query("SELECT * FROM sos_event_table WHERE syncStatus = 'LOCAL'")
    fun getUnsyncedEvents(): Flow<List<SosEventEntity>>

    /** Update the server sync status of an event. */
    @Query("UPDATE sos_event_table SET syncStatus = :status WHERE eventId = :eventId")
    suspend fun updateSyncStatus(eventId: String, status: String)
}
