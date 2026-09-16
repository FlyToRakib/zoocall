package app.zoocall.core.model

enum class CallKind { Audio, Video }

enum class CallDirection { Outgoing, Incoming }

enum class Presence { Available, Busy, DoNotDisturb, Away }

enum class DeviceClass { Phone, Tablet, Desktop }

/** Everyone sees name and presence; ContactsOnly advertises rotating tags only contacts recognize; Hidden advertises nothing. */
enum class Visibility { Everyone, ContactsOnly, Hidden }

enum class AllowCallsFrom { Everyone, Contacts, Favorites }

/** Why a call ended. Shown in the call screen and in Recents. */
enum class EndReason {
    Completed,
    Declined,
    Busy,
    DoNotDisturb,
    Missed,
    Cancelled,
    Unreachable,
    FailedNetwork,
    FailedMedia,

    /** The callee refused because of a block or "allow calls from". Shown to the caller as "unavailable". */
    NotAllowed,

    /** Rang on several of the user's linked devices and was answered or declined on another one. */
    AnsweredElsewhere,
}

data class PeerAddress(val host: String, val port: Int) {
    init {
        require(port in 1..65_535) { "Invalid port" }
    }

    override fun toString(): String = if (':' in host) "[$host]:$port" else "$host:$port"

    companion object {
        const val DEFAULT_PORT = 47_474

        /** Parses `host`, `host:port`, `[v6]` or `[v6]:port`. Returns null for malformed input. */
        fun parse(input: String): PeerAddress? {
            val text = input.trim()
            if (text.isEmpty()) return null
            return runCatching {
                when {
                    text.startsWith('[') -> {
                        val end = text.indexOf(']')
                        val host = text.substring(1, end)
                        val rest = text.substring(end + 1)
                        val port = if (rest.startsWith(':')) rest.drop(1).toInt() else DEFAULT_PORT
                        PeerAddress(host, port)
                    }
                    text.count { it == ':' } == 1 -> {
                        val (host, port) = text.split(':')
                        PeerAddress(host, port.toInt())
                    }
                    else -> PeerAddress(text, DEFAULT_PORT)
                }
            }.getOrNull()?.takeIf { it.host.isNotBlank() }
        }
    }
}

/** What the local user shares with other people on the network. */
data class Profile(
    val displayName: String,
    val role: String = "",
    val presence: Presence = Presence.Available,
    val deviceClass: DeviceClass,
)

/** A saved contact. Identity is the fingerprint; the name is only a label. */
data class Contact(
    val fingerprint: Fingerprint,
    val displayName: String,
    val role: String,
    val verified: Boolean,
    val favorite: Boolean,
    val blocked: Boolean,
    val lastAddress: PeerAddress?,
    val lastSeenMs: Long?,
    val createdAtMs: Long,
    /** May open push-to-talk with us without ringing (opt-in). */
    val allowPushToTalk: Boolean = false,
    /** The user marked this contact as one of their own devices (linked devices ring together). */
    val linked: Boolean = false,
    /** May open a desk intercom line to us without ringing (opt-in, desktop). */
    val allowIntercom: Boolean = false,
)
