package app.zoocall.media

import app.zoocall.core.model.Logger
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.media.Device
import dev.onvoid.webrtc.media.DeviceChangeListener
import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.audio.AudioDevice
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioSink
import dev.onvoid.webrtc.media.audio.AudioSource
import dev.onvoid.webrtc.media.video.VideoCaptureCapability
import dev.onvoid.webrtc.media.video.VideoDevice
import dev.onvoid.webrtc.media.video.VideoDeviceSource
import dev.onvoid.webrtc.media.video.VideoTrack
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * webrtc-java device selection. The call audio device module ([adm]) plays and records calls;
 * separate short-lived modules run the microphone and speaker tests so they never touch a call.
 */
class DesktopDeviceManager internal constructor(
    private val logger: Logger,
    private val preferencesFile: File?,
    private val adm: () -> AudioDeviceModule,
    private val factory: () -> PeerConnectionFactory,
    /** A call has started its media: audio devices must be restarted to switch. */
    private val callAudioRunning: () -> Boolean,
    /** Any call session exists: the camera and the audio devices belong to it. */
    private val callActive: () -> Boolean,
    private val onCameraChanged: (VideoDevice?) -> Unit,
) : MediaDeviceManager {

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "zoocall-devices").apply { isDaemon = true } }
    private val lock = Any()

    private val lists = DeviceKind.entries.associateWith { MutableStateFlow<List<MediaDeviceInfo>>(emptyList()) }
    private var recordingDevices: List<AudioDevice> = emptyList()
    private var playoutDevices: List<AudioDevice> = emptyList()
    private var videoDevices: List<VideoDevice> = emptyList()
    private var appliedRecording: String? = null
    private var appliedPlayout: String? = null

    private val _selection = MutableStateFlow(load())
    override val selection: StateFlow<DeviceSelection> = _selection.asStateFlow()

    private val _unavailable = MutableStateFlow<Set<DeviceKind>>(emptySet())
    override val unavailable: StateFlow<Set<DeviceKind>> = _unavailable.asStateFlow()

    private val _notices = MutableSharedFlow<DeviceNotice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val notices: SharedFlow<DeviceNotice> = _notices.asSharedFlow()

    private val _level = MutableStateFlow(0f)
    override val microphoneLevel: StateFlow<Float> = _level.asStateFlow()

    private var micTest: AudioDeviceModule? = null
    private var speakerTest: AudioDeviceModule? = null
    private var previewSource: VideoDeviceSource? = null
    private var previewTrack: VideoTrack? = null
    private var previewFrames: FrameTrack? = null

    private val changeListener = object : DeviceChangeListener {
        override fun deviceConnected(device: Device) = scheduleRefresh()
        override fun deviceDisconnected(device: Device) = scheduleRefresh()
    }

    init {
        runCatching { MediaDevices.addDeviceChangeListener(changeListener) }
            .onFailure { logger.warn(TAG, "Device change notifications unavailable", it) }
        refresh()
    }

    override fun devices(kind: DeviceKind): StateFlow<List<MediaDeviceInfo>> = lists.getValue(kind).asStateFlow()

    override fun systemDefaultName(kind: DeviceKind): String? = runCatching {
        when (kind) {
            DeviceKind.Microphone -> MediaDevices.getDefaultAudioCaptureDevice()?.name
            DeviceKind.Speaker -> MediaDevices.getDefaultAudioRenderDevice()?.name
            DeviceKind.Camera -> videoDevices.firstOrNull()?.name
        }
    }.getOrNull()

    override fun select(kind: DeviceKind, device: MediaDeviceInfo?) {
        synchronized(lock) {
            _selection.update { it.with(kind, device) }
            save(_selection.value)
            updateAvailability(emitNotices = false)
            applyChoice(kind)
        }
        logger.info(TAG, "$kind set to ${device?.name ?: "system default"}")
    }

    override fun refresh() {
        synchronized(lock) {
            recordingDevices = runCatching { adm().recordingDevices }.recoverCatching { MediaDevices.getAudioCaptureDevices() }.getOrDefault(emptyList())
            playoutDevices = runCatching { adm().playoutDevices }.recoverCatching { MediaDevices.getAudioRenderDevices() }.getOrDefault(emptyList())
            videoDevices = runCatching { MediaDevices.getVideoCaptureDevices() }.getOrDefault(emptyList())
            lists.getValue(DeviceKind.Microphone).value = recordingDevices.map { it.info() }
            lists.getValue(DeviceKind.Speaker).value = playoutDevices.map { it.info() }
            lists.getValue(DeviceKind.Camera).value = videoDevices.map { it.info() }
            val before = _unavailable.value
            updateAvailability(emitNotices = true)
            // Re-apply audio: the chosen device may have returned, gone away, or the OS default changed.
            applyChoice(DeviceKind.Microphone)
            applyChoice(DeviceKind.Speaker)
            if (DeviceKind.Camera in before != DeviceKind.Camera in _unavailable.value) applyChoice(DeviceKind.Camera)
        }
    }

    private fun scheduleRefresh() {
        // Callbacks arrive on a native thread, often several per plug event: debounce and leave the native call first.
        runCatching { worker.schedule({ runCatching { refresh() } }, 400, TimeUnit.MILLISECONDS) }
    }

    /** The name of the device actually in use: the chosen one when connected, otherwise null (system default). */
    fun activeName(kind: DeviceKind): String? = synchronized(lock) {
        when (kind) {
            DeviceKind.Microphone -> resolve(recordingDevices, _selection.value.microphone)?.name
            DeviceKind.Speaker -> resolve(playoutDevices, _selection.value.speaker)?.name
            DeviceKind.Camera -> resolve(videoDevices, _selection.value.camera)?.name
        }
    }

    /** The camera a call should use: the chosen one when connected, otherwise the first one. */
    fun cameraDevice(): VideoDevice? = synchronized(lock) {
        resolve(videoDevices, _selection.value.camera) ?: videoDevices.firstOrNull()
    }

    fun allCameras(): List<VideoDevice> = synchronized(lock) { videoDevices }

    /** Before a call starts: point the call audio module at the chosen devices and free test devices. */
    internal fun prepareForCall() {
        stopMicrophoneTest()
        stopCameraPreview()
        synchronized(lock) {
            appliedRecording = null
            appliedPlayout = null
            applyChoice(DeviceKind.Microphone)
            applyChoice(DeviceKind.Speaker)
        }
    }

    // -- Applying ---------------------------------------------------------------------------------

    private fun applyChoice(kind: DeviceKind) {
        when (kind) {
            DeviceKind.Microphone -> {
                val target = resolve(recordingDevices, _selection.value.microphone)
                    ?: runCatching { MediaDevices.getDefaultAudioCaptureDevice() }.getOrNull()
                    ?: recordingDevices.firstOrNull()
                    ?: return
                if (target.descriptor == appliedRecording) return
                val running = callAudioRunning()
                runCatching {
                    val module = adm()
                    if (running) module.stopRecording()
                    module.setRecordingDevice(target)
                    if (running) {
                        module.initRecording()
                        module.startRecording()
                    }
                    appliedRecording = target.descriptor
                }.onFailure { logger.warn(TAG, "Couldn't switch microphone", it) }
            }
            DeviceKind.Speaker -> {
                val target = resolve(playoutDevices, _selection.value.speaker)
                    ?: runCatching { MediaDevices.getDefaultAudioRenderDevice() }.getOrNull()
                    ?: playoutDevices.firstOrNull()
                    ?: return
                if (target.descriptor == appliedPlayout) return
                val running = callAudioRunning()
                runCatching {
                    val module = adm()
                    if (running) module.stopPlayout()
                    module.setPlayoutDevice(target)
                    if (running) {
                        module.initPlayout()
                        module.startPlayout()
                    }
                    appliedPlayout = target.descriptor
                }.onFailure { logger.warn(TAG, "Couldn't switch speaker", it) }
            }
            DeviceKind.Camera -> {
                val camera = resolve(videoDevices, _selection.value.camera) ?: videoDevices.firstOrNull()
                if (callActive()) onCameraChanged(camera) else previewSource?.let { restartPreview(camera) }
            }
        }
    }

    /** After the last call ends: make sure the call module released the microphone and speaker. */
    internal fun onCallsEnded() {
        runCatching {
            adm().stopRecording()
            adm().stopPlayout()
        }
    }

    private fun updateAvailability(emitNotices: Boolean) {
        val selection = _selection.value
        val missing = buildSet {
            if (selection.microphone != null && resolve(recordingDevices, selection.microphone) == null) add(DeviceKind.Microphone)
            if (selection.speaker != null && resolve(playoutDevices, selection.speaker) == null) add(DeviceKind.Speaker)
            if (selection.camera != null && resolve(videoDevices, selection.camera) == null) add(DeviceKind.Camera)
        }
        val previous = _unavailable.value
        _unavailable.value = missing
        if (!emitNotices) return
        (missing - previous).forEach { kind -> selection[kind]?.let { _notices.tryEmit(DeviceNotice.Unavailable(kind, it.name)) } }
        (previous - missing).forEach { kind -> selection[kind]?.let { _notices.tryEmit(DeviceNotice.Restored(kind, it.name)) } }
    }

    /** Descriptor first; the name as fallback because some drivers change descriptors across reboots. */
    private fun <T : Device> resolve(devices: List<T>, choice: MediaDeviceInfo?): T? {
        choice ?: return null
        return devices.firstOrNull { it.descriptor == choice.id } ?: devices.firstOrNull { it.name == choice.name }
    }

    // -- Tests --------------------------------------------------------------------------------------

    override fun startMicrophoneTest(): Boolean = synchronized(lock) {
        if (micTest != null) return true
        if (callActive()) return false
        val device = resolve(recordingDevices, _selection.value.microphone)
            ?: runCatching { MediaDevices.getDefaultAudioCaptureDevice() }.getOrNull()
        return runCatching {
            val module = AudioDeviceModule()
            module.setAudioSink(object : AudioSink {
                override fun onRecordedData(samples: ByteArray, frames: Int, bytesPerSample: Int, channels: Int, sampleRate: Int, delayMs: Int, drift: Int) {
                    _level.value = levelOf(samples, frames, bytesPerSample, channels)
                }
            })
            device?.let { module.setRecordingDevice(it) }
            module.initRecording()
            module.startRecording()
            micTest = module
            true
        }.getOrElse {
            logger.warn(TAG, "Microphone test failed", it)
            false
        }
    }

    override fun stopMicrophoneTest() {
        val module = synchronized(lock) { micTest.also { micTest = null } } ?: return
        runCatching {
            module.stopRecording()
            module.dispose()
        }
        _level.value = 0f
    }

    override fun playTestSound(): Boolean = synchronized(lock) {
        if (speakerTest != null) return true
        if (callActive()) return false
        val device = resolve(playoutDevices, _selection.value.speaker)
            ?: runCatching { MediaDevices.getDefaultAudioRenderDevice() }.getOrNull()
        return runCatching {
            val module = AudioDeviceModule()
            var position = 0L
            module.setAudioSource(object : AudioSource {
                override fun onPlaybackData(buffer: ByteArray, frames: Int, bytesPerSample: Int, channels: Int, sampleRate: Int): Int {
                    // 16-bit PCM. libwebrtc reports bytes per sample either per channel (2) or per frame (2 × channels).
                    val ch = if (channels in 1..8) channels else 1
                    val frameBytes = if (bytesPerSample >= 2 * ch) bytesPerSample else 2 * ch
                    val rate = if (sampleRate >= 8_000) sampleRate else 48_000
                    val count = minOf(frames.coerceAtLeast(0), buffer.size / frameBytes)
                    for (i in 0 until count) {
                        val value = (chime((position + i).toDouble() / rate) * Short.MAX_VALUE * 0.3).toInt()
                        for (c in 0 until ch) {
                            val index = i * frameBytes + c * 2
                            buffer[index] = value.toByte()
                            buffer[index + 1] = (value shr 8).toByte()
                        }
                    }
                    position += count
                    return count
                }
            })
            device?.let { module.setPlayoutDevice(it) }
            module.initPlayout()
            module.startPlayout()
            speakerTest = module
            worker.schedule({
                synchronized(lock) { speakerTest = null }
                runCatching {
                    module.stopPlayout()
                    module.dispose()
                }
            }, CHIME_MS + 150, TimeUnit.MILLISECONDS)
            true
        }.getOrElse {
            logger.warn(TAG, "Speaker test failed", it)
            false
        }
    }

    override fun startCameraPreview(): VideoTrackHandle? = synchronized(lock) {
        previewFrames?.let { return it }
        if (callActive()) return null
        val camera = resolve(videoDevices, _selection.value.camera) ?: videoDevices.firstOrNull() ?: return null
        return openPreview(camera)
    }

    override fun stopCameraPreview() {
        synchronized(lock) { closePreview() }
    }

    private fun openPreview(camera: VideoDevice): VideoTrackHandle? = runCatching {
        val source = VideoDeviceSource().apply {
            setVideoCaptureDevice(camera)
            setVideoCaptureCapability(previewCapability(camera))
            start()
        }
        val track = factory().createVideoTrack("preview", source)
        previewSource = source
        previewTrack = track
        FrameTrack(track).also { previewFrames = it }
    }.getOrElse {
        logger.warn(TAG, "Camera preview failed", it)
        closePreview()
        null
    }

    private fun restartPreview(camera: VideoDevice?) {
        closePreview()
        camera?.let { openPreview(it) }
    }

    private fun closePreview() {
        previewFrames?.dispose()
        runCatching { previewSource?.stop() }
        runCatching { previewTrack?.dispose() }
        runCatching { previewSource?.dispose() }
        previewFrames = null
        previewTrack = null
        previewSource = null
    }

    /** The preview handle the UI shows; changes when the camera is switched while previewing. */
    fun currentPreview(): VideoTrackHandle? = synchronized(lock) { previewFrames }

    private fun previewCapability(device: VideoDevice): VideoCaptureCapability {
        val caps = runCatching { MediaDevices.getVideoCaptureCapabilities(device) }.getOrDefault(emptyList())
        return caps.filter { it.width <= 640 && it.frameRate >= 15 }.maxByOrNull { it.width * it.height }
            ?: caps.minByOrNull { it.width }
            ?: VideoCaptureCapability(640, 480, 30)
    }

    // -- Persistence --------------------------------------------------------------------------------

    private fun load(): DeviceSelection {
        val file = preferencesFile ?: return DeviceSelection()
        if (!file.isFile) return DeviceSelection()
        val props = Properties()
        runCatching { file.inputStream().use(props::load) }.onFailure { return DeviceSelection() }
        fun read(prefix: String): MediaDeviceInfo? {
            val id = props.getProperty("$prefix.id") ?: return null
            return MediaDeviceInfo(id, props.getProperty("$prefix.name").orEmpty())
        }
        return DeviceSelection(read("microphone"), read("speaker"), read("camera"))
    }

    private fun save(selection: DeviceSelection) {
        val file = preferencesFile ?: return
        val props = Properties()
        DeviceKind.entries.forEach { kind ->
            val prefix = kind.name.lowercase()
            selection[kind]?.let {
                props.setProperty("$prefix.id", it.id)
                props.setProperty("$prefix.name", it.name)
            }
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { props.store(it, "Zoocall audio and video devices") }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { logger.warn(TAG, "Couldn't save device choices", it) }
    }

    private companion object {
        const val TAG = "Devices"
        const val CHIME_MS = 900L

        fun Device.info() = MediaDeviceInfo(descriptor, name)

        /** Two soft notes with a quick fade, like a notification chime. */
        fun chime(t: Double): Double {
            val ms = t * 1000
            if (ms >= CHIME_MS) return 0.0
            val freq = if (ms < 420) 660.0 else 880.0
            val local = if (ms < 420) ms else ms - 420
            val envelope = (1 - local / 480).coerceIn(0.0, 1.0) * (local / 15).coerceAtMost(1.0)
            return sin(2 * PI * freq * t) * envelope
        }

        fun levelOf(samples: ByteArray, frames: Int, bytesPerSample: Int, channels: Int): Float {
            // 16-bit interleaved PCM: every 2 bytes is one sample of one channel.
            val count = minOf(samples.size / 2, frames.coerceAtLeast(0) * channels.coerceIn(1, 8)).coerceAtLeast(1)
            var sum = 0.0
            var n = 0
            while (n < count && n * 2 + 1 < samples.size) {
                val s = ((samples[n * 2 + 1].toInt() shl 8) or (samples[n * 2].toInt() and 0xff)).toShort().toDouble()
                sum += s * s
                n++
            }
            val rms = sqrt(sum / n.coerceAtLeast(1)) / Short.MAX_VALUE
            // Speech is quiet in linear terms; scale so normal talking fills about half the meter.
            return (rms * 5).toFloat().coerceIn(0f, 1f)
        }
    }
}
