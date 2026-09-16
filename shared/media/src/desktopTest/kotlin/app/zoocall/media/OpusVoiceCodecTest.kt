package app.zoocall.media

import app.zoocall.core.model.VoiceClip
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpusVoiceCodecTest {

    @Test
    fun encodeDecodeRoundTripKeepsFramesAndIsCompact() {
        val encoder = OpusVoiceCodec.Encoder()
        val levels = ArrayList<Float>()
        val seconds = 3
        val frames = seconds * 1000 / OpusVoiceCodec.FRAME_MS
        for (f in 0 until frames) {
            val pcm = ShortArray(OpusVoiceCodec.FRAME_SAMPLES) { i ->
                val t = (f * OpusVoiceCodec.FRAME_SAMPLES + i).toDouble() / OpusVoiceCodec.SAMPLE_RATE
                (sin(2 * PI * 440 * t) * 8_000).toInt().toShort()
            }
            encoder.encode(pcm)
            levels += OpusVoiceCodec.rms(pcm)
        }
        val audio = encoder.bytes()
        // ~24 kbps → about 9 KB for 3 s; must stay far below the 900 KB limit for 2 minutes.
        assertTrue(audio.size in 3_000..20_000, "unexpected size ${audio.size}")

        var decoded = 0
        var energy = 0.0
        OpusVoiceCodec.forEachFrame(audio) { _, pcm ->
            decoded++
            energy += OpusVoiceCodec.rms(pcm)
        }
        assertEquals(frames, decoded)
        assertTrue(energy / decoded > 0.05, "decoded audio should not be silent")

        val waveform = OpusVoiceCodec.waveform(levels)
        assertEquals(VoiceClip.WAVEFORM_BARS, waveform.size)
    }

    @Test
    fun malformedContainerStopsWithoutThrowing() {
        var frames = 0
        OpusVoiceCodec.forEachFrame(byteArrayOf(0x7f, 0x7f, 1, 2, 3)) { _, _ -> frames++ }
        assertEquals(0, frames)
    }
}
