package app.zoocall.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import app.zoocall.core.model.CallId
import app.zoocall.core.model.CallKind
import app.zoocall.core.model.Logger
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCBundlePolicy
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtcpMuxPolicy
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCStatsReport
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioProcessing
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSource
import dev.onvoid.webrtc.media.audio.AudioTrackSink
import dev.onvoid.webrtc.media.video.VideoBufferConverter
import dev.onvoid.webrtc.media.video.VideoCaptureCapability
import dev.onvoid.webrtc.media.video.VideoDevice
import dev.onvoid.webrtc.media.video.VideoDeviceSource
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrack
import dev.onvoid.webrtc.media.video.VideoTrackSink
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.util.Timer
import kotlin.concurrent.fixedRateTimer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private typealias NoiseLevel = AudioProcessingConfig.NoiseSuppression.Level

/**
 * webrtc-java (libwebrtc for Windows, macOS and Linux). The audio device module provides AEC3,
 * noise suppression and AGC; LAN host candidates only (no ICE servers are configured).
 *
 * @param devicePreferences where the chosen microphone, speaker and camera are remembered.
 */
class DesktopMediaEngine(
    private val logger: Logger = Logger.current,
    private val devicePreferences: File? = null,
) : MediaEngine {
    // One module for all calls, so the chosen devices can be set on it and switched live.
    private val audioModule: AudioDeviceModule by lazy { AudioDeviceModule() }
    // Our own audio processing module, so noise suppression can be raised above libwebrtc's default.
    private val audioProcessing: AudioProcessing by lazy { AudioProcessing() }
    private val factory: PeerConnectionFactory by lazy { PeerConnectionFactory(audioModule, audioProcessing) }
    @Volatile private var strongNoiseSuppression = false

    override val supportsStrongNoiseSuppression: Boolean = true

    override val createCallRecorderSupported: Boolean = true

    override fun createCallRecorder(output: (ByteArray) -> Unit, onLimitReached: () -> Unit): CallRecording =
        CallRecorder(output, logger, onLimitReached = onLimitReached)

    override fun decodeRecording(audio: ByteArray, onPcm: (ShortArray) -> Unit) = OpusVoiceCodec.forEachFrame(audio) { _, pcm -> onPcm(pcm) }

    override fun setStrongNoiseSuppression(enabled: Boolean) {
        strongNoiseSuppression = enabled
        // A call in progress switches at once; new calls apply it when their audio starts.
        if (sessions.any { it.audioStarted }) applyNoiseSuppression(if (enabled) NoiseLevel.VERY_HIGH else NoiseLevel.HIGH)
    }

    /**
     * libwebrtc applies a call's AudioOptions (echo cancellation, gain control, noise suppression at
     * its "high" level) when audio starts. Changing the level replaces the whole configuration, so the
     * stages those options enable are switched on again with it.
     */
    private fun applyNoiseSuppression(level: NoiseLevel) {
        runCatching {
            audioProcessing.applyConfig(
                AudioProcessingConfig().apply {
                    echoCanceller.enabled = true
                    highPassFilter.enabled = true
                    gainController.enabled = true
                    noiseSuppression.enabled = true
                    noiseSuppression.level = level
                },
            )
        }.onFailure { logger.warn("Media", "Noise suppression change failed", it) }
    }
    private val sessions = CopyOnWriteArraySet<DesktopMediaSession>()

    /** Group calls open a peer connection per person; they all share one camera capture. */
    private val camera = DesktopSharedCamera(logger)
    private val screen = DesktopSharedScreen(logger)

    override val screenCapture: ScreenCapture get() = screen

    override val devices: DesktopDeviceManager by lazy {
        DesktopDeviceManager(
            logger = logger,
            preferencesFile = devicePreferences,
            adm = { audioModule },
            factory = { factory },
            callAudioRunning = { sessions.any { it.audioStarted } },
            callActive = { sessions.isNotEmpty() },
            onCameraChanged = { device -> camera.use(device) },
        )
    }

    override val hasCamera: Boolean
        get() = runCatching { MediaDevices.getVideoCaptureDevices().isNotEmpty() }.getOrDefault(false)

    override fun createSession(callId: CallId, kind: CallKind, isOfferer: Boolean): MediaSession {
        devices.prepareForCall()
        val session = DesktopMediaSession(
            factory = factory,
            camera = camera,
            screen = screen,
            kind = kind,
            logger = logger,
            onAudioStarted = { if (strongNoiseSuppression) applyNoiseSuppression(NoiseLevel.VERY_HIGH) },
            cameraProvider = { devices.cameraDevice() },
            allCameras = { devices.allCameras() },
            onClosed = { closed ->
                sessions.remove(closed)
                if (sessions.isEmpty()) devices.onCallsEnded()
            },
        )
        sessions += session
        return session
    }

    override fun createVoiceRecorder(): VoiceRecorder = DesktopVoiceRecorder(logger) { devices.activeName(DeviceKind.Microphone) }

    override val voicePlayer: VoicePlayer by lazy { DesktopVoicePlayer(logger) { devices.activeName(DeviceKind.Speaker) } }
}

private class DesktopMediaSession(
    private val factory: PeerConnectionFactory,
    private val camera: DesktopSharedCamera,
    private val screen: DesktopSharedScreen,
    private var kind: CallKind,
    private val logger: Logger,
    private val cameraProvider: () -> VideoDevice?,
    private val allCameras: () -> List<VideoDevice>,
    private val onClosed: (DesktopMediaSession) -> Unit,
    /** After the call's audio options are in place. */
    private val onAudioStarted: () -> Unit = {},
) : MediaSession {
    /** Media has started, so the audio module is recording and playing for this call. */
    @Volatile var audioStarted = false
        private set
    private val _events = MutableSharedFlow<MediaEvent>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<MediaEvent> = _events.asSharedFlow()
    private val _localVideo = MutableStateFlow<VideoTrackHandle?>(null)
    override val localVideo: StateFlow<VideoTrackHandle?> = _localVideo.asStateFlow()
    private val _remoteVideo = MutableStateFlow<VideoTrackHandle?>(null)
    override val remoteVideo: StateFlow<VideoTrackHandle?> = _remoteVideo.asStateFlow()
    private val _stats = MutableStateFlow(CallStats())
    override val stats: StateFlow<CallStats> = _stats.asStateFlow()
    override val isFrontCamera: StateFlow<Boolean> = MutableStateFlow(true)

    private var pc: RTCPeerConnection? = null
    private var audioSource: AudioTrackSource? = null
    private var audioTrack: AudioTrack? = null

    // Recording: sinks on our microphone track and the remote audio track, attached only while tapped.
    @Volatile private var audioTap: AudioTap? = null
    private var remoteAudio: AudioTrack? = null
    private val sinkLock = Any()
    private var localSinkOn = false
    private var remoteSinkOn = false
    private val localAudioKey = Any()
    private val remoteAudioKey = Any()
    private val localSink = AudioTrackSink { data, bits, rate, channels, frames -> tapPcm(localAudioKey, data, bits, rate, channels, frames) }
    private val remoteSink = AudioTrackSink { data, bits, rate, channels, frames -> tapPcm(remoteAudioKey, data, bits, rate, channels, frames) }

    private fun tapPcm(source: Any, data: ByteArray, bits: Int, rate: Int, channels: Int, frames: Int) {
        val tap = audioTap ?: return
        if (bits != 16 || channels <= 0) return
        val count = minOf(frames, data.size / (2 * channels))
        tap.onAudio(source, CallRecorder.downmixLittleEndian({ data[it] }, channels, count), count, rate)
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
    private var videoTrack: VideoTrack? = null
    private val id = System.identityHashCode(this).toString(16)
    private var local: FrameTrack? = null
    private var remote: FrameTrack? = null
    private var statsTimer: Timer? = null
    @Volatile private var closed = false

    override suspend fun start() {
        val config = RTCConfiguration().apply {
            iceServers = emptyList()
            bundlePolicy = RTCBundlePolicy.MAX_BUNDLE
            rtcpMuxPolicy = RTCRtcpMuxPolicy.REQUIRE
        }
        val connection = factory.createPeerConnection(config, observer) ?: error("PeerConnection creation failed")
        pc = connection

        val options = AudioOptions().apply {
            echoCancellation = true
            autoGainControl = true
            noiseSuppression = true
            highpassFilter = true
        }
        audioSource = factory.createAudioSource(options)
        audioTrack = factory.createAudioTrack("audio-$id", audioSource).also { connection.addTrack(it, listOf(STREAM_ID)) }
        syncAudioSinks()

        if (kind == CallKind.Video) startCamera(connection)
        audioStarted = true
        onAudioStarted()
        statsTimer = fixedRateTimer("zoocall-stats", daemon = true, initialDelay = 2_000, period = 2_000) {
            if (!closed) pc?.getStats { report -> _stats.value = parseStats(report) }
        }
    }

    private fun startCamera(connection: RTCPeerConnection) {
        val device = cameraProvider()
        if (device == null) {
            // No camera (edge case C14b): the peer's offer still carries video, so we receive it.
            _events.tryEmit(MediaEvent.Error("camera_unavailable"))
            return
        }
        val source = camera.acquire(this, device)
        if (source == null) {
            _events.tryEmit(MediaEvent.Error("camera_unavailable"))
            return
        }
        runCatching {
            val track = factory.createVideoTrack("video-$id", source)
            connection.addTrack(track, listOf(STREAM_ID))
            videoTrack = track
            local = FrameTrack(track).also { _localVideo.value = it }
        }.onFailure {
            logger.warn(TAG, "Camera track failed", it)
            camera.release(this)
            _events.tryEmit(MediaEvent.Error("camera_unavailable"))
        }
    }

    override suspend fun createOffer(iceRestart: Boolean): String {
        val connection = requireNotNull(pc)
        val offer = suspendCancellableCoroutine { cont ->
            connection.createOffer(RTCOfferOptions().apply { this.iceRestart = iceRestart }, createObserver(cont::resume, cont::resumeWithException))
        }
        connection.awaitSetLocal(offer)
        return offer.sdp
    }

    override suspend fun acceptOffer(sdp: String): String {
        val connection = requireNotNull(pc)
        connection.awaitSetRemote(RTCSessionDescription(RTCSdpType.OFFER, sdp))
        val answer = suspendCancellableCoroutine { cont ->
            connection.createAnswer(RTCAnswerOptions(), createObserver(cont::resume, cont::resumeWithException))
        }
        connection.awaitSetLocal(answer)
        return answer.sdp
    }

    override suspend fun applyAnswer(sdp: String) {
        requireNotNull(pc).awaitSetRemote(RTCSessionDescription(RTCSdpType.ANSWER, sdp))
    }

    override fun addRemoteCandidate(candidate: IceCandidateData) {
        pc?.addIceCandidate(RTCIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate))
    }

    override suspend fun upgradeToVideo(): Boolean {
        val connection = pc ?: return false
        if (kind == CallKind.Video) return videoTrack != null
        kind = CallKind.Video
        // A track added before the peer's new offer is matched to its video section (JSEP §5.10).
        startCamera(connection)
        return videoTrack != null
    }

    override fun setMicEnabled(enabled: Boolean) {
        audioTrack?.setEnabled(enabled)
    }

    override fun setCameraEnabled(enabled: Boolean) {
        val track = videoTrack ?: return
        track.setEnabled(enabled)
        // Capture stops once no leg wants the camera, so the OS camera light turns off (docs/04 §6).
        camera.setEnabled(this, enabled)
    }

    // -- Screen sharing ---------------------------------------------------------------------------

    private var screenTrack: VideoTrack? = null
    /** The screen went out on the camera's sender (video call) rather than its own. */
    private var sharingOverCamera = false
    /** Audio call: the video sender added for an earlier share, reused without renegotiating. */
    private var idleScreenTrackId: String? = null
    private val idleScreenTracks = ArrayList<VideoTrack>()
    private var shares = 0

    override suspend fun startScreenShare(source: ScreenSource): ScreenShareResult {
        val connection = pc ?: return ScreenShareResult.Failed
        if (closed) return ScreenShareResult.Failed
        val capture = screen.acquire(this, source) ?: return ScreenShareResult.Failed
        // Already sharing: the shared capture has switched to [source].
        if (screenTrack != null) return ScreenShareResult.Started
        val track = runCatching { factory.createVideoTrack("screen-$id-${++shares}", capture) }.getOrElse {
            logger.warn(TAG, "Screen track failed", it)
            screen.release(this)
            return ScreenShareResult.Failed
        }
        screenTrack = track
        val cameraSender = videoTrack?.let { cam -> connection.senders.firstOrNull { it.track?.id == cam.id } }
        val idleSender = idleScreenTrackId?.let { idle -> connection.senders.firstOrNull { it.track?.id == idle } }
        return when {
            cameraSender != null -> {
                cameraSender.replaceTrack(track)
                sharingOverCamera = true
                // The camera light goes off while the screen is shown instead.
                camera.setEnabled(this, false)
                ScreenShareResult.Started
            }
            idleSender != null -> {
                idleSender.replaceTrack(track)
                sharingOverCamera = false
                ScreenShareResult.Started
            }
            else -> {
                // Audio call: a new video section, so the sharer renegotiates (JSEP §5.10).
                connection.addTrack(track, listOf(STREAM_ID))
                sharingOverCamera = false
                ScreenShareResult.NeedsOffer
            }
        }
    }

    override fun stopScreenShare() {
        val track = screenTrack ?: return
        screenTrack = null
        val sender = pc?.senders?.firstOrNull { it.track?.id == track.id }
        val cam = videoTrack
        if (sharingOverCamera && sender != null && cam != null) {
            sender.replaceTrack(cam)
            camera.setEnabled(this, cam.isEnabled)
            runCatching { track.dispose() }
        } else {
            // Keep the video sender so sharing again needs no renegotiation; just stop the frames.
            track.setEnabled(false)
            idleScreenTrackId = track.id
            idleScreenTracks += track
        }
        screen.release(this)
    }

    override fun switchCamera() {
        val cameras = allCameras()
        if (cameras.size < 2) return
        val index = cameras.indexOfFirst { it.descriptor == camera.device?.descriptor }
        camera.use(cameras[(index + 1).mod(cameras.size)])
    }

    override fun close() {
        if (closed) return
        closed = true
        statsTimer?.cancel()
        _localVideo.value = null
        _remoteVideo.value = null
        local?.dispose()
        remote?.dispose()
        audioTap = null
        syncAudioSinks()
        runCatching { pc?.close() }
        runCatching { videoTrack?.dispose() }
        runCatching { audioTrack?.dispose() }
        if (videoTrack != null) camera.release(this)
        screenTrack?.let {
            runCatching { it.dispose() }
            screen.release(this)
        }
        idleScreenTracks.forEach { runCatching { it.dispose() } }
        audioStarted = false
        _events.tryEmit(MediaEvent.ConnectionStateChanged(MediaConnectionState.Closed))
        onClosed(this)
    }

    private val observer = object : PeerConnectionObserver {
        override fun onIceCandidate(candidate: RTCIceCandidate) {
            _events.tryEmit(MediaEvent.LocalCandidate(IceCandidateData(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)))
        }

        override fun onConnectionChange(state: RTCPeerConnectionState) {
            if (closed) return
            _events.tryEmit(
                MediaEvent.ConnectionStateChanged(
                    when (state) {
                        RTCPeerConnectionState.NEW -> MediaConnectionState.New
                        RTCPeerConnectionState.CONNECTING -> MediaConnectionState.Connecting
                        RTCPeerConnectionState.CONNECTED -> MediaConnectionState.Connected
                        RTCPeerConnectionState.DISCONNECTED -> MediaConnectionState.Disconnected
                        RTCPeerConnectionState.FAILED -> MediaConnectionState.Failed
                        RTCPeerConnectionState.CLOSED -> MediaConnectionState.Closed
                    },
                ),
            )
        }

        override fun onTrack(transceiver: RTCRtpTransceiver) {
            val track = transceiver.receiver.track
            if (track is VideoTrack) remote = FrameTrack(track).also { _remoteVideo.value = it }
            if (track is AudioTrack) {
                remoteAudio = track
                syncAudioSinks()
            }
        }
    }

    private var previousBytes: Pair<Long, Long>? = null

    private fun parseStats(report: RTCStatsReport): CallStats {
        var rtt: Int? = null
        var lost = 0L
        var received = 0L
        var bytesSent = 0L
        var bytesReceived = 0L
        var width: Int? = null
        var height: Int? = null
        var fps: Double? = null
        var audioCodecId: String? = null
        var videoCodecId: String? = null
        val codecs = HashMap<String, String>()
        for (stat in report.stats.values) {
            val a = stat.attributes
            when (stat.type.name) {
                "CANDIDATE_PAIR" -> if (a["nominated"] == true || a["state"]?.toString()?.lowercase() == "succeeded") {
                    (a["currentRoundTripTime"] as? Number)?.let { rtt = (it.toDouble() * 1000).toInt() }
                    bytesSent += (a["bytesSent"] as? Number)?.toLong() ?: 0
                    bytesReceived += (a["bytesReceived"] as? Number)?.toLong() ?: 0
                }
                "INBOUND_RTP" -> {
                    lost += (a["packetsLost"] as? Number)?.toLong() ?: 0
                    received += (a["packetsReceived"] as? Number)?.toLong() ?: 0
                    if (a["kind"] == "video") {
                        width = (a["frameWidth"] as? Number)?.toInt()
                        height = (a["frameHeight"] as? Number)?.toInt()
                        fps = (a["framesPerSecond"] as? Number)?.toDouble()
                        videoCodecId = a["codecId"] as? String
                    } else {
                        audioCodecId = a["codecId"] as? String
                    }
                }
                "CODEC" -> (a["mimeType"] as? String)?.let { codecs[stat.id] = it.substringAfter('/') }
            }
        }
        val previous = previousBytes
        previousBytes = bytesSent to bytesReceived
        return CallStats(
            audioCodec = audioCodecId?.let(codecs::get),
            videoCodec = videoCodecId?.let(codecs::get),
            roundTripTimeMs = rtt,
            packetLossPercent = if (lost + received > 0) lost * 100.0 / (lost + received) else null,
            outgoingBitrateKbps = previous?.let { ((bytesSent - it.first) * 8 / 1000 / STATS_SECONDS).toInt().coerceAtLeast(0) },
            incomingBitrateKbps = previous?.let { ((bytesReceived - it.second) * 8 / 1000 / STATS_SECONDS).toInt().coerceAtLeast(0) },
            frameWidth = width,
            frameHeight = height,
            framesPerSecond = fps,
        )
    }

    private companion object {
        const val TAG = "Media"
        const val STREAM_ID = "zoocall"
        const val STATS_SECONDS = 2.0
    }
}

/** Converts decoded I420 frames to Skia images off the UI thread, reusing the pixel buffer. */
internal class FrameTrack(private val track: VideoTrack) : DesktopVideoTrack {
    private val _frames = MutableStateFlow<ImageBitmap?>(null)
    override val frames: StateFlow<ImageBitmap?> = _frames.asStateFlow()
    private var pixels = ByteArray(0)

    // Two bitmaps per stage, alternated each frame: the UI can keep drawing the previous frame
    // while the next one is written, and nothing is allocated per frame (30 fps).
    private val decoded = arrayOfNulls<Bitmap>(2)
    private val rotated = arrayOfNulls<Bitmap>(2)
    private var slot = 0

    private val sink = VideoTrackSink { frame: VideoFrame ->
        runCatching {
            val i420 = frame.buffer.toI420()
            try {
                val w = i420.width
                val h = i420.height
                if (pixels.size != w * h * 4) pixels = ByteArray(w * h * 4)
                // libyuv "ARGB" is B,G,R,A in memory, which is Skia's BGRA_8888.
                VideoBufferConverter.convertFromI420(i420, pixels, FourCC.ARGB)
                slot = 1 - slot
                val bitmap = pooled(decoded, slot, w, h)
                bitmap.installPixels(pixels)
                _frames.value = rotate(bitmap, frame.rotation).asComposeImageBitmap()
            } finally {
                i420.release()
            }
        }
    }

    private fun pooled(pool: Array<Bitmap?>, index: Int, w: Int, h: Int): Bitmap {
        val existing = pool[index]
        if (existing != null && existing.width == w && existing.height == h) return existing
        return Bitmap().apply { allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)) }
            .also { pool[index] = it }
    }

    init {
        track.addSink(sink)
    }

    private fun rotate(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation % 360 == 0) return bitmap
        val swap = rotation % 180 != 0
        val out = pooled(rotated, slot, if (swap) bitmap.height else bitmap.width, if (swap) bitmap.width else bitmap.height)
        val canvas = org.jetbrains.skia.Canvas(out)
        canvas.translate(out.width / 2f, out.height / 2f)
        canvas.rotate(rotation.toFloat())
        canvas.translate(-bitmap.width / 2f, -bitmap.height / 2f)
        org.jetbrains.skia.Image.makeFromBitmap(bitmap).use { canvas.drawImage(it, 0f, 0f) }
        canvas.close()
        return out
    }

    fun dispose() {
        runCatching { track.removeSink(sink) }
    }
}

private fun createObserver(onSuccess: (RTCSessionDescription) -> Unit, onError: (Throwable) -> Unit) =
    object : CreateSessionDescriptionObserver {
        override fun onSuccess(description: RTCSessionDescription) = onSuccess(description)
        override fun onFailure(error: String?) = onError(IllegalStateException("SDP create failed: $error"))
    }

private suspend fun RTCPeerConnection.awaitSetLocal(description: RTCSessionDescription) = awaitSet { setLocalDescription(description, it) }

private suspend fun RTCPeerConnection.awaitSetRemote(description: RTCSessionDescription) = awaitSet { setRemoteDescription(description, it) }

private suspend fun awaitSet(block: (SetSessionDescriptionObserver) -> Unit) = suspendCancellableCoroutine { cont ->
    block(object : SetSessionDescriptionObserver {
        override fun onSuccess() = cont.resume(Unit)
        override fun onFailure(error: String?) = cont.resumeWithException(IllegalStateException("SDP set failed: $error"))
    })
}
