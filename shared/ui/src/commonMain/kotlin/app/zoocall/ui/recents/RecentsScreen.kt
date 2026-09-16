package app.zoocall.ui.recents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zoocall.core.model.CallDirection
import app.zoocall.core.model.CallKind
import app.zoocall.core.model.EndReason
import app.zoocall.ui.LocalCore
import app.zoocall.ui.common.rememberCallLauncher
import app.zoocall.ui.components.Avatar
import app.zoocall.ui.components.AvatarButton
import app.zoocall.ui.components.EmptyState
import app.zoocall.ui.components.formatDuration
import app.zoocall.ui.components.formatShortTimestamp
import app.zoocall.ui.components.readableContentWidth
import app.zoocall.ui.resources.unknown_person
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.action_cancel
import app.zoocall.ui.resources.action_delete
import app.zoocall.ui.resources.app_name
import app.zoocall.ui.resources.nav_recents
import app.zoocall.ui.resources.nav_settings
import app.zoocall.ui.resources.recents_audio
import app.zoocall.ui.resources.recents_call_back
import app.zoocall.ui.resources.recents_clear
import app.zoocall.ui.resources.recents_clear_confirm
import app.zoocall.ui.resources.recents_declined
import app.zoocall.ui.resources.recents_empty_body
import app.zoocall.ui.resources.recents_empty_title
import app.zoocall.ui.resources.recents_incoming
import app.zoocall.ui.resources.recents_missed
import app.zoocall.ui.resources.recents_no_answer
import app.zoocall.ui.resources.recents_outgoing
import app.zoocall.ui.resources.recents_video
import app.zoocall.ui.resources.recents_recorded
import app.zoocall.ui.resources.recents_recording_save
import app.zoocall.ui.platform.LocalPlatformActions
import androidx.compose.material.icons.rounded.SaveAlt
import app.zoocall.ui.theme.Spacing
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentsScreen(onOpenPerson: (String) -> Unit, onOpenSettings: () -> Unit) {
    val core = LocalCore.current
    val scope = rememberCoroutineScope()
    val recents by core.recents.collectAsStateWithLifecycle(emptyList())
    val people by core.people.collectAsStateWithLifecycle()
    val settings by core.settings.collectAsStateWithLifecycle()
    val launcher = rememberCallLauncher()
    val platform = LocalPlatformActions.current
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(recents.size) { core.markRecentsSeen() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.nav_recents)) },
                navigationIcon = {
                    val label = stringResource(Res.string.nav_settings)
                    AvatarButton(
                        name = settings.displayName,
                        contentDescription = label,
                        onClick = onOpenSettings,
                        presence = settings.presence,
                        online = core.network.collectAsStateWithLifecycle().value.onLocalNetwork,
                        modifier = Modifier.padding(start = Spacing.xs),
                    )
                },
                actions = {
                    if (recents.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Rounded.DeleteSweep, stringResource(Res.string.recents_clear))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (recents.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
                EmptyState(Icons.Rounded.History, stringResource(Res.string.recents_empty_title), stringResource(Res.string.recents_empty_body))
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding).readableContentWidth()) {
            items(recents, key = { it.callId }) { entry ->
                val person = people.firstOrNull { it.fingerprint == entry.peer }
                val name = person?.displayName ?: stringResource(Res.string.unknown_person)
                val missed = entry.isMissed
                val (icon, label) = when {
                    missed -> Icons.AutoMirrored.Rounded.CallMissed to stringResource(Res.string.recents_missed)
                    entry.direction == CallDirection.Outgoing && entry.endReason == EndReason.Missed -> Icons.AutoMirrored.Rounded.CallMade to stringResource(Res.string.recents_no_answer)
                    entry.endReason == EndReason.Declined -> (if (entry.direction == CallDirection.Outgoing) Icons.AutoMirrored.Rounded.CallMade else Icons.AutoMirrored.Rounded.CallReceived) to stringResource(Res.string.recents_declined)
                    entry.direction == CallDirection.Outgoing -> Icons.AutoMirrored.Rounded.CallMade to stringResource(Res.string.recents_outgoing)
                    else -> Icons.AutoMirrored.Rounded.CallReceived to stringResource(Res.string.recents_incoming)
                }
                val kindLabel = stringResource(if (entry.kind == CallKind.Video) Res.string.recents_video else Res.string.recents_audio)
                val recordedLabel = entry.recordingMs?.let { stringResource(Res.string.recents_recorded) }
                val details = listOfNotNull(label, kindLabel, entry.durationMs?.let(::formatDuration), recordedLabel, formatShortTimestamp(entry.startedAtMs))
                    .joinToString(" · ")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .clickable { onOpenPerson(entry.peer.hex) }
                        .padding(horizontal = Spacing.l, vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(name, presence = person?.presence, online = person?.online == true)
                    Column(Modifier.weight(1f).padding(horizontal = Spacing.l).semantics(mergeDescendants = true) {}) {
                        Text(
                            name,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, tint = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Text(
                                details,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = Spacing.xs),
                            )
                        }
                    }
                    if (entry.recordingMs != null) {
                        IconButton(onClick = {
                            scope.launch {
                                val file = core.exportRecording(entry.callId) ?: return@launch
                                val date = kotlin.time.Instant.fromEpochMilliseconds(entry.startedAtMs).toString().take(10)
                                platform.saveFile(file, "zoocall-call-$date.wav", "audio/wav")
                            }
                        }) {
                            Icon(Icons.Rounded.SaveAlt, stringResource(Res.string.recents_recording_save, name))
                        }
                    }
                    IconButton(onClick = { launcher.call(entry.peer.hex, name, entry.kind) }) {
                        Icon(
                            if (entry.kind == CallKind.Video) Icons.Rounded.Videocam else Icons.Rounded.Call,
                            stringResource(Res.string.recents_call_back, name),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(Res.string.recents_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { core.clearRecents() }
                }) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}
