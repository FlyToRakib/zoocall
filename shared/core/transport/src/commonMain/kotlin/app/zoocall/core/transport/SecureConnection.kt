package app.zoocall.core.transport

import app.zoocall.core.crypto.CryptoException
import app.zoocall.core.crypto.Fingerprints
import app.zoocall.core.crypto.Identity
import app.zoocall.core.crypto.NoiseHandshake
import app.zoocall.core.crypto.NoiseSession
import app.zoocall.core.crypto.SafetyCodes
import app.zoocall.core.crypto.Sodium
import app.zoocall.core.model.Fingerprint
import app.zoocall.core.protocol.EnvelopeCodec
import app.zoocall.core.protocol.ProtocolConstants
import app.zoocall.protocol.v1.Envelope
import app.zoocall.protocol.v1.HandshakePayload
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class HandshakeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The peer speaks an incompatible protocol major version (edge case C30). */
class IncompatibleVersionException(val remoteVersions: List<Int>) : Exception("Incompatible protocol version")

/**
 * An authenticated, encrypted connection after a successful Noise XX handshake.
 * [send] may be called concurrently; [receive] must be called from a single reader coroutine.
 */
class SecureConnection internal constructor(
    private val link: ByteLink,
    private val session: NoiseSession,
    val isInitiator: Boolean,
    val remotePayload: HandshakePayload,
) {
    val remoteStaticKey: ByteArray = session.remoteStaticKey
    val remoteFingerprint: Fingerprint = Fingerprints.of(session.remoteStaticKey)
    val remoteHost: String get() = link.remoteHost

    /** 6-digit code both people compare to verify the connection (docs/04-security-privacy.md §5). */
    val safetyCode: String = SafetyCodes.sessionCode(session.handshakeHash)

    private val sendLock = Mutex()
    private val sendCodec = EnvelopeCodec()
    private val receiveCodec = EnvelopeCodec()
    private var seq = 0L
    private var sentBytes = 0L
    private var receivedBytes = 0L

    suspend fun send(envelope: Envelope) = sendLock.withLock {
        val frames = sendCodec.encode(envelope.copy(seq = ++seq))
        for (plaintext in frames) {
            link.writeFrame(session.sender.encrypt(plaintext))
            sentBytes += plaintext.size
            if (sentBytes >= ProtocolConstants.REKEY_BYTES) {
                session.sender.rekey()
                sentBytes = 0
            }
        }
        link.flush()
    }

    suspend fun receive(): Envelope {
        while (true) {
            receiveCodec.decode(receiveFrame())?.let { return it }
        }
    }

    /** FILE connections: sends one raw chunk (≤ [ProtocolConstants.MAX_FRAME_PLAINTEXT] bytes) as one frame. */
    suspend fun sendChunk(chunk: ByteArray) = sendLock.withLock {
        require(chunk.size in 1..ProtocolConstants.MAX_FRAME_PLAINTEXT) { "Chunk size out of range" }
        link.writeFrame(session.sender.encrypt(chunk))
        sentBytes += chunk.size
        if (sentBytes >= ProtocolConstants.REKEY_BYTES) {
            session.sender.rekey()
            sentBytes = 0
        }
        link.flush()
    }

    /** FILE connections: the next raw chunk. Must be called from a single reader coroutine. */
    suspend fun receiveChunk(): ByteArray = receiveFrame()

    private suspend fun receiveFrame(): ByteArray {
        val frame = link.readFrame()
        val plaintext = try {
            session.receiver.decrypt(frame)
        } catch (e: CryptoException) {
            throw HandshakeException("Frame authentication failed", e)
        }
        receivedBytes += plaintext.size
        if (receivedBytes >= ProtocolConstants.REKEY_BYTES) {
            session.receiver.rekey()
            receivedBytes = 0
        }
        return plaintext
    }

    fun close() {
        link.close()
        session.sender.destroy()
        session.receiver.destroy()
    }

    companion object {
        /**
         * Runs the Noise XX handshake over [link]. Closes the link on failure.
         * Message 1 has no payload; messages 2 and 3 carry an encrypted [HandshakePayload].
         */
        suspend fun handshake(
            link: ByteLink,
            identity: Identity,
            initiator: Boolean,
            localPayload: HandshakePayload,
            timeoutMs: Long = ProtocolConstants.HANDSHAKE_TIMEOUT_MS,
        ): SecureConnection = try {
            withTimeout(timeoutMs) {
                val noise = NoiseHandshake.create(initiator, identity.keyPair, ProtocolConstants.PROLOGUE)
                val payloadBytes = HandshakePayload.ADAPTER.encode(localPayload)
                val remoteBytes: ByteArray
                if (initiator) {
                    link.writeFrame(noise.writeMessage())
                    link.flush()
                    remoteBytes = noise.readMessage(link.readFrame())
                    link.writeFrame(noise.writeMessage(payloadBytes))
                    link.flush()
                } else {
                    noise.readMessage(link.readFrame())
                    link.writeFrame(noise.writeMessage(payloadBytes))
                    link.flush()
                    remoteBytes = noise.readMessage(link.readFrame())
                }
                val remote = try {
                    HandshakePayload.ADAPTER.decode(remoteBytes)
                } catch (e: Exception) {
                    throw HandshakeException("Malformed handshake payload", e)
                }
                if (ProtocolConstants.MAJOR_VERSION !in remote.protocol_versions) {
                    throw IncompatibleVersionException(remote.protocol_versions)
                }
                val session = noise.split()
                if (Sodium.constantTimeEquals(session.remoteStaticKey, identity.publicKey)) {
                    throw HandshakeException("Connected to self")
                }
                SecureConnection(link, session, initiator, remote)
            }
        } catch (e: Throwable) {
            link.close()
            when (e) {
                is IncompatibleVersionException, is HandshakeException -> throw e
                else -> throw HandshakeException(e.message ?: "Handshake failed", e)
            }
        }
    }
}
