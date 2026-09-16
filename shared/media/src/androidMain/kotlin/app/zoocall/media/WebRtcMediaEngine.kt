package app.zoocall.media

import android.content.Context
import android.content.Intent
import app.zoocall.core.model.CallId
import app.zoocall.core.model.CallKind
import app.zoocall.core.model.Logger
import org.webrtc.Camera2Enumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * libwebrtc for Android. One factory for the process; sessions are cheap.
 * Hardware AEC/NS are preferred, libwebrtc's software AEC3/NS run as fallback.
 */
class WebRtcMediaEngine(context: Context, private val logger: Logger = Logger.current) : MediaEngine {
    private val appContext = context.applicationContext

    val eglBase: EglBase by lazy { EglBase.create() }

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        val audioModule = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .setAudioRecordErrorCallback(null)
            .createAudioDeviceModule()
        val options = PeerConnectionFactory.Options().apply {
            // LAN only: never use cellular or VPN interfaces for media.
            networkIgnoreMask = PeerConnectionFactory.Options.ADAPTER_TYPE_CELLULAR or
                PeerConnectionFactory.Options.ADAPTER_TYPE_VPN or
                PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK
        }
        PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
            .also { audioModule.release() }
    }

    override val hasCamera: Boolean by lazy {
        runCatching { Camera2Enumerator(appContext).deviceNames.isNotEmpty() }.getOrDefault(false)
    }

    override fun createVoiceRecorder(): VoiceRecorder = AndroidVoiceRecorder(logger)

    override val createCallRecorderSupported: Boolean = true

    override fun createCallRecorder(output: (ByteArray) -> Unit, onLimitReached: () -> Unit): CallRecording =
        CallRecorder(output, logger, onLimitReached = onLimitReached)

    override fun decodeRecording(audio: ByteArray, onPcm: (ShortArray) -> Unit) = OpusVoiceCodec.forEachFrame(audio) { _, pcm -> onPcm(pcm) }

    override val voicePlayer: VoicePlayer by lazy { AndroidVoicePlayer(logger) }

    /** Group calls open a peer connection per person; they all share one camera capture. */
    private val camera by lazy { SharedCamera(appContext, { factory }, eglBase, logger) }

    private val screen by lazy { SharedScreen(appContext, { factory }, eglBase, logger) }

    /** Android shares the whole screen: one source, usable once the user allowed it ([grantScreenCapture]). */
    override val screenCapture: ScreenCapture = object : ScreenCapture {
        override fun sources() = listOf(ScreenSource(id = 0, title = "", isWindow = false))
    }

    /** The result of the system's screen-capture consent, used by the next share. */
    fun grantScreenCapture(data: Intent) = screen.grant(data)

    override fun createSession(callId: CallId, kind: CallKind, isOfferer: Boolean): MediaSession =
        WebRtcMediaSession(factory, eglBase, camera, screen, kind, isOfferer, hasCamera, logger)
}
