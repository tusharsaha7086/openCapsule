package dev.tushar.mycapsule

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
     * Adds what was copied last to the clipboard history. Android tells a background app nothing about a copy and hands it
     * the clip only while it has window focus, so this is done when the deck opens: the read is tried as it is and, if
     * refused, again while an invisible 1 px window holds focus for a moment. [onDone] runs once the clip was read.
     */
    fun captureClip(onDone: () -> Unit) {
        val manager = getSystemService(ClipboardManager::class.java) ?: return
        if (ClipboardHistory.record(this, manager)) {
            onDone()
            return
        }
        val windows = getSystemService(WindowManager::class.java) ?: return
        var finished = false
        lateinit var probe: View
        fun finish(read: Boolean) {
            if (finished) return
            finished = true
            if (read && ClipboardHistory.record(this, manager)) onDone()
            // Not from inside the focus callback: the window is still being told about its focus, and taking it away
            // there crashes the app. One message later it is safe.
            probe.post { runCatching { windows.removeView(probe) } }
        }
        probe = object : View(this) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus) finish(read = true)
            }
        }
        val params = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        if (runCatching { windows.addView(probe, params) }.isFailure) return
        probe.postDelayed({ finish(read = false) }, FOCUS_TIMEOUT_MS)
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

    /**
     * The package name of the app in front, or null when it can't be told (a secure screen, or the system is between windows).
     * Only the name is looked at: nothing on the screen is read. The screen pet uses it to roam only on the home screen.
     */
    fun foregroundPackage(): String? = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

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

    private companion object {
        const val FOCUS_TIMEOUT_MS = 600L
    }
}
