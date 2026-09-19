package com.example.localvoice.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer

/**
 * Reimplementazione manuale della pipeline di openWakeWord (mel-spectrogram
 * → embedding → classificatore), usando ONNX Runtime direttamente sui
 * asset "melspectrogram.onnx" + "embedding_model.onnx" + "hey_lori_it.onnx"
 * — GLI STESSI file già usati da OpenWakeWordEngine/libreria rementia.
 *
 * PERCHÉ QUESTA CLASSE ESISTE: la libreria xyz.rementia:openwakeword gestisce
 * il proprio AudioRecord internamente e non accetta frame audio esterni (vedi
 * commento in WakeWordEngine.kt) — quindi non può leggere dal nostro
 * [SharedAudioSource]. Questa classe fa lo stesso identico calcolo (stessi
 * modelli, stessa matematica → stessa accuratezza) ma accetta chunk passati
 * da fuori con [processChunk], permettendo di condividere UN SOLO microfono
 * fra VAD, keyword-spotting e Vosk.
 *
 * ALGORITMO (verificato eseguendo i tuoi .onnx reali, non solo da doc):
 * 1. mel: finestra di 1760 campioni int16 (480 di lookback + 1280 nuovi) →
 *    8 frame mel da 32 valori; si tengono solo gli ULTIMI 5 (quelli "nuovi",
 *    corrispondenti ai 1280 campioni nuovi — i primi 3 sono ridondanti col
 *    giro precedente). Si applica poi mel = mel/10 + 2 (trasformazione di
 *    riferimento di openWakeWord).
 * 2. embedding: appena si hanno 76 frame mel in buffer, si passano come
 *    finestra [1,76,32,1] → 1 vettore embedding a 96 dimensioni.
 * 3. classificatore: appena si hanno 16 vettori embedding in buffer, si
 *    passano come [1,16,96] → score sigmoid diretto in [0,1].
 *
 * NB SUL CODICE ONNX RUNTIME: non ho potuto compilare/testare questa classe
 * su un vero dispositivo Android (l'estrazione dei tensori di output dal
 * `OrtSession.Result` — i cast a array annidati — è la parte più fragile fra
 * versioni diverse di onnxruntime-android). Se il cast fallisce a runtime o
 * in compilazione, mandami l'errore/lo stack trace: si sistema mirato.
 * Serve la dipendenza (stessa versione già usata transitivamente dalla
 * libreria rementia):
 *   implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
 */
class LocalKeywordSpotter(
    context: Context,
    melAsset: String = "melspectrogram.onnx",
    embAsset: String = "embedding_model.onnx",
    classifierAsset: String,
    private val threshold: Float = 0.05f,
    private val cooldownMs: Long = 1200L
) : KeywordSpotter {

    private val env = OrtEnvironment.getEnvironment()
    private val melSession: OrtSession = env.createSession(context.assets.open(melAsset).readBytes())
    private val embSession: OrtSession = env.createSession(context.assets.open(embAsset).readBytes())
    private val clfSession: OrtSession = env.createSession(context.assets.open(classifierAsset).readBytes())

    // Stato della sliding window, persistente fra chiamate a processChunk().
    private var lookback = ShortArray(LOOKBACK_SAMPLES) // inizialmente silenzio
    private val rawAccumulator = ArrayDeque<Short>()
    private val melBuffer = ArrayDeque<FloatArray>() // ognuno 32 valori, max MEL_WINDOW
    private val embBuffer = ArrayDeque<FloatArray>() // ognuno 96 valori, max EMB_WINDOW

    private var lastDetectionAt = 0L

    /**
     * Alimenta la pipeline con un chunk di campioni PCM 16-bit (dimensione
     * qualsiasi — vengono accumulati e consumati a passi di 1280). Ritorna
     * l'ultimo score del classificatore se in questa chiamata è scattata
     * almeno una nuova inferenza completa, altrimenti null (non c'è ancora
     * abbastanza contesto accumulato).
     */
    override fun processChunk(pcm: ShortArray): Float? {
        for (s in pcm) rawAccumulator.addLast(s)

        var lastScore: Float? = null
        while (rawAccumulator.size >= NEW_SAMPLES) {
            val newSamples = ShortArray(NEW_SAMPLES) { rawAccumulator.removeFirst() }
            val window = ShortArray(LOOKBACK_SAMPLES + NEW_SAMPLES)
            lookback.copyInto(window, 0)
            newSamples.copyInto(window, LOOKBACK_SAMPLES)

            try {
                val melFrames = runMel(window)
                for (f in melFrames) {
                    melBuffer.addLast(f)
                    if (melBuffer.size > MEL_WINDOW) melBuffer.removeFirst()
                }

                if (melBuffer.size >= MEL_WINDOW) {
                    val embVec = runEmbedding(melBuffer.toList())
                    embBuffer.addLast(embVec)
                    if (embBuffer.size > EMB_WINDOW) embBuffer.removeFirst()

                    if (embBuffer.size >= EMB_WINDOW) {
                        lastScore = runClassifier(embBuffer.toList())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore nella pipeline di inferenza ONNX", e)
            }

            lookback = window.copyOfRange(window.size - LOOKBACK_SAMPLES, window.size)
        }
        return lastScore
    }

    override fun checkDetection(score: Float?): Boolean {
        if (score == null || score < threshold) return false
        val now = System.currentTimeMillis()
        if (now - lastDetectionAt < cooldownMs) return false
        lastDetectionAt = now
        Log.d(TAG, "Keyword rilevata: score=$score")
        return true
    }

    private fun runMel(window: ShortArray): List<FloatArray> {
        val floatData = FloatArray(window.size) { window[it].toFloat() }
        val shape = longArrayOf(1, floatData.size.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(floatData), shape).use { input ->
            melSession.run(mapOf(melSession.inputNames.first() to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<Array<Array<FloatArray>>> // [1,1,time,32]
                val timeFrames = out[0][0] // Array<FloatArray>: [time][32]
                val scaled = timeFrames.map { row -> FloatArray(32) { i -> row[i] / 10f + 2f } }
                return scaled.takeLast(NEW_MEL_FRAMES)
            }
        }
    }

    private fun runEmbedding(mel76: List<FloatArray>): FloatArray {
        val floatData = FloatArray(MEL_WINDOW * 32)
        for (t in 0 until MEL_WINDOW) {
            for (b in 0 until 32) floatData[t * 32 + b] = mel76[t][b]
        }
        val shape = longArrayOf(1, MEL_WINDOW.toLong(), 32, 1)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(floatData), shape).use { input ->
            embSession.run(mapOf(embSession.inputNames.first() to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<Array<Array<FloatArray>>> // [1,1,1,96]
                return out[0][0][0]
            }
        }
    }

    private fun runClassifier(emb16: List<FloatArray>): Float {
        val floatData = FloatArray(EMB_WINDOW * 96)
        for (t in 0 until EMB_WINDOW) {
            for (b in 0 until 96) floatData[t * 96 + b] = emb16[t][b]
        }
        val shape = longArrayOf(1, EMB_WINDOW.toLong(), 96)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(floatData), shape).use { input ->
            clfSession.run(mapOf(clfSession.inputNames.first() to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<FloatArray> // [1,1]
                return out[0][0]
            }
        }
    }

    override fun reset() {
        rawAccumulator.clear()
        melBuffer.clear() // In teoria si sovrascrivono essendo una window circolare
        embBuffer.clear() // Resettare così causa solo un cold start ad ogni frase
        lookback = ShortArray(LOOKBACK_SAMPLES)
    }

    override fun release() {
        melSession.close()
        embSession.close()
        clfSession.close()
    }

    companion object {
        private const val TAG = "LocalVoiceKWS"
        private const val LOOKBACK_SAMPLES = 480
        private const val NEW_SAMPLES = 1280
        private const val NEW_MEL_FRAMES = 5
        private const val MEL_WINDOW = 76
        private const val EMB_WINDOW = 16
    }
}

/** Interfaccia comune, cosi il servizio non deve sapere se il keyword-spotter è reale o placeholder. */
interface KeywordSpotter {
    /** Alimenta la pipeline con un chunk PCM. Ritorna l'ultimo score se una nuova inferenza è scattata. */
    fun processChunk(pcm: ShortArray): Float?
    /** true se lo score indica una rilevazione valida (soglia + cooldown). */
    fun checkDetection(score: Float?): Boolean
    fun reset()
    fun release()
}

/** Placeholder — non rileva mai nulla. Usato se l'asset del classificatore manca. */
class NoopKeywordSpotter : KeywordSpotter {
    override fun processChunk(pcm: ShortArray): Float? = null
    override fun checkDetection(score: Float?): Boolean = false
    override fun reset() {}
    override fun release() { /* no-op */ }
}
