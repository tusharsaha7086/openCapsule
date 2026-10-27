package dev.tushar.mycapsule

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One quick note. [id] stays the same when it is edited; [updatedAt] orders the list. */
data class Note(val id: String, val text: String, val updatedAt: Long) {
    /** The first line that has anything on it, which is how the deck names the note. */
    val title: String get() = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
}

/**
 * The quick notes, in this app's private storage and apart from the settings, so "Reset all settings" never deletes
 * what someone wrote.
 */
object NotesStore {

    private const val FILE = "notes"
    private const val KEY = "items"
    private const val MAX_NOTES = 50

    /** Newest edit first. */
    fun all(context: Context): List<Note> {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { array.getJSONObject(it).let { o -> Note(o.getString("id"), o.getString("text"), o.getLong("at")) } }
        }.getOrDefault(emptyList()).sortedByDescending { it.updatedAt }
    }

    fun get(context: Context, id: String) = all(context).firstOrNull { it.id == id }

    /** Saves [text] as the note [id], or as a new note when [id] is null. Blank text removes the note instead. */
    fun save(context: Context, id: String?, text: String) {
        val others = all(context).filter { it.id != id }
        if (text.isBlank()) return write(context, others)
        val note = Note(id ?: UUID.randomUUID().toString(), text.trim(), System.currentTimeMillis())
        write(context, (listOf(note) + others).take(MAX_NOTES))
    }

    fun delete(context: Context, id: String) = write(context, all(context).filter { it.id != id })

    private fun write(context: Context, notes: List<Note>) {
        val array = JSONArray()
        notes.forEach { array.put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.updatedAt)) }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}

/** The notes card of the deck: the latest notes as lines, a tap opens one to edit, and a button starts a new one. */
class NotesSource : CapsuleSource {

    override fun start(context: Context, emit: (CapsuleEvent) -> Unit) = Unit

    override fun card(context: Context): CapsuleEvent {
        val notes = NotesStore.all(context)
        val rows = notes.take(VISIBLE).map { note ->
            Row(text = note.title) { runCatching { context.startActivity(NoteActivity.intent(context, note.id)) } }
        }
        return CapsuleEvent(
            kind = Kind.NOTES,
            title = "Notes",
            text = if (rows.isEmpty()) "Nothing written yet" else "Tap a note to edit it",
            icon = context.getDrawable(R.drawable.ic_edit_note),
            rows = rows,
            actions = listOf(
                CapsuleAction(null, "Write a new note", label = "New note", dismisses = true) {
                    runCatching { context.startActivity(NoteActivity.intent(context, null)) }
                },
            ),
        )
    }

    private companion object {
        const val VISIBLE = 3
    }
}
