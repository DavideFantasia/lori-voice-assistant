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

    private lateinit var audioManager: android.media.AudioManager //per chiedere ad android se ci sono media in riproduzione
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

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
        audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
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
            val preRollChunks = java.util.ArrayDeque<ShortArray>()
            var wasVoiceLikely = false
            var silenceCounter = 0
            var mediaCheckCounter = 0 // contatore per il controllo dei media (e successivo noise cancelling)
            while (isActive) {
                if (capturingCommand.get()) {
                    delay(200)
                    continue
                }

                mediaCheckCounter++
                if (mediaCheckCounter >= 10) {
                    mediaCheckCounter = 0
                    // Accende l'AEC in background SOLO se c'è audio in riproduzione
                    val isMediaPlaying = audioManager.isMusicActive
                    sharedMic.setEnhancementsEnabled(isMediaPlaying)
                }

                val read = sharedMic.readChunk(buffer)
                if (read <= 0) continue

                val pcm = toShortArray(buffer, read)
                val isVoice = vad.isVoiceLikely(pcm)

                if (isVoice) {
                    silenceCounter = 0 // Azzera il timer del silenzio

                    if (!wasVoiceLikely) {
                        Log.d(TAG, "VAD scattato: inietto ${preRollChunks.size} chunk di pre-roll")
                        while (preRollChunks.isNotEmpty()) {
                            keywordSpotter.processChunk(preRollChunks.removeFirst())
                        }
                        wasVoiceLikely = true
                    }
                } else {
                    if (wasVoiceLikely) {
                        silenceCounter++
                        // Resetta il KWS solo se il silenzio persiste per più di 1 secondo
                        if (silenceCounter >= SILENCE_HANGOVER_CHUNKS) {
                            Log.d(TAG, "Silenzio prolungato confermato: reset del KWS")
                            keywordSpotter.reset()
                            wasVoiceLikely = false
                            silenceCounter = 0
                            continue
                        }
                    }
                }

                // Alimentiamo il modello solo se siamo nella finestra di "voce" (inclusi i micro-silenzi tollerati)
                if (wasVoiceLikely) {
                    val score = keywordSpotter.processChunk(pcm)
                    if (score != null) {
                        if (keywordSpotter.checkDetection(score)) {
                            onWakeWordDetected()
                            preRollChunks.clear()
                            wasVoiceLikely = false
                            silenceCounter = 0
                        }
                    }
                } else {
                    // Manteniamo aggiornato il buffer di pre-roll solo nel VERO silenzio
                    preRollChunks.addLast(pcm)
                    if (preRollChunks.size > PRE_ROLL_MAX_CHUNKS) {
                        preRollChunks.removeFirst()
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
        takeAudioFocus() // per silenziare qualunque media in play
        SetWakeLock(3000) // da il woke allo schermo per 3 secondi
        LocalVoiceInteractionService.triggerAssistantUI()
        audioCue.playListeningCue()

        scope.launch {
            // Leggiamo e buttiamo l'audio mentre il beep suona per evitare che si accumuli nel buffer di sistema.
            val discardTimeMs = audioCue.durationMs.toLong()
            val startDiscard = System.currentTimeMillis()
            val dumpBuffer = ByteArray(CHUNK_SIZE_BYTES)

            while (System.currentTimeMillis() - startDiscard < discardTimeMs) {
                sharedMic.readChunk(dumpBuffer)
            }
            sharedMic.setEnhancementsEnabled(true) // accensione soppressione del rumore
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

        val isPauseCommand = voiceIntent is com.example.localvoice.intent.VoiceIntent.PauseMedia // controllo per ristabilire o meno il focus se era un comando di pausa
        if (confirmationMessage.isNotBlank()) {
            tts.speak(confirmationMessage) {
                com.example.localvoice.LoriVoiceInteractionSession.closeUI()
                resumeKeywordSpotting(isPauseCommand) // Riattivazione della Wake Word
            }
        } else {
            com.example.localvoice.LoriVoiceInteractionSession.closeUI()
            resumeKeywordSpotting(isPauseCommand) // Riattivazione della Wake Word
        }
    }

    // reset dello stato pulito
    private fun resumeKeywordSpotting(isPauseCommand: Boolean = false) {
        keywordSpotter.reset()
        capturingCommand.set(false)
        sharedMic.setEnhancementsEnabled(false) // filtri soppressione del rumore spenti
        if (isPauseCommand) { SetFocusLoss() } // check per vedere se si può far ripartire eventuali media

        releaseAudioFocus() // per far ripartire i media che erano in play
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

    private fun takeAudioFocus() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            audioFocusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* Gestito automaticamente dal sistema */ }
                .build()

            audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        }
        Log.d(TAG, "AudioFocus richiesto: media in background in pausa")
    }

    private fun releaseAudioFocus() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        Log.d(TAG, "AudioFocus rilasciato: i media in background possono riprendere")
    }

    // Trasformiamo la perdita di focus da temporanea a DEFINITIVA.
    // Il player in background riceve AUDIOFOCUS_LOSS e cancella l'intento di auto-resume.
    private fun SetFocusLoss(){
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    // prende il focus dello schermo (lo accende) per un timeout di time millisecondi
    private fun SetWakeLock(time: Long){
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        @Suppress("DEPRECATION") // SCREEN_BRIGHT_WAKE_LOCK è deprecato per le app standard, ma vitale per i servizi in background
        val wakeLock = powerManager.newWakeLock(
            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "LocalVoice::WakeScreen"
        )
        // Il lock durerà 3 secondi, dopodiché lo schermo seguirà il normale timeout di sistema
        wakeLock.acquire(time)
    }

    companion object {
        private const val TAG = "LocalVoiceCascade"
        private const val NOTIFICATION_ID = 42
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE_BYTES = 3200 // ~100ms a 16kHz, PCM 16-bit mono
        private const val PRE_ROLL_MAX_CHUNKS = 25 // 25 chunk * 100ms (CHUNK_SIZE_BYTES) = ~2.5 secondi di audio in pre-roll
        private const val SILENCE_HANGOVER_CHUNKS = 10 // 1 secondo di tolleranza (10 * 100ms)
        const val ACTION_FORCE_WAKE = "com.example.localvoice.ACTION_FORCE_WAKE"
    }
}