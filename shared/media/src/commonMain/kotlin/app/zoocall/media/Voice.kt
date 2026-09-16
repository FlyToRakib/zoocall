package app.zoocall.media

import app.zoocall.core.model.VoiceClip
import kotlinx.coroutines.flow.StateFlow

/** Records one voice message at a time (Opus, 16 kHz mono). */
interface VoiceRecorder {
    val isRecording: StateFlow<Boolean>
    val elapsedMs: StateFlow<Long>

    /** Current input level 0..1 for the recording indicator. */
    val level: StateFlow<Float>

    /** Starts recording. Returns false if the microphone couldn't be opened. Needs the microphone permission. */
    fun start(): Boolean

    /**
     * Stops and returns the clip, or null when cancelled or shorter than [VoiceClip.MIN_DURATION_MS].
     * Recording stops by itself at [VoiceClip.MAX_DURATION_MS]; [stop] then returns that clip.
     */
    suspend fun stop(): VoiceClip?

    fun cancel()
}

data class VoicePlaybackState(
    val messageId: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
)

/** Plays one voice message at a time; starting another stops the current one. */
interface VoicePlayer {
    val state: StateFlow<VoicePlaybackState>

    /** Plays [clip], resuming from the paused position if [messageId] was paused. */
    fun play(messageId: String, clip: VoiceClip)
    fun pause()
    fun stop()
}
