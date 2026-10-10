package com.shakelock

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val LIFTOFF_MS = 2200
private const val IGNITION = 0.3f // share of the liftoff spent shaking on the pad
private const val ANSWER_TIMEOUT_MS = 6000L

// Their phone whistles for 3 s, then the missile falls for 1.2 s.
private const val IMPACT_AFTER_HIT_MS = 4200L

private val Red = Color(0xFFE5484D)

/** The sender's side: a big red button, a liftoff, then whether it hit. Best enjoyed in the same room. */
class NukeLaunchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        val targetId = intent.getStringExtra(EXTRA_TARGET_ID) ?: return finish()
        val targetName = intent.getStringExtra(EXTRA_TARGET_NAME) ?: "your friend"
        val bonus = intent.getBooleanExtra(EXTRA_BONUS, false)
        setContent {
            ShakeLockTheme {
                LaunchScreen(targetId, targetName, bonus, onClose = ::finish)
            }
        }
    }

    companion object {
        const val EXTRA_TARGET_ID = "target_id"
        const val EXTRA_TARGET_NAME = "target_name"
        const val EXTRA_BONUS = "bonus"
    }
}

private enum class Stage { READY, LIFTOFF, WAITING, HIT, MISSED, NO_ANSWER, FAILED }

@Composable
private fun LaunchScreen(targetId: String, targetName: String, bonus: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(Stage.READY) }
    var message by remember { mutableStateOf<String?>(null) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    val liftoff = remember { Animatable(0f) }
    val puffs = remember { randomPuffs(28) }

    fun launch() {
        stage = Stage.LIFTOFF
        NukeSound.launch()
        rumble(context, durationSteps = 30, strength = 0.6f)
        scope.launch {
            val sendTime = System.currentTimeMillis()
            val sent = runCatching { Cloud.sendNuke(targetId, bonus) }
            liftoff.animateTo(1f, tween(LIFTOFF_MS, easing = LinearEasing))
            val nuke = sent.getOrElse {
                message = it.message?.lineSequence()?.firstOrNull() ?: "Didn't launch"
                stage = Stage.FAILED
                return@launch
            }
            stage = Stage.WAITING
            // Their phone reports right away whether you caught them scrolling.
            var status = "armed"
            while (status == "armed" && System.currentTimeMillis() - sendTime < ANSWER_TIMEOUT_MS) {
                status = runCatching { Cloud.nuke(nuke.id)?.status }.getOrNull() ?: "armed"
                if (status == "armed") delay(400)
            }
            stage = when (status) {
                "hit" -> Stage.HIT
                "expired" -> Stage.MISSED
                else -> Stage.NO_ANSWER
            }
            if (stage == Stage.HIT) {
                // Count down to (roughly) the moment it explodes on their screen.
                val impactAt = sendTime + IMPACT_AFTER_HIT_MS
                while (System.currentTimeMillis() < impactAt) {
                    countdown = ((impactAt - System.currentTimeMillis() + 999) / 1000).toInt()
                    delay(100)
                }
                countdown = 0
                rumble(context, durationSteps = 20, strength = 0.8f)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(NightGradient),
    ) {
        if (stage != Stage.READY) {
            Liftoff(liftoff.value, puffs)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("TARGET", color = Coral, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, fontSize = 13.sp)
            Text(targetName, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            val (headline, sub) = when (stage) {
                Stage.READY -> (if (bonus) "🎁 Secret nuke unlocked" else null) to "Only hits if they're scrolling right now."
                Stage.LIFTOFF, Stage.WAITING -> "🚀 Missile away…" to null
                Stage.HIT -> (if (countdown == 0) "💥 BOOM" else "Direct hit! Impact in ${countdown ?: 4}…") to
                    "They were scrolling. Not anymore."
                Stage.MISSED -> "Missed!" to "$targetName wasn't scrolling. Lucky them."
                Stage.NO_ANSWER -> "No answer" to "Their phone seems to be offline."
                Stage.FAILED -> "Didn't launch" to message
            }
            if (headline != null) {
                Text(headline, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            if (sub != null) {
                Text(sub, color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.weight(1f))
            if (stage == Stage.READY) {
                LaunchButton(onClick = ::launch)
                Spacer(Modifier.height(24.dp))
            }
            Spacer(Modifier.weight(1f))
            if (stage != Stage.LIFTOFF && stage != Stage.WAITING) {
                TextButton(onClick = onClose) {
                    Text(if (stage == Stage.READY) "Cancel" else "Done", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
                }
            }
        }
    }
}

/** Big pulsing red button. */
@Composable
private fun LaunchButton(onClick: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
        Canvas(Modifier.fillMaxSize().scale(pulse)) {
            drawCircle(Red.copy(alpha = 0.25f), size.minDimension / 2)
            drawCircle(Color(0xFF8E1F23), size.minDimension / 2 * 0.86f)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(190.dp)
                .scale(pulse)
                .clip(CircleShape)
                .background(Red)
                .clickable(onClick = onClick),
        ) {
            Text("LAUNCH", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
        }
    }
}

/** Rocket shakes on the pad in a cloud of smoke, then accelerates up and out of the screen. */
@Composable
private fun Liftoff(t: Float, puffs: List<Puff>) {
    Canvas(Modifier.fillMaxWidth().fillMaxSize()) {
        val pad = Offset(size.width / 2, size.height * 0.86f)
        val rocketLength = 92.dp.toPx()
        val ignition = (t / IGNITION).coerceIn(0f, 1f)
        val flight = ((t - IGNITION) / (1 - IGNITION)).coerceIn(0f, 1f)
        val rise = flight * flight * (size.height + rocketLength * 2) // accelerating
        val jitter = if (flight == 0f) (Random.nextFloat() * 2 - 1) * 3.dp.toPx() * ignition else 0f
        // Nose points up: tip is at the top.
        val tip = Offset(pad.x + jitter, pad.y - rocketLength - rise)

        // Launch smoke rolling out sideways from the pad
        val smoke = (t * 1.4f).coerceIn(0f, 1f)
        val clear = ((t - 0.75f) / 0.25f).coerceIn(0f, 1f)
        val placed = puffs.map { puff ->
            val spread = smoke * puff.distance * size.width * 0.7f
            PlacedPuff(
                center = Offset(pad.x + cos(puff.angle) * spread, pad.y + sin(puff.angle).coerceAtMost(0.2f) * spread * 0.25f),
                radius = (18.dp.toPx() + 50.dp.toPx() * smoke) * puff.size,
                shade = puff.shade,
                fire = (1 - smoke * 2).coerceIn(0f, 1f),
            )
        }
        drawMissile(tip, angle = 180f, p = t, flameScale = 0.4f + 0.6f * ignition + flight)
        drawComicCloud(placed, alpha = smoke.coerceAtMost(1f) * (1 - clear))
        // Launch pad
        drawCircle(Color.White.copy(alpha = 0.15f), 60.dp.toPx(), pad.copy(y = pad.y + 30.dp.toPx()), style = Stroke(4.dp.toPx()))
    }
}
