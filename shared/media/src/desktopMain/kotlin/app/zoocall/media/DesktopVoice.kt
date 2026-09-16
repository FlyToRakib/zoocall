package app.zoocall.media

import app.zoocall.core.model.Logger
import javax.sound.sampled.AudioFormat

private fun pcmFormat(sampleRate: Int) = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)

/** Records voice messages from the microphone chosen in Zoocall ([deviceName] null = system default). */
class DesktopVoiceRecorder(logger: Logger, private val deviceName: () -> String? = { null }) : PcmVoiceRecorder(logger) {
    override fun openInput(sampleRate: Int): PcmInput {
        val format = pcmFormat(sampleRate)
        val line = JavaSoundDevices.targetLine(format, deviceName())
        line.open(format, OpusVoiceCodec.FRAME_SAMPLES * 2 * 8)
        line.start()
        return object : PcmInput {
            private var bytes = ByteArray(0)
            override fun read(buffer: ShortArray, offset: Int, length: Int): Int {
                if (bytes.size != length * 2) bytes = ByteArray(length * 2)
                val n = line.read(bytes, 0, length * 2)
                if (n < 0) return n
                for (i in 0 until n / 2) {
                    buffer[offset + i] = ((bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 0xff)).toShort()
                }
                return n / 2
            }
            override fun close() {
                line.stop()
                line.close()
            }
        }
    }
}

/** Plays voice messages on the speaker chosen in Zoocall ([deviceName] null = system default). */
class DesktopVoicePlayer(logger: Logger, private val deviceName: () -> String? = { null }) : PcmVoicePlayer(logger) {
    override fun openOutput(sampleRate: Int): PcmOutput {
        val format = pcmFormat(sampleRate)
        val line = JavaSoundDevices.sourceLine(format, deviceName())
        line.open(format, OpusVoiceCodec.FRAME_SAMPLES * 2 * 8)
        line.start()
        return object : PcmOutput {
            private var bytes = ByteArray(0)
            override fun write(buffer: ShortArray, length: Int) {
                if (bytes.size != length * 2) bytes = ByteArray(length * 2)
                for (i in 0 until length) {
                    bytes[i * 2] = buffer[i].toByte()
                    bytes[i * 2 + 1] = (buffer[i].toInt() shr 8).toByte()
                }
                line.write(bytes, 0, length * 2)
            }
            override fun close() {
                line.drain()
                line.stop()
                line.close()
            }
        }
    }
}
