package app.zoocall.media

import android.content.Context
import app.zoocall.core.model.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource

/**
 * One camera capture shared by every call leg. A group call has a peer connection per person,
 * and the camera can only be opened once, so each leg makes its own track from this source.
 * Capture runs while at least one leg wants the camera on (the OS camera indicator follows it).
 */
internal class SharedCamera(
    private val context: Context,
    private val factory: () -> PeerConnectionFactory,
    private val eglBase: EglBase,
    private val logger: Logger,
) {
    private val lock = Any()
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private val users = HashSet<Any>()
    private val enabledUsers = HashSet<Any>()
    private var capturing = false

    private val _isFrontCamera = MutableStateFlow(true)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    /** The shared source, opening the camera for the first user. Null when there's no usable camera. */
    fun acquire(user: Any): VideoSource? = synchronized(lock) {
        source?.let {
            users += user
            enabledUsers += user
            updateCapture()
            return it
        }
        runCatching {
            val enumerator = Camera2Enumerator(context)
            val names = enumerator.deviceNames
            val name = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.first()
            _isFrontCamera.value = enumerator.isFrontFacing(name)
            val cam = enumerator.createCapturer(name, null)
            val textureHelper = SurfaceTextureHelper.create("zoocall-capture", eglBase.eglBaseContext)
            val videoSource = factory().createVideoSource(false)
            cam.initialize(textureHelper, context, videoSource.capturerObserver)
            capturer = cam
            helper = textureHelper
            source = videoSource
            users += user
            enabledUsers += user
            updateCapture()
            videoSource
        }.onFailure {
            // Camera busy or permission missing: calls continue with audio + received video (edge case C14).
            logger.warn(TAG, "Camera unavailable", it)
            releaseAll()
        }.getOrNull()
    }

    fun setEnabled(user: Any, enabled: Boolean) = synchronized(lock) {
        if (user !in users) return
        if (enabled) enabledUsers += user else enabledUsers -= user
        updateCapture()
    }

    fun release(user: Any) = synchronized(lock) {
        users -= user
        enabledUsers -= user
        if (users.isEmpty()) releaseAll() else updateCapture()
    }

    fun switchCamera() {
        val cam = synchronized(lock) { capturer } ?: return
        cam.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                _isFrontCamera.value = isFrontCamera
            }
            override fun onCameraSwitchError(error: String?) = logger.warn(TAG, "Camera switch failed: $error")
        })
    }

    private fun updateCapture() {
        val cam = capturer ?: return
        val wanted = enabledUsers.isNotEmpty()
        if (wanted == capturing) return
        runCatching { if (wanted) cam.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS) else cam.stopCapture() }
            .onFailure { logger.warn(TAG, "Camera capture change failed", it) }
        capturing = wanted
    }

    private fun releaseAll() {
        if (capturing) runCatching { capturer?.stopCapture() }
        capturing = false
        capturer?.dispose()
        helper?.dispose()
        source?.dispose()
        capturer = null
        helper = null
        source = null
        users.clear()
        enabledUsers.clear()
    }

    private companion object {
        const val TAG = "Media"
        const val CAPTURE_WIDTH = 1280
        const val CAPTURE_HEIGHT = 720
        const val CAPTURE_FPS = 30
    }
}
