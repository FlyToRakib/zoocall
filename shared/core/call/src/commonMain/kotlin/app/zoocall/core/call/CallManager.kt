package app.zoocall.core.call

import app.zoocall.core.model.CallDirection
import app.zoocall.core.model.CallId
import app.zoocall.core.model.CallKind
import app.zoocall.core.model.EndReason
import app.zoocall.core.model.Fingerprint
import app.zoocall.core.model.Logger
import app.zoocall.core.model.PeerAddress
import app.zoocall.core.protocol.Capabilities
import app.zoocall.core.protocol.ProtocolConstants
import app.zoocall.core.transport.ConnectionManager
import app.zoocall.core.transport.TransportEvent
import app.zoocall.core.transport.toModel
import app.zoocall.core.transport.toProto
import app.zoocall.media.IceCandidateData
import app.zoocall.media.IcePolicy
import app.zoocall.media.MediaConnectionState
import app.zoocall.media.MediaEngine
import app.zoocall.media.MediaEvent
import app.zoocall.media.MediaSession
import app.zoocall.media.ScreenShareResult
import app.zoocall.media.ScreenSource
import app.zoocall.protocol.v1.CallAccept
import app.zoocall.protocol.v1.CallCancel
import app.zoocall.protocol.v1.CallDecline
import app.zoocall.protocol.v1.CallEnd
import app.zoocall.protocol.v1.CallInvite
import app.zoocall.protocol.v1.CallRinging
import app.zoocall.protocol.v1.CallRoster
import app.zoocall.protocol.v1.CallUpgrade
import app.zoocall.protocol.v1.Envelope
import app.zoocall.protocol.v1.IceCandidate
import app.zoocall.protocol.v1.MediaState
import app.zoocall.protocol.v1.PttFloor
import app.zoocall.protocol.v1.RosterMember
import app.zoocall.protocol.v1.SessionDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/** A pending mid-call request to turn on video (edge case C11). */
enum class UpgradeRequest { Outgoing, Incoming }

/** A second incoming call while already in a call (edge case C2). */
data class WaitingCall(val callId: CallId, val peer: Fingerprint, val kind: CallKind, val startedAtMs: Long)

/** Short-lived information shown on the call screen. [ActiveCall.noticePeer] names the person, where relevant. */
enum class CallNotice { UpgradeDeclined, UpgradeNoAnswer, ChannelBusy, AddDeclined, AddBusy, AddNoAnswer }

enum class MemberPhase {
    /** We invited them (host); not answered yet. */
    Invited,
    Ringing,
    /** In the roster; our media leg to them isn't up yet. */
    Joining,
    Connecting,
    Connected,
    Reconnecting,
}

/** Someone in a group call besides [CallState.peer]. */
data class GroupMember(
    val fingerprint: Fingerprint,
    val phase: MemberPhase,
    /** Audio for people who joined a video call above the video limit (edge case C24). */
    val kind: CallKind,
    val micMuted: Boolean = false,
    val cameraOn: Boolean = kind == CallKind.Video,
    val media: MediaSession? = null,
    val screenSharing: Boolean = false,
    val recording: Boolean = false,
)

enum class AddParticipantResult { Invited, NotInCall, NotHost, AlreadyInCall, Full, NotSupported, Unreachable }

/** Someone else in the call is recording it. */
val ActiveCall.othersRecording: Boolean get() = remoteRecording || members.any { it.recording }

/** What the UI needs to render a call. */
data class ActiveCall(
    val state: CallState,
    val micMuted: Boolean = false,
    val cameraOn: Boolean,
    val remoteMicMuted: Boolean = false,
    val remoteCameraOn: Boolean,
    val media: MediaSession? = null,
    val onHold: Boolean = false,
    val remoteOnHold: Boolean = false,
    val upgrade: UpgradeRequest? = null,
    val waiting: WaitingCall? = null,
    val notice: CallNotice? = null,
    val noticePeer: Fingerprint? = null,
    /** Text the callee sent with their decline ("Call you back in 5"). */
    val remoteQuickReply: String? = null,
    /** Push-to-talk: we are transmitting. */
    val talking: Boolean = false,
    /** Push-to-talk: someone else is transmitting ([talker]). */
    val remoteTalking: Boolean = false,
    val talker: Fingerprint? = null,
    /** We are sharing a screen or window (capability call.screen). */
    val screenSharing: Boolean = false,
    /** We are recording this call (capability call.record); everyone in it is shown that. */
    val recording: Boolean = false,
    /** [CallState.peer] is recording this call. */
    val remoteRecording: Boolean = false,
    /** [CallState.peer] is sharing a screen: show their video uncropped. */
    val remoteScreenSharing: Boolean = false,
    /** Other people in a group call, in join order. Empty in a 1:1 call. */
    val members: List<GroupMember> = emptyList(),
    /** Who may add people once the call is a group (docs/02 §7). Null in a 1:1 call. */
    val host: Fingerprint? = null,
) {
    val isGroup: Boolean get() = members.isNotEmpty()
}

enum class IncomingDecision { Ring, DoNotDisturb, NotAllowed }

/** Decides whether an incoming call rings. Implemented by the app layer (contacts, block list, settings). */
fun interface CallPolicy {
    suspend fun decide(caller: Fingerprint): IncomingDecision
}

/**
 * Runs calls: applies [CallStateMachine] transitions and executes their effects (signaling over
 * the Noise channel, media sessions, timers). Also handles the in-call extras: hold, video upgrade
 * with consent, call waiting, push-to-talk and group calls.
 *
 * A group call is a full mesh of 1:1 media legs. [CallState.peer] is the leg the state machine
 * follows (whoever we called or who called us); the others are [ActiveCall.members]. If that leg
 * ends while others remain, the next connected member takes its place (edge case C22).
 *
 * All state changes are serialized by [lock], so transport, media and UI events can't race.
 */
class CallManager(
    private val scope: CoroutineScope,
    private val transport: ConnectionManager,
    private val mediaEngine: MediaEngine,
    private val policy: CallPolicy,
    /** Whether an incoming push-to-talk session connects (opt-in per contact); [IncomingDecision.Ring] allows it. */
    private val pttPolicy: CallPolicy = CallPolicy { IncomingDecision.NotAllowed },
    /** Whether an incoming desk intercom line connects (desktop opt-in, per contact); [IncomingDecision.Ring] allows it. */
    private val intercomPolicy: CallPolicy = CallPolicy { IncomingDecision.NotAllowed },
    private val logger: Logger = Logger.current,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** How long an ended call stays visible ("Call ended") before [activeCall] clears. */
    private val endedLingerMs: Long = 1_500,
) {
    private val lock = Mutex()
    private val timers = HashMap<TimerKind, Job>()
    private var lingerJob: Job? = null
    private var upgradeTimer: Job? = null
    /** Adds our video after an accepted upgrade; an incoming renegotiation offer waits for it. */
    private var upgradeJob: Job? = null
    private var waitingTimer: Job? = null
    private var noticeJob: Job? = null
    private var talkTimer: Job? = null
    private var remoteTalkTimer: Job? = null
    private var pendingQuickReply: String? = null

    /** One media leg per remote person in the call, including [CallState.peer]. */
    private class Leg(val peer: Fingerprint, val offerer: Boolean, val kind: CallKind) {
        var session: MediaSession? = null
        var job: Job? = null
        val ready = CompletableDeferred<MediaSession>()
        val pendingCandidates = ArrayList<IceCandidateData>()
        var remoteDescriptionApplied = false

        fun close() {
            job?.cancel()
            ready.cancel()
            session?.close()
            session = null
        }
    }

    private val legs = HashMap<Fingerprint, Leg>()
    private val memberTimers = HashMap<Fingerprint, Job>()

    /** Linked devices still ringing for our outgoing call, besides [CallState.peer]. */
    private val forks = LinkedHashSet<Fingerprint>()

    /** What we're sharing, so legs that start later share it too. */
    @kotlin.concurrent.Volatile private var screenSource: ScreenSource? = null

    /** SDP/ICE from a member that arrived before our leg to them existed; replayed when it starts. */
    private val earlySignals = HashMap<Fingerprint, MutableList<Envelope>>()

    /** Group join order, host first; decides who becomes host when the host leaves. */
    private var rosterOrder: List<Fingerprint> = emptyList()
    private val rosterAddresses = HashMap<Fingerprint, PeerAddress>()

    private val _activeCall = MutableStateFlow<ActiveCall?>(null)
    val activeCall: StateFlow<ActiveCall?> = _activeCall.asStateFlow()

    private val _finishedCalls = MutableSharedFlow<CallState>(extraBufferCapacity = 16)

    /** Every call that reached [CallPhase.Ended], for Recents and missed-call notifications. */
    val finishedCalls: SharedFlow<CallState> = _finishedCalls.asSharedFlow()

    fun start() {
        scope.launch {
            transport.events.collect { event ->
                try {
                    when (event) {
                        is TransportEvent.Received -> onEnvelope(event.from, event.envelope)
                        is TransportEvent.Disconnected -> onLink(event.fingerprint, lost = true)
                        is TransportEvent.Connected -> onLink(event.peer.fingerprint, lost = false)
                        is TransportEvent.Updated -> Unit
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logger.error(TAG, "Call event handling failed", e)
                }
            }
        }
    }

    val isInCall: Boolean get() = _activeCall.value?.state?.isActive == true

    /**
     * Starts an outgoing call, or a push-to-talk session when [pushToTalk] (always audio).
     * The peer must already be connected (the app layer dials first).
     */
    suspend fun startCall(
        peer: Fingerprint,
        kind: CallKind,
        pushToTalk: Boolean = false,
        /** The callee's linked devices (device.link): they ring too, and the first to answer takes the call. */
        alsoRing: List<Fingerprint> = emptyList(),
        /** A desk intercom line (call.intercom): audio only, connects without ringing if they allow it. */
        intercom: Boolean = false,
    ): CallId? = lock.withLock {
        val current = _activeCall.value?.state
        if (current != null && current.isActive) {
            // Both people called each other and theirs arrived first: calling back answers it.
            if (!pushToTalk && !current.pushToTalk && current.phase == CallPhase.IncomingRinging && current.peer == peer) {
                acceptLocked(if (kind == CallKind.Video && current.requestedKind == CallKind.Video) CallKind.Video else CallKind.Audio)
                return@withLock current.callId
            }
            return@withLock null
        }
        val callId = CallId.generate(now())
        val callKind = if (pushToTalk || intercom) CallKind.Audio else kind
        val invite = CallInvite(call_id = callId.value, kind = callKind.toProto(), push_to_talk = pushToTalk, intercom = intercom && !pushToTalk)
        // Replies wait for the lock, so inviting before the call is installed is safe.
        val targets = if (pushToTalk || intercom) listOf(peer) else (listOf(peer) + alsoRing).distinct()
        val delivered = targets.filter { transport.send(it, Envelope(call_invite = invite)) }
        val transition = CallStateMachine.outgoing(callId, delivered.firstOrNull() ?: peer, callKind, now(), pushToTalk, invite.intercom)
        install(transition.state)
        forks += delivered.drop(1)
        runEffects(transition)
        if (delivered.isEmpty()) reduce(CallEvent.InviteUndeliverable)
        callId
    }

    suspend fun accept(kind: CallKind) = lock.withLock { acceptLocked(kind) }

    /** Must be called with [lock] held. */
    private suspend fun acceptLocked(kind: CallKind) {
        reduce(CallEvent.Accept(kind))
        // Joining a group: connect to the people already in it.
        if (_activeCall.value?.state?.phase in MEDIA_PHASES) startJoiningLegs()
    }

    suspend fun hangUp() = dispatch(CallEvent.HangUp)

    /** Declines the ringing call, optionally with a short text shown to the caller. */
    suspend fun decline(quickReply: String? = null) = lock.withLock {
        pendingQuickReply = quickReply?.sanitizedReply()
        try {
            reduce(CallEvent.Decline)
        } finally {
            pendingQuickReply = null
        }
    }

    suspend fun setMicMuted(muted: Boolean) = lock.withLock {
        val call = _activeCall.value ?: return@withLock
        // In push-to-talk the mic follows the talk button.
        if (call.state.pushToTalk) return@withLock
        sessions().forEach { it.setMicEnabled(!muted && !call.onHold) }
        _activeCall.value = call.copy(micMuted = muted)
        sendMediaState()
    }

    suspend fun setCameraOn(on: Boolean) = lock.withLock {
        val call = _activeCall.value ?: return@withLock
        if (call.state.kind != CallKind.Video) return@withLock
        videoSessions().forEach { it.setCameraEnabled(on && !call.onHold) }
        _activeCall.value = call.copy(cameraOn = on)
        sendMediaState()
    }

    /** Pauses our audio and video without ending the call; the peer sees "On hold". Not in group calls. */
    suspend fun setOnHold(hold: Boolean) = lock.withLock {
        val call = _activeCall.value ?: return@withLock
        if (call.state.phase !in MEDIA_PHASES || call.onHold == hold || (hold && call.isGroup)) return@withLock
        call.media?.setMicEnabled(!hold && !call.micMuted)
        if (call.state.kind == CallKind.Video) call.media?.setCameraEnabled(!hold && call.cameraOn)
        _activeCall.value = call.copy(onHold = hold)
        sendMediaState()
    }

    fun switchCamera() {
        _activeCall.value?.media?.switchCamera()
    }

    private fun sessions(): List<MediaSession> = legs.values.mapNotNull { it.session }

    private fun videoSessions(): List<MediaSession> = legs.values.filter { it.kind == CallKind.Video }.mapNotNull { it.session }

    // -- Push-to-talk -----------------------------------------------------------------------------

    /**
     * Starts or stops transmitting in a push-to-talk session. Returns false when the peer holds
     * the floor (busy channel) or there is no live push-to-talk session.
     */
    suspend fun setTalking(talking: Boolean): Boolean = lock.withLock {
        val call = _activeCall.value ?: return@withLock false
        val state = call.state
        if (!state.pushToTalk || state.phase !in MEDIA_PHASES) return@withLock false
        if (call.talking == talking) return@withLock true
        if (talking && call.remoteTalking) {
            showNotice(CallNotice.ChannelBusy)
            return@withLock false
        }
        applyTalking(call, talking)
        broadcastFloor(state.callId, talking)
        true
    }

    /** Must be called with [lock] held. Tells everyone on the channel that we started or stopped talking. */
    private suspend fun broadcastFloor(callId: CallId, talking: Boolean) {
        val call = _activeCall.value ?: return
        val message = Envelope(ptt_floor = PttFloor(call_id = callId.value, talking = talking))
        send(call.state.peer, message)
        call.members.filter { it.phase in MEMBER_MEDIA_PHASES }.forEach { send(it.fingerprint, message) }
    }

    /** Must be called with [lock] held. */
    private fun applyTalking(call: ActiveCall, talking: Boolean) {
        sessions().forEach { it.setMicEnabled(talking && !call.onHold) }
        _activeCall.value = call.copy(talking = talking, micMuted = !talking)
        talkTimer?.cancel()
        talkTimer = null
        if (!talking) return
        val callId = call.state.callId
        // Edge case H4: a button held down by accident releases the floor after 60 s.
        talkTimer = scope.launch {
            delay(PTT_MAX_TALK_MS)
            lock.withLock {
                val current = _activeCall.value ?: return@withLock
                if (current.state.callId != callId || !current.talking) return@withLock
                talkTimer = null
                applyTalking(current, false)
                broadcastFloor(callId, false)
            }
        }
    }

    /** Must be called with [lock] held. */
    private fun onFloor(call: ActiveCall, from: Fingerprint, floor: PttFloor) {
        val state = call.state
        if (!state.pushToTalk || state.phase !in MEDIA_PHASES) return
        if (floor.talking) {
            if (call.talking) {
                // Both started at once: one keeps the floor, the other yields.
                if (keepsFloor(call, from)) return
                applyTalking(call, false)
                showNotice(CallNotice.ChannelBusy)
            }
            _activeCall.update { it?.copy(remoteTalking = true, talker = from) }
        } else {
            if (call.talker != null && call.talker != from) return
            _activeCall.update { it?.copy(remoteTalking = false, talker = null) }
        }
        remoteTalkTimer?.cancel()
        if (!floor.talking) return
        val callId = state.callId
        // Never show "talking" forever if the release got lost.
        remoteTalkTimer = scope.launch {
            delay(PTT_MAX_TALK_MS + 5_000)
            lock.withLock {
                val current = _activeCall.value
                if (current?.state?.callId == callId && current.talker == from) {
                    _activeCall.update { it?.copy(remoteTalking = false, talker = null) }
                }
            }
        }
    }

    /** On a tie: the caller in 1:1; in a group, whoever joined the channel first (host first). */
    private fun keepsFloor(call: ActiveCall, other: Fingerprint): Boolean {
        val self = rosterOrder.indexOf(transport.localFingerprint)
        val them = rosterOrder.indexOf(other)
        return if (self >= 0 && them >= 0) self < them else call.state.isOfferer
    }

    // -- Screen sharing ---------------------------------------------------------------------------

    /** Whether we can share a screen now: a live call (not push-to-talk) where everyone supports it. */
    fun canShareScreen(): Boolean {
        val call = _activeCall.value ?: return false
        if (mediaEngine.screenCapture == null || call.state.phase !in MEDIA_PHASES || call.state.pushToTalk || call.state.intercom) return false
        return (listOf(call.state.peer) + call.members.map { it.fingerprint }).all { peerSupports(it, Capabilities.CALL_SCREEN) }
    }

    /** Shares [source] with everyone in the call, or switches to it while already sharing. */
    suspend fun startScreenShare(source: ScreenSource): Boolean = lock.withLock {
        if (!canShareScreen()) return@withLock false
        val call = _activeCall.value ?: return@withLock false
        screenSource = source
        var started = false
        for (leg in legs.values.toList()) {
            if (shareOnLeg(call.state.callId, leg, source)) started = true
        }
        if (!started) {
            screenSource = null
            return@withLock false
        }
        if (!call.screenSharing) {
            _activeCall.update { it?.copy(screenSharing = true) }
            sendMediaState()
        }
        true
    }

    suspend fun stopScreenShare() = lock.withLock {
        val call = _activeCall.value ?: return@withLock
        if (!call.screenSharing) return@withLock
        screenSource = null
        sessions().forEach { it.stopScreenShare() }
        _activeCall.value = call.copy(screenSharing = false)
        sendMediaState()
    }

    /** Must be called with [lock] held. */
    private suspend fun shareOnLeg(callId: CallId, leg: Leg, source: ScreenSource): Boolean {
        val session = leg.session ?: return false
        return when (session.startScreenShare(source)) {
            ScreenShareResult.Failed -> false
            ScreenShareResult.Started -> true
            ScreenShareResult.NeedsOffer -> {
                // The new video section is ours, so we renegotiate even if the other side placed the call.
                scope.launch { runCatching { sendSdp(callId, leg.peer, SessionDescription.Type.TYPE_OFFER, session.createOffer()) } }
                true
            }
        }
    }

    // -- Group calls ------------------------------------------------------------------------------

    /** Whether we may invite more people right now: the host of a connected call (or push-to-talk channel). */
    fun canAddParticipants(): Boolean {
        val call = _activeCall.value ?: return false
        return call.state.phase == CallPhase.Connected && !call.state.intercom &&
            (call.members.isEmpty() || hostOf(call) == transport.localFingerprint) &&
            participantCount(call) < MAX_GROUP_AUDIO
    }

    private val _transferTarget = MutableStateFlow<Fingerprint?>(null)

    /** Who this call is being handed over to (attended transfer); we leave once they've joined. */
    val transferTarget: StateFlow<Fingerprint?> = _transferTarget.asStateFlow()

    /** Whether this 1:1 call can be handed over to someone else right now. */
    fun canTransfer(): Boolean {
        val call = _activeCall.value ?: return false
        return canAddParticipants() && call.members.isEmpty() && !call.state.pushToTalk && _transferTarget.value == null
    }

    /**
     * Attended transfer (docs/01 §4, the front-desk case): invites [peer] as if adding them, and
     * leaves the call once they've joined, so the other person carries on with them.
     */
    suspend fun transferTo(peer: Fingerprint): AddParticipantResult {
        if (!canTransfer()) return AddParticipantResult.NotInCall
        val result = addParticipant(peer)
        if (result == AddParticipantResult.Invited) _transferTarget.value = peer
        return result
    }

    /**
     * Invites [peer] into the current call (docs/02 §7). Only the host can. The peer must be
     * connected, and everyone in the call must support `call.group`.
     */
    suspend fun addParticipant(peer: Fingerprint): AddParticipantResult = lock.withLock {
        val call = _activeCall.value ?: return@withLock AddParticipantResult.NotInCall
        val state = call.state
        if (state.phase != CallPhase.Connected) return@withLock AddParticipantResult.NotInCall
        val self = transport.localFingerprint
        // In a 1:1 call either person may add someone and becomes the host; after that only the host can.
        if (call.members.isNotEmpty() && hostOf(call) != self) return@withLock AddParticipantResult.NotHost
        if (peer == self || peer == state.peer || call.members.any { it.fingerprint == peer }) return@withLock AddParticipantResult.AlreadyInCall
        if (participantCount(call) >= MAX_GROUP_AUDIO) return@withLock AddParticipantResult.Full
        if (!transport.isConnected(peer)) return@withLock AddParticipantResult.Unreachable
        val everyone = listOf(state.peer, peer) + call.members.map { it.fingerprint }
        if (everyone.any { !peerSupports(it, Capabilities.CALL_GROUP) }) return@withLock AddParticipantResult.NotSupported
        if (state.pushToTalk && !peerSupports(peer, Capabilities.PTT_V1)) return@withLock AddParticipantResult.NotSupported
        // An intercom line is between two desks; it doesn't grow into a group call.
        if (state.intercom) return@withLock AddParticipantResult.NotSupported
        // A recorded call only takes people whose app shows that it's being recorded.
        if (call.recording && !peerSupports(peer, Capabilities.CALL_RECORD)) return@withLock AddParticipantResult.NotSupported

        // A video call keeps at most 4 people on video; anyone after that joins audio-only (C24).
        val onVideo = 2 + call.members.count { it.kind == CallKind.Video }
        val kind = if (!state.pushToTalk && state.kind == CallKind.Video && onVideo < MAX_GROUP_VIDEO) CallKind.Video else CallKind.Audio
        if (rosterOrder.isEmpty()) rosterOrder = listOf(self, state.peer)
        rosterOrder = rosterOrder + peer
        val updated = call.copy(host = self, members = call.members + GroupMember(peer, MemberPhase.Invited, kind))
        _activeCall.value = updated

        // A push-to-talk channel invite connects without ringing if they allow push-to-talk from us.
        val invite = CallInvite(call_id = state.callId.value, kind = kind.toProto(), group = true, push_to_talk = state.pushToTalk)
        if (!send(peer, Envelope(call_invite = invite))) {
            dropMember(peer)
            return@withLock AddParticipantResult.Unreachable
        }
        send(peer, Envelope(call_roster = rosterMessage(updated, including = peer)))
        memberTimers[peer] = memberTimer(state.callId, peer, ProtocolConstants.RING_TIMEOUT_MS) { member ->
            if (member.phase == MemberPhase.Invited || member.phase == MemberPhase.Ringing) {
                send(peer, Envelope(call_cancel = CallCancel(call_id = state.callId.value)))
                dropMember(peer, CallNotice.AddNoAnswer)
            }
        }
        AddParticipantResult.Invited
    }

    private fun participantCount(call: ActiveCall) = 2 + call.members.size

    /** The host, or in a 1:1 call the caller (who may turn it into a group). */
    private fun hostOf(call: ActiveCall): Fingerprint? =
        call.host ?: if (call.state.direction == CallDirection.Outgoing) transport.localFingerprint else call.state.peer

    /** Must be called with [lock] held. The host's roster, for [including] a not-yet-joined invitee. */
    private fun rosterMessage(call: ActiveCall, including: Fingerprint? = null): CallRoster {
        val self = transport.localFingerprint
        val present = buildSet {
            add(self)
            add(call.state.peer)
            call.members.filter { it.phase in MEMBER_MEDIA_PHASES || it.fingerprint == including }.forEach { add(it.fingerprint) }
        }
        val members = rosterOrder.filter { it in present }.map { fp ->
            val address = if (fp == self) null else transport.peers.value[fp]?.address
            val video = when (fp) {
                self, call.state.peer -> call.state.kind == CallKind.Video
                else -> call.members.firstOrNull { it.fingerprint == fp }?.kind == CallKind.Video
            }
            RosterMember(fingerprint = fp.hex, host = address?.host.orEmpty(), port = address?.port ?: 0, video = video)
        }
        return CallRoster(call_id = call.state.callId.value, host_fingerprint = self.hex, members = members)
    }

    /** Must be called with [lock] held. */
    private suspend fun broadcastRoster() {
        val call = _activeCall.value ?: return
        val message = Envelope(call_roster = rosterMessage(call))
        send(call.state.peer, message)
        call.members.filter { it.phase in MEMBER_MEDIA_PHASES }.forEach { send(it.fingerprint, message) }
    }

    /** Must be called with [lock] held. */
    private fun onRoster(call: ActiveCall, from: Fingerprint, roster: CallRoster) {
        val self = transport.localFingerprint
        val host = runCatching { Fingerprint.fromHex(roster.host_fingerprint) }.getOrNull() ?: return
        // Only the host may change who is in the call; in a 1:1 call the other person can become the host.
        val mayLead = from == hostOf(call) || (call.members.isEmpty() && from == call.state.peer)
        if (host != from || !mayLead) return
        val entries = roster.members.take(MAX_GROUP_AUDIO).mapNotNull { m ->
            runCatching { Fingerprint.fromHex(m.fingerprint) }.getOrNull()?.let { fp -> Triple(fp, m, fp == self) }
        }
        if (entries.none { it.third }) return
        rosterOrder = entries.map { it.first }
        var members = call.members
        for ((fp, member, isSelf) in entries) {
            if (member.host.isNotEmpty()) runCatching { PeerAddress(member.host, member.port) }.getOrNull()?.let { rosterAddresses[fp] = it }
            if (isSelf || fp == call.state.peer || members.any { it.fingerprint == fp }) continue
            val kind = if (member.video && call.state.kind == CallKind.Video) CallKind.Video else CallKind.Audio
            members = members + GroupMember(fp, MemberPhase.Joining, kind)
        }
        _activeCall.value = call.copy(members = members, host = host)
        if (call.state.phase in MEDIA_PHASES) startJoiningLegs()
    }

    /**
     * Must be called with [lock] held. Opens a media leg to every roster member we aren't connected
     * to yet. The lower fingerprint dials and offers, so both sides agree without extra messages.
     */
    private fun startJoiningLegs() {
        val call = _activeCall.value ?: return
        val self = transport.localFingerprint
        val callId = call.state.callId
        for (member in call.members.filter { it.phase == MemberPhase.Joining }) {
            val fp = member.fingerprint
            updateMember(fp) { it.copy(phase = MemberPhase.Connecting) }
            val offerer = self.hex < fp.hex
            memberTimers[fp] = scope.launch {
                val connected = if (offerer) {
                    transport.isConnected(fp) || rosterAddresses[fp]?.let { transport.connect(it).isSuccess } == true
                } else {
                    withTimeoutOrNull(ProtocolConstants.MEDIA_SETUP_TIMEOUT_MS) { transport.peers.first { it.containsKey(fp) } } != null
                }
                lock.withLock {
                    val current = _activeCall.value ?: return@withLock
                    if (current.state.callId != callId || current.members.none { it.fingerprint == fp }) return@withLock
                    memberTimers.remove(fp)
                    if (!connected) {
                        logger.info(TAG, "Group member unreachable")
                        dropMember(fp)
                        return@withLock
                    }
                    startLeg(callId, Leg(fp, offerer, member.kind))
                    memberTimers[fp] = setupTimer(callId, fp)
                }
            }
        }
    }

    /** Must be called with [lock] held. Signaling on a group call leg other than [CallState.peer]. */
    private suspend fun onMemberEnvelope(call: ActiveCall, from: Fingerprint, envelope: Envelope) {
        val id = call.state.callId.value
        val member = call.members.firstOrNull { it.fingerprint == from }
        if (member == null) {
            // A new member can connect and offer before the host's roster reaches us: keep it for a moment.
            val forThisCall = (envelope.sdp?.call_id ?: envelope.ice?.call_id) == id
            if (forThisCall && call.state.isActive && (earlySignals.containsKey(from) || earlySignals.size < MAX_GROUP_AUDIO)) {
                queueEarlySignal(from, envelope)
            }
            return
        }
        val ringing = envelope.call_ringing
        val accept = envelope.call_accept
        val decline = envelope.call_decline
        val cancel = envelope.call_cancel
        val end = envelope.call_end
        val mediaState = envelope.media_state
        val sdp = envelope.sdp
        val ice = envelope.ice
        val floor = envelope.ptt_floor
        when {
            floor != null && floor.call_id == id -> if (member.phase in MEMBER_MEDIA_PHASES) onFloor(call, from, floor)
            ringing != null && ringing.call_id == id ->
                if (member.phase == MemberPhase.Invited) updateMember(from) { it.copy(phase = MemberPhase.Ringing) }
            accept != null && accept.call_id == id -> if (member.phase == MemberPhase.Invited || member.phase == MemberPhase.Ringing) {
                memberTimers.remove(from)?.cancel()
                updateMember(from) { it.copy(phase = MemberPhase.Connecting) }
                // We invited them, so on this leg we are the caller and offer (as in 1:1).
                startLeg(call.state.callId, Leg(from, offerer = true, kind = member.kind))
                memberTimers[from] = setupTimer(call.state.callId, from)
                broadcastRoster()
            }
            decline != null && decline.call_id == id ->
                dropMember(from, if (decline.reason == CallDecline.Reason.REASON_BUSY) CallNotice.AddBusy else CallNotice.AddDeclined)
            (end != null && end.call_id == id) || (cancel != null && cancel.call_id == id) -> dropMember(from)
            mediaState != null && mediaState.call_id == id ->
                updateMember(from) {
                    it.copy(micMuted = mediaState.mic_muted, cameraOn = mediaState.camera_on, screenSharing = mediaState.screen_sharing, recording = mediaState.recording)
                }
            sdp != null && sdp.call_id == id -> when {
                member.phase !in MEMBER_MEDIA_PHASES -> Unit
                legs.containsKey(from) -> onRemoteSdp(call.state.callId, from, sdp)
                else -> queueEarlySignal(from, envelope)
            }
            ice != null && ice.call_id == id -> when {
                legs.containsKey(from) -> onRemoteIce(from, ice)
                member.phase in MEMBER_MEDIA_PHASES -> queueEarlySignal(from, envelope)
            }
        }
    }

    /** The other side offers as soon as it's connected, possibly before our leg to them exists. */
    private fun queueEarlySignal(from: Fingerprint, envelope: Envelope) {
        val queue = earlySignals.getOrPut(from) { ArrayList() }
        if (queue.size < MAX_PENDING_CANDIDATES) queue += envelope
    }

    /** Must be called with [lock] held. */
    private suspend fun onMemberMedia(callId: CallId, peer: Fingerprint, state: MediaConnectionState) {
        val call = _activeCall.value ?: return
        val member = call.members.firstOrNull { it.fingerprint == peer } ?: return
        val leg = legs[peer] ?: return
        when (state) {
            MediaConnectionState.Connected -> {
                memberTimers.remove(peer)?.cancel()
                updateMember(peer) { it.copy(phase = MemberPhase.Connected) }
                // Transfer: they've joined, so we step out. Launched because hanging up takes the lock.
                if (_transferTarget.value == peer) scope.launch { hangUp() }
                // Someone joining a call that's being recorded is told at once.
                if (_activeCall.value?.recording == true) sendMediaState()
            }
            MediaConnectionState.Disconnected -> if (member.phase == MemberPhase.Connected) {
                updateMember(peer) { it.copy(phase = MemberPhase.Reconnecting) }
                if (leg.offerer) restartIce(callId, leg)
                memberTimers.remove(peer)?.cancel()
                memberTimers[peer] = memberTimer(callId, peer, ProtocolConstants.RECONNECT_GRACE_MS) { m ->
                    if (m.phase != MemberPhase.Connected) {
                        send(peer, Envelope(call_end = CallEnd(call_id = callId.value, reason = CallEnd.Reason.REASON_FAILED_NETWORK)))
                        dropMember(peer)
                    }
                }
            }
            MediaConnectionState.Failed -> {
                send(peer, Envelope(call_end = CallEnd(call_id = callId.value, reason = CallEnd.Reason.REASON_FAILED_NETWORK)))
                dropMember(peer)
            }
            else -> Unit
        }
    }

    private fun setupTimer(callId: CallId, peer: Fingerprint) = memberTimer(callId, peer, ProtocolConstants.MEDIA_SETUP_TIMEOUT_MS) { m ->
        if (m.phase != MemberPhase.Connected && m.phase != MemberPhase.Reconnecting) {
            send(peer, Envelope(call_end = CallEnd(call_id = callId.value, reason = CallEnd.Reason.REASON_FAILED_MEDIA)))
            dropMember(peer)
        }
    }

    private fun memberTimer(callId: CallId, peer: Fingerprint, delayMs: Long, onExpire: suspend (GroupMember) -> Unit): Job = scope.launch {
        delay(delayMs)
        lock.withLock {
            val call = _activeCall.value ?: return@withLock
            if (call.state.callId != callId) return@withLock
            call.members.firstOrNull { it.fingerprint == peer }?.let { onExpire(it) }
        }
    }

    private fun updateMember(peer: Fingerprint, change: (GroupMember) -> GroupMember) {
        _activeCall.update { call -> call?.copy(members = call.members.map { if (it.fingerprint == peer) change(it) else it }) }
    }

    /** Must be called with [lock] held. Someone left the group (or never joined). */
    private fun dropMember(peer: Fingerprint, notice: CallNotice? = null) {
        memberTimers.remove(peer)?.cancel()
        earlySignals.remove(peer)
        legs.remove(peer)?.close()
        // The person we were transferring to didn't join: the call stays with us.
        if (_transferTarget.value == peer) _transferTarget.value = null
        val call = _activeCall.value ?: return
        val host = if (call.host == peer) nextHost(call, leaving = peer) else call.host
        rosterOrder = rosterOrder - peer
        val stillTalking = call.remoteTalking && call.talker != peer
        _activeCall.value = call.copy(
            members = call.members.filterNot { it.fingerprint == peer },
            host = host,
            remoteTalking = stillTalking,
            talker = if (stillTalking) call.talker else null,
        )
        notice?.let { showNotice(it, peer) }
    }

    /** The earliest joiner still in the call becomes host (edge case C22). */
    private fun nextHost(call: ActiveCall, leaving: Fingerprint): Fingerprint? {
        val present = call.members.filter { it.phase in MEMBER_MEDIA_PHASES }.map { it.fingerprint }.toSet() +
            call.state.peer + transport.localFingerprint - leaving
        return rosterOrder.firstOrNull { it in present }
    }

    /**
     * Must be called with [lock] held. The state machine's leg ended in a group call: the next
     * connected member takes its place and the call goes on. Returns false when nobody is left.
     */
    private suspend fun promoteMember(call: ActiveCall, event: CallEvent): Boolean {
        // A connected member first; otherwise one whose media is still being set up (e.g. right after a transfer).
        val next = call.members.firstOrNull { it.phase == MemberPhase.Connected || it.phase == MemberPhase.Reconnecting }
            ?: call.members.firstOrNull { it.phase == MemberPhase.Connecting || it.phase == MemberPhase.Joining }
            ?: return false
        val old = call.state
        logger.info(TAG, "Group call continues without the leg that ended")
        timers.values.forEach { it.cancel() }
        timers.clear()
        if (event !is CallEvent.RemoteEnd) {
            send(old.peer, Envelope(call_end = CallEnd(call_id = old.callId.value, reason = CallEnd.Reason.REASON_FAILED_NETWORK)))
        }
        legs.remove(old.peer)?.close()
        memberTimers.remove(next.fingerprint)?.cancel()
        val host = if (call.host == old.peer) nextHost(call, leaving = old.peer) else call.host
        rosterOrder = rosterOrder - old.peer
        val reconnecting = next.phase == MemberPhase.Reconnecting
        val connecting = next.phase == MemberPhase.Connecting || next.phase == MemberPhase.Joining
        val state = old.copy(
            peer = next.fingerprint,
            isOfferer = legs[next.fingerprint]?.offerer ?: false,
            phase = when {
                reconnecting -> CallPhase.Reconnecting
                // Their leg's "connected" now completes the call, with the usual setup timeout.
                connecting -> CallPhase.Connecting
                else -> CallPhase.Connected
            },
        )
        _activeCall.value = call.copy(
            state = state,
            media = next.media,
            remoteMicMuted = next.micMuted,
            remoteCameraOn = next.cameraOn,
            remoteScreenSharing = next.screenSharing,
            remoteRecording = next.recording,
            remoteOnHold = false,
            members = call.members - next,
            host = host,
            upgrade = null,
        )
        if (reconnecting) runEffects(Transition(state, listOf(CallEffect.StartTimer(TimerKind.Reconnect, ProtocolConstants.RECONNECT_GRACE_MS))))
        if (connecting) runEffects(Transition(state, listOf(CallEffect.StartTimer(TimerKind.MediaSetup, ProtocolConstants.MEDIA_SETUP_TIMEOUT_MS))))
        return true
    }

    // -- Video upgrade ----------------------------------------------------------------------------

    /** Asks the peer to switch this audio call to video. Returns false when that isn't possible now. */
    suspend fun requestVideoUpgrade(): Boolean = lock.withLock {
        val call = _activeCall.value ?: return@withLock false
        val state = call.state
        if (state.phase != CallPhase.Connected || state.kind != CallKind.Audio || call.upgrade != null || call.isGroup) return@withLock false
        if (!peerSupports(state.peer, Capabilities.CALL_UPGRADE)) return@withLock false
        if (!sendUpgrade(state, CallUpgrade.Action.ACTION_REQUEST)) return@withLock false
        _activeCall.value = call.copy(upgrade = UpgradeRequest.Outgoing)
        upgradeTimer?.cancel()
        upgradeTimer = scope.launch {
            delay(UPGRADE_TIMEOUT_MS)
            lock.withLock {
                val current = _activeCall.value ?: return@withLock
                if (current.state.callId == state.callId && current.upgrade == UpgradeRequest.Outgoing) {
                    sendUpgrade(current.state, CallUpgrade.Action.ACTION_CANCEL)
                    _activeCall.value = current.copy(upgrade = null)
                    showNotice(CallNotice.UpgradeNoAnswer)
                }
            }
        }
        true
    }

    suspend fun cancelVideoUpgrade() = lock.withLock {
        val call = _activeCall.value ?: return@withLock
        if (call.upgrade != UpgradeRequest.Outgoing) return@withLock
        upgradeTimer?.cancel()
        sendUpgrade(call.state, CallUpgrade.Action.ACTION_CANCEL)
        _activeCall.value = call.copy(upgrade = null)
    }

    /** Answers the peer's request to turn on video. Never turns the camera on without this (edge case C11). */
    suspend fun respondToVideoUpgrade(accept: Boolean) = lock.withLock {
        val call = _activeCall.value ?: return@withLock
        if (call.upgrade != UpgradeRequest.Incoming) return@withLock
        sendUpgrade(call.state, if (accept) CallUpgrade.Action.ACTION_ACCEPT else CallUpgrade.Action.ACTION_DECLINE)
        _activeCall.value = call.copy(upgrade = null)
        if (accept) applyUpgrade()
    }

    private suspend fun onUpgrade(call: ActiveCall, upgrade: CallUpgrade) {
        val state = call.state
        when (upgrade.action) {
            CallUpgrade.Action.ACTION_REQUEST -> when {
                state.phase != CallPhase.Connected || state.kind != CallKind.Audio || call.isGroup ->
                    sendUpgrade(state, CallUpgrade.Action.ACTION_DECLINE)
                // Both asked at the same moment: both want video, so accept.
                call.upgrade == UpgradeRequest.Outgoing -> {
                    upgradeTimer?.cancel()
                    sendUpgrade(state, CallUpgrade.Action.ACTION_ACCEPT)
                    _activeCall.value = call.copy(upgrade = null)
                    applyUpgrade()
                }
                else -> _activeCall.value = call.copy(upgrade = UpgradeRequest.Incoming)
            }
            CallUpgrade.Action.ACTION_ACCEPT -> if (call.upgrade == UpgradeRequest.Outgoing) {
                upgradeTimer?.cancel()
                _activeCall.value = call.copy(upgrade = null)
                applyUpgrade()
            }
            CallUpgrade.Action.ACTION_DECLINE -> if (call.upgrade == UpgradeRequest.Outgoing) {
                upgradeTimer?.cancel()
                _activeCall.value = call.copy(upgrade = null)
                showNotice(CallNotice.UpgradeDeclined)
            }
            CallUpgrade.Action.ACTION_CANCEL -> if (call.upgrade == UpgradeRequest.Incoming) {
                _activeCall.value = call.copy(upgrade = null)
            }
            else -> Unit
        }
    }

    /** Must be called with [lock] held. Switches the call to video and, as offerer, renegotiates. */
    private fun applyUpgrade() {
        val call = _activeCall.value ?: return
        if (call.state.kind == CallKind.Video) return
        val state = call.state.copy(kind = CallKind.Video)
        val session = call.media
        _activeCall.value = call.copy(state = state, cameraOn = mediaEngine.hasCamera, remoteCameraOn = true)
        if (session == null) return
        upgradeJob = scope.launch {
            try {
                val cameraStarted = session.upgradeToVideo()
                if (call.onHold) session.setCameraEnabled(false)
                lock.withLock {
                    val current = _activeCall.value
                    if (current?.state?.callId == state.callId && current.cameraOn != cameraStarted) {
                        _activeCall.value = current.copy(cameraOn = cameraStarted)
                        sendMediaState()
                    }
                }
                if (state.isOfferer) sendSdp(state.callId, state.peer, SessionDescription.Type.TYPE_OFFER, session.createOffer())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.warn(TAG, "Video upgrade failed", e)
            }
        }
    }

    private suspend fun sendUpgrade(state: CallState, action: CallUpgrade.Action) =
        send(state.peer, Envelope(call_upgrade = CallUpgrade(call_id = state.callId.value, action = action)))

    private fun showNotice(notice: CallNotice, peer: Fingerprint? = null) {
        val callId = _activeCall.value?.state?.callId ?: return
        _activeCall.update { it?.copy(notice = notice, noticePeer = peer) }
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(NOTICE_MS)
            lock.withLock {
                if (_activeCall.value?.state?.callId == callId) _activeCall.update { it?.copy(notice = null, noticePeer = null) }
            }
        }
    }

    // -- Call waiting -----------------------------------------------------------------------------

    /** Ends the current call and answers the waiting one. */
    suspend fun acceptWaiting(kind: CallKind) = lock.withLock {
        val call = _activeCall.value ?: return@withLock
        val waiting = call.waiting ?: return@withLock
        waitingTimer?.cancel()
        _activeCall.value = call.copy(waiting = null)
        if (call.state.isActive) reduce(CallEvent.HangUp)
        val incoming = CallStateMachine.incoming(waiting.callId, waiting.peer, waiting.kind, waiting.startedAtMs)
        install(incoming.state)
        runEffects(incoming)
        acceptLocked(kind)
    }

    suspend fun declineWaiting() = lock.withLock {
        val call = _activeCall.value ?: return@withLock
        val waiting = call.waiting ?: return@withLock
        send(waiting.peer, Envelope(call_decline = CallDecline(call_id = waiting.callId.value, reason = CallDecline.Reason.REASON_DECLINED)))
        finishWaiting(waiting, EndReason.Declined)
    }

    /** Must be called with [lock] held. */
    private fun finishWaiting(waiting: WaitingCall, reason: EndReason) {
        waitingTimer?.cancel()
        _activeCall.update { if (it?.waiting?.callId == waiting.callId) it.copy(waiting = null) else it }
        _finishedCalls.tryEmit(
            CallState(waiting.callId, waiting.peer, CallDirection.Incoming, waiting.kind, waiting.kind, CallPhase.Ended, waiting.startedAtMs, endedAtMs = now(), endReason = reason),
        )
    }

    // ---------------------------------------------------------------------------------------------

    private suspend fun dispatch(event: CallEvent) = lock.withLock { reduce(event) }

    /** Must be called with [lock] held. */
    private suspend fun reduce(event: CallEvent) {
        val call = _activeCall.value ?: return
        val current = call.state
        val transition = CallStateMachine.reduce(current, event, now())
        if (transition.state == current && transition.effects.isEmpty()) return
        // A group call goes on when one person leaves; only our own hang-up ends it for us.
        if (!transition.state.isActive && event != CallEvent.HangUp && current.phase in MEDIA_PHASES && promoteMember(call, event)) return
        _activeCall.update { it?.copy(state = transition.state) }
        runEffects(transition)
        if (!transition.state.isActive) onEnded(transition.state)
    }

    private fun install(state: CallState) {
        forks.clear()
        lingerJob?.cancel()
        upgradeTimer?.cancel()
        noticeJob?.cancel()
        talkTimer?.cancel()
        remoteTalkTimer?.cancel()
        closeAllLegs()
        _activeCall.value = ActiveCall(
            state = state,
            // Push-to-talk starts silent: the mic opens only while talking.
            micMuted = state.pushToTalk,
            cameraOn = state.kind == CallKind.Video,
            remoteCameraOn = state.kind == CallKind.Video,
        )
    }

    private fun closeAllLegs() {
        memberTimers.values.forEach { it.cancel() }
        memberTimers.clear()
        earlySignals.clear()
        screenSource = null
        legs.values.forEach { it.close() }
        legs.clear()
        rosterOrder = emptyList()
        rosterAddresses.clear()
        _transferTarget.value = null
        audioTap = null
    }

    // -- Recording (call.record) ------------------------------------------------------------------

    @kotlin.concurrent.Volatile private var audioTap: app.zoocall.media.AudioTap? = null

    /** Whether we can record now: a connected call (not push-to-talk) where everyone's app shows recording. */
    fun canRecord(): Boolean {
        val call = _activeCall.value ?: return false
        if (call.state.phase != CallPhase.Connected || call.state.pushToTalk) return false
        return (listOf(call.state.peer) + call.members.map { it.fingerprint }).all { peerSupports(it, Capabilities.CALL_RECORD) }
    }

    /**
     * Starts recording (every leg's audio goes to [tap]) or stops it (null). Everyone in the call is
     * told, so their apps show a banner and play a chime: recording can't start silently (docs/04).
     */
    suspend fun setRecording(tap: app.zoocall.media.AudioTap?): Boolean = lock.withLock {
        val call = _activeCall.value ?: return@withLock false
        if (tap != null && !canRecord()) return@withLock false
        audioTap = tap
        sessions().forEach { it.setAudioTap(tap) }
        if (call.recording != (tap != null)) {
            _activeCall.update { it?.copy(recording = tap != null) }
            sendMediaState()
        }
        true
    }

    private suspend fun runEffects(transition: Transition) {
        val state = transition.state
        for (effect in transition.effects) {
            when (effect) {
                CallEffect.SendRinging -> send(state.peer, Envelope(call_ringing = CallRinging(call_id = state.callId.value)))
                is CallEffect.SendAccept -> send(
                    state.peer,
                    Envelope(call_accept = CallAccept(call_id = state.callId.value, accepted_kind = effect.kind.toProto())),
                )
                CallEffect.SendDecline -> send(
                    state.peer,
                    Envelope(
                        call_decline = CallDecline(
                            call_id = state.callId.value,
                            reason = CallDecline.Reason.REASON_DECLINED,
                            quick_reply = pendingQuickReply.orEmpty(),
                        ),
                    ),
                )
                CallEffect.SendCancel -> send(state.peer, Envelope(call_cancel = CallCancel(call_id = state.callId.value)))
                is CallEffect.SendEnd -> send(
                    state.peer,
                    Envelope(call_end = CallEnd(call_id = state.callId.value, reason = effect.reason.toEndProto())),
                )
                CallEffect.StartMedia -> startLeg(state.callId, Leg(state.peer, state.isOfferer, state.kind))
                CallEffect.RestartIce -> legs[state.peer]?.let { restartIce(state.callId, it) }
                CallEffect.StopMedia -> {
                    legs.remove(state.peer)?.close()
                    _activeCall.update { it?.copy(media = null) }
                }
                is CallEffect.StartTimer -> {
                    timers.remove(effect.timer)?.cancel()
                    timers[effect.timer] = scope.launch {
                        delay(effect.delayMs)
                        dispatch(CallEvent.TimerFired(effect.timer))
                    }
                }
                is CallEffect.CancelTimer -> timers.remove(effect.timer)?.cancel()
            }
        }
    }

    /** Must be called with [lock] held. */
    private suspend fun onEnded(state: CallState) {
        logger.info(TAG, "Call ended: ${state.endReason}")
        upgradeTimer?.cancel()
        talkTimer?.cancel()
        remoteTalkTimer?.cancel()
        // Nobody answered: the callee's other devices stop ringing and show a missed call.
        forks.forEach { send(it, Envelope(call_cancel = CallCancel(call_id = state.callId.value))) }
        forks.clear()
        // Leaving a group: tell everyone else too, and cancel invitations nobody answered yet.
        _activeCall.value?.members?.forEach { member ->
            val message = if (member.phase == MemberPhase.Invited || member.phase == MemberPhase.Ringing) {
                Envelope(call_cancel = CallCancel(call_id = state.callId.value))
            } else {
                Envelope(call_end = CallEnd(call_id = state.callId.value, reason = CallEnd.Reason.REASON_COMPLETED))
            }
            send(member.fingerprint, message)
        }
        closeAllLegs()
        _activeCall.update { it?.copy(members = emptyList(), media = null) }
        _finishedCalls.tryEmit(state)
        // A call was waiting: it now rings like a normal incoming call.
        val waiting = _activeCall.value?.waiting
        if (waiting != null) {
            waitingTimer?.cancel()
            val incoming = CallStateMachine.incoming(waiting.callId, waiting.peer, waiting.kind, waiting.startedAtMs)
            install(incoming.state)
            runEffects(incoming)
            return
        }
        lingerJob = scope.launch {
            delay(endedLingerMs)
            lock.withLock {
                if (_activeCall.value?.state?.callId == state.callId && _activeCall.value?.state?.isActive == false) {
                    _activeCall.value = null
                }
            }
        }
    }

    private suspend fun send(peer: Fingerprint, envelope: Envelope): Boolean {
        val ok = transport.send(peer, envelope)
        if (!ok) logger.info(TAG, "Signal not delivered")
        return ok
    }

    private fun peerSupports(peer: Fingerprint, capability: String) =
        transport.peers.value[peer]?.hello?.capabilities?.contains(capability) == true

    private suspend fun sendMediaState() {
        val call = _activeCall.value ?: return
        val message = Envelope(
            media_state = MediaState(
                call_id = call.state.callId.value,
                mic_muted = call.micMuted,
                camera_on = call.cameraOn && call.state.kind == CallKind.Video,
                screen_sharing = call.screenSharing,
                on_hold = call.onHold,
                recording = call.recording,
            ),
        )
        send(call.state.peer, message)
        call.members.filter { it.phase in MEMBER_MEDIA_PHASES }.forEach { send(it.fingerprint, message) }
    }

    // -- Media ------------------------------------------------------------------------------------

    /** Must be called with [lock] held. Creates one leg's media session and wires its events. */
    private fun startLeg(callId: CallId, leg: Leg) {
        legs.remove(leg.peer)?.close()
        val session = try {
            mediaEngine.createSession(callId, leg.kind, leg.offerer)
        } catch (e: Exception) {
            logger.error(TAG, "Media session creation failed", e)
            scope.launch { onLegMedia(callId, leg.peer, MediaConnectionState.Failed) }
            return
        }
        leg.session = session
        audioTap?.let(session::setAudioTap)
        legs[leg.peer] = leg
        val call = _activeCall.value ?: return
        if (leg.peer == call.state.peer) {
            _activeCall.value = call.copy(media = session, cameraOn = call.state.kind == CallKind.Video && mediaEngine.hasCamera)
        } else {
            updateMember(leg.peer) { it.copy(media = session) }
        }
        leg.job = scope.launch {
            launch {
                session.events.collect { event ->
                    when (event) {
                        is MediaEvent.LocalCandidate -> if (IcePolicy.isAllowed(event.candidate.candidate)) {
                            send(
                                leg.peer,
                                Envelope(
                                    ice = IceCandidate(
                                        call_id = callId.value,
                                        candidate = event.candidate.candidate,
                                        sdp_mid = event.candidate.sdpMid,
                                        sdp_mline_index = event.candidate.sdpMLineIndex,
                                    ),
                                ),
                            )
                        }
                        is MediaEvent.ConnectionStateChanged -> onLegMedia(callId, leg.peer, event.state)
                        is MediaEvent.LocalOffer -> sendSdp(callId, leg.peer, SessionDescription.Type.TYPE_OFFER, event.sdp)
                        is MediaEvent.Error -> logger.warn(TAG, "Media: ${event.message}")
                        // Stop for every leg and tell the others; launched because stopping takes the lock.
                        MediaEvent.ScreenShareEnded -> scope.launch { stopScreenShare() }
                    }
                }
            }
            try {
                session.start()
                val snapshot = _activeCall.value
                // Muted (or push-to-talk) before media was up: keep the mic closed.
                if (snapshot?.let { it.micMuted || it.onHold } == true) session.setMicEnabled(false)
                // Joining a group with the camera already turned off.
                if (snapshot != null && leg.peer != snapshot.state.peer && leg.kind == CallKind.Video && !snapshot.cameraOn) session.setCameraEnabled(false)
                // Someone joining while we share sees the screen too.
                val shared = screenSource?.let { session.startScreenShare(it) }
                leg.ready.complete(session)
                if (!mediaEngine.hasCamera && leg.kind == CallKind.Video) lock.withLock { sendMediaState() }
                if (leg.offerer) {
                    sendSdp(callId, leg.peer, SessionDescription.Type.TYPE_OFFER, session.createOffer())
                } else if (shared == ScreenShareResult.NeedsOffer) {
                    // As answerer our share adds a video section: offer it once the first exchange is done.
                    while (!leg.remoteDescriptionApplied) delay(100)
                    sendSdp(callId, leg.peer, SessionDescription.Type.TYPE_OFFER, session.createOffer())
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.error(TAG, "Media start failed", e)
                onLegMedia(callId, leg.peer, MediaConnectionState.Failed)
            }
        }
        earlySignals.remove(leg.peer)?.forEach { early ->
            early.sdp?.let { onRemoteSdp(callId, leg.peer, it) }
            early.ice?.let { onRemoteIce(leg.peer, it) }
        }
    }

    /** Routes a leg's media connection state: the main leg drives the state machine, member legs their own phase. */
    private suspend fun onLegMedia(callId: CallId, peer: Fingerprint, state: MediaConnectionState) = lock.withLock {
        val call = _activeCall.value ?: return@withLock
        if (call.state.callId != callId) return@withLock
        if (peer == call.state.peer) {
            when (state) {
                MediaConnectionState.Connected -> reduce(CallEvent.MediaConnected)
                MediaConnectionState.Disconnected -> reduce(CallEvent.MediaDisconnected)
                MediaConnectionState.Failed -> reduce(CallEvent.MediaFailed)
                else -> Unit
            }
        } else {
            onMemberMedia(callId, peer, state)
        }
    }

    private suspend fun sendSdp(callId: CallId, peer: Fingerprint, type: SessionDescription.Type, sdp: String) {
        send(peer, Envelope(sdp = SessionDescription(call_id = callId.value, type = type, sdp = sdp)))
    }

    private fun restartIce(callId: CallId, leg: Leg) {
        val session = leg.session ?: return
        if (!leg.offerer) return
        scope.launch {
            runCatching { sendSdp(callId, leg.peer, SessionDescription.Type.TYPE_OFFER, session.createOffer(iceRestart = true)) }
                .onFailure { logger.warn(TAG, "ICE restart failed", it) }
        }
    }

    // -- Incoming signaling -----------------------------------------------------------------------

    private suspend fun onEnvelope(from: Fingerprint, envelope: Envelope) {
        envelope.call_invite?.let { return onInvite(from, it) }
        lock.withLock {
            val call = _activeCall.value ?: return
            val waiting = call.waiting
            if (waiting != null && waiting.peer == from) {
                val cancelled = envelope.call_cancel?.call_id == waiting.callId.value || envelope.call_end?.call_id == waiting.callId.value
                val elsewhere = envelope.call_cancel?.handled_elsewhere == true
                if (cancelled) finishWaiting(waiting, if (elsewhere) EndReason.AnsweredElsewhere else EndReason.Missed)
                return
            }
            val state = call.state
            val id = state.callId.value
            val roster = envelope.call_roster
            if (roster != null) {
                if (roster.call_id == id && state.isActive) onRoster(call, from, roster)
                return
            }
            if (from in forks) {
                onForkEnvelope(call, from, envelope)
                return
            }
            if (state.peer != from) {
                onMemberEnvelope(call, from, envelope)
                return
            }
            val ringing = envelope.call_ringing
            val accept = envelope.call_accept
            val decline = envelope.call_decline
            val cancel = envelope.call_cancel
            val end = envelope.call_end
            val mediaState = envelope.media_state
            val sdp = envelope.sdp
            val ice = envelope.ice
            val upgrade = envelope.call_upgrade
            val floor = envelope.ptt_floor
            when {
                floor != null && floor.call_id == id -> onFloor(call, from, floor)
                ringing != null && ringing.call_id == id -> reduce(CallEvent.RemoteRinging)
                accept != null && accept.call_id == id -> {
                    cancelForks(state.callId)
                    reduce(CallEvent.RemoteAccept(accept.accepted_kind.toModel()))
                }
                // Busy or unavailable on this device: keep ringing their other linked devices.
                decline != null && decline.call_id == id && forks.isNotEmpty() && decline.reason != CallDecline.Reason.REASON_DECLINED ->
                    switchPeer(forks.first())
                decline != null && decline.call_id == id -> {
                    cancelForks(state.callId)
                    decline.quick_reply.sanitizedReply()?.let { reply -> _activeCall.value = call.copy(remoteQuickReply = reply) }
                    reduce(CallEvent.RemoteDecline(decline.reason.toModel()))
                }
                cancel != null && cancel.call_id == id ->
                    reduce(if (cancel.handled_elsewhere && state.phase == CallPhase.IncomingRinging) CallEvent.HandledElsewhere else CallEvent.RemoteCancel)
                end != null && end.call_id == id -> reduce(CallEvent.RemoteEnd(end.reason.toModel()))
                mediaState != null && mediaState.call_id == id -> _activeCall.value = call.copy(
                    remoteMicMuted = mediaState.mic_muted,
                    remoteCameraOn = mediaState.camera_on,
                    remoteOnHold = mediaState.on_hold,
                    remoteScreenSharing = mediaState.screen_sharing,
                    remoteRecording = mediaState.recording,
                )
                sdp != null && sdp.call_id == id -> if (state.phase in MEDIA_PHASES) onRemoteSdp(state.callId, from, sdp)
                ice != null && ice.call_id == id -> onRemoteIce(from, ice)
                upgrade != null && upgrade.call_id == id -> onUpgrade(call, upgrade)
            }
        }
    }

    /** Must be called with [lock] held. A reply from one of the callee's other linked devices. */
    private suspend fun onForkEnvelope(call: ActiveCall, from: Fingerprint, envelope: Envelope) {
        val state = call.state
        val id = state.callId.value
        val accept = envelope.call_accept?.takeIf { it.call_id == id }
        val decline = envelope.call_decline?.takeIf { it.call_id == id }
        when {
            envelope.call_ringing?.call_id == id -> if (state.phase == CallPhase.OutgoingInviting) reduce(CallEvent.RemoteRinging)
            accept != null -> {
                takeCallOn(from, state.callId)
                reduce(CallEvent.RemoteAccept(accept.accepted_kind.toModel()))
            }
            // Declining on one device declines for the person; busy or unavailable only drops that device.
            decline != null && decline.reason == CallDecline.Reason.REASON_DECLINED -> {
                takeCallOn(from, state.callId)
                decline.quick_reply.sanitizedReply()?.let { reply -> _activeCall.update { it?.copy(remoteQuickReply = reply) } }
                reduce(CallEvent.RemoteDecline(decline.reason.toModel()))
            }
            decline != null -> forks.remove(from)
        }
    }

    /** Must be called with [lock] held. [device] answered: every other ringing device stops. */
    private suspend fun takeCallOn(device: Fingerprint, callId: CallId) {
        val previous = _activeCall.value?.state?.peer ?: return
        forks.remove(device)
        switchPeer(device)
        forks.add(previous)
        cancelForks(callId)
    }

    /** Must be called with [lock] held. Tells the callee's other devices the call was handled on one of them. */
    private suspend fun cancelForks(callId: CallId) {
        val others = forks.toList()
        forks.clear()
        others.forEach { send(it, Envelope(call_cancel = CallCancel(call_id = callId.value, handled_elsewhere = true))) }
    }

    /** Must be called with [lock] held. The outgoing call now goes to [device], one of the callee's linked devices. */
    private fun switchPeer(device: Fingerprint) {
        forks.remove(device)
        _activeCall.update { it?.copy(state = it.state.copy(peer = device)) }
    }

    private suspend fun onInvite(from: Fingerprint, invite: CallInvite) {
        val remoteId = invite.call_id.takeIf { it.length in 32..40 }?.let(::CallId) ?: return
        val remoteKind = invite.kind.toModel()
        lock.withLock {
            val call = _activeCall.value
            val current = call?.state
            if (current != null && current.isActive) {
                val collision = current.peer == from && current.direction == CallDirection.Outgoing &&
                    current.phase in setOf(CallPhase.OutgoingInviting, CallPhase.OutgoingRinging)
                if (collision) {
                    if (CallStateMachine.remoteInviteWinsCollision(current.callId, remoteId)) {
                        // Both people meant to call: drop ours silently and auto-accept theirs.
                        logger.info(TAG, "Call collision: accepting remote invite")
                        timers.values.forEach { it.cancel() }
                        timers.clear()
                        if (invite.push_to_talk) {
                            val incoming = CallStateMachine.incomingPushToTalk(remoteId, from, now())
                            install(incoming.state)
                            runEffects(incoming)
                        } else {
                            val kind = if (current.requestedKind == CallKind.Video && remoteKind == CallKind.Video) CallKind.Video else CallKind.Audio
                            val incoming = CallStateMachine.incoming(remoteId, from, remoteKind, now())
                            install(incoming.state)
                            runEffects(incoming)
                            reduce(CallEvent.Accept(kind))
                        }
                    }
                    return
                }
                if (invite.push_to_talk || invite.intercom) {
                    send(from, Envelope(call_decline = CallDecline(call_id = remoteId.value, reason = CallDecline.Reason.REASON_BUSY)))
                    return
                }
                val canWait = current.phase in MEDIA_PHASES && current.peer != from && call.waiting == null &&
                    call.members.none { it.fingerprint == from }
                if (!canWait) {
                    send(from, Envelope(call_decline = CallDecline(call_id = remoteId.value, reason = CallDecline.Reason.REASON_BUSY)))
                    return
                }
            }
            if (invite.push_to_talk || invite.intercom) {
                // Never rings: connect straight away when allowed, otherwise refuse (no missed call).
                val decision = if (invite.push_to_talk) pttPolicy.decide(from) else intercomPolicy.decide(from)
                val reason = when (decision) {
                    IncomingDecision.Ring -> null
                    IncomingDecision.DoNotDisturb -> CallDecline.Reason.REASON_DND
                    IncomingDecision.NotAllowed -> CallDecline.Reason.REASON_NOT_ALLOWED
                }
                if (reason != null) {
                    send(from, Envelope(call_decline = CallDecline(call_id = remoteId.value, reason = reason)))
                } else {
                    val transition = if (invite.push_to_talk) {
                        CallStateMachine.incomingPushToTalk(remoteId, from, now())
                    } else {
                        CallStateMachine.incomingIntercom(remoteId, from, now())
                    }
                    install(transition.state)
                    runEffects(transition)
                }
                return
            }
            when (policy.decide(from)) {
                IncomingDecision.NotAllowed -> {
                    send(from, Envelope(call_decline = CallDecline(call_id = remoteId.value, reason = CallDecline.Reason.REASON_NOT_ALLOWED)))
                    return
                }
                IncomingDecision.DoNotDisturb -> {
                    send(from, Envelope(call_decline = CallDecline(call_id = remoteId.value, reason = CallDecline.Reason.REASON_DND)))
                    _finishedCalls.tryEmit(
                        CallState(remoteId, from, CallDirection.Incoming, remoteKind, remoteKind, CallPhase.Ended, now(), endedAtMs = now(), endReason = EndReason.Missed),
                    )
                    return
                }
                IncomingDecision.Ring -> Unit
            }
            if (current != null && current.isActive && call != null) {
                // Call waiting (edge case C2): ring softly in the current call instead of answering BUSY.
                val waiting = WaitingCall(remoteId, from, remoteKind, now())
                _activeCall.value = call.copy(waiting = waiting)
                send(from, Envelope(call_ringing = CallRinging(call_id = remoteId.value)))
                waitingTimer?.cancel()
                waitingTimer = scope.launch {
                    delay(ProtocolConstants.RING_TIMEOUT_MS)
                    lock.withLock {
                        if (_activeCall.value?.waiting?.callId == remoteId) {
                            send(from, Envelope(call_decline = CallDecline(call_id = remoteId.value, reason = CallDecline.Reason.REASON_BUSY)))
                            finishWaiting(waiting, EndReason.Missed)
                        }
                    }
                }
                return
            }
            val transition = CallStateMachine.incoming(remoteId, from, remoteKind, now())
            install(transition.state)
            runEffects(transition)
        }
    }

    private fun onRemoteSdp(callId: CallId, from: Fingerprint, sdp: SessionDescription) {
        if (sdp.sdp.length > MAX_SDP) return
        val leg = legs[from] ?: return
        scope.launch {
            try {
                // The offer can arrive before local capture has started; wait for it.
                val session = withTimeout(ProtocolConstants.MEDIA_SETUP_TIMEOUT_MS) { leg.ready.await() }
                when (sdp.type) {
                    SessionDescription.Type.TYPE_OFFER -> {
                        // The first offer always comes from the leg's offerer. Later offers (ICE restart,
                        // video upgrade, a screen share adding video) may come from either side.
                        if (leg.offerer && !leg.remoteDescriptionApplied) return@launch
                        // Answer a video renegotiation only once our own video is added, or we'd answer receive-only.
                        upgradeJob?.takeIf { from == _activeCall.value?.state?.peer }?.join()
                        val answer = session.acceptOffer(sdp.sdp)
                        onRemoteDescriptionApplied(leg, session)
                        sendSdp(callId, from, SessionDescription.Type.TYPE_ANSWER, answer)
                    }
                    SessionDescription.Type.TYPE_ANSWER -> {
                        session.applyAnswer(sdp.sdp)
                        onRemoteDescriptionApplied(leg, session)
                    }
                    else -> Unit
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (leg.remoteDescriptionApplied) {
                    // Media already flows: a failed renegotiation (e.g. both sides offering at once) mustn't end the call.
                    logger.warn(TAG, "Renegotiation failed", e)
                } else {
                    logger.error(TAG, "Applying remote SDP failed", e)
                    onLegMedia(callId, from, MediaConnectionState.Failed)
                }
            }
        }
    }

    private suspend fun onRemoteDescriptionApplied(leg: Leg, session: MediaSession) {
        val queued = lock.withLock {
            leg.remoteDescriptionApplied = true
            leg.pendingCandidates.toList().also { leg.pendingCandidates.clear() }
        }
        queued.forEach(session::addRemoteCandidate)
    }

    /** Must be called with [lock] held. */
    private fun onRemoteIce(from: Fingerprint, ice: IceCandidate) {
        if (ice.end_of_candidates || !IcePolicy.isAllowed(ice.candidate)) return
        val leg = legs[from] ?: return
        val candidate = IceCandidateData(ice.candidate, ice.sdp_mid, ice.sdp_mline_index)
        if (leg.remoteDescriptionApplied) {
            leg.session?.addRemoteCandidate(candidate)
        } else if (leg.pendingCandidates.size < MAX_PENDING_CANDIDATES) {
            leg.pendingCandidates += candidate
        }
    }

    private suspend fun onLink(peer: Fingerprint, lost: Boolean) = lock.withLock {
        val call = _activeCall.value ?: return@withLock
        val waiting = call.waiting
        if (lost && waiting?.peer == peer) finishWaiting(waiting, EndReason.Missed)
        val state = call.state
        if (lost && peer in forks) {
            forks.remove(peer)
            return@withLock
        }
        // The ringing device left, but another of their linked devices is still ringing.
        if (lost && state.peer == peer && forks.isNotEmpty() && state.phase in setOf(CallPhase.OutgoingInviting, CallPhase.OutgoingRinging)) {
            switchPeer(forks.first())
            return@withLock
        }
        if (state.peer == peer && state.isActive) {
            reduce(if (lost) CallEvent.LinkLost else CallEvent.LinkRestored)
            return@withLock
        }
        // An invitation can't be answered without the control link.
        val member = call.members.firstOrNull { it.fingerprint == peer }
        if (lost && member != null && (member.phase == MemberPhase.Invited || member.phase == MemberPhase.Ringing)) {
            dropMember(peer, CallNotice.AddNoAnswer)
        }
    }

    private companion object {
        const val TAG = "Call"
        const val MAX_SDP = 32_000
        const val MAX_PENDING_CANDIDATES = 64
        const val UPGRADE_TIMEOUT_MS = 30_000L
        const val NOTICE_MS = 4_000L
        const val PTT_MAX_TALK_MS = 60_000L
        const val MAX_QUICK_REPLY = 200

        /** ADR-007: full mesh, audio ≤ 8, video ≤ 4 people including yourself. */
        const val MAX_GROUP_AUDIO = 8
        const val MAX_GROUP_VIDEO = 4
        val MEDIA_PHASES = setOf(CallPhase.Connecting, CallPhase.Connected, CallPhase.Reconnecting)
        val MEMBER_MEDIA_PHASES = setOf(MemberPhase.Connecting, MemberPhase.Connected, MemberPhase.Reconnecting)

        /** Untrusted text: no control or bidi-override characters, bounded length, null when empty. */
        fun String.sanitizedReply(): String? =
            filterNot { it.isISOControl() || it in '‪'..'‮' || it in '⁦'..'⁩' }.trim().take(MAX_QUICK_REPLY).ifEmpty { null }
    }
}

private fun EndReason.toEndProto(): CallEnd.Reason = when (this) {
    EndReason.FailedNetwork -> CallEnd.Reason.REASON_FAILED_NETWORK
    EndReason.FailedMedia -> CallEnd.Reason.REASON_FAILED_MEDIA
    else -> CallEnd.Reason.REASON_COMPLETED
}

private fun CallEnd.Reason.toModel(): EndReason = when (this) {
    CallEnd.Reason.REASON_FAILED_NETWORK -> EndReason.FailedNetwork
    CallEnd.Reason.REASON_FAILED_MEDIA -> EndReason.FailedMedia
    else -> EndReason.Completed
}

private fun CallDecline.Reason.toModel(): EndReason = when (this) {
    CallDecline.Reason.REASON_BUSY -> EndReason.Busy
    CallDecline.Reason.REASON_DND -> EndReason.DoNotDisturb
    CallDecline.Reason.REASON_NOT_ALLOWED -> EndReason.NotAllowed
    else -> EndReason.Declined
}
