package com.shakelock

import android.content.Context
import androidx.core.content.edit
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** What a shake locks. */
enum class LockScope { APPS, PHONE }

/** Why a lock started. */
// REENTRY is no longer used, but old stats still contain it.
enum class Trigger { SHAKE, REENTRY, NUKE }

/** Settings and lock state, shared between the UI and [LockService]. */
class LockStore(context: Context) {
    private val prefs = context.getSharedPreferences("shakelock", Context.MODE_PRIVATE)

    var blockedApps: Set<String>
        get() = prefs.getStringSet(KEY_APPS, emptySet())!!.toSet()
        set(value) = prefs.edit { putStringSet(KEY_APPS, value) }

    /** Setting: whether the next shake locks the selected apps or the whole phone. */
    var shakeLocks: LockScope
        get() = LockScope.valueOf(prefs.getString(KEY_SHAKE_LOCKS, LockScope.APPS.name)!!)
        set(value) = prefs.edit { putString(KEY_SHAKE_LOCKS, value.name) }

    /** Lock length when [shakeLocks] is [LockScope.APPS]. */
    var lockMinutes by intPref("lock_minutes", 5)

    /** Set once you've picked a lock time during setup. */
    var lockChosen by boolPref("lock_chosen", false)

    /** Set when the first-start onboarding is finished. */
    var onboarded by boolPref("onboarded", false)

    /** Lock length when [shakeLocks] is [LockScope.PHONE]. */
    var phoneLockMinutes by intPref("phone_lock_minutes", 3)

    /** How hard you have to shake, in g. Lower = more sensitive. */
    var shakeThreshold: Float
        get() = prefs.getFloat(KEY_THRESHOLD, 2.0f)
        set(value) = prefs.edit { putFloat(KEY_THRESHOLD, value) }

    // Smart lock experiments, all off by default.
    var chargeByShaking by boolPref("charge_by_shaking", false)
    var nudge by boolPref("nudge", false)
    var nudgeMinutes by intPref("nudge_minutes", 20)


    /** App offered on the lock screen as the better alternative (e.g. To-Dodo). Always usable. */
    var insteadApp: String?
        get() = prefs.getString(KEY_INSTEAD, null)
        set(value) = prefs.edit {
            putString(KEY_INSTEAD, value)
            putBoolean(KEY_INSTEAD_CHOSEN, true)
        }

    /** Name shown to friends; null until you set up Friends. */
    var cloudName: String?
        get() = prefs.getString(KEY_CLOUD_NAME, null)
        set(value) = prefs.edit { putString(KEY_CLOUD_NAME, value) }

    /** Off = incoming nukes fizzle. */
    var acceptNukes by boolPref("accept_nukes", true)

    /** Who nuked you, for the last lock. */
    val nukedBy: String? get() = prefs.getString(KEY_NUKED_BY, null)

    /** False until the user (or auto-detection) picked an [insteadApp]. */
    val insteadAppChosen get() = prefs.getBoolean(KEY_INSTEAD_CHOSEN, false)

    val lockedUntil get() = prefs.getLong(KEY_UNTIL, 0L)

    val lockedAt get() = prefs.getLong(KEY_LOCKED_AT, 0L)

    /** What the current (or last) lock covers. */
    val activeLock get() = LockScope.valueOf(prefs.getString(KEY_ACTIVE, LockScope.APPS.name)!!)

    val lastLockPkg: String? get() = prefs.getString(KEY_LAST_PKG, null)

    /** How long you'd been scrolling when the last lock started. */
    val lastSessionMs get() = prefs.getLong(KEY_LAST_SESSION, 0L)

    val lastTrigger get() = Trigger.valueOf(prefs.getString(KEY_LAST_TRIGGER, Trigger.SHAKE.name)!!)

    fun isLocked() = remainingMillis() > 0

    fun isPhoneLocked() = isLocked() && activeLock == LockScope.PHONE

    fun remainingMillis() = (lockedUntil - System.currentTimeMillis()).coerceAtLeast(0)

    fun startLock(
        scope: LockScope,
        durationMs: Long,
        pkg: String,
        sessionMs: Long,
        trigger: Trigger,
        nukedBy: String? = null,
    ) = prefs.edit {
        val now = System.currentTimeMillis()
        putString(KEY_ACTIVE, scope.name)
        putLong(KEY_LOCKED_AT, now)
        putLong(KEY_UNTIL, now + durationMs)
        putString(KEY_NUKED_BY, nukedBy)
        putString(KEY_LAST_PKG, pkg)
        putLong(KEY_LAST_SESSION, sessionMs)
        putString(KEY_LAST_TRIGGER, trigger.name)
    }


    private fun boolPref(key: String, default: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getBoolean(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) = prefs.edit { putBoolean(key, value) }
    }

    private fun intPref(key: String, default: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getInt(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) = prefs.edit { putInt(key, value) }
    }

    private companion object {
        const val KEY_APPS = "blocked_apps"
        const val KEY_SHAKE_LOCKS = "shake_locks"
        const val KEY_THRESHOLD = "shake_threshold"
        const val KEY_INSTEAD = "instead_app"
        const val KEY_INSTEAD_CHOSEN = "instead_app_chosen"
        const val KEY_UNTIL = "locked_until"
        const val KEY_LOCKED_AT = "locked_at"
        const val KEY_CLOUD_NAME = "cloud_name"
        const val KEY_NUKED_BY = "nuked_by"
        const val KEY_ACTIVE = "active_lock"
        const val KEY_LAST_PKG = "last_lock_pkg"
        const val KEY_LAST_SESSION = "last_session_ms"
        const val KEY_LAST_TRIGGER = "last_trigger"
    }
}

/** Countdown format, e.g. "4:07". */
fun formatRemaining(millis: Long): String {
    val totalSeconds = (millis + 999) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** Human duration, e.g. "45 s", "34 min", "1 h 12 min". */
fun formatDuration(millis: Long): String {
    val minutes = millis / 60_000
    return when {
        millis < 60_000 -> "${millis / 1000} s"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}
