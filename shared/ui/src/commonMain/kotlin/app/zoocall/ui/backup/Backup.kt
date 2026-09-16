package app.zoocall.ui.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.zoocall.core.app.PickedFile
import app.zoocall.core.app.RestoreResult
import app.zoocall.ui.LocalCore
import app.zoocall.ui.LocalSnackbar
import app.zoocall.ui.platform.LocalPlatformActions
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.action_cancel
import app.zoocall.ui.resources.backup_damaged
import app.zoocall.ui.resources.backup_export
import app.zoocall.ui.resources.backup_export_action
import app.zoocall.ui.resources.backup_export_body
import app.zoocall.ui.resources.backup_export_failed
import app.zoocall.ui.resources.backup_mismatch
import app.zoocall.ui.resources.backup_not_a_backup
import app.zoocall.ui.resources.backup_passphrase
import app.zoocall.ui.resources.backup_passphrase_again
import app.zoocall.ui.resources.backup_passphrase_hint
import app.zoocall.ui.resources.backup_restore
import app.zoocall.ui.resources.backup_restore_action
import app.zoocall.ui.resources.backup_restore_body
import app.zoocall.ui.resources.backup_restored
import app.zoocall.ui.resources.backup_saved
import app.zoocall.ui.resources.backup_too_short
import app.zoocall.ui.resources.backup_unreadable
import app.zoocall.ui.resources.backup_working
import app.zoocall.ui.resources.backup_wrong_passphrase
import app.zoocall.ui.theme.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

private const val MIN_PASSPHRASE = 8
private const val MAX_PASSPHRASE = 256
private const val BACKUP_MIME = "application/octet-stream"

/** "zoocall-backup-2026-09-14.zcbackup" */
private fun backupFileName() = "zoocall-backup-${Clock.System.now().toString().take(10)}.zcbackup"

/** Export and restore rows in Settings. */
@Composable
internal fun BackupSettings() {
    val platform = LocalPlatformActions.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<PickedFile?>(null) }

    ListItem(
        headlineContent = { Text(stringResource(Res.string.backup_export)) },
        supportingContent = { Text(stringResource(Res.string.backup_export_body)) },
        leadingContent = { Icon(Icons.Rounded.Backup, null) },
        modifier = Modifier.clickable { exporting = true },
    )
    if (platform.canPickFiles) {
        ListItem(
            headlineContent = { Text(stringResource(Res.string.backup_restore)) },
            supportingContent = { Text(stringResource(Res.string.backup_restore_body)) },
            leadingContent = { Icon(Icons.Rounded.Restore, null) },
            modifier = Modifier.clickable { scope.launch { restoring = platform.pickFile() } },
        )
    }
    if (exporting) ExportBackupDialog(scope, onDismiss = { exporting = false })
    restoring?.let { file -> RestoreBackupDialog(file, scope, onDismiss = { restoring = null }) }
}

/** Onboarding: bring back the profile, contacts and chats from another device instead of starting fresh. */
@Composable
fun RestoreFromBackupButton(modifier: Modifier = Modifier) {
    val platform = LocalPlatformActions.current
    if (!platform.canPickFiles) return
    val scope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf<PickedFile?>(null) }
    TextButton(onClick = { scope.launch { restoring = platform.pickFile() } }, modifier = modifier) {
        Text(stringResource(Res.string.backup_restore))
    }
    restoring?.let { file -> RestoreBackupDialog(file, scope, onDismiss = { restoring = null }) }
}

/** [scope] outlives the dialog, so the result message still shows after it closes. */
@Composable
private fun ExportBackupDialog(scope: CoroutineScope, onDismiss: () -> Unit) {
    val core = LocalCore.current
    val platform = LocalPlatformActions.current
    val snackbar = LocalSnackbar.current
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<StringResource?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.backup_export)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(stringResource(Res.string.backup_passphrase_hint), style = MaterialTheme.typography.bodyMedium)
                PassphraseField(first, Res.string.backup_passphrase, enabled = !busy) {
                    first = it
                    problem = null
                }
                PassphraseField(second, Res.string.backup_passphrase_again, enabled = !busy) {
                    second = it
                    problem = null
                }
                problem?.let { ProblemText(it) }
                if (busy) Working()
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    problem = when {
                        first.length < MIN_PASSPHRASE -> Res.string.backup_too_short
                        first != second -> Res.string.backup_mismatch
                        else -> null
                    }
                    if (problem == null) {
                        busy = true
                        scope.launch {
                            val path = core.exportBackup(first)
                            val saved = path != null && platform.saveFile(path, backupFileName(), BACKUP_MIME)
                            onDismiss()
                            when {
                                path == null -> snackbar.showSnackbar(getString(Res.string.backup_export_failed))
                                saved -> snackbar.showSnackbar(getString(Res.string.backup_saved))
                            }
                        }
                    }
                },
            ) { Text(stringResource(Res.string.backup_export_action)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

@Composable
private fun RestoreBackupDialog(file: PickedFile, scope: CoroutineScope, onDismiss: () -> Unit) {
    val core = LocalCore.current
    val snackbar = LocalSnackbar.current
    var passphrase by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<StringResource?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.backup_restore)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(file.name, style = MaterialTheme.typography.bodyMedium)
                PassphraseField(passphrase, Res.string.backup_passphrase, enabled = !busy) {
                    passphrase = it
                    problem = null
                }
                problem?.let { ProblemText(it) }
                if (busy) Working()
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && passphrase.isNotEmpty(),
                onClick = {
                    busy = true
                    scope.launch {
                        when (val result = core.restoreBackup(file, passphrase)) {
                            is RestoreResult.Restored -> {
                                onDismiss()
                                snackbar.showSnackbar(getString(Res.string.backup_restored, result.contacts, result.messages))
                            }
                            RestoreResult.WrongPassphrase -> problem = Res.string.backup_wrong_passphrase
                            RestoreResult.NotABackup -> problem = Res.string.backup_not_a_backup
                            RestoreResult.Damaged -> problem = Res.string.backup_damaged
                            RestoreResult.Unreadable -> problem = Res.string.backup_unreadable
                        }
                        busy = false
                    }
                },
            ) { Text(stringResource(Res.string.backup_restore_action)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

@Composable
private fun PassphraseField(value: String, label: StringResource, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(MAX_PASSPHRASE)) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProblemText(problem: StringResource) {
    Text(stringResource(problem), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Working() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(Res.string.backup_working), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Spacing.m))
    }
}
