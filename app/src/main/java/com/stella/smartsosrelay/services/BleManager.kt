package com.stella.smartsosrelay.services

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.stella.smartsosrelay.data.SosEventEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.LinkedHashSet

data class BleLogEntry(
    val timestamp: Long,
    val deviceId: String,
    val latitude: Double,
    val longitude: Double,
    val triggerReason: String,
    val rssi: Int = 0,
    val isOutgoing: Boolean,
    /** Deterministic event ID hash — hex string for display */
    val eventIdHex: String = "",
    /** Number of relay hops */
    val hopCount: Int = 0
)

@SuppressLint("MissingPermission", "HardwareIds")
class BleManager(
    private val context: Context,
    private val onRelayEventReceived: (SosEventEntity) -> Unit
) {

    companion object {
        val SOS_SERVICE_UUID: UUID =
            UUID.fromString("0000FFF3-0000-1000-8000-00805F9B34FB")
        private const val TAG = "BleManager"
        /** Maximum relay hops before dropping the event */
        private const val MAX_HOP_COUNT = 3
        /** Ignore BLE events older than this (milliseconds) */
        private const val EVENT_TTL_MS = 30 * 60 * 1000L // 30 minutes
        /** Minimum RSSI to consider a scan result (ignore very weak signals) */
        private const val MIN_RSSI = -90
        /** Maximum tracked event IDs (LRU eviction) */
        private const val MAX_PROCESSED_IDS = 200
    }

    private val pUuid = ParcelUuid(SOS_SERVICE_UUID)

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val adapter = bluetoothManager.adapter
    private val advertiser = adapter?.bluetoothLeAdvertiser
    private val scanner = adapter?.bluetoothLeScanner

    /** Stable device identity from DeviceIdentityManager */
    val deviceId: String
        get() = DeviceIdentityManager.deviceUuid

    /** Deterministic 4-byte hash of device UUID */
    private val myDeviceIdHash: Int
        get() = DeviceIdentityManager.deviceIdHash

    private val _advertisingLog = MutableStateFlow<List<BleLogEntry>>(emptyList())
    val advertisingLog: StateFlow<List<BleLogEntry>> = _advertisingLog

    private val _scannedDevices = MutableStateFlow<List<BleLogEntry>>(emptyList())
    val scannedDevices: StateFlow<List<BleLogEntry>> = _scannedDevices

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private var pendingAdvertiseEvent: SosEventEntity? = null
    private var pendingPayloadBytes: ByteArray? = null

    private var rawAdvertisingActive = false
    private var rawScanningActive = false

    /**
     * Dedup set — tracks processed eventIdHash values (Int-based, not String).
     * Uses a bounded LinkedHashSet with LRU eviction to prevent memory leaks.
     */
    private val processedEventIds = object : LinkedHashSet<Int>() {
        override fun add(element: Int): Boolean {
            if (size >= MAX_PROCESSED_IDS) {
                val oldest = iterator().next()
                remove(oldest)
            }
            return super.add(element)
        }
    }

    // ── Advertise Callback ─────────────────────────────────

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            rawAdvertisingActive = true
            _isAdvertising.value = true
            Log.d(TAG, "[ADV] ✓ Started")
        }

        override fun onStartFailure(errorCode: Int) {
            rawAdvertisingActive = false
            _isAdvertising.value = false
            Log.e(TAG, "[ADV] ✗ FAILED: $errorCode")
        }
    }

    // ── Scan Callback ─────────────────────────────────────

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return

            val record = result.scanRecord

            // 🔥 RAW LOG — ALL DEVICES
            Log.d(TAG, """
            ================== [SCAN RAW] ==================
            Device Address : ${result.device.address}
            Device Name    : ${record?.deviceName}
            RSSI           : ${result.rssi}
            Tx Power       : ${record?.txPowerLevel}
            UUIDs          : ${record?.serviceUuids}
            Service Data   : ${record?.serviceData?.keys}
        """.trimIndent())

            // If no scan record → ignore
            record ?: return

            val serviceDataMap = record.serviceData

            // If no service data → not our packet
            if (serviceDataMap == null || serviceDataMap.isEmpty()) {
                Log.d(TAG, "[SCAN] ❌ No service data (not our packet)")
                return
            }

            val rawBytes = serviceDataMap[pUuid]

            // If UUID not found → not our packet
            if (rawBytes == null) {
                Log.d(TAG, "[SCAN] ❌ UUID mismatch (not our packet)")
                return
            }

            // 🔥 LOG RAW PAYLOAD
            Log.d(TAG, "[SCAN] RAW PAYLOAD (${rawBytes.size}B): ${rawBytes.joinToString { "%02X".format(it) }}")

            try {
                val payload = BlePayloadCodec.decode(rawBytes)

                if (payload == null) {
                    Log.w(TAG, "[SCAN] ❌ Decode failed")
                    return
                }

                // ── RSSI filtering: ignore very weak signals ──
                if (result.rssi < MIN_RSSI) {
                    Log.d(TAG, "[SCAN] ↩ RSSI too weak (${result.rssi} < $MIN_RSSI)")
                    return
                }

                // ── Own-packet detection: use deterministic hash ──
                if (payload.deviceIdHash == myDeviceIdHash) {
                    Log.d(TAG, "[SCAN] ↩ Own packet ignored")
                    return
                }

                // ── Event TTL: ignore stale events ──
                val eventAgeMs = System.currentTimeMillis() - (payload.epochSeconds * 1000L)
                if (eventAgeMs > EVENT_TTL_MS) {
                    Log.d(TAG, "[SCAN] ↩ Stale event (${eventAgeMs / 1000}s old)")
                    return
                }

                // ── Hop count limit: prevent infinite relay flooding ──
                if (payload.hopCount >= MAX_HOP_COUNT) {
                    Log.d(TAG, "[SCAN] ↩ Hop limit reached (${payload.hopCount} >= $MAX_HOP_COUNT)")
                    return
                }

                // ── Determine dedup key ──
                // For v2: use eventIdHash from packet (deterministic, same on all devices)
                // For v1: synthesize from deviceIdHash + epochSeconds
                val dedupKey = if (payload.eventIdHash != 0) {
                    payload.eventIdHash
                } else {
                    // v1 fallback: deterministic combination
                    (payload.deviceIdHash.toLong() * 31 + payload.epochSeconds).toInt()
                }

                if (processedEventIds.contains(dedupKey)) {
                    Log.d(TAG, "[SCAN] ↩ Duplicate (eventHash=${payload.eventHex})")

                    // 🔥 STILL SHOW IN UI (for debugging)
                    _scannedDevices.value = (_scannedDevices.value + BleLogEntry(
                        timestamp = System.currentTimeMillis(),
                        deviceId = payload.deviceHex,
                        latitude = payload.latitude,
                        longitude = payload.longitude,
                        triggerReason = payload.triggerReason,
                        rssi = result.rssi,
                        isOutgoing = false,
                        eventIdHex = payload.eventHex,
                        hopCount = payload.hopCount
                    )).takeLast(50)

                    return
                }

                processedEventIds.add(dedupKey)

                // 🔥 FINAL SUCCESS LOG
                Log.i(TAG, """
                ================== [SCAN SUCCESS] ==================
                Version     : v${payload.version}
                Event Hash  : ${payload.eventHex}
                Device ID   : ${payload.deviceHex}
                Latitude    : ${payload.latitude}
                Longitude   : ${payload.longitude}
                Trigger     : ${payload.triggerReason}
                Hop Count   : ${payload.hopCount}
                RSSI        : ${result.rssi}
            """.trimIndent())

                // UI log
                _scannedDevices.value = (_scannedDevices.value + BleLogEntry(
                    timestamp = System.currentTimeMillis(),
                    deviceId = payload.deviceHex,
                    latitude = payload.latitude,
                    longitude = payload.longitude,
                    triggerReason = payload.triggerReason,
                    rssi = result.rssi,
                    isOutgoing = false,
                    eventIdHex = payload.eventHex,
                    hopCount = payload.hopCount
                )).takeLast(50)

                // Build the eventId string — use eventHash hex for v2, or legacy format for v1
                val eventIdStr = if (payload.eventIdHash != 0) {
                    "EVT_${payload.eventHex}"
                } else {
                    "RELAY_${payload.deviceHex}_${payload.epochSeconds}"
                }

                val event = SosEventEntity(
                    eventId = eventIdStr,
                    userId = payload.deviceHex,
                    timestamp = payload.epochSeconds * 1000L,
                    latitude = payload.latitude,
                    longitude = payload.longitude,
                    triggerReason = payload.triggerReason,
                    status = "PENDING_RELAY",
                    eventIdHash = dedupKey,
                    relayCount = payload.hopCount
                )

                onRelayEventReceived(event)

            } catch (e: Exception) {
                Log.e(TAG, "[SCAN ERROR] ${e.message}", e)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            rawScanningActive = false
            _isScanning.value = false
            Log.e(TAG, "[SCAN] FAILED: $errorCode")
        }
    }

    // ── RAW BLE ───────────────────────────────────────

    private fun rawStartAdvertising() {
        val dataBytes = pendingPayloadBytes ?: return
        if (advertiser == null || rawAdvertisingActive) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(pUuid)
            .addServiceData(pUuid, dataBytes)
            .build()

        // 🔥 FULL LOGGING BEFORE ADVERTISE
        val event = pendingAdvertiseEvent
        Log.i(TAG, """
        ================== [ADV START] ==================
        Device UUID : ${DeviceIdentityManager.deviceUuid}
        Device Hash : ${DeviceIdentityManager.deviceHex}
        Event       : ${event?.eventId ?: "N/A"}
        Latitude    : ${event?.latitude}
        Longitude   : ${event?.longitude}
        Trigger     : ${event?.triggerReason}
        Timestamp   : ${event?.timestamp}
        UUID        : $SOS_SERVICE_UUID
        Payload HEX : ${dataBytes.joinToString(" ") { "%02X".format(it) }}
        Payload Size: ${dataBytes.size} bytes
        Mode        : LOW_LATENCY
        Tx Power    : HIGH
    """.trimIndent())

        advertiser.startAdvertising(settings, data, advertiseCallback)

        _advertisingLog.value = (_advertisingLog.value + BleLogEntry(
            timestamp = System.currentTimeMillis(),
            deviceId = DeviceIdentityManager.deviceHex,
            latitude = event?.latitude ?: 0.0,
            longitude = event?.longitude ?: 0.0,
            triggerReason = event?.triggerReason ?: "UNKNOWN",
            isOutgoing = true,
            eventIdHex = String.format("%08X", event?.eventIdHash ?: 0),
            hopCount = event?.relayCount ?: 0
        )).takeLast(50)
    }

    private fun rawStopAdvertising() {
        if (!rawAdvertisingActive) return
        advertiser?.stopAdvertising(advertiseCallback)
        rawAdvertisingActive = false
        _isAdvertising.value = false
    }

    private fun rawStartScanning() {
        if (scanner == null || rawScanningActive) return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)
        rawScanningActive = true
        _isScanning.value = true
    }

    private fun rawStopScanning() {
        if (!rawScanningActive) return
        scanner?.stopScan(scanCallback)
        rawScanningActive = false
        _isScanning.value = false
    }

    // ── PUBLIC API ───────────────────────────────────

    fun startScanning() {
        Log.d(TAG, "[API] startScanning")
        if (!rawScanningActive) rawStartScanning()
    }

    fun stopScanning() {
        rawStopScanning()
    }

    /**
     * Start advertising our own SOS event using the v2 payload format.
     * The eventIdHash is generated from DeviceIdentityManager and embedded in the packet.
     */
    fun startAdvertisingSos(event: SosEventEntity) {
        rawStopAdvertising()
        pendingAdvertiseEvent = event

        // Encode as v2 payload — our own event (hopCount = 0)
        pendingPayloadBytes = BlePayloadCodec.encodeV2(
            lat = event.latitude,
            lng = event.longitude,
            epochMillis = event.timestamp,
            trigger = event.triggerReason,
            deviceIdHash = myDeviceIdHash,
            eventIdHash = event.eventIdHash,
            hopCount = 0
        )

        processedEventIds.add(event.eventIdHash)
        rawStartAdvertising()
    }

    /**
     * Re-advertise a relayed SOS event, preserving the original sender's identity
     * and incrementing the hop count.
     *
     * @param event The relayed event
     * @param originalPayload The decoded BLE payload from the original scan (provides deviceIdHash + eventIdHash)
     */
    fun reAdvertiseRelayedSos(event: SosEventEntity, originalDeviceHash: Int) {
        rawStopAdvertising()
        pendingAdvertiseEvent = event

        // Encode as v2 payload — relay (hop count incremented)
        pendingPayloadBytes = BlePayloadCodec.encodeV2(
            lat = event.latitude,
            lng = event.longitude,
            epochMillis = event.timestamp,
            trigger = event.triggerReason,
            deviceIdHash = originalDeviceHash,
            eventIdHash = event.eventIdHash,
            hopCount = event.relayCount + 1
        )

        processedEventIds.add(event.eventIdHash)
        rawStartAdvertising()
    }

    fun stopAdvertising() {
        pendingAdvertiseEvent = null
        pendingPayloadBytes = null
        rawStopAdvertising()
    }

    fun destroy() {
        rawStopAdvertising()
        rawStopScanning()
    }
}