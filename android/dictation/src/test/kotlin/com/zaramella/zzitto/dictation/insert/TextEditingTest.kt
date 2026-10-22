package com.zaramella.zzitto.dictation.insert

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Logica di inserimento e cancellazione (Spec §5.4, §5.9, §5.10). Aggiunto il 22/10/2026 alle 12:40. */
class TextEditingTest {

    @Test
    fun `inserimento in un campo vuoto`() {
        val plan = TextEditing.insert(FieldSnapshot("", 0, 0), "Ciao.")
        assertEquals(EditPlan("Ciao.", 5, 5), plan)
    }

    @Test
    fun `inserimento al cursore con spazio automatico`() {
        val plan = TextEditing.insert(FieldSnapshot("Buongiorno.fine", 11, 11), "Come stai?")
        assertEquals("Buongiorno. Come stai?fine", plan.newText)
        assertEquals(22, plan.selectionStart)
    }

    @Test
    fun `niente spazio prima della punteggiatura o dopo uno spazio`() {
        assertEquals("Ciao, mondo", TextEditing.insert(FieldSnapshot("Ciao", 4, 4), ", mondo").newText)
        assertEquals("Ciao mondo", TextEditing.insert(FieldSnapshot("Ciao ", 5, 5), "mondo").newText)
        assertEquals("(nota", TextEditing.insert(FieldSnapshot("(", 1, 1), "nota").newText)
        assertEquals("abcdef", TextEditing.insert(FieldSnapshot("abc", 3, 3), "def", smartSpacing = false).newText)
    }

    @Test
    fun `la selezione viene sostituita (riscrittura)`() {
        val snap = FieldSnapshot("Testo vecchio qui", 6, 13)
        assertTrue(snap.hasSelection)
        assertEquals("vecchio", snap.selectedText)
        val plan = TextEditing.insert(snap, "nuovo", smartSpacing = false)
        assertEquals("Testo nuovo qui", plan.newText)
        assertEquals(11, plan.selectionStart)
    }

    @Test
    fun `selezione invertita o sconosciuta`() {
        val reversed = FieldSnapshot("abcdef", 4, 1)
        assertEquals(1, reversed.start)
        assertEquals(4, reversed.end)
        val unknown = FieldSnapshot("abc", -1, -1)
        assertEquals(3, unknown.start)
        assertFalse(unknown.hasSelection)
        val outOfRange = FieldSnapshot("abc", 10, 10)
        assertEquals(3, outOfRange.start)
    }

    @Test
    fun `cancella l'ultima frase prima del cursore`() {
        val plan = TextEditing.deletePreviousSentence(FieldSnapshot("Ciao. Come stai? Bene grazie.", 29, 29))!!
        assertEquals("Ciao. Come stai?", plan.newText)
        assertEquals(16, plan.selectionStart)
    }

    @Test
    fun `cancella l'unica frase e conserva il testo dopo il cursore`() {
        val plan = TextEditing.deletePreviousSentence(FieldSnapshot("Una frase sola  |resto", 16, 16))!!
        assertEquals("|resto", plan.newText)
        assertEquals(0, plan.selectionStart)
    }

    @Test
    fun `cancella fino all'a capo`() {
        val plan = TextEditing.deletePreviousSentence(FieldSnapshot("Riga uno\nriga due", 17, 17))!!
        assertEquals("Riga uno\n", plan.newText)
    }

    @Test
    fun `niente da cancellare`() {
        assertNull(TextEditing.deletePreviousSentence(FieldSnapshot("   ", 3, 3)))
        assertNull(TextEditing.deletePreviousSentence(FieldSnapshot("dopo", 0, 0)))
    }

    @Test
    fun `annullamento solo se il campo non e cambiato`() {
        val before = FieldSnapshot("Ciao", 4, 4)
        val record = UndoRecord("k", before, "Ciao mondo")
        assertEquals(EditPlan("Ciao", 4, 4), record.restorePlan(FieldSnapshot("Ciao mondo", 10, 10)))
        assertNull(record.restorePlan(FieldSnapshot("Ciao mondo!", 11, 11)))
    }

    @Test
    fun `toString senza testo dell'utente`() {
        val s = FieldSnapshot("segreto", 0, 0).toString() + EditPlan("segreto", 0, 0) + UndoRecord("k", FieldSnapshot("segreto", 0, 0), "segreto")
        assertFalse(s.contains("segreto"))
    }
}
