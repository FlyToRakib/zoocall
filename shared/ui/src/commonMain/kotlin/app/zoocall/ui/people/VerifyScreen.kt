package app.zoocall.ui.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zoocall.core.app.VerificationInfo
import app.zoocall.ui.LocalCore
import app.zoocall.ui.LocalSnackbar
import app.zoocall.ui.common.DetailScaffold
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.action_done
import app.zoocall.ui.resources.action_retry
import app.zoocall.ui.resources.safety_code_label
import app.zoocall.ui.resources.verify_codes_differ
import app.zoocall.ui.resources.verify_codes_match
import app.zoocall.ui.resources.verify_connecting
import app.zoocall.ui.resources.verify_done
import app.zoocall.ui.resources.verify_instructions
import app.zoocall.ui.resources.verify_mismatch_body
import app.zoocall.ui.resources.verify_mismatch_title
import app.zoocall.ui.resources.verify_title
import app.zoocall.ui.resources.verify_unreachable
import app.zoocall.ui.theme.Spacing
import app.zoocall.ui.theme.ZoocallTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private sealed interface VerifyState {
    data object Connecting : VerifyState
    data object Unreachable : VerifyState
    data class Ready(val info: VerificationInfo) : VerifyState
    data class Mismatch(val info: VerificationInfo) : VerifyState
}

/** Both people compare the same 6-digit code derived from their encrypted session (docs/04 §5). */
@Composable
fun VerifyScreen(personId: String, onDone: () -> Unit) {
    val core = LocalCore.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<VerifyState>(VerifyState.Connecting) }
    val name = core.person(personId)?.displayName.orEmpty()

    LaunchedEffect(personId, attempt) {
        state = VerifyState.Connecting
        state = core.verificationInfo(personId)?.let { VerifyState.Ready(it) } ?: VerifyState.Unreachable
    }

    val title = when (val s = state) {
        is VerifyState.Ready -> stringResource(Res.string.verify_title, s.info.displayName)
        is VerifyState.Mismatch -> stringResource(Res.string.verify_title, s.info.displayName)
        else -> stringResource(Res.string.verify_title, name)
    }

    DetailScaffold(title = title, onBack = onDone) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                when (val s = state) {
                    VerifyState.Connecting -> {
                        CircularProgressIndicator()
                        Text(stringResource(Res.string.verify_connecting))
                    }
                    VerifyState.Unreachable -> {
                        Text(stringResource(Res.string.verify_unreachable, name), textAlign = TextAlign.Center)
                        Button(onClick = { attempt++ }) { Text(stringResource(Res.string.action_retry)) }
                    }
                    is VerifyState.Ready -> {
                        Icon(Icons.Rounded.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                        Text(
                            stringResource(Res.string.verify_instructions, s.info.displayName),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        SafetyCode(s.info.safetyCode)
                        Button(
                            onClick = {
                                scope.launch {
                                    core.addContact(s.info.fingerprint, verified = true)
                                    snackbar.showSnackbar(getString(Res.string.verify_done, s.info.displayName))
                                    onDone()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) { Text(stringResource(Res.string.verify_codes_match)) }
                        OutlinedButton(
                            onClick = { state = VerifyState.Mismatch(s.info) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) { Text(stringResource(Res.string.verify_codes_differ)) }
                    }
                    is VerifyState.Mismatch -> {
                        Icon(Icons.Rounded.GppBad, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(56.dp))
                        Text(
                            stringResource(Res.string.verify_mismatch_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                        )
                        Text(stringResource(Res.string.verify_mismatch_body, s.info.displayName), textAlign = TextAlign.Center)
                        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.action_done)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SafetyCode(code: String) {
    val label = stringResource(Res.string.safety_code_label)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.semantics(mergeDescendants = true) {
            // Read digit by digit so screen readers don't say "four hundred thousand…".
            contentDescription = "$label: ${code.toList().joinToString(" ")}"
        },
    ) {
        Column(Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                Text(
                    code.chunked(3).joinToString(" "),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 44.sp,
                    letterSpacing = 4.sp,
                    color = ZoocallTheme.colors.verified,
                )
            }
        }
    }
}
