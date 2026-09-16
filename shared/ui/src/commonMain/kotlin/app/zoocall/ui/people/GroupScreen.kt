package app.zoocall.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.SettingsVoice
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zoocall.core.app.CallStartResult
import app.zoocall.core.app.Person
import app.zoocall.core.model.CallKind
import app.zoocall.core.model.Fingerprint
import app.zoocall.core.store.ContactGroup
import app.zoocall.ui.LocalCore
import app.zoocall.ui.LocalSnackbar
import app.zoocall.ui.common.DetailScaffold
import app.zoocall.ui.common.rememberCallLauncher
import app.zoocall.ui.components.Avatar
import app.zoocall.ui.components.SectionHeader
import app.zoocall.ui.components.formatDuration
import app.zoocall.ui.platform.LocalPlatformActions
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.action_cancel
import app.zoocall.ui.resources.action_delete
import app.zoocall.ui.resources.call_already_in_call
import app.zoocall.ui.resources.call_mic_permission
import app.zoocall.ui.resources.group_announce
import app.zoocall.ui.resources.group_announce_hint
import app.zoocall.ui.resources.group_announce_record
import app.zoocall.ui.resources.group_announce_send
import app.zoocall.ui.resources.group_announce_sent
import app.zoocall.ui.resources.group_announce_stop
import app.zoocall.ui.resources.group_announce_title
import app.zoocall.ui.resources.group_call
import app.zoocall.ui.resources.group_chat_action
import app.zoocall.ui.resources.group_chat_too_big
import androidx.compose.material.icons.automirrored.rounded.Chat
import app.zoocall.ui.resources.group_create
import app.zoocall.ui.resources.group_delete
import app.zoocall.ui.resources.group_delete_confirm
import app.zoocall.ui.resources.group_edit
import app.zoocall.ui.resources.group_empty_members
import app.zoocall.ui.resources.group_members
import app.zoocall.ui.resources.group_name
import app.zoocall.ui.resources.group_new
import app.zoocall.ui.resources.group_no_contacts
import app.zoocall.ui.resources.group_nobody_reachable
import app.zoocall.ui.resources.group_people_online
import app.zoocall.ui.resources.group_ptt
import app.zoocall.ui.resources.group_save
import app.zoocall.ui.resources.group_video
import app.zoocall.ui.resources.ptt_not_supported
import app.zoocall.ui.resources.unknown_person
import app.zoocall.ui.theme.Spacing
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** A local contact group: call, push-to-talk or announce to everyone in it (docs/01-features.md). */
@Composable
fun GroupScreen(groupId: String, onBack: () -> Unit, onOpenPerson: (String) -> Unit, onOpenGroupChat: (String) -> Unit = {}) {
    val core = LocalCore.current
    val platform = LocalPlatformActions.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val launcher = rememberCallLauncher()
    val groups by core.groups.collectAsStateWithLifecycle(null)
    val people by core.people.collectAsStateWithLifecycle()
    val group = groups?.firstOrNull { it.id == groupId }
    var editOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var announceOpen by remember { mutableStateOf(false) }

    // Deleted (here or elsewhere): leave the screen.
    if (groups != null && group == null) LaunchedEffect(Unit) { onBack() }

    fun startGroup(video: Boolean, begin: suspend () -> CallStartResult) {
        scope.launch {
            if (!platform.ensureCallPermissions(video)) {
                snackbar.showSnackbar(getString(Res.string.call_mic_permission))
                return@launch
            }
            val message = when (begin()) {
                CallStartResult.Started -> null
                CallStartResult.AlreadyInCall -> getString(Res.string.call_already_in_call)
                CallStartResult.NotSupported -> getString(Res.string.ptt_not_supported, group?.name.orEmpty())
                else -> getString(Res.string.group_nobody_reachable)
            }
            message?.let { snackbar.showSnackbar(it) }
        }
    }

    DetailScaffold(
        title = group?.name.orEmpty(),
        onBack = onBack,
        actions = {
            IconButton(onClick = { editOpen = true }, enabled = group != null) { Icon(Icons.Rounded.Edit, stringResource(Res.string.group_edit)) }
            IconButton(onClick = { deleteOpen = true }, enabled = group != null) { Icon(Icons.Rounded.Delete, stringResource(Res.string.group_delete)) }
        },
    ) { padding ->
        if (group == null) return@DetailScaffold
        val members = group.members.map { fp -> fp to people.firstOrNull { it.fingerprint == fp } }
        val online = members.count { it.second?.online == true }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(96.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Groups, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(48.dp))
                    }
                }
                Text(group.name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                Text(
                    stringResource(Res.string.group_people_online, group.members.size.toString(), online.toString()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = Spacing.l), horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                    GroupAction(Icons.AutoMirrored.Rounded.Chat, stringResource(Res.string.group_chat_action), enabled = group.members.isNotEmpty()) {
                        scope.launch {
                            val chatId = core.startGroupChat(group.id)
                            if (chatId != null) onOpenGroupChat(chatId) else snackbar.showSnackbar(getString(Res.string.group_chat_too_big))
                        }
                    }
                    GroupAction(Icons.Rounded.Call, stringResource(Res.string.group_call), enabled = online > 0) {
                        startGroup(video = false) { core.callGroup(group.id, CallKind.Audio) }
                    }
                    GroupAction(Icons.Rounded.Videocam, stringResource(Res.string.group_video), enabled = online > 0) {
                        startGroup(video = true) { core.callGroup(group.id, CallKind.Video) }
                    }
                    GroupAction(Icons.Rounded.SettingsVoice, stringResource(Res.string.group_ptt), enabled = online > 0) {
                        startGroup(video = false) { core.pushToTalkGroup(group.id) }
                    }
                    GroupAction(Icons.Rounded.Campaign, stringResource(Res.string.group_announce), enabled = group.members.isNotEmpty()) {
                        announceOpen = true
                    }
                }
            }
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                SectionHeader(stringResource(Res.string.group_members))
                if (members.isEmpty()) {
                    Text(
                        stringResource(Res.string.group_empty_members),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                    )
                }
                members.forEach { (fp, person) ->
                    if (person != null) {
                        PersonRow(person, onClick = { onOpenPerson(person.id) }, onCall = { kind -> launcher.call(person.id, person.displayName, kind) })
                    } else {
                        ListItem(headlineContent = { Text(stringResource(Res.string.unknown_person)) }, supportingContent = { Text(fp.tag) })
                    }
                }
            }
        }
    }

    if (group != null && editOpen) {
        GroupEditorDialog(
            title = stringResource(Res.string.group_edit),
            confirmLabel = stringResource(Res.string.group_save),
            initialName = group.name,
            initialMembers = group.members.toSet(),
            onDismiss = { editOpen = false },
            onSave = { name, chosen ->
                editOpen = false
                scope.launch {
                    core.renameGroup(group.id, name)
                    core.setGroupMembers(group.id, chosen)
                }
            },
        )
    }
    if (group != null && deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text(stringResource(Res.string.group_delete)) },
            text = { Text(stringResource(Res.string.group_delete_confirm, group.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteOpen = false
                    scope.launch { core.deleteGroup(group.id) }
                }) { Text(stringResource(Res.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
    if (group != null && announceOpen) {
        AnnounceDialog(group, onDismiss = { announceOpen = false })
    }
}

/** A group in the People list. */
@Composable
internal fun GroupRow(group: ContactGroup, people: List<Person>, onClick: () -> Unit) {
    val online = group.members.count { fp -> people.any { it.fingerprint == fp && it.online } }
    ListItem(
        headlineContent = { Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(stringResource(Res.string.group_people_online, group.members.size.toString(), online.toString())) },
        leadingContent = {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Groups, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Creates or edits a group: a name and which contacts are in it. */
@Composable
internal fun GroupEditorDialog(
    title: String = stringResource(Res.string.group_new),
    confirmLabel: String = stringResource(Res.string.group_create),
    initialName: String = "",
    initialMembers: Set<Fingerprint> = emptySet(),
    onDismiss: () -> Unit,
    onSave: (String, Set<Fingerprint>) -> Unit,
) {
    val core = LocalCore.current
    val people by core.people.collectAsState()
    val contacts = people.filter { it.isContact && !it.blocked && it.fingerprint != null }
    var name by rememberSaveable { mutableStateOf(initialName) }
    var chosen by remember { mutableStateOf(initialMembers) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(Res.string.group_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(Res.string.group_members), style = MaterialTheme.typography.labelLarge)
                if (contacts.isEmpty()) {
                    Text(stringResource(Res.string.group_no_contacts), style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(contacts, key = { it.id }) { person ->
                        val fp = person.fingerprint ?: return@items
                        val checked = fp in chosen
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .toggleable(value = checked, role = Role.Checkbox) { on -> chosen = if (on) chosen + fp else chosen - fp },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Avatar(person.displayName, size = 36.dp, modifier = Modifier.padding(start = Spacing.s))
                            Text(person.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Spacing.m))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), chosen) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

/** "Meeting in 5 min": a text or voice announcement that alerts everyone in the group once. */
@Composable
private fun AnnounceDialog(group: ContactGroup, onDismiss: () -> Unit) {
    val core = LocalCore.current
    val platform = LocalPlatformActions.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    val recorder = remember { core.mediaEngine.createVoiceRecorder() }
    val recording by recorder.isRecording.collectAsState()
    val elapsed by recorder.elapsedMs.collectAsState()
    DisposableEffect(recorder) { onDispose { if (recorder.isRecording.value) recorder.cancel() } }

    fun done(count: Int) {
        onDismiss()
        scope.launch { snackbar.showSnackbar(getString(Res.string.group_announce_sent, count.toString())) }
    }

    AlertDialog(
        onDismissRequest = { if (!recording) onDismiss() },
        title = { Text(stringResource(Res.string.group_announce_title, group.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(1_000) },
                    placeholder = { Text(stringResource(Res.string.group_announce_hint)) },
                    enabled = !recording,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = {
                            scope.launch {
                                if (recording) {
                                    val clip = recorder.stop() ?: return@launch
                                    done(core.sendVoiceAnnouncement(group.id, clip))
                                } else if (platform.ensureCallPermissions(video = false)) {
                                    recorder.start()
                                }
                            }
                        },
                    ) {
                        Icon(
                            if (recording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                            stringResource(if (recording) Res.string.group_announce_stop else Res.string.group_announce_record),
                        )
                    }
                    Text(
                        if (recording) formatDuration(elapsed) else stringResource(Res.string.group_announce_record),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = Spacing.m).clearAndSetSemantics { },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { scope.launch { done(core.sendAnnouncement(group.id, text)) } },
                enabled = text.isNotBlank() && !recording,
            ) { Text(stringResource(Res.string.group_announce_send)) }
        },
        dismissButton = {
            TextButton(onClick = {
                if (recording) recorder.cancel()
                onDismiss()
            }) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

@Composable
private fun GroupAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 88.dp)) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp))
        }
        // The button already announces the label.
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.xs).clearAndSetSemantics { },
        )
    }
}
