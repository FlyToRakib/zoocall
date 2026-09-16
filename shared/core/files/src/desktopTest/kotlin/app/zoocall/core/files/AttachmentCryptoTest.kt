package app.zoocall.core.files

import app.zoocall.core.crypto.CryptoException
import app.zoocall.core.crypto.Sodium
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.use
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AttachmentCryptoTest {
    private val fs = FileSystem.SYSTEM
    private val dir = Files.createTempDirectory("zoocall-crypto").toFile().apply { deleteOnExit() }.toOkioPath()

    private fun readAll(path: okio.Path, key: ByteArray, size: Int): ByteArray =
        EncryptedAttachmentReader(fs, path, key, size.toLong()).use { reader ->
            val out = ByteArray(size)
            var position = 0
            while (position < size) position += reader.read(position.toLong(), out, position, size - position)
            out
        }

    @Test
    fun roundTripWithRandomAccess() = runTest {
        Sodium.ensureInitialized()
        val key = AttachmentCrypto.newKey()
        val bytes = Random.nextBytes(200_001)
        val path = dir / "a"
        EncryptedAttachmentWriter(fs, path, key, 0).use { writer ->
            // Odd write sizes cross chunk boundaries.
            var offset = 0
            while (offset < bytes.size) {
                val n = minOf(7_919, bytes.size - offset)
                writer.write(bytes, offset, n)
                offset += n
            }
            writer.finish()
        }
        assertEquals(AttachmentCrypto.encryptedLength(bytes.size.toLong()), fs.metadata(path).size)
        assertFalse(fs.read(path) { readByteArray() }.copyOfRange(4, 1_004).contentEquals(bytes.copyOfRange(0, 1_000)))
        assertContentEquals(bytes, readAll(path, key, bytes.size))

        EncryptedAttachmentReader(fs, path, key, bytes.size.toLong()).use { reader ->
            val buffer = ByteArray(10)
            val position = 3L * AttachmentCrypto.CHUNK - 5
            assertEquals(5, reader.read(position, buffer, 0, buffer.size))
            assertContentEquals(bytes.copyOfRange(position.toInt(), position.toInt() + 5), buffer.copyOf(5))
            assertEquals(-1, reader.read(bytes.size.toLong(), buffer, 0, buffer.size))
        }
    }

    @Test
    fun resumeKeepsCompleteChunksOnly() = runTest {
        Sodium.ensureInitialized()
        val key = AttachmentCrypto.newKey()
        val bytes = Random.nextBytes(150_000)
        val path = dir / "b"
        // Interrupted after 100 KB without finishing: the partial second chunk is never written.
        EncryptedAttachmentWriter(fs, path, key, 0).use { it.write(bytes, 0, 100_000) }
        val resumeAt = AttachmentCrypto.completePlainLength(fs.metadata(path).size!!)
        assertEquals(AttachmentCrypto.CHUNK.toLong(), resumeAt)

        EncryptedAttachmentWriter(fs, path, key, resumeAt).use { writer ->
            writer.write(bytes, resumeAt.toInt(), bytes.size - resumeAt.toInt())
            writer.finish()
        }
        assertContentEquals(bytes, readAll(path, key, bytes.size))
    }

    @Test
    fun alteredOrForeignChunksAreRejected() = runTest {
        Sodium.ensureInitialized()
        val key = AttachmentCrypto.newKey()
        val bytes = Random.nextBytes(3 * AttachmentCrypto.CHUNK)
        val path = dir / "c"
        EncryptedAttachmentWriter(fs, path, key, 0).use {
            it.write(bytes, 0, bytes.size)
            it.finish()
        }
        assertFailsWith<CryptoException> { readAll(path, AttachmentCrypto.newKey(), bytes.size) }

        val stored = fs.read(path) { readByteArray() }
        stored[AttachmentCrypto.HEADER + AttachmentCrypto.CHUNK + AttachmentCrypto.TAG + 10] =
            (stored[AttachmentCrypto.HEADER + AttachmentCrypto.CHUNK + AttachmentCrypto.TAG + 10].toInt() xor 1).toByte()
        fs.write(path) { write(stored) }
        assertFailsWith<CryptoException> { readAll(path, key, bytes.size) }
    }
}
