package com.shakelock

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Calendar

sealed interface StatEvent {
    val at: Long
}

data class LockEvent(
    override val at: Long,
    val pkg: String,
    val scope: LockScope,
    val minutes: Int,
    val sessionMs: Long,
    val trigger: Trigger,
) : StatEvent

/** Time spent in a blocked app, from [at] to [until]. */
data class UseEvent(override val at: Long, val pkg: String, val until: Long) : StatEvent

data class EarlyUnlockEvent(override val at: Long) : StatEvent

data class NudgeEvent(override val at: Long, val pkg: String) : StatEvent

/** Append-only event log (one JSON object per line) that the stats screen is computed from. */
class StatsLog(context: Context) {
    private val file = File(context.filesDir, "events.jsonl")

    fun add(event: StatEvent) {
        val json = when (event) {
            is LockEvent -> JSONObject()
                .put("t", "lock").put("pkg", event.pkg).put("scope", event.scope.name)
                .put("min", event.minutes).put("session", event.sessionMs).put("trigger", event.trigger.name)
            is UseEvent -> JSONObject().put("t", "use").put("pkg", event.pkg).put("until", event.until)
            is EarlyUnlockEvent -> JSONObject().put("t", "early")
            is NudgeEvent -> JSONObject().put("t", "nudge").put("pkg", event.pkg)
        }.put("at", event.at)
        synchronized(FILE_LOCK) { file.appendText("$json\n") }
    }

    fun read(since: Long = 0L): List<StatEvent> {
        val lines = synchronized(FILE_LOCK) { if (file.exists()) file.readLines() else emptyList() }
        return lines
            .mapNotNull { runCatching { parse(JSONObject(it)) }.getOrNull() }
            .filter { it.at >= since }
            .sortedBy { it.at }
    }

    fun locksToday() = read(startOfDay(System.currentTimeMillis())).count { it is LockEvent }

    private fun parse(json: JSONObject): StatEvent? {
        val at = json.getLong("at")
        return when (json.getString("t")) {
            "lock" -> LockEvent(
                at, json.getString("pkg"), LockScope.valueOf(json.getString("scope")),
                json.getInt("min"), json.getLong("session"), Trigger.valueOf(json.getString("trigger")),
            )
            "use" -> UseEvent(at, json.getString("pkg"), json.getLong("until"))
            "early" -> EarlyUnlockEvent(at)
            "nudge" -> NudgeEvent(at, json.getString("pkg"))
            else -> null
        }
    }

    private companion object {
        val FILE_LOCK = Any()
    }
}

fun startOfDay(millis: Long): Long = Calendar.getInstance().run {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    timeInMillis
}

fun addDays(dayStart: Long, days: Int): Long = Calendar.getInstance().run {
    timeInMillis = dayStart
    add(Calendar.DAY_OF_YEAR, days)
    timeInMillis
}
