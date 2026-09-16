package app.zoocall.android.call

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import app.zoocall.core.app.AppSettings

/** Ringtone, vibration, flash alert and call-progress tones. Respects silent and vibrate modes. */
class Ringer(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    private val cameras = context.getSystemService(CameraManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null
    private var vibrating = false

    /**
     * @param ringtoneId "" for the system default, [AppSettings.SILENT_RINGTONE], or a ringtone URI.
     * @param flash blink the camera light (accessibility flash alert); works in silent mode too.
     */
    fun startRinging(ringtoneId: String = "", vibrate: Boolean = true, flash: Boolean = false) {
        stop()
        val mode = audio.ringerMode
        if (mode == AudioManager.RINGER_MODE_NORMAL && ringtoneId != AppSettings.SILENT_RINGTONE) {
            val defaultUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            val chosen = if (ringtoneId.isEmpty()) defaultUri else runCatching { Uri.parse(ringtoneId) }.getOrNull()
            // A custom ringtone may have been deleted since it was chosen: fall back to the default.
            ringtone = (runCatching { RingtoneManager.getRingtone(context, chosen) }.getOrNull()
                ?: runCatching { RingtoneManager.getRingtone(context, defaultUri) }.getOrNull())
                ?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    isLooping = true
                    runCatching { play() }
                }
        }
        val vibrateWhenRinging = runCatching { Settings.System.getInt(context.contentResolver, "vibrate_when_ringing", 1) == 1 }.getOrDefault(true)
        if (vibrate && (mode == AudioManager.RINGER_MODE_VIBRATE || (mode == AudioManager.RINGER_MODE_NORMAL && vibrateWhenRinging))) {
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 900, 700), 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            }
            vibrating = true
        }
        if (flash) startFlash()
    }

    fun startRingback() {
        stop()
        tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70) }.getOrNull()?.apply {
            startTone(ToneGenerator.TONE_SUP_RINGTONE)
        }
    }

    private var waitingTone: ToneGenerator? = null

    /** Repeating call-waiting beep in the call audio; independent of [stop], which the call phase drives. */
    fun startCallWaiting() {
        if (waitingTone != null) return
        waitingTone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 50) }.getOrNull()?.apply {
            startTone(ToneGenerator.TONE_SUP_CALL_WAITING)
        }
    }

    fun stopCallWaiting() {
        waitingTone?.let {
            it.stopTone()
            it.release()
        }
        waitingTone = null
    }

    fun playConnected() = shortTone(ToneGenerator.TONE_PROP_ACK, 150)

    fun playEnded() = shortTone(ToneGenerator.TONE_PROP_PROMPT, 250)

    /** Push-to-talk chirp when someone starts or stops talking. */
    fun playPttChirp(start: Boolean) = shortTone(if (start) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_BEEP2, 120)

    /** Chime when a desk intercom line opens. */
    fun playIntercomChime() = shortTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)

    private fun shortTone(type: Int, durationMs: Int) {
        runCatching {
            val generator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60)
            generator.startTone(type, durationMs)
            handler.postDelayed({ generator.release() }, durationMs + 100L)
        }
    }

    // -- Flash alert ------------------------------------------------------------------------------

    private var torchId: String? = null
    private var torchOn = false

    /** One short blink a second, like the phone's own flash notifications. */
    private val blink = object : Runnable {
        override fun run() {
            val id = torchId ?: return
            torchOn = !torchOn
            runCatching { cameras.setTorchMode(id, torchOn) }
            handler.postDelayed(this, if (torchOn) 300L else 700L)
        }
    }

    private fun startFlash() {
        torchId = runCatching {
            cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrNull() ?: return
        handler.post(blink)
    }

    private fun stopFlash() {
        handler.removeCallbacks(blink)
        val id = torchId
        if (id != null && torchOn) runCatching { cameras.setTorchMode(id, false) }
        torchOn = false
        torchId = null
    }

    fun stop() {
        ringtone?.stop()
        ringtone = null
        tone?.let {
            it.stopTone()
            it.release()
        }
        tone = null
        if (vibrating) vibrator.cancel()
        vibrating = false
        stopFlash()
    }
}
