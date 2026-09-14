package com.example.localvoice.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

/**
 * Test JVM puri (nessuna dipendenza Android, nessun device/emulatore
 * necessario): VadGate lavora solo su ShortArray, quindi possiamo
 * generare segnali sintetici e verificarne il comportamento in isolamento.
 *
 * Esecuzione: click sull'icona verde a fianco della classe/metodo in
 * Android Studio, oppure `./gradlew testDebugUnitTest` da terminale.
 */
class VadGateTest {

    private val sampleRate = 16000

    // --- Generatori di segnale sintetico -----------------------------------

    private fun silence(sizeSamples: Int): ShortArray = ShortArray(sizeSamples) { 0 }

    /** Rumore bianco a bassa ampiezza, per simulare il "fruscio" di fondo. */
    private fun whiteNoise(sizeSamples: Int, amplitude: Int): ShortArray {
        val rnd = Random(42) // seed fisso: test deterministico e ripetibile
        return ShortArray(sizeSamples) { (rnd.nextInt(-amplitude, amplitude)).toShort() }
    }

    /** Tono puro (sinusoide), utile a simulare un suono/voce di ampiezza nota. */
    private fun tone(sizeSamples: Int, amplitude: Int, freqHz: Double): ShortArray {
        return ShortArray(sizeSamples) { i ->
            (amplitude * sin(2.0 * Math.PI * freqHz * i / sampleRate)).toInt().toShort()
        }
    }

    // --- Casi base -----------------------------------------------------------

    @Test
    fun `silenzio non deve attivare il VAD`() {
        val vad = VadGate(energyThreshold = 500.0)
        val chunk = silence(480) // 30ms a 16kHz
        assertFalse(vad.isVoiceLikely(chunk))
    }

    @Test
    fun `tono forte deve attivare il VAD`() {
        val vad = VadGate(energyThreshold = 500.0)
        val chunk = tone(480, amplitude = 8000, freqHz = 300.0) // ampiezza tipica voce
        assertTrue(vad.isVoiceLikely(chunk))
    }

    @Test
    fun `rumore di fondo debole non deve attivare il VAD`() {
        val vad = VadGate(energyThreshold = 500.0)
        val chunk = whiteNoise(480, amplitude = 50) // fruscio leggero
        assertFalse(vad.isVoiceLikely(chunk))
    }

    // --- Comportamento adattivo -----------------------------------------------

    @Test
    fun `dopo adattamento a rumore di fondo, lo stesso rumore resta sotto soglia`() {
        val vad = VadGate(energyThreshold = 500.0, adaptiveNoiseFloor = true)
        val backgroundNoise = whiteNoise(480, amplitude = 300)

        // Alimenta ripetutamente rumore di fondo stabile: il noise floor si adatta.
        repeat(50) { vad.isVoiceLikely(backgroundNoise) }

        // Lo stesso identico rumore di fondo non deve risultare "voce".
        assertFalse(vad.isVoiceLikely(backgroundNoise))
    }

    @Test
    fun `dopo adattamento a rumore di fondo, un tono sopra la soglia relativa attiva comunque il VAD`() {
        val vad = VadGate(energyThreshold = 500.0, adaptiveNoiseFloor = true)
        val backgroundNoise = whiteNoise(480, amplitude = 300)
        repeat(50) { vad.isVoiceLikely(backgroundNoise) }

        // Un tono nettamente più forte del noise floor deve sempre attivare il VAD,
        // anche se il rumore di fondo si è "alzato" nel frattempo.
        val loudTone = tone(480, amplitude = 10000, freqHz = 250.0)
        assertTrue(vad.isVoiceLikely(loudTone))
    }

    @Test
    fun `buffer vuoto non deve mai far crashare il VAD`() {
        val vad = VadGate()
        assertFalse(vad.isVoiceLikely(ShortArray(0)))
    }
}
