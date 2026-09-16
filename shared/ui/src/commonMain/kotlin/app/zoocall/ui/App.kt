package app.zoocall.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import app.zoocall.core.app.CoreStatus
import app.zoocall.core.app.ZoocallCore
import app.zoocall.ui.call.CallScreen
import app.zoocall.ui.home.HomeScaffold
import app.zoocall.ui.knock.KnockHost
import app.zoocall.ui.lock.AppLockGate
import app.zoocall.ui.onboarding.OnboardingScreen
import app.zoocall.ui.settings.DeviceNoticeEffect
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.loading
import app.zoocall.ui.resources.startup_failed_body
import app.zoocall.ui.resources.startup_failed_title
import app.zoocall.ui.theme.Spacing
import app.zoocall.ui.theme.ZoocallTheme
import org.jetbrains.compose.resources.stringResource

val LocalCore = staticCompositionLocalOf<ZoocallCore> { error("ZoocallCore not provided") }
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("SnackbarHostState not provided") }

/**
 * Root of the shared UI for Android and Desktop.
 * @param showCallOverlay false when a dedicated call window/activity hosts [CallScreen].
 * @param navController exposed so the shell can deep-link (e.g. notification → conversation).
 * @param appVisible false while the app is in the background, hidden or minimized (app lock).
 */
@Composable
fun ZoocallApp(
    core: ZoocallCore,
    showCallOverlay: Boolean = true,
    navController: NavHostController = rememberNavController(),
    appVisible: Boolean = true,
) {
    val settings by core.settings.collectAsStateWithLifecycle()
    val status by core.status.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(core) { core.initialize() }

    CompositionLocalProvider(LocalCore provides core, LocalSnackbar provides snackbar) {
        ZoocallTheme(mode = settings.theme, dynamicColor = settings.dynamicColor) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when (val s = status) {
                    CoreStatus.Starting -> Loading()
                    CoreStatus.NeedsOnboarding -> OnboardingScreen()
                    is CoreStatus.Failed -> StartupFailed(s.message)
                    CoreStatus.Running -> Box(Modifier.fillMaxSize()) {
                        DeviceNoticeEffect(core.mediaEngine.devices)
                        AppLockGate(enabled = settings.appLock, visible = appVisible) {
                            HomeScaffold(navController)
                            KnockHost()
                        }
                        // Calls stay reachable while the app is locked.
                        if (showCallOverlay) CallOverlay(core)
                    }
                }
            }
        }
    }
}

@Composable
private fun CallOverlay(core: ZoocallCore) {
    val call by core.activeCall.collectAsStateWithLifecycle()
    AnimatedVisibility(
        visible = call != null,
        enter = fadeIn() + slideInVertically { it / 8 },
        exit = fadeOut() + slideOutVertically { it / 8 },
    ) {
        CallScreen()
    }
}

@Composable
private fun Loading() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Text(stringResource(Res.string.loading), modifier = Modifier.padding(top = Spacing.l))
    }
}

@Composable
private fun StartupFailed(message: String) {
    Column(
        Modifier.fillMaxSize().padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(Res.string.startup_failed_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            stringResource(Res.string.startup_failed_body, message),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.m),
        )
    }
}
