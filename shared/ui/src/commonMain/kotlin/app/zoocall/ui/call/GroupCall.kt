package app.zoocall.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zoocall.core.call.ActiveCall
import app.zoocall.core.call.AddParticipantResult
import app.zoocall.core.call.CallPhase
import app.zoocall.core.call.MemberPhase
import app.zoocall.core.model.CallKind
import app.zoocall.core.model.Fingerprint
import app.zoocall.media.MediaSession
import app.zoocall.media.VideoTrackHandle
import app.zoocall.media.VideoView
import app.zoocall.ui.LocalCore
import app.zoocall.ui.components.Avatar
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.action_cancel
import app.zoocall.ui.resources.call_add_full
import app.zoocall.ui.resources.call_add_none
import app.zoocall.ui.resources.call_add_not_supported
import app.zoocall.ui.resources.call_add_title
import app.zoocall.ui.resources.call_transfer_title
import app.zoocall.ui.resources.call_add_unreachable
import app.zoocall.ui.resources.call_member_audio_only
import app.zoocall.ui.resources.call_member_calling
import app.zoocall.ui.resources.call_member_connecting
import app.zoocall.ui.resources.call_member_muted
import app.zoocall.ui.resources.call_member_reconnecting
import app.zoocall.ui.resources.call_member_sharing
import app.zoocall.ui.resources.unknown_person
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private enum class TileStatus { Calling, Connecting, Connected, Reconnecting }

private class Tile(
    val fingerprint: Fingerprint,
    val media: MediaSession?,
    val video: Boolean,
    val cameraOn: Boolean,
    val micMuted: Boolean,
    val status: TileStatus,
    val screenSharing: Boolean,
)

private val noTrack = MutableStateFlow<VideoTrackHandle?>(null)
private val TileColor = Color(0xFF1C2624)

/** Everyone in a group call as tiles: video when their camera is on, otherwise their avatar. */
@Composable
internal fun GroupGrid(call: ActiveCall, modifier: Modifier = Modifier) {
    val video = call.state.kind == CallKind.Video
    val tiles = buildList {
        add(
            Tile(
                fingerprint = call.state.peer,
                media = call.media,
                video = video,
                cameraOn = call.remoteCameraOn,
                micMuted = call.remoteMicMuted,
                status = when (call.state.phase) {
                    CallPhase.Connected -> TileStatus.Connected
                    CallPhase.Reconnecting -> TileStatus.Reconnecting
                    else -> TileStatus.Connecting
                },
                screenSharing = call.remoteScreenSharing,
            ),
        )
        call.members.forEach { member ->
            add(
                Tile(
                    fingerprint = member.fingerprint,
                    media = member.media,
                    video = member.kind == CallKind.Video,
                    cameraOn = member.cameraOn,
                    micMuted = member.micMuted,
                    status = when (member.phase) {
                        MemberPhase.Invited, MemberPhase.Ringing -> TileStatus.Calling
                        MemberPhase.Joining, MemberPhase.Connecting -> TileStatus.Connecting
                        MemberPhase.Connected -> TileStatus.Connected
                        MemberPhase.Reconnecting -> TileStatus.Reconnecting
                    },
                    screenSharing = member.screenSharing,
                ),
            )
        }
    }
    BoxWithConstraints(modifier) {
        val landscape = maxWidth > maxHeight
        val columns = when {
            tiles.size <= 1 -> 1
            tiles.size == 2 -> if (landscape) 2 else 1
            tiles.size <= 4 -> 2
            else -> if (landscape) 4 else 2
        }
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.chunked(columns).forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { tile -> ParticipantTile(tile, callVideo = video, modifier = Modifier.weight(1f).fillMaxHeight()) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ParticipantTile(tile: Tile, callVideo: Boolean, modifier: Modifier) {
    val people by LocalCore.current.people.collectAsState()
    val name = people.firstOrNull { it.fingerprint == tile.fingerprint }?.displayName ?: stringResource(Res.string.unknown_person)
    val track by (tile.media?.remoteVideo ?: noTrack).collectAsState()
    val showVideo = ((tile.video && tile.cameraOn) || tile.screenSharing) && tile.status == TileStatus.Connected && track != null
    val status = when (tile.status) {
        TileStatus.Calling -> stringResource(Res.string.call_member_calling)
        TileStatus.Connecting -> stringResource(Res.string.call_member_connecting)
        TileStatus.Reconnecting -> stringResource(Res.string.call_member_reconnecting)
        TileStatus.Connected -> when {
            tile.screenSharing -> stringResource(Res.string.call_member_sharing)
            tile.micMuted -> stringResource(Res.string.call_member_muted)
            callVideo && !tile.video -> stringResource(Res.string.call_member_audio_only)
            else -> null
        }
    }
    val description = listOfNotNull(name, status).joinToString(", ")
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(TileColor)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        if (showVideo) {
            VideoView(track, Modifier.fillMaxSize(), fill = !tile.screenSharing)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Avatar(name, size = 72.dp)
            }
        }
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tile.micMuted) Icon(Icons.Rounded.MicOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp).padding(end = 4.dp))
            Text(
                if (status != null) "$name · $status" else name,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Picks someone nearby to invite into the call. Only people who can join are listed. */
@Composable
internal fun AddPeopleDialog(
    call: ActiveCall,
    onDismiss: () -> Unit,
    /** Transfer the call to the chosen person instead of adding them. */
    transfer: Boolean = false,
) {
    val core = LocalCore.current
    val scope = rememberCoroutineScope()
    val people by core.people.collectAsState()
    val inCall = remember(call.state.peer, call.members) { setOf(call.state.peer) + call.members.map { it.fingerprint } }
    val candidates = people.filter { it.online && !it.blocked && it.fingerprint != null && it.fingerprint !in inCall }
    var working by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (transfer) Res.string.call_transfer_title else Res.string.call_add_title)) },
        text = {
            Column {
                if (candidates.isEmpty()) {
                    Text(stringResource(Res.string.call_add_none), style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(candidates, key = { it.id }) { person ->
                        ListItem(
                            headlineContent = { Text(person.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = person.role.takeIf { it.isNotBlank() }?.let { role -> { Text(role, maxLines = 1) } },
                            leadingContent = { Avatar(person.displayName, size = 40.dp, presence = person.presence, online = true, ringColor = MaterialTheme.colorScheme.surfaceContainerHigh) },
                            trailingContent = { if (working == person.id) CircularProgressIndicator(Modifier.size(24.dp)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable(enabled = working == null) {
                                working = person.id
                                error = null
                                scope.launch {
                                    val result = if (transfer) core.transferCall(person.id) else core.addToCall(person.id)
                                    working = null
                                    when (result) {
                                        AddParticipantResult.Invited -> onDismiss()
                                        AddParticipantResult.Full -> error = getString(Res.string.call_add_full)
                                        AddParticipantResult.NotSupported -> error = getString(Res.string.call_add_not_supported, person.displayName)
                                        else -> error = getString(Res.string.call_add_unreachable, person.displayName)
                                    }
                                }
                            },
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}
