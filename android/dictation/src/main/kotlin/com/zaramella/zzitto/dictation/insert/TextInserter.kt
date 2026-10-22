package com.zaramella.zzitto.dictation.insert

import kotlinx.coroutines.delay

/**
 * Campo di testo di destinazione. In produzione è un nodo di accessibilità
 * ([AccessibilityEditableField]); nei test è finto (Spec §12).
 * Aggiunto il 22/10/2026 alle 12:10.
 */
interface EditableField {
    /** Identifica il campo, per non annullare un inserimento in un campo diverso. */
    val key: String

    /** Contenuto e selezione attuali, oppure null se il campo non esiste più. */
    fun read(): FieldSnapshot?

    /** Sostituisce il testo e posiziona la selezione (ACTION_SET_TEXT + ACTION_SET_SELECTION). */
    fun setText(plan: EditPlan): Boolean

    /** Imposta la selezione (ACTION_SET_SELECTION). */
    fun select(start: Int, end: Int): Boolean

    /** Incolla dagli appunti alla posizione del cursore (ACTION_PASTE). */
    fun paste(): Boolean

    /** Tasto invio dell'editor (ACTION_IME_ENTER), per il comando "invia" (Spec §5.9). */
    fun pressEnter(): Boolean
}

/** Appunti di sistema; astratti per i test. */
interface ClipboardAccess {
    /** Salva gli appunti correnti, se leggibili (da Android 10 spesso non lo sono in background). */
    fun save(): SavedClip

    /** Mette [text] negli appunti marcandolo come sensibile (nessuna anteprima, Spec §10). */
    fun putSensitiveText(text: String)

    /** Ripristina gli appunti salvati; se non erano leggibili li svuota, per non lasciarvi il testo dettato. */
    fun restore(saved: SavedClip)
}

/** Contenuto opaco degli appunti salvati. */
class SavedClip(val content: Any?)

enum class InsertMethod { SET_TEXT, PASTE }

sealed interface InsertOutcome {
    data class Inserted(val method: InsertMethod, val undo: UndoRecord?) : InsertOutcome

    /** Inserimento impossibile: il testo è rimasto negli appunti per incollarlo a mano. */
    data object CopiedToClipboard : InsertOutcome
}

/**
 * Strategia di inserimento (Spec §5.1-bis):
 * 1. `ACTION_SET_TEXT` rispettando cursore e selezione, con verifica;
 * 2. incolla tramite appunti e ripristino degli appunti precedenti;
 * 3. se anche l'incolla fallisce, il testo resta negli appunti e l'utente viene avvisato.
 */
class TextInserter(
    private val clipboard: ClipboardAccess,
    private val verifyDelayMs: Long = VERIFY_DELAY_MS,
    private val restoreDelayMs: Long = RESTORE_DELAY_MS,
) {

    /**
     * Inserisce [text]. Con [replaceSnapshot] (riscrittura, Spec §5.10) sostituisce la selezione
     * originale se il campo non è cambiato nel frattempo.
     */
    suspend fun insert(
        field: EditableField,
        text: String,
        replaceSnapshot: FieldSnapshot? = null,
        smartSpacing: Boolean = true,
    ): InsertOutcome {
        val current = field.read() ?: return copyToClipboard(text)
        val base = replaceSnapshot?.takeIf { it.text == current.text } ?: current
        val plan = TextEditing.insert(base, text, smartSpacing)

        // 1. ACTION_SET_TEXT
        if (field.setText(plan)) {
            var after = field.read()
            if (after != null && after.text == current.text) {
                // Alcuni editor aggiornano il nodo in modo asincrono: una seconda lettura.
                delay(verifyDelayMs)
                after = field.read()
            }
            if (after == null || after.text != current.text) {
                return InsertOutcome.Inserted(
                    InsertMethod.SET_TEXT,
                    UndoRecord(field.key, base, after?.text ?: plan.newText),
                )
            }
        }

        // 2. Incolla tramite appunti, con la selezione di partenza.
        val piece = plan.newText.substring(base.start, plan.selectionStart)
        val saved = clipboard.save()
        clipboard.putSensitiveText(piece)
        field.select(base.start, base.end)
        if (field.paste()) {
            delay(restoreDelayMs)
            clipboard.restore(saved)
            val after = field.read()
            return InsertOutcome.Inserted(InsertMethod.PASTE, after?.let { UndoRecord(field.key, base, it.text) })
        }

        // 3. Il testo resta negli appunti (come il fallback di Windows, Spec §2.6).
        return InsertOutcome.CopiedToClipboard
    }

    /** Applica una modifica già calcolata (annulla, cancella frase). */
    fun apply(field: EditableField, plan: EditPlan): Boolean = field.setText(plan)

    private fun copyToClipboard(text: String): InsertOutcome {
        clipboard.putSensitiveText(text)
        return InsertOutcome.CopiedToClipboard
    }

    companion object {
        const val VERIFY_DELAY_MS = 150L
        const val RESTORE_DELAY_MS = 400L
    }
}
