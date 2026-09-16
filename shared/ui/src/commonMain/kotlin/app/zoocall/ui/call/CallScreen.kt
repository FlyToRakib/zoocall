package app.zoocall.ui.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import app.zoocall.ui.resources.call_add_busy
import app.zoocall.ui.resources.call_add_people
import app.zoocall.ui.resources.call_live_captions
import androidx.compose.material.icons.rounded.ClosedCaption
import app.zoocall.ui.resources.call_share_screen
import app.zoocall.ui.resources.call_sharing_banner
import app.zoocall.ui.resources.call_share_failed
import app.zoocall.ui.resources.call_transfer
import app.zoocall.ui.resources.call_transferring
import app.zoocall.ui.resources.unknown_person
import androidx.compose.material.icons.rounded.PhoneForwarded
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import app.zoocall.ui.resources.action_cancel
import app.zoocall.ui.resources.call_record
import app.zoocall.ui.resources.call_record_action
import app.zoocall.ui.resources.call_record_confirm_body
import app.zoocall.ui.resources.call_record_confirm_title
import app.zoocall.ui.resources.call_record_stop
import app.zoocall.ui.resources.call_recorded_by_others
import app.zoocall.ui.resources.call_recording_banner
import androidx.compose.runtime.collectAsState
import app.zoocall.ui.resources.action_close
import app.zoocall.ui.resources.call_intercom_banner
import app.zoocall.ui.resources.call_stop_sharing
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.automirrored.rounded.StopScreenShare
import app.zoocall.ui.resources.call_group_with
import androidx.compose.material.icons.rounded.PersonAdd
import app.zoocall.ui.resources.call_add_declined
import app.zoocall.ui.resources.call_add_no_answer
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SettingsVoice
import app.zoocall.ui.resources.call_devices
import app.zoocall.ui.settings.DeviceSettings
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.zoocall.core.call.ActiveCall
import app.zoocall.core.call.CallNotice
import app.zoocall.core.call.CallPhase
import app.zoocall.core.call.UpgradeRequest
import app.zoocall.core.model.CallDirection
import app.zoocall.core.model.CallKind
import app.zoocall.core.model.EndReason
import app.zoocall.core.protocol.Capabilities
import app.zoocall.media.CallStats
import app.zoocall.media.ScreenSource
import app.zoocall.media.VideoTrackHandle
import app.zoocall.media.VideoView
import app.zoocall.media.qualityBars
import app.zoocall.ui.LocalCore
import app.zoocall.ui.components.Avatar
import app.zoocall.ui.components.VerificationLabel
import app.zoocall.ui.components.formatDuration
import app.zoocall.ui.platform.LocalPlatformActions
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.action_close
import app.zoocall.ui.resources.action_more
import app.zoocall.ui.resources.call_accept
import app.zoocall.ui.resources.call_accept_audio
import app.zoocall.ui.resources.call_calling
import app.zoocall.ui.resources.call_camera_off
import app.zoocall.ui.resources.call_camera_on
import app.zoocall.ui.resources.call_connecting
import app.zoocall.ui.resources.call_decline
import app.zoocall.ui.resources.call_decline_message
import app.zoocall.ui.resources.call_declined_with
import app.zoocall.ui.resources.call_encrypted
import app.zoocall.ui.resources.call_end
import app.zoocall.ui.resources.call_end_busy
import app.zoocall.ui.resources.call_end_cancelled
import app.zoocall.ui.resources.call_end_declined
import app.zoocall.ui.resources.call_end_dnd
import app.zoocall.ui.resources.call_end_media
import app.zoocall.ui.resources.call_end_answered_elsewhere
import app.zoocall.ui.resources.call_end_missed
import app.zoocall.ui.resources.call_end_network
import app.zoocall.ui.resources.call_end_no_answer
import app.zoocall.ui.resources.call_end_not_allowed
import app.zoocall.ui.resources.call_end_unreachable
import app.zoocall.ui.resources.call_ended
import app.zoocall.ui.resources.call_flip_camera
import app.zoocall.ui.resources.call_hold
import app.zoocall.ui.resources.call_incoming_audio
import app.zoocall.ui.resources.call_incoming_video
import app.zoocall.ui.resources.call_info
import app.zoocall.ui.resources.call_mic_permission
import app.zoocall.ui.resources.call_mute
import app.zoocall.ui.resources.call_on_hold
import app.zoocall.ui.resources.call_quality
import app.zoocall.ui.resources.call_quick_reply_1
import app.zoocall.ui.resources.call_quick_reply_2
import app.zoocall.ui.resources.call_quick_reply_3
import app.zoocall.ui.resources.call_quick_reply_custom
import app.zoocall.ui.resources.call_quick_reply_send
import app.zoocall.ui.resources.call_reconnecting
import app.zoocall.ui.resources.call_remote_camera_off
import app.zoocall.ui.resources.call_remote_muted
import app.zoocall.ui.resources.call_remote_on_hold
import app.zoocall.ui.resources.call_resume
import app.zoocall.ui.resources.call_ringing
import app.zoocall.ui.resources.call_show_controls
import app.zoocall.ui.resources.call_speaker
import app.zoocall.ui.resources.call_stats_audio_codec
import app.zoocall.ui.resources.call_stats_bitrate
import app.zoocall.ui.resources.call_stats_loss
import app.zoocall.ui.resources.call_stats_rtt
import app.zoocall.ui.resources.call_stats_unknown
import app.zoocall.ui.resources.call_stats_video
import app.zoocall.ui.resources.call_stats_video_codec
import app.zoocall.ui.resources.call_unmute
import app.zoocall.ui.resources.call_upgrade_accept
import app.zoocall.ui.resources.call_upgrade_cancel
import app.zoocall.ui.resources.call_upgrade_declined
import app.zoocall.ui.resources.call_upgrade_incoming
import app.zoocall.ui.resources.call_upgrade_no_answer
import app.zoocall.ui.resources.call_upgrade_not_now
import app.zoocall.ui.resources.call_upgrade_request
import app.zoocall.ui.resources.call_upgrade_waiting
import app.zoocall.ui.resources.call_waiting_decline
import app.zoocall.ui.resources.call_waiting_end_accept
import app.zoocall.ui.resources.call_waiting_title
import app.zoocall.ui.resources.unknown_person
import app.zoocall.ui.theme.Spacing
import app.zoocall.ui.theme.ZoocallTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/**
 * Full-bleed call screen, always dark (docs/05 §5.3–5.4). Renders nothing when there's no call.
 * [pictureInPicture]: only the remote video (or the caller) without controls, for Android PiP.
 */
@Composable
fun CallScreen(modifier: Modifier = Modifier, pictureInPicture: Boolean = false) {
    val core = LocalCore.current
    val call by core.activeCall.collectAsState()
    val current = call ?: return
    ZoocallTheme(forceDark = true) {
        Surface(modifier.fillMaxSize(), color = Color(0xFF0B0F0E), contentColor = Color.White) {
            when {
                pictureInPicture -> PictureInPictureContent(current)
                current.state.pushToTalk -> PushToTalkContent(current)
                else -> CallContent(current)
            }
        }
    }
}

private val noVideo = MutableStateFlow<VideoTrackHandle?>(null)
private val noStats = MutableStateFlow(CallStats())
private val frontCameraDefault = MutableStateFlow(true)

private val BannerColor = Color(0xE6202B29)

@Composable
private fun PictureInPictureContent(call: ActiveCall) {
    val core = LocalCore.current
    val people by core.people.collectAsState()
    val name = people.firstOrNull { it.fingerprint == call.state.peer }?.displayName ?: stringResource(Res.string.unknown_person)
    val remoteVideo by (call.media?.remoteVideo ?: noVideo).collectAsState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (call.state.kind == CallKind.Video && remoteVideo != null && call.remoteCameraOn) {
            VideoView(remoteVideo, Modifier.fillMaxSize(), fill = true)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(name, size = 56.dp)
                Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CallContent(call: ActiveCall) {
    val core = LocalCore.current
    val platform = LocalPlatformActions.current
    val scope = rememberCoroutineScope()
    val people by core.people.collectAsState()
    val person = people.firstOrNull { it.fingerprint == call.state.peer }
    val name = person?.displayName ?: stringResource(Res.string.unknown_person)
    val state = call.state
    val media = call.media
    val remoteVideo by (media?.remoteVideo ?: noVideo).collectAsState()
    val localVideo by (media?.localVideo ?: noVideo).collectAsState()
    val frontCamera by (media?.isFrontCamera ?: frontCameraDefault).collectAsState()
    val stats by (media?.stats ?: noStats).collectAsState()
    val isVideo = state.kind == CallKind.Video
    val live = state.phase == CallPhase.Connected || state.phase == CallPhase.Reconnecting
    // A group call shows everyone in a grid instead of one full-screen video.
    val showRemoteVideo = !call.isGroup && live && remoteVideo != null && !call.remoteOnHold &&
        ((isVideo && call.remoteCameraOn) || call.remoteScreenSharing)
    var addPeopleOpen by remember { mutableStateOf(false) }
    var sharePickerOpen by remember { mutableStateOf(false) }
    var shareFailed by remember { mutableStateOf(false) }
    var transferOpen by remember { mutableStateOf(false) }
    var recordConfirmOpen by remember { mutableStateOf(false) }
    val transferTarget by core.callTransferTarget.collectAsState(null)
    var controlsVisible by remember { mutableStateOf(true) }
    var permissionError by remember { mutableStateOf(false) }
    var quickReplyOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    val accessibility = LocalAccessibilityManager.current

    // Requests that need an answer always bring the controls back.
    LaunchedEffect(call.upgrade, call.waiting?.callId) {
        if (call.upgrade != null || call.waiting != null) controlsVisible = true
    }

    // Auto-hide controls during a live video call, unless a screen reader or other assistive
    // service needs more time (it then reports an infinite timeout).
    LaunchedEffect(controlsVisible, showRemoteVideo, call.upgrade, call.waiting) {
        if (!controlsVisible || !showRemoteVideo || call.upgrade != null || call.waiting != null) return@LaunchedEffect
        val timeout = accessibility?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = 5_000,
            containsIcons = true,
            containsControls = true,
        ) ?: 5_000
        if (timeout == Long.MAX_VALUE) return@LaunchedEffect
        delay(timeout)
        controlsVisible = false
    }

    val showControlsLabel = stringResource(Res.string.call_show_controls)
    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = showControlsLabel,
            ) { controlsVisible = true },
    ) {
        if (showRemoteVideo) {
            // A shared screen is shown whole (letterboxed) so no text is cropped away.
            VideoView(remoteVideo, Modifier.fillMaxSize(), fill = !call.remoteScreenSharing)
        } else {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF12332D), Color(0xFF0B0F0E)))))
        }
        val flashAlert by core.settings.collectAsState()
        if (state.phase == CallPhase.IncomingRinging && flashAlert.flashAlert) FlashAlert()

        // Self view sits below the controls so it can never hide them.
        if (isVideo && call.cameraOn && !call.onHold && localVideo != null && state.phase != CallPhase.Ended) {
            SelfPreview(localVideo, mirror = frontCamera, fullScreen = !live)
        }

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            AnimatedVisibility(visible = controlsVisible || !showRemoteVideo, enter = fadeIn(), exit = fadeOut()) {
                Header(call, name, stats, compact = showRemoteVideo)
            }
            if (call.isGroup && state.phase in setOf(CallPhase.Connecting, CallPhase.Connected, CallPhase.Reconnecting)) {
                GroupGrid(call, Modifier.weight(1f).fillMaxWidth())
            } else {
                Spacer(Modifier.weight(1f))
                if (!showRemoteVideo) {
                    CenterIdentity(call, name)
                    Spacer(Modifier.weight(1f))
                }
            }
            // Recording is never silent: whoever records and everyone else always see it (docs/04).
            if (state.phase != CallPhase.Ended && (call.recording || call.remoteRecording || call.members.any { it.recording })) {
                Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs), contentAlignment = Alignment.Center) {
                    if (call.recording) {
                        CallBanner(stringResource(Res.string.call_recording_banner)) {
                            Button(onClick = { scope.launch { core.stopRecording() } }) { Text(stringResource(Res.string.call_record_stop)) }
                        }
                    } else {
                        CallBanner(stringResource(Res.string.call_recorded_by_others)) {}
                    }
                }
            }
            transferTarget?.takeIf { state.phase != CallPhase.Ended }?.let { target ->
                Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs), contentAlignment = Alignment.Center) {
                    val targetName = core.person(target.hex)?.displayName ?: stringResource(Res.string.unknown_person)
                    CallBanner(stringResource(Res.string.call_transferring, targetName)) {}
                }
            }
            if (shareFailed && state.phase != CallPhase.Ended) {
                Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs), contentAlignment = Alignment.Center) {
                    CallBanner(stringResource(Res.string.call_share_failed)) {
                        Button(onClick = { shareFailed = false }) { Text(stringResource(Res.string.action_close)) }
                    }
                }
            }
            // A desk intercom line opened without ringing: always say the microphone is live (edge case H3).
            if (state.intercom && state.phase != CallPhase.Ended) {
                Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs), contentAlignment = Alignment.Center) {
                    CallBanner(stringResource(Res.string.call_intercom_banner)) {}
                }
            }
            if (call.screenSharing && state.phase != CallPhase.Ended) {
                Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs), contentAlignment = Alignment.Center) {
                    CallBanner(stringResource(Res.string.call_sharing_banner)) {
                        Button(onClick = { scope.launch { core.stopScreenShare() } }) { Text(stringResource(Res.string.call_stop_sharing)) }
                    }
                }
            }
            CallBanners(
                call = call,
                name = name,
                onAcceptUpgrade = {
                    scope.launch {
                        platform.ensureCallPermissions(video = true)
                        core.respondToVideoUpgrade(true)
                    }
                },
                onDeclineUpgrade = { scope.launch { core.respondToVideoUpgrade(false) } },
                onCancelUpgrade = { scope.launch { core.cancelVideoUpgrade() } },
                onAcceptWaiting = { kind ->
                    scope.launch {
                        if (platform.ensureCallPermissions(kind == CallKind.Video)) core.acceptWaitingCall(kind) else permissionError = true
                    }
                },
                onDeclineWaiting = { scope.launch { core.declineWaitingCall() } },
                onResume = { scope.launch { core.setOnHold(false) } },
            )
            if (permissionError) {
                Text(
                    stringResource(Res.string.call_mic_permission),
                    color = Color(0xFFFFB4AB),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(Spacing.l).semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
            AnimatedVisibility(visible = controlsVisible || !showRemoteVideo, enter = fadeIn(), exit = fadeOut()) {
                when (state.phase) {
                    CallPhase.IncomingRinging -> IncomingControls(
                        isVideo = isVideo,
                        onAccept = { kind ->
                            scope.launch {
                                if (platform.ensureCallPermissions(kind == CallKind.Video)) core.acceptCall(kind) else permissionError = true
                            }
                        },
                        onDecline = { scope.launch { core.declineCall() } },
                        onDeclineWithMessage = { quickReplyOpen = true },
                    )
                    CallPhase.Ended -> Spacer(Modifier.height(120.dp))
                    else -> InCallControls(
                        call = call,
                        isVideo = isVideo,
                        canFlip = !platform.isDesktop && localVideo != null,
                        canUpgrade = !isVideo && !call.isGroup && state.phase == CallPhase.Connected && call.upgrade == null &&
                            core.peerSupports(state.peer, Capabilities.CALL_UPGRADE),
                        canHold = live && !call.isGroup && core.peerSupports(state.peer, Capabilities.CALL_HOLD),
                        canAddPeople = core.canAddToCall(),
                        onAddPeople = { addPeopleOpen = true },
                        canTransfer = core.canTransferCall(),
                        onTransfer = { transferOpen = true },
                        canRecord = call.recording || core.canRecordCall(),
                        onRecord = { if (call.recording) scope.launch { core.stopRecording() } else recordConfirmOpen = true },
                        canShareScreen = live && core.canShareScreen(),
                        onShareScreen = {
                            when {
                                call.screenSharing -> scope.launch { core.stopScreenShare() }
                                platform.isDesktop -> sharePickerOpen = true
                                // Android shares the whole screen after the system's own consent prompt.
                                else -> scope.launch {
                                    shareFailed = false
                                    if (platform.requestScreenCapture()) {
                                        shareFailed = !core.startScreenShare(ScreenSource(id = 0, title = "", isWindow = false))
                                    }
                                }
                            }
                        },
                        onMute = { scope.launch { core.setMicMuted(!call.micMuted) } },
                        onCamera = { scope.launch { core.setCameraOn(!call.cameraOn) } },
                        onFlip = { core.switchCamera() },
                        onHold = { scope.launch { core.setOnHold(!call.onHold) } },
                        onUpgrade = {
                            scope.launch { if (platform.ensureCallPermissions(video = true)) core.requestVideoUpgrade() }
                        },
                        onInfo = { infoOpen = true },
                        onEnd = { scope.launch { core.hangUp() } },
                    )
                }
            }
        }
    }

    if (quickReplyOpen && state.phase == CallPhase.IncomingRinging) {
        QuickReplyDialog(
            onDismiss = { quickReplyOpen = false },
            onSend = { text ->
                quickReplyOpen = false
                scope.launch { core.declineCall(text) }
            },
        )
    }
    if (infoOpen) StatsDialog(stats, onDismiss = { infoOpen = false })
    if (addPeopleOpen && state.phase == CallPhase.Connected) AddPeopleDialog(call, onDismiss = { addPeopleOpen = false })
    if (transferOpen && state.phase == CallPhase.Connected) AddPeopleDialog(call, onDismiss = { transferOpen = false }, transfer = true)
    if (recordConfirmOpen && state.phase == CallPhase.Connected) {
        AlertDialog(
            onDismissRequest = { recordConfirmOpen = false },
            title = { Text(stringResource(Res.string.call_record_confirm_title)) },
            text = { Text(stringResource(Res.string.call_record_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    recordConfirmOpen = false
                    scope.launch { core.startRecording() }
                }) { Text(stringResource(Res.string.call_record_action)) }
            },
            dismissButton = { TextButton(onClick = { recordConfirmOpen = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
    if (sharePickerOpen && live) ScreenSharePicker(onDismiss = { sharePickerOpen = false })
}

@Composable
private fun Header(call: ActiveCall, name: String, stats: CallStats, compact: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (compact) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                StatusText(call, name, MaterialTheme.typography.bodySmall)
            }
        } else {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                Text(
                    stringResource(Res.string.call_encrypted),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = Spacing.xs),
                )
            }
        }
        if (call.state.phase == CallPhase.Connected) QualityBars(stats.qualityBars())
    }
}

@Composable
private fun CenterIdentity(call: ActiveCall, name: String) {
    val core = LocalCore.current
    val people by core.people.collectAsState()
    val person = people.firstOrNull { it.fingerprint == call.state.peer }
    val ringing = call.state.phase in setOf(CallPhase.IncomingRinging, CallPhase.OutgoingRinging, CallPhase.OutgoingInviting)
    val pulse = rememberInfiniteTransition()
    val scale by pulse.animateFloat(1f, 1.08f, infiniteRepeatable(tween(900), RepeatMode.Reverse))

    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            if (ringing) {
                Box(Modifier.size(168.dp).scale(scale).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)))
            }
            Avatar(name, size = 128.dp)
        }
        Text(
            name,
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.xl).semantics { heading() },
        )
        person?.role?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f))
        }
        // An invitation into a group call names who else is there.
        if (call.members.isNotEmpty()) {
            val unknown = stringResource(Res.string.unknown_person)
            val others = call.members.joinToString(", ") { m -> people.firstOrNull { it.fingerprint == m.fingerprint }?.displayName ?: unknown }
            Text(
                stringResource(Res.string.call_group_with, others),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        StatusText(call, name, MaterialTheme.typography.titleMedium, Modifier.padding(top = Spacing.s))
        if (call.state.phase == CallPhase.Ended) {
            call.remoteQuickReply?.let {
                Text(
                    stringResource(Res.string.call_declined_with, it),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = Spacing.s).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        if (call.state.phase == CallPhase.IncomingRinging || call.state.direction == CallDirection.Outgoing && call.state.phase != CallPhase.Connected) {
            VerificationLabel(person?.verified == true, Modifier.padding(top = Spacing.s))
        }
        if (call.state.phase == CallPhase.Connected && call.remoteMicMuted && !call.remoteOnHold) {
            Text(stringResource(Res.string.call_remote_muted, name), color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = Spacing.s))
        }
        if (call.state.phase == CallPhase.Connected && call.state.kind == CallKind.Video && !call.remoteCameraOn && !call.remoteOnHold) {
            Text(stringResource(Res.string.call_remote_camera_off, name), color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = Spacing.xs))
        }
    }
}

/** Requests and states that need attention: video upgrade, a waiting call, hold, short notices. */
@Composable
private fun CallBanners(
    call: ActiveCall,
    name: String,
    onAcceptUpgrade: () -> Unit,
    onDeclineUpgrade: () -> Unit,
    onCancelUpgrade: () -> Unit,
    onAcceptWaiting: (CallKind) -> Unit,
    onDeclineWaiting: () -> Unit,
    onResume: () -> Unit,
) {
    val core = LocalCore.current
    val people by core.people.collectAsState()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        call.waiting?.let { waiting ->
            val waitingName = people.firstOrNull { it.fingerprint == waiting.peer }?.displayName ?: stringResource(Res.string.unknown_person)
            CallBanner(stringResource(Res.string.call_waiting_title, waitingName), assertive = true) {
                TextButton(onClick = onDeclineWaiting) { Text(stringResource(Res.string.call_waiting_decline), color = Color(0xFFFFB4AB)) }
                Button(
                    onClick = { onAcceptWaiting(waiting.kind) },
                    colors = ButtonDefaults.buttonColors(containerColor = ZoocallTheme.colors.accept, contentColor = ZoocallTheme.colors.onAccept),
                ) { Text(stringResource(Res.string.call_waiting_end_accept)) }
            }
        }
        when (call.upgrade) {
            UpgradeRequest.Incoming -> CallBanner(stringResource(Res.string.call_upgrade_incoming, name), assertive = true) {
                TextButton(onClick = onDeclineUpgrade) { Text(stringResource(Res.string.call_upgrade_not_now), color = Color.White) }
                Button(onClick = onAcceptUpgrade) { Text(stringResource(Res.string.call_upgrade_accept)) }
            }
            UpgradeRequest.Outgoing -> CallBanner(stringResource(Res.string.call_upgrade_waiting, name)) {
                TextButton(onClick = onCancelUpgrade) { Text(stringResource(Res.string.call_upgrade_cancel), color = Color.White) }
            }
            null -> Unit
        }
        if (call.onHold) {
            CallBanner(stringResource(Res.string.call_on_hold)) {
                Button(onClick = onResume) { Text(stringResource(Res.string.call_resume)) }
            }
        } else if (call.remoteOnHold && call.state.phase != CallPhase.Ended) {
            CallBanner(stringResource(Res.string.call_remote_on_hold, name))
        }
        call.notice?.let { notice ->
            val people by LocalCore.current.people.collectAsState()
            val noticeName = call.noticePeer?.let { fp -> people.firstOrNull { it.fingerprint == fp }?.displayName }
                ?: stringResource(Res.string.unknown_person)
            val text = when (notice) {
                CallNotice.UpgradeDeclined -> stringResource(Res.string.call_upgrade_declined, name)
                CallNotice.UpgradeNoAnswer -> stringResource(Res.string.call_upgrade_no_answer)
                CallNotice.AddDeclined -> stringResource(Res.string.call_add_declined, noticeName)
                CallNotice.AddBusy -> stringResource(Res.string.call_add_busy, noticeName)
                CallNotice.AddNoAnswer -> stringResource(Res.string.call_add_no_answer, noticeName)
                // Shown by the push-to-talk screen.
                CallNotice.ChannelBusy -> null
            }
            text?.let { CallBanner(it) }
        }
    }
}

/**
 * Accessibility flash alert while a call rings: one short white flash a second, below the
 * three-flashes-a-second photosensitivity threshold (WCAG 2.3.1).
 */
@Composable
private fun FlashAlert() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "flash")
    val alpha by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.keyframes {
                durationMillis = 1_000
                0f at 0
                0.6f at 120
                0.6f at 380
                0f at 520
            },
        ),
        label = "flashAlpha",
    )
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.background(Color.White))
}

@Composable
private fun CallBanner(text: String, assertive: Boolean = false, actions: @Composable () -> Unit = {}) {
    Surface(color = BannerColor, contentColor = Color.White, shape = MaterialTheme.shapes.large, modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)) {
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { liveRegion = if (assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite },
            )
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                actions()
            }
        }
    }
}

@Composable
private fun StatusText(call: ActiveCall, name: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    val state = call.state
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(state.phase) {
        while (state.phase == CallPhase.Connected) {
            now = Clock.System.now().toEpochMilliseconds()
            delay(1_000)
        }
    }
    val text = when (state.phase) {
        CallPhase.IncomingRinging -> stringResource(if (state.kind == CallKind.Video) Res.string.call_incoming_video else Res.string.call_incoming_audio)
        CallPhase.OutgoingInviting -> stringResource(Res.string.call_calling)
        CallPhase.OutgoingRinging -> stringResource(Res.string.call_ringing)
        CallPhase.Connecting -> stringResource(Res.string.call_connecting)
        CallPhase.Reconnecting -> stringResource(Res.string.call_reconnecting)
        CallPhase.Connected -> formatDuration(now - (state.connectedAtMs ?: now))
        CallPhase.Ended -> endReasonText(state.endReason, state.direction, name)
    }
    Text(
        text,
        style = style,
        color = if (state.phase == CallPhase.Reconnecting) Color(0xFFFFB95C) else Color.White.copy(alpha = 0.85f),
        textAlign = TextAlign.Center,
        // Announce state changes, but not every tick of the timer.
        modifier = modifier.semantics { if (state.phase != CallPhase.Connected) liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun endReasonText(reason: EndReason?, direction: CallDirection, name: String): String = when (reason) {
    EndReason.Declined -> if (direction == CallDirection.Outgoing) stringResource(Res.string.call_end_declined, name) else stringResource(Res.string.call_ended)
    EndReason.Busy -> stringResource(Res.string.call_end_busy, name)
    EndReason.DoNotDisturb -> stringResource(Res.string.call_end_dnd, name)
    EndReason.Missed -> if (direction == CallDirection.Outgoing) stringResource(Res.string.call_end_no_answer) else stringResource(Res.string.call_end_missed)
    EndReason.Cancelled -> stringResource(Res.string.call_end_cancelled)
    EndReason.Unreachable -> stringResource(Res.string.call_end_unreachable, name)
    EndReason.FailedNetwork -> stringResource(Res.string.call_end_network)
    EndReason.FailedMedia -> stringResource(Res.string.call_end_media)
    EndReason.NotAllowed -> stringResource(Res.string.call_end_not_allowed, name)
    EndReason.AnsweredElsewhere -> stringResource(Res.string.call_end_answered_elsewhere)
    EndReason.Completed, null -> stringResource(Res.string.call_ended)
}

@Composable
private fun QualityBars(bars: Int) {
    val label = stringResource(Res.string.call_quality, bars)
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = label }, verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) {
            Box(
                Modifier
                    .padding(start = 2.dp)
                    .width(4.dp)
                    .height((4 + i * 4).dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(if (i <= bars) Color.White else Color.White.copy(alpha = 0.3f)),
            )
        }
    }
}

@Composable
private fun IncomingControls(isVideo: Boolean, onAccept: (CallKind) -> Unit, onDecline: () -> Unit, onDeclineWithMessage: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = Spacing.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.padding(bottom = Spacing.xl), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            PillAction(Icons.AutoMirrored.Rounded.Message, stringResource(Res.string.call_decline_message), onDeclineWithMessage)
            if (isVideo) PillAction(Icons.Rounded.Call, stringResource(Res.string.call_accept_audio)) { onAccept(CallKind.Audio) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundAction(
                icon = Icons.Rounded.CallEnd,
                label = stringResource(Res.string.call_decline),
                container = ZoocallTheme.colors.decline,
                content = ZoocallTheme.colors.onDecline,
                size = 72.dp,
                onClick = onDecline,
            )
            RoundAction(
                icon = if (isVideo) Icons.Rounded.Videocam else Icons.Rounded.Call,
                label = stringResource(Res.string.call_accept),
                container = ZoocallTheme.colors.accept,
                content = ZoocallTheme.colors.onAccept,
                size = 72.dp,
                onClick = { onAccept(if (isVideo) CallKind.Video else CallKind.Audio) },
            )
        }
    }
}

@Composable
private fun PillAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.White.copy(alpha = 0.15f), modifier = Modifier.heightIn(min = 48.dp)) {
        Row(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Text(label, modifier = Modifier.padding(start = Spacing.s))
        }
    }
}

@Composable
private fun InCallControls(
    call: ActiveCall,
    isVideo: Boolean,
    canFlip: Boolean,
    canUpgrade: Boolean,
    canHold: Boolean,
    canAddPeople: Boolean,
    onAddPeople: () -> Unit,
    canTransfer: Boolean,
    onTransfer: () -> Unit,
    canRecord: Boolean,
    onRecord: () -> Unit,
    canShareScreen: Boolean,
    onShareScreen: () -> Unit,
    onMute: () -> Unit,
    onCamera: () -> Unit,
    onFlip: () -> Unit,
    onHold: () -> Unit,
    onUpgrade: () -> Unit,
    onInfo: () -> Unit,
    onEnd: () -> Unit,
) {
    val platform = LocalPlatformActions.current
    val routes by platform.audioRoutes.collectAsState()
    val currentRoute by platform.currentAudioRoute.collectAsState()
    var routeMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var devicesOpen by remember { mutableStateOf(false) }
    val deviceManager = LocalCore.current.mediaEngine.devices
    val toggleOff = Color.White.copy(alpha = 0.15f)
    val toggleOn = Color.White

    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xl),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundAction(
            icon = if (call.micMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
            label = stringResource(if (call.micMuted) Res.string.call_unmute else Res.string.call_mute),
            container = if (call.micMuted) toggleOn else toggleOff,
            content = if (call.micMuted) Color.Black else Color.White,
            onClick = onMute,
        )
        if (isVideo) {
            RoundAction(
                icon = if (call.cameraOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                label = stringResource(if (call.cameraOn) Res.string.call_camera_off else Res.string.call_camera_on),
                container = if (!call.cameraOn) toggleOn else toggleOff,
                content = if (!call.cameraOn) Color.Black else Color.White,
                onClick = onCamera,
            )
        } else if (canUpgrade) {
            RoundAction(Icons.Rounded.Videocam, stringResource(Res.string.call_upgrade_request), toggleOff, Color.White, onClick = onUpgrade)
        }
        if (isVideo && canFlip) {
            RoundAction(Icons.Rounded.Cameraswitch, stringResource(Res.string.call_flip_camera), toggleOff, Color.White, onClick = onFlip)
        }
        if (canShareScreen || call.screenSharing) {
            RoundAction(
                icon = if (call.screenSharing) Icons.AutoMirrored.Rounded.StopScreenShare else Icons.AutoMirrored.Rounded.ScreenShare,
                label = stringResource(if (call.screenSharing) Res.string.call_stop_sharing else Res.string.call_share_screen),
                container = if (call.screenSharing) toggleOn else toggleOff,
                content = if (call.screenSharing) Color.Black else Color.White,
                onClick = onShareScreen,
            )
        }
        if (routes.size > 1) {
            Box {
                RoundAction(
                    icon = currentRoute?.icon ?: Icons.AutoMirrored.Rounded.VolumeUp,
                    label = stringResource(Res.string.call_speaker) + (currentRoute?.let { ": ${it.label}" } ?: ""),
                    container = toggleOff,
                    content = Color.White,
                    onClick = { routeMenu = true },
                )
                DropdownMenu(expanded = routeMenu, onDismissRequest = { routeMenu = false }) {
                    routes.forEach { route ->
                        DropdownMenuItem(
                            text = { Text(route.label) },
                            leadingIcon = { Icon(route.icon, null) },
                            onClick = {
                                routeMenu = false
                                platform.selectAudioRoute(route)
                            },
                        )
                    }
                }
            }
        }
        Box {
            RoundAction(
                icon = Icons.Rounded.MoreVert,
                label = stringResource(Res.string.action_more),
                container = if (call.onHold) toggleOn else toggleOff,
                content = if (call.onHold) Color.Black else Color.White,
                onClick = { moreMenu = true },
            )
            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                if (canAddPeople) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.call_add_people)) },
                        leadingIcon = { Icon(Icons.Rounded.PersonAdd, null) },
                        onClick = { moreMenu = false; onAddPeople() },
                    )
                }
                if (canTransfer) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.call_transfer)) },
                        leadingIcon = { Icon(Icons.Rounded.PhoneForwarded, null) },
                        onClick = { moreMenu = false; onTransfer() },
                    )
                }
                if (canRecord) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (call.recording) Res.string.call_record_stop else Res.string.call_record)) },
                        leadingIcon = { Icon(if (call.recording) Icons.Rounded.StopCircle else Icons.Rounded.FiberManualRecord, null) },
                        onClick = { moreMenu = false; onRecord() },
                    )
                }
                if (canHold) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (call.onHold) Res.string.call_resume else Res.string.call_hold)) },
                        leadingIcon = { Icon(if (call.onHold) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null) },
                        onClick = { moreMenu = false; onHold() },
                    )
                }
                platform.liveCaptions?.let { captions ->
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.call_live_captions)) },
                        leadingIcon = { Icon(Icons.Rounded.ClosedCaption, null) },
                        onClick = { moreMenu = false; captions.open() },
                    )
                }
                if (deviceManager != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.call_devices)) },
                        leadingIcon = { Icon(Icons.Rounded.SettingsVoice, null) },
                        onClick = { moreMenu = false; devicesOpen = true },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.call_info)) },
                    leadingIcon = { Icon(Icons.Rounded.Info, null) },
                    onClick = { moreMenu = false; onInfo() },
                )
            }
        }
        RoundAction(
            icon = Icons.Rounded.CallEnd,
            label = stringResource(Res.string.call_end),
            container = ZoocallTheme.colors.decline,
            content = ZoocallTheme.colors.onDecline,
            size = 72.dp,
            onClick = onEnd,
        )
    }
    if (devicesOpen && deviceManager != null) {
        AlertDialog(
            onDismissRequest = { devicesOpen = false },
            title = { Text(stringResource(Res.string.call_devices)) },
            text = { DeviceSettings(deviceManager, inCall = true, showTests = false) },
            confirmButton = { TextButton(onClick = { devicesOpen = false }) { Text(stringResource(Res.string.action_close)) } },
        )
    }
}

@Composable
private fun QuickReplyDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var custom by rememberSaveable { mutableStateOf("") }
    val presets = listOf(
        stringResource(Res.string.call_quick_reply_1),
        stringResource(Res.string.call_quick_reply_2),
        stringResource(Res.string.call_quick_reply_3),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.call_decline_message)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                presets.forEach { preset ->
                    Surface(
                        onClick = { onSend(preset) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(preset, modifier = Modifier.padding(Spacing.m))
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.take(200) },
                    placeholder = { Text(stringResource(Res.string.call_quick_reply_custom)) },
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(custom.trim()) }, enabled = custom.isNotBlank()) {
                Text(stringResource(Res.string.call_quick_reply_send))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) } },
    )
}

@Composable
private fun StatsDialog(stats: CallStats, onDismiss: () -> Unit) {
    val unknown = stringResource(Res.string.call_stats_unknown)
    val rows = listOf(
        stringResource(Res.string.call_stats_audio_codec) to (stats.audioCodec ?: unknown),
        stringResource(Res.string.call_stats_video_codec) to (stats.videoCodec ?: unknown),
        stringResource(Res.string.call_stats_rtt) to (stats.roundTripTimeMs?.let { "$it ms" } ?: unknown),
        stringResource(Res.string.call_stats_loss) to (stats.packetLossPercent?.let { "${(it * 10).toInt() / 10.0} %" } ?: unknown),
        stringResource(Res.string.call_stats_bitrate) to (
            if (stats.outgoingBitrateKbps != null || stats.incomingBitrateKbps != null) {
                "${stats.outgoingBitrateKbps ?: "–"} / ${stats.incomingBitrateKbps ?: "–"} kbps"
            } else {
                unknown
            }
            ),
        stringResource(Res.string.call_stats_video) to (
            if (stats.frameWidth != null && stats.frameHeight != null) {
                "${stats.frameWidth}×${stats.frameHeight}" + (stats.framesPerSecond?.let { " @ ${it.toInt()} fps" } ?: "")
            } else {
                unknown
            }
            ),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.call_info)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                rows.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) } },
    )
}

@Composable
private fun RoundAction(
    icon: ImageVector,
    label: String,
    container: Color,
    content: Color,
    size: Dp = 60.dp,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = Modifier.size(size).semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(size * 0.45f))
        }
    }
}

private enum class Corner { TopStart, TopEnd, BottomStart, BottomEnd }

/**
 * Draggable self view that snaps to the nearest corner (starting bottom-right, clear of the
 * header). Full screen, dimmed, while the call is still connecting.
 */
@Composable
private fun SelfPreview(track: VideoTrackHandle?, mirror: Boolean, fullScreen: Boolean) {
    if (fullScreen) {
        VideoView(track, Modifier.fillMaxSize(), mirror = mirror, fill = true)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(Spacing.l)) {
        val density = LocalDensity.current
        val previewW = if (maxWidth > maxHeight) 200.dp else 112.dp
        val previewH = if (maxWidth > maxHeight) 124.dp else 160.dp
        val controlsReserve = 128.dp
        val maxX = with(density) { (maxWidth - previewW).toPx() }.coerceAtLeast(0f)
        val minY = with(density) { 48.dp.toPx() }
        val maxY = with(density) { (maxHeight - previewH - controlsReserve).toPx() }.coerceAtLeast(minY)
        var corner by rememberSaveable { mutableStateOf(Corner.BottomEnd) }
        var drag by remember { mutableStateOf(IntOffset.Zero) }
        val base = when (corner) {
            Corner.TopStart -> IntOffset(0, minY.toInt())
            Corner.TopEnd -> IntOffset(maxX.toInt(), minY.toInt())
            Corner.BottomStart -> IntOffset(0, maxY.toInt())
            Corner.BottomEnd -> IntOffset(maxX.toInt(), maxY.toInt())
        }
        Box(
            Modifier
                .offset { base + drag }
                .size(previewW, previewH)
                .clip(MaterialTheme.shapes.medium)
                .background(Color.DarkGray)
                .pointerInput(maxX, maxY, corner) {
                    detectDragGestures(
                        onDragEnd = {
                            val x = base.x + drag.x
                            val y = base.y + drag.y
                            val right = x > maxX / 2
                            val bottom = y > (minY + maxY) / 2
                            corner = when {
                                bottom && right -> Corner.BottomEnd
                                bottom -> Corner.BottomStart
                                right -> Corner.TopEnd
                                else -> Corner.TopStart
                            }
                            drag = IntOffset.Zero
                        },
                        onDragCancel = { drag = IntOffset.Zero },
                    ) { change, amount ->
                        change.consume()
                        drag = IntOffset(drag.x + amount.x.toInt(), drag.y + amount.y.toInt())
                    }
                },
        ) {
            VideoView(track, Modifier.fillMaxSize(), mirror = mirror, fill = true, overlay = true)
        }
    }
}
