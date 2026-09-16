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

/** A call rings the callee's linked devices too (capability device.link); the first answer wins. */
class LinkedDevicesTest {
    private val network = InMemoryNetwork()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transports = mutableListOf<ConnectionManager>()

    private class Peer(val identity: Identity, val transport: ConnectionManager, val calls: CallManager) {
        val fp get() = identity.fingerprint
    }

    private suspend fun peer(ip: String, policy: CallPolicy = CallPolicy { IncomingDecision.Ring }): Peer {
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
        val calls = CallManager(scope, transport, FakeMediaEngine(), policy = policy, endedLingerMs = 60_000)
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
    fun answeringOnTheLinkedDeviceTakesTheCall() = realTime {
        val caller = peer("10.0.0.1")
        val phone = peer("10.0.0.2")
        val laptop = peer("10.0.0.3")
        connect(caller, phone, "10.0.0.2")
        connect(caller, laptop, "10.0.0.3")

        val phoneEnded = async { phone.calls.finishedCalls.first() }
        caller.calls.startCall(phone.fp, CallKind.Audio, alsoRing = listOf(laptop.fp))
        phone.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }
        laptop.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }

        laptop.calls.accept(CallKind.Audio)
        val connected = caller.calls.activeCall.first { it?.state?.phase == CallPhase.Connected }
        assertEquals(laptop.fp, connected!!.state.peer)
        assertEquals(EndReason.AnsweredElsewhere, phoneEnded.await().endReason)
    }

    @Test
    fun decliningOnOneDeviceStopsTheOthers() = realTime {
        val caller = peer("10.0.0.1")
        val phone = peer("10.0.0.2")
        val laptop = peer("10.0.0.3")
        connect(caller, phone, "10.0.0.2")
        connect(caller, laptop, "10.0.0.3")

        val laptopEnded = async { laptop.calls.finishedCalls.first() }
        val callerEnded = async { caller.calls.finishedCalls.first() }
        caller.calls.startCall(phone.fp, CallKind.Audio, alsoRing = listOf(laptop.fp))
        phone.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }
        laptop.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }

        phone.calls.decline()
        assertEquals(EndReason.Declined, callerEnded.await().endReason)
        assertEquals(EndReason.AnsweredElsewhere, laptopEnded.await().endReason)
    }

    @Test
    fun anUnavailableDeviceDropsOutWhileTheOtherRings() = realTime {
        val caller = peer("10.0.0.1")
        val phone = peer("10.0.0.2", policy = { IncomingDecision.NotAllowed })
        val laptop = peer("10.0.0.3")
        connect(caller, phone, "10.0.0.2")
        connect(caller, laptop, "10.0.0.3")

        caller.calls.startCall(phone.fp, CallKind.Audio, alsoRing = listOf(laptop.fp))
        laptop.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }
        // The phone's refusal moves the call over to the laptop instead of ending it.
        caller.calls.activeCall.first { it?.state?.peer == laptop.fp }

        laptop.calls.accept(CallKind.Audio)
        caller.calls.activeCall.first { it?.state?.phase == CallPhase.Connected && it.state.peer == laptop.fp }
    }
}
