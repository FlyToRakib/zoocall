package app.zoocall.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
actual fun VideoView(track: VideoTrackHandle?, modifier: Modifier, mirror: Boolean, fill: Boolean, overlay: Boolean) {
    val handle = track as? AndroidVideoTrack ?: return
    val holder = remember { RendererHolder() }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                // Two SurfaceViews overlap during a video call; the self view must sit above the remote one.
                if (overlay) setZOrderMediaOverlay(true)
                init(handle.eglBase.eglBaseContext, null)
                setEnableHardwareScaler(true)
                holder.renderer = this
            }
        },
        update = { renderer ->
            renderer.setMirror(mirror)
            renderer.setScalingType(
                if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT,
            )
        },
    )
    DisposableEffect(handle) {
        val renderer = holder.renderer
        renderer?.let { runCatching { handle.track.addSink(it) } }
        onDispose {
            renderer?.let { runCatching { handle.track.removeSink(it) } }
        }
    }
    DisposableEffect(Unit) {
        onDispose { holder.renderer?.release() }
    }
}

private class RendererHolder {
    var renderer: SurfaceViewRenderer? = null
}
