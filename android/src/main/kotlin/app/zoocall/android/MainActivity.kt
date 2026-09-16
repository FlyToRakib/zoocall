package app.zoocall.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import app.zoocall.core.app.ThemeMode
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import app.zoocall.core.app.AddResult
import app.zoocall.core.app.CoreStatus
import app.zoocall.ui.ZoocallApp
import app.zoocall.ui.navigation.ConversationRoute
import app.zoocall.ui.navigation.GroupChatRoute
import app.zoocall.ui.platform.LocalPlatformActions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as ZoocallApplication
    private lateinit var platformActions: AndroidPlatformActions
    private var pendingConversation by mutableStateOf<String?>(null)

    /** Between onStart and onStop; app lock locks again after the app was out of sight. */
    private var inForeground by mutableStateOf(true)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        platformActions = AndroidPlatformActions(this, app.callController)
        handleIntent(intent)

        setContent {
            val nav = rememberNavController()
            // Status/navigation bar icons follow the in-app theme, which can differ from the system's.
            val settings by app.core.settings.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val dark = when (settings.theme) {
                ThemeMode.System -> systemDark
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            LaunchedEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            CompositionLocalProvider(LocalPlatformActions provides platformActions) {
                ZoocallApp(core = app.core, showCallOverlay = false, navController = nav, appVisible = inForeground)
            }
            val target = pendingConversation
            LaunchedEffect(target) {
                if (target == null) return@LaunchedEffect
                app.core.status.first { it == CoreStatus.Running }
                withFrameNanos { } // let the NavHost attach its graph first
                if (target.startsWith(GROUP_TARGET_PREFIX)) {
                    nav.navigate(GroupChatRoute(target.removePrefix(GROUP_TARGET_PREFIX)))
                } else {
                    nav.navigate(ConversationRoute(target))
                }
                pendingConversation = null
            }
        }

        lifecycleScope.launch {
            app.core.status.first { it == CoreStatus.Running }
            requestNotificationPermissionIfNeeded()
            app.callController.onAppVisible()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        inForeground = true
        app.callController.onAppVisible()
    }

    override fun onStop() {
        inForeground = false
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_CONVERSATION)?.let { pendingConversation = it }
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data?.scheme == "zoocall") {
            val uri = data.toString()
            lifecycleScope.launch {
                app.core.status.first { it == CoreStatus.Running }
                val message = when (val result = app.core.addFromCode(uri)) {
                    is AddResult.Added -> getString(R.string.app_name) + ": " + result.name
                    AddResult.KeyMismatch, AddResult.InvalidCode, AddResult.Self, AddResult.Unreachable -> null
                }
                message?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_CONVERSATION = "app.zoocall.extra.CONVERSATION"

        /** EXTRA_CONVERSATION value prefix for a group chat instead of a fingerprint. */
        const val GROUP_TARGET_PREFIX = "group:"
    }
}
