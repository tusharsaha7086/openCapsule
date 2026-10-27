package dev.tushar.mycapsule

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * A small box to write or edit a note in. The capsule's window cannot take the keyboard, so the notes card opens this over
 * whatever is on screen, like the reply box. Save keeps the note; leaving it empty and saving removes it.
 */
class NoteActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        val existing = id?.let { NotesStore.get(this, it) }
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val field = EditText(this).apply {
            hint = "Write a note"
            setText(existing?.text.orEmpty())
            setSelection(text.length)
            setSingleLine(false)
            minLines = MIN_LINES
            maxLines = MAX_LINES
            gravity = Gravity.TOP or Gravity.START
            requestFocus()
        }
        fun save() {
            NotesStore.save(this, existing?.id, field.text.toString())
            finish()
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            if (existing != null) {
                addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Delete"
                    setOnClickListener {
                        NotesStore.delete(context, existing.id)
                        finish()
                    }
                })
            }
            addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Cancel"
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            addView(MaterialButton(context).apply {
                text = "Save"
                setOnClickListener { save() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(12))
            addView(TextView(context).apply {
                text = if (existing == null) "New note" else "Note"
                textSize = 18f
            })
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        })
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val MIN_LINES = 3
        private const val MAX_LINES = 8

        /** Opens the box on the note [id], or on an empty one when [id] is null. */
        fun intent(context: Context, id: String?) =
            Intent(context, NoteActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra(EXTRA_ID, id)
    }
}
