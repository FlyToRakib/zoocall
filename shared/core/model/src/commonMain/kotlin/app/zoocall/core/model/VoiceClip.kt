package app.zoocall.core.model

/** A recorded voice message: Opus packets in Zoocall framing plus a small waveform for display. */
class VoiceClip(
    val audio: ByteArray,
    val durationMs: Long,
    /** Up to [WAVEFORM_BARS] amplitude values, 0..255. */
    val waveform: ByteArray,
) {
    /** Within the size and length limits for sending, receiving and restoring voice notes. */
    fun isAcceptable(): Boolean =
        audio.isNotEmpty() && audio.size <= MAX_AUDIO_BYTES &&
            durationMs in MIN_DURATION_MS..(MAX_DURATION_MS + 5_000) &&
            waveform.size <= 64

    companion object {
        const val CODEC = "opus/16000/1;zoocall-framing=1"
        const val MAX_DURATION_MS = 120_000L
        const val MIN_DURATION_MS = 500L
        const val MAX_AUDIO_BYTES = 900_000
        const val WAVEFORM_BARS = 48
    }
}

enum class MessageKind(val dbValue: String) {
    Text("text"),
    Voice("voice"),
    File("file"),
    ;

    companion object {
        fun from(value: String) = entries.firstOrNull { it.dbValue == value } ?: Text
    }
}
