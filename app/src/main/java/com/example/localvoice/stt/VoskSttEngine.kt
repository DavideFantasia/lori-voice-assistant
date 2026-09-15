package com.example.localvoice.stt

import android.content.Context
import android.util.Log
import com.example.localvoice.audio.SharedAudioSource
import com.example.localvoice.audio.VadGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.IOException
import java.util.ArrayDeque
import kotlin.math.sqrt

/**
 * Wrapper su Vosk Android SDK (Apache 2.0, alphacephei) — API LOW-LEVEL
 * (Recognizer.acceptWaveForm), non più SpeechService.
 *
 * BUFFER AUDIO CONDIVISO: questa classe non apre più un proprio
 * AudioRecord. Usa [SharedAudioSource] — un solo microfono per questa
 * fase — e legge da lì gli stessi chunk sia per decidere con [VadGate]
 * quando l'utente inizia/smette di parlare, sia per alimentare il
 * riconoscitore Vosk. Prima le due cose (rilevazione voce e STT)
 * avrebbero potuto competere per il microfono se fatte da componenti
 * separati con un proprio AudioRecord ciascuno; ora leggono lo stesso
 * chunk nello stesso ciclo.
 *
 * PRE-ROLL: dato che il VAD ha una piccola latenza di rilevamento (serve
 * qualche chunk perché l'energia superi la soglia), teniamo un buffer
 * circolare dei chunk più recenti PRIMA che parta il parlato, e li
 * "svuotiamo" dentro Vosk non appena il VAD dichiara che si sta parlando
 * — così non perdiamo la prima sillaba. Prima che il parlato inizi, i
 * chunk NON vengono passati a Vosk (risparmio di decoding su puro
 * silenzio); vengono passati solo dal momento in cui il VAD conferma voce.
 *
 * TRADE-OFF IMPORTANTE — grammar mode: per aumentare l'accuratezza sui
 * comandi fissi (bluetooth, timer, volume, media), vincoliamo Vosk a un
 * vocabolario ristretto (vedi GRAMMAR_JSON). Questo però rende ANCHE la
 * ricerca web meno affidabile: una query libera come "cerca ricette di
 * pasta" verrà in gran parte trascritta come "[unk]" per le parole fuori
 * vocabolario, perché il decoder non può fisicamente restituire parole non
 * elencate. È un compromesso deliberato — se la ricerca web ti serve più
 * dell'accuratezza sui comandi fissi, valuta di rimuovere il parametro
 * grammar dal costruttore di Recognizer sotto (torna a riconoscimento a
 * vocabolario pieno, meno preciso su tutto ma senza questo limite).
 *
 * COSA DEVI PROCURARTI TU (invariato rispetto a prima):
 * 1. Modello italiano "small" da https://alphacephei.com/vosk/models
 * 2. Copiato in app/src/main/assets/model-it-small/
 * 3. Con un file "uuid" aggiunto a mano dentro quella cartella (vedi note
 *    precedenti — particolarità nota di StorageService.unpack()).
 */
class VoskSttEngine(
    private val context: Context,
    private val sharedMic: SharedAudioSource
) : SttEngine {

    private var model: Model? = null
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var captureJob: kotlinx.coroutines.Job? = null

    override fun load() {
        StorageService.unpack(
            context,
            MODEL_ASSET_PATH,
            "model",
            { loadedModel: Model ->
                model = loadedModel
                Log.d(TAG, "Modello Vosk caricato correttamente")
            },
            { exception: IOException ->
                Log.e(TAG, "Impossibile caricare il modello Vosk da assets/$MODEL_ASSET_PATH — hai copiato la cartella del modello (e il file uuid)? Vedi doc di questa classe.", exception)
            }
        )
    }

    override fun startListening(onFinalResult: (String) -> Unit) {
        val m = model
        if (m == null) {
            Log.w(TAG, "startListening chiamato ma il modello non è pronto: rispondo con testo vuoto")
            onFinalResult("")
            return
        }

        captureJob = scope.launch {
            val text = try {
                captureAndRecognize(m)
            } catch (e: Exception) {
                Log.e(TAG, "Errore durante cattura/riconoscimento", e)
                ""
            }
            onFinalResult(text)
        }
    }

    override fun stopListening() {
        captureJob?.cancel()
        captureJob = null
    }

    override fun release() {
        stopListening()
        model = null
        job.cancel()
    }

    private suspend fun captureAndRecognize(model: Model): String {
        // NB: se il costruttore a 3 argomenti con grammar non risolve in
        // fase di compilazione, la tua versione di vosk-android potrebbe
        // esporlo con un nome diverso — stesso approccio di sempre: mandami
        // l'errore e/o il sorgente reale della classe Recognizer, lo
        // correggiamo mirato invece di indovinare alla cieca.
        val recognizer = Recognizer(model, SAMPLE_RATE, GRAMMAR_JSON)
        val vad = VadGate()
        // NB: sharedMic è già aperto e gestito dal servizio per tutta la sua
        // vita (vedi ListeningForegroundService) — qui lo leggiamo soltanto,
        // non lo apriamo né lo chiudiamo.

        // Buffer circolare dei chunk pre-parlato: ~PRE_ROLL_CHUNKS * 100ms.
        val preRoll = ArrayDeque<ByteArray>()

        var resultText = ""
        try {
            val buffer = ByteArray(CHUNK_SIZE_BYTES)
            var silenceChunks = 0
            var waitChunks = 0
            var totalChunks = 0
            var speechStarted = false
            val chunkMs = (CHUNK_SIZE_BYTES / 2) * 1000 / SAMPLE_RATE.toInt() // 2 byte per campione 16-bit

            // reset UI di trascrizione
            com.example.localvoice.VoiceUIState.reset()

            while (currentCoroutineContext().isActive) {
                val read = sharedMic.readChunk(buffer)
                if (read <= 0) continue

                val chunk = buffer.copyOf(read)
                val pcm = toShortArray(chunk, read)

                // 1. RMS per l'indicatore audio lineare (UI) — invariato
                val rms = rmsOf(pcm)
                val normalizedVolume = ((rms / 10000.0) * 1.5).toFloat().coerceIn(0f, 1f)
                com.example.localvoice.VoiceUIState.audioVolumeFlow.value = normalizedVolume

                // 2. Decisione di attività vocale — ora delegata al VAD condiviso,
                //    non più ricalcolata separatamente dentro questa classe.
                val voice = vad.isVoiceLikely(pcm)
                totalChunks++

                if (!speechStarted) {
                    // Ancora silenzio: bufferizza per il pre-roll, NON alimentare
                    // ancora Vosk (risparmio di decoding sul silenzio puro).
                    preRoll.addLast(chunk)
                    if (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()

                    if (voice) {
                        speechStarted = true
                        silenceChunks = 0
                        // Svuota il pre-roll dentro Vosk PRIMA di processare il
                        // chunk corrente, per non perdere l'inizio del parlato.
                        for (p in preRoll) recognizer.acceptWaveForm(p, p.size)
                        preRoll.clear()
                        feedAndUpdatePartial(recognizer, chunk)
                    } else {
                        waitChunks++
                        if (waitChunks * chunkMs > SPEECH_START_TIMEOUT_MS) break
                        continue
                    }
                } else {
                    feedAndUpdatePartial(recognizer, chunk)
                    if (voice) silenceChunks = 0 else silenceChunks++
                    if (silenceChunks * chunkMs > SILENCE_END_MS) break
                }

                if (totalChunks * chunkMs > MAX_TOTAL_MS) break
            }

            // 3. Estrai e visualizza il testo finale conclusivo
            resultText = extractText(recognizer.finalResult)
            com.example.localvoice.VoiceUIState.transcriptionFlow.value = resultText

            // Azzera il volume quando l'ascolto termina
            com.example.localvoice.VoiceUIState.audioVolumeFlow.value = 0f

            Log.d(TAG, "Testo riconosciuto: '$resultText'")

        } finally {
            recognizer.close()
        }

        return resultText
    }

    /** Alimenta Vosk con un chunk e, se il risultato non è finale, aggiorna la UI col parziale. */
    private fun feedAndUpdatePartial(recognizer: Recognizer, chunk: ByteArray) {
        val isFinal = recognizer.acceptWaveForm(chunk, chunk.size)
        if (!isFinal) {
            val partialJson = recognizer.partialResult
            val partialText = JSONObject(partialJson).optString("partial", "")
            if (partialText.isNotEmpty()) {
                com.example.localvoice.VoiceUIState.transcriptionFlow.value = partialText
            }
        }
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

    private fun rmsOf(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sumSquares = 0.0
        for (s in samples) sumSquares += s.toDouble() * s.toDouble()
        return sqrt(sumSquares / samples.size)
    }

    private fun extractText(json: String?): String {
        if (json == null) return ""
        return try {
            JSONObject(json).optString("text", "")
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        private const val TAG = "LocalVoiceVosk"
        private const val SAMPLE_RATE = 16000.0f
        private const val MODEL_ASSET_PATH = "model-it-small"
        private const val CHUNK_SIZE_BYTES = 3200 // ~100ms a 16kHz, PCM 16-bit mono
        private const val SPEECH_START_TIMEOUT_MS = 3000
        private const val SILENCE_END_MS = 1200
        private const val MAX_TOTAL_MS = 8000
        private const val PRE_ROLL_CHUNKS = 3 // ~300ms di pre-roll prima del trigger VAD

        // Vocabolario ristretto per il "grammar mode" — vedi trade-off nella
        // doc della classe sopra. "[unk]" è la convenzione Vosk per "parola
        // fuori vocabolario", va sempre incluso.
        private val GRAMMAR_JSON: String = buildList {
            addAll(listOf(
                "cerca", "trova", "su", "internet", "online", "sul", "web",
                "imposta", "avvia", "metti", "fai", "partire", "un", "timer", "di",
                "secondi", "secondo", "minuti", "minuto", "ore", "ora",
                "accendi", "spegni", "il", "bluetooth",
                "prossima", "successiva", "canzone", "dopo", "salta", "la", "cambia",
                "precedente", "prima", "torna", "alla",
                "pausa", "ferma", "tutto", "silenzio",
                "volume", "a", "al", "alza", "abbassa",
                "uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove", "dieci",
                "undici", "dodici", "quindici", "venti", "venticinque", "trenta",
                "quaranta", "quarantacinque", "cinquanta", "sessanta", "settanta",
                "ottanta", "novanta", "cento",
                "[unk]"
            ))
        }.distinct().joinToString(", ", prefix = "[", postfix = "]") { "\"$it\"" }
    }
}