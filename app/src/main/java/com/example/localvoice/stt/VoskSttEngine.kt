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
    private var recognizerGrammar: Recognizer? = null
    private var recognizerOpen: Recognizer? = null
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var captureJob: kotlinx.coroutines.Job? = null

    override fun load() {
        if (cachedModel != null) {
            model = cachedModel
            recognizerGrammar = cachedRecognizerGrammar
            recognizerOpen = cachedRecognizerOpen
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

                recognizerGrammar = Recognizer(loadedModel, SAMPLE_RATE, GRAMMAR_JSON)
                recognizerOpen = Recognizer(loadedModel, SAMPLE_RATE)

                cachedRecognizerGrammar = recognizerGrammar
                cachedRecognizerOpen = recognizerOpen

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

        recognizerGrammar = null
        recognizerOpen = null

        model = null
        job.cancel()
    }

    private suspend fun captureAndRecognize(model: Model): String {
        val recGrammar = recognizerGrammar ?: return ""
        val recOpen = recognizerOpen ?: return ""

        // Reset istantaneo per pulire l'audio del comando precedente
        recGrammar.reset()
        recOpen.reset()

        val vad = VadGate()
        val preRoll = ArrayDeque<ByteArray>()
        val utteranceBuffer = mutableListOf<ByteArray>()

        var resultText = ""
        var finalCapturedText = "" // testo intercettato
        var useOpenMode = false

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

                    // Alimentiamo il motore a grammatica
                    for (p in preRoll) {
                        utteranceBuffer.add(p)
                        // SALVAVITA: svuota lo stato nativo se Vosk chiude un blocco
                        if (recGrammar.acceptWaveForm(p, p.size)) {
                            finalCapturedText = extractText(recGrammar.result)
                            //recGrammar.result
                        }
                    }
                    preRoll.clear()
                    useOpenMode = checkSwitchToOpen(recGrammar)
                } else {
                    waitChunks++
                    if (waitChunks * chunkMs > SPEECH_START_TIMEOUT_MS) break
                    continue
                }
            } else {
                if (!useOpenMode) {
                    // MODO GRAMMATICA
                    utteranceBuffer.add(chunk)
                    if (recGrammar.acceptWaveForm(chunk, chunk.size)) {
                        finalCapturedText = extractText(recGrammar.result)
                        break
                    }
                    useOpenMode = checkSwitchToOpen(recGrammar)

                    if (useOpenMode) {
                        Log.d(TAG, "Switch al modello aperto! Recupero ${utteranceBuffer.size} chunk audio...")
                        // CATCH-UP SUI CHUNK ACCUMULATI
                        for (c in utteranceBuffer) {
                            if (recOpen.acceptWaveForm(c, c.size)) {
                                finalCapturedText = extractText(recOpen.result) // SALVAVITA: impedisce il SIGSEGV
                            }
                        }
                        utteranceBuffer.clear()
                    }
                } else {
                    // MODO APERTO
                    if (recOpen.acceptWaveForm(chunk, chunk.size)) {
                        finalCapturedText = extractText(recOpen.result)// SALVAVITA
                        break
                    }
                    val partialText = JSONObject(recOpen.partialResult).optString("partial", "")
                    if (partialText.isNotEmpty()) {
                        com.example.localvoice.VoiceUIState.transcriptionFlow.value = partialText
                    }
                }

                if (voice) silenceChunks = 0 else silenceChunks++
                if (silenceChunks * chunkMs > SILENCE_END_MS) break
            }
            if (totalChunks * chunkMs > MAX_TOTAL_MS) break
        }

        // Estrazione testo finale
        resultText = if (finalCapturedText.isNotEmpty()) {
            finalCapturedText
        } else {
            if (useOpenMode) extractText(recOpen.finalResult) else extractText(recGrammar.finalResult)
        }

        com.example.localvoice.VoiceUIState.transcriptionFlow.value = resultText
        com.example.localvoice.VoiceUIState.audioVolumeFlow.value = 0f
        Log.d(TAG, "Testo riconosciuto (OpenMode=$useOpenMode): '$resultText'")

        return resultText
    }

    private fun checkSwitchToOpen(recGrammar: Recognizer): Boolean {
        val partialText = JSONObject(recGrammar.partialResult).optString("partial", "")
        if (partialText.isNotEmpty()) {
            com.example.localvoice.VoiceUIState.transcriptionFlow.value = partialText
            val triggerWords = listOf("cerca", "trova", "metti", "suona", "riproduci", "ascoltiamo")
            if (triggerWords.any { partialText.contains(it) }) {
                return true
            }
        }
        return false
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
        private const val SPEECH_START_TIMEOUT_MS = 2500
        private const val SILENCE_END_MS = 1200
        private const val MAX_TOTAL_MS = 8000
        private const val PRE_ROLL_CHUNKS = 3

        private var cachedModel: Model? = null
        private var cachedRecognizerGrammar: Recognizer? = null
        private var cachedRecognizerOpen: Recognizer? = null

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