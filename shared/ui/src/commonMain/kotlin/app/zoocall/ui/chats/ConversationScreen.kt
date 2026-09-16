package app.zoocall.ui.chats

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TimerOff
import app.zoocall.core.app.DisappearingTimer
import app.zoocall.ui.settings.RadioGroup
import app.zoocall.ui.resources.chat_disappearing
import app.zoocall.ui.resources.chat_disappearing_body
import app.zoocall.ui.resources.chat_disappearing_done
import app.zoocall.ui.resources.chat_disappearing_off
import app.zoocall.ui.resources.chat_disappearing_on
import app.zoocall.ui.resources.disappear_day
import app.zoocall.ui.resources.disappear_hour
import app.zoocall.ui.resources.disappear_off
import app.zoocall.ui.resources.disappear_week
import org.jetbrains.compose.resources.StringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zoocall.core.app.SendFileResult
import app.zoocall.core.model.AttachmentInfo
import app.zoocall.core.model.CallKind
import app.zoocall.core.model.Fingerprint
import app.zoocall.core.model.MessageId
import app.zoocall.core.model.MessageKind
import app.zoocall.core.model.TransferState
import app.zoocall.core.model.VoiceClip
import app.zoocall.core.store.ChatMessageRecord
import app.zoocall.core.store.MessageState
import app.zoocall.core.store.ReactionRecord
import app.zoocall.ui.LocalCore
import app.zoocall.ui.LocalSnackbar
import app.zoocall.ui.common.rememberCallLauncher
import app.zoocall.ui.components.Avatar
import app.zoocall.ui.components.Banner
import app.zoocall.ui.components.EmptyState
import app.zoocall.ui.components.formatClock
import app.zoocall.ui.components.formatDuration
import app.zoocall.ui.components.localDateOf
import app.zoocall.ui.components.presenceLabel
import app.zoocall.ui.components.readableContentWidth
import app.zoocall.ui.components.today
import app.zoocall.ui.platform.LocalPlatformActions
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.action_back
import app.zoocall.ui.resources.action_cancel
import app.zoocall.ui.resources.call_person
import app.zoocall.ui.resources.chat_attach_file
import app.zoocall.ui.resources.chat_cancel_recording
import app.zoocall.ui.resources.chat_cancel_reply
import app.zoocall.ui.resources.chat_copied
import app.zoocall.ui.resources.chat_copy
import app.zoocall.ui.resources.chat_delete_confirm_body
import app.zoocall.ui.resources.chat_delete_confirm_title
import app.zoocall.ui.resources.chat_delete_for_me
import app.zoocall.ui.resources.chat_empty_hint
import app.zoocall.ui.resources.chat_file_cant_open
import app.zoocall.ui.resources.chat_file_description
import app.zoocall.ui.resources.chat_file_download
import app.zoocall.ui.resources.chat_file_failed
import app.zoocall.ui.resources.chat_file_open
import app.zoocall.ui.resources.announcement_label
import androidx.compose.material.icons.rounded.Campaign
import app.zoocall.ui.resources.chat_file_pause
import app.zoocall.ui.resources.chat_file_progress
import app.zoocall.ui.resources.chat_file_resume
import app.zoocall.ui.resources.chat_file_retry
import app.zoocall.ui.resources.chat_file_risky
import app.zoocall.ui.resources.chat_file_save
import app.zoocall.ui.resources.chat_file_saved
import app.zoocall.ui.resources.chat_file_too_large
import app.zoocall.ui.resources.chat_file_unreadable
import app.zoocall.ui.resources.chat_file_waiting
import app.zoocall.ui.resources.chat_input_placeholder
import app.zoocall.ui.resources.chat_message_actions
import app.zoocall.ui.resources.chat_mic_needed
import app.zoocall.ui.resources.chat_offline_banner
import app.zoocall.ui.resources.chat_original_deleted
import app.zoocall.ui.resources.chat_pause_voice
import app.zoocall.ui.resources.chat_play_voice
import app.zoocall.ui.resources.chat_reactions_description
import app.zoocall.ui.resources.chat_record_voice
import app.zoocall.ui.resources.chat_recording
import app.zoocall.ui.resources.chat_remove_reaction
import app.zoocall.ui.resources.chat_reply
import app.zoocall.ui.resources.chat_replying_to
import app.zoocall.ui.resources.chat_request_accept
import app.zoocall.ui.resources.chat_request_block
import app.zoocall.ui.resources.chat_request_body
import app.zoocall.ui.resources.chat_request_title
import app.zoocall.ui.resources.chat_send
import app.zoocall.ui.resources.chat_send_voice
import app.zoocall.ui.resources.chat_state_delivered
import app.zoocall.ui.resources.chat_state_read
import app.zoocall.ui.resources.chat_state_sending
import app.zoocall.ui.resources.chat_state_sent
import app.zoocall.ui.resources.chat_today
import app.zoocall.ui.resources.chat_typing
import app.zoocall.ui.resources.chat_voice_description
import app.zoocall.ui.resources.chat_voice_in_call
import app.zoocall.ui.resources.chat_voice_message
import app.zoocall.ui.resources.chat_voice_too_short
import app.zoocall.ui.resources.chat_voice_unavailable
import app.zoocall.ui.resources.chat_yesterday
import app.zoocall.ui.resources.chat_you
import app.zoocall.ui.resources.unknown_person
import app.zoocall.ui.resources.video_call_person
import app.zoocall.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import okio.FileSystem
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Quick reactions offered in the message menu. */
private val QuickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

private fun disappearingLabel(seconds: Long): StringResource = when (seconds) {
    DisappearingTimer.HOUR_S -> Res.string.disappear_hour
    DisappearingTimer.DAY_S -> Res.string.disappear_day
    DisappearingTimer.WEEK_S -> Res.string.disappear_week
    else -> Res.string.disappear_off
}

@Composable
fun ConversationScreen(
    fingerprintHex: String,
    onBack: () -> Unit,
    onOpenPerson: () -> Unit,
    highlightMessageId: String? = null,
) {
    val core = LocalCore.current
    val platform = LocalPlatformActions.current
    val snackbar = LocalSnackbar.current
    val peer = remember(fingerprintHex) { Fingerprint.fromHex(fingerprintHex) }
    val scope = rememberCoroutineScope()
    val messages by remember(peer) { core.conversation(peer) }.collectAsStateWithLifecycle(emptyList())
    val reactions by remember(peer) { core.reactions(peer) }.collectAsStateWithLifecycle(emptyMap())
    val progress by core.transferProgress.collectAsStateWithLifecycle(emptyMap())
    val pausedUploads by core.pausedUploads.collectAsStateWithLifecycle(emptySet())
    val people by core.people.collectAsStateWithLifecycle()
    val typing by core.typing.collectAsStateWithLifecycle(emptySet())
    val person = people.firstOrNull { it.fingerprint == peer }
    val name = person?.displayName ?: stringResource(Res.string.unknown_person)
    val launcher = rememberCallLauncher()
    var draft by rememberSaveable { mutableStateOf("") }
    var replyToId by rememberSaveable { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<MessageId?>(null) }
    var confirmDelete by remember { mutableStateOf<MessageId?>(null) }
    var highlighted by remember(highlightMessageId) { mutableStateOf(highlightMessageId) }
    val listState = rememberLazyListState()

    // Grouping by day runs only when messages change, not on every recomposition (typing, presence).
    val rows = remember(messages) { buildRows(messages) }
    val byId = remember(messages) { messages.associateBy { it.id } }
    val replyTo = replyToId?.let { byId[MessageId(it)] }
    // Edge case M10: someone who isn't a contact wrote first and we never answered.
    val isRequest = person?.isContact != true && messages.isNotEmpty() && messages.none { it.outgoing }

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

    LaunchedEffect(peer, messages.size) { core.markConversationRead(peer) }

    // Jump to the newest message when opening (or to the searched message); animate for later arrivals.
    var initialScrollDone by remember(peer) { mutableStateOf(false) }
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
        scope.launch { core.sendMessage(peer, text, reply) }
    }

    fun attach() {
        scope.launch {
            val file = platform.pickFile() ?: return@launch
            val reply = replyToId?.let(::MessageId)
            when (core.sendFile(peer, file, reply)) {
                SendFileResult.Sent -> replyToId = null
                SendFileResult.TooLarge -> snackbar.showSnackbar(getString(Res.string.chat_file_too_large))
                SendFileResult.Unreadable -> snackbar.showSnackbar(getString(Res.string.chat_file_unreadable))
            }
        }
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
        recordingUi = false // first, so the auto-stop effect below can't send twice
        val reply = replyToId?.let(::MessageId)
        scope.launch {
            val clip = recorder.stop()
            if (clip == null) {
                snackbar.showSnackbar(getString(Res.string.chat_voice_too_short))
            } else {
                core.sendVoice(peer, clip, reply)
                replyToId = null
            }
        }
    }

    fun cancelRecording() {
        recordingUi = false
        recorder.cancel()
    }

    // The recorder stops by itself at the 2-minute limit: send what was recorded.
    LaunchedEffect(recording) {
        if (!recording && recordingUi) finishRecording()
    }

    val timer by remember(peer) { core.disappearingTimer(peer) }.collectAsStateWithLifecycle(DisappearingTimer.OFF)
    var timerDialog by remember { mutableStateOf(false) }
    if (timerDialog) {
        AlertDialog(
            onDismissRequest = { timerDialog = false },
            title = { Text(stringResource(Res.string.chat_disappearing)) },
            text = {
                Column {
                    Text(
                        stringResource(Res.string.chat_disappearing_body),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = Spacing.m),
                    )
                    RadioGroup(
                        options = DisappearingTimer.OPTIONS.map { it to disappearingLabel(it) },
                        selected = timer,
                        onSelect = { seconds -> scope.launch { core.setDisappearingTimer(peer, seconds) } },
                    )
                }
            },
            confirmButton = { TextButton(onClick = { timerDialog = false }) { Text(stringResource(Res.string.chat_disappearing_done)) } },
        )
    }

    val actions = MessageActions(
        onReply = { replyToId = it.id.value },
        onReact = { record, emoji -> scope.launch { core.react(peer, record.id, emoji) } },
        onDelete = { confirmDelete = it.id },
        onDownload = { scope.launch { core.downloadFile(it.id) } },
        onPauseDownload = { scope.launch { core.pauseDownload(it.id) } },
        onPauseUpload = { scope.launch { core.pauseUpload(it.id) } },
        onResumeUpload = { scope.launch { core.resumeUpload(it.id) } },
        onOpen = { record ->
            val info = record.attachment
            if (info != null) {
                scope.launch {
                    val path = core.attachmentForExport(record.id) ?: return@launch
                    if (!platform.openFile(path, info.name, info.mime)) snackbar.showSnackbar(getString(Res.string.chat_file_cant_open))
                }
            }
        },
        onSave = { record ->
            val info = record.attachment
            if (info != null) {
                scope.launch {
                    val path = core.attachmentForExport(record.id) ?: return@launch
                    if (platform.saveFile(path, info.name, info.mime)) snackbar.showSnackbar(getString(Res.string.chat_file_saved))
                }
            }
        },
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
                    Row(Modifier.clickable(onClick = onOpenPerson), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name, size = 40.dp, presence = person?.presence, online = person?.online == true)
                        Column(Modifier.padding(start = Spacing.m)) {
                            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                            val status = when {
                                peer in typing -> stringResource(Res.string.chat_typing)
                                person == null -> ""
                                person.online && person.statusText.isNotBlank() -> "${presenceLabel(person.presence, true)} · ${person.statusText}"
                                else -> presenceLabel(person.presence, person.online)
                            }
                            if (status.isNotEmpty()) {
                                Text(
                                    status,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { timerDialog = true }) {
                        Icon(
                            if (timer > 0) Icons.Rounded.Timer else Icons.Rounded.TimerOff,
                            if (timer > 0) {
                                stringResource(Res.string.chat_disappearing_on, stringResource(disappearingLabel(timer)))
                            } else {
                                stringResource(Res.string.chat_disappearing_off)
                            },
                        )
                    }
                    if (person != null && !person.blocked) {
                        IconButton(onClick = { launcher.call(person.id, name, CallKind.Audio) }) {
                            Icon(Icons.Rounded.Call, stringResource(Res.string.call_person, name))
                        }
                        IconButton(onClick = { launcher.call(person.id, name, CallKind.Video) }) {
                            Icon(Icons.Rounded.Videocam, stringResource(Res.string.video_call_person, name))
                        }
                    }
                },
            )
        },
        bottomBar = {
            // A hairline divider plus the page background: no tinted band, so the composer lines up
            // with the navigation rail on desktop instead of leaving a notch beside it.
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
                        ReplyComposerBar(replyTo, name) { replyToId = null }
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        if (recordingUi) {
                            RecordingBar(
                                elapsedMs = elapsed,
                                level = level,
                                onCancel = ::cancelRecording,
                                onSend = ::finishRecording,
                            )
                        } else {
                            if (platform.canPickFiles) {
                                IconButton(onClick = ::attach, modifier = Modifier.padding(bottom = Spacing.xs).size(52.dp)) {
                                    Icon(Icons.Rounded.AttachFile, stringResource(Res.string.chat_attach_file))
                                }
                            }
                            OutlinedTextField(
                                value = draft,
                                onValueChange = {
                                    draft = it.take(8_000)
                                    scope.launch { core.onUserTyping(peer, it.isNotBlank()) }
                                },
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
                                FilledIconButton(
                                    onClick = ::startRecording,
                                    modifier = Modifier.padding(start = Spacing.s, bottom = Spacing.xs).size(52.dp),
                                ) {
                                    Icon(Icons.Rounded.Mic, stringResource(Res.string.chat_record_voice))
                                }
                            } else {
                                FilledIconButton(
                                    onClick = ::send,
                                    modifier = Modifier.padding(start = Spacing.s, bottom = Spacing.xs).size(52.dp),
                                ) {
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
            if (isRequest) {
                MessageRequestCard(
                    name = name,
                    onAccept = { scope.launch { core.acceptMessageRequest(peer) } },
                    onBlock = { scope.launch { core.setBlocked(peer, true) } },
                )
            } else if (person != null && !person.online) {
                Banner(
                    stringResource(Res.string.chat_offline_banner, name),
                    Icons.Rounded.CloudOff,
                    Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
            }
            if (rows.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                    EmptyState(Icons.AutoMirrored.Rounded.Chat, name, stringResource(Res.string.chat_empty_hint, name))
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
                                MessageBubble(
                                    record = record,
                                    peerName = name,
                                    peerOnline = person?.online == true,
                                    repliedTo = record.replyTo?.let { ReplyTarget(it, byId[it]) },
                                    reactions = reactions[record.id].orEmpty(),
                                    liveBytes = progress[record.id],
                                    uploadPaused = record.id in pausedUploads,
                                    // Files from people who aren't contacts stay closed until the request is accepted.
                                    filesLocked = isRequest,
                                    highlighted = highlighted == record.id.value,
                                    menuOpen = menuFor == record.id,
                                    onMenu = { menuFor = if (it) record.id else null },
                                    actions = actions,
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
}

internal class MessageActions(
    val onReply: (ChatMessageRecord) -> Unit,
    val onReact: (ChatMessageRecord, String?) -> Unit,
    val onDelete: (ChatMessageRecord) -> Unit,
    val onDownload: (ChatMessageRecord) -> Unit,
    val onPauseDownload: (ChatMessageRecord) -> Unit,
    val onPauseUpload: (ChatMessageRecord) -> Unit,
    val onResumeUpload: (ChatMessageRecord) -> Unit,
    val onOpen: (ChatMessageRecord) -> Unit,
    val onSave: (ChatMessageRecord) -> Unit,
    val onCopied: () -> Unit,
    val onShowReply: (MessageId) -> Unit,
)

/** The message a bubble replies to; [record] is null when it was deleted or never arrived. */
internal class ReplyTarget(val id: MessageId, val record: ChatMessageRecord?)

internal sealed interface ChatRow {
    val key: String
    data class Day(val date: LocalDate) : ChatRow { override val key = "day-$date" }
    data class Message(val record: ChatMessageRecord) : ChatRow { override val key = record.id.value }
}

internal fun buildRows(messages: List<ChatMessageRecord>): List<ChatRow> {
    val zone = TimeZone.currentSystemDefault()
    var lastDate: LocalDate? = null
    return buildList(messages.size + 4) {
        for (m in messages) {
            val date = localDateOf(m.receivedAtMs, zone)
            if (date != lastDate) {
                add(ChatRow.Day(date))
                lastDate = date
            }
            add(ChatRow.Message(m))
        }
    }
}

@Composable
private fun MessageRequestCard(name: String, onAccept: () -> Unit, onBlock: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
    ) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(stringResource(Res.string.chat_request_title, name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(Res.string.chat_request_body), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                FilledTonalButton(onClick = onAccept) {
                    Icon(Icons.Rounded.PersonAdd, null, Modifier.size(18.dp))
                    Text(stringResource(Res.string.chat_request_accept), Modifier.padding(start = Spacing.s))
                }
                OutlinedButton(onClick = onBlock) {
                    Text(stringResource(Res.string.chat_request_block), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
internal fun ReplyComposerBar(target: ChatMessageRecord, peerName: String, onCancel: () -> Unit) {
    val author = if (target.outgoing) stringResource(Res.string.chat_you, "").trim().trimEnd(':') else peerName
    Row(
        Modifier.fillMaxWidth().padding(start = Spacing.m, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(36.dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall))
        Column(Modifier.weight(1f).padding(horizontal = Spacing.m)) {
            Text(stringResource(Res.string.chat_replying_to, author), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(previewText(target), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, stringResource(Res.string.chat_cancel_reply)) }
    }
}

@Composable
private fun previewText(record: ChatMessageRecord): String = when (record.kind) {
    MessageKind.Voice -> stringResource(Res.string.chat_voice_message)
    MessageKind.File -> "📎 ${record.body}"
    MessageKind.Text -> record.body
}

@Composable
internal fun RowScope.RecordingBar(
    elapsedMs: Long,
    level: Float,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val pulse = rememberInfiniteTransition()
    val dotAlpha by pulse.animateFloat(1f, 0.3f, infiniteRepeatable(tween(600), RepeatMode.Reverse))
    val recordingLabel = stringResource(Res.string.chat_recording)
    IconButton(onClick = onCancel, modifier = Modifier.size(52.dp)) {
        Icon(Icons.Rounded.Delete, stringResource(Res.string.chat_cancel_recording), tint = MaterialTheme.colorScheme.error)
    }
    Row(
        Modifier
            .weight(1f)
            .heightIn(min = 52.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$recordingLabel ${formatDuration(elapsedMs)}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).alpha(dotAlpha).background(MaterialTheme.colorScheme.error, CircleShape))
        Text(
            formatDuration(elapsedMs),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Spacing.m),
        )
        LinearProgressIndicator(
            progress = { level },
            modifier = Modifier.weight(1f).height(6.dp).clearAndSetSemantics { },
        )
        Text(
            formatDuration(VoiceClip.MAX_DURATION_MS),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.s).clearAndSetSemantics { },
        )
    }
    FilledIconButton(onClick = onSend, modifier = Modifier.padding(start = Spacing.s).size(52.dp)) {
        Icon(Icons.AutoMirrored.Rounded.Send, stringResource(Res.string.chat_send_voice))
    }
}

@Composable
internal fun DaySeparator(date: LocalDate) {
    val days = today().toEpochDays() - date.toEpochDays()
    val label = when (days) {
        0L -> stringResource(Res.string.chat_today)
        1L -> stringResource(Res.string.chat_yesterday)
        else -> "${date.day}/${date.month.ordinal + 1}/${date.year}"
    }
    Box(Modifier.fillMaxWidth().padding(vertical = Spacing.s), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    record: ChatMessageRecord,
    peerName: String,
    peerOnline: Boolean,
    repliedTo: ReplyTarget?,
    reactions: List<ReactionRecord>,
    liveBytes: Long?,
    uploadPaused: Boolean,
    filesLocked: Boolean,
    highlighted: Boolean,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    actions: MessageActions,
    /** Group chats: who wrote an incoming message. */
    senderName: String? = null,
    /** A short line under the bubble, e.g. "Delivered to 3 of 5" in a group chat. */
    footnote: String? = null,
    canReact: Boolean = true,
) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val outgoing = record.outgoing
    val stateLabel = when (record.state) {
        MessageState.Sending -> stringResource(Res.string.chat_state_sending)
        MessageState.Sent -> stringResource(Res.string.chat_state_sent)
        MessageState.Delivered -> stringResource(Res.string.chat_state_delivered)
        MessageState.Read -> stringResource(Res.string.chat_state_read)
        else -> null
    }
    val time = formatClock(record.receivedAtMs)
    val isVoice = record.kind == MessageKind.Voice
    val attachment = record.attachment
    val hasControls = isVoice || attachment != null
    val reactionText = reactions.joinToString(" ") { it.emoji }
    val announcementLabel = stringResource(Res.string.announcement_label)
    val description = listOfNotNull(
        senderName.takeIf { !outgoing },
        announcementLabel.takeIf { record.announcement },
        when {
            isVoice -> stringResource(Res.string.chat_voice_description, formatDuration(record.durationMs ?: 0))
            attachment != null -> stringResource(Res.string.chat_file_description, attachment.name, formatBytes(attachment.size))
            else -> record.body
        },
        reactionText.takeIf { it.isNotEmpty() }?.let { stringResource(Res.string.chat_reactions_description, it) },
        time,
        stateLabel,
        footnote,
    ).joinToString(", ")

    val replyLabel = stringResource(Res.string.chat_reply)
    val menuLabel = stringResource(Res.string.chat_message_actions)
    val copyLabel = stringResource(Res.string.chat_copy)
    val deleteLabel = stringResource(Res.string.chat_delete_for_me)
    val a11yActions = buildList {
        add(CustomAccessibilityAction(replyLabel) { actions.onReply(record); true })
        add(CustomAccessibilityAction(menuLabel) { onMenu(true); true })
        if (record.kind == MessageKind.Text) {
            add(CustomAccessibilityAction(copyLabel) { clipboard.setText(AnnotatedString(record.body)); actions.onCopied(); true })
        }
        add(CustomAccessibilityAction(deleteLabel) { actions.onDelete(record); true })
    }
    val highlightColor by animateColorAsState(if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent)

    Column(
        Modifier.fillMaxWidth().background(highlightColor, MaterialTheme.shapes.medium),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
    ) {
        Box {
            Surface(
                color = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .padding(start = if (outgoing) 48.dp else 0.dp, end = if (outgoing) 0.dp else 48.dp)
                    .clip(MaterialTheme.shapes.large)
                    .combinedClickable(onClickLabel = null, onLongClickLabel = menuLabel, onLongClick = { onMenu(true) }, onClick = {})
                    // Desktop: right-click opens the same menu.
                    .pointerInput(record.id) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                    event.changes.forEach { it.consume() }
                                    onMenu(true)
                                }
                            }
                        }
                    }
                    // Text bubbles read as one sentence; voice and file bubbles keep their buttons reachable.
                    .then(
                        if (hasControls) {
                            Modifier.semantics { contentDescription = description; customActions = a11yActions }
                        } else {
                            Modifier.clearAndSetSemantics { contentDescription = description; customActions = a11yActions }
                        },
                    ),
            ) {
                Column(Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s)) {
                    if (senderName != null && !outgoing) {
                        Text(
                            senderName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = Spacing.xs),
                        )
                    }
                    repliedTo?.let { QuotedMessage(it, peerName, actions.onShowReply) }
                    if (record.announcement) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = Spacing.xs)) {
                            Icon(Icons.Rounded.Campaign, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Text(
                                stringResource(Res.string.announcement_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = Spacing.xs),
                            )
                        }
                    }
                    when {
                        isVoice -> VoiceNoteContent(record)
                        attachment != null -> AttachmentContent(record, attachment, peerName, peerOnline, liveBytes, uploadPaused, filesLocked, actions)
                        else -> Text(record.body, style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (outgoing) {
                            val icon = when (record.state) {
                                MessageState.Sending -> Icons.Rounded.Schedule
                                MessageState.Sent -> Icons.Rounded.Done
                                else -> Icons.Rounded.DoneAll
                            }
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = if (record.state == MessageState.Read) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = Spacing.xs).size(14.dp),
                            )
                        }
                    }
                }
            }
            MessageMenu(record, reactions, menuOpen, onDismiss = { onMenu(false) }, actions = actions, canReact = canReact, onCopy = {
                clipboard.setText(AnnotatedString(record.body))
                actions.onCopied()
            })
        }
        footnote?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.m, vertical = 2.dp).clearAndSetSemantics { },
            )
        }
        if (reactions.isNotEmpty()) {
            val mine = reactions.firstOrNull { it.fromSelf }
            val removeLabel = stringResource(Res.string.chat_remove_reaction)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 1.dp,
                modifier = Modifier
                    .padding(horizontal = Spacing.m)
                    .offset(y = (-6).dp)
                    .clip(CircleShape)
                    .then(if (mine != null) Modifier.clickable(onClickLabel = removeLabel) { actions.onReact(record, null) } else Modifier)
                    .clearAndSetSemantics { },
            ) {
                Text(reactionText, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Spacing.s, vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun MessageMenu(
    record: ChatMessageRecord,
    reactions: List<ReactionRecord>,
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: MessageActions,
    canReact: Boolean,
    onCopy: () -> Unit,
) {
    val mine = reactions.firstOrNull { it.fromSelf }?.emoji
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Row(Modifier.padding(horizontal = Spacing.s)) {
            (if (canReact) QuickReactions else emptyList()).forEach { emoji ->
                val selected = emoji == mine
                val label = if (selected) stringResource(Res.string.chat_remove_reaction) else emoji
                Surface(
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable {
                            onDismiss()
                            actions.onReact(record, if (selected) null else emoji)
                        }
                        .semantics { contentDescription = label },
                ) {
                    Box(contentAlignment = Alignment.Center) { Text(emoji, style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = Spacing.xs))
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.chat_reply)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Reply, null) },
            onClick = { onDismiss(); actions.onReply(record) },
        )
        if (record.kind == MessageKind.Text) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.chat_copy)) },
                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                onClick = { onDismiss(); onCopy() },
            )
        }
        if (record.attachment?.state == TransferState.Done) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.chat_file_save)) },
                leadingIcon = { Icon(Icons.Rounded.SaveAlt, null) },
                onClick = { onDismiss(); actions.onSave(record) },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.chat_delete_for_me), color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); actions.onDelete(record) },
        )
    }
}

@Composable
private fun QuotedMessage(target: ReplyTarget, peerName: String, onShow: (MessageId) -> Unit) {
    val record = target.record
    val author = when {
        record == null -> null
        record.outgoing -> stringResource(Res.string.chat_you, "").trim().trimEnd(':')
        else -> peerName
    }
    Row(
        Modifier
            .padding(bottom = Spacing.xs)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .then(if (record != null) Modifier.clickable { onShow(target.id) } else Modifier)
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().heightIn(min = 40.dp).background(MaterialTheme.colorScheme.primary))
        Column(Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs)) {
            if (author != null) Text(author, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                if (record == null) stringResource(Res.string.chat_original_deleted) else previewText(record),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun AttachmentContent(
    record: ChatMessageRecord,
    info: AttachmentInfo,
    peerName: String,
    peerOnline: Boolean,
    liveBytes: Long?,
    uploadPaused: Boolean,
    locked: Boolean,
    actions: MessageActions,
) {
    val core = LocalCore.current
    val meta = info.meta
    val done = info.state == TransferState.Done
    // Show a preview only for finished images of a reasonable size; decoding happens off the main thread.
    val preview by produceState<ImageBitmap?>(null, record.id, done) {
        if (!done || !meta.isImage || info.size > MAX_PREVIEW_BYTES) return@produceState
        val bytes = core.attachmentBytes(record.id, MAX_PREVIEW_BYTES.toLong()) ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { bytes.decodeToImageBitmap() }.getOrNull()
        }
    }

    Column(Modifier.widthIn(min = 220.dp), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        preview?.let { bitmap ->
            val openLabel = stringResource(Res.string.chat_file_open)
            Image(
                bitmap,
                // Screen readers name the photo by its file name and say that it opens.
                contentDescription = meta.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .heightIn(max = 280.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(onClickLabel = openLabel) { actions.onOpen(record) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when {
                    info.state == TransferState.Failed -> Icons.Rounded.ErrorOutline
                    meta.isImage -> Icons.Rounded.Image
                    else -> Icons.Rounded.InsertDriveFile
                },
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
            Column(Modifier.weight(1f, fill = false).padding(start = Spacing.s)) {
                Text(info.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val bytes = liveBytes ?: info.transferred
                val sizeText = if (!done && !record.outgoing && bytes > 0) {
                    stringResource(Res.string.chat_file_progress, formatBytes(bytes), formatBytes(info.size))
                } else {
                    formatBytes(info.size)
                }
                Text(sizeText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        val uploading = record.outgoing && liveBytes != null
        if (uploading || (!record.outgoing && info.state == TransferState.Requested)) {
            val bytes = liveBytes ?: info.transferred
            LinearProgressIndicator(
                progress = { if (info.size > 0) (bytes.toFloat() / info.size).coerceIn(0f, 1f) else 1f },
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
            )
            if (!record.outgoing && liveBytes == null && !peerOnline) {
                Text(stringResource(Res.string.chat_file_waiting, peerName), style = MaterialTheme.typography.labelMedium)
            }
        }
        if (info.state == TransferState.Failed) {
            Text(stringResource(Res.string.chat_file_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (meta.isRisky && !record.outgoing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(Res.string.chat_file_risky, peerName),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = Spacing.xs),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            when {
                record.outgoing && uploading -> FileAction(Icons.Rounded.Pause, Res.string.chat_file_pause) { actions.onPauseUpload(record) }
                record.outgoing && uploadPaused -> FileAction(Icons.Rounded.PlayArrow, Res.string.chat_file_resume) { actions.onResumeUpload(record) }
                done -> {
                    FileAction(Icons.AutoMirrored.Rounded.OpenInNew, Res.string.chat_file_open) { actions.onOpen(record) }
                    FileAction(Icons.Rounded.SaveAlt, Res.string.chat_file_save) { actions.onSave(record) }
                }
                locked -> Unit
                info.state == TransferState.Offered -> FileAction(Icons.Rounded.Download, Res.string.chat_file_download) { actions.onDownload(record) }
                info.state == TransferState.Requested -> FileAction(Icons.Rounded.Pause, Res.string.chat_file_pause) { actions.onPauseDownload(record) }
                info.state == TransferState.Paused -> FileAction(Icons.Rounded.PlayArrow, Res.string.chat_file_resume) { actions.onDownload(record) }
                info.state == TransferState.Failed -> FileAction(Icons.Rounded.Download, Res.string.chat_file_retry) { actions.onDownload(record) }
            }
        }
    }
}

@Composable
private fun FileAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: org.jetbrains.compose.resources.StringResource, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(icon, null, Modifier.size(18.dp))
        Text(stringResource(label), Modifier.padding(start = Spacing.xs))
    }
}

private const val MAX_PREVIEW_BYTES = 15L * 1024 * 1024

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val rounded = if (value >= 100) value.toLong().toString() else ((value * 10).toLong() / 10.0).toString()
    return "$rounded ${units[unit]}"
}

@Composable
private fun VoiceNoteContent(record: ChatMessageRecord) {
    val core = LocalCore.current
    val player = core.mediaEngine.voicePlayer
    val playback by player.state.collectAsState()
    val clip by produceState<VoiceClip?>(null, record.id) { value = core.voiceClip(record.id) }
    val isThis = playback.messageId == record.id.value
    val playing = isThis && playback.playing
    val durationMs = record.durationMs ?: clip?.durationMs ?: 0
    val progress = if (isThis && durationMs > 0) (playback.positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val played = MaterialTheme.colorScheme.primary
    val unplayed = MaterialTheme.colorScheme.outline

    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledIconButton(
            onClick = {
                val c = clip ?: return@FilledIconButton
                if (playing) player.pause() else player.play(record.id.value, c)
            },
            enabled = clip != null,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                stringResource(if (playing) Res.string.chat_pause_voice else Res.string.chat_play_voice),
            )
        }
        val bars = clip?.waveform ?: ByteArray(0)
        Canvas(Modifier.padding(horizontal = Spacing.m).width(150.dp).height(32.dp).clearAndSetSemantics { }) {
            if (bars.isEmpty()) {
                drawRoundRect(unplayed, Offset(0f, size.height / 2 - 2), Size(size.width, 4f), CornerRadius(2f))
                return@Canvas
            }
            val gap = 2.dp.toPx()
            val barWidth = ((size.width - gap * (bars.size - 1)) / bars.size).coerceAtLeast(1f)
            bars.forEachIndexed { i, value ->
                val h = ((value.toInt() and 0xff) / 255f * size.height).coerceAtLeast(3.dp.toPx())
                val x = i * (barWidth + gap)
                val color = if ((i + 0.5f) / bars.size <= progress) played else unplayed
                drawRoundRect(color, Offset(x, (size.height - h) / 2), Size(barWidth, h), CornerRadius(barWidth / 2))
            }
        }
        Text(
            formatDuration(if (isThis && playback.positionMs > 0) playback.positionMs else durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}
