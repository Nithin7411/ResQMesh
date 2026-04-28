package com.stella.smartsosrelay.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.stella.smartsosrelay.SmartSosApplication
import com.stella.smartsosrelay.data.SosEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SosForegroundService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "SosForegroundService"
        var bleManager: BleManager? = null
            private set
        var fallDetectionHelper: FallDetectionHelper? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())

        // Acquire partial WakeLock to prevent OEM battery killers from stopping sensors
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartSOS::SensorWakelock")
        wakeLock?.acquire()

        // Initialize BLE — safe even if device has no BLE hardware
        try {
            bleManager = BleManager(this) { event ->
                val repository = (application as SmartSosApplication).repository
                scope.launch {
                    // ── Dedup check: verify this event hasn't been stored already ──
                    val existing = repository.getEventByHash(event.eventIdHash)
                    if (existing != null) {
                        Log.d(TAG, "Event already in DB (hash=${event.eventIdHash}), skipping insert")
                        return@launch
                    }
                    repository.insertSosEvent(event)
                }
            }
            bleManager!!.startScanning()
        } catch (e: Exception) {
            Log.w(TAG, "BLE not available on this device", e)
        }

        // Fall detection via accelerometer — works on all devices
        fallDetectionHelper = FallDetectionHelper(this) {
            val repository = (application as SmartSosApplication).repository
            scope.launch {
                val user = repository.user.first()
                // Priority: firestoreUserId → phoneNumber → deviceUuid
                val userId = when {
                    user?.firestoreUserId?.isNotBlank() == true -> user.firestoreUserId
                    user?.phoneNumber?.isNotBlank() == true -> user.phoneNumber
                    else -> DeviceIdentityManager.deviceUuid
                }
                val gps = LocationHelper.getCurrentLocation(this@SosForegroundService)
                val timestampMs = System.currentTimeMillis()
                val eventHash = DeviceIdentityManager.generateEventHash(timestampMs)

                val event = SosEventEntity(
                    eventId = "EVT_${String.format("%08X", eventHash)}",
                    userId = userId,
                    timestamp = timestampMs,
                    latitude = gps.latitude,
                    longitude = gps.longitude,
                    triggerReason = "SENSOR",
                    status = "PENDING",
                    eventIdHash = eventHash
                )
                repository.insertSosEvent(event)
            }
        }
        fallDetectionHelper!!.startListening()

        observePendingSosEvents()
        observeUnsyncedEvents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { bleManager?.destroy() } catch (_: Exception) {}
        bleManager = null
        fallDetectionHelper?.stopListening()
        fallDetectionHelper = null
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        job.cancel()
    }

    // Android may kill the service; this ensures it restarts
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val restartIntent = Intent(applicationContext, SosForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(restartIntent)
        } else {
            applicationContext.startService(restartIntent)
        }
    }

    private fun observePendingSosEvents() {
        val repository = (application as SmartSosApplication).repository
        scope.launch {
            repository.allEvents.collect { events ->
                val pendingEvents = events.filter { it.status == "PENDING" || it.status == "PENDING_RELAY" }

                // Only advertise the LATEST event (by timestamp) — others are processed but not broadcast
                val latestEvent = pendingEvents.maxByOrNull { it.timestamp }

                for (event in pendingEvents) {
                    val isLatest = event.eventId == latestEvent?.eventId
                    processSosEvent(event, advertiseOverBle = isLatest)
                }
            }
        }
    }

    /**
     * Background sync job: periodically check for LOCAL events and upload them
     * when internet becomes available. This implements the hybrid offline+online model.
     */
    private fun observeUnsyncedEvents() {
        val repository = (application as SmartSosApplication).repository
        scope.launch {
            repository.unsyncedEvents.collect { unsyncedEvents ->
                if (unsyncedEvents.isEmpty()) return@collect
                if (!NetworkHelper.isInternetAvailable(this@SosForegroundService)) return@collect

                // Get user's firestoreUserId for uploads
                val user = repository.user.first()
                val firestoreUserId = user?.firestoreUserId ?: ""

                for (event in unsyncedEvents) {
                    try {
                        val success = if (event.status == "PENDING_RELAY" || event.relayCount > 0) {
                            FirebaseManager.uploadRelayedSos(
                                event,
                                DeviceIdentityManager.deviceUuid,
                                DeviceIdentityManager.deviceHex,
                                relayFirestoreUserId = firestoreUserId
                            )
                        } else {
                            FirebaseManager.uploadSosEvent(
                                event,
                                userName = user?.name ?: "",
                                phoneNumber = user?.phoneNumber ?: "",
                                firestoreUserId = firestoreUserId
                            )
                        }

                        if (success) {
                            repository.updateSyncStatus(event.eventId, "SYNCED")
                            Log.i(TAG, "✓ Synced event to server: ${event.eventId}")
                        } else {
                            repository.updateSyncStatus(event.eventId, "SYNC_FAILED")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Sync failed for ${event.eventId}", e)
                        repository.updateSyncStatus(event.eventId, "SYNC_FAILED")
                    }
                }
            }
        }
    }

    private suspend fun processSosEvent(event: SosEventEntity, advertiseOverBle: Boolean) {
        val repository = (application as SmartSosApplication).repository

        if (event.status == "PENDING_RELAY") {
            // ── RELAYED SOS from another device (received via BLE scan) ────
            //
            // Strategy:
            //   1. Try to upload to Firebase immediately
            //   2. If online → upload → mark RELAYED_FIREBASE → done
            //   3. If offline → save locally (already in Room) + RE-ADVERTISE
            //      so another nearby phone with internet can relay it
            //
            // Re-advertising preserves the ORIGINAL sender's deviceIdHash
            // so de-duplication works across the entire relay chain.

            if (NetworkHelper.isInternetAvailable(this)) {
                // Get relay device's firestoreUserId
                val user = repository.user.first()
                val relayFirestoreUserId = user?.firestoreUserId ?: ""

                val success = FirebaseManager.uploadRelayedSos(
                    event,
                    DeviceIdentityManager.deviceUuid,
                    DeviceIdentityManager.deviceHex,
                    relayFirestoreUserId = relayFirestoreUserId
                )
                if (success) {
                    repository.updateSosEventStatus(event.eventId, "RELAYED_FIREBASE")
                    repository.updateSyncStatus(event.eventId, "SYNCED")
                    Log.i(TAG, "✓ Relayed SOS uploaded to Firebase: ${event.eventId}")

                    // Stop advertising this relay since it's been delivered
                    bleManager?.stopAdvertising()
                } else {
                    Log.w(TAG, "Firebase upload failed for relay: ${event.eventId}")
                    // Will retry on next collect cycle
                }
            } else {
                // OFFLINE — re-advertise so another phone can pick it up
                if (advertiseOverBle) {
                    Log.i(TAG, "⟳ Offline — re-advertising relayed SOS: ${event.eventId}")

                    // Add randomized relay delay to avoid BLE flooding
                    delay((100L..500L).random())

                    // Extract the original sender's hash from the userId hex string
                    val originalHash = try {
                        event.userId.toLong(16).toInt()
                    } catch (e: Exception) {
                        event.userId.hashCode()
                    }

                    try {
                        bleManager?.reAdvertiseRelayedSos(event, originalHash)
                    } catch (e: Exception) {
                        Log.w(TAG, "Re-advertise failed", e)
                    }
                }
                // Event stays as PENDING_RELAY in Room — will retry when internet returns
            }
        } else {
            // ── OUR OWN SOS event ────────────────────────────────────────────
            val user = repository.user.first()
            val contacts = repository.contacts.first()
            val userName = user?.name ?: "Unknown User"
            val firestoreUserId = user?.firestoreUserId ?: ""

            // 1. Send SMS to all trusted contacts
            for (contact in contacts) {
                SmsHelper.sendSosSms(
                    context = this,
                    phoneNumber = contact.phoneNumber,
                    userName = userName,
                    latitude = event.latitude,
                    longitude = event.longitude,
                    triggerReason = event.triggerReason
                )
            }

            var newStatus = "SENT_SMS"

            // 2. Upload to Firebase if internet available (with firestoreUserId)
            if (NetworkHelper.isInternetAvailable(this)) {
                val success = FirebaseManager.uploadSosEvent(
                    event,
                    userName = userName,
                    phoneNumber = user?.phoneNumber ?: "",
                    firestoreUserId = firestoreUserId
                )
                if (success) {
                    newStatus = "SENT_FIREBASE"
                    repository.updateSyncStatus(event.eventId, "SYNCED")
                }
            }

            // 3. Broadcast via BLE (only the latest event)
            if (advertiseOverBle) {
                try {
                    bleManager?.startAdvertisingSos(event)
                } catch (_: Exception) {}
            }

            repository.updateSosEventStatus(event.eventId, newStatus)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "sos_channel",
                "Smart SOS Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the SOS relay active in the background"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "sos_channel")
            .setContentTitle("Smart SOS Relay")
            .setContentText("Protecting you in the background...")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .build()
    }
}
