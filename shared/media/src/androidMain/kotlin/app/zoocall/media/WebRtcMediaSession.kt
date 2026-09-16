package app.zoocall.media

import app.zoocall.core.model.CallKind
import app.zoocall.core.model.Logger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.Timer
import kotlin.concurrent.fixedRateTimer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidVideoTrack(val track: VideoTrack, val eglBase: EglBase) : VideoTrackHandle

internal class WebRtcMediaSession(
    private val factory: PeerConnectionFactory,
    private val eglBase: EglBase,
    private val camera: SharedCamera,
    private val screen: SharedScreen,
    @Volatile private var kind: CallKind,
    private val isOfferer: Boolean,
    private val hasCamera: Boolean,
    private val logger: Logger,
) : MediaSession {

    private val _events = MutableSharedFlow<MediaEvent>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<MediaEvent> = _events.asSharedFlow()

    private val _localVideo = MutableStateFlow<VideoTrackHandle?>(null)
    override val localVideo: StateFlow<VideoTrackHandle?> = _localVideo.asStateFlow()

    private val _remoteVideo = MutableStateFlow<VideoTrackHandle?>(null)
    override val remoteVideo: StateFlow<VideoTrackHandle?> = _remoteVideo.asStateFlow()

    private val _stats = MutableStateFlow(CallStats())
    override val stats: StateFlow<CallStats> = _stats.asStateFlow()

    override val isFrontCamera: StateFlow<Boolean> get() = camera.isFrontCamera

    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoTrack: VideoTrack? = null

    // Recording: sinks on our microphone track and the remote audio track, attached only while tapped.
    @Volatile private var audioTap: AudioTap? = null
    private var remoteAudio: AudioTrack? = null
    private val sinkLock = Any()
    private var localSinkOn = false
    private var remoteSinkOn = false
    private val localAudioKey = Any()
    private val remoteAudioKey = Any()
    private val localSink = AudioTrackSink { data, bits, rate, channels, frames, _ -> tapPcm(localAudioKey, data, bits, rate, channels, frames) }
    private val remoteSink = AudioTrackSink { data, bits, rate, channels, frames, _ -> tapPcm(remoteAudioKey, data, bits, rate, channels, frames) }

    private fun tapPcm(source: Any, data: ByteBuffer, bits: Int, rate: Int, channels: Int, frames: Int) {
        val tap = audioTap ?: return
        if (bits != 16 || channels <= 0) return
        val bytes = data.duplicate()
        val base = bytes.position()
        val count = minOf(frames, bytes.remaining() / (2 * channels))
        tap.onAudio(source, CallRecorder.downmixLittleEndian({ bytes.get(base + it) }, channels, count), count, rate)
    }

    private fun syncAudioSinks() = synchronized(sinkLock) {
        val wanted = audioTap != null
        audioTrack?.let { track ->
            if (wanted != localSinkOn) runCatching { if (wanted) track.addSink(localSink) else track.removeSink(localSink) }
            localSinkOn = wanted
        }
        remoteAudio?.let { track ->
            if (wanted != remoteSinkOn) runCatching { if (wanted) track.addSink(remoteSink) else track.removeSink(remoteSink) }
            remoteSinkOn = wanted
        }
    }

    override fun setAudioTap(tap: AudioTap?) {
        audioTap = tap
        syncAudioSinks()
    }

    private var statsTimer: Timer? = null
    private var previousBytes: Pair<Long, Long>? = null
    private var screenTrack: VideoTrack? = null
    /** The screen went out on the camera's sender (video call) rather than its own. */
    private var sharingOverCamera = false
    /** Audio call: the video sender added for an earlier share, reused without renegotiating. */
    private var idleScreenSender: RtpSender? = null
    private var shares = 0
    @Volatile private var closed = false

    override suspend fun start() {
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            keyType = PeerConnection.KeyType.ECDSA
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }
        val pc = factory.createPeerConnection(config, observer) ?: error("PeerConnection creation failed")
        peerConnection = pc

        val audioConstraints = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
            mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
            mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
            mandatory += MediaConstraints.KeyValuePair("googHighpassFilter", "true")
        }
        audioSource = factory.createAudioSource(audioConstraints)
        audioTrack = factory.createAudioTrack("audio-$id", audioSource).also {
            it.setEnabled(true)
            pc.addTrack(it, listOf(STREAM_ID))
        }
        syncAudioSinks()

        if (kind == CallKind.Video) {
            val cameraTrack = if (hasCamera) createCameraTrack() else null
            if (cameraTrack != null) {
                pc.addTrack(cameraTrack, listOf(STREAM_ID))
            } else {
                // Receive-only video (edge cases C12 / C14b).
                pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
            }
        }
        startStats()
    }

    /** This leg's track from the shared camera. */
    private fun createCameraTrack(): VideoTrack? {
        val source = camera.acquire(this)
        if (source == null) {
            _events.tryEmit(MediaEvent.Error("camera_unavailable"))
            return null
        }
        return factory.createVideoTrack("video-$id", source).also {
            videoTrack = it
            _localVideo.value = AndroidVideoTrack(it, eglBase)
        }
    }

    override suspend fun createOffer(iceRestart: Boolean): String {
        val pc = requireNotNull(peerConnection)
        val constraints = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", (kind == CallKind.Video).toString())
            if (iceRestart) mandatory += MediaConstraints.KeyValuePair("IceRestart", "true")
        }
        val offer = pc.awaitCreate(offer = true, constraints)
        pc.awaitSetLocal(offer)
        return offer.description
    }

    override suspend fun acceptOffer(sdp: String): String {
        val pc = requireNotNull(peerConnection)
        pc.awaitSetRemote(SessionDescription(SessionDescription.Type.OFFER, sdp))
        val answer = pc.awaitCreate(offer = false, MediaConstraints())
        pc.awaitSetLocal(answer)
        return answer.description
    }

    override suspend fun applyAnswer(sdp: String) {
        requireNotNull(peerConnection).awaitSetRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    override fun addRemoteCandidate(candidate: IceCandidateData) {
        peerConnection?.addIceCandidate(IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate))
    }

    override suspend fun upgradeToVideo(): Boolean {
        val pc = peerConnection ?: return false
        if (kind == CallKind.Video) return videoTrack != null
        kind = CallKind.Video
        // A track added before the peer's new offer is matched to its video section (JSEP §5.10).
        val cameraTrack = if (hasCamera) createCameraTrack() else null
        if (cameraTrack != null) {
            pc.addTrack(cameraTrack, listOf(STREAM_ID))
            return true
        }
        // No camera: the offerer still has to offer a video section so the peer's video can arrive.
        if (isOfferer) {
            pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        }
        return false
    }

    override fun setMicEnabled(enabled: Boolean) {
        audioTrack?.setEnabled(enabled)
    }

    override fun setCameraEnabled(enabled: Boolean) {
        val track = videoTrack ?: return
        track.setEnabled(enabled)
        // Capture stops once no leg wants the camera, so the OS camera indicator turns off (docs/04 §6).
        camera.setEnabled(this, enabled)
    }

    override fun switchCamera() = camera.switchCamera()

    override suspend fun startScreenShare(source: ScreenSource): ScreenShareResult {
        val pc = peerConnection ?: return ScreenShareResult.Failed
        if (closed) return ScreenShareResult.Failed
        if (screenTrack != null) return ScreenShareResult.Started
        val capture = screen.acquire(this) { _events.tryEmit(MediaEvent.ScreenShareEnded) } ?: return ScreenShareResult.Failed
        val track = runCatching { factory.createVideoTrack("screen-$id-${++shares}", capture) }.getOrElse {
            logger.warn(TAG, "Screen track failed", it)
            screen.release(this)
            return ScreenShareResult.Failed
        }
        screenTrack = track
        val cameraSender = videoTrack?.let { cam -> pc.senders.firstOrNull { it.track()?.id() == cam.id() } }
        val idleSender = idleScreenSender
        return when {
            cameraSender != null -> {
                cameraSender.setTrack(track, false)
                sharingOverCamera = true
                // The camera light goes off while the screen is shown instead.
                camera.setEnabled(this, false)
                ScreenShareResult.Started
            }
            idleSender != null -> {
                idleSender.setTrack(track, false)
                sharingOverCamera = false
                ScreenShareResult.Started
            }
            else -> {
                // Audio call: a new video section, so the sharer renegotiates (JSEP §5.10).
                pc.addTrack(track, listOf(STREAM_ID))
                sharingOverCamera = false
                ScreenShareResult.NeedsOffer
            }
        }
    }

    override fun stopScreenShare() {
        val track = screenTrack ?: return
        screenTrack = null
        val sender = peerConnection?.senders?.firstOrNull { it.track()?.id() == track.id() }
        val cam = videoTrack
        if (sharingOverCamera && sender != null && cam != null) {
            sender.setTrack(cam, false)
            camera.setEnabled(this, cam.enabled())
        } else if (sender != null) {
            // Keep the sender for the next share, so it needs no new offer.
            sender.setTrack(null, false)
            idleScreenSender = sender
        }
        runCatching { track.dispose() }
        screen.release(this)
    }

    override fun close() {
        if (closed) return
        closed = true
        statsTimer?.cancel()
        _localVideo.value = null
        _remoteVideo.value = null
        audioTap = null
        syncAudioSinks()
        peerConnection?.dispose()
        if (screenTrack != null) screen.release(this)
        if (videoTrack != null) camera.release(this)
        audioSource?.dispose()
        _events.tryEmit(MediaEvent.ConnectionStateChanged(MediaConnectionState.Closed))
    }

    private val id = System.identityHashCode(this).toString(16)

    // -- Observer ---------------------------------------------------------------------------------

    private val observer = object : PeerConnection.Observer {
        // Callbacks arrive on libwebrtc's native threads through JNI: an exception escaping here aborts
        // the whole process, so every callback is guarded.
        override fun onIceCandidate(candidate: IceCandidate) = guarded("onIceCandidate") {
            val sdp: String? = candidate.sdp
            val mid: String? = candidate.sdpMid
            if (sdp != null) _events.tryEmit(MediaEvent.LocalCandidate(IceCandidateData(sdp, mid.orEmpty(), candidate.sdpMLineIndex)))
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            val mapped = when (newState) {
                PeerConnection.PeerConnectionState.NEW -> MediaConnectionState.New
                PeerConnection.PeerConnectionState.CONNECTING -> MediaConnectionState.Connecting
                PeerConnection.PeerConnectionState.CONNECTED -> MediaConnectionState.Connected
                PeerConnection.PeerConnectionState.DISCONNECTED -> MediaConnectionState.Disconnected
                PeerConnection.PeerConnectionState.FAILED -> MediaConnectionState.Failed
                PeerConnection.PeerConnectionState.CLOSED -> MediaConnectionState.Closed
            }
            if (!closed) _events.tryEmit(MediaEvent.ConnectionStateChanged(mapped))
        }

        override fun onTrack(transceiver: RtpTransceiver) = guarded("onTrack") {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) _remoteVideo.value = AndroidVideoTrack(track, eglBase)
            if (track is AudioTrack) {
                remoteAudio = track
                syncAudioSinks()
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = channel.dispose()
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    private inline fun guarded(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            logger.error(TAG, "WebRTC callback $name failed", e)
            _events.tryEmit(MediaEvent.Error("$name: ${e::class.simpleName}"))
        }
    }

    // -- Stats ------------------------------------------------------------------------------------

    private fun startStats() {
        statsTimer = fixedRateTimer("zoocall-stats", daemon = true, initialDelay = STATS_INTERVAL_MS, period = STATS_INTERVAL_MS) {
            if (closed) return@fixedRateTimer
            peerConnection?.getStats { report -> _stats.value = parseStats(report) }
        }
    }

    private fun parseStats(report: RTCStatsReport): CallStats {
        var rtt: Int? = null
        var bytesSent = 0L
        var bytesReceived = 0L
        var lost = 0L
        var received = 0L
        var width: Int? = null
        var height: Int? = null
        var fps: Double? = null
        val codecs = HashMap<String, String>()
        var audioCodecId: String? = null
        var videoCodecId: String? = null
        for (stat in report.statsMap.values) {
            val m = stat.members
            when (stat.type) {
                "candidate-pair" -> if (m["nominated"] == true || m["state"] == "succeeded") {
                    (m["currentRoundTripTime"] as? Double)?.let { rtt = (it * 1000).toInt() }
                    bytesSent += (m["bytesSent"] as? java.math.BigInteger)?.toLong() ?: 0
                    bytesReceived += (m["bytesReceived"] as? java.math.BigInteger)?.toLong() ?: 0
                }
                "inbound-rtp" -> {
                    lost += (m["packetsLost"] as? Int)?.toLong() ?: 0
                    received += (m["packetsReceived"] as? Long) ?: ((m["packetsReceived"] as? Int)?.toLong() ?: 0)
                    if (m["kind"] == "video") {
                        width = (m["frameWidth"] as? Long)?.toInt() ?: (m["frameWidth"] as? Int)
                        height = (m["frameHeight"] as? Long)?.toInt() ?: (m["frameHeight"] as? Int)
                        fps = m["framesPerSecond"] as? Double
                        videoCodecId = m["codecId"] as? String
                    } else {
                        audioCodecId = m["codecId"] as? String
                    }
                }
                "codec" -> (m["mimeType"] as? String)?.let { codecs[stat.id] = it.substringAfter('/') }
            }
        }
        val previous = previousBytes
        previousBytes = bytesSent to bytesReceived
        val seconds = STATS_INTERVAL_MS / 1000.0
        return CallStats(
            audioCodec = audioCodecId?.let(codecs::get),
            videoCodec = videoCodecId?.let(codecs::get),
            roundTripTimeMs = rtt,
            packetLossPercent = if (lost + received > 0) lost * 100.0 / (lost + received) else null,
            outgoingBitrateKbps = previous?.let { ((bytesSent - it.first) * 8 / 1000 / seconds).toInt() },
            incomingBitrateKbps = previous?.let { ((bytesReceived - it.second) * 8 / 1000 / seconds).toInt() },
            frameWidth = width,
            frameHeight = height,
            framesPerSecond = fps,
        )
    }

    private companion object {
        const val TAG = "Media"
        const val STREAM_ID = "zoocall"
        const val STATS_INTERVAL_MS = 2_000L
    }
}

private suspend fun PeerConnection.awaitCreate(offer: Boolean, constraints: MediaConstraints): SessionDescription =
    suspendCancellableCoroutine { cont ->
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
            override fun onCreateFailure(error: String?) = cont.resumeWithException(IllegalStateException("SDP create failed: $error"))
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = Unit
        }
        if (offer) createOffer(observer, constraints) else createAnswer(observer, constraints)
    }

private suspend fun PeerConnection.awaitSetLocal(sdp: SessionDescription) = awaitSet { setLocalDescription(it, sdp) }

private suspend fun PeerConnection.awaitSetRemote(sdp: SessionDescription) = awaitSet { setRemoteDescription(it, sdp) }

private suspend fun awaitSet(block: (SdpObserver) -> Unit) = suspendCancellableCoroutine { cont ->
    block(object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetSuccess() = cont.resume(Unit)
        override fun onSetFailure(error: String?) = cont.resumeWithException(IllegalStateException("SDP set failed: $error"))
    })
}
