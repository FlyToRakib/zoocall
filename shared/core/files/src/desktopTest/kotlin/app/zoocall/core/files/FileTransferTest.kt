package app.zoocall.core.files

import app.zoocall.core.chat.ChatManager
import app.zoocall.core.crypto.Identity
import app.zoocall.core.crypto.Sodium
import app.zoocall.core.model.AttachmentMeta
import app.zoocall.core.model.DeviceClass
import app.zoocall.core.model.MessageId
import app.zoocall.core.model.PeerAddress
import app.zoocall.core.model.TransferState
import app.zoocall.core.store.DesktopDatabaseDriverFactory
import app.zoocall.core.store.ZoocallStore
import app.zoocall.core.transport.ConnectionManager
import app.zoocall.core.transport.InMemoryNetwork
import app.zoocall.core.transport.TransportConfig
import app.zoocall.protocol.v1.Hello
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import okio.use

/** End-to-end file transfer between two in-process peers over encrypted FILE connections. */
class FileTransferTest {
    private val network = InMemoryNetwork()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transports = mutableListOf<ConnectionManager>()
    private val fs = FileSystem.SYSTEM

    private class Peer(val identity: Identity, val transport: ConnectionManager, val store: ZoocallStore, val chat: ChatManager, val files: FileTransferManager, val dir: okio.Path)

    private suspend fun peer(ip: String, autoDownload: Boolean): Peer {
        Sodium.ensureInitialized()
        val identity = Identity.generate()
        val transport = ConnectionManager(
            identity = identity,
            config = TransportConfig(appVersion = "test", deviceClass = DeviceClass.Phone),
            linkFactory = network.host(ip),
            helloProvider = { Hello(display_name = ip) },
        )
        val root = Files.createTempDirectory("zoocall-files").toFile().apply { deleteOnExit() }
        val store = ZoocallStore(DesktopDatabaseDriverFactory(root).create(ByteArray(32) { 3 }))
        val dir = root.toOkioPath() / "attachments"
        lateinit var files: FileTransferManager
        files = FileTransferManager(scope, transport, store.attachments, fs, dir, autoDownload = { _, _ -> autoDownload })
        val chat = ChatManager(scope, transport, store.messages, store.attachments, onAttachmentReceived = files::onAttachmentReceived)
        files.start()
        chat.start()
        transport.start()
        transports += transport
        return Peer(identity, transport, store, chat, files, dir)
    }

    private suspend fun connected(autoDownload: Boolean = true): Pair<Peer, Peer> {
        val alice = peer("10.0.0.1", autoDownload = false)
        val bob = peer("10.0.0.2", autoDownload = autoDownload)
        alice.transport.connect(PeerAddress("10.0.0.2", bob.transport.listenPort.value!!)).getOrThrow()
        bob.transport.peers.first { it.containsKey(alice.identity.fingerprint) }
        return alice to bob
    }

    @AfterTest
    fun tearDown() {
        transports.forEach { it.stop() }
        scope.cancel()
    }

    private fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(20_000) { block() } }
    }

    private suspend fun Peer.awaitState(id: MessageId, state: TransferState) {
        while (store.attachments.get(id)?.state != state) delay(20)
    }

    private suspend fun Peer.send(bytes: ByteArray, name: String = "photo.jpg"): Pair<MessageId, ImportedAttachment> {
        val id = MessageId.generate()
        val imported = assertNotNull(files.importFile(id, name, "image/jpeg", Buffer().write(bytes)))
        return id to imported
    }

    @Test
    fun fileArrivesVerifiedAcrossSeveralChunks() = realTime {
        val (alice, bob) = connected()
        val bytes = Random.nextBytes(250_000) // > 4 chunks
        val (id, imported) = alice.send(bytes)
        assertEquals(bytes.size.toLong(), imported.meta.size)
        alice.chat.sendAttachment(bob.identity.fingerprint, id, imported.meta, fileKey = imported.fileKey)

        bob.awaitState(id, TransferState.Done)
        assertContentEquals(bytes, assertNotNull(bob.files.readBytes(id, Long.MAX_VALUE)))
        // Encrypted at rest on both devices; the decrypted copy is only made on request.
        assertFalse(fs.read(bob.dir / id.value) { readByteArray() }.contentEquals(bytes))
        assertFalse(fs.read(alice.dir / id.value) { readByteArray() }.contentEquals(bytes))
        assertContentEquals(bytes, fs.read(assertNotNull(bob.files.decryptedCopy(id))) { readByteArray() })
    }

    @Test
    fun downloadResumesFromPartialFile() = realTime {
        val (alice, bob) = connected(autoDownload = false)
        val bytes = Random.nextBytes(180_000)
        val (id, imported) = alice.send(bytes, name = "notes.pdf")
        // Nothing flows until Alice resumes, so the interruption is under the test's control.
        alice.files.pauseUpload(id)
        alice.chat.sendAttachment(bob.identity.fingerprint, id, imported.meta, fileKey = imported.fileKey)
        bob.awaitState(id, TransferState.Offered)
        bob.files.download(id)
        bob.awaitState(id, TransferState.Requested)

        // Pretend the first 100 KB arrived before an interruption: only its complete chunk is kept.
        val key = assertNotNull(bob.store.attachments.get(id)?.fileKey)
        EncryptedAttachmentWriter(fs, bob.dir / "${id.value}.part", key, 0).use { it.write(bytes, 0, 100_000) }
        alice.files.resumeUpload(id)

        bob.awaitState(id, TransferState.Done)
        assertContentEquals(bytes, bob.files.readBytes(id, Long.MAX_VALUE))
    }

    @Test
    fun corruptedFileIsDiscarded() = realTime {
        val (alice, bob) = connected()
        val bytes = Random.nextBytes(70_000)
        val (id, imported) = alice.send(bytes)
        val wrongHash = AttachmentMeta(imported.meta.name, imported.meta.mime, imported.meta.size, ByteArray(32) { 9 })
        alice.chat.sendAttachment(bob.identity.fingerprint, id, wrongHash, fileKey = imported.fileKey)

        bob.awaitState(id, TransferState.Failed)
        assertFalse(bob.files.hasFile(id))
    }

    @Test
    fun sanitizesHostileNames() {
        assertEquals("passwd", AttachmentMeta.sanitizeName("../../etc/passwd"))
        assertEquals("file_CON.txt", AttachmentMeta.sanitizeName("CON.txt"))
        assertEquals("evilexe.txt", AttachmentMeta.sanitizeName("evil‮exe.txt"))
        assertEquals("file", AttachmentMeta.sanitizeName("..."))
    }
}
