package app.zoocall.core.protocol

import app.zoocall.protocol.v1.Envelope
import app.zoocall.protocol.v1.Fragment
import okio.ByteString.Companion.toByteString

class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns logical envelopes into frame plaintexts (≤ [ProtocolConstants.MAX_FRAME_PLAINTEXT]) and back.
 *
 * One instance per connection. Not thread-safe; the connection serializes access.
 */
class EnvelopeCodec {
    private var nextMessageId = 1L
    private var pending: PendingMessage? = null

    fun encode(envelope: Envelope): List<ByteArray> {
        val bytes = Envelope.ADAPTER.encode(envelope)
        if (bytes.size > ProtocolConstants.MAX_LOGICAL_MESSAGE) throw ProtocolException("Message too large")
        if (bytes.size <= ProtocolConstants.MAX_FRAME_PLAINTEXT) return listOf(bytes)

        val messageId = nextMessageId++
        val chunks = bytes.toList().chunked(ProtocolConstants.FRAGMENT_CHUNK)
        return chunks.mapIndexed { index, chunk ->
            Envelope.ADAPTER.encode(
                Envelope(
                    seq = envelope.seq,
                    fragment = Fragment(
                        message_id = messageId,
                        index = index,
                        count = chunks.size,
                        chunk = chunk.toByteArray().toByteString(),
                    ),
                ),
            )
        }
    }

    /**
     * Decodes one frame plaintext. Returns the logical envelope, or null when a fragment was
     * buffered and more are needed. Throws [ProtocolException] for malformed or oversized input.
     */
    fun decode(plaintext: ByteArray): Envelope? {
        if (plaintext.size > ProtocolConstants.MAX_FRAME_PLAINTEXT) throw ProtocolException("Frame too large")
        val envelope = try {
            Envelope.ADAPTER.decode(plaintext)
        } catch (e: Exception) {
            throw ProtocolException("Malformed envelope", e)
        }
        val fragment = envelope.fragment ?: run {
            if (pending != null) throw ProtocolException("Interleaved message during fragment sequence")
            return envelope
        }
        return accept(fragment)
    }

    private fun accept(fragment: Fragment): Envelope? {
        val maxCount = ProtocolConstants.MAX_LOGICAL_MESSAGE / ProtocolConstants.FRAGMENT_CHUNK + 1
        if (fragment.count !in 2..maxCount) throw ProtocolException("Bad fragment count")
        val current = pending
        val state = if (fragment.index == 0) {
            if (current != null) throw ProtocolException("New fragment sequence before previous finished")
            PendingMessage(fragment.message_id, fragment.count).also { pending = it }
        } else {
            current ?: throw ProtocolException("Fragment without start")
        }
        if (fragment.message_id != state.messageId || fragment.count != state.count || fragment.index != state.received) {
            throw ProtocolException("Fragment out of order")
        }
        state.size += fragment.chunk.size
        if (state.size > ProtocolConstants.MAX_LOGICAL_MESSAGE) throw ProtocolException("Message too large")
        state.parts += fragment.chunk.toByteArray()
        state.received++
        if (state.received < state.count) return null

        pending = null
        val whole = ByteArray(state.size)
        var offset = 0
        for (part in state.parts) {
            part.copyInto(whole, offset)
            offset += part.size
        }
        val envelope = try {
            Envelope.ADAPTER.decode(whole)
        } catch (e: Exception) {
            throw ProtocolException("Malformed envelope", e)
        }
        if (envelope.fragment != null) throw ProtocolException("Nested fragment")
        return envelope
    }

    private class PendingMessage(val messageId: Long, val count: Int) {
        val parts = ArrayList<ByteArray>()
        var received = 0
        var size = 0
    }
}
