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

class VoskSttEngine(
    private val context: Context,
    private val sharedMic: SharedAudioSource
) : SttEngine {

    private var model: Model? = null
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var captureJob: kotlinx.coroutines.Job? = null

    override fun load() {
        if (cachedModel != null) {
            model = cachedModel
            Log.d(TAG, "Modello Vosk recuperato istantaneamente dalla cache in RAM")
            return
        }

        StorageService.unpack(
            context,
            MODEL_ASSET_PATH,
            "model",
            { loadedModel: Model ->
                cachedModel = loadedModel
                model = loadedModel
                Log.d(TAG, "Modello Vosk caricato correttamente dal disco")
            },
            { exception: IOException ->
                Log.e(TAG, "Impossibile caricare il modello Vosk da assets", exception)
            }
        )
    }

    override fun startListening(onFinalResult: (String) -> Unit) {
        val m = model
        if (m == null) {
            Log.w(TAG, "startListening chiamato ma il modello non è pronto")
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
        // 1. Creiamo ENTRAMBI i motori
        val recognizerGrammar = Recognizer(model, SAMPLE_RATE, GRAMMAR_JSON)
        val recognizerOpen = Recognizer(model, SAMPLE_RATE)

        val vad = VadGate()
        val preRoll = ArrayDeque<ByteArray>()

        var resultText = ""
        var useOpenMode = false // Flag per lo switch dinamico

        try {
            val buffer = ByteArray(CHUNK_SIZE_BYTES)
            var silenceChunks = 0
            var waitChunks = 0
            var totalChunks = 0
            var speechStarted = false
            val chunkMs = (CHUNK_SIZE_BYTES / 2) * 1000 / SAMPLE_RATE.toInt()

            com.example.localvoice.VoiceUIState.reset()

            while (currentCoroutineContext().isActive) {
                val read = sharedMic.readChunk(buffer)
                if (read <= 0) continue

                val chunk = buffer.copyOf(read)
                val pcm = toShortArray(chunk, read)

                val rms = rmsOf(pcm)
                val normalizedVolume = ((rms / 10000.0) * 1.5).toFloat().coerceIn(0f, 1f)
                com.example.localvoice.VoiceUIState.audioVolumeFlow.value = normalizedVolume

                val voice = vad.isVoiceLikely(pcm)
                totalChunks++

                if (!speechStarted) {
                    preRoll.addLast(chunk)
                    if (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()

                    if (voice) {
                        speechStarted = true
                        silenceChunks = 0
                        // Alimentiamo entrambi i motori con il pre-roll
                        for (p in preRoll) {
                            recognizerGrammar.acceptWaveForm(p, p.size)
                            recognizerOpen.acceptWaveForm(p, p.size)
                        }
                        preRoll.clear()
                        useOpenMode = updatePartialsAndCheckSwitch(recognizerGrammar, recognizerOpen, chunk, useOpenMode)
                    } else {
                        waitChunks++
                        if (waitChunks * chunkMs > SPEECH_START_TIMEOUT_MS) break
                        continue
                    }
                } else {
                    // Alimentiamo entrambi i motori in tempo reale
                    useOpenMode = updatePartialsAndCheckSwitch(recognizerGrammar, recognizerOpen, chunk, useOpenMode)

                    if (voice) silenceChunks = 0 else silenceChunks++
                    if (silenceChunks * chunkMs > SILENCE_END_MS) break
                }
                if (totalChunks * chunkMs > MAX_TOTAL_MS) break
            }

            // 2. Estraiamo il risultato finale dal motore appropriato
            resultText = if (useOpenMode) {
                extractText(recognizerOpen.finalResult)
            } else {
                extractText(recognizerGrammar.finalResult)
            }

            com.example.localvoice.VoiceUIState.transcriptionFlow.value = resultText
            com.example.localvoice.VoiceUIState.audioVolumeFlow.value = 0f
            Log.d(TAG, "Testo riconosciuto (OpenMode=$useOpenMode): '$resultText'")

        } finally {
            // Chiudiamo entrambi per liberare memoria
            recognizerGrammar.close()
            recognizerOpen.close()
        }

        return resultText
    }

    private fun updatePartialsAndCheckSwitch(
        recognizerGrammar: Recognizer,
        recognizerOpen: Recognizer,
        chunk: ByteArray,
        currentlyOpen: Boolean
    ): Boolean {
        recognizerGrammar.acceptWaveForm(chunk, chunk.size)
        recognizerOpen.acceptWaveForm(chunk, chunk.size)

        var isNowOpen = currentlyOpen
        val activeRecognizer = if (isNowOpen) recognizerOpen else recognizerGrammar

        val partialJson = activeRecognizer.partialResult
        val partialText = JSONObject(partialJson).optString("partial", "")

        if (partialText.isNotEmpty()) {
            com.example.localvoice.VoiceUIState.transcriptionFlow.value = partialText

            // Controlla se scatta la trappola per aprire il vocabolario
            if (!isNowOpen) {
                val triggerWords = listOf("cerca", "trova", "metti", "suona", "riproduci", "ascoltiamo")
                if (triggerWords.any { partialText.contains(it) }) {
                    Log.d(TAG, "Parola chiave aperta rilevata: switch al vocabolario libero!")
                    isNowOpen = true
                }
            }
        }
        return isNowOpen
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
        private const val CHUNK_SIZE_BYTES = 3200
        private const val SPEECH_START_TIMEOUT_MS = 3000
        private const val SILENCE_END_MS = 1200
        private const val MAX_TOTAL_MS = 8000
        private const val PRE_ROLL_CHUNKS = 3

        private var cachedModel: Model? = null

        private val GRAMMAR_JSON: String = buildList {
            addAll(listOf(
                "cerca", "trova", "su", "internet", "online", "sul", "web",
                "imposta", "avvia", "metti", "fai", "partire", "crea", "punta",
                "un", "una", "timer", "sveglia", "di", "secondi", "secondo", "minuti", "minuto", "ore", "ora",
                "accendi", "spegni", "attiva", "disattiva", "il", "bluetooth",
                "prossima", "successiva", "canzone", "traccia", "dopo", "salta", "la", "cambia", "vai", "avanti",
                "precedente", "prima", "torna", "indietro", "alla",
                "pausa", "ferma", "tutto", "musica", "stoppa", "silenzio",
                "suona", "riproduci", "ascoltiamo",
                "volume", "porta", "a", "al", "alza", "abbassa", "aumenta", "diminuisci",
                "uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove", "dieci",
                "undici", "dodici", "quindici", "venti", "venticinque", "trenta",
                "quaranta", "quarantacinque", "cinquanta", "sessanta", "settanta",
                "ottanta", "novanta", "cento",
                "[unk]"
            ))
        }.distinct().joinToString(", ", prefix = "[", postfix = "]") { "\"$it\"" }
    }
}