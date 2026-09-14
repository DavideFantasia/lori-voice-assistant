package com.example.localvoice.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
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
import kotlin.math.sqrt

/**
 * Wrapper su Vosk Android SDK (Apache 2.0, alphacephei) — API LOW-LEVEL
 * (Recognizer.acceptWaveForm), non più SpeechService.
 *
 * PERCHÉ IL CAMBIO: SpeechService gestiva da sé l'AudioRecord internamente,
 * senza esporre il suo audioSessionId — impossibile quindi agganciarci
 * AcousticEchoCanceler/NoiseSuppressor (richiedono l'audioSessionId
 * dell'AudioRecord specifico a cui applicarsi). Aprendo noi l'AudioRecord,
 * possiamo attaccarci questi effetti per attenuare musica/rumore di
 * sottofondo durante la cattura del comando.
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
class VoskSttEngine(private val context: Context) : SttEngine {

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

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE.toInt(), AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE.toInt(),
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, CHUNK_SIZE_BYTES * 4)
        )

        // Possibile SOLO perché apriamo noi l'AudioRecord — con SpeechService
        // non avevamo modo di ottenere questo audioSessionId.
        val aec = if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
        } else null
        val ns = if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
        } else null
        Log.d(TAG, "Cattura comando: AEC disponibile=${aec != null}, NS disponibile=${ns != null}")

        var resultText = ""
        try {
            record.startRecording()

            val buffer = ByteArray(CHUNK_SIZE_BYTES)
            var silenceChunks = 0
            var waitChunks = 0
            var totalChunks = 0
            var speechStarted = false
            val chunkMs = (CHUNK_SIZE_BYTES / 2) * 1000 / SAMPLE_RATE.toInt() // 2 byte per campione 16-bit

            // reset UI di trascrizione
            com.example.localvoice.VoiceUIState.reset()

            while (currentCoroutineContext().isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                // 1. Calcola l'RMS per l'indicatore audio lineare
                var sumSquares = 0.0
                var i = 0
                while (i + 1 < read) {
                    val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                    sumSquares += sample.toDouble() * sample.toDouble()
                    i += 2
                }
                val count = read / 2
                val rms = if (count > 0) sqrt(sumSquares / count) else 0.0

                // Normalizza il volume in un range 0f-1f.
                // Puoi abbassare o alzare il valore "10000.0" se la barra è troppo sensibile/piatta.
                val normalizedVolume = ((rms / 10000.0) * 1.5).toFloat().coerceIn(0f, 1f)
                com.example.localvoice.VoiceUIState.audioVolumeFlow.value = normalizedVolume

                // Controllo del Voice Activity (preesistente)
                val voice = rms > ENERGY_THRESHOLD

                // 2. Invia i buffer a Vosk e ottieni i risultati parziali
                val isFinal = recognizer.acceptWaveForm(buffer, read)
                totalChunks++

                // Estrai il testo intermedio e aggiorna la UI
                if (!isFinal) {
                    val partialJson = recognizer.partialResult
                    val partialText = JSONObject(partialJson).optString("partial", "")
                    if (partialText.isNotEmpty()) {
                        com.example.localvoice.VoiceUIState.transcriptionFlow.value = partialText
                    }
                }

                // Controllo dei timeout
                if (!speechStarted) {
                    if (voice) {
                        speechStarted = true
                        silenceChunks = 0
                    } else {
                        waitChunks++
                        if (waitChunks * chunkMs > SPEECH_START_TIMEOUT_MS) break
                    }
                } else {
                    if (voice) silenceChunks = 0 else silenceChunks++
                    if (silenceChunks * chunkMs > SILENCE_END_MS) break
                }

                if (totalChunks * chunkMs > MAX_TOTAL_MS) break
            }

            // 3. Estrai e visualizza il testo finale conclusivo
            resultText = extractText(recognizer.finalResult)
            com.example.localvoice.VoiceUIState.transcriptionFlow.value = resultText

            // (Opzionale) Azzera il volume quando l'ascolto termina
            com.example.localvoice.VoiceUIState.audioVolumeFlow.value = 0f

            Log.d(TAG, "Testo riconosciuto: '$resultText'")

        } finally {
            record.stop()
            record.release()
            aec?.release()
            ns?.release()
            recognizer.close()
        }

        return resultText
    }

    /** VAD energy-based minimale, usato SOLO per capire quando l'utente ha
     *  smesso di parlare — non più per decidere se svegliare un motore. */
    private fun isLikelyVoice(buffer: ByteArray, length: Int): Boolean {
        var sumSquares = 0.0
        var count = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            sumSquares += sample.toDouble() * sample.toDouble()
            i += 2
            count++
        }
        if (count == 0) return false
        val rms = sqrt(sumSquares / count)
        return rms > ENERGY_THRESHOLD
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
        private const val ENERGY_THRESHOLD = 600.0

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
