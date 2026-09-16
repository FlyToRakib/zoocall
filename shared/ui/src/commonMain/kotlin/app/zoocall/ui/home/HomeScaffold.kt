package app.zoocall.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import app.zoocall.ui.LocalCore
import app.zoocall.ui.LocalSnackbar
import app.zoocall.ui.chats.ChatsScreen
import app.zoocall.ui.chats.ConversationScreen
import app.zoocall.ui.navigation.AddByAddressRoute
import app.zoocall.ui.navigation.ChatsRoute
import app.zoocall.ui.navigation.ConversationRoute
import app.zoocall.ui.navigation.MyCodeRoute
import app.zoocall.ui.navigation.PeopleRoute
import app.zoocall.ui.navigation.PersonRoute
import app.zoocall.ui.navigation.RecentsRoute
import app.zoocall.ui.navigation.SettingsRoute
import app.zoocall.ui.navigation.VerifyRoute
import app.zoocall.ui.people.AddByAddressScreen
import app.zoocall.ui.people.MyCodeScreen
import app.zoocall.ui.people.PeopleScreen
import app.zoocall.ui.people.PersonScreen
import app.zoocall.ui.people.VerifyScreen
import app.zoocall.ui.platform.LocalPlatformActions
import app.zoocall.ui.platform.WidthClass
import app.zoocall.ui.platform.currentWidthClass
import app.zoocall.ui.recents.RecentsScreen
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.badge_unread
import app.zoocall.ui.resources.call_return
import app.zoocall.ui.resources.nav_chats
import app.zoocall.ui.resources.nav_people
import app.zoocall.ui.resources.nav_recents
import app.zoocall.ui.settings.NetworkDoctorScreen
import app.zoocall.ui.settings.SettingsScreen
import app.zoocall.ui.navigation.NetworkDoctorRoute
import app.zoocall.ui.navigation.GroupRoute
import app.zoocall.ui.navigation.GroupChatRoute
import app.zoocall.ui.chats.GroupChatScreen
import app.zoocall.ui.people.GroupScreen
import app.zoocall.ui.theme.Spacing
import app.zoocall.ui.theme.ZoocallTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private data class TopLevel(val route: Any, val label: StringResource, val selected: ImageVector, val unselected: ImageVector)

private val TopLevels = listOf(
    TopLevel(PeopleRoute, Res.string.nav_people, Icons.Rounded.People, Icons.Outlined.People),
    TopLevel(ChatsRoute, Res.string.nav_chats, Icons.AutoMirrored.Rounded.Chat, Icons.AutoMirrored.Outlined.Chat),
    TopLevel(RecentsRoute, Res.string.nav_recents, Icons.Rounded.History, Icons.Outlined.History),
)

/** Three destinations: bottom bar on phones, navigation rail on tablets and desktop (docs/05 §4). */
@Composable
fun HomeScaffold(nav: NavHostController) {
    val core = LocalCore.current
    val platform = LocalPlatformActions.current
    val unread by core.unreadCount.collectAsStateWithLifecycle(0)
    val missed by core.unseenMissedCalls.collectAsStateWithLifecycle(0)
    val call by core.activeCall.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val isTopLevel = TopLevels.any { top -> destination?.hierarchy?.any { it.hasRoute(top.route::class) } == true }
    val wide = currentWidthClass() != WidthClass.Compact
    val showCallBar = call?.state?.isActive == true && !platform.isDesktop

    fun navigate(route: Any) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    fun badgeFor(route: Any) = when (route) {
        ChatsRoute -> unread
        RecentsRoute -> missed
        else -> 0
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
        bottomBar = {
            Column {
                if (showCallBar) ReturnToCallBar(onClick = platform::openCallScreen)
                if (!wide && isTopLevel) {
                    NavigationBar {
                        TopLevels.forEach { top ->
                            val selected = destination?.hierarchy?.any { it.hasRoute(top.route::class) } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigate(top.route) },
                                icon = { NavIcon(top, selected, badgeFor(top.route)) },
                                label = { Text(stringResource(top.label)) },
                            )
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Row(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // The bottom bar already handled the navigation-bar inset; screens must not add it again.
                .consumeWindowInsets(padding)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && nav.previousBackStackEntry != null) {
                        nav.popBackStack()
                        true
                    } else {
                        false
                    }
                },
        ) {
            if (wide) {
                // A shade darker than the page (from the theme, so dynamic colour and light mode stay
                // consistent) plus a hairline edge: the rail reads as its own region.
                val background = MaterialTheme.colorScheme.background
                val railColor = if (background.luminance() < 0.5f) lerp(background, Color.Black, 0.35f) else lerp(background, Color.Black, 0.04f)
                NavigationRail(
                    containerColor = railColor,
                    windowInsets = WindowInsets.safeDrawing,
                    // Breathing room above the first destination, level with the top app bar content.
                    header = { Spacer(Modifier.height(Spacing.m)) },
                ) {
                    TopLevels.forEach { top ->
                        val selected = destination?.hierarchy?.any { it.hasRoute(top.route::class) } == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigate(top.route) },
                            icon = { NavIcon(top, selected, badgeFor(top.route)) },
                            label = { Text(stringResource(top.label)) },
                        )
                    }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            }
            Box(Modifier.weight(1f)) {
                NavHost(nav, startDestination = PeopleRoute) {
                    composable<PeopleRoute> {
                        PeopleScreen(
                            onOpenPerson = { nav.navigate(PersonRoute(it)) },
                            onOpenSettings = { nav.navigate(SettingsRoute) },
                            onShowMyCode = { nav.navigate(MyCodeRoute) },
                            onAddByAddress = { nav.navigate(AddByAddressRoute) },
                            onRunNetworkDoctor = { nav.navigate(NetworkDoctorRoute) },
                            onOpenGroup = { nav.navigate(GroupRoute(it)) },
                        )
                    }
                    composable<GroupRoute> { entry ->
                        GroupScreen(
                            groupId = entry.toRoute<GroupRoute>().groupId,
                            onBack = { nav.popBackStack() },
                            onOpenPerson = { nav.navigate(PersonRoute(it)) },
                            onOpenGroupChat = { nav.navigate(GroupChatRoute(it)) },
                        )
                    }
                    composable<ChatsRoute> {
                        ChatsScreen(
                            onOpenConversation = { nav.navigate(ConversationRoute(it)) },
                            onOpenSettings = { nav.navigate(SettingsRoute) },
                            onOpenMessage = { fp, messageId -> nav.navigate(ConversationRoute(fp, messageId)) },
                            onOpenGroupChat = { id, messageId -> nav.navigate(GroupChatRoute(id, messageId)) },
                        )
                    }
                    composable<RecentsRoute> {
                        RecentsScreen(
                            onOpenPerson = { nav.navigate(PersonRoute(it)) },
                            onOpenSettings = { nav.navigate(SettingsRoute) },
                        )
                    }
                    composable<SettingsRoute> {
                        SettingsScreen(
                            onBack = { nav.popBackStack() },
                            onShowMyCode = { nav.navigate(MyCodeRoute) },
                            onOpenNetworkDoctor = { nav.navigate(NetworkDoctorRoute) },
                        )
                    }
                    composable<NetworkDoctorRoute> {
                        NetworkDoctorScreen(
                            onBack = { nav.popBackStack() },
                            onShowMyCode = { nav.navigate(MyCodeRoute) },
                            onAddByAddress = { nav.navigate(AddByAddressRoute) },
                        )
                    }
                    composable<MyCodeRoute> { MyCodeScreen(onBack = { nav.popBackStack() }) }
                    composable<AddByAddressRoute> {
                        AddByAddressScreen(
                            onBack = { nav.popBackStack() },
                            onConnected = { personId ->
                                nav.popBackStack()
                                nav.navigate(VerifyRoute(personId))
                            },
                        )
                    }
                    composable<PersonRoute> { entry ->
                        val route = entry.toRoute<PersonRoute>()
                        PersonScreen(
                            personId = route.personId,
                            onBack = { nav.popBackStack() },
                            onVerify = { nav.navigate(VerifyRoute(it)) },
                            onMessage = { nav.navigate(ConversationRoute(it)) },
                        )
                    }
                    composable<VerifyRoute> { entry ->
                        VerifyScreen(personId = entry.toRoute<VerifyRoute>().personId, onDone = { nav.popBackStack() })
                    }
                    composable<ConversationRoute> { entry ->
                        val route = entry.toRoute<ConversationRoute>()
                        val fp = route.fingerprint
                        ConversationScreen(
                            fingerprintHex = fp,
                            onBack = { nav.popBackStack() },
                            onOpenPerson = { nav.navigate(PersonRoute(fp)) },
                            highlightMessageId = route.messageId,
                        )
                    }
                    composable<GroupChatRoute> { entry ->
                        val route = entry.toRoute<GroupChatRoute>()
                        GroupChatScreen(groupId = route.groupId, onBack = { nav.popBackStack() }, highlightMessageId = route.messageId)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReturnToCallBar(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = ZoocallTheme.colors.accept,
        contentColor = ZoocallTheme.colors.onAccept,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = Spacing.l, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                stringResource(Res.string.call_return),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = Spacing.m),
            )
        }
    }
}

@Composable
private fun NavIcon(top: TopLevel, selected: Boolean, badge: Int) {
    // The item's visible label already names the destination; only the badge needs a description.
    val badgeText = if (badge > 0) stringResource(Res.string.badge_unread, badge) else null
    BadgedBox(
        badge = {
            if (badge > 0) {
                Badge(Modifier.semantics { contentDescription = badgeText.orEmpty() }) {
                    Text(if (badge > 99) "99+" else badge.toString())
                }
            }
        },
    ) {
        Icon(if (selected) top.selected else top.unselected, contentDescription = null)
    }
}
