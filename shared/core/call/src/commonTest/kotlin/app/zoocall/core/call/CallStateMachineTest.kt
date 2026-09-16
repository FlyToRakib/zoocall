package app.zoocall.core.call

import app.zoocall.core.model.CallId
import app.zoocall.core.model.CallKind
import app.zoocall.core.model.EndReason
import app.zoocall.core.model.Fingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallStateMachineTest {
    private val peer = Fingerprint.of(ByteArray(32) { 7 })
    private val id = CallId("0190c2a0-0000-7000-8000-000000000001")

    private fun CallState.on(event: CallEvent, now: Long = 1_000) = CallStateMachine.reduce(this, event, now)

    @Test
    fun outgoingHappyPath() {
        var t = CallStateMachine.outgoing(id, peer, CallKind.Video, 0)
        assertEquals(CallPhase.OutgoingInviting, t.state.phase)

        t = t.state.on(CallEvent.RemoteRinging)
        assertEquals(CallPhase.OutgoingRinging, t.state.phase)

        t = t.state.on(CallEvent.RemoteAccept(CallKind.Video))
        assertEquals(CallPhase.Connecting, t.state.phase)
        assertTrue(CallEffect.StartMedia in t.effects)

        t = t.state.on(CallEvent.MediaConnected, now = 2_000)
        assertEquals(CallPhase.Connected, t.state.phase)
        assertEquals(2_000, t.state.connectedAtMs)

        t = t.state.on(CallEvent.HangUp, now = 5_000)
        assertEquals(CallPhase.Ended, t.state.phase)
        assertEquals(EndReason.Completed, t.state.endReason)
        assertTrue(CallEffect.SendEnd(EndReason.Completed) in t.effects)
        assertTrue(CallEffect.StopMedia in t.effects)
    }

    @Test
    fun calleeCanDowngradeVideoToAudio() {
        val t = CallStateMachine.incoming(id, peer, CallKind.Video, 0).state.on(CallEvent.Accept(CallKind.Audio))
        assertEquals(CallKind.Audio, t.state.kind)
        assertTrue(CallEffect.SendAccept(CallKind.Audio) in t.effects)
    }

    @Test
    fun unansweredInviteTimesOutAsUnreachable() {
        val t = CallStateMachine.outgoing(id, peer, CallKind.Audio, 0).state.on(CallEvent.TimerFired(TimerKind.Invite))
        assertEquals(EndReason.Unreachable, t.state.endReason)
    }

    @Test
    fun ringingTimesOutAsMissedOnBothSides() {
        val caller = CallStateMachine.outgoing(id, peer, CallKind.Audio, 0).state
            .on(CallEvent.RemoteRinging).state
            .on(CallEvent.TimerFired(TimerKind.Ring))
        assertEquals(EndReason.Missed, caller.state.endReason)
        assertTrue(CallEffect.SendCancel in caller.effects)

        val callee = CallStateMachine.incoming(id, peer, CallKind.Audio, 0).state.on(CallEvent.TimerFired(TimerKind.Ring))
        assertEquals(EndReason.Missed, callee.state.endReason)
    }

    @Test
    fun callerCancelShowsMissedForCallee() {
        val t = CallStateMachine.incoming(id, peer, CallKind.Audio, 0).state.on(CallEvent.RemoteCancel)
        assertEquals(EndReason.Missed, t.state.endReason)
        assertTrue(t.effects.none { it is CallEffect.StopMedia })
    }

    @Test
    fun busyDeclineEndsOutgoingCall() {
        val t = CallStateMachine.outgoing(id, peer, CallKind.Audio, 0).state.on(CallEvent.RemoteDecline(EndReason.Busy))
        assertEquals(EndReason.Busy, t.state.endReason)
    }

    @Test
    fun mediaSetupTimeoutFailsCall() {
        val t = CallStateMachine.incoming(id, peer, CallKind.Audio, 0).state
            .on(CallEvent.Accept(CallKind.Audio)).state
            .on(CallEvent.TimerFired(TimerKind.MediaSetup))
        assertEquals(EndReason.FailedMedia, t.state.endReason)
        assertTrue(CallEffect.StopMedia in t.effects)
    }

    @Test
    fun reconnectRecoversOrExpires() {
        val connected = CallStateMachine.outgoing(id, peer, CallKind.Audio, 0).state
            .on(CallEvent.RemoteAccept(CallKind.Audio)).state
            .on(CallEvent.MediaConnected).state

        val dropped = connected.on(CallEvent.MediaDisconnected)
        assertEquals(CallPhase.Reconnecting, dropped.state.phase)
        assertTrue(CallEffect.RestartIce in dropped.effects, "offerer restarts ICE")

        assertEquals(CallPhase.Connected, dropped.state.on(CallEvent.MediaConnected).state.phase)

        val expired = dropped.state.on(CallEvent.TimerFired(TimerKind.Reconnect))
        assertEquals(EndReason.FailedNetwork, expired.state.endReason)
    }

    @Test
    fun endedStateIgnoresEvents() {
        val ended = CallStateMachine.incoming(id, peer, CallKind.Audio, 0).state.on(CallEvent.Decline).state
        val t = ended.on(CallEvent.Accept(CallKind.Audio))
        assertEquals(ended, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun collisionKeepsSmallerCallId() {
        val small = CallId("0190c2a0-0000-7000-8000-000000000001")
        val large = CallId("0190c2a0-0000-7000-8000-000000000002")
        assertTrue(CallStateMachine.remoteInviteWinsCollision(localCallId = large, remoteCallId = small))
        assertTrue(!CallStateMachine.remoteInviteWinsCollision(localCallId = small, remoteCallId = large))
    }
}
