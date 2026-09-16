package app.zoocall.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zoocall.core.app.AddResult
import app.zoocall.core.app.Person
import app.zoocall.core.model.CallKind
import app.zoocall.core.model.DeviceClass
import app.zoocall.ui.LocalCore
import app.zoocall.ui.LocalSnackbar
import app.zoocall.ui.common.rememberCallLauncher
import app.zoocall.ui.components.Avatar
import app.zoocall.ui.components.AvatarButton
import app.zoocall.ui.components.Banner
import app.zoocall.ui.components.EmptyState
import app.zoocall.ui.components.SectionHeader
import app.zoocall.ui.components.VerificationLabel
import app.zoocall.ui.components.presenceLabel
import app.zoocall.ui.components.readableContentWidth
import app.zoocall.ui.components.relativeTime
import app.zoocall.ui.platform.LocalPlatformActions
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.action_clear_search
import app.zoocall.ui.resources.add_added
import app.zoocall.ui.resources.add_by_address
import app.zoocall.ui.resources.add_by_qr
import app.zoocall.ui.resources.add_contact
import app.zoocall.ui.resources.add_invalid_code
import app.zoocall.ui.resources.add_key_mismatch
import app.zoocall.ui.resources.add_self
import app.zoocall.ui.resources.add_unreachable
import app.zoocall.ui.resources.app_name
import app.zoocall.ui.resources.call_person
import app.zoocall.ui.resources.last_seen
import app.zoocall.ui.resources.name_conflict
import app.zoocall.ui.resources.nav_settings
import app.zoocall.ui.resources.people_empty_body
import app.zoocall.ui.resources.people_empty_title
import app.zoocall.ui.resources.doctor_run
import app.zoocall.ui.resources.group_new
import app.zoocall.ui.resources.groups_section
import app.zoocall.ui.components.SectionHeader
import androidx.compose.material.icons.rounded.GroupAdd
import app.zoocall.ui.resources.people_no_results
import app.zoocall.ui.resources.people_not_on_wifi
import app.zoocall.ui.resources.people_search
import app.zoocall.ui.resources.people_section_blocked
import app.zoocall.ui.resources.people_section_favorites
import app.zoocall.ui.resources.people_section_nearby
import app.zoocall.ui.resources.people_section_offline
import app.zoocall.ui.resources.people_vpn_warning
import app.zoocall.ui.resources.person_blocked_label
import app.zoocall.ui.resources.show_my_code
import app.zoocall.ui.resources.video_call_person
import app.zoocall.ui.theme.Spacing
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
fun PeopleScreen(
    onOpenPerson: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onShowMyCode: () -> Unit,
    onAddByAddress: () -> Unit,
    onRunNetworkDoctor: () -> Unit,
    onOpenGroup: (String) -> Unit,
) {
    val core = LocalCore.current
    val platform = LocalPlatformActions.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val people by core.people.collectAsStateWithLifecycle()
    val settings by core.settings.collectAsStateWithLifecycle()
    val network by core.network.collectAsStateWithLifecycle()
    val launcher = rememberCallLauncher()
    var query by rememberSaveable { mutableStateOf("") }
    var addMenu by remember { mutableStateOf(false) }
    val groups by core.groups.collectAsStateWithLifecycle(emptyList())
    var newGroupOpen by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    val sections = remember(people, query) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) people else people.filter { it.displayName.lowercase().contains(q) || it.role.lowercase().contains(q) }
        PeopleSections(
            favorites = filtered.filter { it.favorite && !it.blocked },
            nearby = filtered.filter { it.online && !it.favorite && !it.blocked },
            offline = filtered.filter { !it.online && !it.favorite && it.isContact && !it.blocked },
            blocked = filtered.filter { it.blocked },
        )
    }

    fun scanQr() {
        val scanner = platform.qrScanner ?: return
        scope.launch {
            val code = scanner() ?: return@launch
            val message = when (val result = core.addFromCode(code)) {
                is AddResult.Added -> getString(Res.string.add_added, result.name)
                AddResult.KeyMismatch -> getString(Res.string.add_key_mismatch)
                AddResult.Unreachable -> getString(Res.string.add_unreachable)
                AddResult.InvalidCode -> getString(Res.string.add_invalid_code)
                AddResult.Self -> getString(Res.string.add_self)
            }
            snackbar.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.app_name)) },
                navigationIcon = {
                    val settingsLabel = stringResource(Res.string.nav_settings)
                    AvatarButton(
                        name = settings.displayName,
                        contentDescription = settingsLabel,
                        onClick = onOpenSettings,
                        presence = settings.presence,
                        online = network.onLocalNetwork,
                        modifier = Modifier.padding(start = Spacing.xs),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    if (platform.qrScanner != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.add_by_qr)) },
                            leadingIcon = { Icon(Icons.Rounded.QrCodeScanner, null) },
                            onClick = { addMenu = false; scanQr() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.add_by_address)) },
                        leadingIcon = { Icon(Icons.Rounded.Dialpad, null) },
                        onClick = { addMenu = false; onAddByAddress() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.show_my_code)) },
                        leadingIcon = { Icon(Icons.Rounded.QrCode2, null) },
                        onClick = { addMenu = false; onShowMyCode() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.group_new)) },
                        leadingIcon = { Icon(Icons.Rounded.GroupAdd, null) },
                        onClick = { addMenu = false; newGroupOpen = true },
                    )
                }
                ExtendedFloatingActionButton(
                    onClick = { addMenu = true },
                    icon = { Icon(Icons.Rounded.PersonAdd, null) },
                    text = { Text(stringResource(Res.string.add_contact)) },
                )
            }
        },
    ) { padding ->
        // Network banners open Network Doctor for the explanation and fix.
        val doctorLabel = stringResource(Res.string.doctor_run)
        val bannerModifier = Modifier
            .padding(horizontal = Spacing.l, vertical = Spacing.s)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = doctorLabel, onClick = onRunNetworkDoctor)
        LazyColumn(
            Modifier.fillMaxSize().readableContentWidth(),
            // Leave room at the end so the floating button never covers the last row.
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 88.dp),
        ) {
            item(key = "search") {
                SearchField(query, onQueryChange = { query = it })
            }
            if (!network.onLocalNetwork) {
                item(key = "wifi") {
                    Banner(stringResource(Res.string.people_not_on_wifi), Icons.Rounded.WifiOff, bannerModifier, warning = true)
                }
            } else if (network.vpnActive) {
                item(key = "vpn") {
                    Banner(stringResource(Res.string.people_vpn_warning), Icons.Rounded.VpnLock, bannerModifier)
                }
            }

            if (sections.isEmpty) {
                item(key = "empty") {
                    if (query.isNotBlank()) {
                        EmptyState(Icons.Rounded.Search, stringResource(Res.string.people_no_results, query), "")
                    } else {
                        EmptyState(
                            Icons.Rounded.WifiFind,
                            stringResource(Res.string.people_empty_title),
                            stringResource(Res.string.people_empty_body),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                FilledTonalButton(onClick = onShowMyCode) { Text(stringResource(Res.string.show_my_code)) }
                                OutlinedButton(onClick = onRunNetworkDoctor) { Text(stringResource(Res.string.doctor_run)) }
                            }
                        }
                    }
                }
            }

            if (groups.isNotEmpty() && query.isBlank()) {
                item(key = "header-groups") { SectionHeader("${stringResource(Res.string.groups_section)} (${groups.size})") }
                items(groups, key = { "group-${it.id}" }) { group -> GroupRow(group, people, onClick = { onOpenGroup(group.id) }) }
            }
            section(Res.string.people_section_favorites, sections.favorites, onOpenPerson, launcher::call)
            section(Res.string.people_section_nearby, sections.nearby, onOpenPerson, launcher::call)
            section(Res.string.people_section_offline, sections.offline, onOpenPerson, launcher::call)
            section(Res.string.people_section_blocked, sections.blocked, onOpenPerson, launcher::call)
        }
    }

    if (newGroupOpen) {
        GroupEditorDialog(
            onDismiss = { newGroupOpen = false },
            onSave = { name, members ->
                newGroupOpen = false
                scope.launch { core.createGroup(name, members)?.let(onOpenGroup) }
            },
        )
    }
}

private data class PeopleSections(
    val favorites: List<Person>,
    val nearby: List<Person>,
    val offline: List<Person>,
    val blocked: List<Person>,
) {
    val isEmpty: Boolean get() = favorites.isEmpty() && nearby.isEmpty() && offline.isEmpty() && blocked.isEmpty()
}

private fun LazyListScope.section(
    title: StringResource,
    people: List<Person>,
    onOpen: (String) -> Unit,
    onCall: (String, String, CallKind) -> Unit,
) {
    if (people.isEmpty()) return
    item(key = "header-${title.key}") {
        SectionHeader("${stringResource(title)} (${people.size})")
    }
    items(people, key = { "${title.key}-${it.id}" }) { person ->
        PersonRow(person, onClick = { onOpen(person.id) }, onCall = { kind -> onCall(person.id, person.displayName, kind) })
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    SearchBarDefaults.InputField(
        query = query,
        onQueryChange = onQueryChange,
        onSearch = {},
        expanded = false,
        onExpandedChange = {},
        placeholder = { Text(stringResource(Res.string.people_search)) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, stringResource(Res.string.action_clear_search))
                }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
    )
}

@Composable
fun PersonRow(person: Person, onClick: () -> Unit, onCall: (CallKind) -> Unit) {
    val presence = presenceLabel(person.presence, person.online)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(person.displayName, presence = person.presence, online = person.online)
        Column(
            Modifier.weight(1f).padding(horizontal = Spacing.l).semantics(mergeDescendants = true) {},
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    person.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (person.favorite) {
                    Icon(Icons.Rounded.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = Spacing.xs).size(16.dp))
                }
                if (person.deviceClass == DeviceClass.Desktop) {
                    Icon(Icons.Rounded.Computer, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = Spacing.xs).size(16.dp))
                }
            }
            val secondary = buildList {
                if (person.role.isNotBlank()) add(person.role)
                if (person.online && person.statusText.isNotBlank()) add(person.statusText)
                if (person.online) add(presence) else person.lastSeenMs?.let { add(stringResource(Res.string.last_seen, relativeTime(it))) }
            }.joinToString(" · ")
            if (secondary.isNotEmpty()) {
                Text(secondary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                person.blocked -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Block, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                    Text(
                        stringResource(Res.string.person_blocked_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = Spacing.xs),
                    )
                }
                person.nameMatchesOtherVerified -> Text(stringResource(Res.string.name_conflict), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                else -> VerificationLabel(person.verified, compact = true)
            }
        }
        if (person.online && !person.blocked) {
            IconButton(onClick = { onCall(CallKind.Audio) }) {
                Icon(Icons.Rounded.Call, stringResource(Res.string.call_person, person.displayName), tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { onCall(CallKind.Video) }) {
                Icon(Icons.Rounded.Videocam, stringResource(Res.string.video_call_person, person.displayName), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
