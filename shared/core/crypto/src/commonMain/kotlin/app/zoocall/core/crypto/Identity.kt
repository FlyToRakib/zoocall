package app.zoocall.core.crypto

import app.zoocall.core.model.Fingerprint

/** This device's long-term identity. The secret key never leaves the device and is never logged. */
class Identity(val keyPair: KeyPair) {
    val publicKey: ByteArray get() = keyPair.publicKey
    val fingerprint: Fingerprint = Fingerprints.of(keyPair.publicKey)

    companion object {
        fun generate(): Identity = Identity(KeyPair.generate())
        fun fromSecret(secret: ByteArray): Identity = Identity(KeyPair.fromSecret(secret))
    }
}

object Fingerprints {
    private val DOMAIN = "zoocall-fp-v1".encodeToByteArray()

    fun of(publicKey: ByteArray): Fingerprint {
        require(publicKey.size == Sodium.X25519_KEY_BYTES) { "Public key must be 32 bytes" }
        return Fingerprint.of(Sodium.blake2b(DOMAIN + publicKey, Fingerprint.SIZE_BYTES))
    }
}

/**
 * Human-comparable codes for verifying that no one is in the middle.
 * Derivations are normative, see protocol/spec/protocol-v1.md §3.
 */
object SafetyCodes {
    private val SAS_DOMAIN = "zoocall-sas".encodeToByteArray()

    /**
     * 6-digit safety code for one session: `BE32(BLAKE2b-32(handshake_hash ‖ "zoocall-sas")) mod 10^6`.
     * Both sides of the same Noise session see the same code; a MITM produces two different sessions.
     */
    fun sessionCode(handshakeHash: ByteArray): String {
        val digest = Sodium.blake2b(handshakeHash + SAS_DOMAIN, 32)
        val value = ((digest[0].toLong() and 0xff) shl 24) or
            ((digest[1].toLong() and 0xff) shl 16) or
            ((digest[2].toLong() and 0xff) shl 8) or
            (digest[3].toLong() and 0xff)
        return (value % 1_000_000).toString().padStart(6, '0')
    }

    /**
     * 60-digit safety number for two identities, independent of who computes it.
     * Each fingerprint contributes 30 digits: 6 groups of 5 digits, each `BE40(chunk) mod 10^5`.
     */
    fun safetyNumber(a: Fingerprint, b: Fingerprint): String {
        val (first, second) = if (a <= b) a to b else b to a
        return digitsFor(first) + digitsFor(second)
    }

    /** Formats a safety number in 12 groups of 5 for display. */
    fun formatSafetyNumber(number: String): List<String> = number.chunked(5)

    private fun digitsFor(fp: Fingerprint): String {
        val bytes = fp.bytes
        return buildString {
            for (group in 0 until 6) {
                var chunk = 0L
                for (i in 0 until 5) chunk = (chunk shl 8) or (bytes[group * 5 + i].toLong() and 0xff)
                append((chunk % 100_000).toString().padStart(5, '0'))
            }
        }
    }
}
