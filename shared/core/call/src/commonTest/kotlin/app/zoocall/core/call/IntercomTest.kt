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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Desk intercom (capability call.intercom): audio-only lines that connect without ringing only when allowed. */
class IntercomTest {
    private val network = InMemoryNetwork()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transports = mutableListOf<ConnectionManager>()

    private class Peer(val identity: Identity, val transport: ConnectionManager, val calls: CallManager) {
        val fp get() = identity.fingerprint
    }

    private suspend fun peer(ip: String, intercom: IncomingDecision = IncomingDecision.NotAllowed): Peer {
        Sodium.ensureInitialized()
        val identity = Identity.generate()
        val transport = ConnectionManager(
            identity = identity,
            config = TransportConfig(appVersion = "test", deviceClass = DeviceClass.Desktop),
            linkFactory = network.host(ip),
            helloProvider = { Hello(display_name = ip, capabilities = app.zoocall.core.protocol.Capabilities.DEFAULT) },
        )
        transport.start()
        transports += transport
        val calls = CallManager(
            scope,
            transport,
            FakeMediaEngine(),
            policy = { IncomingDecision.Ring },
            intercomPolicy = { intercom },
            endedLingerMs = 60_000,
        )
        calls.start()
        return Peer(identity, transport, calls)
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
    fun allowedIntercomConnectsWithoutRingingAndTheMicIsLive() = realTime {
        val alice = peer("10.0.0.1")
        val desk = peer("10.0.0.2", intercom = IncomingDecision.Ring)
        alice.transport.connect(PeerAddress("10.0.0.2", desk.transport.listenPort.value!!)).getOrThrow()

        alice.calls.startCall(desk.fp, CallKind.Video, intercom = true)
        val deskCall = desk.calls.activeCall.first { it?.state?.phase == CallPhase.Connected }!!
        val aliceCall = alice.calls.activeCall.first { it?.state?.phase == CallPhase.Connected }!!

        assertTrue(deskCall.state.intercom)
        assertTrue(aliceCall.state.intercom)
        assertEquals(CallKind.Audio, deskCall.state.kind)
        assertFalse(deskCall.micMuted)
        assertFalse(alice.calls.canAddParticipants())
    }

    @Test
    fun intercomIsRefusedUnlessAllowed() = realTime {
        val alice = peer("10.0.0.1")
        val desk = peer("10.0.0.2")
        alice.transport.connect(PeerAddress("10.0.0.2", desk.transport.listenPort.value!!)).getOrThrow()

        alice.calls.startCall(desk.fp, CallKind.Audio, intercom = true)
        // The ended call lingers (endedLingerMs), so this can't miss it the way a finishedCalls subscriber could.
        val ended = alice.calls.activeCall.first { it?.state?.phase == CallPhase.Ended }!!
        assertEquals(EndReason.NotAllowed, ended.state.endReason)
        assertEquals(null, desk.calls.activeCall.value)
    }
}
