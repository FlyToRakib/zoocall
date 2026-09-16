package app.zoocall.core.call

import app.zoocall.core.model.CallId
import app.zoocall.core.model.CallKind
import app.zoocall.media.CallStats
import app.zoocall.media.IceCandidateData
import app.zoocall.media.MediaConnectionState
import app.zoocall.media.MediaEngine
import app.zoocall.media.MediaEvent
import app.zoocall.media.MediaSession
import app.zoocall.media.VideoTrackHandle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Media engine for tests: "connects" once both an SDP answer has been applied (offerer)
 * or produced (answerer), and at least one remote candidate arrived.
 */
class FakeMediaEngine : MediaEngine {
    val sessions = mutableListOf<FakeSession>()
    override val hasCamera: Boolean = true

    override fun createSession(callId: CallId, kind: CallKind, isOfferer: Boolean): MediaSession =
        FakeSession(isOfferer, kind).also { sessions += it }

    override val screenCapture = object : app.zoocall.media.ScreenCapture {
        override fun sources() = listOf(app.zoocall.media.ScreenSource(1, "Screen 1", isWindow = false))
    }

    override fun createVoiceRecorder(): app.zoocall.media.VoiceRecorder = throw UnsupportedOperationException()
    override val voicePlayer: app.zoocall.media.VoicePlayer get() = throw UnsupportedOperationException()

    class FakeSession(private val isOfferer: Boolean, private val kind: CallKind = CallKind.Audio) : MediaSession {
        var sharing = false
        private var sharedBefore = false

        override suspend fun startScreenShare(source: app.zoocall.media.ScreenSource): app.zoocall.media.ScreenShareResult {
            val needsOffer = kind == CallKind.Audio && !upgraded && !sharedBefore
            sharing = true
            sharedBefore = true
            return if (needsOffer) app.zoocall.media.ScreenShareResult.NeedsOffer else app.zoocall.media.ScreenShareResult.Started
        }

        override fun stopScreenShare() {
            sharing = false
        }

        override val events = MutableSharedFlow<MediaEvent>(replay = 16, extraBufferCapacity = 16)
        override val localVideo: StateFlow<VideoTrackHandle?> = MutableStateFlow(null)
        override val remoteVideo: StateFlow<VideoTrackHandle?> = MutableStateFlow(null)
        override val stats: StateFlow<CallStats> = MutableStateFlow(CallStats())
        override val isFrontCamera: StateFlow<Boolean> = MutableStateFlow(true)
        var micOn = true
        var cameraOn = true
        var closed = false
        var upgraded = false
        var offers = 0
        var answers = 0
        private var negotiated = false
        private var gotCandidate = false

        override suspend fun start() {
            events.emit(MediaEvent.LocalCandidate(IceCandidateData("candidate:1 1 udp 2122260223 192.168.1.10 50000 typ host", "0", 0)))
        }

        override suspend fun createOffer(iceRestart: Boolean): String {
            offers++
            return "v=0 offer"
        }

        override suspend fun acceptOffer(sdp: String): String {
            answers++
            negotiated = true
            maybeConnect()
            return "v=0 answer"
        }

        override suspend fun upgradeToVideo(): Boolean {
            upgraded = true
            return true
        }

        override suspend fun applyAnswer(sdp: String) {
            negotiated = true
            maybeConnect()
        }

        override fun addRemoteCandidate(candidate: IceCandidateData) {
            gotCandidate = true
            maybeConnect()
        }

        private fun maybeConnect() {
            if (negotiated && gotCandidate) events.tryEmit(MediaEvent.ConnectionStateChanged(MediaConnectionState.Connected))
        }

        fun simulate(state: MediaConnectionState) {
            events.tryEmit(MediaEvent.ConnectionStateChanged(state))
        }

        override fun setMicEnabled(enabled: Boolean) {
            micOn = enabled
        }
        override fun setCameraEnabled(enabled: Boolean) {
            cameraOn = enabled
        }
        override fun switchCamera() = Unit
        override fun close() {
            closed = true
        }
    }
}
