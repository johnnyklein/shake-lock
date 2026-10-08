package com.shakelock

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin
import kotlin.random.Random

private const val DURATION_MS = 3200
private const val IMPACT = 0.38f // when the missile hits, as a fraction of the animation

private val Flash = Color(0xFFFFF4D6)
private val Fire = Color(0xFFFF8A3D)
private val Smoke = Color(0xFF8A7F86)

/** A little missile drops in, hits, flash + shockwave + mushroom cloud. Calls [onDone] at the end. */
@Composable
fun NukeAnimation(sender: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val t = remember { Animatable(0f) }
    val shakeSeed = remember { Random.nextInt() }

    LaunchedEffect(Unit) {
        t.animateTo(IMPACT, tween((DURATION_MS * IMPACT).toInt(), easing = LinearEasing))
        vibrate(context)
        t.animateTo(1f, tween((DURATION_MS * (1 - IMPACT)).toInt(), easing = LinearEasing))
        onDone()
    }

    val p = t.value
    val after = ((p - IMPACT) / (1 - IMPACT)).coerceIn(0f, 1f) // 0..1 after impact
    // Screen shake right after impact, fading out.
    val shake = if (p > IMPACT) (1 - after) * 18f else 0f
    val shakeRandom = Random(shakeSeed + (p * 60).toInt())

    Box(
        Modifier
            .fillMaxWidth()
            .height(520.dp)
            .offset { IntOffset((shakeRandom.nextFloat() * 2 - 1).times(shake).dp.roundToPx(), (shakeRandom.nextFloat() * 2 - 1).times(shake).dp.roundToPx()) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(520.dp)) {
            val ground = size.height * 0.62f
            if (p < IMPACT) {
                drawMissile(Offset(size.width / 2, -60f + (ground + 60f) * (p / IMPACT)), p)
            } else {
                drawMushroom(Offset(size.width / 2, ground), after)
                // Shockwave ring
                drawCircle(
                    color = Flash.copy(alpha = (1 - after) * 0.8f),
                    radius = size.width * 0.9f * after,
                    center = Offset(size.width / 2, ground),
                    style = Stroke(width = 14f * (1 - after) + 2f),
                )
                // White flash
                val flash = (1 - after * 4).coerceIn(0f, 1f)
                if (flash > 0) drawRect(Flash.copy(alpha = flash))
            }
        }
        if (after > 0.25f) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            ) {
                Text("NUKED", color = Coral, fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
                Spacer(Modifier.height(4.dp))
                Text("by $sender", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun DrawScope.drawMissile(tip: Offset, p: Float) {
    val w = 26.dp.toPx()
    val h = 80.dp.toPx()
    val wobble = sin(p * 40) * 3f
    val x = tip.x + wobble
    // Flame (above, since it's falling nose-down)
    val flicker = 0.75f + 0.25f * sin(p * 90)
    drawOval(
        brush = Brush.verticalGradient(listOf(Color.Transparent, Fire, Color(0xFFFFE08A)), startY = tip.y - h - 50.dp.toPx() * flicker, endY = tip.y - h),
        topLeft = Offset(x - w * 0.35f, tip.y - h - 50.dp.toPx() * flicker),
        size = Size(w * 0.7f, 50.dp.toPx() * flicker),
    )
    // Fins
    val fins = Path().apply {
        moveTo(x - w / 2, tip.y - h + 6.dp.toPx())
        lineTo(x - w, tip.y - h - 4.dp.toPx())
        lineTo(x - w / 2, tip.y - h + 20.dp.toPx())
        moveTo(x + w / 2, tip.y - h + 6.dp.toPx())
        lineTo(x + w, tip.y - h - 4.dp.toPx())
        lineTo(x + w / 2, tip.y - h + 20.dp.toPx())
    }
    drawPath(fins, Color(0xFFB8B5C8))
    // Body
    drawRoundRect(
        color = Color(0xFFE6E4EE),
        topLeft = Offset(x - w / 2, tip.y - h),
        size = Size(w, h - 18.dp.toPx()),
        cornerRadius = CornerRadius(6.dp.toPx()),
    )
    // Stripe
    drawRect(Coral, Offset(x - w / 2, tip.y - h * 0.55f), Size(w, 8.dp.toPx()))
    // Nose cone, pointing down
    val nose = Path().apply {
        moveTo(x - w / 2, tip.y - 18.dp.toPx() - 1)
        lineTo(x + w / 2, tip.y - 18.dp.toPx() - 1)
        lineTo(x, tip.y)
        close()
    }
    drawPath(nose, Color(0xFFE5484D))
}

private fun DrawScope.drawMushroom(base: Offset, a: Float) {
    val grow = (a * 1.6f).coerceAtMost(1f)
    val fade = if (a > 0.7f) 1 - (a - 0.7f) / 0.3f * 0.5f else 1f
    val color = lerp(Fire, Smoke, a)
    val stemW = 46.dp.toPx() * grow
    val stemH = 150.dp.toPx() * grow
    // Ground dust
    drawOval(
        color = color.copy(alpha = 0.8f * fade),
        topLeft = Offset(base.x - 130.dp.toPx() * grow, base.y - 22.dp.toPx() * grow),
        size = Size(260.dp.toPx() * grow, 44.dp.toPx() * grow),
    )
    // Stem
    drawRoundRect(
        color = color.copy(alpha = fade),
        topLeft = Offset(base.x - stemW / 2, base.y - stemH),
        size = Size(stemW, stemH),
        cornerRadius = CornerRadius(stemW / 2),
    )
    // Cap: a few overlapping puffs
    val capY = base.y - stemH
    val r = 54.dp.toPx() * grow
    listOf(-1.3f to 0.15f, -0.6f to -0.35f, 0f to -0.5f, 0.6f to -0.35f, 1.3f to 0.15f, 0f to 0.1f).forEach { (dx, dy) ->
        drawCircle(lerp(Color(0xFFFFC46B), Smoke, a).copy(alpha = fade), r, Offset(base.x + dx * r, capY + dy * r))
    }
}

private fun vibrate(context: android.content.Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 80, 200, 80, 120), -1))
}
