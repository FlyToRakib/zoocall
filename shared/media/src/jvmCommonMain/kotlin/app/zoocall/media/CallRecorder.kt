package app.zoocall.media

import app.zoocall.core.model.Logger
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Records a call: every audio stream (our microphone and each remote person) is resampled to 16 kHz
 * mono, mixed every 20 ms, and encoded like a voice note (Opus, u16-length-prefixed packets), so
 * [OpusVoiceCodec.forEachFrame] plays it back. Packets go to [output] as they're made.
 */
class CallRecorder(
    private val output: (ByteArray) -> Unit,
    private val logger: Logger,
    private val maxDurationMs: Long = MAX_DURATION_MS,
    private val onLimitReached: () -> Unit = {},
) : CallRecording {
    private val lock = Any()
    private val streams = LinkedHashMap<Any, StreamBuffer>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var frames = 0L

    override fun onAudio(source: Any, pcm: ShortArray, samples: Int, sampleRate: Int) {
        if (samples <= 0 || sampleRate <= 0) return
        synchronized(lock) { streams.getOrPut(source) { StreamBuffer() }.append(pcm, samples, sampleRate) }
    }

    override fun start() {
        if (job != null) return
        job = scope.launch {
            val encoder = OpusEncoder(OpusVoiceCodec.SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
                bitrate = 24_000
                complexity = 5
                useVBR = true
            }
            val packet = ByteArray(MAX_PACKET)
            val mix = ShortArray(OpusVoiceCodec.FRAME_SAMPLES)
            val sums = IntArray(OpusVoiceCodec.FRAME_SAMPLES)
            val startedNs = System.nanoTime()
            try {
                while (isActive) {
                    // Frames follow the wall clock, so gaps in the incoming audio become silence.
                    val due = (System.nanoTime() - startedNs) / 1_000_000 / OpusVoiceCodec.FRAME_MS
                    while (frames < due) {
                        if (frames * OpusVoiceCodec.FRAME_MS >= maxDurationMs) {
                            onLimitReached()
                            return@launch
                        }
                        sums.fill(0)
                        synchronized(lock) { streams.values.forEach { it.takeInto(sums) } }
                        for (i in mix.indices) mix[i] = sums[i].coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        val size = encoder.encode(mix, 0, OpusVoiceCodec.FRAME_SAMPLES, packet, 0, packet.size)
                        val framed = ByteArray(size + 2)
                        framed[0] = (size ushr 8).toByte()
                        framed[1] = size.toByte()
                        packet.copyInto(framed, 2, 0, size)
                        output(framed)
                        frames++
                    }
                    delay(OpusVoiceCodec.FRAME_MS.toLong())
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.warn("Media", "Call recording stopped", e)
            }
        }
    }

    override suspend fun stop(): Long {
        job?.cancelAndJoin()
        scope.cancel()
        return frames * OpusVoiceCodec.FRAME_MS
    }

    /** One stream's audio at 16 kHz, at most a second behind; older audio is dropped. */
    private class StreamBuffer {
        private val buffer = ShortArray(OpusVoiceCodec.SAMPLE_RATE)
        private var start = 0
        private var size = 0
        private var carry = 0.0

        fun append(pcm: ShortArray, samples: Int, sampleRate: Int) {
            if (sampleRate == OpusVoiceCodec.SAMPLE_RATE) {
                for (i in 0 until samples) push(pcm[i])
                return
            }
            // Averaging over each output sample's span is a cheap low-pass against aliasing.
            val step = sampleRate.toDouble() / OpusVoiceCodec.SAMPLE_RATE
            val window = maxOf(1, step.roundToInt())
            var position = carry
            while (position + window <= samples) {
                val at = position.toInt()
                var sum = 0
                for (k in 0 until window) sum += pcm[at + k]
                push((sum / window).toShort())
                position += step
            }
            carry = maxOf(0.0, position - samples)
        }

        fun takeInto(sums: IntArray) {
            val n = minOf(size, sums.size)
            for (k in 0 until n) sums[k] += buffer[(start + k) % buffer.size].toInt()
            start = (start + n) % buffer.size
            size -= n
        }

        private fun push(sample: Short) {
            if (size == buffer.size) {
                start = (start + 1) % buffer.size
                size--
            }
            buffer[(start + size) % buffer.size] = sample
            size++
        }
    }

    companion object {
        /** Two hours. */
        const val MAX_DURATION_MS = 2L * 60 * 60 * 1000
        private const val MAX_PACKET = 1_275

        /** Converts 16-bit little-endian interleaved PCM to mono samples. */
        fun downmixLittleEndian(bytes: (Int) -> Byte, channels: Int, frames: Int): ShortArray = ShortArray(frames) { i ->
            var sum = 0
            for (c in 0 until channels) {
                val at = (i * channels + c) * 2
                sum += ((bytes(at + 1).toInt() shl 8) or (bytes(at).toInt() and 0xff)).toShort().toInt()
            }
            (sum / channels).toShort()
        }
    }
}
