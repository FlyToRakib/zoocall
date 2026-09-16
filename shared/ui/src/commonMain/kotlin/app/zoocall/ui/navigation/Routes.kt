package app.zoocall.ui.navigation

import kotlinx.serialization.Serializable

@Serializable data object PeopleRoute
@Serializable data object ChatsRoute
@Serializable data object RecentsRoute
@Serializable data object SettingsRoute
@Serializable data object MyCodeRoute
@Serializable data object AddByAddressRoute
@Serializable data object NetworkDoctorRoute
@Serializable data class PersonRoute(val personId: String)
@Serializable data class GroupRoute(val groupId: String)
@Serializable data class VerifyRoute(val personId: String)
/** [messageId]: scroll to and highlight this message (from search). */
@Serializable data class ConversationRoute(val fingerprint: String, val messageId: String? = null)
@Serializable data class GroupChatRoute(val groupId: String, val messageId: String? = null)
