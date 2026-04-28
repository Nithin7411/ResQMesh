package com.stella.smartsosrelay.data

import kotlinx.coroutines.flow.Flow

class SosRepository(private val sosDao: SosDao) {

    val user: Flow<UserEntity?> = sosDao.getUser()
    val contacts: Flow<List<ContactEntity>> = sosDao.getContacts()
    val allEvents: Flow<List<SosEventEntity>> = sosDao.getAllSosEvents()
    val unsyncedEvents: Flow<List<SosEventEntity>> = sosDao.getUnsyncedEvents()

    /**
     * Insert or update a user record.
     * Room's REPLACE strategy handles both cases — existing row is overwritten
     * in-place (same primary key), new row is inserted if no match.
     */
    suspend fun upsertUser(user: UserEntity) {
        sosDao.insertUser(user) // OnConflictStrategy.REPLACE handles update
    }

    /**
     * Look up an existing user by phone number before inserting,
     * so we avoid creating duplicate local records on re-registration.
     */
    suspend fun getUserByPhone(phone: String): UserEntity? {
        return sosDao.getUserByPhone(phone)
    }

    /**
     * Look up an existing user by Google account ID — used for dedup
     * when registering via Google Sign-In.
     */
    suspend fun getUserByGoogleId(googleId: String): UserEntity? {
        return sosDao.getUserByGoogleId(googleId)
    }

    /**
     * Look up a user by their Firestore document ID.
     */
    suspend fun getUserByFirestoreId(firestoreId: String): UserEntity? {
        return sosDao.getUserByFirestoreId(firestoreId)
    }

    /**
     * Update the firestoreUserId after successful server registration.
     */
    suspend fun updateFirestoreUserId(localId: Int, firestoreId: String) {
        sosDao.updateFirestoreUserId(localId, firestoreId)
    }

    suspend fun insertContact(contact: ContactEntity) {
        sosDao.insertContact(contact)
    }

    suspend fun deleteContact(id: Int) {
        sosDao.deleteContact(id)
    }

    suspend fun insertSosEvent(event: SosEventEntity) {
        sosDao.insertSosEvent(event)
    }

    suspend fun updateSosEventStatus(eventId: String, status: String) {
        sosDao.updateSosEventStatus(eventId, status)
    }

    // ── New methods for production architecture ─────────────────────────

    /** Check if an event with this hash already exists locally (BLE dedup). */
    suspend fun getEventByHash(hash: Int): SosEventEntity? {
        return sosDao.getEventByHash(hash)
    }

    /** Update the server sync status of an event. */
    suspend fun updateSyncStatus(eventId: String, status: String) {
        sosDao.updateSyncStatus(eventId, status)
    }
}
