package com.example.localvoice.actions

import android.content.Context
import android.media.AudioManager

/**
 * Controlla il volume dello stream musica (STREAM_MUSIC) — lo stream a cui
 * appartiene la riproduzione di contenuti multimediali, coerente con
 * MediaAction. Nessun permesso speciale richiesto per regolarlo via
 * AudioManager.
 */
class VolumeAction(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Imposta il volume alla percentuale data (0-100). @return il risultato reale in %. */
    fun setPercent(percent: Int): Int {
        val clamped = percent.coerceIn(0, 100)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = ((max * clamped) / 100.0).toInt().coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        return currentPercent()
    }

    /** Alza/abbassa di uno scatto (equivalente ai tasti fisici volume). @return il risultato in %. */
    fun adjust(increase: Boolean): Int {
        val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        return currentPercent()
    }

    private fun currentPercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max == 0) return 0
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (current * 100) / max
    }
}
