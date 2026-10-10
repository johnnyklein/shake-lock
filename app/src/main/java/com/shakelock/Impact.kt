package com.shakelock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val WEEK_MS = 7 * 24 * 3_600_000L
private const val SESSION_GAP_MS = 60_000L // back in a blocked app within a minute = same session
private const val MIN_SESSION_MS = 30_000L // shorter peeks don't count as a scroll session
private const val WON_BACK_CAP_MS = 60 * 60_000L // one shake counts at most an hour of time away

/** What Airlock achieved this week. */
data class Impact(
    val escapes: Int,
    /** Time away from blocked apps after each shake, until you opened one again (capped per shake). */
    val wonBackMs: Long,
    val avgSessionMs: Long?,
    val lastWeekAvgSessionMs: Long?,
)

fun computeImpact(events: List<StatEvent>, now: Long): Impact {
    val weekStart = now - WEEK_MS
    val uses = events.filterIsInstance<UseEvent>().sortedBy { it.at }

    // Merge back-to-back use (switching between blocked apps, quick interruptions) into sessions.
    val sessions = mutableListOf<LongArray>()
    for (use in uses) {
        val last = sessions.lastOrNull()
        if (last != null && use.at - last[1] <= SESSION_GAP_MS) last[1] = maxOf(last[1], use.until)
        else sessions += longArrayOf(use.at, use.until)
    }
    fun averageSession(from: Long, to: Long) = sessions
        .filter { it[0] in from until to }
        .map { it[1] - it[0] }
        .filter { it >= MIN_SESSION_MS }
        .takeIf { it.isNotEmpty() }
        ?.average()?.toLong()

    val shakes = events.filterIsInstance<LockEvent>().filter { it.trigger == Trigger.SHAKE && it.at >= weekStart }
    val wonBack = shakes.sumOf { shake ->
        val back = uses.firstOrNull { it.at > shake.at }?.at ?: now
        (back - shake.at).coerceIn(0, WON_BACK_CAP_MS)
    }
    return Impact(
        escapes = shakes.size,
        wonBackMs = wonBack,
        avgSessionMs = averageSession(weekStart, now),
        lastWeekAvgSessionMs = averageSession(weekStart - WEEK_MS, weekStart),
    )
}

/** Three small numbers under the status card: the case for keeping Airlock. */
@Composable
fun ImpactStrip(stats: StatsLog, refreshKey: Any?) {
    val impact by produceState<Impact?>(null, refreshKey) {
        value = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            computeImpact(stats.read(since = now - 2 * WEEK_MS), now)
        }
    }
    val data = impact ?: return
    if (data.escapes == 0) {
        Text(
            "Shake once in a blocked app. Your wins show up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        return
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        ImpactTile(formatDuration(data.wonBackMs), "won back", null, Modifier.weight(1f))
        val avg = data.avgSessionMs
        val previous = data.lastWeekAvgSessionMs
        val trend = if (avg != null && previous != null && previous - avg >= 60_000) "↓ ${formatDuration(previous - avg)} vs last week"
        else if (avg != null && previous != null && avg - previous >= 60_000) "↑ ${formatDuration(avg - previous)} vs last week"
        else null
        ImpactTile(avg?.let(::formatDuration) ?: "–", "avg. scroll", trend, Modifier.weight(1f))
        ImpactTile(data.escapes.toString(), if (data.escapes == 1) "escape" else "escapes", null, Modifier.weight(1f))
    }
    Text(
        "This week",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 6.dp),
    )
}

@Composable
private fun ImpactTile(value: String, label: String, note: String?, modifier: Modifier) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = if (note.startsWith("↓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}
