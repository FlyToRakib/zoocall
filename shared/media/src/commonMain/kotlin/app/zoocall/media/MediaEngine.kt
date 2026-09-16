package app.zoocall.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.zoocall.core.model.CallId
import app.zoocall.core.model.CallKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform media port (ADR-005). Android: libwebrtc Android SDK. Desktop: webrtc-java.
 *
 * The session never talks to the network for signaling: SDP and ICE go through the
 * authenticated Noise channel, which binds the DTLS fingerprint to the peer identity.
 */
interface MediaEngine {
    fun createSession(callId: CallId, kind: CallKind, isOfferer: Boolean): MediaSession

    /** Whether a camera exists at all (edge case C14b). */
    val hasCamera: Boolean

    fun createVoiceRecorder(): VoiceRecorder

    val voicePlayer: VoicePlayer

    /** Device selection, where the platform lets the app choose (desktop). Android routes audio via Telecom instead. */
    val devices: MediaDeviceManager? get() = null

    /** Screens and windows that can be shared; null where screen sharing isn't offered (Android for now). */
    val screenCapture: ScreenCapture? get() = null

    /** Whether a stronger noise suppression level can be chosen (desktop; Android uses the device's own). */
    val supportsStrongNoiseSuppression: Boolean get() = false

    fun setStrongNoiseSuppression(enabled: Boolean) = Unit

    /**
     * Mixes call audio into one voice track (the voice-note codec at [RECORDING_SAMPLE_RATE]),
     * handing encoded packets to [output]. Null where recording isn't available.
     */
    fun createCallRecorder(output: (ByteArray) -> Unit, onLimitReached: () -> Unit): CallRecording? = null

    /** Whether [createCallRecorder] works on this platform. */
    val createCallRecorderSupported: Boolean get() = false

    /** Decodes a recording made by [createCallRecorder], one PCM frame at a time. */
    fun decodeRecording(audio: ByteArray, onPcm: (ShortArray) -> Unit) = Unit

    companion object {
        const val RECORDING_SAMPLE_RATE = 16_000
    }
}

/** A screen or an app window that can be shared in a call. */
data class ScreenSource(val id: Long, val title: String, val isWindow: Boolean)

interface ScreenCapture {
    /** Screens first, then windows. Can take a moment: call off the UI thread. */
    fun sources(): List<ScreenSource>
}

enum class ScreenShareResult {
    Failed,
    /** Sending on an existing video sender (the camera's): no renegotiation needed. */
    Started,
    /** A new video track was added (audio call): the sharer must send a new offer. */
    NeedsOffer,
}

data class IceCandidateData(
    val candidate: String,
    val sdpMid: String,
    val sdpMLineIndex: Int,
)

enum class MediaConnectionState { New, Connecting, Connected, Disconnected, Failed, Closed }

sealed interface MediaEvent {
    data class LocalCandidate(val candidate: IceCandidateData) : MediaEvent
    data class ConnectionStateChanged(val state: MediaConnectionState) : MediaEvent

    /** Offerer produced a new offer (e.g. ICE restart) that must be signaled. */
    data class LocalOffer(val sdp: String) : MediaEvent
    data class Error(val message: String) : MediaEvent

    /** Screen capture stopped without the app asking (e.g. Android's own "Stop sharing"). */
    data object ScreenShareEnded : MediaEvent
}

/**
 * Receives a call's audio for recording: 16-bit mono PCM from one stream ([source] tells streams
 * apart: our microphone, each remote person). Called on audio threads, so it must return quickly.
 */
fun interface AudioTap {
    fun onAudio(source: Any, pcm: ShortArray, samples: Int, sampleRate: Int)
}

/** A call recording in progress (see [MediaEngine.createCallRecorder]). */
interface CallRecording : AudioTap {
    fun start()

    /** Stops and returns the recorded length in milliseconds. */
    suspend fun stop(): Long
}

data class CallStats(
    val audioCodec: String? = null,
    val videoCodec: String? = null,
    val roundTripTimeMs: Int? = null,
    val packetLossPercent: Double? = null,
    val outgoingBitrateKbps: Int? = null,
    val incomingBitrateKbps: Int? = null,
    val frameWidth: Int? = null,
    val frameHeight: Int? = null,
    val framesPerSecond: Double? = null,
)

/** Signal quality bars 0..4 derived from stats (docs/05 §5.4). */
fun CallStats.qualityBars(): Int {
    val rtt = roundTripTimeMs ?: return 4
    val loss = packetLossPercent ?: 0.0
    return when {
        loss > 10 || rtt > 500 -> 1
        loss > 5 || rtt > 250 -> 2
        loss > 2 || rtt > 120 -> 3
        else -> 4
    }
}

/** Opaque handle to a video track, rendered with [VideoView]. */
interface VideoTrackHandle

interface MediaSession {
    val events: Flow<MediaEvent>
    val localVideo: StateFlow<VideoTrackHandle?>
    val remoteVideo: StateFlow<VideoTrackHandle?>
    val stats: StateFlow<CallStats>
    val isFrontCamera: StateFlow<Boolean>

    /** Starts capture (mic, and camera for video) and prepares the peer connection. */
    suspend fun start()

    /** Offerer only. Returns SDP. */
    suspend fun createOffer(iceRestart: Boolean = false): String

    /** Answerer only. Applies the remote offer and returns the SDP answer. */
    suspend fun acceptOffer(sdp: String): String

    /** Offerer only. */
    suspend fun applyAnswer(sdp: String)

    fun addRemoteCandidate(candidate: IceCandidateData)

    /**
     * Adds local video (the camera, when available) to an audio session and makes the session
     * negotiate video from now on. The offerer then sends a new offer. Returns whether the camera started.
     */
    suspend fun upgradeToVideo(): Boolean

    fun setMicEnabled(enabled: Boolean)
    fun setCameraEnabled(enabled: Boolean)
    fun switchCamera()

    /** Sends [source] instead of the camera, or as a new video track in an audio call. */
    suspend fun startScreenShare(source: ScreenSource): ScreenShareResult = ScreenShareResult.Failed

    /** Goes back to the camera (or sends no video). Never needs renegotiation. */
    fun stopScreenShare() = Unit

    /** Starts (or with null stops) handing this call's audio, ours and theirs, to [tap] for recording. */
    fun setAudioTap(tap: AudioTap?) = Unit

    fun close()
}

/**
 * Renders a [VideoTrackHandle]. [mirror] is used for the local front-camera preview.
 * [fill] crops to fill the bounds (remote full-screen) instead of letterboxing.
 * [overlay] marks a view drawn on top of another video (the self view), so native surfaces stack correctly.
 */
@Composable
expect fun VideoView(
    track: VideoTrackHandle?,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    fill: Boolean = true,
    overlay: Boolean = false,
)

/** Keeps SDP private in logs: never log full SDP (docs/04 §6). */
object IcePolicy {
    /**
     * Only LAN host candidates are allowed (docs/03 §4.2): private IPv4, IPv6 link-local and ULA.
     * Returns false for server-reflexive/relay candidates and public addresses.
     */
    fun isAllowed(candidateLine: String): Boolean {
        val parts = candidateLine.removePrefix("a=").removePrefix("candidate:").split(' ')
        if (parts.size < 8) return false
        val address = parts[4]
        val typIndex = parts.indexOf("typ")
        if (typIndex < 0 || parts.getOrNull(typIndex + 1) != "host") return false
        return isLanAddress(address)
    }

    fun isLanAddress(address: String): Boolean {
        val a = address.substringBefore('%').lowercase()
        if (a.contains(':')) {
            return a.startsWith("fe8") || a.startsWith("fe9") || a.startsWith("fea") || a.startsWith("feb") ||
                a.startsWith("fc") || a.startsWith("fd")
        }
        val octets = a.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return false
        val (o1, o2) = octets
        return o1 == 10 ||
            (o1 == 172 && o2 in 16..31) ||
            (o1 == 192 && o2 == 168) ||
            (o1 == 169 && o2 == 254) ||
            (o1 == 100 && o2 in 64..127) // CGNAT range used by some phone hotspots
    }
}
