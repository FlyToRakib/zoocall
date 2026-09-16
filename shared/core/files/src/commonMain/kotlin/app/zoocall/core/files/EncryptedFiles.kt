package app.zoocall.core.files

import okio.Closeable
import okio.FileSystem
import okio.Path

/** Encrypted-at-rest files that aren't chat attachments (call recordings), in the attachment format (ADR 0004). */
object EncryptedFiles {
    fun newKey(): ByteArray = AttachmentCrypto.newKey()

    /** A new encrypted file at [path] to stream plaintext into. Call [EncryptedFileWriter.finish] at the end. */
    fun create(fileSystem: FileSystem, path: Path, key: ByteArray): EncryptedFileWriter {
        path.parent?.let(fileSystem::createDirectories)
        return EncryptedFileWriter(EncryptedAttachmentWriter(fileSystem, path, key, startPlainOffset = 0))
    }

    /** The whole plaintext; null when the file is missing, cut off or was altered. */
    fun readAll(fileSystem: FileSystem, path: Path, key: ByteArray): ByteArray? = runCatching {
        val size = fileSystem.metadataOrNull(path)?.size ?: return null
        val sealedChunk = AttachmentCrypto.CHUNK + AttachmentCrypto.TAG
        val chunks = (size - AttachmentCrypto.HEADER + sealedChunk - 1) / sealedChunk
        val plainLength = size - AttachmentCrypto.HEADER - chunks * AttachmentCrypto.TAG
        val reader = EncryptedAttachmentReader(fileSystem, path, key, plainLength)
        try {
            val out = ByteArray(plainLength.toInt())
            var position = 0
            while (position < out.size) {
                val n = reader.read(position.toLong(), out, position, out.size - position)
                if (n <= 0) break
                position += n
            }
            out
        } finally {
            reader.close()
        }
    }.getOrNull()
}

/** Writes from one thread at a time. */
class EncryptedFileWriter internal constructor(private val writer: EncryptedAttachmentWriter) : Closeable {
    fun write(bytes: ByteArray) = writer.write(bytes, 0, bytes.size)

    /** Seals the last chunk. */
    fun finish() = writer.finish()

    override fun close() = writer.close()
}
