package com.shakelock

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telecom.TelecomManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tracks the foreground app. While a blocked app is open it listens for a shake;
 * a shake (or opening a blocked app during a lock) puts the lock screen on top of it.
 * During a whole-phone lock every app except the phone app is covered, including the launcher.
 *
 * It also times how long you spend in blocked apps, for the stats, the nudge and the comeback limit.
 */
class LockService : AccessibilityService(), SensorEventListener {
    private lateinit var store: LockStore
    private lateinit var stats: StatsLog
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var listening = false
    private var currentPackage: String? = null
    private val shakeDetector = ShakeDetector({ store.shakeThreshold }, ::onShake)
    private val handler = Handler(Looper.getMainLooper())
    private val checkForeground = Runnable(::onForegroundMaybeChanged)
    private val tick = Runnable(::onTick)
    private var sensorEventCount = 0L

    // Time in blocked apps
    private var segmentPkg: String? = null // blocked app currently in front, if any
    private var segmentStart = 0L
    private var sessionStart = 0L // start of the scrolling session; spans quick app switches
    private var lastBlockedLeftAt = 0L
    private var nextNudgeAtMs = 0L // session length at which the next nudge fires
    private var reentryUsedMs = 0L // time in blocked apps since the lock ending at [reentryForLock]
    private var reentryForLock = 0L

    // "Keep shaking = longer lock"
    private var chargingPkg: String? = null
    private var chargeStart = 0L
    private var chargeLastSpike = 0L
    private var chargeExtraMinutes = 0

    // Nukes from friends: wait here until you open a blocked app (or they expire)
    private val pendingNukes = mutableMapOf<Long, Nuke>()
    private val handledNukes = mutableSetOf<Long>()
    private var friendNames = mapOf<String, String>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                chargingPkg?.let { finishCharging(it) }
                // Screen off counts as leaving the app.
                updateSegment(null)
                setListening(false)
                currentPackage = null
            } else {
                scheduleCheck()
                startNukes()
            }
        }
    }

    override fun onServiceConnected() {
        store = LockStore(this)
        stats = StatsLog(this)
        sensorManager = getSystemService(SensorManager::class.java)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        Diagnostics.update { it.copy(serviceRunning = true) }
        Cloud.inboxStarter = ::startNukes
        startNukes()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = scheduleCheck()

    // Don't trust the event's package: launchers (MIUI especially) fire stray events while
    // another app is open. Wait for things to settle, then look at which window has focus.
    private fun scheduleCheck() {
        handler.removeCallbacks(checkForeground)
        handler.postDelayed(checkForeground, SETTLE_MS)
    }

    private fun onForegroundMaybeChanged() {
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val pkg = foregroundPackage() ?: return
        if (pkg == currentPackage && !store.isLocked()) return
        val inBlockedApp = pkg in store.blockedApps
        Log.d(TAG, "foreground: $pkg blocked=$inBlockedApp")

        currentPackage = pkg
        Diagnostics.update { it.copy(lastApp = pkg, lastBlockedApp = if (inBlockedApp) pkg else it.lastBlockedApp) }
        val keepOut = when {
            store.isPhoneLocked() -> pkg !in alwaysAllowed()
            store.isLocked() -> inBlockedApp
            else -> false
        }
        if (keepOut) kickOut(pkg)
        updateSegment(if (inBlockedApp && !keepOut) pkg else null)
        tryDetonate()
        setListening((inBlockedApp && !store.isLocked()) || chargingPkg != null)
    }

    /** Starts/ends timing of a blocked app being in front. */
    private fun updateSegment(pkg: String?) {
        if (pkg == segmentPkg) return
        val now = System.currentTimeMillis()
        segmentPkg?.let { previous ->
            reentryUsedMs = reentryUsed(now)
            stats.add(UseEvent(segmentStart, previous, now))
            lastBlockedLeftAt = now
        }
        segmentPkg = pkg
        handler.removeCallbacks(tick)
        if (pkg != null) {
            if (sessionStart == 0L || now - lastBlockedLeftAt > SESSION_GAP_MS) {
                sessionStart = now
                nextNudgeAtMs = store.nudgeMinutes * 60_000L
            }
            segmentStart = now
            handler.postDelayed(tick, TICK_MS)
        }
    }

    /** Runs every few seconds while a blocked app is in front. */
    private fun onTick() {
        val pkg = segmentPkg ?: return
        val now = System.currentTimeMillis()
        val sessionMs = now - sessionStart

        if (store.nudge && sessionMs >= nextNudgeAtMs) {
            nextNudgeAtMs += store.nudgeMinutes * 60_000L
            nudge(pkg, sessionMs)
        }

        tryDetonate()
        if (segmentPkg == null) return

        val sinceLockEnded = now - store.lockedUntil
        if (store.reentryLimit && store.lastTrigger != Trigger.NUKE && store.lockedUntil > 0 &&
            sinceLockEnded in 0 until REENTRY_WINDOW_MS &&
            reentryUsed(now) >= store.reentryMinutes * 60_000L
        ) {
            lock(pkg, Trigger.REENTRY, extraMinutes = 0)
            return
        }
        handler.postDelayed(tick, TICK_MS)
    }

    /** Time spent in blocked apps since the last lock ended, including the app open right now. */
    private fun reentryUsed(now: Long): Long {
        val lockEnd = store.lockedUntil
        if (reentryForLock != lockEnd) {
            reentryForLock = lockEnd
            reentryUsedMs = 0
        }
        val current = if (segmentPkg != null) (now - maxOf(segmentStart, lockEnd)).coerceAtLeast(0) else 0
        return reentryUsedMs + current
    }

    private fun onShake(@Suppress("UNUSED_PARAMETER") peakG: Float) {
        val pkg = currentPackage ?: return
        if (chargingPkg != null || pkg !in store.blockedApps || store.isLocked()) return
        if (store.chargeByShaking) startCharging(pkg) else lock(pkg, Trigger.SHAKE, extraMinutes = 0)
    }

    /** First shake: cover the app with the charging screen and keep counting while you shake. */
    private fun startCharging(pkg: String) {
        val now = System.currentTimeMillis()
        chargingPkg = pkg
        chargeStart = now
        chargeLastSpike = now
        chargeExtraMinutes = 0
        Charging.state.value = Charging.State(active = true, baseMinutes = lockMinutes(store.shakeLocks, 0), pulse = now)
        vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        kickOut(pkg)
    }

    private fun onChargeReading(gForce: Float) {
        val pkg = chargingPkg ?: return
        val now = System.currentTimeMillis()
        if (gForce >= store.shakeThreshold * CHARGE_SPIKE_FACTOR) {
            val pulse = now - chargeLastSpike > PULSE_MIN_GAP_MS
            chargeLastSpike = now
            val extra = ((now - chargeStart) / 1000).toInt()
            if (extra > chargeExtraMinutes) {
                chargeExtraMinutes = extra
                vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
            }
            if (pulse || extra > Charging.state.value.extraMinutes) {
                Charging.state.value = Charging.state.value.copy(extraMinutes = chargeExtraMinutes, pulse = now)
            }
        } else if (now - chargeLastSpike > CHARGE_IDLE_MS) {
            finishCharging(pkg)
        }
    }

    private fun finishCharging(pkg: String) {
        chargingPkg = null
        lock(pkg, Trigger.SHAKE, chargeExtraMinutes)
        Charging.state.value = Charging.State()
    }

    private fun lock(pkg: String, trigger: Trigger, extraMinutes: Int) {
        val scope = store.shakeLocks
        startLock(pkg, trigger, scope, lockMinutes(scope, extraMinutes) * 60_000L, nukedBy = null)
    }

    private fun startLock(pkg: String, trigger: Trigger, scope: LockScope, durationMs: Long, nukedBy: String?) {
        val now = System.currentTimeMillis()
        val sessionMs = if (sessionStart > 0) now - sessionStart else 0L
        Log.d(TAG, "lock $pkg: $trigger, $scope, ${durationMs / 1000}s, session ${sessionMs / 1000}s")

        updateSegment(null)
        stats.add(LockEvent(now, pkg, scope, durationMs, sessionMs, trigger))
        store.startLock(scope, durationMs, pkg, sessionMs, trigger, nukedBy)
        sessionStart = 0
        Diagnostics.update { it.copy(shakes = it.shakes + 1) }
        vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        kickOut(pkg)
        setListening(false)
    }

    /** Starts listening for nukes once Friends is set up; also picks up ones sent while offline. */
    private fun startNukes() {
        if (!Cloud.configured || store.cloudName == null) return
        Cloud.listen(::onNukeArrived)
        Cloud.scope.launch {
            runCatching {
                friendNames = Cloud.friends().associate { it.id to it.name }
                Cloud.armedForMe(System.currentTimeMillis() - NUKE_TTL_MS).forEach(::onNukeArrived)
            }.onFailure { Log.w(TAG, "nuke catch-up failed", it) }
        }
    }

    private fun onNukeArrived(nuke: Nuke) {
        if (nuke.id in handledNukes || nuke.id in pendingNukes || nuke.status != "armed") return
        Log.d(TAG, "incoming nuke ${nuke.id} from ${nuke.sender}")
        if (!store.acceptNukes) {
            handledNukes += nuke.id
            Cloud.scope.launch { Cloud.reportNuke(nuke.id, "expired") }
            return
        }
        pendingNukes[nuke.id] = nuke
        if (nuke.sender !in friendNames) {
            // New friend: look up their name, but don't let a slow network delay the impact.
            Cloud.scope.launch {
                withTimeoutOrNull(NAME_LOOKUP_MS) {
                    runCatching { friendNames = Cloud.friends().associate { it.id to it.name } }
                }
                tryDetonate()
            }
        } else {
            tryDetonate()
        }
    }

    /** A pending nuke lands as soon as you're in a blocked app and not locked already. */
    private fun tryDetonate() {
        if (pendingNukes.isEmpty()) return
        val now = System.currentTimeMillis()
        pendingNukes.values.filter { now - it.createdAtMillis > NUKE_TTL_MS }.forEach { expired ->
            pendingNukes.remove(expired.id)
            handledNukes += expired.id
            Cloud.scope.launch { Cloud.reportNuke(expired.id, "expired") }
        }
        val pkg = segmentPkg ?: return
        if (store.isLocked() || chargingPkg != null) return
        val nuke = pendingNukes.values.minByOrNull { it.createdAtMillis } ?: return
        pendingNukes.remove(nuke.id)
        handledNukes += nuke.id
        Cloud.scope.launch { Cloud.reportNuke(nuke.id, "hit") }
        startLock(pkg, Trigger.NUKE, LockScope.APPS, NUKE_LOCK_MS, friendNames[nuke.sender] ?: "A friend")
    }

    /** Base duration, doubled per earlier lock today (escalation), plus minutes earned by shaking longer. */
    private fun lockMinutes(scope: LockScope, extraMinutes: Int): Int {
        var minutes = if (scope == LockScope.PHONE) store.phoneLockMinutes else store.lockMinutes
        if (store.escalate) {
            minutes *= 1 shl stats.locksToday().coerceAtMost(6)
        }
        minutes += extraMinutes
        return minutes.coerceAtMost(if (scope == LockScope.PHONE) MAX_PHONE_LOCK_MIN else MAX_APP_LOCK_MIN)
    }

    private fun nudge(pkg: String, sessionMs: Long) {
        stats.add(NudgeEvent(System.currentTimeMillis(), pkg))
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 120, 40), -1))
        Toast.makeText(this, "${formatDuration(sessionMs)} on ${appLabel(pkg)}. Shake if you want out.", Toast.LENGTH_LONG).show()
    }

    private fun kickOut(pkg: String) {
        startActivity(
            Intent(this, BlockedActivity::class.java)
                .putExtra(BlockedActivity.EXTRA_PACKAGE, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        // If the system refused to show the lock screen, fall back to the home screen.
        handler.postDelayed({
            if (foregroundPackage() == pkg) performGlobalAction(GLOBAL_ACTION_HOME)
        }, KICK_FALLBACK_MS)
    }

    private fun setListening(listen: Boolean) {
        if (listen == listening) return
        val sensor = accelerometer ?: return
        if (listen) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            sensorEventCount = 0
            Diagnostics.update { it.copy(sensorEvents = 0, peakG = 0f) }
        } else {
            sensorManager.unregisterListener(this)
        }
        listening = listen
        Log.d(TAG, "listening=$listen")
        Diagnostics.update { it.copy(listening = listen) }
    }

    /** Apps that stay usable during a whole-phone lock: this app, the "instead" app and anything needed to make a call. */
    private fun alwaysAllowed(): Set<String> {
        val dialer = getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        return setOfNotNull(packageName, dialer, store.insteadApp) + EMERGENCY_PACKAGES
    }

    /**
     * Package of the focused app window (ignores the keyboard, notification shade and other system windows).
     * Installed web apps (like To-Dodo) run inside Chrome, so their window is matched to the
     * "instead" app by its title.
     */
    private fun foregroundPackage(): String? {
        val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val window = appWindows.firstOrNull { it.isFocused }
            ?: appWindows.firstOrNull { it.isActive }
            ?: return null
        val instead = store.insteadApp
        if (instead != null && window.title?.toString() == appLabel(instead)) return instead
        return window.root?.packageName?.toString()
    }

    private fun appLabel(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun vibrate(effect: VibrationEffect) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        vibrator.vibrate(effect)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gForce = shakeDetector.onSensorChanged(event)
        onChargeReading(gForce)
        // ~50 readings a second: only push to the status screen occasionally or on a new peak.
        val count = ++sensorEventCount
        if (count % 50 == 0L || gForce > Diagnostics.state.value.peakG) {
            Diagnostics.update { it.copy(sensorEvents = count, peakG = maxOf(it.peakG, gForce)) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        updateSegment(null)
        setListening(false)
        unregisterReceiver(screenReceiver)
        Diagnostics.update { it.copy(serviceRunning = false) }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ShakeLock"
        const val SETTLE_MS = 250L
        const val KICK_FALLBACK_MS = 800L
        const val TICK_MS = 10_000L
        const val SESSION_GAP_MS = 60_000L // away for longer than this = new session
        const val REENTRY_WINDOW_MS = 60 * 60_000L // comeback limit applies for an hour after a lock
        const val CHARGE_SPIKE_FACTOR = 0.8f // still counts as shaking, slightly below the trigger threshold
        const val CHARGE_IDLE_MS = 800L // stopped shaking for this long = lock is set
        const val PULSE_MIN_GAP_MS = 120L
        const val NUKE_LOCK_MS = 30_000L
        const val NAME_LOOKUP_MS = 1_500L
        const val NUKE_TTL_MS = 10 * 60_000L // a nuke waits this long for you to open a blocked app
        const val MAX_APP_LOCK_MIN = 60
        const val MAX_PHONE_LOCK_MIN = 10
        val EMERGENCY_PACKAGES = setOf(
            "com.android.phone",
            "com.android.incallui",
            "com.android.server.telecom",
            "com.android.emergency",
            "com.google.android.dialer",
        )
    }
}
