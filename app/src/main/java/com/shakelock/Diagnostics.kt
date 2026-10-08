package com.shakelock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Live state of [LockService], shown in the app so you can see why a shake did or didn't work. */
object Diagnostics {
    data class State(
        val serviceRunning: Boolean = false,
        val lastApp: String? = null,
        val lastBlockedApp: String? = null,
        val listening: Boolean = false,
        val sensorEvents: Long = 0,
        val peakG: Float = 0f,
        val shakes: Int = 0,
    )

    val state = MutableStateFlow(State())

    fun update(transform: (State) -> State) = state.update(transform)
}
