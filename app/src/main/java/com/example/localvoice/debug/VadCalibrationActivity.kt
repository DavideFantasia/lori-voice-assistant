package com.example.localvoice.debug

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Activity di SOLA DIAGNOSTICA — non fa parte del flusso utente finale,
 * serve a te in fase di sviluppo per rispondere alla domanda:
 * "qual è l'RMS del silenzio nella mia stanza? E della mia voce a distanza
 * normale? Quale soglia ha senso su QUESTO dispositivo?"
 *
 * Uso: lanciala dal device, resta in silenzio 5-10 secondi osservando i
 * valori loggati (tag "VadCalibration"), poi parla normalmente da ~1 metro
 * e confronta. Imposta energyThreshold a metà strada tra i due valori medi
 * che osservi, non a un numero scelto a caso.
 *
 * Premendo "indietro" (o comunque uscendo dall'Activity) il loop di ascolto
 * e il logging si fermano correttamente: il job e l'AudioRecord sono campi
 * dell'Activity, cancellati/rilasciati in onDestroy().
 */
class VadCalibrationActivity : Activity() {

    private val sampleRate = 16000
    private val chunkSize = sampleRate * 30 / 1000 // 30ms
    private var pendingTextView: TextView? = null

    private var audioRecord: AudioRecord? = null
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        val textView = pendingTextView ?: return
        if (granted) {
            startCalibration(textView)
        } else {
            textView.text = "Permesso microfono negato. Concedilo da Impostazioni per usare la calibrazione."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val textView = TextView(this).apply { textSize = 20f; setPadding(32, 64, 32, 32) }
        setContentView(textView)
        pendingTextView = textView

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            textView.text = "In attesa del permesso microfono..."
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }

        startCalibration(textView)
    }

    override fun onDestroy() {
        // Fondamentale: senza questo, il loop di ascolto e il logging
        // continuerebbero a girare in background anche dopo aver lasciato
        // la schermata, tenendo occupato il microfono inutilmente.
        job.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.d("VadCalibration", "Calibrazione fermata, microfono rilasciato")
        super.onDestroy()
    }

    private fun startCalibration(textView: TextView) {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, chunkSize * 4)
        )
        audioRecord = record
        record.startRecording()

        scope.launch {
            val buffer = ShortArray(chunkSize)
            // Media mobile per leggibilità, il valore istantaneo salta troppo.
            var runningAvg = 0.0

            while (isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                var sumSquares = 0.0
                for (i in 0 until read) sumSquares += buffer[i].toDouble() * buffer[i].toDouble()
                val rms = sqrt(sumSquares / read)

                runningAvg = runningAvg * 0.9 + rms * 0.1

                Log.d("VadCalibration", "RMS istantaneo=${rms.toInt()}  media mobile=${runningAvg.toInt()}")
                launch(Dispatchers.Main) {
                    textView.text = "RMS: ${rms.toInt()}\nMedia: ${runningAvg.toInt()}\n\n" +
                        "Resta in silenzio, poi parla normalmente.\n" +
                        "Segna il valore medio in entrambi i casi.\n\n" +
                        "Premi Indietro per fermare."
                }
            }
        }
    }
}
