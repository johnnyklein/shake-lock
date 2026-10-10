package com.shakelock

import android.content.Context
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Time per app (any app, not just blocked ones), counted by [LockService] from what's in front.
 * Kept per day on the phone only; used to put the apps you actually spend time in at the top.
 */
object AppUsage {
    private const val KEEP_DAYS = 14

    /** Apps people typically doomscroll in, suggested before there's any usage data. */
    val DOOMSCROLL_APPS = setOf(
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.google.android.youtube",
        "com.reddit.frontpage",
        "com.twitter.android",
        "com.instagram.barcelona",
        "com.snapchat.android",
        "com.facebook.katana",
        "tv.twitch.android.app",
        "com.pinterest",
        "com.bereal.ft",
        "xyz.blueskyweb.app",
        "com.ninegag.android.app",
        "com.tellm.android.app",
        "com.linkedin.android",
    )

    private fun prefs(context: Context) = context.getSharedPreferences("usage", Context.MODE_PRIVATE)

    private fun day(millis: Long) = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(millis))

    fun add(context: Context, pkg: String, from: Long, until: Long) {
        val ms = until - from
        if (ms <= 0) return
        val key = "${day(from)}|$pkg"
        val prefs = prefs(context)
        prefs.edit {
            putLong(key, prefs.getLong(key, 0L) + ms)
            // Drop old days now and then.
            val oldest = day(System.currentTimeMillis() - KEEP_DAYS * 24 * 3_600_000L)
            prefs.all.keys.filter { it.substringBefore('|') < oldest }.forEach(::remove)
        }
    }

    /** Milliseconds per package over the last 7 days. */
    fun lastWeek(context: Context): Map<String, Long> {
        val since = day(System.currentTimeMillis() - 6 * 24 * 3_600_000L)
        return prefs(context).all.entries
            .filter { it.key.substringBefore('|') >= since }
            .groupBy({ it.key.substringAfter('|') }, { it.value as? Long ?: 0L })
            .mapValues { (_, values) -> values.sum() }
    }
}
