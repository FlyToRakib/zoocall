package app.zoocall.android.call

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import app.zoocall.android.AndroidPlatformActions
import app.zoocall.android.ZoocallApplication
import app.zoocall.core.call.CallPhase
import app.zoocall.core.model.CallKind
import app.zoocall.ui.LocalCore
import app.zoocall.ui.LocalSnackbar
import app.zoocall.ui.call.CallScreen
import app.zoocall.ui.platform.LocalPlatformActions
import kotlinx.coroutines.launch

/**
 * Hosts the shared call screen; shows over the lock screen and turns the screen on for incoming calls.
 * During a connected video call, leaving the app shrinks it to picture-in-picture (edge case C20).
 */
class CallActivity : ComponentActivity() {
    private val app get() = application as ZoocallApplication
    private lateinit var platformActions: AndroidPlatformActions
    private val inPictureInPicture = mutableStateOf(false)

    private val supportsPip: Boolean by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The call screen is always dark, whatever the system theme: light system-bar icons.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        platformActions = AndroidPlatformActions(this, app.callController)
        handleIntent(intent)

        setContent {
            val call by app.core.activeCall.collectAsState()
            val pip by inPictureInPicture
            LaunchedEffect(call == null) {
                if (call == null) finishAndRemoveTask()
            }
            val pipEligible = pipEligible()
            LaunchedEffect(pipEligible) { updatePictureInPictureParams(pipEligible) }
            // Back never ends or silently drops a call: it shrinks a video call to PiP or steps away.
            BackHandler { if (pipEligible && enterPictureInPicture()) Unit else moveTaskToBack(true) }
            CompositionLocalProvider(
                LocalCore provides app.core,
                LocalPlatformActions provides platformActions,
                LocalSnackbar provides remember { SnackbarHostState() },
            ) {
                CallScreen(pictureInPicture = pip)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Before Android 12 there's no auto-enter: do it when the user leaves (Home, recents). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && pipEligible()) enterPictureInPicture()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture.value = isInPictureInPictureMode
    }

    private fun pipEligible(): Boolean {
        val state = app.core.activeCall.value?.state ?: return false
        return supportsPip && state.kind == CallKind.Video && state.phase in setOf(CallPhase.Connected, CallPhase.Reconnecting)
    }

    private fun pictureInPictureParams(autoEnter: Boolean): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .setAspectRatio(Rational(9, 16))
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(autoEnter) }
            .build()

    private fun updatePictureInPictureParams(eligible: Boolean) {
        if (!supportsPip) return
        runCatching { setPictureInPictureParams(pictureInPictureParams(autoEnter = eligible)) }
    }

    private fun enterPictureInPicture(): Boolean =
        runCatching { enterPictureInPictureMode(pictureInPictureParams(autoEnter = true)) }.getOrDefault(false)

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != ACTION_ACCEPT) return
        lifecycleScope.launch {
            val call = app.core.activeCall.value ?: return@launch
            if (call.state.phase != CallPhase.IncomingRinging) return@launch
            val video = intent.getBooleanExtra(EXTRA_VIDEO, call.state.kind == CallKind.Video)
            if (platformActions.ensureCallPermissions(video)) {
                app.core.acceptCall(if (video) CallKind.Video else CallKind.Audio)
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "app.zoocall.action.ACCEPT"
        const val EXTRA_VIDEO = "app.zoocall.extra.VIDEO"
    }
}
