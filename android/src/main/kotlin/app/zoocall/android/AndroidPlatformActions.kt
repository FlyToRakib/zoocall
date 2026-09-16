package app.zoocall.android

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.zoocall.android.call.CallController
import app.zoocall.android.scan.ScanActivity
import app.zoocall.core.app.PickedFile
import app.zoocall.core.model.AttachmentMeta
import app.zoocall.ui.platform.AudioRoute
import app.zoocall.ui.platform.PlatformActions
import app.zoocall.ui.platform.RingtoneChooser
import app.zoocall.ui.platform.DeviceUnlock
import android.media.projection.MediaProjectionManager
import app.zoocall.android.service.ZoocallService
import app.zoocall.media.WebRtcMediaEngine
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import app.zoocall.core.app.AppSettings
import android.media.RingtoneManager
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okio.Path
import okio.buffer
import okio.sink
import okio.source
import java.io.File

/** Must be created in `onCreate` (it registers activity result launchers). */
class AndroidPlatformActions(
    private val activity: ComponentActivity,
    private val calls: CallController,
) : PlatformActions {
    override val isDesktop = false

    private var credentialResult: CompletableDeferred<Boolean>? = null
    private val credentialLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        credentialResult?.complete(result.resultCode == Activity.RESULT_OK)
    }

    override val deviceUnlock: DeviceUnlock = object : DeviceUnlock {
        override fun isAvailable(): Boolean = activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

        override suspend fun unlock(title: String, subtitle: String): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) biometricPrompt(title, subtitle) else confirmCredential(title, subtitle)
    }

    /** Fingerprint or face, with the screen lock PIN, pattern or password as the alternative. */
    private suspend fun biometricPrompt(title: String, subtitle: String): Boolean = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        val builder = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (cont.isActive) cont.resume(false)
            }
        }
        runCatching { builder.build().authenticate(signal, activity.mainExecutor, callback) }
            .onFailure { if (cont.isActive) cont.resume(false) }
    }

    /** Android 9 has no credential fallback in BiometricPrompt: use the lock screen's own confirmation. */
    private suspend fun confirmCredential(title: String, subtitle: String): Boolean {
        @Suppress("DEPRECATION")
        val intent = activity.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(title, subtitle) ?: return false
        val result = CompletableDeferred<Boolean>()
        credentialResult = result
        return try {
            credentialLauncher.launch(intent)
            result.await()
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private var projectionResult: CompletableDeferred<Intent?>? = null
    private val projectionLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        projectionResult?.complete(result.data?.takeIf { result.resultCode == Activity.RESULT_OK })
    }

    override suspend fun requestScreenCapture(): Boolean {
        val manager = activity.getSystemService(MediaProjectionManager::class.java) ?: return false
        val engine = (activity.application as ZoocallApplication).core.mediaEngine as? WebRtcMediaEngine ?: return false
        val result = CompletableDeferred<Intent?>()
        projectionResult = result
        try {
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        } catch (e: ActivityNotFoundException) {
            return false
        }
        val consent = result.await() ?: return false
        // Android 14+: the call's foreground service must include the mediaProjection type before capture starts.
        if (!ZoocallService.startScreenShare(activity)) return false
        engine.grantScreenCapture(consent)
        return true
    }

    private var permissionResult: CompletableDeferred<Map<String, Boolean>>? = null
    private val permissionLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionResult?.complete(it)
    }

    private var scanResult: CompletableDeferred<String?>? = null
    private val scanLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        scanResult?.complete(if (result.resultCode == Activity.RESULT_OK) result.data?.getStringExtra(ScanActivity.EXTRA_CODE) else null)
    }

    private var pickResult: CompletableDeferred<Uri?>? = null
    private val pickLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { pickResult?.complete(it) }

    private var saveResult: CompletableDeferred<Uri?>? = null
    private val saveLauncher = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        saveResult?.complete(it)
    }

    private var ringtoneResult: CompletableDeferred<String?>? = null
    private val ringtoneLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        ringtoneResult?.complete(
            if (result.resultCode != Activity.RESULT_OK) {
                null
            } else {
                val uri = result.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
                when (uri) {
                    null -> AppSettings.SILENT_RINGTONE
                    Settings.System.DEFAULT_RINGTONE_URI -> ""
                    else -> uri.toString()
                }
            },
        )
    }

    /** The system ringtone picker: it lists the phone's ringtones and previews them itself. */
    override val ringtones = object : RingtoneChooser {
        override val builtIn = emptyList<String>()

        override fun systemLabel(id: String): String? = runCatching {
            val uri = if (id.isEmpty()) RingtoneManager.getActualDefaultRingtoneUri(activity, RingtoneManager.TYPE_RINGTONE) else Uri.parse(id)
            RingtoneManager.getRingtone(activity, uri)?.getTitle(activity)
        }.getOrNull()

        override suspend fun pickFromSystem(current: String): String? {
            val existing: Uri? = when (current) {
                AppSettings.SILENT_RINGTONE -> null
                "" -> Settings.System.DEFAULT_RINGTONE_URI
                else -> Uri.parse(current)
            }
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_RINGTONE_URI)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
            val deferred = CompletableDeferred<String?>()
            ringtoneResult = deferred
            return try {
                ringtoneLauncher.launch(intent)
                deferred.await()
            } catch (e: ActivityNotFoundException) {
                null
            }
        }
    }

    override suspend fun ensureCallPermissions(video: Boolean): Boolean {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = wanted.filterNot(::granted)
        if (missing.isNotEmpty()) {
            val deferred = CompletableDeferred<Map<String, Boolean>>()
            permissionResult = deferred
            permissionLauncher.launch(missing.toTypedArray())
            deferred.await()
        }
        return granted(Manifest.permission.RECORD_AUDIO)
    }

    override val qrScanner: (suspend () -> String?) = {
        val deferred = CompletableDeferred<String?>()
        scanResult = deferred
        scanLauncher.launch(Intent(activity, ScanActivity::class.java))
        deferred.await()
    }

    override val audioRoutes: StateFlow<List<AudioRoute>> get() = calls.audioRoutes
    override val currentAudioRoute: StateFlow<AudioRoute?> get() = calls.currentAudioRoute
    override fun selectAudioRoute(route: AudioRoute) = calls.selectAudioRoute(route)

    /** Android's Live Caption is switched on from the caption or accessibility settings (or the volume panel). */
    override val liveCaptions = app.zoocall.ui.platform.SystemFeature {
        listOf(Intent(Settings.ACTION_CAPTIONING_SETTINGS), Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)).any { intent ->
            try {
                activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: ActivityNotFoundException) {
                false
            }
        }
    }

    override fun openAppSettings() {
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun openCallScreen() {
        activity.startActivity(Intent(activity, app.zoocall.android.call.CallActivity::class.java))
    }

    // -- Files ------------------------------------------------------------------------------------

    override val canPickFiles = true

    /** The Storage Access Framework picker: no storage permission needed. */
    override suspend fun pickFile(): PickedFile? {
        val deferred = CompletableDeferred<Uri?>()
        pickResult = deferred
        pickLauncher.launch(arrayOf("*/*"))
        val uri = deferred.await() ?: return null
        val resolver = activity.contentResolver
        var name = "file"
        var size: Long? = null
        withContext(Dispatchers.IO) {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { cursor.getString(it) }?.let { name = it }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
                }
            }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        return PickedFile(name, mime, size) {
            (resolver.openInputStream(uri) ?: error("Can't open the picked file")).source()
        }
    }

    /**
     * Copies the file under its real name into a cache folder shared through FileProvider (read-only,
     * granted to the chosen app only), then asks the system which app should open it.
     */
    override suspend fun openFile(path: Path, name: String, mime: String): Boolean {
        val shared = withContext(Dispatchers.IO) {
            val dir = File(activity.cacheDir, "shared/${path.name}").apply { mkdirs() }
            File(dir, AttachmentMeta.sanitizeName(name)).also { target ->
                if (!target.exists()) File(path.toString()).copyTo(target, overwrite = true)
            }
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", shared)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            activity.startActivity(Intent.createChooser(intent, null))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    override suspend fun saveFile(path: Path, name: String, mime: String): Boolean {
        val deferred = CompletableDeferred<Uri?>()
        saveResult = deferred
        saveLauncher.launch(AttachmentMeta.sanitizeName(name))
        val target = deferred.await() ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                activity.contentResolver.openOutputStream(target)?.sink()?.buffer()?.use { out ->
                    File(path.toString()).source().use { out.writeAll(it) }
                } ?: return@runCatching false
                true
            }.getOrDefault(false)
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
}
