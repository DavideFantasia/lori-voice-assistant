package com.example.localvoice

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService

/**
 * Le linee guida Android per le app "Voice Interaction" richiedono che
 * un'app che implementa VoiceInteractionService dichiari ANCHE un
 * RecognitionService, altrimenti l'app non si qualifica per il ruolo
 * di sistema ASSISTANT e non compare nel selettore "Assistente predefinito".
 *
 * Qui è un'implementazione minimale — il riconoscimento vero e proprio lo fa
 * SttEngine (Vosk) dentro ListeningForegroundService, non questa classe.
 * Questo servizio esiste principalmente per la qualificazione di sistema;
 * se in futuro vuoi che anche altre app possano usare il tuo motore STT
 * tramite SpeechRecognizer standard, è qui che andrebbe implementato
 * per davvero.
 */
class LocalVoiceRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // TODO(opzionale): se vuoi esporre il tuo STT ad altre app tramite
        // SpeechRecognizer standard, implementa qui usando SttEngine.
        listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {
        // no-op nell'implementazione minimale
    }

    override fun onStopListening(listener: Callback?) {
        // no-op nell'implementazione minimale
    }
}
