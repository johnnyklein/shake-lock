package com.shakelock

import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Fires [onShake] after [SPIKES] acceleration spikes above the threshold within [WINDOW_MS],
 * passing the strongest g-force of that shake.
 * A single bump (dropping the phone on a table, a hard tap) gives one spike, so it won't trigger.
 */
class ShakeDetector(
    private val thresholdG: () -> Float,
    private val onShake: (peakG: Float) -> Unit,
) {
    private var firstSpikeAt = 0L
    private var lastSpikeAt = 0L
    private var spikes = 0
    private var peakG = 0f

    /** Returns the g-force of this reading (1.0 = lying still). */
    fun onSensorChanged(event: SensorEvent): Float {
        val (x, y, z) = event.values
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (gForce < thresholdG()) return gForce

        val now = SystemClock.elapsedRealtime()
        if (now - firstSpikeAt > WINDOW_MS) {
            firstSpikeAt = now
            spikes = 0
            peakG = 0f
        }
        peakG = maxOf(peakG, gForce)
        if (now - lastSpikeAt < MIN_GAP_MS) return gForce
        lastSpikeAt = now
        if (++spikes >= SPIKES) {
            spikes = 0
            firstSpikeAt = 0
            onShake(peakG)
        }
        return gForce
    }

    private companion object {
        const val SPIKES = 3
        const val MIN_GAP_MS = 80L
        const val WINDOW_MS = 1500L
    }
}
