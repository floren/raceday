package net.jfloren.raceday.timer

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator

class SoundPlayer(private val context: Context) {

    // Held for the player's lifetime: a ToneGenerator that becomes unreachable can be
    // finalized (releasing it and cutting the tone off) before it finishes playing.
    private var toneGenerator: ToneGenerator? = null

    fun playShortSignal() {
        playTone(ToneGenerator.TONE_PROP_BEEP, 600)
    }

    fun playLongSignal() {
        playTone(ToneGenerator.TONE_CDMA_HIGH_L, 2500)
    }

    fun release() {
        toneGenerator?.release()
        toneGenerator = null
    }

    private fun playTone(toneType: Int, durationMs: Int) {
        // Best effort; failing to raise the volume (e.g. under Do Not Disturb) shouldn't stop the tone.
        try {
            val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.let {
                val maxVolume = it.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                it.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val generator = toneGenerator ?: ToneGenerator(AudioManager.STREAM_ALARM, 100).also {
                toneGenerator = it
            }
            generator.startTone(toneType, durationMs)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
