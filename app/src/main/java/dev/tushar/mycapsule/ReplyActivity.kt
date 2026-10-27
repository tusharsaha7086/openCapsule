package dev.tushar.mycapsule

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * A small box to type a reply in. The capsule's window cannot take the keyboard, so its Reply button opens this over
 * whatever is on screen, sends the text through the notification's own reply action, and closes.
 */
class ReplyActivity : AppCompatActivity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getParcelableExtra<PendingIntent>(EXTRA_ACTION)
        val input = intent.getParcelableExtra<RemoteInput>(EXTRA_INPUT)
        if (action == null || input == null) {
            finish()
            return
        }
        val sender = intent.getCharSequenceExtra(EXTRA_SENDER)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val field = EditText(this).apply {
            hint = input.label ?: "Reply"
            setSingleLine(false)
            maxLines = MAX_LINES
            requestFocus()
        }
        fun send() {
            val text = field.text.toString().trim()
            if (text.isEmpty()) return
            runCatching { sendReply(this, action, input, text) }
                .onFailure { Toast.makeText(this, "Could not send that reply", Toast.LENGTH_SHORT).show() }
            finish()
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Cancel"
                setOnClickListener { finish() }
            })
            addView(MaterialButton(context).apply {
                text = "Send"
                setOnClickListener { send() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(12))
            addView(TextView(context).apply {
                text = if (sender.isNullOrBlank()) "Reply" else "Reply to $sender"
                textSize = 18f
            })
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        })
    }

    companion object {
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_INPUT = "input"
        private const val EXTRA_SENDER = "sender"
        private const val MAX_LINES = 4

        fun intent(context: Context, action: PendingIntent, input: RemoteInput, sender: CharSequence?) =
            Intent(context, ReplyActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra(EXTRA_ACTION, action)
                .putExtra(EXTRA_INPUT, input)
                .putExtra(EXTRA_SENDER, sender)
    }
}
