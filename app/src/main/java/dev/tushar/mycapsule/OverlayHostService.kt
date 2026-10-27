package dev.tushar.mycapsule

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * An accessibility service with two jobs. It owns an accessibility-overlay window layer for [OverlayHost], and it serves
 * the clipboard deck: it hears about each copy ([ClipboardHistory.watch]) and types a chosen item into the field that has
 * the cursor ([paste]). It receives no events and never reads the screen; the one thing it looks at is which field has
 * the cursor, and only when an item is tapped.
 */
class OverlayHostService : AccessibilityService() {

    private var stopWatching: (() -> Unit)? = null

    override fun onServiceConnected() {
        OverlayHost.attach(this)
        stopWatching = ClipboardHistory.watch(this)
    }

    /**
     * Puts [text] into the focused text field at the cursor, replacing what is selected. Pasting through the field
     * itself is tried first; fields that refuse it get the new text set directly. False when no field has the cursor.
     */
    fun paste(text: String): Boolean {
        runCatching { getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Clipboard", text)) }
        val field = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!field.isEditable) return false
        if (field.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true

        val current = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
        val start = field.textSelectionStart.takeIf { it >= 0 }?.coerceAtMost(current.length) ?: current.length
        val end = field.textSelectionEnd.takeIf { it >= start }?.coerceAtMost(current.length) ?: start
        val updated = current.substring(0, start) + text + current.substring(end)
        val set = field.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated) },
        )
        if (set) {
            // The cursor goes after what was typed, as a paste would leave it.
            val cursor = start + text.length
            field.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                },
            )
        }
        return set
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    private fun release() {
        stopWatching?.invoke()
        stopWatching = null
        OverlayHost.detach(this)
    }
}
