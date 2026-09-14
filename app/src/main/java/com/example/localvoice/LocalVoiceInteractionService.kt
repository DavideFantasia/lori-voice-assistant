package com.example.localvoice

import android.content.Intent
import android.service.voice.VoiceInteractionService
import com.example.localvoice.service.ListeningForegroundService
import android.os.Bundle
import android.service.voice.VoiceInteractionSession

/**
 * Entry point registrato in manifest come VoiceInteractionService.
 * Il suo compito principale è avviare/fermare il ListeningForegroundService
 * (ascolto VAD + wake-word a basso consumo) quando l'utente attiva/disattiva
 * l'assistente dalle impostazioni di sistema.
 */
class LocalVoiceInteractionService : VoiceInteractionService() {

    companion object {
        private var instance: LocalVoiceInteractionService? = null

        fun triggerAssistantUI() {
            // SHOW_WITH_ASSIST garantisce che l'overlay scavalchi le app in primo piano
            instance?.showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST)
        }
    }

    override fun onReady() {
        super.onReady()
        // Salva l'istanza quando il sistema avvia il servizio
        instance = this
    }

    override fun onShutdown() {
        // Pulisci il riferimento per evitare memory leak quando l'app viene chiusa
        instance = null
        super.onShutdown()
    }
}
