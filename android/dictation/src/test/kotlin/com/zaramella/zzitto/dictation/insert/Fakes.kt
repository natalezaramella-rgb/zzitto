package com.zaramella.zzitto.dictation.insert

/** Campo e appunti finti per i test di inserimento (Spec §12). Aggiunto il 22/10/2026 alle 11:59. */
class FakeField(
    var text: String = "",
    var selStart: Int = 0,
    var selEnd: Int = 0,
    override val key: String = "campo-1",
) : EditableField {
    var setTextSupported = true

    /** Simula un editor che accetta l'azione ma non cambia il testo. */
    var ignoreSetText = false
    var pasteSupported = true
    var enterSupported = true
    var exists = true
    var clipboard: FakeClipboard? = null
    var enterPressed = 0
    var setTextCalls = 0
    var pasteCalls = 0

    override fun read(): FieldSnapshot? = if (exists) FieldSnapshot(text, selStart, selEnd) else null

    override fun setText(plan: EditPlan): Boolean {
        setTextCalls++
        if (!setTextSupported) return false
        if (!ignoreSetText) {
            text = plan.newText
            selStart = plan.selectionStart
            selEnd = plan.selectionEnd
        }
        return true
    }

    override fun select(start: Int, end: Int): Boolean {
        selStart = start
        selEnd = end
        return true
    }

    override fun paste(): Boolean {
        pasteCalls++
        if (!pasteSupported) return false
        val clip = clipboard?.current ?: return false
        val s = minOf(selStart, selEnd).coerceIn(0, text.length)
        val e = maxOf(selStart, selEnd).coerceIn(0, text.length)
        text = text.substring(0, s) + clip + text.substring(e)
        selStart = s + clip.length
        selEnd = selStart
        return true
    }

    override fun pressEnter(): Boolean {
        if (!enterSupported) return false
        enterPressed++
        return true
    }
}

class FakeClipboard(var current: String? = null, private val readable: Boolean = true) : ClipboardAccess {
    var sensitiveWrites = 0

    override fun save(): SavedClip = SavedClip(if (readable) current else null)

    override fun putSensitiveText(text: String) {
        sensitiveWrites++
        current = text
    }

    override fun restore(saved: SavedClip) {
        current = saved.content as String?
    }
}
