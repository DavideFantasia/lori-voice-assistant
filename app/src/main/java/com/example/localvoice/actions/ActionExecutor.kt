package com.example.localvoice.actions

import android.content.Context
import com.example.localvoice.intent.VoiceIntent
import com.example.localvoice.tts.TtsEngine

class ActionExecutor(private val context: Context, private val tts: TtsEngine) {

    private val webSearchAction = WebSearchAction(context)
    private val timerAction = TimerAction(context)
    private val bluetoothAction = BluetoothAction(context)
    private val mediaAction = MediaAction(context)
    private val volumeAction = VolumeAction(context)
    private val callAction = CallAction(context)
    private val spotifyAction = SpotifyAction(context)
    /** @return un breve messaggio da leggere via TTS come conferma. */
    fun execute(intent: VoiceIntent): String {
        return when (intent) {
            is VoiceIntent.WebSearch -> {
                webSearchAction.search(intent.query)
                "Ecco i risultati per ${intent.query}"
            }
            is VoiceIntent.SetTimer -> {
                when (val result = timerAction.set(intent.seconds, intent.label)) {
                    is TimerResult.ScheduledExact -> "Timer impostato"
                    is TimerResult.ScheduledInexact ->
                        "Timer impostato, ma potrebbe ritardare: concedimi il permesso sveglie esatte nelle impostazioni"
                    is TimerResult.MissingNotificationPermission ->
                        "Timer programmato, ma non potrò avvisarti: concedimi il permesso notifiche"
                }
            }
            is VoiceIntent.BluetoothToggle -> {
                val toggledDirectly = bluetoothAction.setEnabled(intent.turnOn)
                if (toggledDirectly) {
                    if (intent.turnOn) "Bluetooth acceso" else "Bluetooth spento"
                } else {
                    "Apro le impostazioni Bluetooth, conferma tu la modifica"
                }
            }
            VoiceIntent.NextTrack -> {
                mediaAction.next()
                "Prossima canzone"
            }
            VoiceIntent.PreviousTrack -> {
                mediaAction.previous()
                "Canzone precedente"
            }
            VoiceIntent.PauseMedia -> {
                mediaAction.pause()
                "Messo in pausa"
            }
            is VoiceIntent.SetVolume -> {
                val result = volumeAction.setPercent(intent.percent)
                "Volume al $result percento"
            }
            is VoiceIntent.AdjustVolume -> {
                val result = volumeAction.adjust(intent.increase)
                if (intent.increase) "Volume alzato, ora al $result percento"
                else "Volume abbassato, ora al $result percento"
            }
            is VoiceIntent.CallContact -> {
                callAction.call(intent.contactName) // Esegue l'azione e ritorna il testo di conferma
            }
            is VoiceIntent.PlayMusic -> {
                // Asincrono per natura (richiede una chiamata di rete):
                // diamo subito un messaggio "sto cercando", e un secondo
                // messaggio via TTS quando il risultato arriva — l'unico
                // comando in tutta l'app che funziona in due tempi invece
                // che con una singola conferma immediata.
                spotifyAction.playTopResult(intent.query) { found ->
                    if (!found) tts.speak("Non ho trovato nulla su Spotify per ${intent.query}")
                    // Se trovato, l'apertura di Spotify stessa è già la
                    // conferma visiva — nessun secondo messaggio necessario.
                }
                "Cerco ${intent.query} su Spotify"
            }
            VoiceIntent.Unknown -> "Non ho capito il comando"
        }
    }
}
