package app.zoocall.core.call

import app.zoocall.core.crypto.Identity
import app.zoocall.core.crypto.Sodium
import app.zoocall.core.model.CallKind
import app.zoocall.core.model.DeviceClass
import app.zoocall.core.model.PeerAddress
import app.zoocall.core.transport.ConnectionManager
import app.zoocall.core.transport.InMemoryNetwork
import app.zoocall.core.transport.TransportConfig
import app.zoocall.media.AudioTap
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Recording with consent (call.record): the other side always learns a recording started and stopped. */
class RecordingTest {
    private val network = InMemoryNetwork()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transports = mutableListOf<ConnectionManager>()

    private class Peer(val identity: Identity, val transport: ConnectionManager, val calls: CallManager) {
        val fp get() = identity.fingerprint
    }

    private suspend fun peer(ip: String, capabilities: List<String> = app.zoocall.core.protocol.Capabilities.DEFAULT): Peer {
        Sodium.ensureInitialized()
        val identity = Identity.generate()
        val transport = ConnectionManager(
            identity = identity,
            config = TransportConfig(appVersion = "test", deviceClass = DeviceClass.Desktop),
            linkFactory = network.host(ip),
            helloProvider = { Hello(display_name = ip, capabilities = capabilities) },
        )
        transport.start()
        transports += transport
        val calls = CallManager(scope, transport, FakeMediaEngine(), policy = { IncomingDecision.Ring }, endedLingerMs = 60_000)
        calls.start()
        return Peer(identity, transport, calls)
    }

    private suspend fun connectedCall(alice: Peer, bob: Peer) {
        alice.transport.connect(PeerAddress("10.0.0.2", bob.transport.listenPort.value!!)).getOrThrow()
        alice.calls.startCall(bob.fp, CallKind.Audio)
        bob.calls.activeCall.first { it?.state?.phase == CallPhase.IncomingRinging }
        bob.calls.accept(CallKind.Audio)
        alice.calls.activeCall.first { it?.state?.phase == CallPhase.Connected }
        bob.calls.activeCall.first { it?.state?.phase == CallPhase.Connected }
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
    fun theOtherSideIsToldWhenRecordingStartsAndStops() = realTime {
        val alice = peer("10.0.0.1")
        val bob = peer("10.0.0.2")
        connectedCall(alice, bob)

        assertTrue(alice.calls.canRecord())
        assertTrue(alice.calls.setRecording(AudioTap { _, _, _, _ -> }))
        assertTrue(alice.calls.activeCall.value!!.recording)
        bob.calls.activeCall.first { it?.othersRecording == true }

        alice.calls.setRecording(null)
        bob.calls.activeCall.first { it?.othersRecording == false }
    }

    @Test
    fun noRecordingWithSomeoneWhoseAppWouldNotShowIt() = realTime {
        val alice = peer("10.0.0.1")
        val bob = peer("10.0.0.2", capabilities = app.zoocall.core.protocol.Capabilities.DEFAULT - app.zoocall.core.protocol.Capabilities.CALL_RECORD)
        connectedCall(alice, bob)

        assertFalse(alice.calls.canRecord())
        assertFalse(alice.calls.setRecording(AudioTap { _, _, _, _ -> }))
        assertFalse(alice.calls.activeCall.value!!.recording)
    }
}
