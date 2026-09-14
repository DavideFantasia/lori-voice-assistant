package com.example.localvoice.intent

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandParserTest {

    private val parser = CommandParser()

    // --- Timer: verbi -----------------------------------------------------

    @Test
    fun `imposta timer con cifra`() {
        assertEquals(VoiceIntent.SetTimer(120), parser.parse("imposta un timer di 2 minuti"))
    }

    @Test
    fun `avvia timer con numero a parole`() {
        assertEquals(VoiceIntent.SetTimer(120), parser.parse("avvia timer di due minuti"))
    }

    @Test
    fun `metti timer`() {
        assertEquals(VoiceIntent.SetTimer(300), parser.parse("metti un timer di 5 minuti"))
        assertEquals(VoiceIntent.NextTrack, parser.parse("metti la canzone successiva"))
    }

    @Test
    fun `fai partire timer`() {
        assertEquals(VoiceIntent.SetTimer(60), parser.parse("fai partire un timer di un minuto"))
    }

    // --- Timer: unità di misura --------------------------------------------

    @Test
    fun `timer in secondi`() {
        assertEquals(VoiceIntent.SetTimer(30), parser.parse("imposta un timer di 30 secondi"))
    }

    @Test
    fun `timer in ore`() {
        assertEquals(VoiceIntent.SetTimer(3600), parser.parse("imposta un timer di 1 ora"))
    }

    @Test
    fun `timer con numero a parole grande`() {
        assertEquals(VoiceIntent.SetTimer(45 * 60), parser.parse("avvia timer di quarantacinque minuti"))
    }

    // --- Ricerca web --------------------------------------------------------

    @Test
    fun `cerca su internet`() {
        val result = parser.parse("cerca ricette pasta su internet")
        assertEquals(VoiceIntent.WebSearch("ricette pasta"), result)
    }

    @Test
    fun `cerca semplice senza suffisso`() {
        val result = parser.parse("cerca il meteo di domani")
        assertEquals(VoiceIntent.WebSearch("il meteo di domani"), result)
    }

    // --- Bluetooth ------------------------------------------------------------

    @Test
    fun `accendi bluetooth`() {
        assertEquals(VoiceIntent.BluetoothToggle(turnOn = true), parser.parse("accendi il bluetooth"))
    }

    @Test
    fun `spegni bluetooth`() {
        assertEquals(VoiceIntent.BluetoothToggle(turnOn = false), parser.parse("spegni il bluetooth"))
    }

    // --- Comandi non riconosciuti -------------------------------------------

    @Test
    fun `frase non riconosciuta ritorna Unknown`() {
        assertEquals(VoiceIntent.Unknown, parser.parse("che tempo fa domani"))
    }

    @Test
    fun `numero a parole non nella mappa ritorna Unknown invece di crashare`() {
        assertEquals(VoiceIntent.Unknown, parser.parse("imposta un timer di ventisettemila minuti"))
    }

    // --- Media: skip traccia --------------------------------------------------

    @Test
    fun `prossima canzone`() {
        assertEquals(VoiceIntent.NextTrack, parser.parse("prossima canzone"))
    }

    @Test
    fun `canzone successiva`() {
        assertEquals(VoiceIntent.NextTrack, parser.parse("metti la canzone successiva"))
    }

    @Test
    fun `salta canzone`() {
        assertEquals(VoiceIntent.NextTrack, parser.parse("salta la canzone"))
    }

    @Test
    fun `canzone precedente`() {
        assertEquals(VoiceIntent.PreviousTrack, parser.parse("canzone precedente"))
    }

    @Test
    fun `canzone di prima`() {
        assertEquals(VoiceIntent.PreviousTrack, parser.parse("torna alla canzone di prima"))
    }

    @Test
    fun `precedente da solo attiva deliberatamente il comando canzone`() {
        // Trade-off esplicito: trigger più corti da pronunciare, a costo di
        // falsi positivi su frasi come "il giorno precedente". Vedi commento
        // su previousTrackPattern in CommandParser.
        assertEquals(VoiceIntent.PreviousTrack, parser.parse("qual era il giorno precedente"))
    }

    @Test
    fun `prossima da sola attiva next track`() {
        assertEquals(VoiceIntent.NextTrack, parser.parse("prossima"))
    }

    @Test
    fun `successiva da sola attiva next track`() {
        assertEquals(VoiceIntent.NextTrack, parser.parse("successiva"))
    }

    // --- Pausa media ---------------------------------------------------------

    @Test
    fun `pausa`() {
        assertEquals(VoiceIntent.PauseMedia, parser.parse("pausa"))
    }

    @Test
    fun `ferma tutto`() {
        assertEquals(VoiceIntent.PauseMedia, parser.parse("ferma tutto"))
    }

    @Test
    fun `metti in pausa`() {
        assertEquals(VoiceIntent.PauseMedia, parser.parse("metti in pausa"))
    }

    @Test
    fun `silenzio`() {
        assertEquals(VoiceIntent.PauseMedia, parser.parse("silenzio"))
    }

    // --- Volume ------------------------------------------------------------

    @Test
    fun `volume assoluto con preposizione al`() {
        assertEquals(VoiceIntent.SetVolume(70), parser.parse("volume al 70"))
    }

    @Test
    fun `volume assoluto con preposizione a`() {
        assertEquals(VoiceIntent.SetVolume(30), parser.parse("volume a 30"))
    }

    @Test
    fun `volume assoluto con simbolo percento`() {
        assertEquals(VoiceIntent.SetVolume(50), parser.parse("volume 50%"))
    }

    @Test
    fun `volume assoluto oltre 100 viene limitato`() {
        assertEquals(VoiceIntent.SetVolume(100), parser.parse("volume al 150"))
    }

    @Test
    fun `volume assoluto espresso a parole`() {
        assertEquals(VoiceIntent.SetVolume(100), parser.parse("volume a cento"))
    }

    @Test
    fun `alza il volume`() {
        assertEquals(VoiceIntent.AdjustVolume(increase = true), parser.parse("alza il volume"))
    }

    @Test
    fun `abbassa il volume`() {
        assertEquals(VoiceIntent.AdjustVolume(increase = false), parser.parse("abbassa il volume"))
    }
}
