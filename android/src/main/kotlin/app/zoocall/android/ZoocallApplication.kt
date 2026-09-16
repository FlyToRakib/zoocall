package app.zoocall.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import app.zoocall.android.call.CallController
import app.zoocall.android.notify.Notifications
import app.zoocall.core.app.AndroidNetworkMonitor
import app.zoocall.core.app.Platform
import app.zoocall.core.app.ZoocallCore
import app.zoocall.core.app.coreModule
import app.zoocall.core.discovery.NsdDiscovery
import app.zoocall.core.model.DeviceClass
import app.zoocall.core.model.Logger
import app.zoocall.core.store.AndroidDatabaseDriverFactory
import app.zoocall.core.store.AndroidKeystoreKeyStore
import app.zoocall.media.WebRtcMediaEngine
import app.zoocall.ui.settings.AppInfo
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class ZoocallApplication : Application() {
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Logger.current.error("App", "Uncaught app error", e) },
    )
    lateinit var core: ZoocallCore
        private set
    lateinit var callController: CallController
        private set
    val foreground = ForegroundTracker()

    override fun onCreate() {
        super.onCreate()
        Logger.current = LogcatLogger(verbose = BuildConfig.DEBUG)
        // Make real crashes easy to find in logcat (tag Zoocall/Crash) before the system handler runs.
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            android.util.Log.e("Zoocall/Crash", "Fatal on thread ${thread.name}", error)
            systemHandler?.uncaughtException(thread, error)
        }
        AppInfo.version = BuildConfig.VERSION_NAME

        val deviceClass = if (resources.configuration.smallestScreenWidthDp >= 600) DeviceClass.Tablet else DeviceClass.Phone
        val mediaEngine = WebRtcMediaEngine(this)
        val platform = Platform(
            keyStore = AndroidKeystoreKeyStore(this),
            databaseFactory = AndroidDatabaseDriverFactory(this),
            discovery = NsdDiscovery(this),
            mediaEngine = mediaEngine,
            network = AndroidNetworkMonitor(this),
            deviceClass = deviceClass,
            appVersion = BuildConfig.VERSION_NAME,
            logger = Logger.current,
            fileSystem = okio.FileSystem.SYSTEM,
            attachmentsDir = java.io.File(noBackupFilesDir, "attachments").absolutePath.toPath(),
            diagnostics = AndroidDiagnostics(this),
        )
        val koin = startKoin {
            androidContext(this@ZoocallApplication)
            modules(coreModule(platform))
        }.koin
        core = koin.get()

        registerActivityLifecycleCallbacks(foreground)
        Notifications.createChannels(this)
        callController = CallController(this, core, appScope, foreground)
        callController.start()
        appScope.launch { core.initialize() }
    }
}

/** Tracks whether any activity is visible (activities may only be started directly while in the foreground). */
class ForegroundTracker : Application.ActivityLifecycleCallbacks {
    private var started = 0
    val isForeground: Boolean get() = started > 0

    override fun onActivityStarted(activity: Activity) {
        started++
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

class LogcatLogger(private val verbose: Boolean) : Logger {
    override fun debug(tag: String, message: () -> String) {
        if (verbose) android.util.Log.d("Zoocall/$tag", message())
    }
    override fun info(tag: String, message: String) {
        android.util.Log.i("Zoocall/$tag", message)
    }
    override fun warn(tag: String, message: String, error: Throwable?) {
        android.util.Log.w("Zoocall/$tag", message, error)
    }
    override fun error(tag: String, message: String, error: Throwable?) {
        android.util.Log.e("Zoocall/$tag", message, error)
    }
}
