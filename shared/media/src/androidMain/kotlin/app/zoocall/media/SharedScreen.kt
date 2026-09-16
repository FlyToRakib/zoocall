package app.zoocall.media

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import app.zoocall.core.model.Logger
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource

/**
 * One screen capture (MediaProjection) shared by every call leg, like [SharedCamera]. Android asks
 * the user before each share; [grant] hands over that consent, which the next capture uses once.
 */
internal class SharedScreen(
    private val context: Context,
    private val factory: () -> PeerConnectionFactory,
    private val eglBase: EglBase,
    private val logger: Logger,
) {
    private val lock = Any()
    private var consent: Intent? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    /** Each leg using the capture, with what to do if the system stops it. */
    private val users = HashMap<Any, () -> Unit>()

    fun grant(data: Intent) = synchronized(lock) { consent = data }

    /** The shared source, starting capture for the first user. Null without consent or when capture fails. */
    fun acquire(user: Any, onStopped: () -> Unit): VideoSource? = synchronized(lock) {
        source?.let {
            users[user] = onStopped
            return it
        }
        val data = consent ?: return null
        consent = null
        runCatching {
            val screenCapturer = ScreenCapturerAndroid(
                data,
                object : MediaProjection.Callback() {
                    override fun onStop() = stoppedBySystem()
                },
            )
            val textureHelper = SurfaceTextureHelper.create("zoocall-screen", eglBase.eglBaseContext)
            val videoSource = factory().createVideoSource(true)
            screenCapturer.initialize(textureHelper, context, videoSource.capturerObserver)
            val metrics = context.resources.displayMetrics
            val scale = minOf(1.0, MAX_EDGE.toDouble() / maxOf(metrics.widthPixels, metrics.heightPixels))
            // Even dimensions keep hardware encoders happy.
            screenCapturer.startCapture((metrics.widthPixels * scale).toInt() and 1.inv(), (metrics.heightPixels * scale).toInt() and 1.inv(), CAPTURE_FPS)
            capturer = screenCapturer
            helper = textureHelper
            source = videoSource
            users[user] = onStopped
            videoSource
        }.onFailure {
            logger.warn(TAG, "Screen capture unavailable", it)
            releaseAll()
        }.getOrNull()
    }

    fun release(user: Any) = synchronized(lock) {
        users -= user
        if (users.isEmpty()) releaseAll()
    }

    /** The user stopped it from the system UI, or another app took the projection. */
    private fun stoppedBySystem() {
        val listeners = synchronized(lock) { users.values.toList() }
        listeners.forEach { it() }
    }

    private fun releaseAll() {
        // Clear first: stopping the capture calls onStop, which must not report our own stop.
        users.clear()
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        helper?.dispose()
        source?.dispose()
        capturer = null
        helper = null
        source = null
    }

    private companion object {
        const val TAG = "Media"
        const val MAX_EDGE = 1280
        const val CAPTURE_FPS = 15
    }
}
