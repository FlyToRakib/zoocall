package app.zoocall.core.app

import app.zoocall.core.model.AllowCallsFrom
import app.zoocall.core.model.Base64Url
import app.zoocall.core.model.DeviceClass
import app.zoocall.core.model.Fingerprint
import app.zoocall.core.model.PeerAddress
import app.zoocall.core.model.Presence
import app.zoocall.core.model.Visibility

enum class ThemeMode { System, Light, Dark }

data class AppSettings(
    val displayName: String = "",
    val role: String = "",
    val presence: Presence = Presence.Available,
    val visibility: Visibility = Visibility.Everyone,
    val allowCallsFrom: AllowCallsFrom = AllowCallsFrom.Everyone,
    val theme: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val readReceipts: Boolean = true,
    /** Custom status shown next to presence, e.g. "At the front desk". */
    val statusText: String = "",
    /** Platform ringtone id: "" for the default, [SILENT_RINGTONE], a built-in tone (desktop) or a system URI (Android). */
    val ringtone: String = "",
    val vibrateOnRing: Boolean = true,
    /** Call-connected/ended tones, call-waiting beeps and knock chimes. */
    val callSounds: Boolean = true,
    /** Flash the screen (and the camera light on phones) while a call rings (accessibility). */
    val flashAlert: Boolean = false,
    /** Desktop: a system-wide shortcut toggles mute during a call. */
    val globalMuteHotkey: Boolean = false,
    /** Desktop: libwebrtc's strongest noise suppression level instead of its default. */
    val strongNoiseSuppression: Boolean = false,
    /** Desktop: contacts allowed one by one may open a live audio line without ringing. Off by default. */
    val deskIntercom: Boolean = false,
    /** Opening the app needs the device's lock (Android) or the app passcode (desktop). */
    val appLock: Boolean = false,
    /** A desktop app-lock passcode hash is stored. */
    val appLockHasPasscode: Boolean = false,
) {
    val isOnboarded: Boolean get() = displayName.isNotBlank()

    companion object {
        const val SILENT_RINGTONE = "silent"
    }
}

/** One row in People: a contact, a connected stranger, or a device seen only through discovery. */
data class Person(
    /** Stable key: fingerprint hex, or `svc:<instance>` for a device we haven't connected to yet. */
    val id: String,
    val fingerprint: Fingerprint?,
    val displayName: String,
    val role: String,
    val deviceClass: DeviceClass,
    val presence: Presence,
    val online: Boolean,
    val isContact: Boolean,
    val verified: Boolean,
    val favorite: Boolean,
    val blocked: Boolean,
    val address: PeerAddress?,
    val lastSeenMs: Long?,
    /** A verified contact has the same name but a different key (edge cases D1 / D2). */
    val nameMatchesOtherVerified: Boolean = false,
    val statusText: String = "",
    /** This contact may open push-to-talk with us without ringing. */
    val allowPushToTalk: Boolean = false,
    /** The user marked this contact as one of their own devices. */
    val linkedDevice: Boolean = false,
    /** ...and that device lists us too, so calls to either ring on both. */
    val linkConfirmed: Boolean = false,
    /** This contact may open a desk intercom line to us. */
    val allowIntercom: Boolean = false,
) {
    /** Short code for telling apart people with the same name. */
    val tag: String? get() = fingerprint?.tag
}

sealed interface CoreStatus {
    data object Starting : CoreStatus
    data object NeedsOnboarding : CoreStatus
    data object Running : CoreStatus
    data class Failed(val message: String) : CoreStatus
}

/** A file the user picked to send. [open] is called once, on a background thread. */
class PickedFile(val name: String, val mime: String, val size: Long?, val open: () -> okio.Source)

enum class SendFileResult { Sent, TooLarge, Unreadable }

/** What restoring an encrypted backup did. */
sealed interface RestoreResult {
    data class Restored(val contacts: Int, val messages: Int) : RestoreResult
    data object WrongPassphrase : RestoreResult
    data object NotABackup : RestoreResult
    data object Damaged : RestoreResult
    data object Unreadable : RestoreResult
}

/** Disappearing-message timers offered for a conversation, in seconds (docs/01 §5). */
object DisappearingTimer {
    const val OFF = 0L
    const val HOUR_S = 3_600L
    const val DAY_S = 86_400L
    const val WEEK_S = 604_800L
    val OPTIONS = listOf(OFF, HOUR_S, DAY_S, WEEK_S)
}

enum class CallStartResult { Started, AlreadyInCall, Unreachable, KeyMismatch, Blocked, NotSupported }

enum class KnockResult { Sent, Unreachable, NotSupported, Blocked }

data class VerificationInfo(
    val fingerprint: Fingerprint,
    val displayName: String,
    val role: String,
    val safetyCode: String,
    val safetyNumber: String,
    val isContact: Boolean,
    val verified: Boolean,
)

sealed interface AddResult {
    data class Added(val person: Fingerprint, val name: String) : AddResult
    data object Unreachable : AddResult
    data object KeyMismatch : AddResult
    data object InvalidCode : AddResult
    data object Self : AddResult
}

/** `zoocall://add?v=1&k=<base64url pubkey>&a=<ip:port>[,<ip:port>]&n=<name>` (docs/03-protocol.md §2). */
data class ContactCode(val publicKey: ByteArray, val addresses: List<PeerAddress>, val name: String) {
    fun toUri(): String = buildString {
        append("zoocall://add?v=1&k=").append(Base64Url.encode(publicKey))
        if (addresses.isNotEmpty()) append("&a=").append(addresses.joinToString(",") { it.toString() }.urlEncode())
        if (name.isNotBlank()) append("&n=").append(name.take(64).urlEncode())
    }

    override fun equals(other: Any?) = other is ContactCode && other.publicKey.contentEquals(publicKey) && other.addresses == addresses && other.name == name
    override fun hashCode() = publicKey.contentHashCode()

    companion object {
        fun parse(uri: String): ContactCode? = runCatching {
            val text = uri.trim()
            if (!text.startsWith("zoocall://add?")) return null
            val params = text.substringAfter('?').split('&').associate {
                it.substringBefore('=') to it.substringAfter('=', "").urlDecode()
            }
            if (params["v"] != "1") return null
            val key = Base64Url.decode(params["k"] ?: return null)
            if (key.size != 32) return null
            val addresses = params["a"].orEmpty().split(',').filter { it.isNotBlank() }.take(4).mapNotNull(PeerAddress::parse)
            ContactCode(key, addresses, params["n"].orEmpty().take(64))
        }.getOrNull()

        private fun String.urlEncode(): String = buildString {
            for (byte in this@urlEncode.encodeToByteArray()) {
                val c = byte.toInt().toChar()
                if (c.isLetterOrDigit() && byte >= 0 || c in "-._~:[]") append(c) else append('%').append(((byte.toInt() and 0xff) + 0x100).toString(16).substring(1).uppercase())
            }
        }

        private fun String.urlDecode(): String {
            val out = ArrayList<Byte>()
            var i = 0
            while (i < length) {
                val c = this[i]
                if (c == '%' && i + 2 <= lastIndex) {
                    out += substring(i + 1, i + 3).toInt(16).toByte()
                    i += 3
                } else {
                    out.addAll((if (c == '+') " " else c.toString()).encodeToByteArray().toList())
                    i++
                }
            }
            return out.toByteArray().decodeToString()
        }
    }
}
