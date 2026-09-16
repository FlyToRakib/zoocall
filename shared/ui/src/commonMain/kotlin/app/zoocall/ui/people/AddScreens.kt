package app.zoocall.ui.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zoocall.core.app.AddResult
import app.zoocall.core.model.PeerAddress
import app.zoocall.ui.LocalCore
import app.zoocall.ui.common.DetailScaffold
import app.zoocall.ui.components.Avatar
import app.zoocall.ui.components.QrCode
import app.zoocall.ui.platform.encodeQr
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.action_add
import app.zoocall.ui.resources.add_key_mismatch
import app.zoocall.ui.resources.add_self
import app.zoocall.ui.resources.add_unreachable
import app.zoocall.ui.resources.address_connecting
import app.zoocall.ui.resources.address_invalid
import app.zoocall.ui.resources.address_label
import app.zoocall.ui.resources.address_placeholder
import app.zoocall.ui.resources.address_supporting
import app.zoocall.ui.resources.address_title
import app.zoocall.ui.resources.my_code_address
import app.zoocall.ui.resources.my_code_body
import app.zoocall.ui.resources.my_code_qr_description
import app.zoocall.ui.resources.my_code_title
import app.zoocall.ui.resources.my_code_unavailable
import app.zoocall.ui.theme.Spacing
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Manual `ip[:port]` for networks that block discovery (edge cases N3–N5). */
@Composable
fun AddByAddressScreen(onBack: () -> Unit, onConnected: (personId: String) -> Unit) {
    val core = LocalCore.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<StringResource?>(null) }
    val valid = PeerAddress.parse(input) != null

    fun submit() {
        if (!valid) {
            error = Res.string.address_invalid
            return
        }
        busy = true
        error = null
        scope.launch {
            when (val result = core.connectToAddress(input)) {
                is AddResult.Added -> onConnected(result.person.hex)
                AddResult.Unreachable -> error = Res.string.add_unreachable
                AddResult.InvalidCode -> error = Res.string.address_invalid
                AddResult.KeyMismatch -> error = Res.string.add_key_mismatch
                AddResult.Self -> error = Res.string.add_self
            }
            busy = false
        }
    }

    DetailScaffold(title = stringResource(Res.string.address_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.trim().take(64); error = null },
                    label = { Text(stringResource(Res.string.address_label)) },
                    placeholder = { Text(stringResource(Res.string.address_placeholder)) },
                    supportingText = {
                        Text(
                            stringResource(error ?: Res.string.address_supporting),
                            modifier = Modifier.semantics { if (error != null) liveRegion = LiveRegionMode.Polite },
                        )
                    },
                    isError = error != null,
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = ::submit, enabled = input.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.padding(end = Spacing.s).size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(Res.string.address_connecting))
                    } else {
                        Text(stringResource(Res.string.action_add))
                    }
                }
            }
        }
    }
}

/** QR with public key + address; scanning it adds a verified contact. */
@Composable
fun MyCodeScreen(onBack: () -> Unit) {
    val core = LocalCore.current
    val settings by core.settings.collectAsStateWithLifecycle()
    val network by core.network.collectAsStateWithLifecycle()
    val port by core.listenPort.collectAsStateWithLifecycle(null)
    val code = remember(network, port, settings.displayName) { core.myContactCode() }
    val matrix = remember(code) { code?.let { encodeQr(it.toUri()) } }

    DetailScaffold(title = stringResource(Res.string.my_code_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Avatar(settings.displayName, size = 72.dp)
            Text(settings.displayName, style = MaterialTheme.typography.headlineSmall)
            if (code != null && matrix != null && network.onLocalNetwork) {
                QrCode(matrix, stringResource(Res.string.my_code_qr_description, settings.displayName))
                Text(
                    stringResource(Res.string.my_code_body),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
                SelectionContainer {
                    Text(
                        stringResource(Res.string.my_code_address, code.addresses.joinToString(", ")),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Text(stringResource(Res.string.my_code_unavailable), textAlign = TextAlign.Center)
            }
        }
    }
}
