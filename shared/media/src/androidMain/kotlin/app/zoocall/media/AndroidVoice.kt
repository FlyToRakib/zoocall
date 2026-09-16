package app.zoocall.media

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import app.zoocall.core.model.Logger

class AndroidVoiceRecorder(logger: Logger) : PcmVoiceRecorder(logger) {
    // The UI asks for RECORD_AUDIO right before calling start().
    @SuppressLint("MissingPermission")
    override fun openInput(sampleRate: Int): PcmInput {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, OpusVoiceCodec.FRAME_SAMPLES * 8),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("AudioRecord init failed")
        }
        record.startRecording()
        return object : PcmInput {
            override fun read(buffer: ShortArray, offset: Int, length: Int): Int = record.read(buffer, offset, length)
            override fun close() {
                runCatching { record.stop() }
                record.release()
            }
        }
    }
}

class AndroidVoicePlayer(logger: Logger) : PcmVoicePlayer(logger) {
    override fun openOutput(sampleRate: Int): PcmOutput {
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, OpusVoiceCodec.FRAME_SAMPLES * 16))
            .build()
        track.play()
        return object : PcmOutput {
            override fun write(buffer: ShortArray, length: Int) {
                track.write(buffer, 0, length, AudioTrack.WRITE_BLOCKING)
            }
            override fun close() {
                runCatching { track.stop() }
                track.release()
            }
        }
    }
}
