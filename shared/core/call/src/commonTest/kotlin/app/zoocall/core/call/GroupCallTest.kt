package app.zoocall.core.call

import app.zoocall.core.crypto.Identity
import app.zoocall.core.crypto.Sodium
import app.zoocall.core.model.CallKind
import app.zoocall.core.model.DeviceClass
import app.zoocall.core.model.PeerAddress
import app.zoocall.core.transport.ConnectionManager
import app.zoocall.core.transport.InMemoryNetwork
import app.zoocall.core.transport.TransportConfig
import app.zoocall.protocol.v1.Hello
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mesh group calls (capability call.group) between three in-process peers. */
class GroupCallTest {
    private val network = InMemoryNetwork()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transports = mutableListOf<ConnectionManager>()

    private class Peer(val identity: Identity, val transport: ConnectionManager, val calls: CallManager, val media: FakeMediaEngine) {
        val fp get() = identity.fingerprint
    }

    private suspend fun peer(ip: String): Peer {
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
        val media = FakeMediaEngine()
        val calls = CallManager(scope, transport, media, policy = { IncomingDecision.Ring }, endedLingerMs = 60_000)
        calls.start()
        return Peer(identity, transport, calls, media)
    }

    private suspend fun connect(a: Peer, b: Peer, ip: String) {
        a.transport.connect(PeerAddress(ip, b.transport.listenPort.value!!)).getOrThrow()
        b.transport.peers.first { it.containsKey(a.fp) }
    }

    /** Alice calls Bob, then adds Carol. Bob and Carol aren't connected to each other beforehand. */
    private suspend fun group(): Triple<Peer, Peer, Peer> {
        val alice = peer("10.0.0.1")
        val bob = peer("10.0.0.2")
        val carol = peer("10.0.0.3")
        connect(alice, bob, "10.0.0.2")
        connect(alice, carol, "10.0.0.3")

        alice.calls.startCall(bob.fp, CallKind.Audio)
        bob.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }
        bob.calls.accept(CallKind.Audio)
        alice.calls.activeCall.first { it?.state?.phase == CallPhase.Connected }

        assertTrue(alice.calls.canAddParticipants())
        assertEquals(AddParticipantResult.Invited, alice.calls.addParticipant(carol.fp))
        carol.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }
        carol.calls.accept(CallKind.Audio)

        alice.calls.activeCall.first { call -> call?.members?.singleOrNull()?.let { it.fingerprint == carol.fp && it.phase == MemberPhase.Connected } == true }
        bob.calls.activeCall.first { call -> call?.members?.singleOrNull()?.let { it.fingerprint == carol.fp && it.phase == MemberPhase.Connected } == true }
        carol.calls.activeCall.first { call ->
            call?.state?.phase == CallPhase.Connected && call.members.singleOrNull()?.let { it.fingerprint == bob.fp && it.phase == MemberPhase.Connected } == true
        }
        return Triple(alice, bob, carol)
    }

    @AfterTest
    fun tearDown() {
        transports.forEach { it.stop() }
        scope.cancel()
    }

    private fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(15_000) { block() } }
    }

    @Test
    fun hostAddsSomeoneAndEveryPairConnects() = realTime {
        val (alice, bob, carol) = group()
        assertEquals(alice.fp, bob.calls.activeCall.value!!.host)
        assertEquals(alice.fp, carol.calls.activeCall.value!!.host)
        // Full mesh: two media legs each.
        assertEquals(2, alice.media.sessions.size)
        assertEquals(2, bob.media.sessions.size)
        assertEquals(2, carol.media.sessions.size)
    }

    @Test
    fun onlyTheHostCanAddPeople() = realTime {
        val (_, bob, carol) = group()
        assertEquals(AddParticipantResult.NotHost, bob.calls.addParticipant(carol.fp))
    }

    @Test
    fun hostLeavingKeepsTheCallGoing() = realTime {
        val (alice, bob, carol) = group()
        alice.calls.hangUp()

        val bobCall = bob.calls.activeCall.first { it?.state?.peer == carol.fp && it.members.isEmpty() }!!
        val carolCall = carol.calls.activeCall.first { it?.state?.peer == bob.fp && it.members.isEmpty() }!!
        assertEquals(CallPhase.Connected, bobCall.state.phase)
        assertEquals(CallPhase.Connected, carolCall.state.phase)
        // The earliest joiner after the host takes over (edge case C22).
        assertEquals(bob.fp, bobCall.host)
        assertEquals(bob.fp, carolCall.host)
        assertTrue(bob.calls.canAddParticipants())
    }

    @Test
    fun memberLeavingOnlyRemovesThem() = realTime {
        val (alice, bob, carol) = group()
        carol.calls.hangUp()
        alice.calls.activeCall.first { it?.members?.isEmpty() == true }
        bob.calls.activeCall.first { it?.members?.isEmpty() == true }
        assertEquals(CallPhase.Connected, alice.calls.activeCall.value!!.state.phase)
        assertEquals(bob.fp, alice.calls.activeCall.value!!.state.peer)
    }

    @Test
    fun declinedInvitationShowsANotice() = realTime {
        val alice = peer("10.0.0.1")
        val bob = peer("10.0.0.2")
        val carol = peer("10.0.0.3")
        connect(alice, bob, "10.0.0.2")
        connect(alice, carol, "10.0.0.3")
        alice.calls.startCall(bob.fp, CallKind.Audio)
        bob.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }
        bob.calls.accept(CallKind.Audio)
        alice.calls.activeCall.first { it?.state?.phase == CallPhase.Connected }

        alice.calls.addParticipant(carol.fp)
        carol.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }
        carol.calls.decline()
        val call = alice.calls.activeCall.first { it?.notice == CallNotice.AddDeclined }!!
        assertEquals(carol.fp, call.noticePeer)
        assertTrue(call.members.isEmpty())
        assertEquals(CallPhase.Connected, call.state.phase)
    }
}
