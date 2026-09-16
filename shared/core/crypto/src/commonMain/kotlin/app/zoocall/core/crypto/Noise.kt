package app.zoocall.core.crypto

/**
 * `Noise_XX_25519_ChaChaPoly_BLAKE2b`, implemented from the Noise Protocol Framework spec (rev. 34).
 *
 * This is security-critical code: it only composes libsodium primitives, is verified against the
 * official test vectors (protocol/test-vectors) and requires two-person review for changes.
 *
 * ```
 * XX:
 *   -> e
 *   <- e, ee, s, es
 *   -> s, se
 * ```
 */
object NoiseXX {
    const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_BLAKE2b"
    const val HASH_LEN = 64
    const val DH_LEN = 32
    const val MAX_MESSAGE_LEN = 65_535
}

class KeyPair(val publicKey: ByteArray, val secretKey: ByteArray) {
    init {
        require(publicKey.size == Sodium.X25519_KEY_BYTES && secretKey.size == Sodium.X25519_KEY_BYTES)
    }

    companion object {
        fun generate(): KeyPair = fromSecret(Sodium.randomBytes(Sodium.X25519_KEY_BYTES))
        fun fromSecret(secret: ByteArray): KeyPair = KeyPair(Sodium.x25519Base(secret), secret.copyOf())
    }
}

/** Transport cipher for one direction after the handshake. Not thread-safe; callers serialize access. */
class NoiseCipher internal constructor(key: ByteArray) {
    private var key: ByteArray = key.copyOf()
    private var nonce: ULong = 0u

    fun encrypt(plaintext: ByteArray, ad: ByteArray = EMPTY): ByteArray {
        val ct = Sodium.chaChaPolyEncrypt(key, nonceBytes(nonce), ad, plaintext)
        advance()
        return ct
    }

    fun decrypt(ciphertext: ByteArray, ad: ByteArray = EMPTY): ByteArray {
        val pt = Sodium.chaChaPolyDecrypt(key, nonceBytes(nonce), ad, ciphertext)
        advance()
        return pt
    }

    /** Noise `Rekey()`: k = ENCRYPT(k, maxnonce, zerolen, zeros)[0..32]. */
    fun rekey() {
        val next = Sodium.chaChaPolyEncrypt(key, nonceBytes(ULong.MAX_VALUE), EMPTY, ByteArray(32)).copyOf(32)
        Sodium.wipe(key)
        key = next
    }

    fun destroy() = Sodium.wipe(key)

    private fun advance() {
        // 2^64-1 is reserved for rekey; a connection will never get near this but we fail closed anyway.
        if (nonce == ULong.MAX_VALUE - 1u) throw CryptoException("Nonce exhausted")
        nonce++
    }
}

/** Result of a completed handshake. */
class NoiseSession(
    val sender: NoiseCipher,
    val receiver: NoiseCipher,
    /** Channel binding value, used to derive the safety code. */
    val handshakeHash: ByteArray,
    val remoteStaticKey: ByteArray,
)

class NoiseHandshake private constructor(
    val isInitiator: Boolean,
    private val s: KeyPair,
    private var e: KeyPair?,
) {
    private val symmetric = SymmetricState()
    private var re: ByteArray? = null
    private var rs: ByteArray? = null
    private var step = 0

    val isComplete: Boolean get() = step == PATTERN.size

    /** The peer's static public key; available after message 2 (initiator) or 3 (responder). */
    val remoteStaticKey: ByteArray? get() = rs?.copyOf()

    /** True when it's this side's turn to write. */
    val isMyTurn: Boolean get() = !isComplete && (step % 2 == 0) == isInitiator

    fun writeMessage(payload: ByteArray = EMPTY): ByteArray {
        check(isMyTurn) { "Not our turn to write" }
        val out = ArrayList<ByteArray>()
        for (token in PATTERN[step]) {
            when (token) {
                Token.E -> {
                    val eph = e ?: KeyPair.generate().also { e = it }
                    out += eph.publicKey
                    symmetric.mixHash(eph.publicKey)
                }
                Token.S -> out += symmetric.encryptAndHash(s.publicKey)
                else -> mixDh(token)
            }
        }
        out += symmetric.encryptAndHash(payload)
        step++
        val message = concat(out)
        if (message.size > NoiseXX.MAX_MESSAGE_LEN) throw CryptoException("Handshake message too large")
        return message
    }

    fun readMessage(message: ByteArray): ByteArray {
        check(!isComplete && !isMyTurn) { "Not our turn to read" }
        if (message.size > NoiseXX.MAX_MESSAGE_LEN) throw CryptoException("Handshake message too large")
        var offset = 0
        fun take(n: Int): ByteArray {
            if (offset + n > message.size) throw CryptoException("Handshake message truncated")
            return message.copyOfRange(offset, offset + n).also { offset += n }
        }
        for (token in PATTERN[step]) {
            when (token) {
                Token.E -> {
                    val key = take(NoiseXX.DH_LEN)
                    re = key
                    symmetric.mixHash(key)
                }
                Token.S -> {
                    val len = NoiseXX.DH_LEN + if (symmetric.hasKey) Sodium.CHACHAPOLY_TAG_BYTES else 0
                    rs = symmetric.decryptAndHash(take(len))
                }
                else -> mixDh(token)
            }
        }
        val payload = symmetric.decryptAndHash(message.copyOfRange(offset, message.size))
        step++
        return payload
    }

    fun split(): NoiseSession {
        check(isComplete) { "Handshake not complete" }
        val (k1, k2) = symmetric.split()
        val c1 = NoiseCipher(k1)
        val c2 = NoiseCipher(k2)
        Sodium.wipe(k1)
        Sodium.wipe(k2)
        e?.let { Sodium.wipe(it.secretKey) }
        return NoiseSession(
            sender = if (isInitiator) c1 else c2,
            receiver = if (isInitiator) c2 else c1,
            handshakeHash = symmetric.handshakeHash(),
            remoteStaticKey = requireNotNull(rs),
        )
    }

    private fun mixDh(token: Token) {
        val eph = requireNotNull(e)
        val shared = when (token) {
            Token.EE -> Sodium.x25519(eph.secretKey, requireNotNull(re))
            Token.ES -> if (isInitiator) Sodium.x25519(eph.secretKey, requireNotNull(rs)) else Sodium.x25519(s.secretKey, requireNotNull(re))
            Token.SE -> if (isInitiator) Sodium.x25519(s.secretKey, requireNotNull(re)) else Sodium.x25519(eph.secretKey, requireNotNull(rs))
            else -> error("Not a DH token")
        }
        symmetric.mixKey(shared)
        Sodium.wipe(shared)
    }

    private enum class Token { E, S, EE, ES, SE }

    companion object {
        private val PATTERN = listOf(
            listOf(Token.E),
            listOf(Token.E, Token.EE, Token.S, Token.ES),
            listOf(Token.S, Token.SE),
        )

        /**
         * @param prologue bound into the transcript; Zoocall uses `"zoocall/1"`.
         * @param ephemeral fixed ephemeral key, **only** for test vectors.
         */
        fun create(
            initiator: Boolean,
            staticKey: KeyPair,
            prologue: ByteArray,
            ephemeral: KeyPair? = null,
        ): NoiseHandshake = NoiseHandshake(initiator, staticKey, ephemeral).also {
            it.symmetric.mixHash(prologue)
        }
    }
}

private class SymmetricState {
    private var ck: ByteArray
    private var h: ByteArray
    private var k: ByteArray? = null
    private var n: ULong = 0u

    init {
        val name = NoiseXX.PROTOCOL_NAME.encodeToByteArray()
        h = if (name.size <= NoiseXX.HASH_LEN) name.copyOf(NoiseXX.HASH_LEN) else hash(name)
        ck = h.copyOf()
    }

    val hasKey: Boolean get() = k != null

    fun mixKey(ikm: ByteArray) {
        val (newCk, tempK) = hkdf2(ck, ikm)
        ck = newCk
        k = tempK.copyOf(Sodium.CHACHAPOLY_KEY_BYTES)
        n = 0u
    }

    fun mixHash(data: ByteArray) {
        h = hash(h + data)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val key = k
        val ct = if (key == null) plaintext else Sodium.chaChaPolyEncrypt(key, nonceBytes(n++), h, plaintext)
        mixHash(ct)
        return ct
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val key = k
        val pt = if (key == null) ciphertext else Sodium.chaChaPolyDecrypt(key, nonceBytes(n++), h, ciphertext)
        mixHash(ciphertext)
        return pt
    }

    fun split(): Pair<ByteArray, ByteArray> {
        val (a, b) = hkdf2(ck, EMPTY)
        return a.copyOf(Sodium.CHACHAPOLY_KEY_BYTES) to b.copyOf(Sodium.CHACHAPOLY_KEY_BYTES)
    }

    fun handshakeHash(): ByteArray = h.copyOf()
}

private const val BLOCK_LEN = 128
private val EMPTY = ByteArray(0)

private fun hash(data: ByteArray): ByteArray = Sodium.blake2b(data, NoiseXX.HASH_LEN)

private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
    val block = (if (key.size > BLOCK_LEN) hash(key) else key).copyOf(BLOCK_LEN)
    val inner = ByteArray(BLOCK_LEN) { (block[it].toInt() xor 0x36).toByte() }
    val outer = ByteArray(BLOCK_LEN) { (block[it].toInt() xor 0x5c).toByte() }
    return hash(outer + hash(inner + data))
}

private fun hkdf2(chainingKey: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
    val temp = hmac(chainingKey, ikm)
    val out1 = hmac(temp, byteArrayOf(1))
    val out2 = hmac(temp, out1 + byteArrayOf(2))
    return out1 to out2
}

private fun nonceBytes(n: ULong): ByteArray {
    val nonce = ByteArray(Sodium.CHACHAPOLY_NONCE_BYTES)
    for (i in 0 until 8) nonce[4 + i] = (n shr (8 * i)).toByte()
    return nonce
}

private fun concat(parts: List<ByteArray>): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var offset = 0
    for (p in parts) {
        p.copyInto(out, offset)
        offset += p.size
    }
    return out
}
