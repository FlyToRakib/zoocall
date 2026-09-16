package app.zoocall.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zoocall.core.app.AppSettings
import app.zoocall.ui.LocalCore
import app.zoocall.ui.platform.RingtoneChooser
import app.zoocall.ui.platform.LocalPlatformActions
import androidx.compose.material.icons.rounded.ClosedCaption
import app.zoocall.ui.resources.call_live_captions
import app.zoocall.ui.resources.settings_live_captions_body
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.sounds_call_sounds
import app.zoocall.ui.resources.sounds_call_sounds_body
import app.zoocall.ui.resources.sounds_flash
import app.zoocall.ui.resources.sounds_flash_body
import app.zoocall.ui.resources.sounds_ringtone
import app.zoocall.ui.resources.sounds_ringtone_bright
import app.zoocall.ui.resources.sounds_ringtone_change
import app.zoocall.ui.resources.sounds_ringtone_classic
import app.zoocall.ui.resources.sounds_ringtone_default
import app.zoocall.ui.resources.sounds_ringtone_default_named
import app.zoocall.ui.resources.sounds_ringtone_gentle
import app.zoocall.ui.resources.sounds_ringtone_silent
import app.zoocall.ui.resources.sounds_vibrate
import app.zoocall.ui.theme.Spacing
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Settings → Sounds & alerts: ringtone, vibration, call sounds and the flash alert. */
@Composable
fun SoundSettings(chooser: RingtoneChooser?, isDesktop: Boolean) {
    val core = LocalCore.current
    val scope = rememberCoroutineScope()
    val settings by core.settings.collectAsStateWithLifecycle()

    if (chooser != null) RingtoneRow(chooser, settings.ringtone) { scope.launch { core.setRingtone(it) } }
    if (!isDesktop) {
        SettingSwitch(Res.string.sounds_vibrate, null, settings.vibrateOnRing) { scope.launch { core.setVibrateOnRing(it) } }
    }
    SettingSwitch(Res.string.sounds_call_sounds, Res.string.sounds_call_sounds_body, settings.callSounds) { scope.launch { core.setCallSounds(it) } }
    SettingSwitch(Res.string.sounds_flash, Res.string.sounds_flash_body, settings.flashAlert) { scope.launch { core.setFlashAlert(it) } }
    LocalPlatformActions.current.liveCaptions?.let { captions ->
        ListItem(
            headlineContent = { Text(stringResource(Res.string.call_live_captions)) },
            supportingContent = { Text(stringResource(Res.string.settings_live_captions_body)) },
            leadingContent = { Icon(Icons.Rounded.ClosedCaption, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { captions.open() },
        )
    }
}

@Composable
private fun RingtoneRow(chooser: RingtoneChooser, current: String, onChange: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val title = stringResource(Res.string.sounds_ringtone)
    val label = ringtoneLabel(chooser, current)
    val changeLabel = stringResource(Res.string.sounds_ringtone_change)
    DisposableEffect(chooser) { onDispose { chooser.stopPreview() } }

    Box {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(label) },
            leadingContent = { Icon(Icons.Rounded.MusicNote, null) },
            trailingContent = { if (chooser.builtIn.isNotEmpty()) Icon(Icons.Rounded.ArrowDropDown, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clickable(onClickLabel = changeLabel) {
                    if (chooser.builtIn.isNotEmpty()) {
                        open = true
                    } else {
                        scope.launch { chooser.pickFromSystem(current)?.let(onChange) }
                    }
                }
                .semantics(mergeDescendants = true) { contentDescription = "$title: $label" },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false; chooser.stopPreview() }) {
            val selected = current.ifEmpty { chooser.builtIn.first() }
            (chooser.builtIn + AppSettings.SILENT_RINGTONE).forEach { id ->
                DropdownMenuItem(
                    text = { Text(ringtoneLabel(chooser, id)) },
                    leadingIcon = { RadioButton(selected = id == selected, onClick = null) },
                    // Choosing plays a short preview; the menu stays open to compare tones.
                    onClick = {
                        onChange(if (id == chooser.builtIn.first()) "" else id)
                        if (id == AppSettings.SILENT_RINGTONE) chooser.stopPreview() else chooser.preview(id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ringtoneLabel(chooser: RingtoneChooser, id: String): String {
    builtInLabel(id)?.let { return stringResource(it) }
    return when (id) {
        AppSettings.SILENT_RINGTONE -> stringResource(Res.string.sounds_ringtone_silent)
        "" -> chooser.builtIn.firstOrNull()?.let { builtInLabel(it) }?.let { stringResource(it) }
            ?: chooser.systemLabel("")?.let { stringResource(Res.string.sounds_ringtone_default_named, it) }
            ?: stringResource(Res.string.sounds_ringtone_default)
        else -> chooser.systemLabel(id) ?: stringResource(Res.string.sounds_ringtone_default)
    }
}

private fun builtInLabel(id: String): StringResource? = when (id) {
    "classic" -> Res.string.sounds_ringtone_classic
    "gentle" -> Res.string.sounds_ringtone_gentle
    "bright" -> Res.string.sounds_ringtone_bright
    else -> null
}

@Composable
internal fun SettingSwitch(label: StringResource, body: StringResource?, checked: Boolean, onChange: (Boolean) -> Unit) =
    SettingSwitch(stringResource(label), body?.let { stringResource(it) }, checked, onChange)

@Composable
internal fun SettingSwitch(label: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Spacing.m)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
