package com.shakelock

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val FALL_MS = 1200
private const val BLAST_MS = 4600
private const val PUFFS = 64
private const val CLEAR_START = 0.6f // smoke starts clearing
private const val CLEAR_END = 0.88f

private val Flash = Color(0xFFFFF4D6)
private val Fire = Color(0xFFFF8A3D)
private val NightTop = Color(0xFF0F0E2E)
private val NightBottom = Color(0xFF3D2F8F)

// Comic smoke: flat greys with a darker outline around the whole cloud.
private val SmokeOutline = Color(0xFF55505E)
private val SmokeFills = listOf(Color(0xFFE4E1EA), Color(0xFFD2CED9), Color(0xFFBEB9C7))
private val FireFills = listOf(Color(0xFFFFE08A), Color(0xFFFFB347), Fire)

/**
 * See-through screen: the missile falls over whatever app is open, then a comic smoke cloud
 * wipes it away, clears to "LOCKED" and hands over to the lock screen.
 */
class NukeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        // No escaping mid-flight.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        val sender = intent.getStringExtra(EXTRA_SENDER) ?: "A friend"
        setContent {
            NukeStrike(
                sender = sender,
                onImpact = {
                    NukeSound.boom()
                    rumble(this)
                },
                onDone = {
                    startActivity(
                        Intent(this, BlockedActivity::class.java)
                            .putExtra(BlockedActivity.EXTRA_PACKAGE, intent.getStringExtra(EXTRA_PACKAGE))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    @Suppress("DEPRECATION")
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    finish()
                },
            )
        }
    }

    companion object {
        const val EXTRA_SENDER = "sender"
        const val EXTRA_PACKAGE = "package"
    }
}

/** One puff of a comic smoke cloud. */
class Puff(val angle: Float, val distance: Float, val size: Float, val delay: Float, val shade: Int)

fun randomPuffs(count: Int, random: Random = Random) = List(count) {
    Puff(
        angle = random.nextFloat() * 2 * PI.toFloat(),
        distance = random.nextFloat(),
        size = 0.6f + random.nextFloat() * 0.8f,
        delay = random.nextFloat() * 0.12f,
        shade = random.nextInt(SmokeFills.size),
    )
}

@Composable
private fun NukeStrike(sender: String, onImpact: () -> Unit, onDone: () -> Unit) {
    val fall = remember { Animatable(0f) }
    val blast = remember { Animatable(0f) }
    val shakeSeed = remember { Random.nextInt() }
    val puffs = remember { randomPuffs(PUFFS) }

    LaunchedEffect(Unit) {
        fall.animateTo(1f, tween(FALL_MS, easing = FastOutLinearInEasing))
        onImpact()
        blast.animateTo(1f, tween(BLAST_MS, easing = LinearEasing))
        onDone()
    }

    val a = blast.value
    val exploded = fall.value >= 1f
    val clear = ((a - CLEAR_START) / (CLEAR_END - CLEAR_START)).coerceIn(0f, 1f)
    val shake = if (exploded) (1 - a / CLEAR_START).coerceAtLeast(0f).let { it * it } * 20f else 0f
    val shakeRandom = Random(shakeSeed + (a * 80).toInt())

    Box(Modifier.fillMaxSize()) {
        // Background never moves, so the shake can't reveal the app underneath.
        if (exploded) {
            Canvas(Modifier.fillMaxSize()) { drawRect(Brush.verticalGradient(listOf(NightTop, NightBottom))) }
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .offset {
                    IntOffset(
                        ((shakeRandom.nextFloat() * 2 - 1) * shake).dp.roundToPx(),
                        ((shakeRandom.nextFloat() * 2 - 1) * shake).dp.roundToPx(),
                    )
                },
        ) {
            val ground = Offset(size.width / 2, size.height * 0.55f)
            if (!exploded) {
                // Only the missile: the app underneath stays visible.
                val start = Offset(size.width * 0.8f, -120f)
                val tip = Offset(start.x + (ground.x - start.x) * fall.value, start.y + (ground.y - start.y) * fall.value)
                drawMissile(tip, angle = 18f, p = fall.value)
            } else {
                drawBlast(ground, a, clear, puffs)
            }
        }
        if (exploded && clear > 0f) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center).alpha(clear),
            ) {
                Text("LOCKED", color = Coral, fontSize = 56.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
                Spacer(Modifier.height(6.dp))
                Text("Nuked by $sender", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Flash, then a comic cloud that rolls slowly outward over the whole screen and clears. */
private fun DrawScope.drawBlast(ground: Offset, a: Float, clear: Float, puffs: List<Puff>) {
    val reach = maxOf(size.width, size.height) * 0.8f
    val placed = puffs.mapNotNull { puff ->
        val local = ((a - puff.delay) / 0.7f).coerceIn(0f, 1f)
        if (local <= 0f) return@mapNotNull null
        val eased = 1 - (1 - local) * (1 - local) * (1 - local) // slow ease-out
        val center = Offset(
            ground.x + cos(puff.angle) * puff.distance * reach * eased,
            ground.y + sin(puff.angle) * puff.distance * reach * eased * 0.85f,
        )
        val radius = (30.dp.toPx() + 130.dp.toPx() * eased) * puff.size * (1 - clear * 0.85f)
        PlacedPuff(center, radius, puff.shade, fire = (1 - local * 3).coerceIn(0f, 1f))
    }
    drawComicCloud(placed, alpha = 1 - clear)

    // Shockwave ring
    val ring = (a / 0.3f).coerceIn(0f, 1f)
    if (ring < 1f) {
        drawCircle(
            color = Flash.copy(alpha = (1 - ring) * 0.9f),
            radius = reach * 1.4f * ring,
            center = ground,
            style = Stroke(width = 20f * (1 - ring) + 3f),
        )
    }
    // White flash right at impact
    val flash = (1 - a * 10).coerceIn(0f, 1f)
    if (flash > 0) drawRect(Flash.copy(alpha = flash))
}

class PlacedPuff(val center: Offset, val radius: Float, val shade: Int, val fire: Float = 0f)

/**
 * Cartoon cloud: every outline circle first, then every fill on top, so the outline only shows
 * around the outside of the merged cloud. [PlacedPuff.fire] tints a puff from smoke towards fire.
 */
fun DrawScope.drawComicCloud(puffs: List<PlacedPuff>, alpha: Float) {
    if (alpha <= 0f) return
    val outline = 3.dp.toPx()
    puffs.forEach { drawCircle(SmokeOutline, it.radius + outline, it.center, alpha) }
    puffs.forEach { drawCircle(lerp(SmokeFills[it.shade], FireFills[it.shade], it.fire), it.radius, it.center, alpha) }
    // Little highlight bump on each puff for the comic look
    puffs.forEach {
        drawCircle(
            Color.White.copy(alpha = 0.35f * (1 - it.fire)),
            it.radius * 0.45f,
            Offset(it.center.x - it.radius * 0.3f, it.center.y - it.radius * 0.35f),
            alpha,
        )
    }
}

/** Missile with its nose at [tip], tilted [angle] degrees (0 = nose down), flame behind it. */
fun DrawScope.drawMissile(tip: Offset, angle: Float, p: Float, flameScale: Float = 1f) {
    val w = 30.dp.toPx()
    val h = 92.dp.toPx()
    val noseH = 22.dp.toPx()
    rotate(angle, pivot = tip) {
        val x = tip.x
        val flameH = 60.dp.toPx() * flameScale * (0.75f + 0.25f * sin(p * 120))
        if (flameH > 0f) {
            drawOval(
                brush = Brush.verticalGradient(
                    listOf(Color.Transparent, Fire, Color(0xFFFFE08A)),
                    startY = tip.y - h - flameH,
                    endY = tip.y - h,
                ),
                topLeft = Offset(x - w * 0.35f, tip.y - h - flameH),
                size = Size(w * 0.7f, flameH),
            )
        }
        val fins = Path().apply {
            moveTo(x - w / 2, tip.y - h + 6.dp.toPx())
            lineTo(x - w, tip.y - h - 6.dp.toPx())
            lineTo(x - w / 2, tip.y - h + 24.dp.toPx())
            close()
            moveTo(x + w / 2, tip.y - h + 6.dp.toPx())
            lineTo(x + w, tip.y - h - 6.dp.toPx())
            lineTo(x + w / 2, tip.y - h + 24.dp.toPx())
            close()
        }
        drawPath(fins, Color(0xFF9C98B0))
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(Color(0xFFBDB9CC), Color.White, Color(0xFFBDB9CC)), startX = x - w / 2, endX = x + w / 2),
            topLeft = Offset(x - w / 2, tip.y - h),
            size = Size(w, h - noseH),
            cornerRadius = CornerRadius(6.dp.toPx()),
        )
        drawRect(Coral, Offset(x - w / 2, tip.y - h * 0.55f), Size(w, 9.dp.toPx()))
        val nose = Path().apply {
            moveTo(x - w / 2, tip.y - noseH - 1)
            lineTo(x + w / 2, tip.y - noseH - 1)
            lineTo(x, tip.y)
            close()
        }
        drawPath(nose, Color(0xFFE5484D))
    }
}

/** ~3 s of rumble: strong at first, slowly dying down, a bit uneven like a real quake. */
fun rumble(context: Context, durationSteps: Int = 60, strength: Float = 1f) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    val timings = LongArray(durationSteps) { 50L }
    val amplitudes = IntArray(durationSteps) { i ->
        val fade = 1f - i / durationSteps.toFloat()
        (255 * strength * fade * (0.7f + 0.3f * Random.nextFloat())).toInt().coerceIn(1, 255)
    }
    if (vibrator.hasAmplitudeControl()) {
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    } else {
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 100, 700, 100, 500, 100, 300), -1))
    }
}
