package com.example.localvoice.stt

/**
 * Come WakeWordEngine, anche questa interfaccia è callback-based: Vosk's
 * SpeechService gestisce da sé il proprio AudioRecord (con endpointing
 * automatico basato sul silenzio, integrato nel motore di riconoscimento),
 * quindi non abbiamo più bisogno di alimentarlo noi chunk per chunk né di
 * usare il nostro VAD per capire quando il parlato finisce.
 */
interface SttEngine {
    fun load()

    /**
     * Avvia l'ascolto per catturare UN comando. [onFinalResult] riceve il
     * testo riconosciuto (stringa vuota se timeout/nessun parlato) — viene
     * invocato UNA sola volta per ogni chiamata a startListening().
     */
    fun startListening(onFinalResult: (String) -> Unit)

    /** Ferma l'ascolto anticipatamente (es. timeout di sicurezza esterno). */
    fun stopListening()

    fun release()
}

/**
 * Implementazione placeholder — ritorna sempre stringa vuota, quindi ogni
 * comando risulterà "non riconosciuto". Usala solo per testare il resto
 * della pipeline prima di configurare il modello Vosk reale.
 */
class NoopSttEngine : SttEngine {
    override fun load() { /* no-op */ }
    override fun startListening(onFinalResult: (String) -> Unit) {
        onFinalResult("")
    }
    override fun stopListening() { /* no-op */ }
    override fun release() { /* no-op */ }
}
