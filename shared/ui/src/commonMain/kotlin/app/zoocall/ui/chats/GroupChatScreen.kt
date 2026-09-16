package app.zoocall.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zoocall.core.model.MessageId
import app.zoocall.core.store.MessageState
import app.zoocall.ui.LocalCore
import app.zoocall.ui.LocalSnackbar
import app.zoocall.ui.components.Avatar
import app.zoocall.ui.components.EmptyState
import app.zoocall.ui.components.readableContentWidth
import app.zoocall.ui.platform.LocalPlatformActions
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.action_back
import app.zoocall.ui.resources.action_cancel
import app.zoocall.ui.resources.chat_copied
import app.zoocall.ui.resources.chat_delete_confirm_body
import app.zoocall.ui.resources.chat_delete_confirm_title
import app.zoocall.ui.resources.chat_delete_for_me
import app.zoocall.ui.resources.chat_input_placeholder
import app.zoocall.ui.resources.chat_mic_needed
import app.zoocall.ui.resources.chat_record_voice
import app.zoocall.ui.resources.chat_send
import app.zoocall.ui.resources.chat_voice_in_call
import app.zoocall.ui.resources.chat_voice_too_short
import app.zoocall.ui.resources.chat_voice_unavailable
import app.zoocall.ui.resources.group_chat_delivered
import app.zoocall.ui.resources.group_chat_empty
import app.zoocall.ui.resources.group_chat_leave
import app.zoocall.ui.resources.group_chat_leave_action
import app.zoocall.ui.resources.group_chat_leave_confirm
import app.zoocall.ui.resources.group_chat_options
import app.zoocall.ui.resources.group_chat_people
import app.zoocall.ui.resources.group_chat_read
import app.zoocall.ui.resources.unknown_person
import app.zoocall.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * A small group chat (docs/01 §5): text and voice notes to everyone in it, each member's copy
 * delivered separately, so the sender sees how many have it (edge case M11).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(groupId: String, onBack: () -> Unit, highlightMessageId: String? = null) {
    val core = LocalCore.current
    val platform = LocalPlatformActions.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val chat by remember(groupId) { core.groupChat(groupId) }.collectAsStateWithLifecycle(null)
    val messages by remember(groupId) { core.groupConversation(groupId) }.collectAsStateWithLifecycle(emptyList())
    val delivery by remember(groupId) { core.groupDelivery(groupId) }.collectAsStateWithLifecycle(emptyMap())
    val people by core.people.collectAsStateWithLifecycle()
    val name = chat?.name.orEmpty()
    val unknown = stringResource(Res.string.unknown_person)
    var draft by rememberSaveable { mutableStateOf("") }
    var replyToId by rememberSaveable { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<MessageId?>(null) }
    var confirmDelete by remember { mutableStateOf<MessageId?>(null) }
    var optionsOpen by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var highlighted by remember(highlightMessageId) { mutableStateOf(highlightMessageId) }
    val listState = rememberLazyListState()

    val rows = remember(messages) { buildRows(messages) }
    val byId = remember(messages) { messages.associateBy { it.id } }
    val replyTo = replyToId?.let { byId[MessageId(it)] }
    // Only the newest of our messages says how many people have it, to keep the chat readable.
    val lastOutgoing = remember(messages) { messages.lastOrNull { it.outgoing }?.id }

    val recorder = remember { core.mediaEngine.createVoiceRecorder() }
    val recording by recorder.isRecording.collectAsState()
    val elapsed by recorder.elapsedMs.collectAsState()
    val level by recorder.level.collectAsState()
    var recordingUi by remember { mutableStateOf(false) }
    DisposableEffect(recorder) {
        onDispose {
            recorder.cancel()
            core.mediaEngine.voicePlayer.stop()
        }
    }

    LaunchedEffect(groupId, messages.size) { core.markGroupRead(groupId) }

    var initialScrollDone by remember(groupId) { mutableStateOf(false) }
    LaunchedEffect(rows.size) {
        if (rows.isEmpty()) return@LaunchedEffect
        val target = highlightMessageId?.takeIf { !initialScrollDone }?.let { id -> rows.indexOfFirst { it.key == id } }?.takeIf { it >= 0 }
        when {
            target != null -> listState.scrollToItem(target)
            initialScrollDone -> listState.animateScrollToItem(rows.lastIndex)
            else -> listState.scrollToItem(rows.lastIndex)
        }
        initialScrollDone = true
    }
    LaunchedEffect(highlighted) {
        if (highlighted != null) {
            delay(2_500)
            highlighted = null
        }
    }

    fun scrollTo(id: MessageId) {
        val index = rows.indexOfFirst { it.key == id.value }
        if (index < 0) return
        highlighted = id.value
        scope.launch { listState.animateScrollToItem(index) }
    }

    fun send() {
        val text = draft
        if (text.isBlank()) return
        draft = ""
        val reply = replyToId?.let(::MessageId)
        replyToId = null
        scope.launch { core.sendGroupMessage(groupId, text, reply) }
    }

    fun startRecording() {
        scope.launch {
            if (core.activeCall.value?.state?.isActive == true) {
                snackbar.showSnackbar(getString(Res.string.chat_voice_in_call))
                return@launch
            }
            if (!platform.ensureCallPermissions(video = false)) {
                snackbar.showSnackbar(getString(Res.string.chat_mic_needed))
                return@launch
            }
            core.mediaEngine.voicePlayer.stop()
            if (recorder.start()) recordingUi = true else snackbar.showSnackbar(getString(Res.string.chat_voice_unavailable))
        }
    }

    fun finishRecording() {
        if (!recordingUi) return
        recordingUi = false
        val reply = replyToId?.let(::MessageId)
        scope.launch {
            val clip = recorder.stop()
            if (clip == null) {
                snackbar.showSnackbar(getString(Res.string.chat_voice_too_short))
            } else {
                core.sendGroupVoice(groupId, clip, reply)
                replyToId = null
            }
        }
    }

    fun cancelRecording() {
        recordingUi = false
        recorder.cancel()
    }

    LaunchedEffect(recording) {
        if (!recording && recordingUi) finishRecording()
    }

    val actions = MessageActions(
        onReply = { replyToId = it.id.value },
        onReact = { _, _ -> },
        onDelete = { confirmDelete = it.id },
        onDownload = {},
        onPauseDownload = {},
        onPauseUpload = {},
        onResumeUpload = {},
        onOpen = {},
        onSave = {},
        onCopied = { scope.launch { snackbar.showSnackbar(getString(Res.string.chat_copied)) } },
        onShowReply = ::scrollTo,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.action_back)) }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name, size = 40.dp)
                        Column(Modifier.padding(start = Spacing.m)) {
                            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                            chat?.let {
                                Text(
                                    stringResource(Res.string.group_chat_people, it.members.size.toString()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { optionsOpen = true }) { Icon(Icons.Rounded.MoreVert, stringResource(Res.string.group_chat_options)) }
                        DropdownMenu(expanded = optionsOpen, onDismissRequest = { optionsOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.group_chat_leave), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Logout, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    optionsOpen = false
                                    confirmLeave = true
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier
                            .navigationBarsPadding()
                            .imePadding()
                            .readableContentWidth(840.dp)
                            .padding(horizontal = Spacing.m, vertical = Spacing.m),
                    ) {
                        if (replyTo != null && !recordingUi) {
                            val author = people.firstOrNull { it.fingerprint == replyTo.peer }?.displayName ?: unknown
                            ReplyComposerBar(replyTo, author) { replyToId = null }
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            if (recordingUi) {
                                RecordingBar(elapsedMs = elapsed, level = level, onCancel = ::cancelRecording, onSend = ::finishRecording)
                            } else {
                                OutlinedTextField(
                                    value = draft,
                                    onValueChange = { draft = it.take(8_000) },
                                    placeholder = { Text(stringResource(Res.string.chat_input_placeholder)) },
                                    maxLines = 5,
                                    shape = MaterialTheme.shapes.extraLarge,
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                    modifier = Modifier
                                        .weight(1f)
                                        // Desktop: Enter sends, Shift+Enter inserts a new line.
                                        .onPreviewKeyEvent { event ->
                                            if (platform.isDesktop && event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                                                send()
                                                true
                                            } else {
                                                false
                                            }
                                        },
                                )
                                if (draft.isBlank()) {
                                    FilledIconButton(onClick = ::startRecording, modifier = Modifier.padding(start = Spacing.s, bottom = Spacing.xs).size(52.dp)) {
                                        Icon(Icons.Rounded.Mic, stringResource(Res.string.chat_record_voice))
                                    }
                                } else {
                                    FilledIconButton(onClick = ::send, modifier = Modifier.padding(start = Spacing.s, bottom = Spacing.xs).size(52.dp)) {
                                        Icon(Icons.AutoMirrored.Rounded.Send, stringResource(Res.string.chat_send))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).readableContentWidth(840.dp)) {
            if (rows.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                    EmptyState(Icons.Rounded.Groups, name, stringResource(Res.string.group_chat_empty, name))
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is ChatRow.Day -> DaySeparator(row.date)
                            is ChatRow.Message -> {
                                val record = row.record
                                val sender = if (record.outgoing) null else people.firstOrNull { it.fingerprint == record.peer }?.displayName ?: unknown
                                val counts = delivery[record.id]?.takeIf { record.outgoing && it.total > 0 }
                                // Ticks follow the slowest member: "read" only once everyone read it.
                                val shown = if (counts == null) {
                                    record
                                } else {
                                    record.copy(
                                        state = when {
                                            counts.read == counts.total -> MessageState.Read
                                            counts.delivered == counts.total -> MessageState.Delivered
                                            counts.sent > 0 -> MessageState.Sent
                                            else -> MessageState.Sending
                                        },
                                    )
                                }
                                val footnote = when {
                                    counts == null || record.id != lastOutgoing -> null
                                    counts.read > 0 -> stringResource(Res.string.group_chat_read, counts.read, counts.total)
                                    else -> stringResource(Res.string.group_chat_delivered, counts.delivered, counts.total)
                                }
                                MessageBubble(
                                    record = shown,
                                    peerName = sender ?: name,
                                    peerOnline = false,
                                    repliedTo = record.replyTo?.let { ReplyTarget(it, byId[it]) },
                                    reactions = emptyList(),
                                    liveBytes = null,
                                    uploadPaused = false,
                                    filesLocked = true,
                                    highlighted = highlighted == record.id.value,
                                    menuOpen = menuFor == record.id,
                                    onMenu = { menuFor = if (it) record.id else null },
                                    actions = actions,
                                    senderName = sender,
                                    footnote = footnote,
                                    canReact = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(Res.string.chat_delete_confirm_title)) },
            text = { Text(stringResource(Res.string.chat_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    if (replyToId == id.value) replyToId = null
                    scope.launch { core.deleteMessage(id) }
                }) { Text(stringResource(Res.string.chat_delete_for_me), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(Res.string.group_chat_leave)) },
            text = { Text(stringResource(Res.string.group_chat_leave_confirm, name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    scope.launch {
                        core.leaveGroupChat(groupId)
                        onBack()
                    }
                }) { Text(stringResource(Res.string.group_chat_leave_action), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}
