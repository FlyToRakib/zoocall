package app.zoocall.android

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import app.zoocall.core.app.CheckStatus
import app.zoocall.core.app.DiagnosticResult
import app.zoocall.core.app.DoctorCheck
import app.zoocall.core.app.NetworkState
import app.zoocall.core.app.PlatformDiagnostics

/**
 * Android checks for Network Doctor. Battery optimization delays or kills the "stay reachable"
 * service on many phones, so incoming calls can be missed (docs/06-platforms.md risks).
 */
class AndroidDiagnostics(context: Context) : PlatformDiagnostics {
    private val context = context.applicationContext

    override suspend fun run(network: NetworkState): List<DiagnosticResult> {
        val power = context.getSystemService(PowerManager::class.java) ?: return emptyList()
        val exempt = power.isIgnoringBatteryOptimizations(context.packageName)
        return listOf(DiagnosticResult(DoctorCheck.Battery, if (exempt) CheckStatus.Ok else CheckStatus.Warning, fixable = !exempt))
    }

    /**
     * Zoocall has no push service: without the exemption, calls can't ring while the phone dozes,
     * which is an accepted use of the direct request.
     */
    @SuppressLint("BatteryLife")
    override suspend fun fix(check: DoctorCheck): Boolean {
        if (check != DoctorCheck.Battery) return false
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        return listOf(direct, list).any { intent ->
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: ActivityNotFoundException) {
                false
            } catch (e: SecurityException) {
                false
            }
        }
    }
}
