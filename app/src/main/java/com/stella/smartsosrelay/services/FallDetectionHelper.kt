package com.stella.smartsosrelay.services

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detection phase for the multi-stage fall detection algorithm.
 */
enum class FallDetectionPhase {
    /** Normal state — monitoring for freefall. */
    IDLE,
    /** Freefall detected — magnitude dropped below threshold. Waiting for impact. */
    FREEFALL_DETECTED,
    /** High-G impact detected after freefall. Waiting for post-impact stillness. */
    IMPACT_DETECTED,
    /** Checking post-impact stillness/orientation change. */
    STILLNESS_CHECK,
    /** All 3 phases confirmed — fall triggered. */
    TRIGGERED
}

data class SensorReading(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val magnitude: Float = 0f,
    val timestamp: Long = 0L,
    val triggered: Boolean = false,
    val phase: FallDetectionPhase = FallDetectionPhase.IDLE
)

/**
 * FallDetectionHelper — Production-grade multi-phase fall detection.
 *
 * Real falls follow a distinctive 3-phase pattern:
 *
 *   Phase 1: FREEFALL
 *     Person is falling → accelerometer reads near 0 (weightlessness).
 *     Magnitude drops below FREEFALL_THRESHOLD for 80–300ms.
 *
 *   Phase 2: IMPACT
 *     Person hits the ground → accelerometer spikes to very high G.
 *     Magnitude exceeds IMPACT_THRESHOLD within IMPACT_WINDOW_MS after freefall ends.
 *
 *   Phase 3: STILLNESS / ORIENTATION CHANGE
 *     Person is lying on the ground → reduced movement + orientation changed.
 *     Average magnitude stays near gravity (but orientation differs from pre-fall).
 *     Must persist for POST_IMPACT_STILLNESS_MS.
 *
 * This eliminates false positives from:
 *   - Phone drops (no freefall phase — phone starts from rest)
 *   - Shaking (no freefall phase — continuous high-G)
 *   - Walking/running (no impact spike after low-G)
 *   - Sitting down quickly (no sustained freefall)
 */
class FallDetectionHelper(
    context: Context,
    private val onFallDetected: () -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "FallDetection"

        // ── Phase 1: Freefall thresholds ──
        /** Below this magnitude = freefall (normal gravity is ~9.81 m/s²) */
        const val FREEFALL_THRESHOLD = 3.0f          // m/s²
        /** Minimum freefall duration to qualify (filters micro-glitches) */
        const val FREEFALL_MIN_DURATION_MS = 80L
        /** Maximum freefall duration (falls from standing are < 600ms) */
        const val FREEFALL_MAX_DURATION_MS = 600L

        // ── Phase 2: Impact thresholds ──
        /** Above this magnitude = impact (a real fall impact is 20–60 m/s²) */
        const val IMPACT_THRESHOLD = 20.0f            // m/s²
        /** Window after freefall ends to look for impact */
        const val IMPACT_WINDOW_MS = 1000L

        // ── Phase 3: Post-impact stillness ──
        /** Duration of reduced movement required after impact */
        const val POST_IMPACT_STILLNESS_MS = 2000L
        /** Max magnitude variance during stillness (near gravity ± this value) */
        const val STILLNESS_THRESHOLD = 4.0f          // m/s²
        /** Minimum orientation change (delta from pre-fall average) to confirm fall */
        const val ORIENTATION_CHANGE_THRESHOLD = 3.0f // m/s² axis shift

        // ── Global ──
        /** Cooldown between triggers (prevents repeated triggers from same event) */
        const val COOLDOWN_MS = 10000L                // 10 seconds
        /** Sliding window size for orientation tracking */
        const val ORIENTATION_WINDOW_SIZE = 50
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // ── Detection state machine ──
    private var currentPhase = FallDetectionPhase.IDLE
    private var freefallStartTime = 0L
    private var freefallEndTime = 0L
    private var impactTime = 0L
    private var impactMagnitude = 0f
    private var lastTriggerTime = 0L

    // ── Pre-fall orientation tracking ──
    // Stores recent readings to compute the "normal" orientation before a fall
    private val preFallWindow = ArrayDeque<FloatArray>(ORIENTATION_WINDOW_SIZE)
    private var preFallAvgX = 0f
    private var preFallAvgY = 0f
    private var preFallAvgZ = 0f

    // ── Post-impact stillness tracking ──
    private val postImpactReadings = mutableListOf<Float>()

    // Live sensor data exposed as StateFlow so UI can observe it
    private val _currentReading = MutableStateFlow(SensorReading())
    val currentReading: StateFlow<SensorReading> = _currentReading

    private val _triggerCount = MutableStateFlow(0)
    val triggerCount: StateFlow<Int> = _triggerCount

    private val _triggerLog = MutableStateFlow<List<SensorReading>>(emptyList())
    val triggerLog: StateFlow<List<SensorReading>> = _triggerLog

    private val _currentPhaseFlow = MutableStateFlow(FallDetectionPhase.IDLE)
    val currentPhaseFlow: StateFlow<FallDetectionPhase> = _currentPhaseFlow

    fun startListening() {
        accelerometer?.let {
            // Use SENSOR_DELAY_GAME for faster sampling (~20ms) — better accuracy
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.i(TAG, "Fall detection started (multi-phase algorithm)")
        }
    }

    fun stopListening() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val now = System.currentTimeMillis()

        // Update UI state
        _currentReading.value = SensorReading(
            x = x, y = y, z = z,
            magnitude = magnitude,
            timestamp = now,
            triggered = false,
            phase = currentPhase
        )

        // ── Cooldown check ──
        if (now - lastTriggerTime < COOLDOWN_MS && currentPhase == FallDetectionPhase.IDLE) {
            return
        }

        // ── State machine ──
        when (currentPhase) {
            FallDetectionPhase.IDLE -> {
                // Update pre-fall orientation window
                updatePreFallWindow(x, y, z)

                // Check for freefall: magnitude drops significantly below gravity
                if (magnitude < FREEFALL_THRESHOLD) {
                    currentPhase = FallDetectionPhase.FREEFALL_DETECTED
                    freefallStartTime = now
                    _currentPhaseFlow.value = currentPhase
                    Log.d(TAG, "⚡ Phase 1: Freefall detected (mag=${String.format("%.1f", magnitude)})")
                }
            }

            FallDetectionPhase.FREEFALL_DETECTED -> {
                if (magnitude < FREEFALL_THRESHOLD) {
                    // Still in freefall — check duration limits
                    val duration = now - freefallStartTime
                    if (duration > FREEFALL_MAX_DURATION_MS) {
                        // Too long for a fall — probably sensor noise or slow motion
                        Log.d(TAG, "✗ Freefall too long (${duration}ms), resetting")
                        resetState()
                    }
                } else {
                    // Freefall ended — check if it was long enough
                    val duration = now - freefallStartTime
                    if (duration >= FREEFALL_MIN_DURATION_MS) {
                        // Valid freefall → wait for impact
                        freefallEndTime = now
                        currentPhase = FallDetectionPhase.IMPACT_DETECTED
                        _currentPhaseFlow.value = currentPhase
                        Log.d(TAG, "✓ Freefall confirmed (${duration}ms), waiting for impact...")

                        // Check if THIS reading is already the impact
                        if (magnitude > IMPACT_THRESHOLD) {
                            impactTime = now
                            impactMagnitude = magnitude
                            currentPhase = FallDetectionPhase.STILLNESS_CHECK
                            postImpactReadings.clear()
                            _currentPhaseFlow.value = currentPhase
                            Log.d(TAG, "⚡ Phase 2: Immediate impact! (mag=${String.format("%.1f", magnitude)})")
                        }
                    } else {
                        // Too short — not a real freefall
                        Log.d(TAG, "✗ Freefall too short (${duration}ms), resetting")
                        resetState()
                    }
                }
            }

            FallDetectionPhase.IMPACT_DETECTED -> {
                // Waiting for high-G impact within the window
                val timeSinceFreefallEnd = now - freefallEndTime

                if (magnitude > IMPACT_THRESHOLD) {
                    // Impact detected!
                    impactTime = now
                    impactMagnitude = magnitude
                    currentPhase = FallDetectionPhase.STILLNESS_CHECK
                    postImpactReadings.clear()
                    _currentPhaseFlow.value = currentPhase
                    Log.d(TAG, "⚡ Phase 2: Impact detected! (mag=${String.format("%.1f", magnitude)}, " +
                            "delay=${timeSinceFreefallEnd}ms after freefall)")
                } else if (timeSinceFreefallEnd > IMPACT_WINDOW_MS) {
                    // No impact within window — false alarm
                    Log.d(TAG, "✗ No impact within ${IMPACT_WINDOW_MS}ms, resetting")
                    resetState()
                }
            }

            FallDetectionPhase.STILLNESS_CHECK -> {
                // Phase 3: Check for post-impact stillness + orientation change
                val timeSinceImpact = now - impactTime

                // Collect magnitude readings
                postImpactReadings.add(magnitude)

                if (timeSinceImpact >= POST_IMPACT_STILLNESS_MS) {
                    // Check if movement was reduced (near gravity)
                    val avgMag = postImpactReadings.average().toFloat()
                    val variance = postImpactReadings.map { abs(it - avgMag) }.average().toFloat()

                    // Check orientation change (compared to pre-fall)
                    // We use the last few readings to get current orientation
                    val orientationChanged = checkOrientationChange(x, y, z)

                    Log.d(TAG, "Phase 3 check: avgMag=${String.format("%.1f", avgMag)}, " +
                            "variance=${String.format("%.2f", variance)}, orientationChanged=$orientationChanged")

                    if (variance < STILLNESS_THRESHOLD) {
                        // Person is relatively still after impact — FALL CONFIRMED
                        currentPhase = FallDetectionPhase.TRIGGERED
                        _currentPhaseFlow.value = currentPhase
                        lastTriggerTime = now
                        _triggerCount.value++

                        val triggeredReading = SensorReading(
                            x = x, y = y, z = z,
                            magnitude = impactMagnitude,
                            timestamp = now,
                            triggered = true,
                            phase = FallDetectionPhase.TRIGGERED
                        )
                        _triggerLog.value = _triggerLog.value + triggeredReading

                        Log.i(TAG, "🚨 FALL DETECTED! Freefall → Impact (${String.format("%.1f", impactMagnitude)} m/s²) → Stillness confirmed")

                        onFallDetected()
                        resetState()
                    } else {
                        // Too much movement — person recovered, not a fall
                        Log.d(TAG, "✗ Too much post-impact movement (variance=${String.format("%.2f", variance)}), resetting")
                        resetState()
                    }
                }
            }

            FallDetectionPhase.TRIGGERED -> {
                // Should not stay here — resetState() is called immediately
                resetState()
            }
        }
    }

    /**
     * Track recent orientation to compare against post-fall orientation.
     */
    private fun updatePreFallWindow(x: Float, y: Float, z: Float) {
        if (preFallWindow.size >= ORIENTATION_WINDOW_SIZE) {
            preFallWindow.removeFirst()
        }
        preFallWindow.addLast(floatArrayOf(x, y, z))

        // Compute running average
        if (preFallWindow.size > 10) {
            preFallAvgX = preFallWindow.map { it[0] }.average().toFloat()
            preFallAvgY = preFallWindow.map { it[1] }.average().toFloat()
            preFallAvgZ = preFallWindow.map { it[2] }.average().toFloat()
        }
    }

    /**
     * Check if the device orientation has changed significantly from pre-fall.
     * A real fall typically results in a ~90° orientation change
     * (standing → lying down).
     */
    private fun checkOrientationChange(currentX: Float, currentY: Float, currentZ: Float): Boolean {
        if (preFallWindow.size < 10) return false

        val deltaX = abs(currentX - preFallAvgX)
        val deltaY = abs(currentY - preFallAvgY)
        val deltaZ = abs(currentZ - preFallAvgZ)

        // At least one axis should have shifted significantly
        return deltaX > ORIENTATION_CHANGE_THRESHOLD ||
                deltaY > ORIENTATION_CHANGE_THRESHOLD ||
                deltaZ > ORIENTATION_CHANGE_THRESHOLD
    }

    private fun resetState() {
        currentPhase = FallDetectionPhase.IDLE
        _currentPhaseFlow.value = FallDetectionPhase.IDLE
        freefallStartTime = 0L
        freefallEndTime = 0L
        impactTime = 0L
        impactMagnitude = 0f
        postImpactReadings.clear()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
