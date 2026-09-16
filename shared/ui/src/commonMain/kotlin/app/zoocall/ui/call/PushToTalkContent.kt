package app.zoocall.ui.call

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.OutlinedButton
import app.zoocall.ui.resources.call_add_people
import app.zoocall.ui.resources.call_group_people
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.zoocall.core.call.ActiveCall
import app.zoocall.core.call.CallNotice
import app.zoocall.core.call.CallPhase
import app.zoocall.core.model.EndReason
import app.zoocall.ui.LocalCore
import app.zoocall.ui.components.Avatar
import app.zoocall.ui.platform.LocalPlatformActions
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.call_end_busy
import app.zoocall.ui.resources.call_end_dnd
import app.zoocall.ui.resources.call_end_unreachable
import app.zoocall.ui.resources.ptt_channel_busy
import app.zoocall.ui.resources.ptt_connecting
import app.zoocall.ui.resources.ptt_ended
import app.zoocall.ui.resources.ptt_idle
import app.zoocall.ui.resources.ptt_idle_desktop
import app.zoocall.ui.resources.ptt_leave
import app.zoocall.ui.resources.ptt_not_allowed
import app.zoocall.ui.resources.ptt_remote_talking
import app.zoocall.ui.resources.ptt_stop
import app.zoocall.ui.resources.ptt_talk
import app.zoocall.ui.resources.ptt_title
import app.zoocall.ui.resources.ptt_you_talking
import app.zoocall.ui.resources.unknown_person
import app.zoocall.ui.theme.Spacing
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Walkie-talkie colors (docs/05 §2: secondary Indigo for PTT, "live" red while transmitting). */
private val PttIndigo = Color(0xFF5B63D3)
private val LiveRed = Color(0xFFE5484D)

/** Push-to-talk session screen (docs/05-ux-ui-design.md §5.6). */
@Composable
internal fun PushToTalkContent(call: ActiveCall) {
    val core = LocalCore.current
    val platform = LocalPlatformActions.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val people by core.people.collectAsState()
    val name = people.firstOrNull { it.fingerprint == call.state.peer }?.displayName ?: stringResource(Res.string.unknown_person)
    val state = call.state
    val live = state.phase == CallPhase.Connected || state.phase == CallPhase.Reconnecting
    val talkerName = call.talker?.let { fp -> people.firstOrNull { it.fingerprint == fp }?.displayName } ?: name
    var addPeopleOpen by remember { mutableStateOf(false) }

    fun talk(on: Boolean) {
        scope.launch {
            val ok = core.setTalking(on)
            if (on && ok) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    val status = when {
        state.phase == CallPhase.Ended -> when (state.endReason) {
            EndReason.NotAllowed -> stringResource(Res.string.ptt_not_allowed, name)
            EndReason.Busy -> stringResource(Res.string.call_end_busy, name)
            EndReason.DoNotDisturb -> stringResource(Res.string.call_end_dnd, name)
            EndReason.Unreachable -> stringResource(Res.string.call_end_unreachable, name)
            else -> stringResource(Res.string.ptt_ended)
        }
        !live -> stringResource(Res.string.ptt_connecting)
        call.notice == CallNotice.ChannelBusy -> stringResource(Res.string.ptt_channel_busy, talkerName)
        call.talking -> stringResource(Res.string.ptt_you_talking)
        call.remoteTalking -> stringResource(Res.string.ptt_remote_talking, talkerName)
        platform.isDesktop -> stringResource(Res.string.ptt_idle_desktop)
        else -> stringResource(Res.string.ptt_idle)
    }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(Spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(Res.string.ptt_title),
            style = MaterialTheme.typography.titleSmall,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = Spacing.m).semantics { heading() },
        )
        Spacer(Modifier.weight(1f))
        // Everyone on the channel; whoever is talking gets a highlighted ring.
        val channel = listOf(state.peer) + call.members.map { it.fingerprint }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            channel.take(4).forEach { fp ->
                val personName = people.firstOrNull { it.fingerprint == fp }?.displayName ?: stringResource(Res.string.unknown_person)
                val speaking = live && call.remoteTalking && (call.talker ?: state.peer) == fp
                Box(
                    Modifier
                        .clip(CircleShape)
                        .border(width = 4.dp, color = if (speaking) PttIndigo else Color.Transparent, shape = CircleShape)
                        .padding(6.dp),
                ) {
                    Avatar(personName, size = if (channel.size == 1) 104.dp else 72.dp)
                }
            }
        }
        Text(
            if (channel.size == 1) name else stringResource(Res.string.call_group_people, (channel.size + 1).toString()),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.l),
        )
        Text(
            status,
            style = MaterialTheme.typography.bodyLarge,
            color = if (call.talking) LiveRed else Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.s).semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.weight(1f))
        TalkButton(
            talking = call.talking,
            enabled = live && !call.remoteTalking,
            onPress = { talk(true) },
            onRelease = { talk(false) },
        )
        Spacer(Modifier.height(Spacing.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), modifier = Modifier.padding(bottom = Spacing.l)) {
            if (live && core.canAddToCall()) {
                OutlinedButton(onClick = { addPeopleOpen = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                    Icon(Icons.Rounded.PersonAdd, null, Modifier.size(20.dp))
                    Text(stringResource(Res.string.call_add_people), Modifier.padding(start = Spacing.s))
                }
            }
            Button(
                onClick = { scope.launch { core.hangUp() } },
                colors = ButtonDefaults.buttonColors(containerColor = LiveRed, contentColor = Color.White),
            ) {
                Icon(Icons.Rounded.CallEnd, null, Modifier.size(20.dp))
                Text(stringResource(Res.string.ptt_leave), Modifier.padding(start = Spacing.s))
            }
        }
    }
    if (addPeopleOpen && live) AddPeopleDialog(call, onDismiss = { addPeopleOpen = false })
}

/**
 * Hold to talk. Screen-reader and switch users can't hold, so the accessibility action toggles
 * talking on and off instead.
 */
@Composable
private fun TalkButton(talking: Boolean, enabled: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    val talkLabel = stringResource(Res.string.ptt_talk)
    val stopLabel = stringResource(Res.string.ptt_stop)
    val canTalk by rememberUpdatedState(enabled)
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val pulse = rememberInfiniteTransition(label = "ptt")
    val ring by pulse.animateFloat(1f, 1.12f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pttRing")

    Box(
        Modifier
            .size(200.dp)
            .semantics {
                role = Role.Button
                contentDescription = if (talking) stopLabel else talkLabel
                onClick(if (talking) stopLabel else talkLabel) {
                    if (talking) release() else press()
                    true
                }
                if (!enabled && !talking) disabled()
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (canTalk) {
                            press()
                            tryAwaitRelease()
                            release()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (talking) {
            Box(Modifier.size(180.dp).scale(ring).clip(CircleShape).background(LiveRed.copy(alpha = 0.25f)))
        }
        Box(
            Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(
                    when {
                        talking -> LiveRed
                        enabled -> PttIndigo
                        else -> Color.White.copy(alpha = 0.12f)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(64.dp))
        }
    }
}
