package app.zoocall.core.call

import app.zoocall.core.model.CallDirection
import app.zoocall.core.model.CallId
import app.zoocall.core.model.CallKind
import app.zoocall.core.model.EndReason
import app.zoocall.core.model.Fingerprint
import app.zoocall.core.protocol.ProtocolConstants

enum class CallPhase {
    OutgoingInviting,
    OutgoingRinging,
    IncomingRinging,
    Connecting,
    Connected,
    Reconnecting,
    Ended,
}

/** One 1:1 call leg. Immutable; every change goes through [CallStateMachine.reduce]. */
data class CallState(
    val callId: CallId,
    val peer: Fingerprint,
    val direction: CallDirection,
    val requestedKind: CallKind,
    val kind: CallKind,
    val phase: CallPhase,
    val startedAtMs: Long,
    val connectedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    val endReason: EndReason? = null,
    /** A push-to-talk session: audio only, auto-accepted, the mic is on only while talking. */
    val pushToTalk: Boolean = false,
    /** A desk intercom line: audio only, auto-accepted, both microphones live (edge case H3). */
    val intercom: Boolean = false,
    /** Who sends SDP offers on this leg: the caller in 1:1; in a group, fixed per leg (docs/02 §7). */
    val isOfferer: Boolean = direction == CallDirection.Outgoing,
) {
    val isActive: Boolean get() = phase != CallPhase.Ended
}

enum class TimerKind { Invite, Ring, MediaSetup, Reconnect }

sealed interface CallEvent {
    // Local user
    data class Accept(val kind: CallKind) : CallEvent
    data object Decline : CallEvent
    data object HangUp : CallEvent

    // Remote peer (signaling)
    data object RemoteRinging : CallEvent
    data class RemoteAccept(val kind: CallKind) : CallEvent
    data class RemoteDecline(val reason: EndReason) : CallEvent
    data object RemoteCancel : CallEvent

    /** The caller stopped ringing us because another of our linked devices answered or declined. */
    data object HandledElsewhere : CallEvent
    data class RemoteEnd(val reason: EndReason) : CallEvent

    /** Control connection to the peer dropped / came back. */
    data object LinkLost : CallEvent
    data object LinkRestored : CallEvent

    // Media engine
    data object MediaConnected : CallEvent
    data object MediaDisconnected : CallEvent
    data object MediaFailed : CallEvent

    data class TimerFired(val timer: TimerKind) : CallEvent

    /** Sending the invite failed (peer unreachable). */
    data object InviteUndeliverable : CallEvent
}

sealed interface CallEffect {
    data object SendRinging : CallEffect
    data class SendAccept(val kind: CallKind) : CallEffect
    data object SendDecline : CallEffect
    data object SendCancel : CallEffect
    data class SendEnd(val reason: EndReason) : CallEffect

    /** Create the media session and, if offerer, send the SDP offer. */
    data object StartMedia : CallEffect
    data object RestartIce : CallEffect
    data object StopMedia : CallEffect

    data class StartTimer(val timer: TimerKind, val delayMs: Long) : CallEffect
    data class CancelTimer(val timer: TimerKind) : CallEffect
}

data class Transition(val state: CallState, val effects: List<CallEffect> = emptyList())

/**
 * Pure call state machine from docs/02-architecture.md §6: `(State, Event) → (State, Effects)`.
 * No I/O, no clocks (time is passed in), so every transition is unit-testable.
 */
object CallStateMachine {

    fun outgoing(callId: CallId, peer: Fingerprint, kind: CallKind, nowMs: Long, pushToTalk: Boolean = false, intercom: Boolean = false) = Transition(
        CallState(callId, peer, CallDirection.Outgoing, kind, kind, CallPhase.OutgoingInviting, nowMs, pushToTalk = pushToTalk, intercom = intercom),
        listOf(CallEffect.StartTimer(TimerKind.Invite, ProtocolConstants.INVITE_TRANSPORT_TIMEOUT_MS)),
    )

    /** An allowed push-to-talk invite: accepted straight away, never rings. */
    fun incomingPushToTalk(callId: CallId, peer: Fingerprint, nowMs: Long) = autoAccepted(callId, peer, nowMs, pushToTalk = true)

    /** An allowed desk intercom invite: accepted straight away, never rings. */
    fun incomingIntercom(callId: CallId, peer: Fingerprint, nowMs: Long) = autoAccepted(callId, peer, nowMs, intercom = true)

    private fun autoAccepted(callId: CallId, peer: Fingerprint, nowMs: Long, pushToTalk: Boolean = false, intercom: Boolean = false) = Transition(
        CallState(callId, peer, CallDirection.Incoming, CallKind.Audio, CallKind.Audio, CallPhase.Connecting, nowMs, pushToTalk = pushToTalk, intercom = intercom),
        listOf(
            CallEffect.StartMedia,
            CallEffect.SendAccept(CallKind.Audio),
            CallEffect.StartTimer(TimerKind.MediaSetup, ProtocolConstants.MEDIA_SETUP_TIMEOUT_MS),
        ),
    )

    fun incoming(callId: CallId, peer: Fingerprint, kind: CallKind, nowMs: Long) = Transition(
        CallState(callId, peer, CallDirection.Incoming, kind, kind, CallPhase.IncomingRinging, nowMs),
        listOf(CallEffect.SendRinging, CallEffect.StartTimer(TimerKind.Ring, ProtocolConstants.RING_TIMEOUT_MS)),
    )

    fun reduce(state: CallState, event: CallEvent, nowMs: Long): Transition {
        if (state.phase == CallPhase.Ended) return Transition(state)
        return when (state.phase) {
            CallPhase.OutgoingInviting, CallPhase.OutgoingRinging -> outgoingPreAnswer(state, event, nowMs)
            CallPhase.IncomingRinging -> incomingRinging(state, event, nowMs)
            CallPhase.Connecting -> connecting(state, event, nowMs)
            CallPhase.Connected -> connected(state, event, nowMs)
            CallPhase.Reconnecting -> reconnecting(state, event, nowMs)
            CallPhase.Ended -> Transition(state)
        }
    }

    private fun outgoingPreAnswer(state: CallState, event: CallEvent, nowMs: Long): Transition = when (event) {
        CallEvent.RemoteRinging -> if (state.phase == CallPhase.OutgoingInviting) {
            Transition(
                state.copy(phase = CallPhase.OutgoingRinging),
                listOf(
                    CallEffect.CancelTimer(TimerKind.Invite),
                    CallEffect.StartTimer(TimerKind.Ring, ProtocolConstants.RING_TIMEOUT_MS),
                ),
            )
        } else {
            Transition(state)
        }
        is CallEvent.RemoteAccept -> Transition(
            state.copy(phase = CallPhase.Connecting, kind = minKind(state.requestedKind, event.kind)),
            listOf(
                CallEffect.CancelTimer(TimerKind.Invite),
                CallEffect.CancelTimer(TimerKind.Ring),
                CallEffect.StartMedia,
                CallEffect.StartTimer(TimerKind.MediaSetup, ProtocolConstants.MEDIA_SETUP_TIMEOUT_MS),
            ),
        )
        is CallEvent.RemoteDecline -> end(state, event.reason, nowMs)
        CallEvent.HangUp -> end(state, EndReason.Cancelled, nowMs, CallEffect.SendCancel)
        CallEvent.InviteUndeliverable, CallEvent.LinkLost -> end(state, EndReason.Unreachable, nowMs)
        is CallEvent.TimerFired -> when (event.timer) {
            TimerKind.Invite -> end(state, EndReason.Unreachable, nowMs, CallEffect.SendCancel)
            TimerKind.Ring -> end(state, EndReason.Missed, nowMs, CallEffect.SendCancel)
            else -> Transition(state)
        }
        is CallEvent.RemoteEnd -> end(state, event.reason, nowMs)
        CallEvent.RemoteCancel -> end(state, EndReason.Cancelled, nowMs)
        else -> Transition(state)
    }

    private fun incomingRinging(state: CallState, event: CallEvent, nowMs: Long): Transition = when (event) {
        is CallEvent.Accept -> {
            val kind = minKind(state.requestedKind, event.kind)
            Transition(
                state.copy(phase = CallPhase.Connecting, kind = kind),
                listOf(
                    CallEffect.CancelTimer(TimerKind.Ring),
                    CallEffect.StartMedia,
                    CallEffect.SendAccept(kind),
                    CallEffect.StartTimer(TimerKind.MediaSetup, ProtocolConstants.MEDIA_SETUP_TIMEOUT_MS),
                ),
            )
        }
        CallEvent.Decline -> end(state, EndReason.Declined, nowMs, CallEffect.SendDecline)
        CallEvent.HandledElsewhere -> end(state, EndReason.AnsweredElsewhere, nowMs)
        CallEvent.RemoteCancel, CallEvent.LinkLost -> end(state, EndReason.Missed, nowMs)
        is CallEvent.RemoteEnd -> end(state, EndReason.Missed, nowMs)
        is CallEvent.TimerFired -> if (event.timer == TimerKind.Ring) end(state, EndReason.Missed, nowMs) else Transition(state)
        else -> Transition(state)
    }

    private fun connecting(state: CallState, event: CallEvent, nowMs: Long): Transition = when (event) {
        CallEvent.MediaConnected -> Transition(
            state.copy(phase = CallPhase.Connected, connectedAtMs = nowMs),
            listOf(CallEffect.CancelTimer(TimerKind.MediaSetup)),
        )
        CallEvent.MediaFailed -> end(state, EndReason.FailedMedia, nowMs, CallEffect.SendEnd(EndReason.FailedMedia))
        CallEvent.HangUp -> end(state, EndReason.Cancelled, nowMs, CallEffect.SendEnd(EndReason.Completed))
        is CallEvent.RemoteEnd -> end(state, event.reason, nowMs)
        CallEvent.RemoteCancel -> end(state, EndReason.Cancelled, nowMs)
        CallEvent.LinkLost -> end(state, EndReason.FailedNetwork, nowMs)
        is CallEvent.TimerFired -> if (event.timer == TimerKind.MediaSetup) {
            end(state, EndReason.FailedMedia, nowMs, CallEffect.SendEnd(EndReason.FailedMedia))
        } else {
            Transition(state)
        }
        else -> Transition(state)
    }

    private fun connected(state: CallState, event: CallEvent, nowMs: Long): Transition = when (event) {
        CallEvent.MediaDisconnected, CallEvent.LinkLost -> Transition(
            state.copy(phase = CallPhase.Reconnecting),
            listOfNotNull(
                CallEffect.StartTimer(TimerKind.Reconnect, ProtocolConstants.RECONNECT_GRACE_MS),
                // The offerer drives ICE restart; it needs the control link to signal.
                if (state.isOfferer && event == CallEvent.MediaDisconnected) CallEffect.RestartIce else null,
            ),
        )
        CallEvent.MediaFailed -> end(state, EndReason.FailedNetwork, nowMs, CallEffect.SendEnd(EndReason.FailedNetwork))
        CallEvent.HangUp -> end(state, EndReason.Completed, nowMs, CallEffect.SendEnd(EndReason.Completed))
        is CallEvent.RemoteEnd -> end(state, event.reason, nowMs)
        else -> Transition(state)
    }

    private fun reconnecting(state: CallState, event: CallEvent, nowMs: Long): Transition = when (event) {
        CallEvent.MediaConnected -> Transition(
            state.copy(phase = CallPhase.Connected),
            listOf(CallEffect.CancelTimer(TimerKind.Reconnect)),
        )
        CallEvent.LinkRestored -> Transition(state, if (state.isOfferer) listOf(CallEffect.RestartIce) else emptyList())
        CallEvent.HangUp -> end(state, EndReason.Completed, nowMs, CallEffect.SendEnd(EndReason.Completed))
        is CallEvent.RemoteEnd -> end(state, event.reason, nowMs)
        CallEvent.MediaFailed -> end(state, EndReason.FailedNetwork, nowMs, CallEffect.SendEnd(EndReason.FailedNetwork))
        is CallEvent.TimerFired -> if (event.timer == TimerKind.Reconnect) {
            end(state, EndReason.FailedNetwork, nowMs, CallEffect.SendEnd(EndReason.FailedNetwork))
        } else {
            Transition(state)
        }
        else -> Transition(state)
    }

    private fun end(state: CallState, reason: EndReason, nowMs: Long, vararg signal: CallEffect): Transition {
        val wasMediaStarted = state.phase in setOf(CallPhase.Connecting, CallPhase.Connected, CallPhase.Reconnecting)
        return Transition(
            state.copy(phase = CallPhase.Ended, endReason = reason, endedAtMs = nowMs),
            buildList {
                addAll(signal)
                TimerKind.entries.forEach { add(CallEffect.CancelTimer(it)) }
                if (wasMediaStarted) add(CallEffect.StopMedia)
            },
        )
    }

    private fun minKind(a: CallKind, b: CallKind) = if (a == CallKind.Video && b == CallKind.Video) CallKind.Video else CallKind.Audio

    /**
     * Call collision (docs/03-protocol.md §4.2): both sides invited each other.
     * Returns true when the remote invite survives, i.e. the local invite should be dropped
     * and the remote invite auto-accepted.
     */
    fun remoteInviteWinsCollision(localCallId: CallId, remoteCallId: CallId): Boolean = remoteCallId < localCallId
}
