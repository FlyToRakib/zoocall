package app.zoocall.core.call

import app.zoocall.core.crypto.Identity
import app.zoocall.core.crypto.Sodium
import app.zoocall.core.model.CallKind
import app.zoocall.core.model.DeviceClass
import app.zoocall.core.model.EndReason
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** End-to-end call signaling between two in-process peers with a fake media engine. */
class CallManagerTest {
    private val network = InMemoryNetwork()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transports = mutableListOf<ConnectionManager>()

    private class Peer(val identity: Identity, val transport: ConnectionManager, val calls: CallManager, val media: FakeMediaEngine)

    private suspend fun peer(ip: String, decision: IncomingDecision = IncomingDecision.Ring): Peer {
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
        val calls = CallManager(scope, transport, media, policy = { decision }, endedLingerMs = 60_000)
        calls.start()
        return Peer(identity, transport, calls, media)
    }

    private suspend fun connected(): Pair<Peer, Peer> {
        val alice = peer("10.0.0.1")
        val bob = peer("10.0.0.2")
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
    fun callRingsIsAcceptedAndConnects() = realTime {
        val (alice, bob) = connected()
        assertNotNull(alice.calls.startCall(bob.identity.fingerprint, CallKind.Video))

        val incoming = bob.calls.activeCall.filterNotNull().first { it.state.phase == CallPhase.IncomingRinging }
        assertEquals(alice.identity.fingerprint, incoming.state.peer)
        alice.calls.activeCall.first { it?.state?.phase == CallPhase.OutgoingRinging }

        bob.calls.accept(CallKind.Video)
        alice.calls.activeCall.first { it?.state?.phase == CallPhase.Connected }
        bob.calls.activeCall.first { it?.state?.phase == CallPhase.Connected }

        alice.calls.hangUp()
        val bobEnded = bob.calls.activeCall.first { it?.state?.phase == CallPhase.Ended }
        assertEquals(EndReason.Completed, bobEnded!!.state.endReason)
        assertTrue(alice.media.sessions.single().closed)
        bob.calls.activeCall.first { it?.media == null }
        assertTrue(bob.media.sessions.single().closed)
    }

    @Test
    fun declineEndsCallerWithDeclined() = realTime {
        val (alice, bob) = connected()
        alice.calls.startCall(bob.identity.fingerprint, CallKind.Audio)
        bob.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }
        bob.calls.decline()
        val ended = alice.calls.activeCall.first { it?.state?.phase == CallPhase.Ended }
        assertEquals(EndReason.Declined, ended!!.state.endReason)
    }

    @Test
    fun callerCancelBecomesMissedCall() = realTime {
        val (alice, bob) = connected()
        val missed = async(start = CoroutineStart.UNDISPATCHED) { bob.calls.finishedCalls.first() }
        alice.calls.startCall(bob.identity.fingerprint, CallKind.Audio)
        bob.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }
        alice.calls.hangUp()
        assertEquals(EndReason.Missed, missed.await().endReason)
    }

    @Test
    fun secondCallerGetsBusy() = realTime {
        val (alice, bob) = connected()
        val carol = peer("10.0.0.3")
        carol.transport.connect(PeerAddress("10.0.0.2", bob.transport.listenPort.value!!)).getOrThrow()
        bob.transport.peers.first { it.containsKey(carol.identity.fingerprint) }

        alice.calls.startCall(bob.identity.fingerprint, CallKind.Audio)
        bob.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }

        carol.calls.startCall(bob.identity.fingerprint, CallKind.Audio)
        val ended = carol.calls.activeCall.first { it?.state?.phase == CallPhase.Ended }
        assertEquals(EndReason.Busy, ended!!.state.endReason)
    }

    @Test
    fun notAllowedIsReportedAsSuch() = realTime {
        val alice = peer("10.0.0.1")
        val bob = peer("10.0.0.2", decision = IncomingDecision.NotAllowed)
        alice.transport.connect(PeerAddress("10.0.0.2", bob.transport.listenPort.value!!)).getOrThrow()
        bob.transport.peers.first { it.containsKey(alice.identity.fingerprint) }
        alice.calls.startCall(bob.identity.fingerprint, CallKind.Audio)
        val ended = alice.calls.activeCall.first { it?.state?.phase == CallPhase.Ended }
        assertEquals(EndReason.NotAllowed, ended!!.state.endReason)
    }

    @Test
    fun simultaneousCallsConnectInsteadOfBusy() = realTime {
        val (alice, bob) = connected()
        val a = async { alice.calls.startCall(bob.identity.fingerprint, CallKind.Audio) }
        val b = async { bob.calls.startCall(alice.identity.fingerprint, CallKind.Audio) }
        a.await()
        b.await()
        alice.calls.activeCall.first { it?.state?.phase == CallPhase.Connected }
        bob.calls.activeCall.first { it?.state?.phase == CallPhase.Connected }
        assertEquals(alice.calls.activeCall.value!!.state.callId, bob.calls.activeCall.value!!.state.callId)
    }

    private suspend fun inCall(alice: Peer, bob: Peer, kind: CallKind = CallKind.Audio) {
        alice.calls.startCall(bob.identity.fingerprint, kind)
        bob.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }
        bob.calls.accept(kind)
        alice.calls.activeCall.first { it?.state?.phase == CallPhase.Connected }
        bob.calls.activeCall.first { it?.state?.phase == CallPhase.Connected }
    }

    @Test
    fun videoUpgradeNeedsConsentThenRenegotiates() = realTime {
        val (alice, bob) = connected()
        inCall(alice, bob)
        assertTrue(bob.calls.requestVideoUpgrade())
        alice.calls.activeCall.first { it?.upgrade == UpgradeRequest.Incoming }
        // Nothing changes until Alice agrees (edge case C11).
        assertEquals(CallKind.Audio, bob.calls.activeCall.value!!.state.kind)

        alice.calls.respondToVideoUpgrade(accept = true)
        alice.calls.activeCall.first { it?.state?.kind == CallKind.Video }
        bob.calls.activeCall.first { it?.state?.kind == CallKind.Video && it.upgrade == null }
        // Alice placed the call, so she is the offerer and renegotiates: a second offer, a second answer.
        while (bob.media.sessions.single().answers < 2) kotlinx.coroutines.delay(10)
        assertTrue(alice.media.sessions.single().upgraded && bob.media.sessions.single().upgraded)
        assertEquals(2, alice.media.sessions.single().offers)
    }

    @Test
    fun screenShareInAnAudioCallRenegotiatesFromTheSharer() = realTime {
        val (alice, bob) = connected()
        inCall(alice, bob)
        assertTrue(bob.calls.canShareScreen())
        assertTrue(bob.calls.startScreenShare(app.zoocall.media.ScreenSource(1, "Screen 1", isWindow = false)))
        alice.calls.activeCall.first { it?.remoteScreenSharing == true }
        // Bob added the video section, so Bob offers although Alice placed the call.
        while (alice.media.sessions.single().answers < 1) kotlinx.coroutines.delay(10)
        assertTrue(bob.media.sessions.single().sharing)
        assertEquals(CallPhase.Connected, alice.calls.activeCall.value!!.state.phase)

        bob.calls.stopScreenShare()
        alice.calls.activeCall.first { it?.remoteScreenSharing == false }
        assertTrue(!bob.media.sessions.single().sharing)
    }

    @Test
    fun declinedUpgradeStaysAudio() = realTime {
        val (alice, bob) = connected()
        inCall(alice, bob)
        alice.calls.requestVideoUpgrade()
        bob.calls.activeCall.first { it?.upgrade == UpgradeRequest.Incoming }
        bob.calls.respondToVideoUpgrade(accept = false)
        val notice = alice.calls.activeCall.first { it?.notice == CallNotice.UpgradeDeclined }
        assertEquals(CallKind.Audio, notice!!.state.kind)
        assertTrue(!alice.media.sessions.single().upgraded)
    }

    @Test
    fun holdPausesMediaAndIsSignaled() = realTime {
        val (alice, bob) = connected()
        inCall(alice, bob)
        alice.calls.setOnHold(true)
        bob.calls.activeCall.first { it?.remoteOnHold == true }
        assertTrue(!alice.media.sessions.single().micOn)
        alice.calls.setOnHold(false)
        bob.calls.activeCall.first { it?.remoteOnHold == false }
        assertTrue(alice.media.sessions.single().micOn)
    }

    @Test
    fun declineWithQuickReplyReachesCaller() = realTime {
        val (alice, bob) = connected()
        alice.calls.startCall(bob.identity.fingerprint, CallKind.Audio)
        bob.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }
        bob.calls.decline("Call you back in 5")
        val ended = alice.calls.activeCall.first { it?.state?.phase == CallPhase.Ended }!!
        assertEquals(EndReason.Declined, ended.state.endReason)
        assertEquals("Call you back in 5", ended.remoteQuickReply)
    }

    @Test
    fun waitingCallCanReplaceCurrentCall() = realTime {
        val (alice, bob) = connected()
        val carol = peer("10.0.0.3")
        carol.transport.connect(PeerAddress("10.0.0.2", bob.transport.listenPort.value!!)).getOrThrow()
        bob.transport.peers.first { it.containsKey(carol.identity.fingerprint) }
        inCall(alice, bob)

        carol.calls.startCall(bob.identity.fingerprint, CallKind.Audio)
        bob.calls.activeCall.first { it?.waiting?.peer == carol.identity.fingerprint }
        carol.calls.activeCall.first { it?.state?.phase == CallPhase.OutgoingRinging }

        bob.calls.acceptWaiting(CallKind.Audio)
        assertEquals(EndReason.Completed, alice.calls.activeCall.first { it?.state?.phase == CallPhase.Ended }!!.state.endReason)
        carol.calls.activeCall.first { it?.state?.phase == CallPhase.Connected }
        val bobCall = bob.calls.activeCall.first { it?.state?.phase == CallPhase.Connected }!!
        assertEquals(carol.identity.fingerprint, bobCall.state.peer)
    }

    @Test
    fun waitingCallRingsWhenCurrentCallEnds() = realTime {
        val (alice, bob) = connected()
        val carol = peer("10.0.0.3")
        carol.transport.connect(PeerAddress("10.0.0.2", bob.transport.listenPort.value!!)).getOrThrow()
        bob.transport.peers.first { it.containsKey(carol.identity.fingerprint) }
        inCall(alice, bob)

        carol.calls.startCall(bob.identity.fingerprint, CallKind.Video)
        bob.calls.activeCall.first { it?.waiting != null }
        alice.calls.hangUp()
        val ringing = bob.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }!!
        assertEquals(carol.identity.fingerprint, ringing.state.peer)
        assertEquals(CallKind.Video, ringing.state.kind)
    }

    @Test
    fun muteIsSignaledToPeer() = realTime {
        val (alice, bob) = connected()
        alice.calls.startCall(bob.identity.fingerprint, CallKind.Audio)
        bob.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }
        bob.calls.accept(CallKind.Audio)
        alice.calls.activeCall.first { it?.state?.phase == CallPhase.Connected }
        alice.calls.setMicMuted(true)
        bob.calls.activeCall.first { it?.remoteMicMuted == true }
        assertTrue(!alice.media.sessions.single().micOn)
    }
}
