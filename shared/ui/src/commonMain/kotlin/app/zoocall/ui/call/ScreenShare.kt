package app.zoocall.ui.call

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zoocall.media.ScreenSource
import app.zoocall.ui.LocalCore
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.action_cancel
import app.zoocall.ui.resources.call_share_failed
import app.zoocall.ui.resources.call_share_none
import app.zoocall.ui.resources.call_share_screens
import app.zoocall.ui.resources.call_share_title
import app.zoocall.ui.resources.call_share_windows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Picks a screen or an app window to share (desktop). */
@Composable
internal fun ScreenSharePicker(onDismiss: () -> Unit) {
    val core = LocalCore.current
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf<List<ScreenSource>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        sources = withContext(Dispatchers.Default) { core.mediaEngine.screenCapture?.sources().orEmpty() }
    }

    fun share(source: ScreenSource) {
        working = true
        error = null
        scope.launch {
            val ok = core.startScreenShare(source)
            working = false
            if (ok) onDismiss() else error = getString(Res.string.call_share_failed)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.call_share_title)) },
        text = {
            val list = sources
            Column {
                when {
                    list == null || working -> CircularProgressIndicator(Modifier.size(32.dp))
                    list.isEmpty() -> Text(stringResource(Res.string.call_share_none))
                    else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        val screens = list.filter { !it.isWindow }
                        val windows = list.filter { it.isWindow }
                        if (screens.isNotEmpty()) item { PickerHeader(stringResource(Res.string.call_share_screens)) }
                        items(screens, key = { "s${it.id}" }) { SourceRow(it) { share(it) } }
                        if (windows.isNotEmpty()) item { PickerHeader(stringResource(Res.string.call_share_windows)) }
                        items(windows, key = { "w${it.id}" }) { SourceRow(it) { share(it) } }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

@Composable
private fun PickerHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun SourceRow(source: ScreenSource, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(source.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(if (source.isWindow) Icons.Rounded.DesktopWindows else Icons.Rounded.Monitor, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
