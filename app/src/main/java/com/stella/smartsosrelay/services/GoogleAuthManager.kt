package com.stella.smartsosrelay.services

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * GoogleAuthManager — Optional Google Sign-In integration for Stella SOS.
 *
 * When signed in:
 * - Links deviceUuid to Google account for cross-device identity
 * - Stores userId, deviceUuid, lastKnownLocation in Firestore
 * - Enables emergency contact linkage via Google profile
 *
 * When NOT signed in:
 * - System operates in anonymous BLE mode (default)
 * - All BLE mesh functionality works identically
 *
 * NOTE: Requires oauth_client to be configured in google-services.json.
 * Enable Google Sign-In in Firebase Console → Authentication → Sign-in method.
 */
object GoogleAuthManager {

    private const val TAG = "GoogleAuthManager"

    private var googleSignInClient: GoogleSignInClient? = null

    /** Whether Google Sign-In is properly configured (oauth_client exists) */
    private val _isConfigured = MutableStateFlow(false)
    val isConfigured: StateFlow<Boolean> = _isConfigured

    private val _isSignedIn = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn

    private val _displayName = MutableStateFlow<String?>(null)
    val displayName: StateFlow<String?> = _displayName

    private val _email = MutableStateFlow<String?>(null)
    val email: StateFlow<String?> = _email

    private val _photoUrl = MutableStateFlow<String?>(null)
    val photoUrl: StateFlow<String?> = _photoUrl

    /**
     * Initialize Google Sign-In client.
     * Call from Activity before requesting sign-in.
     *
     * @param context Activity or Application context
     * @param webClientId Your Google Cloud OAuth 2.0 Web Client ID.
     *                     If null/blank, uses default sign-in (email only, no ID token).
     */
    fun init(context: Context, webClientId: String? = null) {
        try {
            val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()

            if (!webClientId.isNullOrBlank()) {
                gsoBuilder.requestIdToken(webClientId)
            }

            googleSignInClient = GoogleSignIn.getClient(context, gsoBuilder.build())
            _isConfigured.value = true

            // Check if already signed in from a previous session
            val account = GoogleSignIn.getLastSignedInAccount(context)
            if (account != null) {
                updateState(context, account)
            }

            Log.d(TAG, "Google Sign-In initialized (configured=${_isConfigured.value})")
        } catch (e: Exception) {
            Log.w(TAG, "Google Sign-In initialization failed — OAuth client may not be configured", e)
            _isConfigured.value = false
        }
    }

    /**
     * Get the Intent to launch Google Sign-In.
     * Returns null if not configured or client not initialized.
     */
    fun getSignInIntent(context: Context): Intent? {
        if (googleSignInClient == null) {
            Toast.makeText(
                context,
                "⚠️ Google Sign-In not configured.\nEnable it in Firebase Console → Authentication → Sign-in method → Google",
                Toast.LENGTH_LONG
            ).show()
            Log.w(TAG, "Google Sign-In not configured — oauth_client is empty in google-services.json")
            return null
        }
        return googleSignInClient?.signInIntent
    }

    /**
     * Handle the result from the Google Sign-In activity.
     * Call from your Activity's onActivityResult or ActivityResultCallback.
     *
     * @return The signed-in account, or null if sign-in failed
     */
    fun handleSignInResult(context: Context, task: Task<GoogleSignInAccount>): GoogleSignInAccount? {
        return try {
            val account = task.getResult(ApiException::class.java)
            if (account != null) {
                updateState(context, account)
                Log.i(TAG, "Google Sign-In successful: ${account.displayName}")
                Toast.makeText(context, "✓ Signed in as ${account.displayName}", Toast.LENGTH_SHORT).show()
            }
            account
        } catch (e: ApiException) {
            val msg = when (e.statusCode) {
                12500 -> "Google Sign-In not configured in Firebase Console"
                12501 -> "Sign-in cancelled"
                12502 -> "Sign-in already in progress"
                else -> "Sign-in failed (code: ${e.statusCode})"
            }
            Log.e(TAG, "Google Sign-In failed: $msg", e)
            Toast.makeText(context, "⚠️ $msg", Toast.LENGTH_LONG).show()
            null
        }
    }

    /**
     * Sign out from Google account. Reverts to anonymous BLE mode.
     */
    fun signOut(context: Context, onComplete: () -> Unit = {}) {
        googleSignInClient?.signOut()?.addOnCompleteListener {
            _isSignedIn.value = false
            _displayName.value = null
            _email.value = null
            _photoUrl.value = null
            DeviceIdentityManager.unlinkGoogleAccount(context)
            Log.i(TAG, "Google Sign-Out complete")
            onComplete()
        } ?: run {
            onComplete()
        }
    }

    /**
     * Check if user is currently signed in with Google.
     */
    fun isCurrentlySignedIn(context: Context): Boolean {
        return GoogleSignIn.getLastSignedInAccount(context) != null
    }

    /**
     * Get the current Google account ID (if signed in).
     */
    fun getAccountId(): String? {
        return if (_isSignedIn.value) DeviceIdentityManager.googleAccountId else null
    }

    private fun updateState(context: Context, account: GoogleSignInAccount) {
        _isSignedIn.value = true
        _displayName.value = account.displayName
        _email.value = account.email
        _photoUrl.value = account.photoUrl?.toString()

        // Link Google account ID to device identity
        account.id?.let { googleId ->
            DeviceIdentityManager.linkGoogleAccount(context, googleId)
        }
    }
}
