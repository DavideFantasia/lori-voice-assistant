package com.example.localvoice.audio

import android.content.Context
import android.util.Log
import com.rementia.openwakeword.lib.model.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.rementia.openwakeword.lib.WakeWordEngine as OwwEngine

/**
 * Wrapper sulla libreria xyz.rementia:openwakeword (Apache 2.0, Maven
 * Central) — porta Kotlin/ONNX Runtime di openWakeWord per Android.
 * Interamente locale: nessuna chiave, nessun account, nessuna connessione
 * di rete richiesta.
 *
 * COSA DEVI PROCURARTI TU:
 * Un file .onnx della TUA parola di attivazione in italiano. openWakeWord
 * non ha modelli pre-addestrati ufficiali in italiano (i modelli pronti
 * come "hey_jarvis" sono in inglese) — vanno addestrati con il notebook
 * Colab ufficiale: https://colab.research.google.com/drive/1q1oe2zOyZp7UsB3jJiQ1IFn8z5YfjwEb
 * (genera dati di training sintetici via text-to-speech, gratuito, ~1-2 ore
 * di elaborazione su Colab). Esporta il risultato in formato ONNX e mettilo
 * in app/src/main/assets/ (root, non una sottocartella — vedi
 * melspectrogram.onnx ed embedding_model.onnx già inclusi lì).
 *
 * Nel frattempo è incluso hey_jarvis_test_en.onnx (inglese, licenza
 * CC-BY-NC-SA — va bene per test personali, non per uso commerciale) per
 * verificare che l'intera pipeline funzioni prima di allenare la tua
 * keyword italiana. Passa il nome del file al costruttore.
 */
class OpenWakeWordEngine(
    private val context: Context,
    private val keywordAssetFileName: String,
    // Nel log che hai mandato, uno score di successo era 0.76 — ma la voce
    // umana varia molto tra un'elocuzione e l'altra, ed è plausibile che
    // pronunce genuine scendano ben sotto 0.5, venendo scartate in silenzio.
    // Valore abbassato per aumentare il recall (meno "wake-word ignorata"),
    // al costo di qualche falso positivo in più — costa poco: un trigger
    // spurio produce solo un "non ho capito il comando" innocuo. Osserva i
    // punteggi loggati da questa classe per tarare il valore giusto per la
    // tua voce/ambiente.
    private val threshold: Float = 0.25f
) : WakeWordEngine {

    private var engine: OwwEngine? = null
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var onDetectedCallback: (() -> Unit)? = null

    override fun load() {
        if (engine != null) return
        val model = WakeWordModel(
            name = "keyword",
            modelPath = keywordAssetFileName,
            threshold = threshold
        )
        val e = OwwEngine(
            context = context,
            models = listOf(model),
            scope = scope
            // detectionMode non specificato: uso il default della libreria
            // (DetectionMode.SINGLE_BEST, confermato dal sorgente reale di
            // WakeWordEngine.kt) — il tipo vive in
            // com.rementia.openwakeword.lib.model.DetectionMode, non nel
            // package principale come lasciava intendere la documentazione;
            // evito l'import esplicito così non serve indovinare il path
            // giusto per un parametro che comunque non ci serve cambiare.
        )
        engine = e

        // Un solo collector per tutta la vita dell'engine: start()/stop()
        // mettono in pausa/riprendono la cattura audio interna, non
        // ri-sottoscrivono il Flow ogni volta.
        scope.launch {
            e.detections.collect {
                Log.d(TAG, "Wake-word rilevata: score=${it.score}")
                onDetectedCallback?.invoke()
            }
        }
        Log.d(TAG, "openWakeWord caricato con keyword asset '$keywordAssetFileName'")
    }

    override fun startListening(onDetected: () -> Unit) {
        onDetectedCallback = onDetected
        engine?.start()
    }

    override fun stopListening() {
        engine?.stop()
    }

    override fun release() {
        engine?.release()
        engine = null
        onDetectedCallback = null
        job.cancel()
    }

    companion object {
        private const val TAG = "LocalVoiceOWW"
    }
}
