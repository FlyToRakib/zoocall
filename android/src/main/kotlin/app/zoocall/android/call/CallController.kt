package app.zoocall.android.call

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import android.telecom.DisconnectCause
import app.zoocall.android.ForegroundTracker
import app.zoocall.android.MainActivity
import app.zoocall.android.R
import app.zoocall.android.notify.Notifications
import app.zoocall.android.service.ZoocallService
import app.zoocall.core.app.CoreStatus
import app.zoocall.core.app.ZoocallCore
import app.zoocall.core.call.ActiveCall
import app.zoocall.core.call.CallPhase
import app.zoocall.core.chat.KnockEvent
import app.zoocall.core.model.CallDirection
import app.zoocall.core.model.CallKind
import app.zoocall.core.model.EndReason
import app.zoocall.core.model.Logger
import app.zoocall.ui.platform.AudioRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Bridges the shared call state to Android: Core-Telecom, the call activity, notifications,
 * ringing, the foreground service, audio routing and the proximity sensor.
 */
class CallController(
    private val context: Context,
    private val core: ZoocallCore,
    private val scope: CoroutineScope,
    private val foreground: ForegroundTracker,
) {
    private val logger = Logger.current
    private val ringer = Ringer(context)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var proximityLock: PowerManager.WakeLock? = null

    private val callsManager: CallsManager? by lazy {
        runCatching {
            CallsManager(context).apply {
                registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING)
            }
        }.onFailure { logger.warn(TAG, "Telecom unavailable, using AudioManager fallback", it) }.getOrNull()
    }
    private var telecomJob: Job? = null
    private var telecom: CallControlScope? = null
    private var endpoints: List<CallEndpointCompat> = emptyList()

    private val _audioRoutes = MutableStateFlow<List<AudioRoute>>(emptyList())
    val audioRoutes: StateFlow<List<AudioRoute>> = _audioRoutes.asStateFlow()
    private val _currentAudioRoute = MutableStateFlow<AudioRoute?>(null)
    val currentAudioRoute: StateFlow<AudioRoute?> = _currentAudioRoute.asStateFlow()

    private var lastCallId: String? = null
    private var lastPhase: CallPhase? = null

    fun start() {
        scope.launch {
            core.activeCall
                .distinctUntilChanged { a, b -> a?.state?.callId == b?.state?.callId && a?.state?.phase == b?.state?.phase && a?.state?.kind == b?.state?.kind }
                .collect { onCall(it) }
        }
        scope.launch {
            // A second call is waiting: a soft in-call tone instead of the ringtone (edge case C2).
            core.activeCall.map { it?.waiting != null }.distinctUntilChanged().collect { waiting ->
                if (waiting && core.settings.value.callSounds) ringer.startCallWaiting() else ringer.stopCallWaiting()
            }
        }
        scope.launch {
            core.finishedCalls.collect { call ->
                if (call.direction == CallDirection.Incoming && call.endReason == EndReason.Missed) {
                    Notifications.showMissedCall(context, nameOf(call.peer.hex), call.peer.hex)
                }
            }
        }
        scope.launch {
            core.incomingMessages.collect { message ->
                if (message.isAnnouncement) {
                    val text = if (message.isVoice) context.getString(R.string.notify_voice_announcement) else message.text
                    Notifications.showAnnouncement(context, nameOf(message.from.hex), message.from.hex, text, message.quiet)
                } else if (!foreground.isForeground) {
                    val text = if (message.isVoice) context.getString(R.string.notify_voice_message) else message.text
                    val groupId = message.groupId
                    if (groupId != null) {
                        Notifications.showMessage(context, nameOf(message.from.hex), MainActivity.GROUP_TARGET_PREFIX + groupId, text, groupName = message.groupName)
                    } else {
                        Notifications.showMessage(context, nameOf(message.from.hex), message.from.hex, text)
                    }
                }
            }
        }
        scope.launch {
            // Push-to-talk chirps when either side starts or stops talking.
            var last: Pair<Boolean, Boolean>? = null
            core.activeCall
                .map { call -> call?.takeIf { it.state.pushToTalk }?.let { it.talking to it.remoteTalking } }
                .distinctUntilChanged()
                .collect { current ->
                    val before = last
                    last = current
                    if (current == null || before == null) return@collect
                    if (current.first != before.first) {
                        ringer.playPttChirp(start = current.first)
                    } else if (current.second != before.second) {
                        ringer.playPttChirp(start = current.second)
                    }
                }
        }
        scope.launch {
            // Screen sharing ended (from the call screen, the system, or the call ending): drop the capture type.
            var wasSharing = false
            core.activeCall.map { it?.screenSharing == true }.distinctUntilChanged().collect { sharing ->
                if (wasSharing && !sharing && core.activeCall.value?.state?.isActive == true) ZoocallService.stopScreenShare(context)
                wasSharing = sharing
            }
        }
        scope.launch {
            // Recording starts with a chime on every device in the call (docs/04: recording consent).
            core.activeCall
                .map { call -> call != null && (call.recording || call.remoteRecording || call.members.any { it.recording }) }
                .distinctUntilChanged()
                .collect { on -> if (on) ringer.playIntercomChime() }
        }
        scope.launch {
            // A desk intercom line connects without ringing, so both ends hear a chime when it opens.
            core.activeCall
                .map { call -> call?.state?.let { it.intercom && it.phase == app.zoocall.core.call.CallPhase.Connected } == true }
                .distinctUntilChanged()
                .collect { live -> if (live) ringer.playIntercomChime() }
        }
        scope.launch {
            // In the foreground the app shows its own knock card.
            core.knocks.collect { event ->
                if (foreground.isForeground) return@collect
                val fpHex = event.from.hex
                when (event) {
                    is KnockEvent.Received -> Notifications.showKnock(context, nameOf(fpHex), fpHex, event.id, event.text, event.quiet)
                    is KnockEvent.Replied -> Notifications.showKnockReply(context, nameOf(fpHex), fpHex, event.reply)
                }
            }
        }
        scope.launch {
            core.status.map { it == CoreStatus.Running }.distinctUntilChanged().collect { running ->
                if (running && foreground.isForeground) ZoocallService.startReachable(context)
            }
        }
    }

    /** Called when an activity becomes visible: a safe moment to (re)start the foreground service. */
    fun onAppVisible() {
        if (core.status.value == CoreStatus.Running && core.activeCall.value?.state?.isActive != true) {
            ZoocallService.startReachable(context)
        }
    }

    private fun nameOf(fpHex: String): String =
        core.person(fpHex)?.displayName ?: context.getString(R.string.notify_unknown_person)

    private fun onCall(call: ActiveCall?) {
        val state = call?.state
        val isNewCall = state != null && state.callId.value != lastCallId
        val previousPhase = if (isNewCall) null else lastPhase
        lastCallId = state?.callId?.value ?: lastCallId
        lastPhase = state?.phase

        if (state == null) {
            stopEverything()
            return
        }
        val name = nameOf(state.peer.hex)
        val video = state.kind == CallKind.Video

        if (isNewCall) startTelecom(call, name)

        when (state.phase) {
            CallPhase.IncomingRinging -> {
                val settings = core.settings.value
                ringer.startRinging(settings.ringtone, vibrate = settings.vibrateOnRing, flash = settings.flashAlert)
                Notifications.showIncomingCall(context, name, video)
                if (foreground.isForeground) launchCallScreen()
            }
            CallPhase.OutgoingInviting -> launchCallScreen()
            CallPhase.OutgoingRinging -> ringer.startRingback()
            CallPhase.Connecting -> {
                ringer.stop()
                Notifications.cancelIncomingCall(context)
                ZoocallService.enterCall(context, name, video)
                if (state.direction == CallDirection.Incoming) telecomAnswer(video)
                // Push-to-talk plays on the loudspeaker, like a walkie-talkie.
                if (telecom == null) fallbackAudioMode(video || state.pushToTalk)
                // An incoming push-to-talk session never rings, so show it right away.
                if (state.pushToTalk && isNewCall && state.direction == CallDirection.Incoming && foreground.isForeground) launchCallScreen()
            }
            CallPhase.Connected -> {
                ringer.stop()
                // Also re-emitted when a call switches to video; the tone is only for the first connect.
                if (previousPhase != CallPhase.Reconnecting && previousPhase != CallPhase.Connected && core.settings.value.callSounds) ringer.playConnected()
                scope.launch { telecom?.setActive() }
                ZoocallService.enterCall(context, name, video, connectedAtMs = state.connectedAtMs)
                updateProximity(video || state.pushToTalk)
            }
            CallPhase.Reconnecting -> Unit
            CallPhase.Ended -> {
                ringer.stop()
                if ((previousPhase == CallPhase.Connected || previousPhase == CallPhase.Reconnecting) && core.settings.value.callSounds) ringer.playEnded()
                Notifications.cancelIncomingCall(context)
                telecomDisconnect(state.endReason)
                releaseAudio()
                ZoocallService.exitCall(context)
            }
        }
    }

    private fun launchCallScreen() {
        runCatching {
            context.startActivity(Intent(context, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { logger.warn(TAG, "Couldn't open call screen", it) }
    }

    // -- Telecom ----------------------------------------------------------------------------------

    private fun startTelecom(call: ActiveCall, name: String) {
        val manager = callsManager ?: return
        telecomJob?.cancel()
        val state = call.state
        val attributes = CallAttributesCompat(
            displayName = name,
            address = Uri.fromParts("zoocall", state.peer.hex, null),
            direction = if (state.direction == CallDirection.Incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
            callType = if (state.kind == CallKind.Video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
        )
        telecomJob = scope.launch {
            try {
                manager.addCall(
                    attributes,
                    onAnswer = { type ->
                        core.acceptCall(if (type == CallAttributesCompat.CALL_TYPE_VIDEO_CALL) CallKind.Video else CallKind.Audio)
                    },
                    onDisconnect = {
                        val phase = core.activeCall.value?.state?.phase
                        if (phase == CallPhase.IncomingRinging) core.declineCall() else if (phase != CallPhase.Ended) core.hangUp()
                    },
                    // The system holds us for a cellular call (edge case C4) and resumes us afterwards.
                    onSetActive = { core.setOnHold(false) },
                    onSetInactive = { core.setOnHold(true) },
                ) {
                    telecom = this
                    var routedForPtt = false
                    launch {
                        availableEndpoints.collect { list ->
                            endpoints = list
                            _audioRoutes.value = list.map(::toRoute)
                            // Push-to-talk uses the loudspeaker unless a headset is connected.
                            if (state.pushToTalk && !routedForPtt) {
                                routedForPtt = true
                                val headset = list.any { it.type == CallEndpointCompat.TYPE_BLUETOOTH || it.type == CallEndpointCompat.TYPE_WIRED_HEADSET }
                                val speaker = list.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
                                if (!headset && speaker != null) runCatching { requestEndpointChange(speaker) }
                            }
                        }
                    }
                    launch { currentCallEndpoint.collect { _currentAudioRoute.value = toRoute(it) } }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.warn(TAG, "Telecom addCall failed", e)
                telecom = null
            }
        }
    }

    private fun telecomAnswer(video: Boolean) {
        val scopeRef = telecom ?: return
        scope.launch {
            runCatching {
                scopeRef.answer(if (video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
            }
        }
    }

    private fun telecomDisconnect(reason: EndReason?) {
        val scopeRef = telecom
        val job = telecomJob
        telecom = null
        telecomJob = null
        if (scopeRef == null) {
            job?.cancel()
            return
        }
        val cause = when (reason) {
            EndReason.Missed -> DisconnectCause.MISSED
            EndReason.Declined -> DisconnectCause.REJECTED
            EndReason.Busy -> DisconnectCause.BUSY
            EndReason.Cancelled -> DisconnectCause.CANCELED
            EndReason.FailedMedia, EndReason.FailedNetwork, EndReason.Unreachable -> DisconnectCause.ERROR
            EndReason.NotAllowed, EndReason.DoNotDisturb -> DisconnectCause.REJECTED
            EndReason.AnsweredElsewhere -> DisconnectCause.ANSWERED_ELSEWHERE
            EndReason.Completed, null -> DisconnectCause.LOCAL
        }
        scope.launch {
            runCatching { scopeRef.disconnect(DisconnectCause(cause)) }
            job?.cancel()
        }
    }

    fun selectAudioRoute(route: AudioRoute) {
        val scopeRef = telecom
        if (scopeRef != null) {
            val endpoint = endpoints.firstOrNull { it.identifier.toString() == route.id } ?: return
            scope.launch { runCatching { scopeRef.requestEndpointChange(endpoint) } }
            return
        }
        // AudioManager fallback: only earpiece ↔ speaker.
        setSpeaker(route.id == ROUTE_SPEAKER)
    }

    private fun toRoute(endpoint: CallEndpointCompat): AudioRoute = AudioRoute(
        id = endpoint.identifier.toString(),
        label = when (endpoint.type) {
            CallEndpointCompat.TYPE_EARPIECE -> context.getString(R.string.audio_route_earpiece)
            CallEndpointCompat.TYPE_SPEAKER -> context.getString(R.string.audio_route_speaker)
            CallEndpointCompat.TYPE_WIRED_HEADSET -> context.getString(R.string.audio_route_wired)
            CallEndpointCompat.TYPE_BLUETOOTH -> endpoint.name.toString().ifBlank { context.getString(R.string.audio_route_bluetooth) }
            else -> endpoint.name.toString()
        },
        icon = when (endpoint.type) {
            CallEndpointCompat.TYPE_EARPIECE -> Icons.Rounded.PhoneInTalk
            CallEndpointCompat.TYPE_SPEAKER -> Icons.AutoMirrored.Rounded.VolumeUp
            CallEndpointCompat.TYPE_WIRED_HEADSET -> Icons.Rounded.Headphones
            CallEndpointCompat.TYPE_BLUETOOTH -> Icons.Rounded.Bluetooth
            else -> Icons.Rounded.Cast
        },
    )

    // -- Audio fallback (no Telecom) and proximity ------------------------------------------------

    private fun fallbackAudioMode(video: Boolean) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        _audioRoutes.value = listOf(
            AudioRoute(ROUTE_EARPIECE, context.getString(R.string.audio_route_earpiece), Icons.Rounded.PhoneInTalk),
            AudioRoute(ROUTE_SPEAKER, context.getString(R.string.audio_route_speaker), Icons.AutoMirrored.Rounded.VolumeUp),
        )
        setSpeaker(video)
    }

    private fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            if (device != null) audioManager.setCommunicationDevice(device) else audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
        _currentAudioRoute.value = _audioRoutes.value.firstOrNull { it.id == if (on) ROUTE_SPEAKER else ROUTE_EARPIECE }
        updateProximity(video = on)
    }

    /** Screen off near the ear, only for audio on the earpiece (edge case C17). */
    private fun updateProximity(video: Boolean) {
        val onEarpiece = _currentAudioRoute.value?.let { it.icon == Icons.Rounded.PhoneInTalk } ?: !video
        if (!video && onEarpiece) {
            if (proximityLock == null && power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximityLock = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "zoocall:proximity").apply {
                    setReferenceCounted(false)
                    acquire(4 * 60 * 60 * 1000L)
                }
            }
        } else {
            releaseProximity()
        }
    }

    private fun releaseProximity() {
        proximityLock?.let { if (it.isHeld) it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
        proximityLock = null
    }

    private fun releaseAudio() {
        releaseProximity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
        if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) audioManager.mode = AudioManager.MODE_NORMAL
        _audioRoutes.value = emptyList()
        _currentAudioRoute.value = null
    }

    private fun stopEverything() {
        ringer.stop()
        Notifications.cancelIncomingCall(context)
        releaseAudio()
    }

    private companion object {
        const val TAG = "CallController"
        const val ROUTE_EARPIECE = "earpiece"
        const val ROUTE_SPEAKER = "speaker"
    }
}

internal fun Context.mainActivityIntent(conversationFp: String? = null) =
    Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .apply { conversationFp?.let { putExtra(MainActivity.EXTRA_CONVERSATION, it) } }
