package app.zoocall.core.chat

import app.zoocall.core.crypto.Identity
import app.zoocall.core.crypto.Sodium
import app.zoocall.core.model.DeviceClass
import app.zoocall.core.model.MessageId
import app.zoocall.core.model.PeerAddress
import app.zoocall.core.transport.ConnectionManager
import app.zoocall.core.transport.InMemoryNetwork
import app.zoocall.core.transport.TransportConfig
import app.zoocall.protocol.v1.Hello
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnockManagerTest {
    private val network = InMemoryNetwork()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transports = mutableListOf<ConnectionManager>()

    private class Peer(val identity: Identity, val transport: ConnectionManager, val knocks: KnockManager)

    private suspend fun peer(ip: String, contact: Boolean = true, quiet: Boolean = false): Peer {
        Sodium.ensureInitialized()
        val identity = Identity.generate()
        val transport = ConnectionManager(
            identity = identity,
            config = TransportConfig(appVersion = "test", deviceClass = DeviceClass.Phone),
            linkFactory = network.host(ip),
            helloProvider = { Hello(display_name = ip, capabilities = app.zoocall.core.protocol.Capabilities.DEFAULT) },
        )
        transport.start()
        transports += transport
        val knocks = KnockManager(scope, transport, isContact = { contact }, isQuiet = { quiet })
        knocks.start()
        return Peer(identity, transport, knocks)
    }

    private suspend fun connected(bobIsContact: Boolean = true, bobQuiet: Boolean = false): Pair<Peer, Peer> {
        val alice = peer("10.0.0.1")
        val bob = peer("10.0.0.2", contact = bobIsContact, quiet = bobQuiet)
        alice.transport.connect(PeerAddress("10.0.0.2", bob.transport.listenPort.value!!)).getOrThrow()
        bob.transport.peers.first { it.containsKey(alice.identity.fingerprint) }
        return alice to bob
    }

    @AfterTest
    fun tearDown() {
        transports.forEach { it.stop() }
        scope.cancel()
    }

    private fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(10_000) { block() } }
    }

    @Test
    fun knockAndReply() = realTime {
        val (alice, bob) = connected(bobQuiet = true)
        val received = async(start = CoroutineStart.UNDISPATCHED) { bob.knocks.events.first() }
        val id = assertNotNull(alice.knocks.knock(bob.identity.fingerprint, "  Lunch? "))

        val knock = assertIs<KnockEvent.Received>(received.await())
        assertEquals(id, knock.id)
        assertEquals("Lunch?", knock.text)
        assertEquals(alice.identity.fingerprint, knock.from)
        assertTrue(knock.quiet)

        val replied = async(start = CoroutineStart.UNDISPATCHED) { alice.knocks.events.first() }
        assertTrue(bob.knocks.reply(alice.identity.fingerprint, id, KnockReply.TwoMinutes))
        val reply = assertIs<KnockEvent.Replied>(replied.await())
        assertEquals(KnockReply.TwoMinutes, reply.reply)
        assertEquals(id, reply.knockId)
    }

    @Test
    fun repliesToKnocksWeDidNotSendAreIgnored() = realTime {
        val (alice, bob) = connected()
        val events = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(700) { alice.knocks.events.first() } }
        bob.knocks.reply(alice.identity.fingerprint, MessageId.generate(1).value, KnockReply.CallMe)
        assertNull(events.await())
    }

    @Test
    fun strangersGetOneKnockAMinute() = realTime {
        val (alice, bob) = connected(bobIsContact = false)
        val events = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(1_000) { bob.knocks.events.take(2).toList() } }
        alice.knocks.knock(bob.identity.fingerprint)
        alice.knocks.knock(bob.identity.fingerprint)
        // take(2) never completes: the second knock is dropped.
        assertNull(events.await())
    }

    @Test
    fun knockNeedsAConnection() = realTime {
        val alice = peer("10.0.0.1")
        val bob = peer("10.0.0.2")
        assertNull(alice.knocks.knock(bob.identity.fingerprint))
    }
}
