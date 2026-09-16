package app.zoocall.media

import app.zoocall.core.model.Logger
import app.zoocall.core.model.VoiceClip
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Voice note codec shared by Android and desktop: Opus (Concentus, pure Java) at 16 kHz mono,
 * 20 ms frames, ~24 kbps. Container: each packet prefixed with a u16 big-endian length.
 */
object OpusVoiceCodec {
    const val SAMPLE_RATE = 16_000
    const val FRAME_SAMPLES = 320
    const val FRAME_MS = 20
    private const val MAX_PACKET = 1_275

    class Encoder {
        private val encoder = OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
            bitrate = 24_000
            complexity = 5
            useVBR = true
        }
        private val out = ByteArrayOutputStream()
        private val packet = ByteArray(MAX_PACKET)

        fun encode(pcm: ShortArray) {
            val n = encoder.encode(pcm, 0, FRAME_SAMPLES, packet, 0, packet.size)
            out.write(n ushr 8)
            out.write(n and 0xff)
            out.write(packet, 0, n)
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    /** Decodes every frame. Stops early (without throwing) on a malformed container. */
    inline fun forEachFrame(audio: ByteArray, block: (index: Int, pcm: ShortArray) -> Unit) {
        val decoder = OpusDecoder(SAMPLE_RATE, 1)
        val pcm = ShortArray(FRAME_SAMPLES)
        var offset = 0
        var index = 0
        while (offset + 2 <= audio.size) {
            val len = ((audio[offset].toInt() and 0xff) shl 8) or (audio[offset + 1].toInt() and 0xff)
            offset += 2
            if (len == 0 || len > 1_275 || offset + len > audio.size) return
            val samples = runCatching { decoder.decode(audio, offset, len, pcm, 0, FRAME_SAMPLES, false) }.getOrDefault(-1)
            if (samples <= 0) return
            offset += len
            block(index++, pcm)
        }
    }

    fun rms(pcm: ShortArray): Float {
        var sum = 0.0
        for (s in pcm) sum += s.toDouble() * s
        return (sqrt(sum / pcm.size) / Short.MAX_VALUE).toFloat()
    }

    /** Buckets per-frame levels into [VoiceClip.WAVEFORM_BARS] bars scaled to 0..255. */
    fun waveform(levels: List<Float>): ByteArray {
        if (levels.isEmpty()) return ByteArray(0)
        val bars = minOf(VoiceClip.WAVEFORM_BARS, levels.size)
        val bucket = levels.size.toFloat() / bars
        val raw = FloatArray(bars) { i ->
            val from = (i * bucket).toInt()
            val to = maxOf(from + 1, ((i + 1) * bucket).toInt())
            levels.subList(from, minOf(to, levels.size)).max()
        }
        val peak = raw.max().takeIf { it > 0f } ?: 1f
        return ByteArray(bars) { i -> (raw[i] / peak * 255).toInt().coerceIn(8, 255).toByte() }
    }
}

/** Platform microphone. Returns the number of samples read, or a negative value on error. */
interface PcmInput {
    fun read(buffer: ShortArray, offset: Int, length: Int): Int
    fun close()
}

interface PcmOutput {
    fun write(buffer: ShortArray, length: Int)
    fun close()
}

abstract class PcmVoiceRecorder(private val logger: Logger) : VoiceRecorder {
    protected abstract fun openInput(sampleRate: Int): PcmInput

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    private val _elapsed = MutableStateFlow(0L)
    override val elapsedMs: StateFlow<Long> = _elapsed.asStateFlow()
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile private var stopRequested = false
    @Volatile private var cancelled = false
    private var job: Job? = null
    private var result: CompletableDeferred<VoiceClip?>? = null

    @Synchronized
    override fun start(): Boolean {
        if (job?.isActive == true) return false
        val input = try {
            openInput(OpusVoiceCodec.SAMPLE_RATE)
        } catch (e: Exception) {
            logger.warn(TAG, "Microphone unavailable", e)
            return false
        }
        stopRequested = false
        cancelled = false
        _elapsed.value = 0
        _isRecording.value = true
        val deferred = CompletableDeferred<VoiceClip?>()
        result = deferred
        job = scope.launch {
            val encoder = OpusVoiceCodec.Encoder()
            val frame = ShortArray(OpusVoiceCodec.FRAME_SAMPLES)
            val levels = ArrayList<Float>()
            val maxFrames = (VoiceClip.MAX_DURATION_MS / OpusVoiceCodec.FRAME_MS).toInt()
            var frames = 0
            try {
                while (!stopRequested && frames < maxFrames) {
                    var filled = 0
                    while (filled < frame.size && !stopRequested) {
                        val n = input.read(frame, filled, frame.size - filled)
                        if (n < 0) error("Microphone read failed ($n)")
                        filled += n
                    }
                    if (filled < frame.size) break
                    encoder.encode(frame)
                    val level = OpusVoiceCodec.rms(frame)
                    levels += level
                    _level.value = (level * 4f).coerceAtMost(1f)
                    frames++
                    _elapsed.value = frames.toLong() * OpusVoiceCodec.FRAME_MS
                }
                val durationMs = frames.toLong() * OpusVoiceCodec.FRAME_MS
                deferred.complete(
                    if (cancelled || durationMs < VoiceClip.MIN_DURATION_MS) null
                    else VoiceClip(encoder.bytes(), durationMs, OpusVoiceCodec.waveform(levels)),
                )
            } catch (e: Exception) {
                logger.warn(TAG, "Recording failed", e)
                deferred.complete(null)
            } finally {
                runCatching { input.close() }
                _level.value = 0f
                _isRecording.value = false
            }
        }
        return true
    }

    override suspend fun stop(): VoiceClip? {
        stopRequested = true
        return result?.await()
    }

    override fun cancel() {
        cancelled = true
        stopRequested = true
    }

    private companion object {
        const val TAG = "Voice"
    }
}

abstract class PcmVoicePlayer(private val logger: Logger) : VoicePlayer {
    protected abstract fun openOutput(sampleRate: Int): PcmOutput

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(VoicePlaybackState())
    override val state: StateFlow<VoicePlaybackState> = _state.asStateFlow()
    private var job: Job? = null

    @Synchronized
    override fun play(messageId: String, clip: VoiceClip) {
        val current = _state.value
        val resumeFrame = if (current.messageId == messageId && !current.playing && current.positionMs < clip.durationMs) {
            (current.positionMs / OpusVoiceCodec.FRAME_MS).toInt()
        } else {
            0
        }
        job?.cancel()
        _state.value = VoicePlaybackState(messageId, resumeFrame.toLong() * OpusVoiceCodec.FRAME_MS, clip.durationMs, playing = true)
        job = scope.launch {
            val output = try {
                openOutput(OpusVoiceCodec.SAMPLE_RATE)
            } catch (e: Exception) {
                logger.warn(TAG, "Speaker unavailable", e)
                _state.value = VoicePlaybackState()
                return@launch
            }
            try {
                OpusVoiceCodec.forEachFrame(clip.audio) { index, pcm ->
                    ensureActive()
                    if (index >= resumeFrame) {
                        output.write(pcm, pcm.size)
                        _state.value = _state.value.copy(positionMs = (index + 1).toLong() * OpusVoiceCodec.FRAME_MS)
                    }
                }
                // Finished: reset so the next tap starts from the beginning.
                _state.value = VoicePlaybackState(messageId, 0, clip.durationMs, playing = false)
            } finally {
                runCatching { output.close() }
            }
        }
    }

    @Synchronized
    override fun pause() {
        job?.cancel()
        _state.value = _state.value.copy(playing = false)
    }

    @Synchronized
    override fun stop() {
        job?.cancel()
        _state.value = VoicePlaybackState()
    }

    private companion object {
        const val TAG = "Voice"
    }
}
