package app.zoocall.core.chat

import app.zoocall.core.model.AttachmentMeta
import app.zoocall.core.model.Fingerprint
import app.zoocall.core.model.Logger
import app.zoocall.core.model.MessageId
import app.zoocall.core.model.MessageKind
import app.zoocall.core.model.TransferState
import app.zoocall.core.model.VoiceClip
import app.zoocall.core.protocol.Capabilities
import app.zoocall.core.protocol.FileLimits
import app.zoocall.core.store.AttachmentRepository
import app.zoocall.core.store.ChatMessageRecord
import app.zoocall.core.store.MessageRepository
import app.zoocall.core.store.MessageState
import app.zoocall.core.store.GroupChat
import app.zoocall.core.store.GroupChatRepository
import app.zoocall.protocol.v1.GroupChatInfo
import app.zoocall.core.transport.ConnectionManager
import app.zoocall.core.transport.TransportEvent
import app.zoocall.protocol.v1.Attachment
import app.zoocall.protocol.v1.ChatMessage
import app.zoocall.protocol.v1.ChatReceipt
import app.zoocall.protocol.v1.Envelope
import app.zoocall.protocol.v1.Reaction
import app.zoocall.protocol.v1.Typing
import app.zoocall.protocol.v1.VoiceNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.toByteString
import kotlin.time.Clock

/** A message that just arrived, for notifications. */
data class IncomingMessage(
    val from: Fingerprint,
    val id: MessageId,
    val text: String,
    val isVoice: Boolean = false,
    val isFile: Boolean = false,
    /** A group announcement: alert once, even while the app is open. */
    val isAnnouncement: Boolean = false,
    /** Do Not Disturb: show it without sound. */
    val quiet: Boolean = false,
    /** Set for a message in a group chat. */
    val groupId: String? = null,
    val groupName: String? = null,
)

/**
 * 1:1 chat (text, voice notes, file messages, replies and reactions) with at-least-once delivery:
 * messages stay in the outbox (state `sending`) until the peer is connected, and move to
 * `delivered`/`read` when receipts arrive. File bytes are moved by the files module.
 */
class ChatManager(
    private val scope: CoroutineScope,
    private val transport: ConnectionManager,
    private val messages: MessageRepository,
    private val attachments: AttachmentRepository,
    private val readReceiptsEnabled: () -> Boolean = { true },
    /** Whether alerts from this person should be silent (Do Not Disturb). */
    private val isQuiet: (Fingerprint) -> Boolean = { false },
    /** Called once for each new incoming file message (e.g. to start an automatic download). */
    private val onAttachmentReceived: suspend (Fingerprint, MessageId, AttachmentMeta) -> Unit = { _, _, _ -> },
    /** The conversation's disappearing-message timer in seconds; 0 keeps messages. */
    private val disappearAfterS: suspend (Fingerprint) -> Long = { 0 },
    /** A peer's message carried a different timer (chat.disappear): the chat follows it, like the sender's. */
    private val onPeerTimer: suspend (Fingerprint, Long) -> Unit = { _, _ -> },
    /** Small group chats (chat.group); null turns them off. */
    private val groupChats: GroupChatRepository? = null,
    private val logger: Logger = Logger.current,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val flushLocks = HashMap<Fingerprint, Mutex>()
    private val rateWindows = HashMap<Fingerprint, ArrayDeque<Long>>()
    private val typingExpiry = HashMap<Fingerprint, Job>()
    private val lastTypingSent = HashMap<Fingerprint, Long>()

    private val _typing = MutableStateFlow<Set<Fingerprint>>(emptySet())
    val typing: StateFlow<Set<Fingerprint>> = _typing.asStateFlow()

    private val _incoming = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    val incoming: SharedFlow<IncomingMessage> = _incoming.asSharedFlow()

    fun start() {
        scope.launch {
            transport.events.collect { event ->
                try {
                    when (event) {
                        is TransportEvent.Connected -> scope.launch { flushOutbox(event.peer.fingerprint) }
                        is TransportEvent.Received -> onEnvelope(event.from, event.envelope)
                        is TransportEvent.Disconnected -> setTyping(event.fingerprint, false)
                        is TransportEvent.Updated -> Unit
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logger.error(TAG, "Chat event handling failed", e)
                }
            }
        }
    }

    /** Stores the message and sends it now if possible, otherwise when the peer reappears. */
    suspend fun send(peer: Fingerprint, text: String, replyTo: MessageId? = null, announcement: Boolean = false): MessageId? {
        val body = text.trim().take(MAX_TEXT)
        if (body.isEmpty()) return null
        val t = now()
        val id = MessageId.generate(t)
        messages.insert(
            ChatMessageRecord(
                id, peer, outgoing = true, body = body, state = MessageState.Sending, sentAtMs = t, receivedAtMs = t,
                replyTo = replyTo, announcement = announcement, expiresAtMs = expiryFor(peer, t),
            ),
        )
        lastTypingSent.remove(peer)
        flushOutbox(peer)
        return id
    }

    /** Stores and sends a voice note. Returns null when the clip is outside the allowed size/duration. */
    suspend fun sendVoice(peer: Fingerprint, clip: VoiceClip, replyTo: MessageId? = null, announcement: Boolean = false): MessageId? {
        if (!clip.isAcceptable()) {
            logger.warn(TAG, "Voice clip rejected: ${clip.audio.size} bytes, ${clip.durationMs} ms")
            return null
        }
        val t = now()
        val id = MessageId.generate(t)
        messages.insert(
            ChatMessageRecord(
                id, peer, outgoing = true, body = "", state = MessageState.Sending, sentAtMs = t, receivedAtMs = t,
                replyTo = replyTo, kind = MessageKind.Voice, durationMs = clip.durationMs, announcement = announcement,
                expiresAtMs = expiryFor(peer, t),
            ),
            voice = clip,
        )
        flushOutbox(peer)
        return id
    }

    /**
     * Sends a file message for a file already imported under [id] (see the files module).
     * The receiver fetches the bytes over a FILE connection.
     */
    suspend fun sendAttachment(peer: Fingerprint, id: MessageId, meta: AttachmentMeta, replyTo: MessageId? = null, fileKey: ByteArray? = null) {
        val t = now()
        messages.insert(
            ChatMessageRecord(
                id, peer, outgoing = true, body = meta.name, state = MessageState.Sending, sentAtMs = t, receivedAtMs = t,
                replyTo = replyTo, kind = MessageKind.File, expiresAtMs = expiryFor(peer, t),
            ),
            attachment = meta,
            attachmentState = TransferState.Done,
            fileKey = fileKey,
        )
        flushOutbox(peer)
    }

    private suspend fun expiryFor(peer: Fingerprint, sentAtMs: Long): Long? =
        disappearAfterS(peer).coerceIn(0, MAX_EXPIRY_S).takeIf { it > 0 }?.let { sentAtMs + it * 1_000 }

    /** Sets our reaction on a message, or removes it when [emoji] is null. Sent now or when the peer reconnects. */
    suspend fun react(peer: Fingerprint, id: MessageId, emoji: String?) {
        val message = messages.message(id) ?: return
        if (message.peer != peer) return
        val value = emoji?.trim().orEmpty()
        if (value.length > MAX_EMOJI) return
        messages.setReaction(id, fromSelf = true, emoji = value, pending = true)
        flushReactions(peer)
    }

    /** Deletes a message on this device only. Returns whether it had an attachment file. */
    suspend fun deleteMessage(id: MessageId): Boolean = messages.deleteMessage(id)

    /** Marks the conversation read locally and, if enabled, tells the peer. */
    suspend fun markRead(peer: Fingerprint) {
        val ids = messages.markSeen(peer)
        if (ids.isNotEmpty() && readReceiptsEnabled()) {
            transport.send(peer, Envelope(receipt = ChatReceipt(ids = ids.map { it.value }, state = ChatReceipt.State.STATE_READ)))
        }
    }

    /** Call on each keystroke; throttled to one signal per few seconds. */
    suspend fun onUserTyping(peer: Fingerprint, active: Boolean) {
        val t = now()
        if (active && t - (lastTypingSent[peer] ?: 0) < TYPING_THROTTLE_MS) return
        lastTypingSent[peer] = if (active) t else 0
        if (transport.isConnected(peer)) transport.send(peer, Envelope(typing = Typing(active = active)))
    }

    suspend fun flushOutbox(peer: Fingerprint) {
        if (!transport.isConnected(peer)) return
        val lock = flushLocks.getOrPut(peer) { Mutex() }
        lock.withLock {
            for (record in messages.outbox(peer)) {
                val message = toWire(record) ?: continue
                if (!transport.send(peer, Envelope(chat = message))) break
                messages.advanceOutgoing(peer, record.id, MessageState.Sent)
            }
            // Group messages go to each member on their own connection (docs/01 §5: fan-out from the sender).
            val groups = groupChats
            if (groups != null) {
                for (record in messages.groupOutbox(peer)) {
                    val group = record.groupId?.let { groups.get(it) } ?: continue
                    val message = toWire(record)?.copy(group = group.toWire()) ?: continue
                    if (!transport.send(peer, Envelope(chat = message))) break
                    messages.advanceGroupRecipient(record.id, peer, MessageState.Sent)
                }
            }
        }
        flushReactions(peer)
    }

    private suspend fun toWire(record: ChatMessageRecord): ChatMessage? {
        val base = ChatMessage(
            id = record.id.value,
            sent_at_ms = record.sentAtMs,
            reply_to = record.replyTo?.value.orEmpty(),
            announcement = record.announcement,
            expires_in_s = record.expiresAtMs?.let { ((it - record.sentAtMs) / 1_000).coerceIn(1, MAX_EXPIRY_S).toInt() } ?: 0,
        )
        return when (record.kind) {
            MessageKind.Text -> base.copy(text = record.body)
            MessageKind.Voice -> {
                val clip = messages.voiceClip(record.id) ?: return null
                base.copy(
                    voice_note = VoiceNote(
                        audio = clip.audio.toByteString(),
                        duration_ms = clip.durationMs.toInt(),
                        waveform = clip.waveform.toByteString(),
                        codec = VoiceClip.CODEC,
                    ),
                )
            }
            MessageKind.File -> {
                val meta = attachments.get(record.id)?.meta ?: return null
                base.copy(
                    attachment = Attachment(
                        file_id = record.id.value,
                        name = meta.name,
                        mime = meta.mime,
                        size = meta.size,
                        blake2b_256 = meta.hash.toByteString(),
                    ),
                )
            }
        }
    }

    private suspend fun flushReactions(peer: Fingerprint) {
        if (!transport.isConnected(peer)) return
        for (reaction in messages.pendingReactions(peer)) {
            val envelope = Envelope(
                reaction = Reaction(message_id = reaction.messageId.value, emoji = reaction.emoji, remove = reaction.emoji.isEmpty()),
            )
            if (!transport.send(peer, envelope)) break
            messages.markReactionSent(reaction.messageId, reaction.emoji)
        }
    }

    private suspend fun onEnvelope(from: Fingerprint, envelope: Envelope) {
        val chat = envelope.chat
        val receipt = envelope.receipt
        val typing = envelope.typing
        val reaction = envelope.reaction
        when {
            chat != null -> onChat(from, chat)
            receipt != null -> {
                val state = when (receipt.state) {
                    ChatReceipt.State.STATE_DELIVERED -> MessageState.Delivered
                    ChatReceipt.State.STATE_READ -> MessageState.Read
                    else -> return
                }
                receipt.ids.take(MAX_RECEIPT_IDS).forEach { id ->
                    if (isValidId(id)) {
                        messages.advanceOutgoing(from, MessageId(id), state)
                        messages.advanceGroupRecipient(MessageId(id), from, state)
                    }
                }
            }
            typing != null -> setTyping(from, typing.active)
            reaction != null -> onReaction(from, reaction)
        }
    }

    private suspend fun onReaction(from: Fingerprint, reaction: Reaction) {
        if (!isValidId(reaction.message_id) || reaction.emoji.length > MAX_EMOJI || !allowMessage(from)) return
        val id = MessageId(reaction.message_id)
        // Only reactions to messages in the conversation with this peer.
        if (messages.message(id)?.peer != from) return
        val emoji = if (reaction.remove) "" else reaction.emoji.filterNot { it.isISOControl() }.trim()
        messages.setReaction(id, fromSelf = false, emoji = emoji, pending = false)
    }

    private suspend fun onChat(from: Fingerprint, chat: ChatMessage) {
        if (!isValidId(chat.id)) return
        val voiceNote = chat.voice_note
        val voice = voiceNote?.let {
            VoiceClip(it.audio.toByteArray(), it.duration_ms.toLong(), it.waveform.toByteArray())
                .takeIf { clip -> it.codec == VoiceClip.CODEC && clip.isAcceptable() }
                ?: run {
                    logger.warn(TAG, "Unsupported or oversized voice note ignored")
                    null
                }
        }
        val attachment = chat.attachment?.let { toMeta(chat.id, it) }
        val groupInfo = chat.group
        if (voice == null && attachment == null && chat.text.isBlank() && groupInfo == null) return
        if (!allowMessage(from)) {
            logger.warn(TAG, "Chat rate limit hit")
            return
        }
        val groupId = if (groupInfo != null) acceptGroup(from, groupInfo) ?: return else null
        // A group change carries no content; group chats don't carry files.
        if (voice == null && attachment == null && chat.text.isBlank()) return
        if (groupId != null && attachment != null) return
        val t = now()
        val id = MessageId(chat.id)
        // The timer counts from when it arrived here, so a message that waited in an outbox isn't gone on arrival.
        val expiresInS = chat.expires_in_s.toLong().coerceIn(0, MAX_EXPIRY_S)
        val kind = when {
            voice != null -> MessageKind.Voice
            attachment != null -> MessageKind.File
            else -> MessageKind.Text
        }
        val body = when (kind) {
            MessageKind.Voice -> ""
            MessageKind.File -> attachment!!.name
            MessageKind.Text -> chat.text.take(MAX_TEXT)
        }
        val inserted = messages.insert(
            ChatMessageRecord(
                id = id,
                peer = from,
                outgoing = false,
                body = body,
                state = MessageState.Received,
                sentAtMs = chat.sent_at_ms,
                // Display order uses local receive time (edge case M4: clock skew).
                receivedAtMs = t,
                replyTo = chat.reply_to.takeIf(::isValidId)?.let(::MessageId),
                kind = kind,
                durationMs = voice?.durationMs,
                announcement = chat.announcement && attachment == null,
                expiresAtMs = if (expiresInS > 0 && groupId == null) t + expiresInS * 1_000 else null,
                groupId = groupId,
            ),
            voice = voice,
            attachment = attachment,
        )
        // Always acknowledge, even duplicates, so the sender stops retrying (edge case M3).
        transport.send(from, Envelope(receipt = ChatReceipt(ids = listOf(chat.id), state = ChatReceipt.State.STATE_DELIVERED)))
        setTyping(from, false)
        // Older apps send no timer at all, so only a peer that supports it can change ours.
        if (inserted && groupId == null && transport.peers.value[from]?.hello?.capabilities?.contains(Capabilities.CHAT_DISAPPEAR) == true &&
            disappearAfterS(from) != expiresInS
        ) {
            onPeerTimer(from, expiresInS)
        }
        if (inserted) {
            _incoming.tryEmit(
                IncomingMessage(
                    from, id, body,
                    isVoice = voice != null,
                    isFile = attachment != null,
                    isAnnouncement = chat.announcement && attachment == null,
                    quiet = isQuiet(from),
                    groupId = groupId,
                    groupName = groupId?.let { groupChats?.get(it)?.name },
                ),
            )
            if (attachment != null) onAttachmentReceived(from, id, attachment)
        }
    }

    /** Validates an incoming attachment description. Invalid ones drop the whole message. */
    private fun toMeta(messageId: String, attachment: Attachment): AttachmentMeta? {
        if (attachment.file_id != messageId || attachment.size < 0 || attachment.size > FileLimits.MAX_SIZE ||
            attachment.blake2b_256.size != AttachmentMeta.HASH_BYTES
        ) {
            logger.warn(TAG, "Invalid attachment ignored")
            return null
        }
        return AttachmentMeta(
            name = AttachmentMeta.sanitizeName(attachment.name),
            mime = AttachmentMeta.sanitizeMime(attachment.mime),
            size = attachment.size,
            hash = attachment.blake2b_256.toByteArray(),
        )
    }

    private fun setTyping(peer: Fingerprint, active: Boolean) {
        typingExpiry.remove(peer)?.cancel()
        _typing.update { if (active) it + peer else it - peer }
        if (active) {
            typingExpiry[peer] = scope.launch {
                delay(TYPING_EXPIRY_MS)
                _typing.update { it - peer }
            }
        }
    }

    // -- Group chats (chat.group) -----------------------------------------------------------------

    private val self: Fingerprint get() = transport.localFingerprint

    /** Sends a text to a group chat: stored once here, then queued for each member separately. */
    suspend fun sendToGroup(groupId: String, text: String, replyTo: MessageId? = null): MessageId? {
        val group = groupChats?.get(groupId)?.takeIf { !it.left } ?: return null
        val body = text.trim().take(MAX_TEXT)
        if (body.isEmpty()) return null
        val t = now()
        val id = MessageId.generate(t)
        val record = ChatMessageRecord(
            id, self, outgoing = true, body = body, state = MessageState.Sending, sentAtMs = t, receivedAtMs = t,
            replyTo = replyTo, groupId = groupId,
        )
        messages.insertGroupMessage(record, recipients = group.members.filter { it != self })
        flushGroup(group)
        return id
    }

    suspend fun sendVoiceToGroup(groupId: String, clip: VoiceClip, replyTo: MessageId? = null): MessageId? {
        val group = groupChats?.get(groupId)?.takeIf { !it.left } ?: return null
        if (!clip.isAcceptable()) return null
        val t = now()
        val id = MessageId.generate(t)
        val record = ChatMessageRecord(
            id, self, outgoing = true, body = "", state = MessageState.Sending, sentAtMs = t, receivedAtMs = t,
            replyTo = replyTo, kind = MessageKind.Voice, durationMs = clip.durationMs, groupId = groupId,
        )
        messages.insertGroupMessage(record, recipients = group.members.filter { it != self }, voice = clip)
        flushGroup(group)
        return id
    }

    /**
     * Saves a new or changed group chat. The change goes straight to [notify] (e.g. the remaining
     * members when leaving); everyone else learns it with the group's next message.
     */
    suspend fun saveGroup(chat: GroupChat, notify: Collection<Fingerprint> = emptyList()) {
        val groups = groupChats ?: return
        groups.save(chat)
        if (notify.isEmpty()) return
        val change = ChatMessage(id = MessageId.generate(now()).value, sent_at_ms = now(), group = chat.toWire())
        notify.filter { it != self && transport.isConnected(it) }.forEach { transport.send(it, Envelope(chat = change)) }
    }

    /** Marks a group chat read and, if enabled, tells each sender. */
    suspend fun markGroupRead(groupId: String) {
        val seen = messages.markGroupSeen(groupId)
        if (seen.isEmpty() || !readReceiptsEnabled()) return
        seen.groupBy({ it.second }, { it.first }).forEach { (sender, ids) ->
            transport.send(sender, Envelope(receipt = ChatReceipt(ids = ids.map { it.value }, state = ChatReceipt.State.STATE_READ)))
        }
    }

    private suspend fun flushGroup(group: GroupChat) {
        group.members.filter { it != self }.forEach { flushOutbox(it) }
    }

    private fun GroupChat.toWire() = GroupChatInfo(id = id, name = name, members = members.map { it.hex }, updated_at_ms = updatedAtMs)

    /**
     * Files an incoming group message under its group, creating or updating the group from it.
     * Returns null (drop it) when the group is invalid, we left it, or the sender can't change it:
     * only someone already in the group changes it, and the newest change wins.
     */
    private suspend fun acceptGroup(from: Fingerprint, info: GroupChatInfo): String? {
        val groups = groupChats ?: return null
        if (!isValidId(info.id) || info.members.size > MAX_GROUP_MEMBERS) return null
        val members = info.members.mapNotNull { runCatching { Fingerprint.fromHex(it) }.getOrNull() }.distinct()
        val name = info.name.filterNot { it.isISOControl() }.trim().take(MAX_GROUP_NAME)
        if (name.isEmpty()) return null
        val existing = groups.get(info.id)
        return when {
            existing == null -> if (from in members && self in members) {
                groups.save(GroupChat(info.id, name, members, info.updated_at_ms))
                info.id
            } else {
                null
            }
            existing.left || from !in existing.members -> null
            info.updated_at_ms > existing.updatedAtMs -> {
                val stillIn = self in members
                groups.save(GroupChat(info.id, name, members, info.updated_at_ms, left = !stillIn))
                if (stillIn) info.id else null
            }
            else -> info.id
        }
    }

    private fun allowMessage(from: Fingerprint): Boolean {
        val t = now()
        val window = rateWindows.getOrPut(from) { ArrayDeque() }
        while (window.isNotEmpty() && t - window.first() > RATE_WINDOW_MS) window.removeFirst()
        if (window.size >= RATE_LIMIT) return false
        window.addLast(t)
        return true
    }

    private fun isValidId(id: String) = id.length == 26 && id.all { it.isLetterOrDigit() }

    private companion object {
        const val TAG = "Chat"
        const val MAX_TEXT = 8_000

        /** Longest disappearing-message timer accepted: 4 weeks. */
        const val MAX_EXPIRY_S = 28L * 24 * 3_600
        const val MAX_EMOJI = 16
        const val MAX_GROUP_MEMBERS = 32
        const val MAX_GROUP_NAME = 40
        const val MAX_RECEIPT_IDS = 500
        const val RATE_LIMIT = 30
        const val RATE_WINDOW_MS = 10_000L
        const val TYPING_THROTTLE_MS = 3_000L
        const val TYPING_EXPIRY_MS = 6_000L
    }
}
