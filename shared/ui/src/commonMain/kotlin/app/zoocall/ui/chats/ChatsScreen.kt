package app.zoocall.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zoocall.core.model.MessageKind
import app.zoocall.core.store.ChatMessageRecord
import app.zoocall.ui.LocalCore
import app.zoocall.ui.components.Avatar
import app.zoocall.ui.components.AvatarButton
import app.zoocall.ui.components.EmptyState
import app.zoocall.ui.components.formatShortTimestamp
import app.zoocall.ui.components.readableContentWidth
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.action_clear_search
import app.zoocall.ui.resources.action_close
import app.zoocall.ui.resources.badge_unread
import app.zoocall.ui.resources.chat_voice_message
import app.zoocall.ui.resources.chat_you
import app.zoocall.ui.resources.chats_empty_body
import app.zoocall.ui.resources.chats_group_sender
import app.zoocall.ui.resources.chats_empty_title
import app.zoocall.ui.resources.chats_request_label
import app.zoocall.ui.resources.chats_search
import app.zoocall.ui.resources.chats_search_no_results
import app.zoocall.ui.resources.nav_chats
import app.zoocall.ui.resources.nav_settings
import app.zoocall.ui.resources.unknown_person
import app.zoocall.ui.theme.Spacing
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    onOpenConversation: (String) -> Unit,
    onOpenSettings: () -> Unit,
    /** Opens a conversation scrolled to one message (search results). */
    onOpenMessage: (fingerprint: String, messageId: String) -> Unit = { fp, _ -> onOpenConversation(fp) },
    /** Opens a group chat, optionally scrolled to one message. */
    onOpenGroupChat: (groupId: String, messageId: String?) -> Unit = { _, _ -> },
) {
    val core = LocalCore.current
    val conversations by core.conversations.collectAsStateWithLifecycle(emptyList())
    val people by core.people.collectAsStateWithLifecycle()
    val settings by core.settings.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ChatMessageRecord>>(emptyList()) }
    val focus = remember { FocusRequester() }

    // Debounced local search; nothing leaves the device.
    LaunchedEffect(query, searching) {
        if (!searching || query.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(200)
        results = core.searchMessages(query)
    }
    LaunchedEffect(searching) { if (searching) runCatching { focus.requestFocus() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it.take(100) },
                            placeholder = { Text(stringResource(Res.string.chats_search)) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, stringResource(Res.string.action_clear_search)) }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    } else {
                        Text(stringResource(Res.string.nav_chats))
                    }
                },
                navigationIcon = {
                    if (searching) {
                        IconButton(onClick = { searching = false; query = "" }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.action_close))
                        }
                    } else {
                        val label = stringResource(Res.string.nav_settings)
                        AvatarButton(
                            name = settings.displayName,
                            contentDescription = label,
                            onClick = onOpenSettings,
                            presence = settings.presence,
                            online = LocalCore.current.network.collectAsStateWithLifecycle().value.onLocalNetwork,
                            modifier = Modifier.padding(start = Spacing.xs),
                        )
                    }
                },
                actions = {
                    if (!searching && conversations.isNotEmpty()) {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, stringResource(Res.string.chats_search)) }
                    }
                },
            )
        },
    ) { padding ->
        if (searching) {
            if (query.isNotBlank() && results.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
                    EmptyState(Icons.Rounded.SearchOff, stringResource(Res.string.chats_search_no_results, query.trim()), "")
                }
                return@Scaffold
            }
            LazyColumn(Modifier.fillMaxSize().padding(padding).readableContentWidth()) {
                items(results, key = { it.id.value }) { record ->
                    val person = people.firstOrNull { it.fingerprint == record.peer }
                    val name = person?.displayName ?: stringResource(Res.string.unknown_person)
                    val body = if (record.kind == MessageKind.File) "📎 ${record.body}" else record.body
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp)
                            .clickable { record.groupId?.let { onOpenGroupChat(it, record.id.value) } ?: onOpenMessage(record.peer.hex, record.id.value) }
                            .padding(horizontal = Spacing.l, vertical = Spacing.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(name)
                        Column(Modifier.weight(1f).padding(horizontal = Spacing.l)) {
                            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (record.outgoing) stringResource(Res.string.chat_you, body) else body,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(formatShortTimestamp(record.receivedAtMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            return@Scaffold
        }

        if (conversations.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
                EmptyState(Icons.AutoMirrored.Rounded.Chat, stringResource(Res.string.chats_empty_title), stringResource(Res.string.chats_empty_body))
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding).readableContentWidth()) {
            items(conversations, key = { it.groupId ?: it.peer.hex }) { convo ->
                val person = people.firstOrNull { it.fingerprint == convo.peer }
                val senderName = person?.displayName ?: stringResource(Res.string.unknown_person)
                val groupId = convo.groupId
                val name = convo.groupName ?: senderName
                val isRequest = groupId == null && person?.isContact != true && !convo.hasOutgoing
                val body = when (convo.lastKind) {
                    MessageKind.Voice -> stringResource(Res.string.chat_voice_message)
                    MessageKind.File -> "📎 ${convo.lastBody}"
                    MessageKind.Text -> convo.lastBody
                }
                val preview = when {
                    convo.lastOutgoing -> stringResource(Res.string.chat_you, body)
                    groupId != null -> stringResource(Res.string.chats_group_sender, senderName, body)
                    else -> body
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .clickable { if (groupId != null) onOpenGroupChat(groupId, null) else onOpenConversation(convo.peer.hex) }
                        .padding(horizontal = Spacing.l, vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (groupId != null) Avatar(name) else Avatar(name, presence = person?.presence, online = person?.online == true)
                    Column(Modifier.weight(1f).padding(horizontal = Spacing.l)) {
                        Text(
                            name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (convo.unread > 0) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isRequest) {
                            Text(
                                stringResource(Res.string.chats_request_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            preview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (convo.unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(formatShortTimestamp(convo.lastAtMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (convo.unread > 0) {
                            val unreadLabel = stringResource(Res.string.badge_unread, convo.unread)
                            Badge(Modifier.padding(top = Spacing.xs).semantics { contentDescription = unreadLabel }) {
                                Text(convo.unread.toString())
                            }
                        }
                    }
                }
            }
        }
    }
}
