package app.zoocall.core.protocol

object ProtocolConstants {
    const val MAJOR_VERSION = 1

    /** Noise prologue. Binds the protocol major version into every handshake. */
    val PROLOGUE: ByteArray = "zoocall/$MAJOR_VERSION".encodeToByteArray()

    const val SERVICE_TYPE = "_zoocall._tcp."
    const val DEFAULT_PORT = 47_474

    /** Maximum Noise message (length prefix is u16). */
    const val MAX_FRAME = 65_535
    const val AEAD_TAG = 16
    const val MAX_FRAME_PLAINTEXT = MAX_FRAME - AEAD_TAG

    /** Fragment payload size, leaving room for the Fragment envelope overhead. */
    const val FRAGMENT_CHUNK = 60_000

    /** Largest logical control message accepted. Anything larger closes the connection. */
    const val MAX_LOGICAL_MESSAGE = 1 shl 20

    /** Timeouts from docs/03-protocol.md. */
    const val HANDSHAKE_TIMEOUT_MS = 10_000L
    const val KEEPALIVE_INTERVAL_MS = 25_000L
    const val DEAD_PEER_TIMEOUT_MS = 60_000L
    const val INVITE_TRANSPORT_TIMEOUT_MS = 10_000L
    const val RING_TIMEOUT_MS = 45_000L
    const val MEDIA_SETUP_TIMEOUT_MS = 15_000L
    const val RECONNECT_GRACE_MS = 30_000L

    /** Rekey after this much traffic per direction (Noise `Rekey()`). */
    const val REKEY_BYTES = 1L shl 30
    const val REKEY_INTERVAL_MS = 60 * 60 * 1000L
}

/** Capability registry, mirrored in protocol/capabilities.md. */
object Capabilities {
    const val CALL_AUDIO = "call.audio"
    const val CALL_VIDEO = "call.video"
    const val CHAT_V1 = "chat.v1"
    const val CHAT_RECEIPTS = "chat.receipts"
    const val CHAT_TYPING = "chat.typing"
    const val CHAT_VOICE = "chat.voice"
    const val CHAT_REACTIONS = "chat.reactions"
    const val CALL_UPGRADE = "call.upgrade"
    const val CALL_HOLD = "call.hold"
    const val CALL_WAITING = "call.waiting"
    const val FILE_V1 = "file.v1"
    const val KNOCK_V1 = "knock.v1"
    const val PTT_V1 = "ptt.v1"
    const val CALL_GROUP = "call.group"
    const val CALL_SCREEN = "call.screen"
    const val CHAT_ANNOUNCE = "chat.announce"
    const val DEVICE_LINK = "device.link"
    const val CALL_INTERCOM = "call.intercom"
    const val CHAT_DISAPPEAR = "chat.disappear"
    const val CHAT_GROUP = "chat.group"
    const val CALL_RECORD = "call.record"

    val DEFAULT: List<String> = listOf(
        CALL_AUDIO, CALL_VIDEO, CALL_UPGRADE, CALL_HOLD, CALL_WAITING, CALL_GROUP, CALL_SCREEN, CALL_INTERCOM, CALL_RECORD,
        CHAT_V1, CHAT_RECEIPTS, CHAT_TYPING, CHAT_VOICE, CHAT_REACTIONS, CHAT_ANNOUNCE, FILE_V1, KNOCK_V1, PTT_V1,
        DEVICE_LINK, CHAT_DISAPPEAR, CHAT_GROUP,
    )
}

/** File transfer limits (protocol/spec/protocol-v1.md §6). */
object FileLimits {
    /** Plaintext bytes per chunk on a FILE connection. */
    const val CHUNK = 60_000

    /** Concurrent FILE connections per peer (docs/03 §6: control + 2 file). */
    const val MAX_CONNECTIONS_PER_PEER = 2

    /** Largest accepted file. Local networks make big files practical; this only stops nonsense sizes. */
    const val MAX_SIZE = 64L * 1024 * 1024 * 1024

    /** Files from contacts up to this size download without asking (edge case M7). */
    const val AUTO_DOWNLOAD_MAX = 100L * 1024 * 1024
}
