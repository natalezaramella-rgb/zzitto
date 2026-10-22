package com.zaramella.zzitto.dictation.insert

/**
 * Logica pura di modifica del testo di un campo (Spec §5.4, §5.9, §5.10).
 * Aggiunto il 22/10/2026 alle 11:59.
 */

/** Contenuto e selezione di un campo. Contiene testo dell'utente: mai nei log (Spec §10). */
data class FieldSnapshot(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
) {
    /** Inizio selezione normalizzato; se il campo non la espone (-1) il cursore va in fondo. */
    val start: Int
        get() = normalized().first

    val end: Int
        get() = normalized().second

    val hasSelection: Boolean
        get() = start != end

    val selectedText: String
        get() = text.substring(start, end)

    private fun normalized(): Pair<Int, Int> {
        if (selectionStart < 0 || selectionEnd < 0) return text.length to text.length
        val a = selectionStart.coerceIn(0, text.length)
        val b = selectionEnd.coerceIn(0, text.length)
        return minOf(a, b) to maxOf(a, b)
    }

    override fun toString(): String = "FieldSnapshot(len=${text.length}, sel=$selectionStart..$selectionEnd)"
}

/** Risultato di una modifica: nuovo testo e nuova selezione. */
data class EditPlan(val newText: String, val selectionStart: Int, val selectionEnd: Int) {
    override fun toString(): String = "EditPlan(len=${newText.length}, sel=$selectionStart..$selectionEnd)"
}

object TextEditing {

    private const val SENTENCE_END = ".!?…"
    private const val NO_SPACE_BEFORE = ".,;:!?…)]}»”'\""

    /**
     * Inserisce [inserted] al cursore, sostituendo l'eventuale selezione (Spec §5.4, §5.10).
     * Con [smartSpacing] aggiunge uno spazio se il testo precedente non termina con uno spazio
     * e il testo inserito non inizia con punteggiatura o spazio.
     */
    fun insert(snapshot: FieldSnapshot, inserted: String, smartSpacing: Boolean = true): EditPlan {
        val start = snapshot.start
        val end = snapshot.end
        val before = snapshot.text.substring(0, start)
        val after = snapshot.text.substring(end)
        val needsSpace = smartSpacing &&
            inserted.isNotEmpty() &&
            before.isNotEmpty() &&
            !before.last().isWhitespace() &&
            before.last() !in "([{«“" &&
            !inserted.first().isWhitespace() &&
            inserted.first() !in NO_SPACE_BEFORE
        val piece = if (needsSpace) " $inserted" else inserted
        val newText = before + piece + after
        val cursor = before.length + piece.length
        return EditPlan(newText, cursor, cursor)
    }

    /**
     * Comando "cancella l'ultima frase" quando la dettatura corrente non ne contiene (Spec §5.9,
     * ZZ_ACTION_DELETE_PREVIOUS_SENTENCE): cancella la frase che precede il cursore.
     * Restituisce null se prima del cursore non c'è testo.
     */
    fun deletePreviousSentence(snapshot: FieldSnapshot): EditPlan? {
        val cursor = snapshot.start
        val before = snapshot.text.substring(0, cursor)
        val trimmedEnd = before.trimEnd().length
        if (trimmedEnd == 0) return null
        var i = trimmedEnd - 1
        // Salta la punteggiatura finale della frase da cancellare.
        while (i >= 0 && before[i] in SENTENCE_END) i--
        // Cerca la fine della frase precedente (o un a capo).
        while (i >= 0 && before[i] !in SENTENCE_END && before[i] != '\n') i--
        val keep = before.substring(0, i + 1)
        val newText = keep + snapshot.text.substring(cursor)
        return EditPlan(newText, keep.length, keep.length)
    }
}

/** Stato salvato per annullare l'ultimo inserimento o riscrittura (Spec §5.4, §5.10). */
data class UndoRecord(
    val fieldKey: String,
    val before: FieldSnapshot,
    val afterText: String,
) {
    /** Piano di ripristino, valido solo se il campo contiene ancora esattamente il testo inserito. */
    fun restorePlan(current: FieldSnapshot): EditPlan? {
        if (current.text != afterText) return null
        return EditPlan(before.text, before.start, before.end)
    }

    override fun toString(): String = "UndoRecord(field=$fieldKey, before=$before, afterLen=${afterText.length})"
}
