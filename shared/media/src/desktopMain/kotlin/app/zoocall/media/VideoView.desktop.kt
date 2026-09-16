package app.zoocall.media

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.StateFlow

/** A desktop video track exposes decoded frames as Compose images (see DesktopMediaEngine). */
interface DesktopVideoTrack : VideoTrackHandle {
    val frames: StateFlow<androidx.compose.ui.graphics.ImageBitmap?>
}

@Composable
actual fun VideoView(track: VideoTrackHandle?, modifier: Modifier, mirror: Boolean, fill: Boolean, overlay: Boolean) {
    val desktop = track as? DesktopVideoTrack ?: return
    val frame by desktop.frames.collectAsState()
    Canvas(modifier) {
        val image = frame ?: return@Canvas
        val scale = if (fill) {
            maxOf(size.width / image.width, size.height / image.height)
        } else {
            minOf(size.width / image.width, size.height / image.height)
        }
        val w = image.width * scale
        val h = image.height * scale
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        scale(scaleX = if (mirror) -1f else 1f, scaleY = 1f, pivot = Offset(size.width / 2, size.height / 2)) {
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(left.toInt(), top.toInt()),
                dstSize = IntSize(w.toInt(), h.toInt()),
            )
        }
    }
}
