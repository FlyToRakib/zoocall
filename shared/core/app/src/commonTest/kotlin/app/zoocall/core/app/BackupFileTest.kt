package app.zoocall.core.app

import app.zoocall.core.crypto.Sodium
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The encrypted .zcbackup container (protocol/spec/backup-file.md). */
class BackupFileTest {
    private fun seal(payload: ByteArray, passphrase: String = "correct horse"): ByteArray =
        Buffer().also { BackupFile.write(payload, passphrase, it) }.readByteArray()

    private fun open(file: ByteArray, passphrase: String = "correct horse"): ByteArray =
        BackupFile.read(Buffer().write(file), passphrase)

    private fun failure(block: () -> Unit): RestoreResult = assertFailsWith<BackupFormatException> { block() }.result

    @Test
    fun multiChunkPayloadRoundTripsAndEmptyOneToo() = runTest {
        Sodium.ensureInitialized()
        val payload = ByteArray(BackupFile.CHUNK * 2 + 123) { (it % 251).toByte() }
        val file = seal(payload)
        assertEquals(BackupFile.HEADER_BYTES + payload.size + 3 * Sodium.CHACHAPOLY_TAG_BYTES, file.size)
        assertContentEquals(payload, open(file))
        assertContentEquals(ByteArray(0), open(seal(ByteArray(0))))
    }

    @Test
    fun wrongPassphraseAndForeignFilesAreToldApart() = runTest {
        Sodium.ensureInitialized()
        val file = seal("hello".encodeToByteArray())
        assertEquals(RestoreResult.WrongPassphrase, failure { open(file, "wrong horse") })
        assertEquals(RestoreResult.NotABackup, failure { open("PK a zip file, not a backup".encodeToByteArray()) })
        assertEquals(RestoreResult.NotABackup, failure { open(ByteArray(3)) })
    }

    @Test
    fun cutOffOrAlteredFilesAreDamaged() = runTest {
        Sodium.ensureInitialized()
        // Three chunks: two full ones and a short last one.
        val file = seal(ByteArray(BackupFile.CHUNK * 2 + 10) { 7 })
        // Cut exactly after the first full chunk: without the last-chunk flag this would look complete.
        val oneChunk = BackupFile.HEADER_BYTES + BackupFile.CHUNK + Sodium.CHACHAPOLY_TAG_BYTES
        assertEquals(RestoreResult.WrongPassphrase, failure { open(file.copyOf(oneChunk)) })
        val twoChunks = oneChunk + BackupFile.CHUNK + Sodium.CHACHAPOLY_TAG_BYTES
        assertEquals(RestoreResult.Damaged, failure { open(file.copyOf(twoChunks)) })

        val flipped = file.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() }
        assertEquals(RestoreResult.Damaged, failure { open(flipped) })
    }
}
