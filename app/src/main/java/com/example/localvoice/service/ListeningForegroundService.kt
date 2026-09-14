package com.example.localvoice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.localvoice.actions.ActionExecutor
import com.example.localvoice.audio.AudioCue
import com.example.localvoice.audio.NoopWakeWordEngine
import com.example.localvoice.audio.OpenWakeWordEngine
import com.example.localvoice.audio.WakeWordEngine
import com.example.localvoice.intent.CommandParser
import com.example.localvoice.stt.NoopSttEngine
import com.example.localvoice.stt.SttEngine
import com.example.localvoice.stt.VoskSttEngine
import com.example.localvoice.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

import com.example.localvoice.LocalVoiceInteractionService
/**
 * Cuore del consumo energetico dell'app.
 *
 *   openWakeWord (ascolto continuo, MAI fermato durante la vita del servizio)
 *        │  keyword rilevata → beep di conferma
 *        │  (nuove rilevazioni ravvicinate vengono ignorate via
 *        │   handlingWakeWord, non fermano il motore)
 *        ▼
 *   Vosk (Recognizer.acceptWaveForm, AudioRecord nostro con AEC/NS)
 *        │  testo finale (anche vuoto, se timeout)
 *        ▼
 *   parser + azione + TTS
 *
 * NOTA ARCHITETTURALE: la versione precedente fermava/riavviava il motore
 * wake-word ad ogni singola rilevazione (stopListening()/startListening()).
 * Rimosso: sospettiamo fosse la causa sia dei "beep multipli" (rilevazioni
 * ravvicinate della stessa pronuncia processate più volte, prima che lo
 * stop avesse effetto) sia del microfono che smetteva di funzionare dopo
 * alcuni minuti (probabile degrado dell'AudioRecord interno della libreria
 * dopo troppi cicli ravvicinati di stop/riavvio — non verificabile con
 * certezza dato che è codice compilato di terze parti, ma il pattern dei
 * sintomi combacia). Ora il motore resta sempre acceso, gestito solo da un
 * flag software.
 */
class ListeningForegroundService : Service() {

    private lateinit var wakeWord: WakeWordEngine
    private lateinit var stt: SttEngine
    private lateinit var tts: TtsEngine
    private lateinit var audioCue: AudioCue
    private val parser = CommandParser()
    private lateinit var actionExecutor: ActionExecutor

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    // Evita di gestire più rilevazioni ravvicinate della stessa pronuncia
    // (causa dei "beep multipli"), e ci permette di NON fermare/riavviare
    // il motore wake-word ad ogni ciclo — resta sempre acceso, ignoriamo
    // solo le rilevazioni mentre stiamo già gestendo un comando. Il
    // continuo stop()/start() sospettiamo fosse la causa del microfono che
    // smetteva di funzionare dopo alcuni minuti (probabile degrado
    // dell'AudioRecord interno della libreria dopo troppi cicli ravvicinati).
    private val handlingWakeWord = AtomicBoolean(false)

    private val forceWakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_FORCE_WAKE) {
                Log.d(TAG, "Force-wake ricevuto dal tester manuale")
                onWakeWordDetected()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Nome del file .onnx della keyword — "hey_lori_it.onnx" è il nome
        // del TUO modello italiano addestrato (non incluso in questo
        // scheletro, aggiungilo tu in assets/ quando pronto). Se manca,
        // il servizio degrada automaticamente a NoopWakeWordEngine (vedi
        // sotto) — l'app non crasha, il wake-word resta solo disattivato.
        // "hey_jarvis_test_en.onnx" (inglese, incluso) resta disponibile
        // in assets/ per un test rapido della pipeline nel frattempo, se
        // ti serve: basta cambiare il valore di questa variabile.
        val keywordAsset = "hey_lori_it.onnx"
        wakeWord = if (assetExists(keywordAsset)) {
            OpenWakeWordEngine(this, keywordAsset)
        } else {
            Log.w(TAG, "Asset keyword '$keywordAsset' non trovato: wake-word disattivato (Noop)")
            NoopWakeWordEngine()
        }

        // Il modello Vosk è una CARTELLA (non un singolo asset), quindi la
        // verifichiamo diversamente dal file .onnx del wake-word.
        stt = if (assetDirExists("model-it-small")) {
            VoskSttEngine(this)
        } else {
            Log.w(TAG, "Cartella modello Vosk 'model-it-small' non trovata in assets/: STT disattivato (Noop). Vedi VoskSttEngine.kt per come procurartela.")
            NoopSttEngine()
        }

        tts = TtsEngine(this)
        audioCue = AudioCue()
        actionExecutor = ActionExecutor(this, tts)

        wakeWord.load()
        stt.load()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(forceWakeReceiver, IntentFilter(ACTION_FORCE_WAKE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(forceWakeReceiver, IntentFilter(ACTION_FORCE_WAKE))
        }

        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        wakeWord.startListening { onWakeWordDetected() }
        Log.d(TAG, "In ascolto della keyword")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        job.cancel()
        wakeWord.release()
        stt.release()
        tts.release()
        audioCue.release()
        try {
            unregisterReceiver(forceWakeReceiver)
        } catch (e: IllegalArgumentException) {
            // già non registrato, ignorabile
        }
        super.onDestroy()
    }

    /**
     * Invocato quando la keyword scatta (o viene simulata dal tester).
     *
     * A differenza della versione precedente, NON fermiamo più il motore
     * wake-word (niente stopListening()/startListening() ad ogni ciclo) —
     * resta sempre acceso. Ci limitiamo a IGNORARE nuove rilevazioni mentre
     * stiamo già gestendo un comando, tramite il flag [handlingWakeWord].
     * Questo risolve sia i beep multipli (rilevazioni ravvicinate della
     * stessa pronuncia venivano processate tutte) sia, probabilmente, il
     * degrado del microfono dopo alcuni minuti (troppi cicli di
     * stop/riavvio ravvicinati dell'AudioRecord interno della libreria).
     */
    private fun onWakeWordDetected() {
        if (!handlingWakeWord.compareAndSet(false, true)) {
            Log.d(TAG, "Rilevazione ignorata: comando già in gestione")
            return
        }

        LocalVoiceInteractionService.triggerAssistantUI()
        audioCue.playListeningCue()
        Log.d(TAG, "Wake-word gestita: beep suonato, avvio Vosk tra poco")

        scope.launch {
            delay(audioCue.durationMs.toLong()) // lascia finire il beep prima di ascoltare
            stt.startListening { text ->
                Log.d(TAG, "Vosk ha consegnato: '$text'")
                handleRecognizedText(text)
                handlingWakeWord.set(false) // torna a reagire a nuove rilevazioni
            }
        }
    }

    private fun handleRecognizedText(text: String) {
        val voiceIntent = parser.parse(text)
        val confirmationMessage = actionExecutor.execute(voiceIntent)
        Log.d(TAG, "Intent=$voiceIntent conferma='$confirmationMessage'")
        if (confirmationMessage.isNotBlank()) {
            // Parla e, quando ha finito, chiude l'interfaccia
            tts.speak(confirmationMessage, {com.example.localvoice.LoriVoiceInteractionSession.closeUI()})
        } else {
            // Se non c'è nulla da dire (es. comando sconosciuto silenzioso), chiude subito
            com.example.localvoice.LoriVoiceInteractionSession.closeUI()
        }
    }

    private fun assetExists(name: String): Boolean {
        return try {
            assets.open(name).close()
            true
        } catch (e: java.io.IOException) {
            false
        }
    }

    private fun assetDirExists(name: String): Boolean {
        return try {
            assets.list(name)?.isNotEmpty() == true
        } catch (e: java.io.IOException) {
            false
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "localvoice_listening"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Assistente in ascolto", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Lori attivo")
            .setContentText("In ascolto della keyword")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "LocalVoiceCascade"
        private const val NOTIFICATION_ID = 42
        const val ACTION_FORCE_WAKE = "com.example.localvoice.ACTION_FORCE_WAKE"
    }
}
