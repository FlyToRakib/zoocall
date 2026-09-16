package app.zoocall.media

import app.zoocall.core.model.Logger
import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.video.VideoCaptureCapability
import dev.onvoid.webrtc.media.video.VideoDevice
import dev.onvoid.webrtc.media.video.VideoDeviceSource

/**
 * One camera capture shared by every call leg: a group call has a peer connection per person, and
 * a capture device can only be opened once. Capture runs while at least one leg wants the camera on.
 */
internal class DesktopSharedCamera(private val logger: Logger) {
    private var source: VideoDeviceSource? = null
    private val users = HashSet<Any>()
    private val enabledUsers = HashSet<Any>()
    private var capturing = false

    /** The device being captured, if any. */
    @Volatile var device: VideoDevice? = null
        private set

    /** The shared source, opening [camera] for the first user. Null when it can't be opened. */
    @Synchronized
    fun acquire(user: Any, camera: VideoDevice): VideoDeviceSource? {
        source?.let {
            users += user
            enabledUsers += user
            updateCapture()
            return it
        }
        return runCatching {
            VideoDeviceSource().apply {
                setVideoCaptureDevice(camera)
                setVideoCaptureCapability(bestCapability(camera))
            }
        }.onSuccess {
            source = it
            device = camera
            users += user
            enabledUsers += user
            updateCapture()
        }.onFailure {
            logger.warn(TAG, "Camera unavailable", it)
        }.getOrNull()
    }

    @Synchronized
    fun setEnabled(user: Any, enabled: Boolean) {
        if (user !in users) return
        if (enabled) enabledUsers += user else enabledUsers -= user
        updateCapture()
    }

    @Synchronized
    fun release(user: Any) {
        users -= user
        enabledUsers -= user
        if (users.isNotEmpty()) {
            updateCapture()
            return
        }
        runCatching { source?.stop() }
        runCatching { source?.dispose() }
        source = null
        device = null
        capturing = false
    }

    /** Switches the capture device of a running call without renegotiating (same tracks, new device). */
    @Synchronized
    fun use(camera: VideoDevice?) {
        val current = source ?: return
        if (camera == null || camera.descriptor == device?.descriptor) return
        runCatching {
            current.stop()
            current.setVideoCaptureDevice(camera)
            current.setVideoCaptureCapability(bestCapability(camera))
            if (capturing) current.start()
            device = camera
        }.onFailure {
            logger.warn(TAG, "Camera switch failed", it)
        }
    }

    private fun updateCapture() {
        val current = source ?: return
        val wanted = enabledUsers.isNotEmpty()
        if (wanted == capturing) return
        // Stopping the device turns the OS camera light off (docs/04 §6).
        runCatching { if (wanted) current.start() else current.stop() }.onFailure { logger.warn(TAG, "Camera capture change failed", it) }
        capturing = wanted
    }

    private companion object {
        const val TAG = "Media"
    }
}

internal fun bestCapability(device: VideoDevice): VideoCaptureCapability {
    val caps = runCatching { MediaDevices.getVideoCaptureCapabilities(device) }.getOrDefault(emptyList())
    return caps.filter { it.width <= 1280 && it.frameRate >= 24 }.maxByOrNull { it.width * it.height }
        ?: caps.minByOrNull { kotlin.math.abs(it.width - 1280) }
        ?: VideoCaptureCapability(1280, 720, 30)
}
