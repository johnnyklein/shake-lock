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
import android.os.PowerManager
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

    // "Keep shaking = longer lock"
    private var chargingPkg: String? = null
    private var chargeStart = 0L
    private var chargeLastSpike = 0L
    private var chargeExtraMinutes = 0

    // Nukes from friends: wait here until you open a blocked app (or they expire)
    private val pendingNukes = mutableMapOf<Long, Nuke>()
    private val handledNukes = mutableSetOf<Long>()
    private var friendNames = mapOf<String, String>()
    private var nukeIncoming = false

    // Time per app (all apps), for sorting the app list by what you actually use
    private var usagePkg: String? = null
    private var usageStart = 0L
    private val homePackage by lazy {
        packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
    }
    private var nukeShowingUntil = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                chargingPkg?.let { finishCharging(it) }
                // Screen off counts as leaving the app.
                trackUsage(null)
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
        Bubble.hide(this)
        Cloud.inboxStarter = ::startNukes
        startNukes()
        // We may have been (re)started while an app is already open: look now, not at the next app switch.
        scheduleCheck()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = scheduleCheck()

    // Don't trust the event's package: launchers (MIUI especially) fire stray events while
    // another app is open. Wait for things to settle, then look at which window has focus.
    private fun scheduleCheck() {
        handler.removeCallbacks(checkForeground)
        handler.postDelayed(checkForeground, SETTLE_MS)
    }

    private fun onForegroundMaybeChanged() {
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            // Unlock (face/fingerprint) can still be animating. Scrolling fires no events,
            // so keep checking while the screen is on instead of losing track of the app.
            if (getSystemService(PowerManager::class.java).isInteractive) {
                handler.postDelayed(checkForeground, KEYGUARD_RETRY_MS)
            }
            return
        }
        val pkg = foregroundPackage() ?: return
        if (pkg == currentPackage && !store.isLocked()) return
        val inBlockedApp = pkg in store.blockedApps
        Log.d(TAG, "foreground: $pkg blocked=$inBlockedApp")

        currentPackage = pkg
        trackUsage(pkg)
        Diagnostics.update { it.copy(lastApp = pkg, lastBlockedApp = if (inBlockedApp) pkg else it.lastBlockedApp) }
        val keepOut = when {
            store.isPhoneLocked() -> pkg !in alwaysAllowed()
            store.isLocked() -> inBlockedApp
            else -> false
        }
        // While the nuke animation plays, it hands over to the lock screen itself.
        if (keepOut && System.currentTimeMillis() > nukeShowingUntil) kickOut(pkg)
        updateSegment(if (inBlockedApp && !keepOut) pkg else null)
        tryDetonate()
        setListening((inBlockedApp && !store.isLocked()) || chargingPkg != null)
    }

    /** Adds up time per app in front; the launcher and Airlock itself don't count. */
    private fun trackUsage(pkg: String?) {
        val counted = pkg?.takeIf { it != packageName && it != homePackage }
        if (counted == usagePkg) return
        val now = System.currentTimeMillis()
        usagePkg?.let { AppUsage.add(this, it, usageStart, now) }
        usagePkg = counted
        usageStart = now
    }

    /** Starts/ends timing of a blocked app being in front. */
    private fun updateSegment(pkg: String?) {
        if (pkg == segmentPkg) return
        val now = System.currentTimeMillis()
        segmentPkg?.let { previous ->
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
        handler.postDelayed(tick, TICK_MS)
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

    private fun startLock(
        pkg: String,
        trigger: Trigger,
        scope: LockScope,
        durationMs: Long,
        nukedBy: String?,
        showLockScreen: Boolean = true,
    ) {
        val now = System.currentTimeMillis()
        val sessionMs = if (sessionStart > 0) now - sessionStart else 0L
        Log.d(TAG, "lock $pkg: $trigger, $scope, ${durationMs / 1000}s, session ${sessionMs / 1000}s")

        updateSegment(null)
        stats.add(LockEvent(now, pkg, scope, durationMs, sessionMs, trigger))
        store.startLock(scope, durationMs, pkg, sessionMs, trigger, nukedBy)
        sessionStart = 0
        Diagnostics.update { it.copy(shakes = it.shakes + 1) }
        if (showLockScreen) {
            vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
            kickOut(pkg)
        }
        setListening(false)
    }

    /** Starts listening for nukes once Friends is set up; also picks up ones sent while offline. */
    private fun startNukes() {
        if (!Cloud.configured || store.cloudName == null) return
        Cloud.listen(::onNukeArrived)
        Cloud.scope.launch {
            runCatching {
                friendNames = Cloud.friends().associate { it.id to it.name }
                // Only just-sent ones (e.g. the connection was re-established a moment ago).
                Cloud.armedForMe(System.currentTimeMillis() - NUKE_CATCH_UP_MS).forEach(::onNukeArrived)
            }.onFailure { Log.w(TAG, "nuke catch-up failed", it) }
        }
    }

    /** Nukes are live only: it lands if you're scrolling a blocked app right now, otherwise it misses. */
    private fun onNukeArrived(nuke: Nuke) {
        if (nuke.id in handledNukes || nuke.id in pendingNukes || nuke.status != "armed") return
        Log.d(TAG, "incoming nuke ${nuke.id} from ${nuke.sender}")
        // Don't trust what we last saw: look at what's on screen right now.
        currentPackage = null
        onForegroundMaybeChanged()
        if (!canBeHit()) {
            Log.d(
                TAG,
                "nuke ${nuke.id} missed: accept=${store.acceptNukes} inBlockedApp=${segmentPkg != null} " +
                    "foreground=$currentPackage locked=${store.isLocked()} charging=${chargingPkg != null} incoming=$nukeIncoming",
            )
            miss(nuke)
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

    private fun canBeHit() = store.acceptNukes && segmentPkg != null && !store.isLocked() && chargingPkg == null && !nukeIncoming

    private fun miss(nuke: Nuke) {
        pendingNukes.remove(nuke.id)
        handledNukes += nuke.id
        Cloud.scope.launch { Cloud.reportNuke(nuke.id, "expired") }
    }

    /** Lands the pending nuke (after the sender's name lookup), or misses if you stopped scrolling meanwhile. */
    private fun tryDetonate() {
        if (pendingNukes.isEmpty()) return
        if (!canBeHit()) {
            pendingNukes.values.toList().forEach(::miss)
            return
        }
        val pkg = segmentPkg ?: return
        val nuke = pendingNukes.values.minByOrNull { it.createdAtMillis } ?: return
        pendingNukes.values.filter { it != nuke }.forEach(::miss)
        pendingNukes.remove(nuke.id)
        handledNukes += nuke.id
        Cloud.scope.launch { Cloud.reportNuke(nuke.id, "hit") }
        val sender = friendNames[nuke.sender] ?: "A friend"

        // You hear it coming before you see it: whistle now, missile after NUKE_WARNING_MS.
        nukeIncoming = true
        NukeSound.whistle(NUKE_WARNING_MS.toInt() + NUKE_FALL_MS)
        handler.postDelayed({
            nukeIncoming = false
            nukeShowingUntil = System.currentTimeMillis() + NUKE_ANIMATION_MS
            startLock(pkg, Trigger.NUKE, LockScope.APPS, NUKE_LOCK_MS, sender, showLockScreen = false)
            startActivity(
                Intent(this, NukeActivity::class.java)
                    .putExtra(NukeActivity.EXTRA_SENDER, sender)
                    .putExtra(NukeActivity.EXTRA_PACKAGE, pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }, NUKE_WARNING_MS)
    }

    /** Chosen duration plus minutes earned by shaking longer. */
    private fun lockMinutes(scope: LockScope, extraMinutes: Int): Int {
        val minutes = (if (scope == LockScope.PHONE) store.phoneLockMinutes else store.lockMinutes) + extraMinutes
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
        trackUsage(null)
        updateSegment(null)
        setListening(false)
        unregisterReceiver(screenReceiver)
        Diagnostics.update { it.copy(serviceRunning = false) }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ShakeLock"
        const val SETTLE_MS = 250L
        const val KEYGUARD_RETRY_MS = 1_000L
        const val KICK_FALLBACK_MS = 800L
        const val TICK_MS = 10_000L
        const val SESSION_GAP_MS = 60_000L // away for longer than this = new session
        const val CHARGE_SPIKE_FACTOR = 0.8f // still counts as shaking, slightly below the trigger threshold
        const val CHARGE_IDLE_MS = 800L // stopped shaking for this long = lock is set
        const val PULSE_MIN_GAP_MS = 120L
        const val NUKE_LOCK_MS = 30_000L
        const val NAME_LOOKUP_MS = 1_500L
        const val NUKE_WARNING_MS = 3_000L // whistle before the missile shows up
        const val NUKE_FALL_MS = 1_200 // missile flight, matches NukeActivity
        const val NUKE_ANIMATION_MS = 7_000L // fall + blast + a little slack
        const val NUKE_CATCH_UP_MS = 15_000L
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
