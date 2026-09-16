package app.zoocall.core.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Argon2id helpers used by app lock (passcode hash) and encrypted exports (passphrase key). */
class PasswordHashTest {
    @Test
    fun passcodeHashVerifiesOnlyTheRightPasscode() = runTest {
        Sodium.ensureInitialized()
        val hash = Sodium.passwordHash("1234")
        assertTrue(hash.startsWith("\$argon2id\$"), "the hash names its algorithm and parameters")
        assertTrue(Sodium.verifyPasswordHash(hash, "1234"))
        assertFalse(Sodium.verifyPasswordHash(hash, "1235"))
        assertFalse(Sodium.verifyPasswordHash("not a hash", "1234"))
    }

    @Test
    fun passphraseKeyIsStablePerSaltAndDiffersAcrossSalts() = runTest {
        Sodium.ensureInitialized()
        val salt = ByteArray(16) { it.toByte() }
        val ops = 2L
        val mem = 8 * 1024 * 1024
        val key = Sodium.passwordKey("correct horse", salt, ops, mem)
        assertContentEquals(key, Sodium.passwordKey("correct horse", salt, ops, mem))
        assertFalse(key.contentEquals(Sodium.passwordKey("correct horse", ByteArray(16) { 9 }, ops, mem)))
        assertFalse(key.contentEquals(Sodium.passwordKey("wrong horse", salt, ops, mem)))
        assertFailsWith<CryptoException> { Sodium.passwordKey("short salt", ByteArray(3), ops, mem) }
    }
}
