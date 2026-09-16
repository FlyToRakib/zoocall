package app.zoocall.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.NetworkCheck
import app.zoocall.ui.resources.doctor_settings_body
import app.zoocall.ui.resources.doctor_title
import app.zoocall.ui.resources.settings_sounds
import app.zoocall.ui.resources.settings_visibility_contacts
import app.zoocall.ui.resources.settings_strong_ns
import app.zoocall.ui.resources.settings_desk_intercom
import app.zoocall.ui.lock.AppLockSetting
import app.zoocall.ui.backup.BackupSettings
import app.zoocall.ui.resources.settings_backup
import app.zoocall.ui.resources.settings_desk_intercom_body
import app.zoocall.ui.resources.settings_strong_ns_body
import app.zoocall.ui.resources.settings_camera_effects
import app.zoocall.ui.resources.settings_camera_effects_body
import androidx.compose.material.icons.rounded.AutoAwesome
import app.zoocall.ui.resources.settings_global_mute
import app.zoocall.ui.resources.settings_global_mute_body
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zoocall.core.app.ThemeMode
import app.zoocall.core.model.AllowCallsFrom
import app.zoocall.core.model.Presence
import app.zoocall.core.model.Visibility
import app.zoocall.ui.LocalCore
import app.zoocall.ui.LocalSnackbar
import app.zoocall.ui.common.DetailScaffold
import app.zoocall.ui.components.Avatar
import app.zoocall.ui.components.presenceColor
import app.zoocall.ui.platform.FirewallHelper
import app.zoocall.ui.platform.LocalPlatformActions
import app.zoocall.ui.platform.LoginItem
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.action_save
import app.zoocall.ui.resources.person_unblock
import app.zoocall.ui.resources.presence_available
import app.zoocall.ui.resources.presence_away
import app.zoocall.ui.resources.presence_busy
import app.zoocall.ui.resources.presence_dnd
import app.zoocall.ui.resources.settings_about
import app.zoocall.ui.resources.settings_addresses
import app.zoocall.ui.resources.settings_allow_calls
import app.zoocall.ui.resources.settings_allow_contacts
import app.zoocall.ui.resources.settings_allow_everyone
import app.zoocall.ui.resources.settings_allow_favorites
import app.zoocall.ui.resources.settings_appearance
import app.zoocall.ui.resources.settings_audio_video
import app.zoocall.ui.resources.settings_blocked
import app.zoocall.ui.resources.settings_blocked_empty
import app.zoocall.ui.resources.settings_dynamic_color
import app.zoocall.ui.resources.settings_firewall
import app.zoocall.ui.resources.settings_firewall_allow
import app.zoocall.ui.resources.settings_firewall_failed
import app.zoocall.ui.resources.settings_firewall_missing
import app.zoocall.ui.resources.settings_firewall_ok
import app.zoocall.ui.resources.settings_license
import app.zoocall.ui.resources.settings_my_fingerprint
import app.zoocall.ui.resources.settings_name
import app.zoocall.ui.resources.settings_name_invalid
import app.zoocall.ui.resources.settings_network
import app.zoocall.ui.resources.settings_no_network
import app.zoocall.ui.resources.settings_privacy
import app.zoocall.ui.resources.settings_profile
import app.zoocall.ui.resources.settings_read_receipts
import app.zoocall.ui.resources.settings_role
import app.zoocall.ui.resources.settings_saved
import app.zoocall.ui.resources.settings_start_at_login
import app.zoocall.ui.resources.settings_start_at_login_body
import app.zoocall.ui.resources.settings_status
import app.zoocall.ui.resources.settings_status_text
import app.zoocall.ui.resources.settings_status_text_placeholder
import app.zoocall.ui.resources.settings_theme
import app.zoocall.ui.resources.settings_theme_dark
import app.zoocall.ui.resources.settings_theme_light
import app.zoocall.ui.resources.settings_theme_system
import app.zoocall.ui.resources.settings_title
import app.zoocall.ui.resources.settings_version
import app.zoocall.ui.resources.settings_visibility
import app.zoocall.ui.resources.settings_visibility_everyone
import app.zoocall.ui.resources.settings_visibility_hidden
import app.zoocall.ui.resources.show_my_code
import app.zoocall.ui.theme.Spacing
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsScreen(onBack: () -> Unit, onShowMyCode: () -> Unit, onOpenNetworkDoctor: () -> Unit) {
    val core = LocalCore.current
    val platform = LocalPlatformActions.current
    val scope = rememberCoroutineScope()
    val settings by core.settings.collectAsStateWithLifecycle()
    val network by core.network.collectAsStateWithLifecycle()
    val port by core.listenPort.collectAsStateWithLifecycle(null)
    val blocked by core.blockedPeople.collectAsStateWithLifecycle(emptyList())
    val snackbar = LocalSnackbar.current
    var name by rememberSaveable(settings.displayName) { mutableStateOf(settings.displayName) }
    var role by rememberSaveable(settings.role) { mutableStateOf(settings.role) }
    var statusText by rememberSaveable(settings.statusText) { mutableStateOf(settings.statusText) }
    val profileChanged = name.trim() != settings.displayName || role.trim() != settings.role

    DetailScaffold(title = stringResource(Res.string.settings_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Header(Res.string.settings_profile)
                Row(Modifier.padding(horizontal = Spacing.l), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name.ifBlank { settings.displayName }, size = 64.dp)
                    Column(Modifier.weight(1f).padding(start = Spacing.l)) {
                        OutlinedTextField(name, { name = it.take(64) }, label = { Text(stringResource(Res.string.settings_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(role, { role = it.take(64) }, label = { Text(stringResource(Res.string.settings_role)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = Spacing.s))
                    }
                }
                if (profileChanged && name.isNotBlank()) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val message = runCatching { core.updateProfile(name, role) }
                                    .fold({ getString(Res.string.settings_saved) }, { getString(Res.string.settings_name_invalid) })
                                snackbar.showSnackbar(message)
                            }
                        },
                        modifier = Modifier.align(Alignment.End).padding(horizontal = Spacing.l),
                    ) { Text(stringResource(Res.string.action_save)) }
                }
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.show_my_code)) },
                    leadingContent = { Icon(Icons.Rounded.QrCode2, null) },
                    modifier = Modifier.clickable(onClick = onShowMyCode),
                )

                Header(Res.string.settings_status)
                RadioGroup(
                    options = listOf(
                        Presence.Available to Res.string.presence_available,
                        Presence.Busy to Res.string.presence_busy,
                        Presence.DoNotDisturb to Res.string.presence_dnd,
                        Presence.Away to Res.string.presence_away,
                    ),
                    selected = settings.presence,
                    onSelect = { scope.launch { core.setPresence(it) } },
                    leading = { p ->
                        Box(Modifier.padding(end = Spacing.s).size(12.dp).clip(CircleShape).background(presenceColor(p, true)))
                    },
                )
                OutlinedTextField(
                    value = statusText,
                    onValueChange = { statusText = it.take(80) },
                    label = { Text(stringResource(Res.string.settings_status_text)) },
                    placeholder = { Text(stringResource(Res.string.settings_status_text_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
                if (statusText.trim() != settings.statusText) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                core.setStatusText(statusText)
                                snackbar.showSnackbar(getString(Res.string.settings_saved))
                            }
                        },
                        modifier = Modifier.align(Alignment.End).padding(horizontal = Spacing.l),
                    ) { Text(stringResource(Res.string.action_save)) }
                }

                HorizontalDivider(Modifier.padding(vertical = Spacing.s))
                Header(Res.string.settings_appearance)
                Label(Res.string.settings_theme)
                RadioGroup(
                    options = listOf(
                        ThemeMode.System to Res.string.settings_theme_system,
                        ThemeMode.Light to Res.string.settings_theme_light,
                        ThemeMode.Dark to Res.string.settings_theme_dark,
                    ),
                    selected = settings.theme,
                    onSelect = { scope.launch { core.setTheme(it) } },
                )
                if (!platform.isDesktop) {
                    SwitchRow(Res.string.settings_dynamic_color, settings.dynamicColor) { scope.launch { core.setDynamicColor(it) } }
                }
                platform.startAtLogin?.let { StartAtLoginRow(it) }
                platform.globalMuteShortcut?.let { shortcut ->
                    SettingSwitch(
                        label = stringResource(Res.string.settings_global_mute),
                        body = stringResource(Res.string.settings_global_mute_body, shortcut),
                        checked = settings.globalMuteHotkey,
                    ) { scope.launch { core.setGlobalMuteHotkey(it) } }
                }
                if (platform.isDesktop) {
                    SettingSwitch(Res.string.settings_desk_intercom, Res.string.settings_desk_intercom_body, settings.deskIntercom) {
                        scope.launch { core.setDeskIntercom(it) }
                    }
                }

                core.mediaEngine.devices?.let { devices ->
                    val activeCall by core.activeCall.collectAsStateWithLifecycle()
                    HorizontalDivider(Modifier.padding(vertical = Spacing.s))
                    Header(Res.string.settings_audio_video)
                    DeviceSettings(devices, inCall = activeCall?.state?.isActive == true)
                    if (core.mediaEngine.supportsStrongNoiseSuppression) {
                        SettingSwitch(Res.string.settings_strong_ns, Res.string.settings_strong_ns_body, settings.strongNoiseSuppression) {
                            scope.launch { core.setStrongNoiseSuppression(it) }
                        }
                    }
                    platform.cameraEffects?.let { effects ->
                        ListItem(
                            headlineContent = { Text(stringResource(Res.string.settings_camera_effects)) },
                            supportingContent = { Text(stringResource(Res.string.settings_camera_effects_body)) },
                            leadingContent = { Icon(Icons.Rounded.AutoAwesome, null) },
                            modifier = Modifier.clickable { effects.open() },
                        )
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = Spacing.s))
                Header(Res.string.settings_sounds)
                SoundSettings(platform.ringtones, isDesktop = platform.isDesktop)

                HorizontalDivider(Modifier.padding(vertical = Spacing.s))
                Header(Res.string.settings_privacy)
                Label(Res.string.settings_visibility)
                RadioGroup(
                    options = listOf(
                        Visibility.Everyone to Res.string.settings_visibility_everyone,
                        Visibility.ContactsOnly to Res.string.settings_visibility_contacts,
                        Visibility.Hidden to Res.string.settings_visibility_hidden,
                    ),
                    selected = settings.visibility,
                    onSelect = { scope.launch { core.setVisibility(it) } },
                )
                Label(Res.string.settings_allow_calls)
                RadioGroup(
                    options = listOf(
                        AllowCallsFrom.Everyone to Res.string.settings_allow_everyone,
                        AllowCallsFrom.Contacts to Res.string.settings_allow_contacts,
                        AllowCallsFrom.Favorites to Res.string.settings_allow_favorites,
                    ),
                    selected = settings.allowCallsFrom,
                    onSelect = { scope.launch { core.setAllowCallsFrom(it) } },
                )
                SwitchRow(Res.string.settings_read_receipts, settings.readReceipts) { scope.launch { core.setReadReceipts(it) } }
                AppLockSetting()
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.settings_my_fingerprint)) },
                    supportingContent = { Text(core.localFingerprint.tag) },
                )
                Label(Res.string.settings_blocked)
                if (blocked.isEmpty()) {
                    Text(
                        stringResource(Res.string.settings_blocked_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                    )
                }
                blocked.forEach { contact ->
                    ListItem(
                        headlineContent = { Text(contact.displayName) },
                        supportingContent = { Text(contact.fingerprint.tag) },
                        leadingContent = { Avatar(contact.displayName, size = 40.dp) },
                        trailingContent = {
                            TextButton(onClick = { scope.launch { core.setBlocked(contact.fingerprint, false) } }) {
                                Text(stringResource(Res.string.person_unblock))
                            }
                        },
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = Spacing.s))
                Header(Res.string.settings_backup)
                BackupSettings()

                HorizontalDivider(Modifier.padding(vertical = Spacing.s))
                Header(Res.string.settings_network)
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.settings_addresses)) },
                    supportingContent = {
                        Text(
                            if (network.localAddresses.isEmpty()) stringResource(Res.string.settings_no_network)
                            else network.localAddresses.joinToString("\n") { a -> port?.let { "$a:$it" } ?: a },
                        )
                    },
                )
                platform.firewall?.let { FirewallRow(it) }
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.doctor_title)) },
                    supportingContent = { Text(stringResource(Res.string.doctor_settings_body)) },
                    leadingContent = { Icon(Icons.Rounded.NetworkCheck, null) },
                    modifier = Modifier.clickable(onClick = onOpenNetworkDoctor),
                )

                HorizontalDivider(Modifier.padding(vertical = Spacing.s))
                Header(Res.string.settings_about)
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.settings_version, AppInfo.version)) },
                    supportingContent = { Text(stringResource(Res.string.settings_license)) },
                )
            }
        }
    }
}

/** Filled in by the platform shell at startup. */
object AppInfo {
    var version: String = "dev"
}

@Composable
private fun StartAtLoginRow(item: LoginItem) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(item.isEnabled()) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = enabled, role = Role.Switch, onValueChange = { wanted ->
                scope.launch { if (item.setEnabled(wanted)) enabled = item.isEnabled() }
            })
            .padding(horizontal = Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.settings_start_at_login), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(Res.string.settings_start_at_login_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = enabled, onCheckedChange = null)
    }
}

@Composable
private fun FirewallRow(firewall: FirewallHelper) {
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var hasRule by remember { mutableStateOf<Boolean?>(null) }
    var working by remember { mutableStateOf(false) }
    LaunchedEffect(firewall) { hasRule = firewall.hasRule() }
    ListItem(
        headlineContent = { Text(stringResource(Res.string.settings_firewall)) },
        supportingContent = {
            when (hasRule) {
                true -> Text(stringResource(Res.string.settings_firewall_ok))
                false -> Text(stringResource(Res.string.settings_firewall_missing), color = MaterialTheme.colorScheme.error)
                null -> Unit
            }
        },
        leadingContent = { Icon(if (hasRule == true) Icons.Rounded.CheckCircle else Icons.Rounded.Shield, null) },
        trailingContent = {
            when {
                working -> CircularProgressIndicator(Modifier.size(24.dp))
                hasRule == false -> TextButton(onClick = {
                    working = true
                    scope.launch {
                        val ok = firewall.addRule()
                        working = false
                        hasRule = ok
                        if (!ok) snackbar.showSnackbar(getString(Res.string.settings_firewall_failed))
                    }
                }) { Text(stringResource(Res.string.settings_firewall_allow)) }
            }
        },
    )
}

@Composable
private fun Header(text: StringResource) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m).semantics { heading() },
    )
}

@Composable
private fun Label(text: StringResource) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
    )
}

@Composable
internal fun <T> RadioGroup(
    options: List<Pair<T, StringResource>>,
    selected: T,
    onSelect: (T) -> Unit,
    leading: (@Composable (T) -> Unit)? = null,
) {
    Column(Modifier.selectableGroup()) {
        options.forEach { (value, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = value == selected, onClick = { onSelect(value) }, role = Role.RadioButton)
                    .padding(horizontal = Spacing.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == selected, onClick = null)
                Row(Modifier.padding(start = Spacing.l), verticalAlignment = Alignment.CenterVertically) {
                    leading?.invoke(value)
                    Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: StringResource, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
            .padding(horizontal = Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
