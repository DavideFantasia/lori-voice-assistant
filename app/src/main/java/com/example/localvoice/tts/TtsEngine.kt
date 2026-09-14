package com.example.localvoice.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import android.os.Handler
import android.os.Looper
/**
 * Wrapper sottile su android.speech.tts.TextToSpeech (motore offline di
 * sistema — nessuna dipendenza esterna, funziona anche senza Play Services).
 */
class TtsEngine(context: Context) {

    private lateinit var tts: TextToSpeech
    private var ready = false
    private var languageAvailable = false
    private val pendingQueue = mutableListOf<String>()

    // Variabile per conservare l'azione da eseguire alla fine del parlato (chiusura UI)
    private var onSpeakDoneCallback: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // isLanguageAvailable() è di sola lettura, non cambia lo
                // stato del motore — a differenza di setLanguage(), che se
                // chiamato con una lingua non supportata può lasciare
                // motori TTS ancora acerbi (come GrapheneOS Speech Services,
                // prima release pubblica) in uno stato "rotto" invece di
                // ripiegare con garbo sulla loro voce di default. Chiamando
                // setLanguage() SOLO quando sappiamo che avrà successo,
                // se l'italiano non c'è il motore resta sulla sua voce di
                // default (probabilmente inglese) — pronuncia storpiata,
                // ma perlomeno udibile, invece del silenzio totale osservato.
                val availability = tts.isLanguageAvailable(Locale.ITALY)
                if (availability >= TextToSpeech.LANG_AVAILABLE) {
                    tts.setLanguage(Locale.ITALY)
                    languageAvailable = true
                    Log.d(TAG, "Lingua italiana impostata correttamente per il TTS")
                } else {
                    languageAvailable = false
                    Log.w(TAG, "Voce italiana non disponibile su questo motore TTS (risultato=$availability) — resto sulla voce di default invece di forzare l'italiano, per evitare che il motore vada in silenzio totale. Se vuoi una pronuncia corretta, installa RHVoice/eSpeak-NG da Impostazioni > Lingue > Sintesi vocale.")
                }
                ready = true
                pendingQueue.forEach { speakNow(it) }
                pendingQueue.clear()
            } else {
                Log.e(TAG, "Inizializzazione TextToSpeech fallita, status=$status")
            }
        }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS onStart: $utteranceId")
            }
            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS onDone: $utteranceId")
                invokeCallback() // Chiude l'interfaccia a fine frase
            }
            @Deprecated("Deprecato nell'API ma richiesto per compatibilità")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS onError (legacy): $utteranceId")
                invokeCallback() // Chiude l'interfaccia anche se la voce fallisce
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                // Overload più recente, con codice errore — se il motore lo
                // supporta ci dice ESATTAMENTE perché una utterance è
                // sparita senza mai chiamare onStart/onDone.
                Log.e(TAG, "TTS onError: $utteranceId, codice=$errorCode")
                invokeCallback()
            }
            // Metodo di supporto: l'interfaccia grafica può essere chiusa
            // solo dal thread principale di Android
            private fun invokeCallback() {
                Handler(Looper.getMainLooper()).post {
                    onSpeakDoneCallback?.invoke()
                    onSpeakDoneCallback = null // Pulisce il riferimento dopo l'uso
                }
            }
        })
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        Log.d(TAG, "speak() chiamato con: '$text' (ready=$ready)")
        onSpeakDoneCallback = onDone // Salva l'azione

        if (!languageAvailable) {
            Log.w(TAG, "Tento comunque la sintesi senza lingua italiana confermata — se non senti nulla, è il motore TTS corrente il problema, non questo codice")
        }
        if (ready) speakNow(text) else pendingQueue.add(text)
    }

    private fun speakNow(text: String) {
        // L'utteranceId "localvoice_utterance" è vitale per far scattare l'onDone listener
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "localvoice_utterance")
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "tts.speak() ha ritornato ERROR per: '$text'")
            // Se fallisce subito alla chiamata, eseguiamo immediatamente il callback
            Handler(Looper.getMainLooper()).post {
                onSpeakDoneCallback?.invoke()
                onSpeakDoneCallback = null
            }
        }
    }

    fun release() {
        tts.stop()
        tts.shutdown()
    }

    companion object {
        private const val TAG = "LocalVoiceTts"
    }
}
