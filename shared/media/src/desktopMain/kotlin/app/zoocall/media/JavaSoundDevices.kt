package app.zoocall.media

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Line
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Opens Java Sound lines on the device chosen in Zoocall (used by voice messages and the ringtone,
 * which don't go through WebRTC). Falls back to the system default when no mixer matches.
 */
object JavaSoundDevices {
    fun sourceLine(format: AudioFormat, deviceName: String?): SourceDataLine =
        find(deviceName, SourceDataLine::class.java, format) ?: AudioSystem.getSourceDataLine(format)

    fun targetLine(format: AudioFormat, deviceName: String?): TargetDataLine =
        find(deviceName, TargetDataLine::class.java, format) ?: AudioSystem.getTargetDataLine(format)

    private fun <T : Line> find(deviceName: String?, type: Class<T>, format: AudioFormat): T? {
        if (deviceName.isNullOrBlank()) return null
        val info = DataLine.Info(type, format)
        return AudioSystem.getMixerInfo()
            .filter { namesMatch(it.name, deviceName) }
            .firstNotNullOfOrNull { mixerInfo ->
                runCatching {
                    val mixer = AudioSystem.getMixer(mixerInfo)
                    if (mixer.isLineSupported(info)) type.cast(mixer.getLine(info)) else null
                }.getOrNull()
            }
    }

    /**
     * Windows reports the same device with different spellings: WASAPI gives "Microphone (Realtek(R) Audio)",
     * while Java Sound's DirectSound mixers may truncate it to 31 characters or prefix "Port ".
     */
    fun namesMatch(mixerName: String, deviceName: String): Boolean {
        val mixer = normalize(mixerName)
        val device = normalize(deviceName)
        if (mixer.isEmpty() || device.isEmpty() || mixerName.startsWith("Port ")) return false
        if (mixer == device) return true
        val shorter = minOf(mixer.length, device.length)
        return shorter >= 12 && (mixer.startsWith(device.take(shorter)) || device.startsWith(mixer.take(shorter)))
    }

    private fun normalize(name: String) = name.lowercase().filter { it.isLetterOrDigit() }
}
