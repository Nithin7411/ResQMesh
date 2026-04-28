package com.stella.smartsosrelay.services

import android.view.KeyEvent

class VolumeGestureHelper(private val onGestureDetected: () -> Unit) {

    // Pattern: DOWN DOWN UP UP UP DOWN
    private val PATTERN = listOf(
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN
    )

    private val sequence = mutableListOf<Pair<Int, Long>>()
    private val TIMEOUT_MS = 4000L  // 4 seconds to complete the full sequence

    fun onKeyPress(keyCode: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP) {
            return false
        }

        val now = System.currentTimeMillis()

        // Remove old entries outside the timeout window
        sequence.removeAll { now - it.second > TIMEOUT_MS }

        sequence.add(Pair(keyCode, now))

        // Check if the last N presses match the pattern
        if (sequence.size >= PATTERN.size) {
            val recent = sequence.takeLast(PATTERN.size).map { it.first }
            if (recent == PATTERN) {
                sequence.clear()
                onGestureDetected()
                return true
            }
        }

        return false
    }

    fun reset() {
        sequence.clear()
    }
}
