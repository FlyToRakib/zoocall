package app.zoocall.core.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.generichash.GenericHash
import com.ionspin.kotlin.crypto.pwhash.PasswordHash
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_MEMLIMIT_INTERACTIVE
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_OPSLIMIT_INTERACTIVE
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_argon2id_ALG_ARGON2ID13
import com.ionspin.kotlin.crypto.scalarmult.ScalarMultiplication
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import com.ionspin.kotlin.crypto.util.LibsodiumUtil
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The only file that touches libsodium. Everything else in Zoocall uses these ByteArray helpers,
 * so swapping the binding (e.g. to lazysodium) only changes this file.
 */
@OptIn(ExperimentalUnsignedTypes::class)
object Sodium {
    const val X25519_KEY_BYTES = 32
    const val CHACHAPOLY_KEY_BYTES = 32
    const val CHACHAPOLY_NONCE_BYTES = 12
    const val CHACHAPOLY_TAG_BYTES = 16

    /** Argon2id salt size (crypto_pwhash_SALTBYTES). */
    const val PASSWORD_SALT_BYTES = 16

    private val initLock = Mutex()

    /** Loads libsodium. Safe to call many times and from any thread. */
    suspend fun ensureInitialized() {
        if (LibsodiumInitializer.isInitialized()) return
        initLock.withLock {
            if (!LibsodiumInitializer.isInitialized()) LibsodiumInitializer.initialize()
        }
    }

    fun randomBytes(size: Int): ByteArray = LibsodiumRandom.buf(size).asByteArray()

    fun x25519Base(secretKey: ByteArray): ByteArray =
        ScalarMultiplication.scalarMultiplicationBase(secretKey.asUByteArray()).asByteArray()

    /** X25519. Throws [CryptoException] when the result is all zeros (low-order point). */
    fun x25519(secretKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(secretKey.size == X25519_KEY_BYTES && publicKey.size == X25519_KEY_BYTES)
        val shared = try {
            ScalarMultiplication.scalarMultiplication(secretKey.asUByteArray(), publicKey.asUByteArray()).asByteArray()
        } catch (e: Exception) {
            throw CryptoException("X25519 failed", e)
        }
        if (shared.all { it == 0.toByte() }) throw CryptoException("X25519 produced a low-order result")
        return shared
    }

    fun blake2b(data: ByteArray, outputLength: Int): ByteArray =
        GenericHash.genericHash(data.asUByteArray(), outputLength, null).asByteArray()

    /** Keyed BLAKE2b, used as a MAC. [key] is 16–64 bytes. */
    fun keyedBlake2b(key: ByteArray, data: ByteArray, outputLength: Int): ByteArray =
        GenericHash.genericHash(data.asUByteArray(), outputLength, key.asUByteArray()).asByteArray()

    /** Incremental BLAKE2b for data too large to hold in memory (file hashes). */
    fun blake2bStream(outputLength: Int): HashStream = HashStream(GenericHash.genericHashInit(outputLength, null))

    class HashStream internal constructor(private val state: com.ionspin.kotlin.crypto.generichash.GenericHashState) {
        fun update(data: ByteArray, size: Int = data.size) {
            if (size <= 0) return
            GenericHash.genericHashUpdate(state, (if (size == data.size) data else data.copyOf(size)).asUByteArray())
        }

        fun finish(): ByteArray = GenericHash.genericHashFinal(state).asByteArray()
    }

    fun chaChaPolyEncrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, plaintext: ByteArray): ByteArray =
        AuthenticatedEncryptionWithAssociatedData.chaCha20Poly1305IetfEncrypt(
            plaintext.asUByteArray(),
            ad.asUByteArray(),
            nonce.asUByteArray(),
            key.asUByteArray(),
        ).asByteArray()

    /** Throws [CryptoException] when authentication fails. */
    fun chaChaPolyDecrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < CHACHAPOLY_TAG_BYTES) throw CryptoException("Ciphertext too short")
        return try {
            AuthenticatedEncryptionWithAssociatedData.chaCha20Poly1305IetfDecrypt(
                ciphertext.asUByteArray(),
                ad.asUByteArray(),
                nonce.asUByteArray(),
                key.asUByteArray(),
            ).asByteArray()
        } catch (e: Exception) {
            throw CryptoException("Decryption failed", e)
        }
    }

    /** Argon2id hash of a passcode in libsodium's self-describing string format (salt and limits included). */
    fun passwordHash(password: String): String =
        PasswordHash.str(password, crypto_pwhash_OPSLIMIT_INTERACTIVE.toULong(), crypto_pwhash_MEMLIMIT_INTERACTIVE)

    /** Constant-time check of [password] against a [passwordHash] string. False for malformed hashes. */
    fun verifyPasswordHash(hash: String, password: String): Boolean =
        runCatching { PasswordHash.strVerify(hash, password) }.getOrDefault(false)

    /** Derives a [length]-byte key from a passphrase with Argon2id (encrypted exports). */
    fun passwordKey(password: String, salt: ByteArray, opsLimit: Long, memLimit: Int, length: Int = CHACHAPOLY_KEY_BYTES): ByteArray {
        // The binding hands the array to native code as-is, which reads SALT_BYTES from it: check first.
        if (salt.size != PASSWORD_SALT_BYTES) throw CryptoException("Salt must be $PASSWORD_SALT_BYTES bytes")
        return try {
            PasswordHash.pwhash(length, password, salt.asUByteArray(), opsLimit.toULong(), memLimit, crypto_pwhash_argon2id_ALG_ARGON2ID13).asByteArray()
        } catch (e: Exception) {
            throw CryptoException("Key derivation failed", e)
        }
    }

    /** Best-effort wipe. The JVM may still hold copies, see docs/04-security-privacy.md §3. */
    fun wipe(bytes: ByteArray) {
        val view = bytes.asUByteArray()
        LibsodiumUtil.memzero(view)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}

class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
