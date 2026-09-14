package com.example.localvoice.audio

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Un breve bip quando la keyword scatta — segnale minimo che l'app è in
 * ascolto del comando, analogo al "chime" di altri assistenti vocali.
 * Usa ToneGenerator (nessun asset audio da includere, nessuna dipendenza
 * dal TTS che potrebbe non essere ancora pronto in quel momento).
 */
class AudioCue {

    // Alcuni dispositivi/emulatori negano l'accesso al tone generator
    // (risorse audio esaurite): non deve far crashare l'app, il segnale
    // acustico è un miglioramento UX, non una funzione critica.
    private val toneGenerator = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME_PERCENT)
    } catch (e: RuntimeException) {
        null
    }

    /** Durata approssimativa del segnale — il chiamante aspetta questo
     *  tempo prima di iniziare ad ascoltare il comando, così il microfono
     *  non cattura la coda del beep stesso. */
    val durationMs = DURATION_MS

    fun playListeningCue() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, DURATION_MS)
    }

    fun release() {
        toneGenerator?.release()
    }

    companion object {
        private const val VOLUME_PERCENT = 70
        private const val DURATION_MS = 150
    }
}
