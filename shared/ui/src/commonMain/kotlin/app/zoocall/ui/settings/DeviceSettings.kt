package app.zoocall.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zoocall.media.DeviceKind
import app.zoocall.media.DeviceNotice
import app.zoocall.media.MediaDeviceInfo
import app.zoocall.media.MediaDeviceManager
import app.zoocall.media.VideoTrackHandle
import app.zoocall.media.VideoView
import app.zoocall.ui.LocalSnackbar
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.device_camera
import app.zoocall.ui.resources.device_camera_busy
import app.zoocall.ui.resources.device_change
import app.zoocall.ui.resources.device_mic_busy
import app.zoocall.ui.resources.device_mic_level
import app.zoocall.ui.resources.device_microphone
import app.zoocall.ui.resources.device_none_found
import app.zoocall.ui.resources.device_notice_restored
import app.zoocall.ui.resources.device_notice_unavailable
import app.zoocall.ui.resources.device_preview_camera
import app.zoocall.ui.resources.device_speaker
import app.zoocall.ui.resources.device_speaker_busy
import app.zoocall.ui.resources.device_stop_preview
import app.zoocall.ui.resources.device_stop_test
import app.zoocall.ui.resources.device_system_default
import app.zoocall.ui.resources.device_system_default_named
import app.zoocall.ui.resources.device_test_mic
import app.zoocall.ui.resources.device_test_speaker
import app.zoocall.ui.resources.device_tests_in_call
import app.zoocall.ui.resources.device_unavailable
import app.zoocall.ui.theme.Spacing
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Microphone, speaker and camera pickers with a live microphone meter, a speaker test and a camera
 * preview. Used in Settings and (without tests) in the call's device dialog.
 */
@Composable
fun DeviceSettings(
    manager: MediaDeviceManager,
    inCall: Boolean,
    showTests: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val selection by manager.selection.collectAsState()
    val unavailable by manager.unavailable.collectAsState()
    LaunchedEffect(manager) { manager.refresh() }

    Column(modifier) {
        DevicePicker(manager, DeviceKind.Microphone, selection[DeviceKind.Microphone], DeviceKind.Microphone in unavailable)
        if (showTests) MicrophoneTest(manager, inCall)
        DevicePicker(manager, DeviceKind.Speaker, selection[DeviceKind.Speaker], DeviceKind.Speaker in unavailable)
        if (showTests) SpeakerTest(manager, inCall)
        DevicePicker(manager, DeviceKind.Camera, selection[DeviceKind.Camera], DeviceKind.Camera in unavailable)
        if (showTests) CameraPreview(manager, inCall, selection[DeviceKind.Camera])
        if (showTests && inCall) {
            Text(
                stringResource(Res.string.device_tests_in_call),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
            )
        }
    }
}

private fun DeviceKind.icon(): ImageVector = when (this) {
    DeviceKind.Microphone -> Icons.Rounded.Mic
    DeviceKind.Speaker -> Icons.AutoMirrored.Rounded.VolumeUp
    DeviceKind.Camera -> Icons.Rounded.Videocam
}

private fun DeviceKind.label(): StringResource = when (this) {
    DeviceKind.Microphone -> Res.string.device_microphone
    DeviceKind.Speaker -> Res.string.device_speaker
    DeviceKind.Camera -> Res.string.device_camera
}

@Composable
private fun DevicePicker(manager: MediaDeviceManager, kind: DeviceKind, chosen: MediaDeviceInfo?, missing: Boolean) {
    val devices by manager.devices(kind).collectAsState()
    var open by remember { mutableStateOf(false) }
    val defaultName = remember(devices) { manager.systemDefaultName(kind) }
    val defaultLabel = defaultName?.let { stringResource(Res.string.device_system_default_named, it) }
        ?: stringResource(Res.string.device_system_default)
    val label = stringResource(kind.label())
    val current = chosen?.name ?: defaultLabel
    val changeLabel = stringResource(Res.string.device_change, label)

    Box {
        ListItem(
            headlineContent = { Text(label) },
            supportingContent = {
                Column {
                    Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when {
                        missing -> Text(
                            stringResource(Res.string.device_unavailable, chosen?.name.orEmpty()),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        devices.isEmpty() -> Text(
                            stringResource(Res.string.device_none_found),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            leadingContent = { Icon(kind.icon(), contentDescription = null) },
            trailingContent = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clickable(onClickLabel = changeLabel) {
                    manager.refresh()
                    open = true
                }
                .semantics(mergeDescendants = true) { contentDescription = "$label: $current" },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DeviceOption(defaultLabel, selected = chosen == null) {
                open = false
                manager.select(kind, null)
            }
            val chosenPresent = chosen != null && devices.any { it.id == chosen.id }
            devices.forEach { device ->
                val selected = chosen != null && (device.id == chosen.id || (!chosenPresent && device.name == chosen.name))
                DeviceOption(device.name, selected) {
                    open = false
                    manager.select(kind, device)
                }
            }
        }
    }
}

@Composable
private fun DeviceOption(text: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            if (selected) Icon(Icons.Rounded.Check, contentDescription = null) else Spacer(Modifier.size(24.dp))
        },
        onClick = onClick,
        modifier = Modifier.widthIn(min = 280.dp, max = 480.dp).semantics { if (selected) contentDescription = "$text ✓" },
    )
}

@Composable
private fun MicrophoneTest(manager: MediaDeviceManager, inCall: Boolean) {
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    val level by manager.microphoneLevel.collectAsState()
    val levelLabel = stringResource(Res.string.device_mic_level)

    DisposableEffect(manager) { onDispose { manager.stopMicrophoneTest() } }
    LaunchedEffect(inCall) {
        if (inCall && testing) {
            manager.stopMicrophoneTest()
            testing = false
        }
    }

    TestRow {
        OutlinedButton(
            enabled = !inCall,
            onClick = {
                if (testing) {
                    manager.stopMicrophoneTest()
                    testing = false
                } else {
                    testing = manager.startMicrophoneTest()
                    if (!testing) scope.launch { snackbar.showSnackbar(getString(Res.string.device_mic_busy)) }
                }
            },
        ) { Text(stringResource(if (testing) Res.string.device_stop_test else Res.string.device_test_mic)) }
        if (testing) {
            LinearProgressIndicator(
                progress = { level },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Spacing.l)
                    .height(8.dp)
                    .clip(MaterialTheme.shapes.small)
                    .semantics {
                        contentDescription = levelLabel
                        progressBarRangeInfo = ProgressBarRangeInfo(level, 0f..1f)
                    },
            )
        }
    }
}

@Composable
private fun SpeakerTest(manager: MediaDeviceManager, inCall: Boolean) {
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    TestRow {
        OutlinedButton(
            enabled = !inCall,
            onClick = {
                if (!manager.playTestSound()) scope.launch { snackbar.showSnackbar(getString(Res.string.device_speaker_busy)) }
            },
        ) { Text(stringResource(Res.string.device_test_speaker)) }
    }
}

@Composable
private fun CameraPreview(manager: MediaDeviceManager, inCall: Boolean, chosen: MediaDeviceInfo?) {
    val snackbar = LocalSnackbar.current
    var previewing by remember { mutableStateOf(false) }
    var handle by remember { mutableStateOf<VideoTrackHandle?>(null) }

    DisposableEffect(manager) { onDispose { manager.stopCameraPreview() } }
    // (Re)open when previewing starts or the chosen camera changes; stop when a call takes the camera.
    LaunchedEffect(previewing, chosen, inCall) {
        if (inCall && previewing) {
            manager.stopCameraPreview()
            previewing = false
        }
        handle = if (previewing) manager.startCameraPreview() else null
        if (previewing && handle == null) {
            previewing = false
            snackbar.showSnackbar(getString(Res.string.device_camera_busy))
        }
    }

    Column {
        TestRow {
            OutlinedButton(
                enabled = !inCall && manager.devices(DeviceKind.Camera).collectAsState().value.isNotEmpty(),
                onClick = {
                    if (previewing) manager.stopCameraPreview()
                    previewing = !previewing
                },
            ) { Text(stringResource(if (previewing) Res.string.device_stop_preview else Res.string.device_preview_camera)) }
        }
        handle?.let { track ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = Color.Black,
                modifier = Modifier
                    .padding(start = 72.dp, end = Spacing.l, bottom = Spacing.m)
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            ) {
                VideoView(track, Modifier.fillMaxWidth().aspectRatio(16f / 9f), mirror = true, fill = false)
            }
        }
    }
}

@Composable
private fun TestRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 72.dp, end = Spacing.l, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        content = content,
    )
}

/** Shows a snackbar when a chosen device is unplugged (and Zoocall falls back) or comes back. */
@Composable
fun DeviceNoticeEffect(manager: MediaDeviceManager?) {
    manager ?: return
    val snackbar = LocalSnackbar.current
    LaunchedEffect(manager) {
        manager.notices.collect { notice ->
            val message = when (notice) {
                is DeviceNotice.Unavailable -> getString(Res.string.device_notice_unavailable, notice.deviceName)
                is DeviceNotice.Restored -> getString(Res.string.device_notice_restored, notice.deviceName)
            }
            snackbar.showSnackbar(message)
        }
    }
}
