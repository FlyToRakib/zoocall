package app.zoocall.ui

import androidx.compose.runtime.Composable
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.chat_voice_message
import app.zoocall.ui.resources.knock_default_text
import app.zoocall.ui.resources.knock_received
import app.zoocall.ui.resources.knock_replied_busy
import app.zoocall.ui.resources.knock_replied_call_me
import app.zoocall.ui.resources.knock_replied_two_minutes
import app.zoocall.ui.resources.shell_already_running
import app.zoocall.ui.resources.shell_call_window
import app.zoocall.ui.resources.shell_hotkey_in_use
import app.zoocall.ui.resources.shell_incoming_call_window
import app.zoocall.ui.resources.shell_tray_open
import app.zoocall.ui.resources.shell_tray_quit
import app.zoocall.ui.resources.unknown_person
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Localized text for the platform shells: tray menu, system notifications, window titles.
 * The generated `Res` is internal to this module, so shells go through this list.
 */
enum class ShellText(private val resource: StringResource) {
    AlreadyRunning(Res.string.shell_already_running),
    TrayOpen(Res.string.shell_tray_open),
    TrayQuit(Res.string.shell_tray_quit),
    IncomingCallWindow(Res.string.shell_incoming_call_window),
    CallWindow(Res.string.shell_call_window),
    HotkeyInUse(Res.string.shell_hotkey_in_use),
    Knocked(Res.string.knock_received),
    KnockDefault(Res.string.knock_default_text),
    RepliedCallMe(Res.string.knock_replied_call_me),
    RepliedTwoMinutes(Res.string.knock_replied_two_minutes),
    RepliedBusy(Res.string.knock_replied_busy),
    VoiceMessage(Res.string.chat_voice_message),
    UnknownPerson(Res.string.unknown_person),
    ;

    suspend fun get(vararg args: Any): String = getString(resource, *args)

    @Composable
    fun text(vararg args: Any): String = stringResource(resource, *args)
}
