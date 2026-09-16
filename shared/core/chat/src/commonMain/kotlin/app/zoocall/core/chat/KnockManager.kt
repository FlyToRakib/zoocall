package app.zoocall.core.chat

import app.zoocall.core.model.Fingerprint
import app.zoocall.core.model.Logger
import app.zoocall.core.model.MessageId
import app.zoocall.core.transport.ConnectionManager
import app.zoocall.core.transport.TransportEvent
import app.zoocall.protocol.v1.Envelope
import app.zoocall.protocol.v1.Knock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

enum class KnockReply { CallMe, TwoMinutes, Busy }

sealed interface KnockEvent {
    val from: Fingerprint

    /** Someone knocked. [quiet]: show it without sound (Do Not Disturb). */
    data class Received(override val from: Fingerprint, val id: String, val text: String, val quiet: Boolean) : KnockEvent

    /** An answer to one of our own knocks. */
    data class Replied(override val from: Fingerprint, val knockId: String, val reply: KnockReply) : KnockEvent
}

/**
 * Knock (capability `knock.v1`): a live "Are you free?" nudge with one-tap replies. Nothing is
 * stored or queued; a knock only reaches a connected peer (docs/01-features.md §4, protocol §6 limits).
 */
class KnockManager(
    private val scope: CoroutineScope,
    private val transport: ConnectionManager,
    private val isContact: (Fingerprint) -> Boolean,
    private val isQuiet: (Fingerprint) -> Boolean = { false },
    private val logger: Logger = Logger.current,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = Mutex()
    /** Our recent knocks by id, so only the knocked peer can answer them. */
    private val sent = HashMap<String, Sent>()
    private val received = HashMap<Fingerprint, ArrayDeque<Long>>()

    private class Sent(val peer: Fingerprint, val atMs: Long)

    private val _events = MutableSharedFlow<KnockEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<KnockEvent> = _events.asSharedFlow()

    fun start() {
        scope.launch {
            transport.events.collect { event ->
                val knock = (event as? TransportEvent.Received)?.envelope?.knock ?: return@collect
                try {
                    onKnock(event.from, knock)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logger.error(TAG, "Knock handling failed", e)
                }
            }
        }
    }

    /** Returns the knock id, or null when the peer isn't connected. */
    suspend fun knock(peer: Fingerprint, text: String = ""): String? {
        if (!transport.isConnected(peer)) return null
        val t = now()
        val id = MessageId.generate(t).value
        if (!transport.send(peer, Envelope(knock = Knock(id = id, text = clean(text))))) return null
        lock.withLock {
            prune(t)
            sent[id] = Sent(peer, t)
        }
        return id
    }

    suspend fun reply(peer: Fingerprint, knockId: String, reply: KnockReply): Boolean {
        if (!isValidId(knockId) || !transport.isConnected(peer)) return false
        val knock = Knock(id = MessageId.generate(now()).value, in_reply_to = knockId, reply = reply.toProto())
        return transport.send(peer, Envelope(knock = knock))
    }

    private suspend fun onKnock(from: Fingerprint, knock: Knock) {
        if (!isValidId(knock.id)) return
        if (knock.in_reply_to.isNotEmpty()) {
            val reply = knock.reply.toModel() ?: return
            val ours = lock.withLock {
                prune(now())
                sent[knock.in_reply_to]?.peer == from
            }
            if (ours) _events.tryEmit(KnockEvent.Replied(from, knock.in_reply_to, reply))
            return
        }
        if (!allow(from)) {
            logger.warn(TAG, "Knock rate limit hit")
            return
        }
        _events.tryEmit(KnockEvent.Received(from, knock.id, clean(knock.text), quiet = isQuiet(from)))
    }

    /** Protocol §6: one knock a minute from a stranger; a few more from contacts. */
    private suspend fun allow(from: Fingerprint): Boolean = lock.withLock {
        val t = now()
        val window = received.getOrPut(from) { ArrayDeque() }
        while (window.isNotEmpty() && t - window.first() > RATE_WINDOW_MS) window.removeFirst()
        if (window.size >= if (isContact(from)) CONTACT_LIMIT else STRANGER_LIMIT) return@withLock false
        window.addLast(t)
        true
    }

    private fun prune(t: Long) {
        sent.entries.removeAll { t - it.value.atMs > REPLY_WINDOW_MS }
    }

    private fun clean(text: String) = text.filterNot { it.isISOControl() }.trim().take(MAX_TEXT)

    private fun isValidId(id: String) = id.length == 26 && id.all { it.isLetterOrDigit() }

    private fun KnockReply.toProto() = when (this) {
        KnockReply.CallMe -> Knock.Reply.REPLY_CALL_ME
        KnockReply.TwoMinutes -> Knock.Reply.REPLY_TWO_MINUTES
        KnockReply.Busy -> Knock.Reply.REPLY_BUSY
    }

    private fun Knock.Reply.toModel() = when (this) {
        Knock.Reply.REPLY_CALL_ME -> KnockReply.CallMe
        Knock.Reply.REPLY_TWO_MINUTES -> KnockReply.TwoMinutes
        Knock.Reply.REPLY_BUSY -> KnockReply.Busy
        else -> null
    }

    private companion object {
        const val TAG = "Knock"
        const val MAX_TEXT = 120
        const val RATE_WINDOW_MS = 60_000L
        const val STRANGER_LIMIT = 1
        const val CONTACT_LIMIT = 5
        const val REPLY_WINDOW_MS = 10 * 60_000L
    }
}
