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

/** Attended call transfer: whoever answered hands the caller over to a third person, then leaves. */
class TransferTest {
    private val network = InMemoryNetwork()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transports = mutableListOf<ConnectionManager>()

    private class Peer(val identity: Identity, val transport: ConnectionManager, val calls: CallManager) {
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
        val calls = CallManager(scope, transport, FakeMediaEngine(), policy = { IncomingDecision.Ring }, endedLingerMs = 60_000)
        calls.start()
        return Peer(identity, transport, calls)
    }

    private suspend fun connect(a: Peer, b: Peer, ip: String) {
        a.transport.connect(PeerAddress(ip, b.transport.listenPort.value!!)).getOrThrow()
        b.transport.peers.first { it.containsKey(a.fp) }
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
    fun frontDeskHandsTheCallerOverAndLeaves() = realTime {
        val caller = peer("10.0.0.1")
        val desk = peer("10.0.0.2")
        val colleague = peer("10.0.0.3")
        connect(caller, desk, "10.0.0.2")
        connect(desk, colleague, "10.0.0.3")

        caller.calls.startCall(desk.fp, CallKind.Audio)
        desk.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }
        desk.calls.accept(CallKind.Audio)
        desk.calls.activeCall.first { it?.state?.phase == CallPhase.Connected }

        // The desk answered, so it isn't the host yet, but it can still transfer.
        assertTrue(desk.calls.canTransfer())
        assertEquals(AddParticipantResult.Invited, desk.calls.transferTo(colleague.fp))
        assertEquals(colleague.fp, desk.calls.transferTarget.value)
        colleague.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }
        colleague.calls.accept(CallKind.Audio)

        desk.calls.activeCall.first { it?.state?.phase == CallPhase.Ended }
        val carriedOn = caller.calls.activeCall.first { call ->
            call?.state?.phase == CallPhase.Connected && call.state.peer == colleague.fp && call.members.isEmpty()
        }!!
        assertEquals(colleague.fp, carriedOn.state.peer)
        colleague.calls.activeCall.first { call -> call?.state?.phase == CallPhase.Connected && call.state.peer == caller.fp && call.members.isEmpty() }
    }

    @Test
    fun declinedTransferKeepsTheCall() = realTime {
        val caller = peer("10.0.0.1")
        val desk = peer("10.0.0.2")
        val colleague = peer("10.0.0.3")
        connect(caller, desk, "10.0.0.2")
        connect(desk, colleague, "10.0.0.3")

        caller.calls.startCall(desk.fp, CallKind.Audio)
        desk.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }
        desk.calls.accept(CallKind.Audio)
        desk.calls.activeCall.first { it?.state?.phase == CallPhase.Connected }

        assertEquals(AddParticipantResult.Invited, desk.calls.transferTo(colleague.fp))
        colleague.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }
        colleague.calls.decline()

        desk.calls.transferTarget.first { it == null }
        assertEquals(CallPhase.Connected, desk.calls.activeCall.value?.state?.phase)
        assertEquals(caller.fp, desk.calls.activeCall.value?.state?.peer)
    }
}
