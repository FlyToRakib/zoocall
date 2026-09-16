package app.zoocall.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.zoocall.core.app.AdapterKind
import app.zoocall.core.app.CheckStatus
import app.zoocall.core.app.DiagnosticResult
import app.zoocall.core.app.DoctorCheck
import app.zoocall.core.app.DoctorReport
import app.zoocall.ui.LocalCore
import app.zoocall.ui.LocalSnackbar
import app.zoocall.ui.common.DetailScaffold
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.add_by_address
import app.zoocall.ui.resources.doctor_adapter
import app.zoocall.ui.resources.doctor_adapter_ethernet
import app.zoocall.ui.resources.doctor_adapter_hotspot
import app.zoocall.ui.resources.doctor_adapter_wifi
import app.zoocall.ui.resources.doctor_all_good
import app.zoocall.ui.resources.doctor_all_good_body
import app.zoocall.ui.resources.doctor_battery_fix
import app.zoocall.ui.resources.doctor_battery_ok
import app.zoocall.ui.resources.doctor_battery_warning
import app.zoocall.ui.resources.doctor_check_again
import app.zoocall.ui.resources.doctor_checking
import app.zoocall.ui.resources.doctor_discovery_blocked
import app.zoocall.ui.resources.doctor_discovery_blocked_fix
import app.zoocall.ui.resources.doctor_discovery_none
import app.zoocall.ui.resources.doctor_discovery_none_fix
import app.zoocall.ui.resources.doctor_discovery_ok
import app.zoocall.ui.resources.doctor_firewall_fix
import app.zoocall.ui.resources.doctor_firewall_ok
import app.zoocall.ui.resources.doctor_firewall_problem
import app.zoocall.ui.resources.doctor_fix_failed
import app.zoocall.ui.resources.doctor_ignored_adapters
import app.zoocall.ui.resources.doctor_issues
import app.zoocall.ui.resources.doctor_issues_body
import app.zoocall.ui.resources.doctor_listening_fix
import app.zoocall.ui.resources.doctor_listening_ok
import app.zoocall.ui.resources.doctor_listening_problem
import app.zoocall.ui.resources.doctor_network_fix
import app.zoocall.ui.resources.doctor_network_ok
import app.zoocall.ui.resources.doctor_network_problem
import app.zoocall.ui.resources.doctor_open_settings
import app.zoocall.ui.resources.doctor_profile_fix
import app.zoocall.ui.resources.doctor_profile_ok
import app.zoocall.ui.resources.doctor_profile_problem
import app.zoocall.ui.resources.doctor_reach_fix
import app.zoocall.ui.resources.doctor_reach_ok
import app.zoocall.ui.resources.doctor_reach_problem
import app.zoocall.ui.resources.doctor_status_ok
import app.zoocall.ui.resources.doctor_status_problem
import app.zoocall.ui.resources.doctor_status_warning
import app.zoocall.ui.resources.doctor_subnet_fix
import app.zoocall.ui.resources.doctor_subnet_warning
import app.zoocall.ui.resources.doctor_title
import app.zoocall.ui.resources.doctor_vpn_fix
import app.zoocall.ui.resources.doctor_vpn_ok
import app.zoocall.ui.resources.doctor_vpn_warning
import app.zoocall.ui.resources.doctor_your_address
import app.zoocall.ui.resources.settings_firewall_allow
import app.zoocall.ui.resources.settings_no_network
import app.zoocall.ui.resources.show_my_code
import app.zoocall.ui.theme.Spacing
import app.zoocall.ui.theme.ZoocallTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Checklist with live results and plain-language fixes (docs/05-ux-ui-design.md §5.7). */
@Composable
fun NetworkDoctorScreen(onBack: () -> Unit, onShowMyCode: () -> Unit, onAddByAddress: () -> Unit) {
    val core = LocalCore.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<DoctorReport?>(null) }
    var running by remember { mutableStateOf(true) }
    var runs by remember { mutableIntStateOf(0) }
    var fixing by remember { mutableStateOf<DoctorCheck?>(null) }
    // Set after opening a system settings page; the check reruns when the user comes back.
    var recheckOnResume by remember { mutableStateOf(false) }

    LaunchedEffect(runs) {
        running = true
        report = core.runNetworkDoctor()
        running = false
    }
    LifecycleResumeEffect(recheckOnResume) {
        if (recheckOnResume) {
            recheckOnResume = false
            runs++
        }
        onPauseOrDispose { }
    }

    fun fix(check: DoctorCheck) {
        fixing = check
        scope.launch {
            val done = core.fixNetworkIssue(check)
            fixing = null
            when {
                !done -> snackbar.showSnackbar(getString(Res.string.doctor_fix_failed))
                // The firewall prompt is modal and finishes here; settings pages finish when the user returns.
                check == DoctorCheck.Firewall -> runs++
                else -> recheckOnResume = true
            }
        }
    }

    DetailScaffold(
        title = stringResource(Res.string.doctor_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = { runs++ }, enabled = !running) {
                Icon(Icons.Rounded.Refresh, stringResource(Res.string.doctor_check_again))
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                val current = report
                Summary(current, running)
                current?.results?.forEach { result ->
                    ResultRow(
                        result = result,
                        fixing = fixing == result.check,
                        onFix = { fix(result.check) },
                        onShowMyCode = onShowMyCode,
                        onAddByAddress = onAddByAddress,
                    )
                }
                if (current != null) {
                    HorizontalDivider(Modifier.padding(vertical = Spacing.s))
                    AddressRow(current, onShowMyCode)
                }
            }
        }
    }
}

@Composable
private fun Summary(report: DoctorReport?, running: Boolean) {
    val (title, body, icon) = when {
        report == null || running -> Triple(Res.string.doctor_checking, null, null)
        report.hasIssues -> Triple(Res.string.doctor_issues, Res.string.doctor_issues_body, Icons.Rounded.Warning)
        else -> Triple(Res.string.doctor_all_good, Res.string.doctor_all_good_body, Icons.Rounded.CheckCircle)
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.l)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, null, Modifier.size(32.dp)) else CircularProgressIndicator(Modifier.size(32.dp))
            Column(Modifier.padding(start = Spacing.l)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                if (body != null) Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ResultRow(
    result: DiagnosticResult,
    fixing: Boolean,
    onFix: () -> Unit,
    onShowMyCode: () -> Unit,
    onAddByAddress: () -> Unit,
) {
    val args = result.args.toTypedArray()
    val text = texts(result)
    val (icon, tint, statusLabel) = when (result.status) {
        CheckStatus.Ok -> Triple(Icons.Rounded.CheckCircle, ZoocallTheme.colors.presenceAvailable, Res.string.doctor_status_ok)
        CheckStatus.Warning -> Triple(Icons.Rounded.Warning, ZoocallTheme.colors.presenceAway, Res.string.doctor_status_warning)
        CheckStatus.Problem -> Triple(Icons.Rounded.Error, MaterialTheme.colorScheme.error, Res.string.doctor_status_problem)
    }
    ListItem(
        leadingContent = { StatusIcon(icon, tint, stringResource(statusLabel)) },
        headlineContent = { Text(stringResource(text.first, *args)) },
        supportingContent = text.second?.let { fix ->
            {
                Column {
                    Text(stringResource(fix, *args))
                    Row(Modifier.padding(top = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        when {
                            fixing -> CircularProgressIndicator(Modifier.size(24.dp))
                            result.fixable -> FilledTonalButton(onClick = onFix) {
                                Text(stringResource(if (result.check == DoctorCheck.Firewall) Res.string.settings_firewall_allow else Res.string.doctor_open_settings))
                            }
                            result.check in PEER_CHECKS -> {
                                FilledTonalButton(onClick = onShowMyCode) { Text(stringResource(Res.string.show_my_code)) }
                                OutlinedButton(onClick = onAddByAddress) { Text(stringResource(Res.string.add_by_address)) }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun StatusIcon(icon: ImageVector, tint: Color, label: String) {
    Icon(icon, contentDescription = label, tint = tint)
}

@Composable
private fun AddressRow(report: DoctorReport, onShowMyCode: () -> Unit) {
    val network = report.network
    val adapter = network.adapter?.let { a ->
        when (a.kind) {
            AdapterKind.WiFi -> stringResource(Res.string.doctor_adapter_wifi)
            AdapterKind.Ethernet -> stringResource(Res.string.doctor_adapter_ethernet)
            AdapterKind.Hotspot -> stringResource(Res.string.doctor_adapter_hotspot)
            AdapterKind.Other -> a.name
        }.let { kind -> if (a.kind == AdapterKind.Other || a.name == kind || a.name.length <= 8) kind else "$kind · ${a.name}" }
    }
    ListItem(
        headlineContent = { Text(stringResource(Res.string.doctor_your_address)) },
        supportingContent = {
            Column {
                Text(
                    if (network.localAddresses.isEmpty()) stringResource(Res.string.settings_no_network)
                    else network.localAddresses.joinToString("\n") { a -> report.port?.let { "$a:$it" } ?: a },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (adapter != null) Text(stringResource(Res.string.doctor_adapter, adapter))
                if (network.ignoredAdapters.isNotEmpty()) {
                    Text(stringResource(Res.string.doctor_ignored_adapters, network.ignoredAdapters.joinToString(", ")))
                }
            }
        },
        trailingContent = { TextButton(onClick = onShowMyCode) { Text(stringResource(Res.string.show_my_code)) } },
    )
}

private val PEER_CHECKS = setOf(DoctorCheck.Discovery, DoctorCheck.Reachability, DoctorCheck.Subnet)

/** Headline and, for anything not OK, the plain-language fix. */
private fun texts(result: DiagnosticResult): Pair<StringResource, StringResource?> {
    val ok = result.status == CheckStatus.Ok
    return when (result.check) {
        DoctorCheck.LocalNetwork -> if (ok) Res.string.doctor_network_ok to null else Res.string.doctor_network_problem to Res.string.doctor_network_fix
        DoctorCheck.Listening -> if (ok) Res.string.doctor_listening_ok to null else Res.string.doctor_listening_problem to Res.string.doctor_listening_fix
        DoctorCheck.Vpn -> if (ok) Res.string.doctor_vpn_ok to null else Res.string.doctor_vpn_warning to Res.string.doctor_vpn_fix
        DoctorCheck.Discovery -> when (result.status) {
            CheckStatus.Ok -> Res.string.doctor_discovery_ok to null
            CheckStatus.Warning -> Res.string.doctor_discovery_none to Res.string.doctor_discovery_none_fix
            CheckStatus.Problem -> Res.string.doctor_discovery_blocked to Res.string.doctor_discovery_blocked_fix
        }
        DoctorCheck.Reachability -> if (ok) Res.string.doctor_reach_ok to null else Res.string.doctor_reach_problem to Res.string.doctor_reach_fix
        DoctorCheck.Subnet -> Res.string.doctor_subnet_warning to Res.string.doctor_subnet_fix
        DoctorCheck.NetworkProfile -> if (ok) Res.string.doctor_profile_ok to null else Res.string.doctor_profile_problem to Res.string.doctor_profile_fix
        DoctorCheck.Firewall -> if (ok) Res.string.doctor_firewall_ok to null else Res.string.doctor_firewall_problem to Res.string.doctor_firewall_fix
        DoctorCheck.Battery -> if (ok) Res.string.doctor_battery_ok to null else Res.string.doctor_battery_warning to Res.string.doctor_battery_fix
    }
}
