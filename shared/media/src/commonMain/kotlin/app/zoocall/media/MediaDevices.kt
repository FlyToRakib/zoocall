package app.zoocall.media

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class DeviceKind { Microphone, Speaker, Camera }

/** [id] is the OS descriptor; [name] is shown to people and used to find the device again if the descriptor changes. */
data class MediaDeviceInfo(val id: String, val name: String)

/** The person's choice per kind. A null id means "System default". */
data class DeviceSelection(
    val microphone: MediaDeviceInfo? = null,
    val speaker: MediaDeviceInfo? = null,
    val camera: MediaDeviceInfo? = null,
) {
    operator fun get(kind: DeviceKind): MediaDeviceInfo? = when (kind) {
        DeviceKind.Microphone -> microphone
        DeviceKind.Speaker -> speaker
        DeviceKind.Camera -> camera
    }

    fun with(kind: DeviceKind, device: MediaDeviceInfo?): DeviceSelection = when (kind) {
        DeviceKind.Microphone -> copy(microphone = device)
        DeviceKind.Speaker -> copy(speaker = device)
        DeviceKind.Camera -> copy(camera = device)
    }
}

/** Something the person should know about a chosen device. */
sealed interface DeviceNotice {
    val kind: DeviceKind
    val deviceName: String

    /** The chosen device went away; the system default is used until it comes back. */
    data class Unavailable(override val kind: DeviceKind, override val deviceName: String) : DeviceNotice

    /** The chosen device is back and in use again. */
    data class Restored(override val kind: DeviceKind, override val deviceName: String) : DeviceNotice
}

/**
 * Microphone, speaker and camera selection (desktop). Choices are remembered, applied to calls,
 * voice messages and the ringtone, switched live during a call, and fall back to the system
 * default while a chosen device is unplugged.
 */
interface MediaDeviceManager {
    fun devices(kind: DeviceKind): StateFlow<List<MediaDeviceInfo>>

    val selection: StateFlow<DeviceSelection>

    /** Kinds whose chosen device isn't connected right now. */
    val unavailable: StateFlow<Set<DeviceKind>>

    val notices: SharedFlow<DeviceNotice>

    /** Name of the device the OS currently uses as default, if known. */
    fun systemDefaultName(kind: DeviceKind): String?

    /** Pass null for "System default". */
    fun select(kind: DeviceKind, device: MediaDeviceInfo?)

    /** Re-reads the device lists (also happens automatically when devices are plugged in or out). */
    fun refresh()

    /** Input level 0..1 while the microphone test runs. */
    val microphoneLevel: StateFlow<Float>

    /** Returns false when the microphone can't be opened or a call is using it. */
    fun startMicrophoneTest(): Boolean
    fun stopMicrophoneTest()

    /** Plays a short chime on the chosen speaker. Returns false when a call is using it or it can't be opened. */
    fun playTestSound(): Boolean

    /** Starts a local preview of the chosen camera, or null when it can't be opened or a call is using it. */
    fun startCameraPreview(): VideoTrackHandle?
    fun stopCameraPreview()
}
