package com.shakelock

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * "Keep shaking = longer lock": after the first shake the lock screen shows a charging animation
 * while [LockService] counts how long you keep shaking. Shared in-process between the two.
 */
object Charging {
    data class State(
        val active: Boolean = false,
        val baseMinutes: Int = 0,
        val extraMinutes: Int = 0,
        /** Time of the latest shake spike, so the animation can pulse with it. */
        val pulse: Long = 0,
    )

    val state = MutableStateFlow(State())
}
