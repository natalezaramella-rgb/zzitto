package com.zaramella.zzitto.dictation.insert

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Strategia di inserimento con fallback (Spec §5.1-bis, §2.6). Aggiunto il 22/10/2026 alle 12:40. */
class TextInserterTest {

    private val clipboard = FakeClipboard(current = "appunti utente")
    private val inserter = TextInserter(clipboard, verifyDelayMs = 1, restoreDelayMs = 1)

    @Test
    fun `ACTION_SET_TEXT al cursore`() = runBlocking {
        val field = FakeField("Ciao", 4, 4).also { it.clipboard = clipboard }
        val outcome = inserter.insert(field, "mondo.")
        assertTrue(outcome is InsertOutcome.Inserted)
        outcome as InsertOutcome.Inserted
        assertEquals(InsertMethod.SET_TEXT, outcome.method)
        assertEquals("Ciao mondo.", field.text)
        assertEquals(11, field.selStart)
        assertEquals("appunti utente", clipboard.current)
        assertEquals(0, clipboard.sensitiveWrites)
        assertEquals(FieldSnapshot("Ciao", 4, 4), outcome.undo?.before)
    }

    @Test
    fun `senza ACTION_SET_TEXT incolla e ripristina gli appunti`() = runBlocking {
        val field = FakeField("Ciao", 4, 4).also {
            it.clipboard = clipboard
            it.setTextSupported = false
        }
        val outcome = inserter.insert(field, "mondo")
        outcome as InsertOutcome.Inserted
        assertEquals(InsertMethod.PASTE, outcome.method)
        assertEquals("Ciao mondo", field.text)
        assertEquals("appunti utente", clipboard.current)
        assertEquals(1, clipboard.sensitiveWrites)
    }

    @Test
    fun `editor che ignora ACTION_SET_TEXT passa all'incolla senza duplicare`() = runBlocking {
        val field = FakeField("A", 1, 1).also {
            it.clipboard = clipboard
            it.ignoreSetText = true
        }
        inserter.insert(field, "b")
        assertEquals("A b", field.text)
        assertEquals(1, field.pasteCalls)
    }

    @Test
    fun `appunti non leggibili vengono svuotati dopo l'incolla`() = runBlocking {
        val unreadable = FakeClipboard(current = "privato", readable = false)
        val field = FakeField("", 0, 0).also {
            it.clipboard = unreadable
            it.setTextSupported = false
        }
        TextInserter(unreadable, 1, 1).insert(field, "testo")
        assertEquals("testo", field.text)
        assertNull(unreadable.current)
    }

    @Test
    fun `se anche l'incolla fallisce il testo resta negli appunti`() = runBlocking {
        val field = FakeField("x", 1, 1).also {
            it.clipboard = clipboard
            it.setTextSupported = false
            it.pasteSupported = false
        }
        val outcome = inserter.insert(field, "dettato")
        assertEquals(InsertOutcome.CopiedToClipboard, outcome)
        assertEquals(" dettato", clipboard.current)
    }

    @Test
    fun `campo scomparso`() = runBlocking {
        val field = FakeField().also { it.exists = false }
        assertEquals(InsertOutcome.CopiedToClipboard, inserter.insert(field, "abc"))
        assertEquals("abc", clipboard.current)
    }

    @Test
    fun `riscrittura sostituisce la selezione originale`() = runBlocking {
        val field = FakeField("Testo vecchio qui", 0, 0).also { it.clipboard = clipboard }
        val original = FieldSnapshot("Testo vecchio qui", 6, 13)
        inserter.insert(field, "nuovo", replaceSnapshot = original, smartSpacing = false)
        assertEquals("Testo nuovo qui", field.text)
    }

    @Test
    fun `riscrittura con campo cambiato usa la selezione attuale`() = runBlocking {
        val field = FakeField("Altro testo", 11, 11).also { it.clipboard = clipboard }
        val original = FieldSnapshot("Testo vecchio qui", 6, 13)
        inserter.insert(field, "!", replaceSnapshot = original, smartSpacing = false)
        assertEquals("Altro testo!", field.text)
    }
}
