package com.shakelock

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Shown on top of a blocked app (or everything, during a whole-phone lock). */
class BlockedActivity : ComponentActivity() {
    private var appLabel by mutableStateOf("")
    private lateinit var store: LockStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark background regardless of theme, so light status bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        store = LockStore(this)
        appLabel = labelFor(intent.getStringExtra(EXTRA_PACKAGE) ?: store.lastLockPkg)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // Back does nothing while the whole phone is locked.
            override fun handleOnBackPressed() {
                if (!store.isPhoneLocked()) goHome()
            }
        })
        val insteadLabel = store.insteadApp
            ?.takeIf { packageManager.getLaunchIntentForPackage(it) != null }
            ?.let { labelFor(it) }
        setContent {
            ShakeLockTheme {
                BlockedScreen(
                    appLabel = appLabel,
                    store = store,
                    insteadLabel = insteadLabel,
                    onDone = ::goHome,
                    onEmergencyCall = ::openDialer,
                    onOpenInstead = ::openInstead,
                    onUnlockEarly = ::unlockEarly,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        appLabel = labelFor(intent.getStringExtra(EXTRA_PACKAGE))
    }

    private fun labelFor(pkg: String?): String {
        if (pkg == null) return "This app"
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun openDialer() {
        startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openInstead() {
        val pkg = store.insteadApp ?: return
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun unlockEarly() {
        store.endLockEarly()
        StatsLog(this).add(EarlyUnlockEvent(System.currentTimeMillis()))
        val pkg = store.lastLockPkg
        if (store.activeLock == LockScope.APPS && pkg != null) {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        finish()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}

private const val UNLOCK_PHRASE = "I choose to keep scrolling"
private const val UNLOCK_WAIT_SECONDS = 30
private const val BREATH_MS = 4000

private val OnNight = Color.White
private val OnNightMuted = Color.White.copy(alpha = 0.7f)
private val RingTrack = Color.White.copy(alpha = 0.12f)

@Composable
private fun BlockedScreen(
    appLabel: String,
    store: LockStore,
    insteadLabel: String?,
    onDone: () -> Unit,
    onEmergencyCall: () -> Unit,
    onOpenInstead: () -> Unit,
    onUnlockEarly: () -> Unit,
) {
    val phoneLock = store.activeLock == LockScope.PHONE
    val total = (store.lockedUntil - store.lockedAt).coerceAtLeast(1)
    val remaining by produceState(store.remainingMillis()) {
        while (value > 0) {
            delay(250)
            value = store.remainingMillis()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(NightGradient)
            .safeDrawingPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (remaining > 0) {
                Text(
                    if (phoneLock) "PHONE LOCKED" else "LOCKED",
                    color = Coral,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (phoneLock) "Time for a real break" else "$appLabel can wait",
                    color = OnNight,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                val why = when {
                    store.lastTrigger == Trigger.REENTRY -> "Your ${store.reentryMinutes} minutes back are up."
                    store.lastSessionMs >= 60_000 -> "You were on $appLabel for ${formatDuration(store.lastSessionMs)}."
                    else -> null
                }
                if (why != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(why, color = OnNightMuted, fontSize = 16.sp, textAlign = TextAlign.Center)
                }

                Spacer(Modifier.height(36.dp))
                CountdownRing(remaining = remaining, total = total)
                Spacer(Modifier.height(40.dp))

                if (insteadLabel != null) {
                    Button(
                        onClick = onOpenInstead,
                        colors = ButtonDefaults.buttonColors(containerColor = OnNight, contentColor = Color(0xFF26236E)),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Open $insteadLabel instead", fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.height(12.dp))
                }
                if (phoneLock) {
                    OutlinedButton(
                        onClick = onEmergencyCall,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = OnNight),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Phone / emergency call") }
                }
                if (store.earlyUnlock) {
                    Spacer(Modifier.height(20.dp))
                    EarlyUnlock(onUnlockEarly)
                }
            } else {
                Text("Lock is over", color = OnNight, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("Welcome back. Go easy.", color = OnNightMuted, fontSize = 16.sp)
                Spacer(Modifier.height(40.dp))
                Button(
                    onClick = onDone,
                    colors = ButtonDefaults.buttonColors(containerColor = OnNight, contentColor = Color(0xFF26236E)),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("OK", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** Ring that empties as the lock runs out, with a slow breathing circle inside. */
@Composable
private fun CountdownRing(remaining: Long, total: Long) {
    var inhale by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(BREATH_MS.toLong())
            inhale = !inhale
        }
    }
    val breath by animateFloatAsState(
        targetValue = if (inhale) 1f else 0.55f,
        animationSpec = tween(BREATH_MS),
        label = "breath",
    )
    val progress by animateFloatAsState(
        targetValue = remaining.toFloat() / total,
        animationSpec = tween(250),
        label = "progress",
    )

    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawCircle(
                color = Color.White.copy(alpha = 0.06f + 0.06f * breath),
                radius = size.minDimension / 2 * 0.78f * breath,
            )
            drawArc(RingTrack, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(
                color = Coral,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatRemaining(remaining), color = OnNight, fontSize = 60.sp, fontWeight = FontWeight.Bold)
            Text(if (inhale) "Breathe in…" else "Breathe out…", color = OnNightMuted, fontSize = 15.sp)
        }
    }
}

/** Getting back in early is possible, but takes a 30 s wait and typing a sentence. */
@Composable
private fun EarlyUnlock(onUnlock: () -> Unit) {
    var stage by remember { mutableIntStateOf(0) } // 0 = button, 1 = waiting, 2 = typing
    var secondsLeft by remember { mutableIntStateOf(UNLOCK_WAIT_SECONDS) }
    var typed by remember { mutableStateOf("") }

    LaunchedEffect(stage) {
        if (stage == 1) {
            secondsLeft = UNLOCK_WAIT_SECONDS
            while (secondsLeft > 0) {
                delay(1000)
                secondsLeft--
            }
            stage = 2
        }
    }

    when (stage) {
        0 -> TextButton(onClick = { stage = 1 }) { Text("Unlock early", color = OnNightMuted) }
        1 -> Text(
            "Still want in? Sit with it for a moment… $secondsLeft s",
            color = OnNightMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Type \"$UNLOCK_PHRASE\" to unlock", color = OnNightMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = OnNight,
                    unfocusedTextColor = OnNight,
                    focusedBorderColor = OnNight,
                    unfocusedBorderColor = OnNightMuted,
                    cursorColor = OnNight,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = onUnlock,
                enabled = typed.trim().equals(UNLOCK_PHRASE, ignoreCase = true),
            ) { Text("Unlock", color = if (typed.isNotBlank()) OnNight else OnNightMuted) }
        }
    }
}
