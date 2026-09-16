package app.zoocall.ui.knock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.zoocall.core.chat.KnockEvent
import app.zoocall.core.chat.KnockReply
import app.zoocall.core.model.CallKind
import app.zoocall.ui.LocalCore
import app.zoocall.ui.LocalSnackbar
import app.zoocall.ui.common.rememberCallLauncher
import app.zoocall.ui.components.Avatar
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.knock_call
import app.zoocall.ui.resources.knock_call_back
import app.zoocall.ui.resources.knock_default_text
import app.zoocall.ui.resources.knock_dismiss
import app.zoocall.ui.resources.knock_received
import app.zoocall.ui.resources.knock_replied_busy
import app.zoocall.ui.resources.knock_replied_call_me
import app.zoocall.ui.resources.knock_replied_two_minutes
import app.zoocall.ui.resources.knock_reply_busy
import app.zoocall.ui.resources.knock_reply_call_me
import app.zoocall.ui.resources.knock_reply_failed
import app.zoocall.ui.resources.knock_reply_two_minutes
import app.zoocall.ui.resources.unknown_person
import app.zoocall.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Shows incoming knocks as a card with one-tap replies, and answers to our knocks as snackbars.
 * Place it above the main content.
 */
@Composable
fun KnockHost() {
    val core = LocalCore.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val launcher = rememberCallLauncher()
    var pending by remember { mutableStateOf<KnockEvent.Received?>(null) }

    LaunchedEffect(core) {
        core.knocks.collect { event ->
            val name = core.person(event.from.hex)?.displayName ?: getString(Res.string.unknown_person)
            when (event) {
                is KnockEvent.Received -> {
                    pending = event
                    if (!event.quiet) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                is KnockEvent.Replied -> scope.launch {
                    val message = when (event.reply) {
                        KnockReply.CallMe -> getString(Res.string.knock_replied_call_me, name)
                        KnockReply.TwoMinutes -> getString(Res.string.knock_replied_two_minutes, name)
                        KnockReply.Busy -> getString(Res.string.knock_replied_busy, name)
                    }
                    val result = snackbar.showSnackbar(
                        message = message,
                        actionLabel = if (event.reply == KnockReply.CallMe) getString(Res.string.knock_call_back) else null,
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) launcher.call(event.from.hex, name, CallKind.Audio)
                }
            }
        }
    }

    val knock = pending
    // A knock is about "now": it goes away by itself after a while.
    LaunchedEffect(knock?.id) {
        if (knock != null) {
            delay(KNOCK_VISIBLE_MS)
            if (pending?.id == knock.id) pending = null
        }
    }

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = knock != null,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
        ) {
            val shown = remember(knock?.id) { knock } ?: return@AnimatedVisibility
            val name = core.person(shown.from.hex)?.displayName ?: stringResource(Res.string.unknown_person)
            KnockCard(
                name = name,
                text = shown.text,
                onReply = { reply ->
                    pending = null
                    scope.launch {
                        if (!core.replyToKnock(shown.from, shown.id, reply)) {
                            snackbar.showSnackbar(getString(Res.string.knock_reply_failed, name))
                        }
                    }
                },
                onCall = {
                    pending = null
                    launcher.call(shown.from.hex, name, CallKind.Audio)
                },
                onDismiss = { pending = null },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KnockCard(
    name: String,
    text: String,
    onReply: (KnockReply) -> Unit,
    onCall: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier.padding(Spacing.m).widthIn(max = 480.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name, size = 40.dp)
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = Spacing.m)
                        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                ) {
                    Text(
                        "👋 " + stringResource(Res.string.knock_received, name),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text.ifBlank { stringResource(Res.string.knock_default_text) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(Res.string.knock_dismiss)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                FilledTonalButton(onClick = { onReply(KnockReply.CallMe) }) { Text(stringResource(Res.string.knock_reply_call_me)) }
                FilledTonalButton(onClick = { onReply(KnockReply.TwoMinutes) }) { Text(stringResource(Res.string.knock_reply_two_minutes)) }
                FilledTonalButton(onClick = { onReply(KnockReply.Busy) }) { Text(stringResource(Res.string.knock_reply_busy)) }
                Button(onClick = onCall) {
                    Icon(Icons.Rounded.Call, null, Modifier.size(18.dp))
                    Text(stringResource(Res.string.knock_call, name), Modifier.padding(start = Spacing.s))
                }
            }
        }
    }
}

private const val KNOCK_VISIBLE_MS = 120_000L
