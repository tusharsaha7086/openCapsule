package dev.tushar.mycapsule

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import org.json.JSONArray

/**
 * What the user copied lately, newest first, kept in this app's private storage (not in the settings, so "Reset all
 * settings" leaves it alone, and not backed up). Text only; an item the app that copied it marked sensitive (a
 * password manager's) is never kept.
 */
object ClipboardHistory {

    private const val FILE = "clipboard_history"
    private const val KEY = "items"
    private const val MAX_ITEMS = 30
    private const val MAX_CHARS = 5_000
    private const val IGNORE_MS = 2_000L

    /** The key the system uses to mark a clip as sensitive (a constant only from Android 13, but older apps may set it too). */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private var ignored: String? = null
    private var ignoredUntil = 0L

    fun items(context: Context): List<String> {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching { JSONArray(raw).let { array -> List(array.length()) { array.getString(it) } } }.getOrDefault(emptyList())
    }

    /** Adds [text] on top; a copy of something already in the list moves it up instead of repeating it. */
    fun add(context: Context, text: String) {
        val clean = text.trim().take(MAX_CHARS)
        if (clean.isEmpty()) return
        save(context, (listOf(clean) + items(context).filter { it != clean }).take(MAX_ITEMS))
    }

    fun remove(context: Context, text: String) = save(context, items(context).filter { it != text })

    fun clear(context: Context) = save(context, emptyList())

    private fun save(context: Context, items: List<String>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, JSONArray(items).toString()).apply()
    }

    /**
     * Starts recording what is copied. Android only tells an app about a copy while it is allowed to see the clipboard, so
     * this is meant to run in the accessibility service. Returns a function that stops it.
     */
    fun watch(context: Context): () -> Unit {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return {}
        val listener = ClipboardManager.OnPrimaryClipChangedListener { record(context, manager) }
        manager.addPrimaryClipChangedListener(listener)
        return { manager.removePrimaryClipChangedListener(listener) }
    }

    /**
     * Adds what is on the clipboard now to the history. False when Android would not hand over the clip (it only does so to
     * the app that has window focus), so the caller can try again with focus; true once it was read, whatever it held.
     */
    fun record(context: Context, manager: ClipboardManager): Boolean {
        if (!Prefs(context).clipboardRecording) return true
        val clip = runCatching { manager.primaryClip }.getOrNull() ?: return false
        if (clip.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true) return true
        val text = clip.getItemAt(0)?.coerceToText(context)?.toString() ?: return true
        // What we put on the clipboard ourselves, to paste it, is not a new copy.
        if (text == ignored && SystemClock.uptimeMillis() < ignoredUntil) return true
        add(context, text)
        return true
    }

    /**
     * Puts [text] into the field being typed in, through the accessibility service. When that is not possible (the
     * service is off, or no field is focused) the text is left on the clipboard for the user to paste, and they are told.
     */
    fun insert(context: Context, text: String) {
        ignored = text
        ignoredUntil = SystemClock.uptimeMillis() + IGNORE_MS
        if (OverlayHost.paste(text)) return
        runCatching { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Clipboard", text)) }
        Toast.makeText(context, "Copied. No text field is open to paste into.", Toast.LENGTH_SHORT).show()
    }
}

/** The clipboard card of the deck: the last copies as numbered lines, and tapping one pastes it into the open field. */
class ClipboardSource : CapsuleSource {

    override fun start(context: Context, emit: (CapsuleEvent) -> Unit) = Unit

    override fun card(context: Context): CapsuleEvent {
        val items = ClipboardHistory.items(context)
        val recording = Prefs(context).clipboardRecording
        val serviceOn = OverlayHost.isAboveStatusBar
        val rows = items.take(VISIBLE).mapIndexed { i, item ->
            Row(
                text = item.lineSummary(),
                number = i + 1,
                onLongPress = { ClipboardHistory.remove(context, item) },
            ) { ClipboardHistory.insert(context, item) }
        }
        val actions = if (!serviceOn) {
            listOf(
                CapsuleAction(context.getDrawable(R.drawable.ic_settings), "Open accessibility settings", dismisses = true) {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
            )
        } else if (rows.isNotEmpty()) {
            listOf(CapsuleAction(context.getDrawable(R.drawable.ic_delete), "Clear the clipboard history", dismisses = true) { ClipboardHistory.clear(context) })
        } else {
            emptyList()
        }
        return CapsuleEvent(
            kind = Kind.CLIPBOARD,
            title = "Clipboard",
            text = when {
                !serviceOn -> "Copies are recorded by the accessibility service"
                !recording -> "Recording is off in Dynamic cards"
                rows.isEmpty() -> "Copy some text and it shows up here"
                else -> "Tap to paste, hold to remove"
            },
            icon = context.getDrawable(R.drawable.ic_content_paste),
            rows = rows,
            actions = actions,
        )
    }

    /** One line to show: line breaks and runs of spaces become single spaces. */
    private fun String.lineSummary() = replace(WHITESPACE, " ")

    private companion object {
        const val VISIBLE = 4
        val WHITESPACE = Regex("\\s+")
    }
}
