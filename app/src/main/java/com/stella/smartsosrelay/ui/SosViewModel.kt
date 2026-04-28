package com.stella.smartsosrelay.ui

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.stella.smartsosrelay.data.ContactEntity
import com.stella.smartsosrelay.data.SosEventEntity
import com.stella.smartsosrelay.data.SosRepository
import com.stella.smartsosrelay.data.UserEntity
import com.stella.smartsosrelay.services.DeviceIdentityManager
import com.stella.smartsosrelay.services.FirebaseManager
import com.stella.smartsosrelay.services.GoogleAuthManager
import com.stella.smartsosrelay.services.LocationHelper
import com.stella.smartsosrelay.services.NetworkHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SosViewModel(
    private val repository: SosRepository,
    private val application: Application
) : ViewModel() {

    val user: StateFlow<UserEntity?> = repository.user.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    val contacts: StateFlow<List<ContactEntity>> = repository.contacts.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val allEvents: StateFlow<List<SosEventEntity>> = repository.allEvents.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /** Google Sign-In state */
    val isGoogleSignedIn: StateFlow<Boolean> = GoogleAuthManager.isSignedIn
    val googleDisplayName: StateFlow<String?> = GoogleAuthManager.displayName
    val googleEmail: StateFlow<String?> = GoogleAuthManager.email
    val isGoogleConfigured: StateFlow<Boolean> = GoogleAuthManager.isConfigured

    /** Firestore userId — exposed for UI display */
    private val _firestoreUserId = MutableStateFlow("")
    val firestoreUserId: StateFlow<String> = _firestoreUserId

    init {
        // Load firestoreUserId from existing user on startup
        viewModelScope.launch {
            repository.user.collect { existingUser ->
                if (existingUser != null && existingUser.firestoreUserId.isNotBlank()) {
                    _firestoreUserId.value = existingUser.firestoreUserId
                }
            }
        }
    }

    /**
     * Save (or update) the user profile via phone number registration.
     *
     * Deduplication logic — one phone number = one user:
     *  1. Query local Room DB for an existing record with this phone number.
     *  2a. Found → copy the existing entity, updating only the name.
     *  2b. Not found → create a fresh UserEntity.
     *  3. Upsert to Room.
     *  4. If online → upsertUser to Firestore + register device in device_registry.
     *  5. On Firestore success → mark isRegistered = true in local DB + store firestoreUserId.
     */
    fun saveUser(name: String, phone: String) {
        if (name.isBlank() || phone.isBlank()) return

        viewModelScope.launch {
            // Step 1–2: check for existing user by phone
            val existing = repository.getUserByPhone(phone)
            val userEntity = existing?.copy(
                name = name,
                deviceUuid = DeviceIdentityManager.deviceUuid,
                googleAccountId = DeviceIdentityManager.googleAccountId
            ) ?: UserEntity(
                name            = name,
                phoneNumber     = phone,
                temporaryUserId = phone, // Use phone as the primary user ID
                isRegistered    = false,
                deviceUuid      = DeviceIdentityManager.deviceUuid,
                googleAccountId = DeviceIdentityManager.googleAccountId
            )

            // Step 3: persist locally
            repository.upsertUser(userEntity)

            // Step 4–5: sync to Firestore + register device
            if (NetworkHelper.isInternetAvailable(application)) {
                val currentContacts = contacts.value
                val firestoreId = FirebaseManager.upsertUser(userEntity, currentContacts)

                if (firestoreId != null) {
                    _firestoreUserId.value = firestoreId

                    // Register device hash → user mapping
                    FirebaseManager.registerDevice(
                        userName = name,
                        phoneNumber = phone,
                        firestoreUserId = firestoreId
                    )

                    repository.upsertUser(userEntity.copy(
                        isRegistered = true,
                        firestoreUserId = firestoreId
                    ))
                }
            }
        }
    }

    fun addContact(name: String, phone: String) {
        viewModelScope.launch {
            repository.insertContact(ContactEntity(name = name, phoneNumber = phone))

            // Re-sync the user doc to Firestore with the updated contacts list
            val currentUser = user.value
            if (currentUser != null && NetworkHelper.isInternetAvailable(application)) {
                FirebaseManager.upsertUser(currentUser, contacts.value)
            }
        }
    }

    fun removeContact(id: Int) {
        viewModelScope.launch {
            repository.deleteContact(id)
        }
    }

    /**
     * Trigger an SOS event with deterministic event ID hash.
     * Uses firestoreUserId as primary identifier for server-side user linkage.
     * Falls back to phone number → deviceUuid if firestoreUserId not available.
     */
    fun triggerSos(triggerReason: String = "MANUAL") {
        viewModelScope.launch {
            val currentUser = user.value
            // Priority: firestoreUserId → phoneNumber → deviceUuid
            val userId = when {
                currentUser?.firestoreUserId?.isNotBlank() == true -> currentUser.firestoreUserId
                currentUser?.phoneNumber?.isNotBlank() == true -> currentUser.phoneNumber
                else -> DeviceIdentityManager.deviceUuid
            }
            val userName = currentUser?.name ?: ""

            val gps = LocationHelper.getCurrentLocation(application)
            val timestampMs = System.currentTimeMillis()

            // Generate deterministic event hash
            val eventHash = DeviceIdentityManager.generateEventHash(timestampMs)

            val event = SosEventEntity(
                eventId       = "EVT_${String.format("%08X", eventHash)}",
                userId        = userId,
                timestamp     = timestampMs,
                latitude      = gps.latitude,
                longitude     = gps.longitude,
                triggerReason = triggerReason,
                status        = "PENDING",
                eventIdHash   = eventHash
            )
            repository.insertSosEvent(event)
        }
    }

    // ── Google Sign-In ─────────────────────────────────────────────────

    fun initGoogleAuth(webClientId: String? = null) {
        GoogleAuthManager.init(application, webClientId)
    }

    fun getGoogleSignInIntent(): Intent? {
        return GoogleAuthManager.getSignInIntent(application)
    }

    /**
     * Handle Google Sign-In result.
     * Creates/updates user profile using Google account data.
     * If no user exists yet, auto-creates one with Google name + email.
     * If user already exists (phone registration), links the Google account.
     */
    fun handleGoogleSignInResult(data: Intent?) {
        if (data == null) return
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        val account: GoogleSignInAccount? = GoogleAuthManager.handleSignInResult(application, task)

        if (account != null) {
            viewModelScope.launch {
                val currentUser = user.value
                val googleId = account.id ?: return@launch

                if (currentUser != null) {
                    // Existing user — link Google account
                    val updated = currentUser.copy(
                        name = account.displayName ?: currentUser.name,
                        googleAccountId = googleId,
                        deviceUuid = DeviceIdentityManager.deviceUuid
                    )
                    repository.upsertUser(updated)

                    if (NetworkHelper.isInternetAvailable(application)) {
                        val firestoreId = FirebaseManager.upsertUser(updated, contacts.value)
                        if (firestoreId != null) {
                            _firestoreUserId.value = firestoreId
                            FirebaseManager.registerDevice(
                                userName = updated.name,
                                phoneNumber = updated.phoneNumber,
                                firestoreUserId = firestoreId
                            )
                            repository.upsertUser(updated.copy(
                                isRegistered = true,
                                firestoreUserId = firestoreId
                            ))
                        }
                    }
                } else {
                    // No existing user — create from Google profile
                    val googleUser = UserEntity(
                        name            = account.displayName ?: "Google User",
                        phoneNumber     = "", // No phone from Google — user can add later
                        temporaryUserId = googleId,
                        isRegistered    = false,
                        deviceUuid      = DeviceIdentityManager.deviceUuid,
                        googleAccountId = googleId
                    )
                    repository.upsertUser(googleUser)

                    if (NetworkHelper.isInternetAvailable(application)) {
                        val firestoreId = FirebaseManager.upsertUser(googleUser, contacts.value)
                        if (firestoreId != null) {
                            _firestoreUserId.value = firestoreId
                            FirebaseManager.registerDevice(
                                userName = googleUser.name,
                                phoneNumber = "",
                                firestoreUserId = firestoreId
                            )
                            repository.upsertUser(googleUser.copy(
                                isRegistered = true,
                                firestoreUserId = firestoreId
                            ))
                        }
                    }

                    Log.i("SosViewModel", "Created user from Google: ${account.displayName} (${account.email})")
                }
            }
        }
    }

    fun googleSignOut() {
        GoogleAuthManager.signOut(application) {
            viewModelScope.launch {
                val currentUser = user.value
                if (currentUser != null) {
                    repository.upsertUser(currentUser.copy(googleAccountId = null))
                }
            }
        }
    }
}

class SosViewModelFactory(
    private val repository: SosRepository,
    private val application: Application
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SosViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SosViewModel(repository, application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
