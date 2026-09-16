package app.zoocall.core.app

import app.zoocall.core.crypto.Identity
import app.zoocall.core.crypto.Sodium
import app.zoocall.core.model.Hex

/**
 * Private discovery, "Contacts only" (docs/03-protocol.md §2): the advertisement carries no name or
 * fingerprint, only short tags that change every 10 minutes. A tag comes from a secret only this
 * device and one contact can compute, so strangers learn nothing and can't follow an advertisement.
 */
object DiscoveryTags {
    const val EPOCH_MS = 10 * 60_000L
    const val MAX_TAGS = 16
    private val CONTEXT = "zoocall-discovery-v1".encodeToByteArray()

    fun epoch(nowMs: Long): Long = nowMs / EPOCH_MS

    /** Both sides derive the same 32-byte secret from their static identity keys. */
    fun pairSecret(identity: Identity, theirPublicKey: ByteArray): ByteArray {
        val shared = Sodium.x25519(identity.keyPair.secretKey, theirPublicKey)
        return try {
            Sodium.blake2b(CONTEXT + shared, 32)
        } finally {
            Sodium.wipe(shared)
        }
    }

    /** 4 bytes of a keyed BLAKE2b over the epoch, as 8 lowercase hex characters. */
    fun tag(pairSecret: ByteArray, epoch: Long): String =
        Hex.encode(Sodium.keyedBlake2b(pairSecret, "tag|$epoch".encodeToByteArray(), 16).copyOf(4)).lowercase()

    /** A new mDNS instance name every epoch, derived from the stable per-install one. */
    fun rotatingInstanceName(stable: String, epoch: Long): String =
        Hex.encode(Sodium.blake2b("$stable|$epoch".encodeToByteArray(), 6)).lowercase()
}
