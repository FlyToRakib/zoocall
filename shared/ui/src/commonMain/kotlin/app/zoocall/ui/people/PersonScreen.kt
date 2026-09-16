package app.zoocall.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zoocall.core.crypto.SafetyCodes
import app.zoocall.core.model.CallKind
import app.zoocall.ui.LocalCore
import app.zoocall.ui.LocalSnackbar
import androidx.compose.material.icons.rounded.SettingsVoice
import app.zoocall.ui.settings.SettingSwitch
import app.zoocall.ui.resources.ptt_action
import app.zoocall.ui.resources.ptt_action_body
import app.zoocall.ui.resources.ptt_allow
import app.zoocall.ui.resources.ptt_allow_body
import app.zoocall.ui.resources.person_linked_device
import app.zoocall.ui.resources.intercom_action
import app.zoocall.ui.resources.intercom_action_body
import app.zoocall.ui.resources.intercom_allow
import app.zoocall.ui.resources.intercom_allow_body
import app.zoocall.ui.platform.LocalPlatformActions
import app.zoocall.core.model.DeviceClass
import androidx.compose.material.icons.rounded.RecordVoiceOver
import app.zoocall.ui.resources.person_linked_device_body
import app.zoocall.ui.resources.person_linked_device_on
import app.zoocall.ui.resources.person_linked_device_waiting
import app.zoocall.core.app.KnockResult
import androidx.compose.material.icons.rounded.WavingHand
import app.zoocall.ui.resources.knock_action
import app.zoocall.ui.resources.knock_action_body
import app.zoocall.ui.resources.knock_blocked
import app.zoocall.ui.resources.knock_not_supported
import app.zoocall.ui.resources.knock_sent
import app.zoocall.ui.resources.knock_unreachable
import org.jetbrains.compose.resources.getString
import app.zoocall.ui.common.DetailScaffold
import app.zoocall.ui.common.rememberCallLauncher
import app.zoocall.ui.components.Avatar
import app.zoocall.ui.components.VerificationLabel
import app.zoocall.ui.components.EmptyState
import app.zoocall.ui.components.relativeTime
import app.zoocall.ui.resources.person_unavailable_body
import app.zoocall.ui.resources.person_unavailable_title
import androidx.compose.material.icons.rounded.PersonOff
import app.zoocall.ui.components.presenceLabel
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.action_call
import app.zoocall.ui.resources.action_cancel
import app.zoocall.ui.resources.action_message
import app.zoocall.ui.resources.action_video
import app.zoocall.ui.resources.last_seen
import app.zoocall.ui.resources.person_add_contact
import app.zoocall.ui.resources.person_address
import app.zoocall.ui.resources.person_block
import app.zoocall.ui.resources.person_block_confirm_body
import app.zoocall.ui.resources.person_block_confirm_title
import app.zoocall.ui.resources.person_blocked_label
import app.zoocall.ui.resources.person_favorite
import app.zoocall.ui.resources.person_remove_contact
import app.zoocall.ui.resources.person_safety_number
import app.zoocall.ui.resources.person_safety_number_help
import app.zoocall.ui.resources.person_unblock
import app.zoocall.ui.resources.person_unfavorite
import app.zoocall.ui.resources.person_verify
import app.zoocall.ui.theme.Spacing
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun PersonScreen(personId: String, onBack: () -> Unit, onVerify: (String) -> Unit, onMessage: (String) -> Unit) {
    val core = LocalCore.current
    val scope = rememberCoroutineScope()
    val people by core.people.collectAsStateWithLifecycle()
    val person = people.firstOrNull { it.id == personId }
    val launcher = rememberCallLauncher()
    val snackbar = LocalSnackbar.current
    val platform = LocalPlatformActions.current
    val settings by core.settings.collectAsStateWithLifecycle()
    var confirmBlock by remember { mutableStateOf(false) }

    DetailScaffold(title = person?.displayName ?: stringResource(Res.string.person_unavailable_title), onBack = onBack) { padding ->
        if (person == null) {
            Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
                EmptyState(
                    Icons.Rounded.PersonOff,
                    stringResource(Res.string.person_unavailable_title),
                    stringResource(Res.string.person_unavailable_body),
                )
            }
            return@DetailScaffold
        }
        val fp = person.fingerprint
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Avatar(person.displayName, size = 112.dp, presence = person.presence, online = person.online)
                Text(
                    person.displayName,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                if (person.role.isNotBlank()) Text(person.role, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (person.online && person.statusText.isNotBlank()) {
                    Text("“${person.statusText}”", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                }
                Text(
                    if (person.online) presenceLabel(person.presence, true)
                    else person.lastSeenMs?.let { stringResource(Res.string.last_seen, relativeTime(it)) } ?: presenceLabel(person.presence, false),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (person.blocked) {
                    Text(stringResource(Res.string.person_blocked_label), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                } else {
                    VerificationLabel(person.verified)
                }

                Row(Modifier.padding(top = Spacing.l), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                    ActionButton(Icons.Rounded.Call, stringResource(Res.string.action_call), enabled = !person.blocked) {
                        launcher.call(person.id, person.displayName, CallKind.Audio)
                    }
                    ActionButton(Icons.Rounded.Videocam, stringResource(Res.string.action_video), enabled = !person.blocked) {
                        launcher.call(person.id, person.displayName, CallKind.Video)
                    }
                    ActionButton(Icons.AutoMirrored.Rounded.Chat, stringResource(Res.string.action_message), enabled = !person.blocked) {
                        scope.launch { core.resolve(person.id)?.let { onMessage(it.hex) } }
                    }
                }
            }

            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                HorizontalDivider()
                if (person.online && !person.blocked) {
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.knock_action)) },
                        supportingContent = { Text(stringResource(Res.string.knock_action_body)) },
                        leadingContent = { Icon(Icons.Rounded.WavingHand, null) },
                        modifier = Modifier.clickableRow {
                            scope.launch {
                                val message = when (core.knock(person.id)) {
                                    KnockResult.Sent -> getString(Res.string.knock_sent, person.displayName)
                                    KnockResult.Unreachable -> getString(Res.string.knock_unreachable, person.displayName)
                                    KnockResult.NotSupported -> getString(Res.string.knock_not_supported, person.displayName)
                                    KnockResult.Blocked -> getString(Res.string.knock_blocked, person.displayName)
                                }
                                snackbar.showSnackbar(message)
                            }
                        },
                    )
                }
                if (person.online && !person.blocked) {
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.ptt_action)) },
                        supportingContent = { Text(stringResource(Res.string.ptt_action_body)) },
                        leadingContent = { Icon(Icons.Rounded.SettingsVoice, null) },
                        modifier = Modifier.clickableRow { launcher.pushToTalk(person.id, person.displayName) },
                    )
                }
                // Desk intercom reaches computers only (docs/01 §4).
                if (fp != null && person.online && !person.blocked && person.deviceClass == DeviceClass.Desktop && core.supportsIntercom(fp)) {
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.intercom_action)) },
                        supportingContent = { Text(stringResource(Res.string.intercom_action_body)) },
                        leadingContent = { Icon(Icons.Rounded.RecordVoiceOver, null) },
                        modifier = Modifier.clickableRow { launcher.intercom(person.id, person.displayName) },
                    )
                }
                if (fp != null && platform.isDesktop && settings.deskIntercom && person.isContact && !person.blocked) {
                    SettingSwitch(
                        label = stringResource(Res.string.intercom_allow),
                        body = stringResource(Res.string.intercom_allow_body, person.displayName),
                        checked = person.allowIntercom,
                    ) { allow -> scope.launch { core.setAllowIntercom(fp, allow) } }
                }
                if (fp != null && person.isContact && !person.blocked) {
                    SettingSwitch(
                        label = stringResource(Res.string.ptt_allow),
                        body = stringResource(Res.string.ptt_allow_body, person.displayName),
                        checked = person.allowPushToTalk,
                    ) { allow -> scope.launch { core.setAllowPushToTalk(fp, allow) } }
                }
                // Linking means your calls ring there, so it's offered only for a device whose key you verified.
                if (fp != null && person.isContact && person.verified && !person.blocked) {
                    SettingSwitch(
                        label = stringResource(Res.string.person_linked_device),
                        body = when {
                            person.linkConfirmed -> stringResource(Res.string.person_linked_device_on)
                            person.linkedDevice -> stringResource(Res.string.person_linked_device_waiting, person.displayName)
                            else -> stringResource(Res.string.person_linked_device_body)
                        },
                        checked = person.linkedDevice,
                    ) { linked -> scope.launch { core.setLinkedDevice(fp, linked) } }
                }
                if (!person.verified && !person.blocked) {
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.person_verify)) },
                        leadingContent = { Icon(Icons.Rounded.Verified, null) },
                        modifier = Modifier.clickableRow { onVerify(person.id) },
                    )
                }
                if (fp != null && !person.isContact) {
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.person_add_contact)) },
                        leadingContent = { Icon(Icons.Rounded.PersonAdd, null) },
                        modifier = Modifier.clickableRow { scope.launch { core.addContact(fp, verified = false) } },
                    )
                }
                if (fp != null && person.isContact) {
                    ListItem(
                        headlineContent = { Text(stringResource(if (person.favorite) Res.string.person_unfavorite else Res.string.person_favorite)) },
                        leadingContent = { Icon(if (person.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, null) },
                        modifier = Modifier.clickableRow { scope.launch { core.setFavorite(fp, !person.favorite) } },
                    )
                }
                if (fp != null) {
                    ListItem(
                        headlineContent = {
                            Text(
                                stringResource(if (person.blocked) Res.string.person_unblock else Res.string.person_block),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingContent = { Icon(Icons.Rounded.Block, null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickableRow {
                            if (person.blocked) scope.launch { core.setBlocked(fp, false) } else confirmBlock = true
                        },
                    )
                }
                if (fp != null && person.isContact) {
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.person_remove_contact)) },
                        leadingContent = { Icon(Icons.Rounded.PersonRemove, null) },
                        modifier = Modifier.clickableRow {
                            scope.launch {
                                core.removeContact(fp)
                                onBack()
                            }
                        },
                    )
                }
                if (fp != null) {
                    HorizontalDivider()
                    val number = remember(fp) { SafetyCodes.formatSafetyNumber(core.safetyNumberWith(fp)) }
                    Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        Text(stringResource(Res.string.person_safety_number), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                        Text(
                            number.chunked(4).joinToString("\n") { it.joinToString("  ") },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(Res.string.person_safety_number_help, person.displayName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                person.address?.let {
                    Text(
                        stringResource(Res.string.person_address, it.toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                    )
                }
            }
        }

        if (confirmBlock && fp != null) {
            AlertDialog(
                onDismissRequest = { confirmBlock = false },
                title = { Text(stringResource(Res.string.person_block_confirm_title, person.displayName)) },
                text = { Text(stringResource(Res.string.person_block_confirm_body, person.displayName)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmBlock = false
                        scope.launch { core.setBlocked(fp, true) }
                    }) { Text(stringResource(Res.string.person_block), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text(stringResource(Res.string.action_cancel)) } },
            )
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(64.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
        }
        // The button already announces the label; don't read it twice.
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = Spacing.s).clearAndSetSemantics { },
        )
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = clickable(onClick = onClick)
