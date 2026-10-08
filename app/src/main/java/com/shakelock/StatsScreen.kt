package com.shakelock

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class DayStats(val start: Long, val label: String, val useMs: Long, val escapes: Int)

private data class WeekStats(
    val days: List<DayStats>,
    val useMs: Long,
    val escapes: Int,
    val autoRelocks: Int,
    val lockedMs: Long,
    val earlyUnlocks: Int,
    val nudges: Int,
    val avgSessionBeforeShakeMs: Long?,
    val medianComebackMs: Long?,
    val topApps: List<Pair<String, Long>>,
    val escapeStreak: Int,
    val cardsAnswered: Int,
    val cardsRight: Int,
    val wordsKnown: Int,
)

@Composable
fun StatsScreen(stats: StatsLog) {
    val context = LocalContext.current
    val week by produceState<WeekStats?>(null) {
        value = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            summarize(stats.read(since = addDays(startOfDay(now), -STREAK_DAYS)), now, context)
        }
    }
    val data = week
    if (data == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                BigNumber("Escapes this week", data.escapes.toString(), Modifier.weight(1f))
                BigNumber("Blocked apps today", formatDuration(data.days.last().useMs), Modifier.weight(1f))
                BigNumber("Streak", "${data.escapeStreak} d", Modifier.weight(1f))
            }
        }

        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Time in blocked apps", fontWeight = FontWeight.Bold)
                    Text(
                        "Last 7 days · ${formatDuration(data.useMs)} total · × = escapes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    WeekChart(data.days)
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("This week", fontWeight = FontWeight.Bold)
                    Fact("Average scroll before you shook", data.avgSessionBeforeShakeMs?.let(::formatDuration) ?: "–")
                    Fact("Typical time until you went back", data.medianComebackMs?.let(::formatDuration) ?: "–")
                    Fact("Time spent locked", formatDuration(data.lockedMs))
                    Fact("Re-locks from comeback limit", data.autoRelocks.toString())
                    Fact("Early unlocks", data.earlyUnlocks.toString())
                    Fact("Nudges", data.nudges.toString())
                    Fact(
                        "Spanish cards answered",
                        if (data.cardsAnswered == 0) "0"
                        else "${data.cardsAnswered} (${data.cardsRight * 100 / data.cardsAnswered}% right)",
                    )
                    Fact("Spanish words you know", data.wordsKnown.toString())
                }
            }
        }

        if (data.topApps.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Where the time went", fontWeight = FontWeight.Bold)
                        data.topApps.forEach { (label, ms) -> Fact(label, formatDuration(ms)) }
                    }
                }
            }
        }

        item {
            Text(
                "Time in the app you're using right now is added once you leave it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun BigNumber(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WeekChart(days: List<DayStats>) {
    val max = days.maxOf { it.useMs }.coerceAtLeast(1)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.fillMaxWidth().height(170.dp),
    ) {
        days.forEachIndexed { index, day ->
            val today = index == days.lastIndex
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                Text(shortDuration(day.useMs), style = MaterialTheme.typography.labelSmall)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((BAR_MAX_DP * day.useMs / max).coerceAtLeast(2f).dp)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(
                            if (today) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                        )
                )
                Text(
                    day.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    if (day.escapes > 0) "${day.escapes}×" else " ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

private fun summarize(events: List<StatEvent>, now: Long, context: Context): WeekStats {
    val today = startOfDay(now)
    val weekStart = addDays(today, -6)
    val uses = events.filterIsInstance<UseEvent>()
    val locks = events.filterIsInstance<LockEvent>()
    val earlyUnlocks = events.filterIsInstance<EarlyUnlockEvent>()
    val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())

    fun useBetween(from: Long, to: Long) = uses.sumOf { (minOf(it.until, to) - maxOf(it.at, from)).coerceAtLeast(0) }
    fun escapesBetween(from: Long, to: Long) = locks.count { it.trigger == Trigger.SHAKE && it.at >= from && it.at < to }

    // When a lock really ended: its planned end, or an early unlock during it.
    fun lockEnd(lock: LockEvent): Long {
        val planned = lock.at + lock.minutes * 60_000L
        val early = earlyUnlocks.firstOrNull { it.at in lock.at..planned }?.at
        return minOf(early ?: planned, now)
    }

    val days = (6 downTo 0).map { back ->
        val start = addDays(today, -back)
        val end = addDays(start, 1)
        DayStats(start, dayFormat.format(Date(start)).take(2), useBetween(start, end), escapesBetween(start, end))
    }

    val weekLocks = locks.filter { it.at >= weekStart }
    val weekShakes = weekLocks.filter { it.trigger == Trigger.SHAKE }
    val comebacks = weekLocks.mapNotNull { lock ->
        val end = lockEnd(lock)
        uses.firstOrNull { it.at >= end }?.let { it.at - end }
    }.sorted()

    val pm = context.packageManager
    fun label(pkg: String) = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
    val topApps = uses.filter { it.at >= weekStart }
        .groupBy { it.pkg }
        .mapValues { (_, list) -> list.sumOf { it.until - it.at } }
        .entries.sortedByDescending { it.value }
        .take(5)
        .map { label(it.key) to it.value }

    val weekCards = events.filterIsInstance<CardEvent>().filter { it.at >= weekStart }

    // Days in a row with at least one escape, counting back from today (or yesterday, if none yet today).
    var streak = 0
    var day = if (escapesBetween(today, addDays(today, 1)) > 0) today else addDays(today, -1)
    while (streak < STREAK_DAYS && escapesBetween(day, addDays(day, 1)) > 0) {
        streak++
        day = addDays(day, -1)
    }

    return WeekStats(
        days = days,
        useMs = days.sumOf { it.useMs },
        escapes = weekShakes.size,
        autoRelocks = weekLocks.count { it.trigger == Trigger.REENTRY },
        lockedMs = weekLocks.sumOf { lockEnd(it) - it.at },
        earlyUnlocks = earlyUnlocks.count { it.at >= weekStart },
        nudges = events.count { it is NudgeEvent && it.at >= weekStart },
        avgSessionBeforeShakeMs = weekShakes.map { it.sessionMs }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()?.toLong(),
        medianComebackMs = comebacks.getOrNull(comebacks.size / 2),
        topApps = topApps,
        escapeStreak = streak,
        cardsAnswered = weekCards.size,
        cardsRight = weekCards.count { it.correct },
        wordsKnown = Srs(context).knownCount(),
    )
}

private fun shortDuration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "0"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h${minutes % 60}"
    }
}

private const val BAR_MAX_DP = 110f
private const val STREAK_DAYS = 60
