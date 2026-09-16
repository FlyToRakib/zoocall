package app.zoocall.core.transport

import app.zoocall.core.crypto.Identity
import app.zoocall.core.crypto.Sodium
import app.zoocall.core.model.DeviceClass
import app.zoocall.core.model.PeerAddress
import app.zoocall.protocol.v1.ChatMessage
import app.zoocall.protocol.v1.Envelope
import app.zoocall.protocol.v1.Hello
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionManagerTest {
    private val network = InMemoryNetwork()
    private val managers = mutableListOf<ConnectionManager>()

    private suspend fun peer(ip: String, name: String): Pair<ConnectionManager, Identity> {
        Sodium.ensureInitialized()
        val identity = Identity.generate()
        val manager = ConnectionManager(
            identity = identity,
            config = TransportConfig(appVersion = "test", deviceClass = DeviceClass.Phone),
            linkFactory = network.host(ip),
            helloProvider = { Hello(display_name = name, app_version = "test") },
        )
        manager.start()
        managers += manager
        return manager to identity
    }

    @AfterTest
    fun tearDown() = managers.forEach { it.stop() }

    /** Real dispatchers: the transport runs its own coroutines outside the test scheduler. */
    private fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(10_000) { block() } }
    }

    @Test
    fun peersConnectExchangeHelloAndMessages() = realTime {
        val (alice, aliceId) = peer("10.0.0.1", "Alice")
        val (bob, bobId) = peer("10.0.0.2", "Bob")

        val bobSawAlice = async(start = CoroutineStart.UNDISPATCHED) { bob.events.filterIsInstance<TransportEvent.Connected>().first() }
        val peerBob = alice.connect(PeerAddress("10.0.0.2", bob.listenPort.value!!)).getOrThrow()

        assertEquals(bobId.fingerprint, peerBob.fingerprint)
        assertEquals("Bob", peerBob.hello.display_name)
        val peerAlice = bobSawAlice.await().peer
        assertEquals(aliceId.fingerprint, peerAlice.fingerprint)
        assertEquals("Alice", peerAlice.hello.display_name)
        assertEquals(PeerAddress("10.0.0.1", alice.listenPort.value!!), peerAlice.address)
        assertEquals(peerBob.safetyCode, peerAlice.safetyCode, "both sides must show the same safety code")

        val received = async(start = CoroutineStart.UNDISPATCHED) { bob.events.filterIsInstance<TransportEvent.Received>().first() }
        assertTrue(alice.send(bobId.fingerprint, Envelope(chat = ChatMessage(id = "1", text = "hi"))))
        val message = received.await()
        assertEquals(aliceId.fingerprint, message.from)
        assertEquals("hi", message.envelope.chat?.text)
    }

    @Test
    fun disconnectIsReportedToTheOtherSide() = realTime {
        val (alice, aliceId) = peer("10.0.0.1", "Alice")
        val (bob, bobId) = peer("10.0.0.2", "Bob")
        alice.connect(PeerAddress("10.0.0.2", bob.listenPort.value!!)).getOrThrow()
        bob.peers.first { it.containsKey(aliceId.fingerprint) }
        val gone = async(start = CoroutineStart.UNDISPATCHED) { bob.events.filterIsInstance<TransportEvent.Disconnected>().first() }
        alice.disconnect(bobId.fingerprint)
        assertEquals(aliceId.fingerprint, gone.await().fingerprint)
        assertFalse(alice.isConnected(bobId.fingerprint))
    }

    @Test
    fun simultaneousDialsConvergeOnOneConnection() = realTime {
        val (alice, aliceId) = peer("10.0.0.1", "Alice")
        val (bob, bobId) = peer("10.0.0.2", "Bob")
        val a = async { alice.connect(PeerAddress("10.0.0.2", bob.listenPort.value!!)) }
        val b = async { bob.connect(PeerAddress("10.0.0.1", alice.listenPort.value!!)) }
        a.await()
        b.await()
        alice.peers.first { it.containsKey(bobId.fingerprint) }
        bob.peers.first { it.containsKey(aliceId.fingerprint) }

        val received = async(start = CoroutineStart.UNDISPATCHED) { bob.events.filterIsInstance<TransportEvent.Received>().first() }
        assertTrue(alice.send(bobId.fingerprint, Envelope(chat = ChatMessage(id = "2", text = "still works"))))
        assertEquals("still works", received.await().envelope.chat?.text)
    }

    @Test
    fun refusedPeerIsNotRegistered() = realTime {
        Sodium.ensureInitialized()
        val (bob, _) = peer("10.0.0.2", "Bob")
        val stranger = ConnectionManager(
            identity = Identity.generate(),
            config = TransportConfig(appVersion = "test", deviceClass = DeviceClass.Desktop),
            linkFactory = network.host("10.0.0.3"),
            helloProvider = { Hello(display_name = "Stranger") },
            admit = { false },
        )
        stranger.start()
        managers += stranger
        assertTrue(stranger.connect(PeerAddress("10.0.0.2", bob.listenPort.value!!)).isFailure)
    }
}
