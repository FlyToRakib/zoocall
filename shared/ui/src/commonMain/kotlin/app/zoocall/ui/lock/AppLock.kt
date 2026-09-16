package app.zoocall.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zoocall.core.app.ZoocallCore
import app.zoocall.ui.LocalCore
import app.zoocall.ui.LocalSnackbar
import app.zoocall.ui.platform.DeviceUnlock
import app.zoocall.ui.platform.LocalPlatformActions
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.action_cancel
import app.zoocall.ui.resources.lock_needs_screen_lock
import app.zoocall.ui.resources.lock_passcode
import app.zoocall.ui.resources.lock_prompt_subtitle
import app.zoocall.ui.resources.lock_save
import app.zoocall.ui.resources.lock_set_confirm
import app.zoocall.ui.resources.lock_set_mismatch
import app.zoocall.ui.resources.lock_set_title
import app.zoocall.ui.resources.lock_set_too_short
import app.zoocall.ui.resources.lock_title
import app.zoocall.ui.resources.lock_turn_off
import app.zoocall.ui.resources.lock_turn_off_title
import app.zoocall.ui.resources.lock_unlock
import app.zoocall.ui.resources.lock_wait
import app.zoocall.ui.resources.lock_wrong
import app.zoocall.ui.resources.settings_app_lock
import app.zoocall.ui.resources.settings_app_lock_body_device
import app.zoocall.ui.resources.settings_app_lock_body_passcode
import app.zoocall.ui.settings.SettingSwitch
import app.zoocall.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** How long the app may be out of sight before it locks again. */
private const val LOCK_AFTER_MS = 60_000L
private const val MAX_TRIES = 5
private const val COOLDOWN_S = 30
private const val MIN_PASSCODE = 4
private const val MAX_PASSCODE = 64

/**
 * Lives as long as the process, not in saved state: Android restores saved state after the process
 * is killed, which must not bring the app back unlocked.
 */
private object LockSession {
    var unlocked by mutableStateOf(false)
    var hiddenAtMs: Long? = null
}

private fun nowMs() = Clock.System.now().toEpochMilliseconds()

/** The user just proved it's them (e.g. while turning app lock on): don't ask again right away. */
internal fun markAppUnlocked() {
    LockSession.unlocked = true
}

/**
 * Keeps chats, contacts and recents behind the lock screen (docs/01 §9, docs/04 "device thief"):
 * when Zoocall starts and after it was out of sight for a minute. Calls still ring and can be
 * answered, since the call screen lives in its own activity or window.
 */
@Composable
internal fun AppLockGate(enabled: Boolean, visible: Boolean, content: @Composable () -> Unit) {
    LaunchedEffect(enabled, visible) {
        when {
            !enabled -> LockSession.hiddenAtMs = null
            !visible -> if (LockSession.hiddenAtMs == null) LockSession.hiddenAtMs = nowMs()
            else -> {
                val since = LockSession.hiddenAtMs
                LockSession.hiddenAtMs = null
                if (since != null && nowMs() - since >= LOCK_AFTER_MS) LockSession.unlocked = false
            }
        }
    }
    // Nothing behind the lock is composed, so screen readers and keyboard focus can't reach it.
    if (enabled && !LockSession.unlocked) LockScreen() else content()
}

@Composable
private fun LockScreen() {
    val core = LocalCore.current
    val platform = LocalPlatformActions.current
    val settings by core.settings.collectAsStateWithLifecycle()
    val device = platform.deviceUnlock
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(Spacing.xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(Res.string.lock_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.l).semantics { heading() },
            )
            when {
                device != null -> DeviceUnlockAction(device)
                settings.appLockHasPasscode -> PasscodeUnlock(core)
                // No passcode stored: there's nothing to check against, so don't lock the owner out.
                else -> LaunchedEffect(Unit) { markAppUnlocked() }
            }
        }
    }
}

@Composable
private fun DeviceUnlockAction(device: DeviceUnlock) {
    val scope = rememberCoroutineScope()
    val title = stringResource(Res.string.lock_title)
    val subtitle = stringResource(Res.string.lock_prompt_subtitle)
    fun unlock() {
        scope.launch {
            // The screen lock was removed since: app lock can't be checked, so it doesn't keep the owner out.
            if (!device.isAvailable() || device.unlock(title, subtitle)) markAppUnlocked()
        }
    }
    LaunchedEffect(Unit) { unlock() }
    Button(onClick = { unlock() }, modifier = Modifier.padding(top = Spacing.xl)) { Text(stringResource(Res.string.lock_unlock)) }
}

@Composable
private fun PasscodeUnlock(core: ZoocallCore) {
    val scope = rememberCoroutineScope()
    var passcode by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failures by remember { mutableStateOf(0) }
    var waitS by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(waitS) {
        if (waitS > 0) {
            delay(1_000)
            waitS -= 1
        }
    }

    fun submit() {
        if (busy || waitS > 0 || passcode.isEmpty()) return
        busy = true
        scope.launch {
            val ok = core.verifyAppLockPasscode(passcode)
            busy = false
            if (ok) {
                markAppUnlocked()
            } else {
                passcode = ""
                wrong = true
                failures += 1
                if (failures >= MAX_TRIES) {
                    failures = 0
                    waitS = COOLDOWN_S
                }
            }
        }
    }

    val message = when {
        waitS > 0 -> stringResource(Res.string.lock_wait, waitS)
        wrong -> stringResource(Res.string.lock_wrong)
        else -> null
    }
    OutlinedTextField(
        value = passcode,
        onValueChange = {
            passcode = it.take(MAX_PASSCODE)
            wrong = false
        },
        label = { Text(stringResource(Res.string.lock_passcode)) },
        singleLine = true,
        enabled = waitS == 0,
        isError = message != null,
        supportingText = message?.let { text -> { Text(text) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.padding(top = Spacing.xl).widthIn(max = 360.dp).fillMaxWidth().focusRequester(focus),
    )
    Button(
        onClick = { submit() },
        enabled = !busy && waitS == 0 && passcode.isNotEmpty(),
        modifier = Modifier.padding(top = Spacing.m),
    ) { Text(stringResource(Res.string.lock_unlock)) }
}

/** The "App lock" row in Settings › Privacy. */
@Composable
internal fun AppLockSetting() {
    val core = LocalCore.current
    val platform = LocalPlatformActions.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val settings by core.settings.collectAsStateWithLifecycle()
    val device = platform.deviceUnlock
    var choosingPasscode by remember { mutableStateOf(false) }
    var turningOff by remember { mutableStateOf(false) }
    val title = stringResource(Res.string.settings_app_lock)
    val confirm = stringResource(Res.string.lock_prompt_subtitle)
    val needsScreenLock = stringResource(Res.string.lock_needs_screen_lock)

    SettingSwitch(
        label = title,
        body = stringResource(if (device != null) Res.string.settings_app_lock_body_device else Res.string.settings_app_lock_body_passcode),
        checked = settings.appLock,
    ) { enable ->
        when {
            device == null -> if (enable) choosingPasscode = true else turningOff = true
            enable && !device.isAvailable() -> scope.launch { snackbar.showSnackbar(needsScreenLock) }
            // Changing it either way needs the owner, not just someone holding the unlocked phone.
            else -> scope.launch {
                if (!device.isAvailable() || device.unlock(title, confirm)) {
                    core.setAppLock(enable)
                    if (enable) markAppUnlocked()
                }
            }
        }
    }

    if (choosingPasscode) {
        ChoosePasscodeDialog(onDismiss = { choosingPasscode = false }) { passcode ->
            scope.launch {
                core.enableAppLockPasscode(passcode)
                markAppUnlocked()
                choosingPasscode = false
            }
        }
    }
    if (turningOff) TurnOffDialog(core, onDismiss = { turningOff = false })
}

@Composable
private fun ChoosePasscodeDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<StringResource?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.lock_set_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                PasscodeField(first, Res.string.lock_passcode) {
                    first = it
                    problem = null
                }
                PasscodeField(second, Res.string.lock_set_confirm) {
                    second = it
                    problem = null
                }
                problem?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                problem = when {
                    first.length < MIN_PASSCODE -> Res.string.lock_set_too_short
                    first != second -> Res.string.lock_set_mismatch
                    else -> null
                }
                if (problem == null) onSave(first)
            }) { Text(stringResource(Res.string.lock_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

@Composable
private fun TurnOffDialog(core: ZoocallCore, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var passcode by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.lock_turn_off_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                PasscodeField(passcode, Res.string.lock_passcode) {
                    passcode = it
                    wrong = false
                }
                if (wrong) Text(stringResource(Res.string.lock_wrong), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    if (core.verifyAppLockPasscode(passcode)) {
                        core.disableAppLock()
                        onDismiss()
                    } else {
                        wrong = true
                    }
                }
            }) { Text(stringResource(Res.string.lock_turn_off)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

@Composable
private fun PasscodeField(value: String, label: StringResource, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(MAX_PASSCODE)) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}
