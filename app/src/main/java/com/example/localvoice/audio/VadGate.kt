package com.example.localvoice.audio

/**
 * Primo stadio della cascata: un VAD (Voice Activity Detection) leggerissimo,
 * puramente energetico, che decide se vale la pena svegliare il wake-word engine.
 *
 * Questo è lo stadio che gira PIÙ SPESSO (su ogni chunk audio), quindi deve
 * essere quasi gratis in termini di CPU. Un VAD a energia (RMS + soglia adattiva)
 * costa pochissimo ed è sufficiente per filtrare il silenzio, che è la stragrande
 * maggioranza del tempo in ascolto continuo.
 *
 * TODO(integrazione): se vuoi maggiore precisione (es. distinguere voce da
 * rumore di fondo tipo TV/musica) sostituisci con Silero VAD (modello ONNX
 * ~1MB, inferenza comunque molto economica, ~1ms per chunk su CPU mobile).
 */
class VadGate(
    private val energyThreshold: Double = 200.0,
    private val adaptiveNoiseFloor: Boolean = true
) {
    private var noiseFloor = energyThreshold / 4
    private val adaptRate = 0.02

    /**
     * @param pcm16 buffer di campioni PCM 16-bit mono (es. 20-30ms di audio)
     * @return true se il buffer contiene probabile attività vocale/sonora
     */
    fun isVoiceLikely(pcm16: ShortArray): Boolean {
        val meanSquare = calculateMeanSquare(pcm16) // Niente radice
        if (adaptiveNoiseFloor) {
            if (meanSquare < noiseFloor * 2) {
                noiseFloor = noiseFloor * (1 - adaptRate) + meanSquare * adaptRate
            }
        }
        // Eleviamo al quadrato l'energyThreshold in fase di confronto
        val threshold = maxOf(energyThreshold * energyThreshold, noiseFloor * 3)
        return meanSquare > threshold
    }

    private fun calculateMeanSquare(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sumSquares = 0.0
        for (s in samples) {
            sumSquares += (s.toDouble() * s.toDouble())
        }
        return sumSquares / samples.size
    }
}
