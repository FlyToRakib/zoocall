package app.zoocall.core.protocol

import app.zoocall.protocol.v1.ChatMessage
import app.zoocall.protocol.v1.Envelope
import app.zoocall.protocol.v1.Fragment
import app.zoocall.protocol.v1.Ping
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvelopeCodecTest {

    @Test
    fun smallMessageIsOneFrame() {
        val codec = EnvelopeCodec()
        val env = Envelope(seq = 7, ping = Ping(nonce = 42))
        val frames = codec.encode(env)
        assertEquals(1, frames.size)
        assertEquals(env, EnvelopeCodec().decode(frames.single()))
    }

    @Test
    fun largeMessageIsFragmentedAndReassembled() {
        val text = "x".repeat(200_000)
        val env = Envelope(seq = 1, chat = ChatMessage(id = "01J", text = text))
        val frames = EnvelopeCodec().encode(env)
        assertTrue(frames.size > 1)
        assertTrue(frames.all { it.size <= ProtocolConstants.MAX_FRAME_PLAINTEXT })

        val receiver = EnvelopeCodec()
        frames.dropLast(1).forEach { assertNull(receiver.decode(it)) }
        assertEquals(env, receiver.decode(frames.last()))
    }

    @Test
    fun oversizedMessageIsRejected() {
        val env = Envelope(chat = ChatMessage(text = "x".repeat(ProtocolConstants.MAX_LOGICAL_MESSAGE + 1)))
        assertFailsWith<ProtocolException> { EnvelopeCodec().encode(env) }
    }

    @Test
    fun outOfOrderFragmentIsRejected() {
        val receiver = EnvelopeCodec()
        val f = { i: Int -> Envelope.ADAPTER.encode(Envelope(fragment = Fragment(1, i, 3, ByteArray(10).toByteString()))) }
        receiver.decode(f(0))
        assertFailsWith<ProtocolException> { receiver.decode(f(2)) }
    }

    @Test
    fun garbageIsRejected() {
        assertFailsWith<ProtocolException> { EnvelopeCodec().decode(byteArrayOf(0x0a, 0x7f, 0x01)) }
    }

    @Test
    fun unknownFieldsAreIgnored() {
        // Field 999 (varint) is unknown to this client and must not cause an error.
        val bytes = Envelope.ADAPTER.encode(Envelope(seq = 3)) + byteArrayOf(0xb8.toByte(), 0x3e, 0x01)
        assertEquals(3L, EnvelopeCodec().decode(bytes)!!.seq)
    }
}
