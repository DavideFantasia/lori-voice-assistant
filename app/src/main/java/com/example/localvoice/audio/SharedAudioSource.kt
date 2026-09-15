package com.example.localvoice.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Unico punto di apertura del microfono per la fase di CATTURA COMANDO
 * (quella dopo il wake-word, prima gestita da VoskSttEngine con un suo
 * AudioRecord dedicato).
 *
 * PERCHÉ QUESTA CLASSE: in questa fase vogliamo che sia il VAD (VadGate)
 * a decidere quando l'utente ha iniziato/smesso di parlare, e che Vosk
 * riceva i campioni PCM da quello stesso stream — un solo AudioRecord,
 * un solo buffer, letto una volta per ciclo e condiviso tra le due
 * logiche (VAD e riconoscimento). Prima ognuna apriva/avrebbe aperto il
 * proprio AudioRecord, che su molti dispositivi va in conflitto con
 * effetti come AEC/NS attivi su un secondo client del microfono.
 *
 * NOTA: questo NON copre lo stadio del wake-word. openWakeWord gestisce
 * il proprio AudioRecord internamente e non accetta frame esterni (vedi
 * commento in WakeWordEngine.kt) — quindi quello resta, per costrizione
 * della libreria, un secondo microfono indipendente. Ma i DUE consumatori
 * di QUESTA fase (VAD e Vosk) non hanno più motivo di essere separati, e
 * ora condividono davvero lo stesso buffer.
 */
class SharedAudioSource(
    private val sampleRate: Int = 16000,
    private val chunkSizeBytes: Int = 3200 // ~100ms a 16kHz mono, PCM 16-bit
) {
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    /** Apre il microfono e agganciа AEC/NS. Va chiamato una volta per ogni cattura. */
    fun open() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, chunkSizeBytes * 4)
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord non inizializzato correttamente (stato=${r.state})")
        }

        // Possibile solo perché QUESTA classe è l'unica proprietaria
        // dell'AudioRecord in questa fase — stesso motivo per cui prima
        // era VoskSttEngine ad aprirlo direttamente.
        aec = if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true }
        } else null
        ns = if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true }
        } else null

        r.startRecording()
        record = r
        Log.d(TAG, "Microfono condiviso aperto: AEC disponibile=${aec != null}, NS disponibile=${ns != null}")
    }

    /**
     * Legge un chunk (bloccante finché non ci sono abbastanza campioni).
     * @return numero di byte letti in [buffer] (0 o negativo se nessun dato / errore).
     */
    fun readChunk(buffer: ByteArray): Int {
        val r = record ?: return -1
        return r.read(buffer, 0, buffer.size)
    }

    /** Ferma e rilascia tutto. Dopo questa chiamata serve un nuovo open(). */
    fun close() {
        try {
            record?.stop()
        } catch (e: IllegalStateException) {
            // stop() chiamato su un record già fermo/non avviato, ignorabile
        }
        record?.release()
        aec?.release()
        ns?.release()
        record = null
        aec = null
        ns = null
    }

    companion object {
        private const val TAG = "LocalVoiceSharedMic"
    }
}
