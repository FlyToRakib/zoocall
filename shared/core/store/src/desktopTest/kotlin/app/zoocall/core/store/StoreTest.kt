package app.zoocall.core.store

import app.zoocall.core.model.Fingerprint
import app.zoocall.core.model.MessageId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreTest {
    private val dir: File = Files.createTempDirectory("zoocall-store").toFile().apply { deleteOnExit() }
    private val key = ByteArray(32) { it.toByte() }
    private val peer = Fingerprint.of(ByteArray(32) { 1 })

    @Test
    fun messagesDedupAndStatesOnlyMoveForward() = runTest {
        val store = ZoocallStore(DesktopDatabaseDriverFactory(dir).create(key))
        val id = MessageId.generate()
        val record = ChatMessageRecord(id, peer, outgoing = true, body = "hi", state = MessageState.Sending, sentAtMs = 1, receivedAtMs = 1, replyTo = null)
        assertTrue(store.messages.insert(record))
        assertFalse(store.messages.insert(record), "duplicate id must be ignored")

        store.messages.advanceOutgoing(peer, id, MessageState.Delivered)
        store.messages.advanceOutgoing(peer, id, MessageState.Sent) // must not go back
        assertEquals(MessageState.Delivered, store.messages.conversation(peer).first().single().state)

        val summary = store.messages.conversations.first().single()
        assertEquals("hi", summary.lastBody)
    }

    @Test
    fun groupsKeepTheirMembersAndLoseRemovedContacts() = runTest {
        val store = ZoocallStore(DesktopDatabaseDriverFactory(dir).create(key))
        val other = Fingerprint.of(ByteArray(32) { 2 })
        val id = store.groups.create("Kitchen", listOf(peer, other), 1)
        val group = store.groups.all.first().single()
        assertEquals("Kitchen", group.name)
        assertEquals(setOf(peer, other), group.members.toSet())

        store.groups.setMembers(id, listOf(other))
        store.groups.rename(id, "Front desk")
        assertEquals(listOf(other), store.groups.get(id)!!.members)
        assertEquals("Front desk", store.groups.get(id)!!.name)

        store.groups.removeEverywhere(other)
        assertTrue(store.groups.get(id)!!.members.isEmpty())
        store.groups.delete(id)
        assertTrue(store.groups.all.first().isEmpty())
    }

    @Test
    fun groupChatMessagesQueuePerMemberAndStayOutOfDirectChats() = runTest {
        val store = ZoocallStore(DesktopDatabaseDriverFactory(dir).create(key))
        val self = Fingerprint.of(ByteArray(32) { 9 })
        val other = Fingerprint.of(ByteArray(32) { 2 })
        val groupId = MessageId.generate().value
        store.groupChats.save(GroupChat(groupId, "Lab", listOf(self, peer, other), 5))
        val id = MessageId.generate()
        store.messages.insertGroupMessage(
            ChatMessageRecord(id, self, true, "hi all", MessageState.Sending, 1, 1, null, groupId = groupId),
            recipients = listOf(peer, other),
        )

        assertEquals(listOf(id), store.messages.groupOutbox(peer).map { it.id })
        store.messages.advanceGroupRecipient(id, peer, MessageState.Delivered)
        store.messages.advanceGroupRecipient(id, peer, MessageState.Sent) // must not go back
        assertTrue(store.messages.groupOutbox(peer).isEmpty())
        assertEquals(listOf(id), store.messages.groupOutbox(other).map { it.id })
        assertEquals(GroupDelivery(total = 2, sent = 1, delivered = 1, read = 0), store.messages.groupDelivery(groupId).first()[id])

        assertTrue(store.messages.conversations.first().isEmpty(), "group messages aren't a 1:1 conversation")
        assertTrue(store.messages.conversation(self).first().isEmpty())
        assertEquals("Lab", store.messages.groupConversations.first().single().groupName)
        assertEquals(listOf(id), store.messages.groupConversation(groupId).first().map { it.id })
        assertEquals(listOf(self, peer, other), store.groupChats.get(groupId)!!.members)
    }

    @Test
    fun onlyExpiredDisappearingMessagesAreDue() = runTest {
        val store = ZoocallStore(DesktopDatabaseDriverFactory(dir).create(key))
        val keep = MessageId.generate()
        val soon = MessageId.generate()
        val later = MessageId.generate()
        store.messages.insert(ChatMessageRecord(keep, peer, false, "stays", MessageState.Received, 1, 1, null))
        store.messages.insert(ChatMessageRecord(soon, peer, false, "goes", MessageState.Received, 1, 1, null, expiresAtMs = 1_000))
        store.messages.insert(ChatMessageRecord(later, peer, true, "later", MessageState.Sending, 1, 1, null, expiresAtMs = 5_000))

        assertEquals(listOf(soon), store.messages.expiredIds(1_000))
        assertEquals(5_000, store.messages.message(later)!!.expiresAtMs)
        store.messages.deleteMessage(soon)
        assertTrue(store.messages.expiredIds(1_000).isEmpty())
        assertEquals(setOf(keep, later), store.messages.conversation(peer).first().map { it.id }.toSet())
    }

    @Test
    fun announcementsAreKeptWithTheirMessage() = runTest {
        val store = ZoocallStore(DesktopDatabaseDriverFactory(dir).create(key))
        val id = MessageId.generate()
        store.messages.insert(ChatMessageRecord(id, peer, false, "Meeting in 5 min", MessageState.Received, 1, 1, null, announcement = true))
        assertTrue(store.messages.conversation(peer).first().single().announcement)
        assertTrue(store.messages.message(id)!!.announcement)
    }

    @Test
    fun voiceClipsAreStoredWithTheirMessage() = runTest {
        val store = ZoocallStore(DesktopDatabaseDriverFactory(dir).create(key))
        val id = MessageId.generate()
        val clip = app.zoocall.core.model.VoiceClip(ByteArray(1_000) { it.toByte() }, 4_200, ByteArray(48) { 100 })
        store.messages.insert(
            ChatMessageRecord(id, peer, true, "", MessageState.Sending, 1, 1, null, app.zoocall.core.model.MessageKind.Voice, 4_200),
            voice = clip,
        )
        val loaded = store.messages.voiceClip(id)!!
        assertTrue(loaded.audio.contentEquals(clip.audio))
        assertEquals(4_200, loaded.durationMs)
        assertEquals(app.zoocall.core.model.MessageKind.Voice, store.messages.conversations.first().single().lastKind)

        store.messages.deleteConversation(peer)
        assertEquals(null, store.messages.voiceClip(id))
    }

    @Test
    fun reactionsAreOnePerPersonAndRemovable() = runTest {
        val store = ZoocallStore(DesktopDatabaseDriverFactory(dir).create(key))
        val id = MessageId.generate()
        store.messages.insert(ChatMessageRecord(id, peer, false, "hello", MessageState.Received, 1, 1, null))

        store.messages.setReaction(id, fromSelf = true, emoji = "👍", pending = true)
        store.messages.setReaction(id, fromSelf = true, emoji = "❤️", pending = true) // replaces
        store.messages.setReaction(id, fromSelf = false, emoji = "😂", pending = false)
        val reactions = store.messages.reactions(peer).first()[id]!!
        assertEquals(setOf("❤️" to true, "😂" to false), reactions.map { it.emoji to it.fromSelf }.toSet())
        assertEquals(listOf("❤️"), store.messages.pendingReactions(peer).map { it.emoji })

        store.messages.markReactionSent(id, "❤️")
        assertTrue(store.messages.pendingReactions(peer).isEmpty())

        store.messages.setReaction(id, fromSelf = false, emoji = "", pending = false)
        assertEquals(listOf(true), store.messages.reactions(peer).first()[id]!!.map { it.fromSelf })
    }

    @Test
    fun attachmentsJoinTheirMessageAndDeleteWithIt() = runTest {
        val store = ZoocallStore(DesktopDatabaseDriverFactory(dir).create(key))
        val id = MessageId.generate()
        val meta = app.zoocall.core.model.AttachmentMeta("report.pdf", "application/pdf", 12_345, ByteArray(32) { 7 })
        store.messages.insert(
            ChatMessageRecord(id, peer, false, meta.name, MessageState.Received, 1, 1, null, app.zoocall.core.model.MessageKind.File),
            attachment = meta,
        )
        val info = store.messages.conversation(peer).first().single().attachment!!
        assertEquals(app.zoocall.core.model.TransferState.Offered, info.state)
        assertEquals(12_345, info.size)

        store.attachments.setState(id, app.zoocall.core.model.TransferState.Requested, 500)
        assertEquals(listOf(id), store.attachments.activeDownloads(peer))
        assertTrue(store.attachments.get(id)!!.meta.hash.contentEquals(meta.hash))

        assertTrue(store.messages.deleteMessage(id))
        assertEquals(null, store.attachments.get(id))
        assertTrue(store.messages.conversation(peer).first().isEmpty())
    }

    @Test
    fun searchMatchesSubstringsAndEscapesWildcards() = runTest {
        val store = ZoocallStore(DesktopDatabaseDriverFactory(dir).create(key))
        store.messages.insert(ChatMessageRecord(MessageId.generate(), peer, true, "Meeting at 5", MessageState.Sent, 1, 1, null))
        store.messages.insert(ChatMessageRecord(MessageId.generate(), peer, false, "100% ready", MessageState.Received, 2, 2, null))
        store.messages.insert(ChatMessageRecord(MessageId.generate(), peer, false, "বাংলা বার্তা", MessageState.Received, 3, 3, null))

        assertEquals(listOf("Meeting at 5"), store.messages.search("meeting").map { it.body })
        assertEquals(listOf("100% ready"), store.messages.search("0%").map { it.body })
        assertTrue(store.messages.search("_").isEmpty())
        assertEquals(1, store.messages.search("বার্তা").size)
    }

    @Test
    fun databaseIsEncryptedAtRest() = runTest {
        val store = ZoocallStore(DesktopDatabaseDriverFactory(dir).create(key))
        store.settings.put("profile.name", "Plaintext-Canary-Value")
        val bytes = File(dir, "zoocall.db").readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Plaintext-Canary-Value"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).startsWith("SQLite format 3"))

        val wrongKey = runCatching {
            ZoocallStore(DesktopDatabaseDriverFactory(dir).create(ByteArray(32))).settings.snapshot()
        }
        assertTrue(wrongKey.isFailure, "wrong key must not open the database")
    }

    @Test
    fun keyStoreRoundTrip() = runTest {
        val keys = DesktopKeyStore(dir)
        val secret = ByteArray(32) { (it * 3).toByte() }
        keys.save("identity", secret)
        assertTrue(secret.contentEquals(keys.load("identity")))
        assertFalse(File(dir, "identity.key").readBytes().contentEquals(secret) && System.getProperty("os.name").lowercase().contains("win"))
    }
}
