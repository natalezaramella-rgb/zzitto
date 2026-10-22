package com.zaramella.zzitto.licensing.trial

import com.zaramella.zzitto.core.licensing.LicenseState
import com.zaramella.zzitto.licensing.DAY
import com.zaramella.zzitto.licensing.T0
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

// Spec §8.1, §12: calcolo giorni rimanenti, scadenza, orologio indietro. Aggiunto il 22/10/2026 alle 11:28.
class TrialPolicyTest {

    @ParameterizedTest(name = "dopo {0} ms rimangono {1} giorni")
    @CsvSource(
        "0, 30",
        "3600000, 30",
        "86399999, 30",
        "86400000, 29",
        "2505600000, 1", // 29 giorni esatti: ultimo giorno
        "2591999999, 1", // un millisecondo prima della scadenza
        "2592000000, 0", // 30 giorni esatti: scaduta
        "8640000000, 0", // 100 giorni
    )
    fun `giorni rimanenti`(elapsedMillis: Long, expected: Int) {
        val record = TrialPolicy.start(T0)
        assertEquals(expected, TrialPolicy.daysLeft(record, T0 + elapsedMillis))
    }

    @Test
    fun `stato prova attiva e scaduta`() {
        val record = TrialPolicy.start(T0)
        assertEquals(LicenseState.Trial(30), TrialPolicy.evaluate(record, T0))
        assertEquals(LicenseState.Trial(1), TrialPolicy.evaluate(record, T0 + 29 * DAY))
        assertEquals(LicenseState.TrialExpired, TrialPolicy.evaluate(record, T0 + 30 * DAY))
    }

    @Test
    fun `orologio spostato indietro non restituisce giorni`() {
        val seen = TrialPolicy.advance(TrialPolicy.start(T0), T0 + 10 * DAY)
        assertEquals(20, TrialPolicy.daysLeft(seen, T0 + 10 * DAY))
        // L'utente riporta l'orologio a 5 giorni fa: restano comunque 20 giorni.
        assertEquals(20, TrialPolicy.daysLeft(seen, T0 + 5 * DAY))
        // Anche prima dell'inizio della prova.
        assertEquals(20, TrialPolicy.daysLeft(seen, T0 - 100 * DAY))
        // Con prova scaduta, spostare l'orologio indietro non la riattiva.
        val expired = TrialPolicy.advance(seen, T0 + 31 * DAY)
        assertEquals(LicenseState.TrialExpired, TrialPolicy.evaluate(expired, T0 + DAY))
    }

    @Test
    fun `l'high-water mark non torna mai indietro`() {
        val record = TrialPolicy.advance(TrialPolicy.start(T0), T0 + 5 * DAY)
        assertEquals(T0 + 5 * DAY, TrialPolicy.advance(record, T0 + DAY).highWaterMillis)
    }

    @Test
    fun `inizio nel futuro non produce giorni negativi`() {
        val record = TrialRecord(startMillis = T0 + 2 * DAY, highWaterMillis = T0 + 2 * DAY)
        assertEquals(30, TrialPolicy.daysLeft(record, T0))
    }

    @Test
    fun `merge sceglie l'inizio piu vecchio e l'high-water mark piu avanzato`() {
        val local = TrialRecord(T0 + 3 * DAY, T0 + 4 * DAY)
        val account = TrialRecord(T0, T0 + 2 * DAY)
        assertEquals(TrialRecord(T0, T0 + 4 * DAY), TrialPolicy.merge(local, account))
        assertEquals(account, TrialPolicy.merge(null, account))
        assertEquals(local, TrialPolicy.merge(local, null))
        assertNull(TrialPolicy.merge(null, null))
    }

    @Test
    fun `orologio implausibile`() {
        assertFalse(TrialPolicy.isPlausible(946_684_800_000L)) // 01/01/2000
        assertTrue(TrialPolicy.isPlausible(T0))
    }

    @Test
    fun `riscrittura su Block Store solo se serve`() {
        val stored = TrialRecord(T0, T0)
        assertTrue(TrialPolicy.needsAccountWrite(null, stored))
        assertFalse(TrialPolicy.needsAccountWrite(stored, stored.copy(highWaterMillis = T0 + 60_000)))
        assertTrue(TrialPolicy.needsAccountWrite(stored, stored.copy(highWaterMillis = T0 + 3_600_000)))
        assertTrue(TrialPolicy.needsAccountWrite(stored, TrialRecord(T0 - DAY, T0)))
    }

    @Test
    fun `codifica e decodifica del record`() {
        val record = TrialRecord(T0, T0 + DAY)
        assertEquals(record, TrialRecord.decode(record.encode()))
        assertNull(TrialRecord.decode(null))
        assertNull(TrialRecord.decode(""))
        assertNull(TrialRecord.decode("v2;1;2"))
        assertNull(TrialRecord.decode("v1;abc;2"))
        assertNull(TrialRecord.decode("v1;-5;2"))
        // High-water mark incoerente (prima dell'inizio): corretto all'inizio.
        assertEquals(TrialRecord(T0, T0), TrialRecord.decode("v1;$T0;1"))
    }
}
