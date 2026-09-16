package app.zoocall.core.files

import app.zoocall.core.crypto.Sodium
import app.zoocall.core.model.AttachmentMeta
import app.zoocall.core.model.Fingerprint
import app.zoocall.core.model.Logger
import app.zoocall.core.model.MessageId
import app.zoocall.core.model.TransferState
import app.zoocall.core.protocol.FileLimits
import app.zoocall.core.store.AttachmentRepository
import app.zoocall.core.store.StoredAttachment
import app.zoocall.core.transport.ConnectionManager
import app.zoocall.core.transport.SecureConnection
import app.zoocall.core.transport.TransportEvent
import app.zoocall.protocol.v1.Envelope
import app.zoocall.protocol.v1.FileOffer
import app.zoocall.protocol.v1.FileResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Source
import okio.buffer
import okio.use
import kotlin.time.Clock

/** A picked file copied into private storage and encrypted with its own key. */
class ImportedAttachment(val meta: AttachmentMeta, val fileKey: ByteArray)

/**
 * Moves attachment bytes (docs/03 §4.4). The chat message carries the file's name, size and hash;
 * the receiver asks for bytes with `FileResponse{accept, offset}`, and the sender streams them on a
 * separate Noise connection (purpose FILE) so the control channel stays responsive.
 *
 * Wire format of a FILE connection, sender → receiver: first chunk = u64 BE start offset,
 * then raw file chunks until the file is complete, then the sender closes.
 * The receiver verifies BLAKE2b-256 of the whole file before marking it done.
 *
 * Files are encrypted at rest with a key per file ([AttachmentCrypto]); plain files from before
 * that stay readable.
 */
class FileTransferManager(
    private val scope: CoroutineScope,
    private val transport: ConnectionManager,
    private val attachments: AttachmentRepository,
    private val fileSystem: FileSystem,
    /** Private directory for attachment files, named by message id. */
    private val directory: Path,
    /** Whether a new incoming file should download without asking. */
    private val autoDownload: suspend (Fingerprint, AttachmentMeta) -> Boolean = { _, _ -> false },
    private val logger: Logger = Logger.current,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = Mutex()
    private val uploads = HashMap<MessageId, Job>()
    private val downloads = HashMap<MessageId, Job>()
    private val pausedUploads = HashSet<MessageId>()
    private val retries = HashMap<MessageId, Int>()

    /** Decrypted copies made for opening or saving; removed at the next start. */
    private val exports = directory / EXPORTS

    private val _progress = MutableStateFlow<Map<MessageId, Long>>(emptyMap())

    /** Live byte counts for transfers in progress (both directions). */
    val progress: StateFlow<Map<MessageId, Long>> = _progress.asStateFlow()

    private val _uploading = MutableStateFlow<Set<MessageId>>(emptySet())

    /** Outgoing files currently streaming. */
    val uploading: StateFlow<Set<MessageId>> = _uploading.asStateFlow()

    private val _pausedOutgoing = MutableStateFlow<Set<MessageId>>(emptySet())
    val pausedOutgoing: StateFlow<Set<MessageId>> = _pausedOutgoing.asStateFlow()

    fun start() {
        fileSystem.createDirectories(directory)
        runCatching { fileSystem.deleteRecursively(exports) }
        transport.fileConnectionHandler = ::receive
        scope.launch {
            transport.events.collect { event ->
                try {
                    when (event) {
                        is TransportEvent.Connected -> resumeDownloadsFrom(event.peer.fingerprint)
                        is TransportEvent.Received -> onEnvelope(event.from, event.envelope)
                        else -> Unit
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logger.error(TAG, "File event handling failed", e)
                }
            }
        }
    }

    /** Whether the finished file for [id] is stored on this device. */
    fun hasFile(id: MessageId): Boolean = fileSystem.exists(finalPath(id))

    /**
     * Copies a picked file into private storage under [id], encrypting and hashing it on the way.
     * Returns null when the file is larger than [FileLimits.MAX_SIZE] or can't be read.
     */
    suspend fun importFile(id: MessageId, name: String, mime: String, source: Source): ImportedAttachment? = withContext(Dispatchers.IO) {
        val temp = directory / "${id.value}.import"
        val key = AttachmentCrypto.newKey()
        val hash = Sodium.blake2bStream(AttachmentMeta.HASH_BYTES)
        var size = 0L
        try {
            source.buffer().use { input ->
                EncryptedAttachmentWriter(fileSystem, temp, key, 0).use { output ->
                    val buffer = ByteArray(IO_BUFFER)
                    while (true) {
                        val n = input.read(buffer, 0, buffer.size)
                        if (n == -1) break
                        size += n
                        if (size > FileLimits.MAX_SIZE) error("File too large")
                        hash.update(buffer, n)
                        output.write(buffer, 0, n)
                    }
                    output.finish()
                }
            }
            fileSystem.atomicMove(temp, finalPath(id))
            ImportedAttachment(AttachmentMeta(AttachmentMeta.sanitizeName(name), AttachmentMeta.sanitizeMime(mime), size, hash.finish()), key)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.warn(TAG, "Import failed: ${e::class.simpleName}")
            fileSystem.delete(temp, mustExist = false)
            null
        }
    }

    /** A finished file's bytes, for in-app previews. Null when missing or larger than [maxBytes]. */
    suspend fun readBytes(id: MessageId, maxBytes: Long): ByteArray? = withContext(Dispatchers.IO) {
        val stored = attachments.get(id) ?: return@withContext null
        if (!hasFile(id) || stored.meta.size > maxBytes) return@withContext null
        runCatching {
            openReader(stored).use { reader ->
                val out = ByteArray(stored.meta.size.toInt())
                var position = 0
                while (position < out.size) {
                    val n = reader.read(position.toLong(), out, position, out.size - position)
                    if (n <= 0) error("Attachment is truncated")
                    position += n
                }
                out
            }
        }.onFailure { logger.warn(TAG, "Reading attachment failed: ${it::class.simpleName}") }.getOrNull()
    }

    /**
     * A decrypted copy of a finished file, made only when the user opens or saves it (another app
     * needs plain bytes). Copies are deleted at the next start.
     */
    suspend fun decryptedCopy(id: MessageId): Path? = withContext(Dispatchers.IO) {
        val stored = attachments.get(id) ?: return@withContext null
        if (!hasFile(id)) return@withContext null
        val target = exports / id.value
        if (fileSystem.metadataOrNull(target)?.size == stored.meta.size) return@withContext target
        val temp = exports / "${id.value}.tmp"
        runCatching {
            fileSystem.createDirectories(exports)
            openReader(stored).use { reader ->
                fileSystem.sink(temp).buffer().use { out ->
                    val buffer = ByteArray(IO_BUFFER)
                    var position = 0L
                    while (position < stored.meta.size) {
                        val n = reader.read(position, buffer, 0, buffer.size)
                        if (n <= 0) error("Attachment is truncated")
                        out.write(buffer, 0, n)
                        position += n
                    }
                }
            }
            fileSystem.atomicMove(temp, target)
            target
        }.onFailure {
            logger.warn(TAG, "Decrypting attachment failed: ${it::class.simpleName}")
            fileSystem.delete(temp, mustExist = false)
        }.getOrNull()
    }

    /** Called for each new incoming file message. */
    suspend fun onAttachmentReceived(from: Fingerprint, id: MessageId, meta: AttachmentMeta) {
        if (autoDownload(from, meta)) download(id)
    }

    /** Starts or resumes a download. Bytes flow once the sender is connected. */
    suspend fun download(id: MessageId) {
        val stored = attachments.get(id) ?: return
        if (stored.outgoing || stored.state == TransferState.Done) return
        if (stored.state == TransferState.Failed) {
            // A fresh key for a fresh copy: the same chunk nonces must never seal different bytes.
            fileSystem.delete(partPath(id), mustExist = false)
            attachments.setFileKey(id, AttachmentCrypto.newKey())
        } else {
            ensureKey(stored)
        }
        retries.remove(id)
        val offset = partSize(id, stored.meta.size)
        attachments.setState(id, TransferState.Requested, offset)
        requestBytes(stored.peer, id, offset)
    }

    /** Pauses an incoming download; [download] continues from the last complete chunk. */
    suspend fun pauseDownload(id: MessageId) {
        val stored = attachments.get(id) ?: return
        if (stored.outgoing || stored.state != TransferState.Requested) return
        lock.withLock { downloads.remove(id) }?.cancel()
        attachments.setState(id, TransferState.Paused, partSize(id, stored.meta.size))
        clearProgress(id)
        transport.send(stored.peer, Envelope(file_response = FileResponse(file_id = id.value, accept = false)))
    }

    /** Stops sending a file until [resumeUpload]. */
    suspend fun pauseUpload(id: MessageId) {
        lock.withLock {
            pausedUploads += id
            uploads.remove(id)
        }?.cancel()
        _pausedOutgoing.update { it + id }
        clearProgress(id)
    }

    suspend fun resumeUpload(id: MessageId) {
        val stored = attachments.get(id) ?: return
        if (!stored.outgoing) return
        lock.withLock { pausedUploads -= id }
        _pausedOutgoing.update { it - id }
        transport.send(
            stored.peer,
            Envelope(file_offer = FileOffer(file_id = id.value, size = stored.meta.size, blake2b_256 = stored.meta.hash.toByteString())),
        )
    }

    /** Stops any transfer and removes the local files for [id]. The database row (and key) is deleted by the caller. */
    suspend fun deleteFiles(id: MessageId) {
        lock.withLock {
            pausedUploads -= id
            listOfNotNull(uploads.remove(id), downloads.remove(id))
        }.forEach { it.cancel() }
        clearProgress(id)
        withContext(Dispatchers.IO) {
            fileSystem.delete(finalPath(id), mustExist = false)
            fileSystem.delete(partPath(id), mustExist = false)
            fileSystem.delete(exports / id.value, mustExist = false)
        }
    }

    // -- Control messages -------------------------------------------------------------------------

    private suspend fun onEnvelope(from: Fingerprint, envelope: Envelope) {
        envelope.file_response?.let { return onFileResponse(from, it) }
        envelope.file_offer?.let { return onFileOffer(from, it) }
    }

    private suspend fun onFileResponse(from: Fingerprint, response: FileResponse) {
        val id = parseId(response.file_id) ?: return
        val stored = attachments.get(id) ?: return
        if (!stored.outgoing || stored.peer != from) return
        val previous = lock.withLock { uploads.remove(id) }
        previous?.cancel()
        if (!response.accept) {
            clearProgress(id)
            return
        }
        if (!hasFile(id) || response.resume_from_offset > stored.meta.size) return
        lock.withLock {
            if (id in pausedUploads) return
            uploads[id] = scope.launch { upload(stored, response.resume_from_offset) }
        }
    }

    private suspend fun onFileOffer(from: Fingerprint, offer: FileOffer) {
        val id = parseId(offer.file_id) ?: return
        val stored = attachments.get(id) ?: return
        if (stored.outgoing || stored.peer != from || stored.state != TransferState.Requested) return
        if (lock.withLock { downloads.containsKey(id) }) return
        ensureKey(stored)
        requestBytes(from, id, partSize(id, stored.meta.size))
    }

    private suspend fun resumeDownloadsFrom(peer: Fingerprint) {
        for (id in attachments.activeDownloads(peer)) {
            if (lock.withLock { downloads.containsKey(id) }) continue
            val stored = attachments.get(id) ?: continue
            ensureKey(stored)
            requestBytes(peer, id, partSize(id, stored.meta.size))
        }
    }

    private suspend fun requestBytes(peer: Fingerprint, id: MessageId, offset: Long) {
        if (transport.isConnected(peer)) {
            transport.send(peer, Envelope(file_response = FileResponse(file_id = id.value, accept = true, resume_from_offset = offset)))
        }
    }

    /** Incoming files get their key when a download starts; a partial file from before encryption starts over. */
    private suspend fun ensureKey(stored: StoredAttachment): ByteArray {
        stored.fileKey?.let { return it }
        withContext(Dispatchers.IO) { fileSystem.delete(partPath(stored.id), mustExist = false) }
        val key = AttachmentCrypto.newKey()
        attachments.setFileKey(stored.id, key)
        return key
    }

    // -- Sending ----------------------------------------------------------------------------------

    private suspend fun upload(stored: StoredAttachment, offset: Long) {
        val id = stored.id
        _uploading.update { it + id }
        try {
            val completed = transport.withFileConnection(stored.peer, id.value) { connection ->
                connection.sendChunk(Buffer().writeLong(offset).readByteArray())
                withContext(Dispatchers.IO) { openReader(stored) }.use { reader ->
                    val buffer = ByteArray(FileLimits.CHUNK)
                    var position = offset
                    var lastReport = 0L
                    while (position < stored.meta.size) {
                        val n = withContext(Dispatchers.IO) { reader.read(position, buffer, 0, buffer.size) }
                        if (n <= 0) error("File shrank while sending")
                        connection.sendChunk(if (n == buffer.size) buffer else buffer.copyOf(n))
                        position += n
                        if (now() - lastReport > PROGRESS_INTERVAL_MS) {
                            lastReport = now()
                            _progress.update { it + (id to position) }
                        }
                    }
                }
                true
            }
            if (completed != true) logger.info(TAG, "Upload interrupted")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.info(TAG, "Upload failed: ${e::class.simpleName}: ${e.message}")
        } finally {
            _uploading.update { it - id }
            clearProgress(id)
            lock.withLock { if (uploads[id] === currentCoroutineContext()[Job]) uploads.remove(id) }
        }
    }

    // -- Receiving --------------------------------------------------------------------------------

    /** Handles an inbound FILE connection from [from]. Runs until the file completes or the stream breaks. */
    private suspend fun receive(from: Fingerprint, fileId: String, connection: SecureConnection) {
        val id = parseId(fileId) ?: return
        val stored = attachments.get(id) ?: return
        if (stored.outgoing || stored.peer != from || stored.state != TransferState.Requested) return
        val job = currentCoroutineContext()[Job]
        val registered = lock.withLock {
            if (downloads.containsKey(id)) false else {
                if (job != null) downloads[id] = job
                true
            }
        }
        if (!registered) return

        val key = ensureKey(stored)
        val size = stored.meta.size
        val part = partPath(id)
        var position = 0L
        var failed = false
        try {
            val header = connection.receiveChunk()
            if (header.size != 8) error("Bad FILE header")
            val offset = Buffer().write(header).readLong()
            // Resume exactly where our complete chunks end, as we asked.
            if (offset != partSize(id, size)) error("Unexpected resume offset")
            position = offset
            withContext(Dispatchers.IO) { EncryptedAttachmentWriter(fileSystem, part, key, offset) }.use { writer ->
                var lastSave = now()
                while (position < size) {
                    val chunk = connection.receiveChunk()
                    if (chunk.isEmpty() || position + chunk.size > size) error("Chunk overflows file size")
                    withContext(Dispatchers.IO) { writer.write(chunk, 0, chunk.size) }
                    position += chunk.size
                    _progress.update { it + (id to position) }
                    if (now() - lastSave > SAVE_INTERVAL_MS) {
                        lastSave = now()
                        attachments.setTransferred(id, position)
                        retries.remove(id)
                    }
                }
                withContext(Dispatchers.IO) { writer.finish() }
            }
            if (verify(part, key, size, stored.meta.hash)) {
                withContext(Dispatchers.IO) { fileSystem.atomicMove(part, finalPath(id)) }
                attachments.setState(id, TransferState.Done, size)
                logger.info(TAG, "File received and verified")
            } else {
                // Edge case M5: never keep a corrupted file.
                withContext(Dispatchers.IO) { fileSystem.delete(part, mustExist = false) }
                attachments.setState(id, TransferState.Failed, 0)
                logger.warn(TAG, "File hash mismatch, discarded")
            }
        } catch (e: Exception) {
            if (e is CancellationException) {
                attachments.setTransferred(id, partSize(id, size))
                throw e
            }
            logger.info(TAG, "Download interrupted: ${e::class.simpleName}: ${e.message}")
            attachments.setTransferred(id, partSize(id, size))
            failed = true
        } finally {
            clearProgress(id)
            lock.withLock { if (downloads[id] === job) downloads.remove(id) }
        }
        if (failed) scheduleRetry(from, id)
    }

    /** A dropped FILE connection while the control link is still up: ask again a few times. */
    private fun scheduleRetry(peer: Fingerprint, id: MessageId) {
        val attempt = (retries[id] ?: 0) + 1
        if (attempt > MAX_RETRIES) return
        retries[id] = attempt
        scope.launch {
            delay(RETRY_DELAY_MS * attempt)
            val stored = attachments.get(id) ?: return@launch
            if (stored.state == TransferState.Requested && !lock.withLock { downloads.containsKey(id) }) {
                requestBytes(peer, id, partSize(id, stored.meta.size))
            }
        }
    }

    /** Decrypts every chunk (authenticating it) and checks the sender's hash of the whole file. */
    private suspend fun verify(path: Path, key: ByteArray, size: Long, expected: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (fileSystem.metadataOrNull(path)?.size != AttachmentCrypto.encryptedLength(size)) return@withContext false
        runCatching {
            val hash = Sodium.blake2bStream(AttachmentMeta.HASH_BYTES)
            EncryptedAttachmentReader(fileSystem, path, key, size).use { reader ->
                val buffer = ByteArray(AttachmentCrypto.CHUNK)
                var position = 0L
                while (position < size) {
                    val n = reader.read(position, buffer, 0, buffer.size)
                    if (n <= 0) error("Attachment is truncated")
                    hash.update(buffer, n)
                    position += n
                }
            }
            Sodium.constantTimeEquals(hash.finish(), expected)
        }.getOrDefault(false)
    }

    private fun openReader(stored: StoredAttachment): AttachmentReader {
        val key = stored.fileKey
        return if (key == null) {
            PlainAttachmentReader(fileSystem.openReadOnly(finalPath(stored.id)))
        } else {
            EncryptedAttachmentReader(fileSystem, finalPath(stored.id), key, stored.meta.size)
        }
    }

    /** Plaintext bytes safely stored in the partial download: its complete chunks. */
    private suspend fun partSize(id: MessageId, max: Long): Long = withContext(Dispatchers.IO) {
        AttachmentCrypto.completePlainLength(fileSystem.metadataOrNull(partPath(id))?.size ?: 0L).coerceIn(0, max)
    }

    private fun clearProgress(id: MessageId) = _progress.update { it - id }

    private fun finalPath(id: MessageId) = directory / id.value
    private fun partPath(id: MessageId) = directory / "${id.value}.part"

    private fun parseId(value: String): MessageId? = value.takeIf { it.length == 26 && it.all(Char::isLetterOrDigit) }?.let(::MessageId)

    private companion object {
        const val TAG = "Files"
        const val EXPORTS = "exports"
        const val IO_BUFFER = 64 * 1024
        const val PROGRESS_INTERVAL_MS = 200L
        const val SAVE_INTERVAL_MS = 1_000L
        const val RETRY_DELAY_MS = 3_000L
        const val MAX_RETRIES = 5
    }
}
