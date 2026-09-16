package app.zoocall.media

import app.zoocall.core.model.Logger
import dev.onvoid.webrtc.media.video.VideoDesktopSource
import dev.onvoid.webrtc.media.video.desktop.DesktopCapturer
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer

/**
 * Screen sharing capture, shared by every call leg like the camera. Picking another screen while
 * sharing re-targets the running capture, so every leg switches together.
 */
internal class DesktopSharedScreen(private val logger: Logger) : ScreenCapture {
    private var source: VideoDesktopSource? = null
    private var current: ScreenSource? = null
    private val users = HashSet<Any>()

    override fun sources(): List<ScreenSource> {
        val screens = list(ScreenCapturer()).mapIndexed { index, s -> ScreenSource(s.id, s.title.ifBlank { "Screen ${index + 1}" }, isWindow = false) }
        val windows = list(WindowCapturer()).filter { it.title.isNotBlank() }.map { ScreenSource(it.id, it.title, isWindow = true) }
        return screens + windows
    }

    private fun list(capturer: DesktopCapturer) = runCatching {
        try {
            capturer.desktopSources
        } finally {
            capturer.dispose()
        }
    }.onFailure { logger.warn(TAG, "Listing shareable sources failed", it) }.getOrDefault(emptyList())

    @Synchronized
    fun acquire(user: Any, screen: ScreenSource): VideoDesktopSource? {
        source?.let { running ->
            if (current != screen) {
                runCatching {
                    running.stop()
                    running.setSourceId(screen.id, screen.isWindow)
                    running.start()
                    current = screen
                }.onFailure { logger.warn(TAG, "Switching the shared screen failed", it) }
            }
            users += user
            return running
        }
        return runCatching {
            VideoDesktopSource().apply {
                setSourceId(screen.id, screen.isWindow)
                // Text stays sharp at a modest frame rate; LAN bandwidth is plentiful.
                setFrameRate(FRAME_RATE)
                setMaxFrameSize(MAX_WIDTH, MAX_HEIGHT)
                start()
            }
        }.onSuccess {
            source = it
            current = screen
            users += user
        }.onFailure {
            logger.warn(TAG, "Screen capture failed", it)
        }.getOrNull()
    }

    @Synchronized
    fun release(user: Any) {
        users -= user
        if (users.isNotEmpty()) return
        runCatching { source?.stop() }
        runCatching { source?.dispose() }
        source = null
        current = null
    }

    private companion object {
        const val TAG = "Media"
        const val FRAME_RATE = 15
        const val MAX_WIDTH = 1920
        const val MAX_HEIGHT = 1080
    }
}
