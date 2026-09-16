package app.zoocall.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import app.zoocall.core.app.CallStartResult
import app.zoocall.core.model.CallKind
import app.zoocall.ui.LocalCore
import app.zoocall.ui.LocalSnackbar
import app.zoocall.ui.platform.LocalPlatformActions
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.call_already_in_call
import app.zoocall.ui.resources.call_blocked
import app.zoocall.ui.resources.call_end_unreachable
import app.zoocall.ui.resources.call_key_mismatch
import app.zoocall.ui.resources.call_mic_permission
import app.zoocall.ui.resources.intercom_not_supported
import app.zoocall.ui.resources.ptt_not_supported
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

interface CallLauncher {
    fun call(personId: String, displayName: String, kind: CallKind)

    /** Opens a push-to-talk session. */
    fun pushToTalk(personId: String, displayName: String)

    /** Opens a desk intercom line. */
    fun intercom(personId: String, displayName: String)
}

/** Asks for permissions just in time, starts the call and explains failures in plain language. */
@Composable
fun rememberCallLauncher(): CallLauncher {
    val core = LocalCore.current
    val platform = LocalPlatformActions.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    return remember(core, platform) {
        object : CallLauncher {
            override fun call(personId: String, displayName: String, kind: CallKind) =
                start(displayName, video = kind == CallKind.Video) { core.startCall(personId, kind) }

            override fun pushToTalk(personId: String, displayName: String) =
                start(displayName, video = false) { core.startPushToTalk(personId) }

            override fun intercom(personId: String, displayName: String) =
                start(displayName, video = false, notSupported = Res.string.intercom_not_supported) { core.startIntercom(personId) }

            private fun start(
                name: String,
                video: Boolean,
                notSupported: StringResource = Res.string.ptt_not_supported,
                begin: suspend () -> CallStartResult,
            ) {
                scope.launch {
                    if (!platform.ensureCallPermissions(video)) {
                        snackbar.showSnackbar(getString(Res.string.call_mic_permission))
                        return@launch
                    }
                    val message = when (begin()) {
                        CallStartResult.Started -> null
                        CallStartResult.AlreadyInCall -> getString(Res.string.call_already_in_call)
                        CallStartResult.Unreachable -> getString(Res.string.call_end_unreachable, name)
                        CallStartResult.KeyMismatch -> getString(Res.string.call_key_mismatch, name)
                        CallStartResult.Blocked -> getString(Res.string.call_blocked, name)
                        CallStartResult.NotSupported -> getString(notSupported, name)
                    }
                    message?.let { snackbar.showSnackbar(it) }
                }
            }
        }
    }
}
