package com.shakelock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** First start: one step per screen. Permission steps move on by themselves once granted. */
class OnboardingActivity : ComponentActivity() {
    private var state by mutableStateOf(OnboardingState())
    private lateinit var store: LockStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = LockStore(this)
        setContent {
            ShakeLockTheme {
                Onboarding(store, state) {
                    store.onboarded = true
                    finish()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Bubble.hide(this)
        state = OnboardingState(
            canShowBubble = Setup.canShowBubble(this),
            serviceEnabled = Setup.serviceEnabled(this),
            batteryUnrestricted = Setup.batteryUnrestricted(this),
            restricted = Setup.restrictedSettings(this),
            checkedAt = System.currentTimeMillis(),
        )
    }
}

private data class OnboardingState(
    val canShowBubble: Boolean = false,
    val serviceEnabled: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val restricted: Boolean? = null,
    val checkedAt: Long = 0L,
)

private enum class Step { WELCOME, APPS, TIME, BUBBLE, ACCESSIBILITY, BATTERY, DONE }

/** Permission steps that are already done get skipped (in both directions). */
private fun Step.satisfied(state: OnboardingState) = when (this) {
    Step.BUBBLE -> state.canShowBubble
    Step.ACCESSIBILITY -> state.serviceEnabled
    Step.BATTERY -> state.batteryUnrestricted && Setup.brandBattery == null // brands with extra steps always see them
    else -> false
}

@Composable
private fun Onboarding(store: LockStore, state: OnboardingState, onFinish: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(Step.WELCOME) }
    var forward by remember { mutableStateOf(true) }
    fun go(delta: Int) {
        var i = step.ordinal + delta
        while (i in 1 until Step.entries.lastIndex && Step.entries[i].satisfied(state)) i += delta
        if (i in Step.entries.indices) {
            forward = delta > 0
            step = Step.entries[i]
        }
    }
    // Back from Android settings with the permission granted: carry on.
    LaunchedEffect(step, state) {
        if (step.satisfied(state)) go(+1)
    }
    BackHandler(enabled = step != Step.WELCOME) { go(-1) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (step != Step.WELCOME) {
                    IconButton(onClick = { go(-1) }, modifier = Modifier.padding(start = 0.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                LinearProgressIndicator(
                    progress = { step.ordinal / Step.entries.lastIndex.toFloat() },
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.size(48.dp))
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally { it / 4 * dir } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 * dir } + fadeOut())
                },
                modifier = Modifier.weight(1f),
                label = "step",
            ) { current ->
                when (current) {
                    Step.WELCOME -> Page(
                        title = "Caught yourself doomscrolling?",
                        text = "Shake your phone and Airlock locks you out of the app for a few minutes. " +
                            "No timers, no nagging: you decide, right in the moment you notice.",
                        illustration = { WobblingBubble() },
                        primary = "Get started" to { go(+1) },
                    )
                    Step.APPS -> AppsPage(store) { go(+1) }
                    Step.TIME -> TimePage { minutes ->
                        store.lockMinutes = minutes
                        store.lockChosen = true
                        go(+1)
                    }
                    Step.BUBBLE -> {
                        val context = LocalContext.current
                        Page(
                            title = "Want a little guide?",
                            text = "Next we'll open Android's settings. Airlock can show a small tip on top of them, " +
                                "so you know exactly what to tap. It needs \"Display over other apps\" for that.",
                            illustration = { StepIcon(Icons.Filled.Info) },
                            primary = "Allow" to { Setup.openBubblePermission(context) },
                            secondary = "Skip" to { go(+1) },
                        )
                    }
                    Step.ACCESSIBILITY -> {
                        val context = LocalContext.current
                        val restrictedHint = "Tap ⋮ (top right) → Allow restricted settings"
                        if (state.restricted == true) {
                            Page(
                                title = "First, unlock it",
                                text = "Android blocks this switch for apps that aren't from the Play Store. " +
                                    "In App info, tap ⋮ in the top right and choose \"Allow restricted settings\". Then come back.",
                                illustration = { StepIcon(Icons.Filled.Lock) },
                                primary = "Open App info" to { Setup.openAppInfo(context, restrictedHint, "Then come back to Airlock.") },
                            )
                        } else {
                            Page(
                                title = "Turn on Airlock",
                                text = "To know when you're in Instagram, Airlock needs accessibility access. " +
                                    "It only checks which app is open, it never reads what's on your screen.",
                                illustration = { StepIcon(Icons.Filled.Lock) },
                                primary = "Open settings" to { Setup.openAccessibility(context) },
                                secondary = "Switch greyed out?" to {
                                    Setup.openAppInfo(context, restrictedHint, "Then come back and turn Airlock on.")
                                },
                            )
                        }
                    }
                    Step.BATTERY -> {
                        val context = LocalContext.current
                        val brand = Setup.brandBattery
                        Page(
                            title = "Keep it running",
                            text = if (brand != null) {
                                "${brand.brand} phones freeze apps in the background, then shaking does nothing. " +
                                    "Allow Airlock to run, then in App info: ${brand.path}." + (brand.detail?.let { " $it" } ?: "")
                            } else {
                                "Let Airlock run in the background, so it's there when you shake."
                            },
                            illustration = { StepIcon(Icons.Filled.Settings) },
                            primary = if (state.batteryUnrestricted && brand != null) {
                                "Open App info" to { Setup.openAppInfo(context, brand.path, brand.detail) }
                            } else {
                                "Allow" to { Setup.requestBattery(context) }
                            },
                            secondary = (if (brand != null) "Next" else "Skip") to { go(+1) },
                        )
                    }
                    Step.DONE -> {
                        val first = remember { store.blockedApps.firstOrNull() }
                        val label = first?.let { labelOf(LocalContext.current, it) } ?: "a scroll app"
                        Page(
                            title = "You're set",
                            text = "Next time you catch yourself in $label, shake your phone 3 times. That's it.",
                            illustration = { WobblingBubble() },
                            primary = "Start" to onFinish,
                        )
                    }
                }
            }
        }
    }
}

/** Shared layout: picture, title, text, optional content, buttons at the bottom. */
@Composable
private fun Page(
    title: String,
    text: String,
    illustration: @Composable () -> Unit,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(0.6f))
        illustration()
        Spacer(Modifier.height(28.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        content?.invoke(this)
        Spacer(Modifier.weight(1f))
        Button(onClick = primary.second, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text(primary.first, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        if (secondary != null) {
            TextButton(onClick = secondary.second, modifier = Modifier.fillMaxWidth()) { Text(secondary.first) }
        } else {
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun AppsPage(store: LockStore, onNext: () -> Unit) {
    val context = LocalContext.current
    var blocked by remember { mutableStateOf(store.blockedApps) }
    // Most used first, then typical doomscroll apps; the full list is on the Home tab.
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) {
            loadApps(context)
                .sortedWith(compareByDescending<AppEntry> { it.weekMs }.thenByDescending { it.suggested })
                .take(12)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(24.dp))
        Text("Which apps pull you in?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Airlock only listens for a shake while one of these is open. You can change them later.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        val list = apps
        if (list == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.packageName }) { app ->
                    val checked = app.packageName in blocked
                    val toggle = {
                        blocked = if (checked) blocked - app.packageName else blocked + app.packageName
                        store.blockedApps = blocked
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(if (checked) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable(onClick = toggle)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(app.label, modifier = Modifier.weight(1f), fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal)
                        Checkbox(checked = checked, onCheckedChange = { toggle() })
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onNext, enabled = blocked.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text(if (blocked.isEmpty()) "Pick at least one" else "Next", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun TimePage(onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(0.6f))
        Text(
            "How long should a shake lock you out?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "You can change it any time in Settings.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        listOf(
            Triple(1, "A quick nudge", false),
            Triple(5, "Enough to break the loop", true),
            Triple(10, "A proper break", false),
        ).forEach { (minutes, description, recommended) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(MaterialTheme.shapes.medium)
                    // All three look the same: nothing should look pre-selected; the label does the recommending.
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onPick(minutes) }
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text("$minutes min", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(110.dp))
                Column(Modifier.weight(1f)) {
                    Text(description, style = MaterialTheme.typography.bodyLarge)
                    if (recommended) {
                        Text("Recommended", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun StepIcon(icon: ImageVector) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(110.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** The app icon, wobbling like it's being shaken. */
@Composable
private fun WobblingBubble() {
    val wobble by rememberInfiniteTransition(label = "wobble").animateFloat(
        initialValue = -9f,
        targetValue = 9f,
        animationSpec = infiniteRepeatable(tween(260), RepeatMode.Reverse),
        label = "wobble",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val arc = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
            listOf(0.30f, 0.40f).forEach { r ->
                val d = size.minDimension * r * 2
                val topLeft = Offset(center.x - d / 2, center.y - d / 2)
                drawArc(Coral, 150f, 60f, false, topLeft, Size(d, d), style = arc)
                drawArc(Coral, -30f, 60f, false, topLeft, Size(d, d), style = arc)
            }
        }
        Canvas(Modifier.size(110.dp).rotate(wobble)) {
            val s = size.minDimension / 108f
            drawRoundRect(Color(0xFF3B36B8), cornerRadius = androidx.compose.ui.geometry.CornerRadius(26 * s))
            drawCircle(Color.White, 28 * s, Offset(54 * s, 54 * s))
            drawCircle(Color(0xFFE2E0FF), 5.5f * s, Offset(42.5f * s, 42 * s))
            drawCircle(Coral, 6 * s, Offset(54 * s, 50 * s))
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(50.8f * s, 53 * s); lineTo(57.2f * s, 53 * s); lineTo(59 * s, 66 * s); lineTo(49 * s, 66 * s); close()
            }
            drawPath(path, Coral)
        }
    }
}

private fun labelOf(context: android.content.Context, pkg: String) = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrNull()
