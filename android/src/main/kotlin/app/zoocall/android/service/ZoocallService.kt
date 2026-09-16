package app.zoocall.android.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.zoocall.android.notify.Notifications
import app.zoocall.core.model.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One foreground service with two modes:
 * - **reachable** (`specialUse`): keeps the LAN listener alive so calls and messages arrive.
 * - **call** (`phoneCall` + `microphone` [+ `camera`] [+ `mediaProjection` while sharing the screen]):
 *   required to keep capturing while not visible.
 */
class ZoocallService : Service() {
    private var inCall = false
    private var callName = ""
    private var callVideo = false
    private var callConnectedAtMs: Long? = null
    private var sharingScreen = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CALL -> startCallMode(
                name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
                video = intent.getBooleanExtra(EXTRA_VIDEO, false),
                connectedAtMs = intent.getLongExtra(EXTRA_CONNECTED_AT, 0L).takeIf { it > 0 },
            )
            ACTION_SCREEN_SHARE -> {
                sharingScreen = intent.getBooleanExtra(EXTRA_ON, false)
                val ready = if (inCall) {
                    startCallMode(callName, callVideo, callConnectedAtMs)
                } else {
                    // Never left without a foreground notification after startForegroundService.
                    startReachableMode()
                    false
                }
                pendingShare?.complete(ready && sharingScreen)
                pendingShare = null
            }
            else -> startReachableMode()
        }
        return START_STICKY
    }

    private fun startReachableMode() {
        inCall = false
        sharingScreen = false
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        startForegroundSafely(Notifications.reachable(this), type)
    }

    /** Returns whether every requested type was accepted. */
    private fun startCallMode(name: String, video: Boolean, connectedAtMs: Long?): Boolean {
        inCall = true
        callName = name
        callVideo = video
        callConnectedAtMs = connectedAtMs
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        if (granted(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (video && granted(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (sharingScreen) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val notification = Notifications.ongoingCall(this, name, video, connectedAtMs)
        if (startForegroundSafely(notification, type)) return true
        // e.g. started from the background without a while-in-use exemption: keep at least the call type.
        startForegroundSafely(notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        return false
    }

    private fun startForegroundSafely(notification: android.app.Notification, type: Int): Boolean = try {
        ServiceCompat.startForeground(this, Notifications.ID_SERVICE, notification, type)
        true
    } catch (e: Exception) {
        Logger.current.warn(TAG, "startForeground failed", e)
        false
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "Service"
        private const val ACTION_REACHABLE = "app.zoocall.service.REACHABLE"
        private const val ACTION_CALL = "app.zoocall.service.CALL"
        private const val ACTION_SCREEN_SHARE = "app.zoocall.service.SCREEN_SHARE"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_CONNECTED_AT = "connectedAt"
        private const val EXTRA_ON = "on"
        private const val SHARE_READY_TIMEOUT_MS = 3_000L

        /** Completed by the service once it has (or couldn't get) the mediaProjection type. */
        @Volatile private var pendingShare: CompletableDeferred<Boolean>? = null

        fun startReachable(context: Context) = start(context, Intent(context, ZoocallService::class.java).setAction(ACTION_REACHABLE))

        fun enterCall(context: Context, name: String, video: Boolean, connectedAtMs: Long? = null) = start(
            context,
            Intent(context, ZoocallService::class.java)
                .setAction(ACTION_CALL)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_VIDEO, video)
                .putExtra(EXTRA_CONNECTED_AT, connectedAtMs ?: 0L),
        )

        fun exitCall(context: Context) = startReachable(context)

        /** Adds the mediaProjection type to the running call. True once Android accepted it. */
        suspend fun startScreenShare(context: Context): Boolean {
            val ready = CompletableDeferred<Boolean>()
            pendingShare = ready
            start(context, Intent(context, ZoocallService::class.java).setAction(ACTION_SCREEN_SHARE).putExtra(EXTRA_ON, true))
            return withTimeoutOrNull(SHARE_READY_TIMEOUT_MS) { ready.await() } ?: false
        }

        fun stopScreenShare(context: Context) =
            start(context, Intent(context, ZoocallService::class.java).setAction(ACTION_SCREEN_SHARE).putExtra(EXTRA_ON, false))

        private fun start(context: Context, intent: Intent) {
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Logger.current.warn(TAG, "Couldn't start service", it) }
        }
    }
}
