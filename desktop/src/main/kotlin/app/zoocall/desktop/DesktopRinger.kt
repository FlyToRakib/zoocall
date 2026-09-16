package app.zoocall.desktop

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Synthesized ring and ringback tones, so no sound files (or network) are needed.
 * Ringtones: classic (two 480+620 Hz bursts per 3 s), gentle (soft C–E chord) and bright (quick high triple).
 * Ringback: 440+480 Hz, 2 s on / 4 s off.
 */
class DesktopRinger(
    /** The speaker chosen in Zoocall's audio settings; null plays on the system default. */
    private val speakerName: () -> String? = { null },
) {
    @Volatile private var generation = 0
    @Volatile private var previewGeneration = -1

    private class Tone(val pattern: List<Pair<Int, Boolean>>, val f1: Double, val f2: Double, val volume: Double)

    /** [style]: a built-in ringtone id; "" or unknown ids play the classic tone, "silent" plays nothing. */
    fun startRinging(style: String = "") {
        val tone = ringtone(style)
        if (tone == null) stop() else play(tone, repeat = true)
    }

    /** One cycle of a ringtone, for Settings. */
    fun preview(style: String) {
        val tone = ringtone(style) ?: return
        previewGeneration = play(tone, repeat = false)
    }

    /** Stops a preview but never a ringing call. */
    fun stopPreview() {
        if (previewGeneration == generation) stop()
    }

    fun startRingback() {
        play(Tone(listOf(2_000 to true, 4_000 to false), 440.0, 480.0, 0.15), repeat = true)
    }

    /** Soft double beep every few seconds while a second call waits (edge case C2). */
    fun startCallWaiting() {
        play(Tone(listOf(200 to true, 150 to false, 200 to true, 3_500 to false), 440.0, 440.0, 0.12), repeat = true)
    }

    /** A short two-note chime for a knock. Plays alongside anything else. */
    fun playKnock() {
        thread(isDaemon = true, name = "zoocall-chime") {
            val line = openLine() ?: return@thread
            try {
                writeTone(line, Tone(listOf(140 to true, 60 to false), 660.0, 660.0, 0.2), 0L)
                writeTone(line, Tone(listOf(220 to true, 200 to false), 880.0, 880.0, 0.2), 0L)
                line.drain()
            } finally {
                line.close()
            }
        }
    }

    /** Audible confirmation for the global mute shortcut: falling tone for mute, rising for unmute. */
    fun playMuteCue(muted: Boolean) {
        thread(isDaemon = true, name = "zoocall-chime") {
            val line = openLine() ?: return@thread
            val (first, second) = if (muted) 660.0 to 440.0 else 440.0 to 660.0
            try {
                writeTone(line, Tone(listOf(90 to true, 30 to false), first, first, 0.15), 0L)
                writeTone(line, Tone(listOf(110 to true, 120 to false), second, second, 0.15), 0L)
                line.drain()
            } finally {
                line.close()
            }
        }
    }

    /** Push-to-talk chirp: rising when talking starts, falling when it stops. */
    fun playPttChirp(start: Boolean) {
        thread(isDaemon = true, name = "zoocall-chime") {
            val line = openLine() ?: return@thread
            val (first, second) = if (start) 1_200.0 to 1_600.0 else 1_600.0 to 1_200.0
            try {
                writeTone(line, Tone(listOf(50 to true), first, first, 0.14), 0L)
                writeTone(line, Tone(listOf(70 to true, 80 to false), second, second, 0.14), 0L)
                line.drain()
            } finally {
                line.close()
            }
        }
    }

    /** Three-note chime when a desk intercom line opens; plays whatever the sound settings, since the mic is live. */
    fun playIntercomChime() {
        thread(isDaemon = true, name = "zoocall-chime") {
            val line = openLine() ?: return@thread
            try {
                for (frequency in listOf(784.0, 988.0, 1_175.0)) {
                    writeTone(line, Tone(listOf(110 to true, 20 to false), frequency, frequency, 0.18), 0L)
                }
                line.drain()
            } finally {
                line.close()
            }
        }
    }

    fun stop() {
        generation++
    }

    private fun ringtone(style: String): Tone? = when (style) {
        SILENT -> null
        "gentle" -> Tone(listOf(900 to true, 2_100 to false), 523.25, 659.25, 0.22)
        "bright" -> Tone(listOf(150 to true, 90 to false, 150 to true, 90 to false, 150 to true, 1_800 to false), 1_046.5, 1_318.5, 0.25)
        else -> Tone(listOf(400 to true, 200 to false, 400 to true, 2_000 to false), 480.0, 620.0, 0.35)
    }

    private fun openLine(): SourceDataLine? {
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
        return runCatching {
            app.zoocall.media.JavaSoundDevices.sourceLine(format, runCatching { speakerName() }.getOrNull()).apply {
                open(format)
                start()
            }
        }.getOrNull()
    }

    /** Writes one pass of the pattern; returns the sample phase to continue from. */
    private fun writeTone(line: SourceDataLine, tone: Tone, startPhase: Long, keepGoing: () -> Boolean = { true }): Long {
        var phase = startPhase
        for ((durationMs, on) in tone.pattern) {
            if (!keepGoing()) break
            val samples = SAMPLE_RATE * durationMs / 1000
            val buffer = ByteArray(samples * 2)
            for (i in 0 until samples) {
                val t = (phase + i).toDouble() / SAMPLE_RATE
                val value = if (on) ((sin(2 * PI * tone.f1 * t) + sin(2 * PI * tone.f2 * t)) * 0.5 * tone.volume * Short.MAX_VALUE).toInt() else 0
                buffer[i * 2] = value.toByte()
                buffer[i * 2 + 1] = (value shr 8).toByte()
            }
            phase += samples
            line.write(buffer, 0, buffer.size)
        }
        return phase
    }

    /** Returns the generation this playback belongs to; a later [play] or [stop] ends it. */
    private fun play(tone: Tone, repeat: Boolean): Int {
        val myGeneration = ++generation
        thread(isDaemon = true, name = "zoocall-ringer") {
            val line = openLine() ?: return@thread
            try {
                var phase = 0L
                do {
                    phase = writeTone(line, tone, phase) { generation == myGeneration }
                } while (repeat && generation == myGeneration)
                if (generation == myGeneration) line.drain()
            } finally {
                line.flush()
                line.close()
            }
        }
        return myGeneration
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val SILENT = "silent"

        /** Built-in ringtone ids; the first is the default. */
        val STYLES = listOf("classic", "gentle", "bright")
    }
}
