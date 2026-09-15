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
import com.example.localvoice.audio.KeywordSpotter
import com.example.localvoice.audio.LocalKeywordSpotter
import com.example.localvoice.audio.NoopKeywordSpotter
import com.example.localvoice.audio.SharedAudioSource
import com.example.localvoice.audio.VadGate
import com.example.localvoice.intent.CommandParser
import com.example.localvoice.stt.NoopSttEngine
import com.example.localvoice.stt.SttEngine
import com.example.localvoice.stt.VoskSttEngine
import com.example.localvoice.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

import com.example.localvoice.LocalVoiceInteractionService
/**
 * Cuore del consumo energetico dell'app.
 *
 * NUOVA ARCHITETTURA — UN SOLO MICROFONO PER TUTTA LA VITA DEL SERVIZIO:
 *
 *   SharedAudioSource (un solo AudioRecord, aperto in onCreate, mai
 *   richiuso finché il servizio vive)
 *        │  ogni chunk (~100ms) passa PRIMA dal VAD (VadGate), che è
 *        │  quasi gratis in CPU — è lui il gate di tutto il resto
 *        ▼
 *   [se voce probabile] LocalKeywordSpotter (rialimenta la pipeline
 *   mel→embedding→classificatore di openWakeWord con chunk esterni,
 *   invece di usare la libreria che vuole il proprio AudioRecord)
 *        │  score sopra soglia → beep di conferma
 *        ▼
 *   VoskSttEngine (stessa SharedAudioSource, NON un secondo AudioRecord —
 *   legge dallo stesso buffer, usa lo stesso VAD per capire inizio/fine
 *   del comando)
 *        │  testo finale (anche vuoto, se timeout)
 *        ▼
 *   parser + azione + TTS, poi si torna al keyword-spotting sullo
 *   STESSO microfono, mai chiuso nel frattempo.
 *
 * PERCHÉ QUESTO CAMBIO: prima openWakeWord (libreria, AudioRecord proprio)
 * e Vosk (AudioRecord proprio) erano due client del microfono distinti,
 * attivi entrambi durante la cattura del comando — esattamente il tipo di
 * contesa che si voleva evitare. Ora c'è un solo AudioRecord dall'inizio
 * alla fine, e VAD/keyword-spotting/Vosk sono solo stadi logici che
 * consumano lo stesso flusso di chunk in sequenza, mai in concorrenza.
 *
 * NOTA SUL MUTEX DI LETTURA: keywordSpotLoop e VoskSttEngine.startListening
 * NON leggono mai contemporaneamente da sharedMic — il flag
 * [capturingCommand] fa sì che keywordSpotLoop smetta di chiamare
 * readChunk() (si limita ad attendere) per tutta la durata della cattura
 * comando, che la riprende lei da sola internamente.
 */
class ListeningForegroundService : Service() {

    private lateinit var sharedMic: SharedAudioSource
    private val vad = VadGate()
    private lateinit var keywordSpotter: KeywordSpotter
    private lateinit var stt: SttEngine
    private lateinit var tts: TtsEngine
    private lateinit var audioCue: AudioCue
    private val parser = CommandParser()
    private lateinit var actionExecutor: ActionExecutor

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var keywordSpotJob: Job? = null

    // true mentre VoskSttEngine sta catturando un comando: il ciclo di
    // keyword-spotting smette di leggere dal microfono condiviso finché
    // non torna false (vedi NOTA SUL MUTEX DI LETTURA sopra).
    private val capturingCommand = AtomicBoolean(false)

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

        sharedMic = SharedAudioSource(SAMPLE_RATE, CHUNK_SIZE_BYTES)

        // Nome del file .onnx della keyword — "hey_lori_it.onnx" è il nome
        // del TUO modello italiano addestrato. Se manca, il servizio
        // degrada automaticamente a NoopKeywordSpotter (l'app non crasha,
        // il wake-word resta solo disattivato).
        val keywordAsset = "hey_lori_it.onnx"
        keywordSpotter = if (assetExists(keywordAsset)) {
            LocalKeywordSpotter(this, classifierAsset = keywordAsset, threshold = 0.25f)
        } else {
            Log.w(TAG, "Asset keyword '$keywordAsset' non trovato: wake-word disattivato (Noop)")
            NoopKeywordSpotter()
        }

        // Il modello Vosk è una CARTELLA (non un singolo asset), quindi la
        // verifichiamo diversamente dal file .onnx del wake-word.
        stt = if (assetDirExists("model-it-small")) {
            VoskSttEngine(this, sharedMic)
        } else {
            Log.w(TAG, "Cartella modello Vosk 'model-it-small' non trovata in assets/: STT disattivato (Noop). Vedi VoskSttEngine.kt per come procurartela.")
            NoopSttEngine()
        }

        tts = TtsEngine(this)
        audioCue = AudioCue()
        actionExecutor = ActionExecutor(this, tts)

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
        if (keywordSpotJob == null) {
            sharedMic.open()
            startKeywordSpotLoop()
            Log.d(TAG, "In ascolto della keyword (microfono condiviso, sempre aperto)")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        job.cancel()
        keywordSpotJob = null
        sharedMic.close()
        keywordSpotter.release()
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
     * Ciclo unico: legge dal microfono condiviso, passa ogni chunk dal VAD
     * (quasi gratis), e SOLO se il VAD dice "voce probabile" alimenta il
     * keyword-spotter (che è la parte costosa — inferenza di 3 modelli
     * ONNX). Questo è il risparmio di batteria che cercavi fin dall'inizio:
     * niente più inferenza neurale continua sul silenzio puro.
     */
    private fun startKeywordSpotLoop() {
        keywordSpotJob = scope.launch {
            val buffer = ByteArray(CHUNK_SIZE_BYTES)
            while (isActive) {
                if (capturingCommand.get()) {
                    // Vosk sta leggendo dallo stesso microfono: non
                    // chiamiamo readChunk() per non entrare in contesa
                    // (vedi NOTA SUL MUTEX DI LETTURA nella doc di classe).
                    delay(50)
                    continue
                }

                val read = sharedMic.readChunk(buffer)
                if (read <= 0) continue

                val pcm = toShortArray(buffer, read)
                if (!vad.isVoiceLikely(pcm)) continue

                val score = keywordSpotter.processChunk(pcm)
                if (score != null) {
                    Log.d(TAG, "Inferenza ONNX completata, score: $score")
                    if (keywordSpotter.checkDetection(score)) {
                        onWakeWordDetected()
                    }
                }
            }
        }
    }

    /**
     * Invocato quando la keyword scatta (o viene simulata dal tester).
     * Alza [capturingCommand] così il ciclo di keyword-spotting smette di
     * leggere dal microfono, suona il beep, poi passa la mano a Vosk —
     * che legge dallo STESSO SharedAudioSource, non ne apre uno nuovo.
     */
    private fun onWakeWordDetected() {
        if (!capturingCommand.compareAndSet(false, true)) {
            Log.d(TAG, "Rilevazione ignorata: comando già in gestione")
            return
        }

        LocalVoiceInteractionService.triggerAssistantUI()
        audioCue.playListeningCue()
        Log.d(TAG, "Wake-word gestita: beep suonato, avvio Vosk tra poco")

        scope.launch {
            // Leggiamo e buttiamo l'audio mentre il beep suona per evitare che si accumuli nel buffer di sistema.
            val discardTimeMs = audioCue.durationMs.toLong()
            val startDiscard = System.currentTimeMillis()
            val dumpBuffer = ByteArray(CHUNK_SIZE_BYTES)

            while (System.currentTimeMillis() - startDiscard < discardTimeMs) {
                sharedMic.readChunk(dumpBuffer)
            }

            stt.startListening { text ->
                Log.d(TAG, "Vosk ha consegnato: '$text'")
                handleRecognizedText(text)
            }
        }
    }

    private fun handleRecognizedText(text: String) {
        val voiceIntent = parser.parse(text)
        val confirmationMessage = actionExecutor.execute(voiceIntent)
        Log.d(TAG, "Intent=$voiceIntent conferma='$confirmationMessage'")
        if (confirmationMessage.isNotBlank()) {
            tts.speak(confirmationMessage) {
                com.example.localvoice.LoriVoiceInteractionSession.closeUI()
                resumeKeywordSpotting() // Riattivazione della Wake Word
            }
        } else {
            com.example.localvoice.LoriVoiceInteractionSession.closeUI()
            resumeKeywordSpotting() // Riattivazione della Wake Word
        }
    }

    // reset dello stato pulito
    private fun resumeKeywordSpotting() {
        keywordSpotter.reset()
        capturingCommand.set(false)
        Log.d(TAG, "Motore Wake-Word pulito e riattivato")
    }

    private fun toShortArray(buffer: ByteArray, length: Int): ShortArray {
        val out = ShortArray(length / 2)
        var i = 0
        var j = 0
        while (i + 1 < length) {
            out[j] = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            i += 2
            j++
        }
        return out
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
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE_BYTES = 3200 // ~100ms a 16kHz, PCM 16-bit mono
        const val ACTION_FORCE_WAKE = "com.example.localvoice.ACTION_FORCE_WAKE"
    }
}