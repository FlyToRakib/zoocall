package app.zoocall.core.crypto

import app.zoocall.core.model.Hex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** Verifies the Noise implementation against the official cacophony vectors. */
class NoiseVectorsTest {

    private fun vectors(): List<JsonObject> {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "protocol/test-vectors/noise-xx-25519-chachapoly-blake2b.json") }
            .first { it.exists() }
        return Json.parseToJsonElement(file.readText()).jsonObject["vectors"]!!.jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.hex(key: String) = Hex.decode(this[key]!!.jsonPrimitive.content)

    @Test
    fun handshakeAndTransportMatchOfficialVectors() = runTest {
        Sodium.ensureInitialized()
        val all = vectors()
        assertTrue(all.isNotEmpty())
        for (v in all) {
            val initiator = NoiseHandshake.create(
                initiator = true,
                staticKey = KeyPair.fromSecret(v.hex("init_static")),
                prologue = v.hex("init_prologue"),
                ephemeral = KeyPair.fromSecret(v.hex("init_ephemeral")),
            )
            val responder = NoiseHandshake.create(
                initiator = false,
                staticKey = KeyPair.fromSecret(v.hex("resp_static")),
                prologue = v.hex("resp_prologue"),
                ephemeral = KeyPair.fromSecret(v.hex("resp_ephemeral")),
            )
            val messages = v["messages"]!!.jsonArray.map { it.jsonObject }

            var initSession: NoiseSession? = null
            var respSession: NoiseSession? = null
            messages.forEachIndexed { index, m ->
                val payload = m.hex("payload")
                val expected = m.hex("ciphertext")
                val initiatorSends = index % 2 == 0
                if (index < 3) {
                    val (writer, reader) = if (initiatorSends) initiator to responder else responder to initiator
                    val ct = writer.writeMessage(payload)
                    assertContentEquals(expected, ct, "handshake message $index")
                    assertContentEquals(payload, reader.readMessage(ct))
                    if (index == 2) {
                        val i = initiator.split().also { initSession = it }
                        val r = responder.split().also { respSession = it }
                        assertContentEquals(v.hex("handshake_hash"), i.handshakeHash)
                        assertContentEquals(v.hex("handshake_hash"), r.handshakeHash)
                    }
                } else {
                    val (writer, reader) = if (initiatorSends) initSession!! to respSession!! else respSession!! to initSession!!
                    val ct = writer.sender.encrypt(payload)
                    assertContentEquals(expected, ct, "transport message $index")
                    assertContentEquals(payload, reader.receiver.decrypt(ct))
                }
            }
        }
    }

    @Test
    fun tamperedHandshakeIsRejected() = runTest {
        Sodium.ensureInitialized()
        val prologue = "zoocall/1".encodeToByteArray()
        val a = NoiseHandshake.create(true, KeyPair.generate(), prologue)
        val b = NoiseHandshake.create(false, KeyPair.generate(), prologue)
        b.readMessage(a.writeMessage())
        val m2 = b.writeMessage()
        m2[m2.size - 1] = (m2[m2.size - 1].toInt() xor 1).toByte()
        val failed = runCatching { a.readMessage(m2) }.isFailure
        assertTrue(failed, "tampered message must fail authentication")
    }

    @Test
    fun prologueMismatchIsRejected() = runTest {
        Sodium.ensureInitialized()
        val a = NoiseHandshake.create(true, KeyPair.generate(), "zoocall/1".encodeToByteArray())
        val b = NoiseHandshake.create(false, KeyPair.generate(), "zoocall/2".encodeToByteArray())
        b.readMessage(a.writeMessage())
        assertTrue(runCatching { a.readMessage(b.writeMessage()) }.isFailure)
    }

    @Test
    fun safetyCodesMatchOnBothSides() = runTest {
        Sodium.ensureInitialized()
        val prologue = "zoocall/1".encodeToByteArray()
        val aId = Identity.generate()
        val bId = Identity.generate()
        val a = NoiseHandshake.create(true, aId.keyPair, prologue)
        val b = NoiseHandshake.create(false, bId.keyPair, prologue)
        b.readMessage(a.writeMessage())
        a.readMessage(b.writeMessage())
        b.readMessage(a.writeMessage())
        val sa = a.split()
        val sb = b.split()
        val code = SafetyCodes.sessionCode(sa.handshakeHash)
        assertTrue(code.length == 6 && code.all(Char::isDigit))
        assertTrue(code == SafetyCodes.sessionCode(sb.handshakeHash))
        assertContentEquals(bId.publicKey, sa.remoteStaticKey)
        assertContentEquals(aId.publicKey, sb.remoteStaticKey)
        val n1 = SafetyCodes.safetyNumber(aId.fingerprint, bId.fingerprint)
        assertTrue(n1.length == 60 && n1 == SafetyCodes.safetyNumber(bId.fingerprint, aId.fingerprint))
    }
}
