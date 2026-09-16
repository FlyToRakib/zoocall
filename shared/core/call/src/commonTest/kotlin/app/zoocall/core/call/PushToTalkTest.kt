package app.zoocall.core.call

import app.zoocall.core.crypto.Identity
import app.zoocall.core.crypto.Sodium
import app.zoocall.core.model.CallDirection
import app.zoocall.core.model.CallKind
import app.zoocall.core.model.DeviceClass
import app.zoocall.core.model.EndReason
import app.zoocall.core.model.PeerAddress
import app.zoocall.core.transport.ConnectionManager
import app.zoocall.core.transport.InMemoryNetwork
import app.zoocall.core.transport.TransportConfig
import app.zoocall.protocol.v1.Hello
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Push-to-talk sessions (capability ptt.v1) between two in-process peers. */
class PushToTalkTest {
    private val network = InMemoryNetwork()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transports = mutableListOf<ConnectionManager>()

    private class Peer(val identity: Identity, val transport: ConnectionManager, val calls: CallManager, val media: FakeMediaEngine)

    private suspend fun peer(ip: String, allowPtt: Boolean = true): Peer {
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
        val calls = CallManager(
            scope, transport, media,
            policy = { IncomingDecision.Ring },
            pttPolicy = { if (allowPtt) IncomingDecision.Ring else IncomingDecision.NotAllowed },
            endedLingerMs = 60_000,
        )
        calls.start()
        return Peer(identity, transport, calls, media)
    }

    private suspend fun connected(bobAllows: Boolean = true): Pair<Peer, Peer> {
        val alice = peer("10.0.0.1")
        val bob = peer("10.0.0.2", allowPtt = bobAllows)
        alice.transport.connect(PeerAddress("10.0.0.2", bob.transport.listenPort.value!!)).getOrThrow()
        bob.transport.peers.first { it.containsKey(alice.identity.fingerprint) }
        return alice to bob
    }

    private suspend fun live(alice: Peer, bob: Peer) {
        assertNotNull(alice.calls.startCall(bob.identity.fingerprint, CallKind.Video, pushToTalk = true))
        alice.calls.activeCall.first { it?.state?.phase == CallPhase.Connected }
        bob.calls.activeCall.first { it?.state?.phase == CallPhase.Connected }
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
    fun connectsWithoutRingingAndMicFollowsTheFloor() = realTime {
        val (alice, bob) = connected()
        // The callee must never see a ringing phase.
        val sawRinging = async { bob.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging } }
        live(alice, bob)
        sawRinging.cancel()

        val bobCall = bob.calls.activeCall.value!!
        assertTrue(bobCall.state.pushToTalk)
        assertEquals(CallDirection.Incoming, bobCall.state.direction)
        assertEquals(CallKind.Audio, bobCall.state.kind, "push-to-talk is audio only")
        assertTrue(bobCall.micMuted)
        assertFalse(alice.media.sessions.single().micOn)

        assertTrue(alice.calls.setTalking(true))
        assertTrue(alice.media.sessions.single().micOn)
        bob.calls.activeCall.first { it?.remoteTalking == true }

        // Busy channel while Alice holds the floor.
        assertFalse(bob.calls.setTalking(true))
        assertFalse(bob.media.sessions.single().micOn)

        assertTrue(alice.calls.setTalking(false))
        assertFalse(alice.media.sessions.single().micOn)
        bob.calls.activeCall.first { it?.remoteTalking == false }
        assertTrue(bob.calls.setTalking(true))
        alice.calls.activeCall.first { it?.remoteTalking == true }
    }

    /**
     * First come wins; if both floors cross on the wire the caller keeps it. Either way both
     * sides must settle on exactly one talker, with the mics matching.
     */
    @Test
    fun simultaneousTalkSettlesOnOneTalker() = realTime {
        val (alice, bob) = connected()
        live(alice, bob)
        listOf(async { alice.calls.setTalking(true) }, async { bob.calls.setTalking(true) }).awaitAll()

        while (true) {
            val a = alice.calls.activeCall.value!!
            val b = bob.calls.activeCall.value!!
            if (a.talking != b.talking && a.remoteTalking == b.talking && b.remoteTalking == a.talking) break
            delay(20)
        }
        delay(200)
        val a = alice.calls.activeCall.value!!
        val b = bob.calls.activeCall.value!!
        assertTrue(a.talking != b.talking, "exactly one talker")
        assertEquals(a.talking, alice.media.sessions.single().micOn)
        assertEquals(b.talking, bob.media.sessions.single().micOn)
    }

    @Test
    fun notAllowedIsDeclinedWithoutAMissedCall() = realTime {
        val (alice, bob) = connected(bobAllows = false)
        alice.calls.startCall(bob.identity.fingerprint, CallKind.Audio, pushToTalk = true)
        val ended = alice.calls.activeCall.first { it?.state?.phase == CallPhase.Ended }
        assertEquals(EndReason.NotAllowed, ended!!.state.endReason)
        assertNull(bob.calls.activeCall.value)
    }

    @Test
    fun groupChannelSharesOneFloor() = realTime {
        val (alice, bob) = connected()
        val carol = peer("10.0.0.3")
        alice.transport.connect(PeerAddress("10.0.0.3", carol.transport.listenPort.value!!)).getOrThrow()
        carol.transport.peers.first { it.containsKey(alice.identity.fingerprint) }
        live(alice, bob)

        assertTrue(alice.calls.canAddParticipants())
        assertEquals(AddParticipantResult.Invited, alice.calls.addParticipant(carol.identity.fingerprint))
        // Carol allows push-to-talk from Alice, so she joins without ringing and connects to Bob too.
        carol.calls.activeCall.first { call ->
            call?.state?.pushToTalk == true && call.members.singleOrNull()?.phase == MemberPhase.Connected
        }
        bob.calls.activeCall.first { call -> call?.members?.singleOrNull()?.phase == MemberPhase.Connected }

        assertTrue(carol.calls.setTalking(true))
        alice.calls.activeCall.first { it?.talker == carol.identity.fingerprint }
        bob.calls.activeCall.first { it?.talker == carol.identity.fingerprint }
        assertFalse(bob.calls.setTalking(true))

        carol.calls.setTalking(false)
        bob.calls.activeCall.first { it?.remoteTalking == false && it.talker == null }
        assertTrue(bob.calls.setTalking(true))
    }

    @Test
    fun muteDoesNotOpenThePushToTalkMic() = realTime {
        val (alice, bob) = connected()
        live(alice, bob)
        alice.calls.setMicMuted(false)
        assertFalse(alice.media.sessions.single().micOn)
        assertTrue(alice.calls.activeCall.value!!.micMuted)
    }
}
