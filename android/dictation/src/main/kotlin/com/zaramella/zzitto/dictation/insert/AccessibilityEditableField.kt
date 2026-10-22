package com.zaramella.zzitto.dictation.insert

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.PersistableBundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Campo di testo di un'altra app, raggiunto tramite il servizio di accessibilità (Spec §5.1-bis).
 * Aggiunto il 22/10/2026 alle 12:15.
 */
class AccessibilityEditableField(private val node: AccessibilityNodeInfo) : EditableField {

    override val key: String = buildString {
        append(node.windowId).append(':')
        append(node.packageName).append(':')
        append(node.viewIdResourceName ?: "").append(':')
        append(node.hashCode())
    }

    override fun read(): FieldSnapshot? {
        if (!node.refresh()) return null
        if (!node.isEditable) return null
        // Spec §5.10: la selezione si legge dal nodo (al posto di InputConnection.getSelectedText).
        val text = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        return FieldSnapshot(text, node.textSelectionStart, node.textSelectionEnd)
    }

    override fun setText(plan: EditPlan): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, plan.newText)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        select(plan.selectionStart, plan.selectionEnd)
        return true
    }

    override fun select(start: Int, end: Int): Boolean {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    override fun paste(): Boolean = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)

    override fun pressEnter(): Boolean {
        // Spec §5.9: "invia" solo dove il campo lo consente.
        val enter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
        if (node.actionList.none { it.id == enter.id }) return false
        return node.performAction(enter.id)
    }

    companion object {
        /** Campo adatto alla dettatura: modificabile e non password (Spec §10, minimizzazione). */
        fun isDictationTarget(node: AccessibilityNodeInfo?): Boolean =
            node != null && node.isEditable && !node.isPassword && node.isEnabled
    }
}

/** Appunti di sistema (Spec §5.1-bis: incolla con ripristino degli appunti precedenti). */
class AndroidClipboardAccess(context: Context) : ClipboardAccess {

    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    override fun save(): SavedClip = SavedClip(
        try {
            // Da Android 10 un'app senza focus riceve null: in quel caso non si può ripristinare.
            clipboard.primaryClip
        } catch (_: SecurityException) {
            null
        },
    )

    override fun putSensitiveText(text: String) {
        val clip = ClipData.newPlainText(CLIP_LABEL, text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        clipboard.setPrimaryClip(clip)
    }

    override fun restore(saved: SavedClip) {
        val previous = saved.content as? ClipData
        if (previous != null) {
            clipboard.setPrimaryClip(previous)
        } else {
            clipboard.clearPrimaryClip()
        }
    }

    private companion object {
        const val CLIP_LABEL = "Zzitto"
    }
}
