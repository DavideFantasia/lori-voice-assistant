package com.example.localvoice.audio

/**
 * Interfaccia per il motore di wake-word.
 *
 * A differenza della versione precedente (pensata per Porcupine low-level,
 * che riceveva chunk audio pre-filtrati dal nostro VAD), questa è
 * callback-based: l'engine gestisce da SOLO il proprio AudioRecord in
 * ascolto continuo e ci notifica quando la keyword scatta. Questo perché
 * openWakeWord (la libreria che usiamo ora) è pensata per l'ascolto
 * continuo autonomo, non per ricevere frame esterni — e va bene così dal
 * punto di vista della batteria, perché il modello stesso è leggero e
 * pensato apposta per girare senza sosta.
 *
 * Il nostro VAD (VadGate) resta comunque nella cascata, ma si sposta a
 * governare SOLO la fase successiva: capire quando il comando pronunciato
 * dopo la keyword è finito (silenzio prolungato), non più se svegliare il
 * wake-word engine.
 */
interface WakeWordEngine {
    /** Inizializza il modello. Va chiamato una volta, prima di startListening(). */
    fun load()

    /**
     * Inizia l'ascolto continuo. [onDetected] viene invocato (su un thread
     * di background, non quello UI) ogni volta che la keyword è rilevata.
     */
    fun startListening(onDetected: () -> Unit)

    /**
     * Ferma l'ascolto senza rilasciare le risorse — usato per liberare
     * temporaneamente il microfono mentre catturiamo il comando dopo la
     * keyword, poi si richiama startListening() per riprendere.
     */
    fun stopListening()

    /** Rilascia tutte le risorse. Dopo questa chiamata serve un nuovo load(). */
    fun release()
}

/**
 * Implementazione placeholder — non rileva mai nulla. Utile per continuare
 * a testare il resto della pipeline (tramite il pulsante "Simula wake-word"
 * in MainActivity) prima di configurare un vero modello .onnx della keyword.
 */
class NoopWakeWordEngine : WakeWordEngine {
    override fun load() { /* no-op */ }
    override fun startListening(onDetected: () -> Unit) { /* non rileva mai nulla */ }
    override fun stopListening() { /* no-op */ }
    override fun release() { /* no-op */ }
}
