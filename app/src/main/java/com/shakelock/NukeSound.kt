package com.shakelock

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Synthesised nuke sounds (no audio files): a falling whistle, a soft boom and a liftoff rumble. Kept quiet on purpose. */
object NukeSound {
    private const val RATE = 44_100

    fun whistle(durationMs: Int) = play(buildWhistle(durationMs))

    fun boom() = play(buildBoom())

    fun launch() = play(buildLaunch())

    private fun play(samples: ShortArray) {
        thread(name = "nuke-sound") {
            runCatching {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.size * 2)
                    .build()
                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep(samples.size * 1000L / RATE + 200)
                track.release()
            }
        }
    }

    /** Pitch slides down like a falling bomb and gets a bit louder as it "approaches". */
    private fun buildWhistle(durationMs: Int): ShortArray {
        val n = RATE * durationMs / 1000
        var phase = 0.0
        return ShortArray(n) { i ->
            val t = i.toDouble() / n
            val vibrato = 1 + 0.012 * sin(2 * PI * 6 * i / RATE)
            val freq = (1900 - 1250 * t.pow(1.4)) * vibrato
            phase += 2 * PI * freq / RATE
            val fadeIn = min(1.0, i / (RATE * 0.4))
            val volume = (0.04 + 0.14 * t) * fadeIn
            val v = volume * (sin(phase) + 0.25 * sin(2 * phase))
            (v * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** Liftoff: rumbling noise that swells, with a rising roar on top. */
    private fun buildLaunch(): ShortArray {
        val n = (RATE * 2.2).toInt()
        val random = Random(11)
        var lowPassed = 0.0
        var phase = 0.0
        return ShortArray(n) { i ->
            val t = i.toDouble() / n
            lowPassed += 0.06 * (random.nextDouble() * 2 - 1 - lowPassed)
            phase += 2 * PI * (90 + 260 * t * t) / RATE
            val swell = min(1.0, t * 3) * (1 - t * t)
            val v = 0.3 * swell * (lowPassed * 5 + 0.3 * sin(phase))
            (v.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** Low rumble: filtered noise plus a falling thump, decaying over ~1.5 s. */
    private fun buildBoom(): ShortArray {
        val n = (RATE * 1.6).toInt()
        val random = Random(7)
        var lowPassed = 0.0
        return ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            lowPassed += 0.04 * (random.nextDouble() * 2 - 1 - lowPassed)
            val thump = sin(2 * PI * (55 - 20 * t) * t) * exp(-t * 5)
            val v = 0.4 * exp(-t * 3) * (lowPassed * 4 + 0.6 * thump)
            (v.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
