package app.zoocall.core.store

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.db.SqlDriver
import app.zoocall.core.model.AttachmentInfo
import app.zoocall.core.model.AttachmentMeta
import app.zoocall.core.model.CallDirection
import app.zoocall.core.model.CallKind
import app.zoocall.core.model.Contact
import app.zoocall.core.model.EndReason
import app.zoocall.core.model.Fingerprint
import app.zoocall.core.model.MessageId
import app.zoocall.core.model.MessageKind
import app.zoocall.core.model.PeerAddress
import app.zoocall.core.model.TransferState
import app.zoocall.core.model.VoiceClip
import app.zoocall.core.store.db.ZoocallDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import app.zoocall.core.store.db.Contact as ContactRow
import app.zoocall.core.store.db.Conversation as ConversationRow
import app.zoocall.core.store.db.Message as MessageRow

/** Opens the encrypted database. Platform implementations supply the SQLCipher driver. */
fun interface DatabaseDriverFactory {
    fun create(key: ByteArray): SqlDriver
}

class ZoocallStore(driver: SqlDriver, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val db = ZoocallDatabase(driver)

    val contacts = ContactRepository(db, io)
    val messages = MessageRepository(db, io)
    val attachments = AttachmentRepository(db, io)
    val calls = CallLogRepository(db, io)
    val settings = SettingsRepository(db, io)
    val groups = GroupRepository(db, io)
    val groupChats = GroupChatRepository(db, io)
}

class GroupRepository internal constructor(private val db: ZoocallDatabase, private val io: CoroutineDispatcher) {
    private val q get() = db.contactGroupQueries

    /** Groups sorted by name, members in insertion order. */
    val all: Flow<List<ContactGroup>> = q.groupsWithMembers().asFlow().mapToList(io).map { rows ->
        rows.groupBy { it.id }.map { (id, members) ->
            ContactGroup(id, members.first().name, members.mapNotNull { m -> m.fingerprint?.let(Fingerprint::fromHex) }, members.first().created_at_ms)
        }
    }

    suspend fun get(id: String): ContactGroup? = withContext(io) {
        val rows = q.groupWithMembers(id).executeAsList()
        rows.firstOrNull()?.let { first ->
            ContactGroup(first.id, first.name, rows.mapNotNull { m -> m.fingerprint?.let(Fingerprint::fromHex) }, first.created_at_ms)
        }
    }

    /** Returns the new group's id. */
    suspend fun create(name: String, members: List<Fingerprint>, nowMs: Long): String = withContext(io) {
        val id = MessageId.generate(nowMs).value
        db.transaction {
            q.insertGroup(id, name, nowMs)
            members.forEach { q.addMember(id, it.hex) }
        }
        id
    }

    suspend fun rename(id: String, name: String) = withContext(io) { q.renameGroup(name, id) }

    suspend fun setMembers(id: String, members: List<Fingerprint>) = withContext(io) {
        db.transaction {
            q.deleteMembers(id)
            members.forEach { q.addMember(id, it.hex) }
        }
    }

    suspend fun delete(id: String) = withContext(io) {
        db.transaction {
            q.deleteMembers(id)
            q.deleteGroup(id)
        }
    }

    /** A removed contact leaves every group. */
    suspend fun removeEverywhere(fp: Fingerprint) = withContext(io) { q.removeFromAllGroups(fp.hex) }
}

class GroupChatRepository internal constructor(private val db: ZoocallDatabase, private val io: CoroutineDispatcher) {
    private val q get() = db.groupChatQueries

    /** Group chats we're still in, by name. */
    val all: Flow<List<GroupChat>> = q.selectAll().asFlow().mapToList(io).map { rows -> rows.map { it.toModel() } }

    fun observe(id: String): Flow<GroupChat?> = all.map { chats -> chats.firstOrNull { it.id == id } }

    suspend fun list(): List<GroupChat> = withContext(io) { q.selectAll().executeAsList().map { it.toModel() } }

    suspend fun get(id: String): GroupChat? = withContext(io) { q.selectById(id).executeAsOneOrNull()?.toModel() }

    suspend fun save(chat: GroupChat) = withContext(io) {
        q.upsert(chat.id, chat.name, chat.members.joinToString(" ") { it.hex }, chat.updatedAtMs, chat.left.toLong())
    }

    private fun app.zoocall.core.store.db.Group_chat.toModel() = GroupChat(
        id = id,
        name = name,
        members = members.split(' ').filter { it.isNotEmpty() }.mapNotNull { runCatching { Fingerprint.fromHex(it) }.getOrNull() },
        updatedAtMs = updated_at_ms,
        left = left_group == 1L,
    )
}

class ContactRepository internal constructor(private val db: ZoocallDatabase, private val io: CoroutineDispatcher) {
    private val q get() = db.contactQueries

    val all: Flow<List<Contact>> = q.selectAll().asFlow().mapToList(io).map { rows -> rows.map { it.toModel() } }

    suspend fun get(fp: Fingerprint): Contact? = withContext(io) { q.selectByFingerprint(fp.hex).executeAsOneOrNull()?.toModel() }

    suspend fun publicKey(fp: Fingerprint): ByteArray? = withContext(io) { q.selectByFingerprint(fp.hex).executeAsOneOrNull()?.public_key }

    suspend fun add(fp: Fingerprint, publicKey: ByteArray, name: String, role: String, verified: Boolean, address: PeerAddress?, nowMs: Long) =
        withContext(io) {
            db.transaction {
                q.insert(fp.hex, publicKey, name, role, verified.toLong(), address?.host, address?.port?.toLong(), nowMs, nowMs)
                if (verified) q.setVerified(1, fp.hex)
            }
        }

    suspend fun updateProfile(fp: Fingerprint, name: String, role: String) = withContext(io) { q.updateProfile(name, role, fp.hex) }
    suspend fun updateSeen(fp: Fingerprint, address: PeerAddress?, nowMs: Long) =
        withContext(io) { q.updateSeen(address?.host, address?.port?.toLong(), nowMs, fp.hex) }
    suspend fun setVerified(fp: Fingerprint, verified: Boolean) = withContext(io) { q.setVerified(verified.toLong(), fp.hex) }
    suspend fun setFavorite(fp: Fingerprint, favorite: Boolean) = withContext(io) { q.setFavorite(favorite.toLong(), fp.hex) }
    suspend fun setBlocked(fp: Fingerprint, blocked: Boolean) = withContext(io) { q.setBlocked(blocked.toLong(), fp.hex) }
    suspend fun setAllowPushToTalk(fp: Fingerprint, allow: Boolean) = withContext(io) { q.setAllowPtt(allow.toLong(), fp.hex) }
    suspend fun setLinked(fp: Fingerprint, linked: Boolean) = withContext(io) { q.setLinked(linked.toLong(), fp.hex) }
    suspend fun setAllowIntercom(fp: Fingerprint, allow: Boolean) = withContext(io) { q.setAllowIntercom(allow.toLong(), fp.hex) }
    suspend fun delete(fp: Fingerprint) = withContext(io) { q.delete(fp.hex) }

    private fun ContactRow.toModel(): Contact {
        val host = last_host
        val port = last_port
        return Contact(
            fingerprint = Fingerprint.fromHex(fingerprint),
            displayName = display_name,
            role = role,
            verified = verified == 1L,
            favorite = favorite == 1L,
            blocked = blocked == 1L,
            allowPushToTalk = allow_ptt == 1L,
            linked = linked == 1L,
            allowIntercom = allow_intercom == 1L,
            lastAddress = if (host != null && port != null) runCatching { PeerAddress(host, port.toInt()) }.getOrNull() else null,
            lastSeenMs = last_seen_ms,
            createdAtMs = created_at_ms,
        )
    }
}

enum class MessageState(val dbValue: String) {
    Sending("sending"), Sent("sent"), Delivered("delivered"), Read("read"),
    Received("received"), Seen("seen");

    companion object {
        fun from(value: String) = entries.firstOrNull { it.dbValue == value } ?: Sending
    }
}

data class ChatMessageRecord(
    val id: MessageId,
    val peer: Fingerprint,
    val outgoing: Boolean,
    val body: String,
    val state: MessageState,
    val sentAtMs: Long,
    val receivedAtMs: Long,
    val replyTo: MessageId?,
    val kind: MessageKind = MessageKind.Text,
    val durationMs: Long? = null,
    val attachment: AttachmentInfo? = null,
    /** A broadcast announcement to a contact group. */
    val announcement: Boolean = false,
    /** Disappearing messages: when this device deletes it; null keeps it. */
    val expiresAtMs: Long? = null,
    /** The group chat it belongs to; null in a 1:1 conversation. */
    val groupId: String? = null,
)

/** A local contact group. Only this device knows it exists. */
data class ContactGroup(val id: String, val name: String, val members: List<Fingerprint>, val createdAtMs: Long)

/** A small group chat shared by its members (≤ 32, including us). */
data class GroupChat(val id: String, val name: String, val members: List<Fingerprint>, val updatedAtMs: Long, val left: Boolean = false)

/** How far one of our group messages got: [total] members, of whom [sent] were sent it, [delivered] have it, [read] read it. */
data class GroupDelivery(val total: Int, val sent: Int, val delivered: Int, val read: Int)

data class ConversationSummary(
    val peer: Fingerprint,
    val lastBody: String,
    val lastOutgoing: Boolean,
    val lastState: MessageState,
    val lastAtMs: Long,
    val unread: Int,
    val lastKind: MessageKind = MessageKind.Text,
    /** Whether we ever wrote in this conversation. Strangers' conversations without it are requests (edge case M10). */
    val hasOutgoing: Boolean = true,
    /** Set for a group chat; [peer] is then whoever wrote the last message. */
    val groupId: String? = null,
    val groupName: String? = null,
)

data class ReactionRecord(val messageId: MessageId, val fromSelf: Boolean, val emoji: String)

class MessageRepository internal constructor(private val db: ZoocallDatabase, private val io: CoroutineDispatcher) {
    private val q get() = db.messageQueries
    private val reactionsQ get() = db.reactionQueries

    fun conversation(peer: Fingerprint): Flow<List<ChatMessageRecord>> =
        q.conversation(groupId = null, peer = peer.hex).asFlow().mapToList(io).map { rows -> rows.map { it.toModel() } }

    fun reactions(peer: Fingerprint): Flow<Map<MessageId, List<ReactionRecord>>> =
        reactionsQ.forPeer(peer.hex).asFlow().mapToList(io).map { rows ->
            rows.map { ReactionRecord(MessageId(it.message_id), it.from_self == 1L, it.emoji) }.groupBy { it.messageId }
        }

    val conversations: Flow<List<ConversationSummary>> = q.conversations().asFlow().mapToList(io).map { rows ->
        rows.map {
            ConversationSummary(
                peer = Fingerprint.fromHex(it.peer_fp),
                lastBody = it.body,
                lastOutgoing = it.outgoing == 1L,
                lastState = MessageState.from(it.state),
                lastAtMs = it.received_at_ms,
                unread = it.unread.toInt(),
                lastKind = MessageKind.from(it.kind),
                hasOutgoing = it.sent_count > 0,
            )
        }
    }

    fun groupConversation(groupId: String): Flow<List<ChatMessageRecord>> =
        q.conversation(groupId = groupId, peer = "").asFlow().mapToList(io).map { rows -> rows.map { it.toModel() } }

    val groupConversations: Flow<List<ConversationSummary>> = q.groupConversations().asFlow().mapToList(io).map { rows ->
        rows.map {
            ConversationSummary(
                peer = Fingerprint.fromHex(it.peer_fp),
                lastBody = it.body,
                lastOutgoing = it.outgoing == 1L,
                lastState = MessageState.from(it.state),
                lastAtMs = it.received_at_ms,
                unread = it.unread.toInt(),
                lastKind = MessageKind.from(it.kind),
                groupId = it.group_id,
                groupName = it.group_name,
            )
        }
    }

    /** Stores one of our group messages once and queues it for each of [recipients]. */
    suspend fun insertGroupMessage(record: ChatMessageRecord, recipients: List<Fingerprint>, voice: VoiceClip? = null): Boolean {
        val inserted = insert(record, voice = voice)
        if (inserted) {
            withContext(io) { db.transaction { recipients.forEach { db.groupChatQueries.insertRecipient(record.id.value, it.hex) } } }
        }
        return inserted
    }

    /** Group messages still waiting to go to [member], oldest first. */
    suspend fun groupOutbox(member: Fingerprint): List<ChatMessageRecord> = withContext(io) {
        db.groupChatQueries.pendingForMember(member.hex).executeAsList().mapNotNull { q.selectById(it).executeAsOneOrNull()?.toModel() }
    }

    suspend fun advanceGroupRecipient(id: MessageId, member: Fingerprint, state: MessageState) =
        withContext(io) { db.groupChatQueries.advanceRecipient(state = state.dbValue, id = id.value, member = member.hex) }

    fun groupDelivery(groupId: String): Flow<Map<MessageId, GroupDelivery>> =
        db.groupChatQueries.deliveryForGroup(groupId).asFlow().mapToList(io).map { rows ->
            rows.groupBy { it.message_id }.map { (id, states) ->
                val values = states.map { it.state }
                MessageId(id) to GroupDelivery(
                    total = values.size,
                    sent = values.count { it != MessageState.Sending.dbValue },
                    delivered = values.count { it == MessageState.Delivered.dbValue || it == MessageState.Read.dbValue },
                    read = values.count { it == MessageState.Read.dbValue },
                )
            }.toMap()
        }

    /** Marks a group chat read and returns each message with its sender (for read receipts). */
    suspend fun markGroupSeen(groupId: String): List<Pair<MessageId, Fingerprint>> = withContext(io) {
        db.transactionWithResult {
            val unread = q.groupUnreadIncoming(groupId).executeAsList().map { MessageId(it.id) to Fingerprint.fromHex(it.peer_fp) }
            if (unread.isNotEmpty()) q.markGroupSeen(groupId)
            unread
        }
    }

    val totalUnread: Flow<Int> = q.totalUnread().asFlow().mapToOne(io).map { it.toInt() }

    /** Returns false when the id already existed (duplicate delivery, edge case M3). */
    suspend fun insert(
        record: ChatMessageRecord,
        voice: VoiceClip? = null,
        attachment: AttachmentMeta? = null,
        attachmentState: TransferState = TransferState.Offered,
        /** At-rest encryption key of an attachment file that already exists (outgoing files). */
        fileKey: ByteArray? = null,
    ): Boolean = withContext(io) {
        db.transactionWithResult {
            val exists = q.selectById(record.id.value).executeAsOneOrNull() != null
            if (!exists) {
                q.insert(
                    record.id.value, record.peer.hex, record.outgoing.toLong(), record.body, record.state.dbValue,
                    record.sentAtMs, record.receivedAtMs, record.replyTo?.value, record.kind.dbValue, record.durationMs,
                    record.announcement.toLong(), record.expiresAtMs, record.groupId,
                )
                if (voice != null) db.voiceClipQueries.insert(record.id.value, voice.audio, voice.waveform)
                if (attachment != null) {
                    val transferred = if (attachmentState == TransferState.Done) attachment.size else 0
                    db.attachmentQueries.insert(
                        record.id.value, attachment.name, attachment.mime, attachment.size, attachment.hash, attachmentState.dbValue, transferred, fileKey,
                    )
                }
            }
            !exists
        }
    }

    suspend fun message(id: MessageId): ChatMessageRecord? = withContext(io) { q.selectById(id.value).executeAsOneOrNull()?.toModel() }

    suspend fun voiceClip(id: MessageId): VoiceClip? = withContext(io) {
        val durationMs = q.selectById(id.value).executeAsOneOrNull()?.duration_ms ?: return@withContext null
        db.voiceClipQueries.selectByMessage(id.value).executeAsOneOrNull()?.let { VoiceClip(it.audio, durationMs, it.waveform) }
    }

    suspend fun advanceOutgoing(peer: Fingerprint, id: MessageId, state: MessageState) =
        withContext(io) { q.advanceOutgoingState(state = state.dbValue, id = id.value, peer = peer.hex) }

    suspend fun outbox(peer: Fingerprint): List<ChatMessageRecord> = withContext(io) { q.outbox(peer.hex).executeAsList().map { it.toModel() } }

    /** Marks incoming messages as seen and returns their ids (for read receipts). */
    suspend fun markSeen(peer: Fingerprint): List<MessageId> = withContext(io) {
        db.transactionWithResult {
            val ids = q.unreadIncomingIds(peer.hex).executeAsList().map(::MessageId)
            if (ids.isNotEmpty()) q.markSeen(peer.hex)
            ids
        }
    }

    /** Case-insensitive substring search over text messages and file names. */
    suspend fun search(query: String, limit: Int = 100): List<ChatMessageRecord> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val escaped = buildString { term.forEach { if (it == '%' || it == '_' || it == '\\') append('\\'); append(it) } }
        return withContext(io) { q.search(escaped, limit.toLong()).executeAsList().map { it.toModel() } }
    }

    /** Sets (or with an empty [emoji], removes) one person's reaction. [pending] marks our own not-yet-sent change. */
    suspend fun setReaction(id: MessageId, fromSelf: Boolean, emoji: String, pending: Boolean) = withContext(io) {
        if (emoji.isEmpty() && !pending) {
            reactionsQ.deleteOne(id.value, fromSelf.toLong())
        } else {
            reactionsQ.upsert(id.value, fromSelf.toLong(), emoji, pending.toLong())
        }
    }

    suspend fun pendingReactions(peer: Fingerprint): List<ReactionRecord> = withContext(io) {
        reactionsQ.pendingForPeer(peer.hex).executeAsList().map { ReactionRecord(MessageId(it.message_id), true, it.emoji) }
    }

    suspend fun markReactionSent(id: MessageId, emoji: String) = withContext(io) {
        if (emoji.isEmpty()) reactionsQ.deleteOne(id.value, 1) else reactionsQ.markSent(id.value, emoji)
    }

    /** Text and voice messages for an encrypted export, oldest first. */
    suspend fun forBackup(): List<ChatMessageRecord> = withContext(io) { q.forBackup().executeAsList().map { it.toModel() } }

    /** Disappearing messages whose time has come. */
    suspend fun expiredIds(nowMs: Long): List<MessageId> = withContext(io) { q.expiredIds(nowMs).executeAsList().map(::MessageId) }

    /** Deletes one message on this device only. Returns whether it had an attachment file to remove. */
    suspend fun deleteMessage(id: MessageId): Boolean = withContext(io) {
        db.transactionWithResult {
            val hadAttachment = db.attachmentQueries.selectById(id.value).executeAsOneOrNull() != null
            db.voiceClipQueries.deleteForMessage(id.value)
            reactionsQ.deleteForMessage(id.value)
            db.attachmentQueries.deleteForMessage(id.value)
            db.groupChatQueries.deleteRecipientsForMessage(id.value)
            q.deleteById(id.value)
            hadAttachment
        }
    }

    /** Deletes the whole conversation and returns the ids of attachments whose files should be removed. */
    suspend fun deleteConversation(peer: Fingerprint): List<MessageId> = withContext(io) {
        db.transactionWithResult {
            val attachments = db.attachmentQueries.idsForPeer(peer.hex).executeAsList().map(::MessageId)
            db.voiceClipQueries.deleteForPeer(peer.hex)
            reactionsQ.deleteForPeer(peer.hex)
            db.attachmentQueries.deleteForPeer(peer.hex)
            q.deleteConversation(peer.hex)
            attachments
        }
    }

    private fun MessageRow.toModel() = ChatMessageRecord(
        id = MessageId(id),
        peer = Fingerprint.fromHex(peer_fp),
        outgoing = outgoing == 1L,
        body = body,
        state = MessageState.from(state),
        sentAtMs = sent_at_ms,
        receivedAtMs = received_at_ms,
        replyTo = reply_to?.let(::MessageId),
        kind = MessageKind.from(kind),
        durationMs = duration_ms,
        announcement = announcement == 1L,
        expiresAtMs = expires_at_ms,
        groupId = group_id,
    )

    private fun ConversationRow.toModel(): ChatMessageRecord {
        val name = att_name
        val mime = att_mime
        val size = att_size
        val state = att_state
        return ChatMessageRecord(
            id = MessageId(id),
            peer = Fingerprint.fromHex(peer_fp),
            outgoing = outgoing == 1L,
            body = body,
            state = MessageState.from(this.state),
            sentAtMs = sent_at_ms,
            receivedAtMs = received_at_ms,
            replyTo = reply_to?.let(::MessageId),
            kind = MessageKind.from(kind),
            durationMs = duration_ms,
            announcement = announcement == 1L,
            expiresAtMs = expires_at_ms,
            groupId = group_id,
            attachment = if (name != null && mime != null && size != null && state != null) {
                AttachmentInfo(name, mime, size, TransferState.from(state), att_transferred ?: 0)
            } else {
                null
            },
        )
    }
}

/** An attachment row with what the transfer engine needs. */
class StoredAttachment(
    val id: MessageId,
    val peer: Fingerprint,
    val outgoing: Boolean,
    val meta: AttachmentMeta,
    val state: TransferState,
    val transferred: Long,
    /** Key of the file's at-rest encryption; null for plain files from before encryption. */
    val fileKey: ByteArray? = null,
)

class AttachmentRepository internal constructor(private val db: ZoocallDatabase, private val io: CoroutineDispatcher) {
    private val q get() = db.attachmentQueries

    suspend fun get(id: MessageId): StoredAttachment? = withContext(io) {
        q.selectById(id.value).executeAsOneOrNull()?.let {
            StoredAttachment(
                id = MessageId(it.message_id),
                peer = Fingerprint.fromHex(it.peer_fp),
                outgoing = it.outgoing == 1L,
                meta = AttachmentMeta(it.name, it.mime, it.size, it.hash),
                state = TransferState.from(it.state),
                transferred = it.transferred,
                fileKey = it.file_key,
            )
        }
    }

    suspend fun setState(id: MessageId, state: TransferState, transferred: Long) = withContext(io) { q.setState(state.dbValue, transferred, id.value) }

    suspend fun setTransferred(id: MessageId, transferred: Long) = withContext(io) { q.setTransferred(transferred, id.value) }

    suspend fun setFileKey(id: MessageId, key: ByteArray) = withContext(io) { q.setFileKey(key, id.value) }

    suspend fun activeDownloads(peer: Fingerprint): List<MessageId> = withContext(io) { q.activeDownloads(peer.hex).executeAsList().map(::MessageId) }
}

data class CallLogEntry(
    val callId: String,
    val peer: Fingerprint,
    val direction: CallDirection,
    val kind: CallKind,
    val startedAtMs: Long,
    val connectedAtMs: Long?,
    val endedAtMs: Long,
    val endReason: EndReason,
    /** Length of this call's recording; null when it wasn't recorded. */
    val recordingMs: Long? = null,
) {
    val durationMs: Long? get() = connectedAtMs?.let { endedAtMs - it }
    val isMissed: Boolean get() = direction == CallDirection.Incoming && endReason == EndReason.Missed
}

class CallLogRepository internal constructor(private val db: ZoocallDatabase, private val io: CoroutineDispatcher) {
    private val q get() = db.callLogQueries

    val recent: Flow<List<CallLogEntry>> = q.recent(200).asFlow().mapToList(io).map { rows ->
        rows.map {
            CallLogEntry(
                callId = it.call_id,
                peer = Fingerprint.fromHex(it.peer_fp),
                direction = if (it.outgoing == 1L) CallDirection.Outgoing else CallDirection.Incoming,
                kind = if (it.video == 1L) CallKind.Video else CallKind.Audio,
                startedAtMs = it.started_at_ms,
                connectedAtMs = it.connected_at_ms,
                endedAtMs = it.ended_at_ms,
                endReason = EndReason.entries.firstOrNull { r -> r.name == it.end_reason } ?: EndReason.Completed,
                recordingMs = it.recording_ms.takeIf { _ -> it.recording_key != null },
            )
        }
    }

    val unseenMissed: Flow<Int> = q.unseenMissedCount().asFlow().mapToOne(io).map { it.toInt() }

    /** [recordingKey] is the file key of the call's recording, stored with it in the encrypted database. */
    suspend fun record(entry: CallLogEntry, recordingKey: ByteArray? = null) = withContext(io) {
        q.insert(
            entry.callId, entry.peer.hex, (entry.direction == CallDirection.Outgoing).toLong(), (entry.kind == CallKind.Video).toLong(),
            entry.startedAtMs, entry.connectedAtMs, entry.endedAtMs, entry.endReason.name, if (entry.isMissed) 0 else 1,
            recordingKey, entry.recordingMs,
        )
    }

    suspend fun recordingKey(callId: String): ByteArray? = withContext(io) { q.selectById(callId).executeAsOneOrNull()?.recording_key }

    suspend fun markAllSeen() = withContext(io) { q.markAllSeen() }
    suspend fun delete(callId: String) = withContext(io) { q.deleteOne(callId) }
    suspend fun clear() = withContext(io) { q.clear() }
}

class SettingsRepository internal constructor(private val db: ZoocallDatabase, private val io: CoroutineDispatcher) {
    private val q get() = db.settingQueries

    val all: Flow<Map<String, String>> = q.selectAll().asFlow().mapToList(io).map { rows -> rows.associate { it.key to it.value_ } }

    suspend fun snapshot(): Map<String, String> = withContext(io) { q.selectAll().executeAsList().associate { it.key to it.value_ } }
    suspend fun put(key: String, value: String) = withContext(io) { q.put(key, value) }
    suspend fun remove(key: String) = withContext(io) { q.remove(key) }
}

private fun Boolean.toLong() = if (this) 1L else 0L
