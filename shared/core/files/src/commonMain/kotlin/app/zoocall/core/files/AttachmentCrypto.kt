package app.zoocall.core.files

import app.zoocall.core.crypto.Sodium
import okio.Closeable
import okio.FileHandle
import okio.FileSystem
import okio.Path

/**
 * Attachments at rest (ADR 0004): every file has its own random key, stored in the encrypted
 * database. The file is `"ZCA1"` followed by 64 KiB chunks sealed with ChaCha20-Poly1305 (IETF),
 * each using its chunk index as the nonce, so chunks can be read and appended independently
 * (resumable downloads, uploads from any offset) and can't be swapped or altered undetected.
 */
internal object AttachmentCrypto {
    const val CHUNK = 64 * 1024
    const val TAG = 16
    const val KEY_BYTES = 32
    const val HEADER = 4
    val MAGIC = byteArrayOf('Z'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte())
    val NO_AD = ByteArray(0)

    fun newKey(): ByteArray = Sodium.randomBytes(KEY_BYTES)

    /** Unique per chunk; the key is unique per file, so a nonce is never reused with one key. */
    fun nonce(index: Long): ByteArray = ByteArray(12).also { n ->
        for (i in 0 until 8) n[11 - i] = (index ushr (8 * i)).toByte()
    }

    fun encryptedLength(plainLength: Long): Long = HEADER + plainLength + ((plainLength + CHUNK - 1) / CHUNK) * TAG

    /** Plaintext bytes held by the complete chunks of a (partial) encrypted file. */
    fun completePlainLength(encryptedLength: Long): Long =
        if (encryptedLength < HEADER) 0 else ((encryptedLength - HEADER) / (CHUNK + TAG)) * CHUNK
}

/** Random-access plaintext reads of an attachment file. */
internal interface AttachmentReader : Closeable {
    /** Like [FileHandle.read]: the number of bytes read, or -1 at the end. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/** A file from before at-rest encryption. */
internal class PlainAttachmentReader(private val handle: FileHandle) : AttachmentReader {
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) = handle.read(position, buffer, offset, length)
    override fun close() = handle.close()
}

internal class EncryptedAttachmentReader(
    fileSystem: FileSystem,
    path: Path,
    private val key: ByteArray,
    private val plainLength: Long,
) : AttachmentReader {
    private val handle = fileSystem.openReadOnly(path)
    private val sealed = ByteArray(AttachmentCrypto.CHUNK + AttachmentCrypto.TAG)
    private var chunkIndex = -1L
    private var chunk = ByteArray(0)

    init {
        val magic = ByteArray(AttachmentCrypto.HEADER)
        if (handle.read(0, magic, 0, magic.size) != magic.size || !magic.contentEquals(AttachmentCrypto.MAGIC)) {
            handle.close()
            error("Not an encrypted attachment")
        }
    }

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= plainLength) return -1
        if (length == 0) return 0
        val index = position / AttachmentCrypto.CHUNK
        if (index != chunkIndex) load(index)
        val within = (position - index * AttachmentCrypto.CHUNK).toInt()
        val n = minOf(length, chunk.size - within)
        check(n > 0) { "Attachment is truncated" }
        chunk.copyInto(buffer, offset, within, within + n)
        return n
    }

    private fun load(index: Long) {
        val plain = minOf(AttachmentCrypto.CHUNK.toLong(), plainLength - index * AttachmentCrypto.CHUNK).toInt()
        val size = plain + AttachmentCrypto.TAG
        val start = AttachmentCrypto.HEADER + index * (AttachmentCrypto.CHUNK + AttachmentCrypto.TAG)
        var read = 0
        while (read < size) {
            val n = handle.read(start + read, sealed, read, size - read)
            check(n > 0) { "Attachment is truncated" }
            read += n
        }
        // Throws CryptoException if the chunk was altered, moved or belongs to another file.
        chunk = Sodium.chaChaPolyDecrypt(key, AttachmentCrypto.nonce(index), AttachmentCrypto.NO_AD, sealed.copyOf(size))
        chunkIndex = index
    }

    override fun close() = handle.close()
}

/**
 * Writes plaintext as sealed chunks, starting at [startPlainOffset] (a chunk boundary: resumed
 * downloads keep their complete chunks). Only full chunks are written until [finish], so an
 * interrupted file always ends on a chunk boundary.
 */
internal class EncryptedAttachmentWriter(
    fileSystem: FileSystem,
    path: Path,
    private val key: ByteArray,
    startPlainOffset: Long,
) : Closeable {
    private val handle = fileSystem.openReadWrite(path)
    private val buffer = ByteArray(AttachmentCrypto.CHUNK)
    private var fill = 0
    private var index = startPlainOffset / AttachmentCrypto.CHUNK
    private var position: Long

    init {
        require(startPlainOffset % AttachmentCrypto.CHUNK == 0L) { "Resume offset must be a chunk boundary" }
        if (startPlainOffset == 0L) {
            handle.resize(0)
            handle.write(0, AttachmentCrypto.MAGIC, 0, AttachmentCrypto.HEADER)
        }
        position = AttachmentCrypto.HEADER + index * (AttachmentCrypto.CHUNK + AttachmentCrypto.TAG)
        // Drop anything after the resume point.
        handle.resize(position)
    }

    fun write(data: ByteArray, offset: Int, length: Int) {
        var from = offset
        var left = length
        while (left > 0) {
            val n = minOf(left, AttachmentCrypto.CHUNK - fill)
            data.copyInto(buffer, fill, from, from + n)
            fill += n
            from += n
            left -= n
            if (fill == AttachmentCrypto.CHUNK) seal()
        }
    }

    /** Writes the last, shorter chunk. Call once the whole file has been written. */
    fun finish() {
        if (fill > 0) seal()
        handle.flush()
    }

    private fun seal() {
        val plain = if (fill == buffer.size) buffer else buffer.copyOf(fill)
        val sealed = Sodium.chaChaPolyEncrypt(key, AttachmentCrypto.nonce(index), AttachmentCrypto.NO_AD, plain)
        handle.write(position, sealed, 0, sealed.size)
        position += sealed.size
        index++
        fill = 0
    }

    override fun close() = handle.close()
}
