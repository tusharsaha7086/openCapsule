package dev.tushar.mycapsule

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Parcelable
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The shape of the app icon or avatar on a notification capsule. */
enum class IconShape(val label: String) {
    ROUNDED("Rounded"),
    CIRCLE("Circle"),
    SQUIRCLE("Squircle"),
    SQUARE("Square"),
    HEXAGON("Hexagon"),
    ;

    /** Clips [view] to this shape. [roundedDp] is the corner size of [ROUNDED], which is what the capsule always used. */
    fun applyTo(view: View, roundedDp: Int) {
        val density = view.resources.displayMetrics.density
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                val w = v.width
                val h = v.height
                val side = min(w, h).toFloat()
                when (this@IconShape) {
                    ROUNDED -> outline.setRoundRect(0, 0, w, h, roundedDp * density)
                    CIRCLE -> outline.setOval(0, 0, w, h)
                    SQUIRCLE -> outline.setRoundRect(0, 0, w, h, side * SQUIRCLE_RADIUS)
                    SQUARE -> outline.setRoundRect(0, 0, w, h, SQUARE_DP * density)
                    HEXAGON -> outline.setConvexPath(hexagon(w.toFloat(), h.toFloat()))
                }
            }
        }
        view.invalidateOutline()
    }

    private fun hexagon(w: Float, h: Float): Path {
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f
        return Path().apply {
            for (i in 0 until HEX_SIDES) {
                // Pointing up: the first corner is at the top.
                val angle = -PI / 2 + i * 2 * PI / HEX_SIDES
                val x = cx + (r * cos(angle)).toFloat()
                val y = cy + (r * sin(angle)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }

    private companion object {
        const val SQUIRCLE_RADIUS = 0.38f
        const val SQUARE_DP = 3
        const val HEX_SIDES = 6
    }
}

/** The newest message of a chat notification, as the capsule shows it. */
class Conversation(val title: CharSequence?, val text: CharSequence?, val avatar: Drawable?)

/** The buttons a chat notification offers: mark it read, and reply to it. */
class MessageActions(val seen: Notification.Action?, val reply: Notification.Action?)

/** Reads chat notifications: who wrote, what, their avatar, and the Seen and Reply actions. */
object NotificationLook {

    /**
     * The newest message of [n] if it is a chat (a messaging-style notification), with the sender's name and avatar. A
     * notification that is not one still gets its large icon as an avatar, since chat apps set one.
     */
    @Suppress("DEPRECATION")
    fun conversation(context: Context, n: Notification): Conversation? {
        val raw: Array<Parcelable>? = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (raw != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val last = Notification.MessagingStyle.Message.getMessagesFromBundleArray(raw).lastOrNull()
            if (last != null) {
                val person = last.senderPerson
                val name = person?.name ?: last.sender
                val group = n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
                val groupTitle = n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                val title = if (group && !groupTitle.isNullOrBlank() && !name.isNullOrBlank()) "$name · $groupTitle" else name ?: groupTitle
                val avatar = runCatching { person?.icon?.loadDrawable(context) }.getOrNull()
                    ?: runCatching { n.getLargeIcon()?.loadDrawable(context) }.getOrNull()
                return Conversation(title, last.text, avatar)
            }
        }
        val large = runCatching { n.getLargeIcon()?.loadDrawable(context) }.getOrNull() ?: return null
        return Conversation(null, null, large)
    }

    /** The Seen and Reply actions of [n], when it has them. */
    fun actions(n: Notification): MessageActions {
        val all = n.actions?.toList().orEmpty()
        fun title(a: Notification.Action) = a.title?.toString().orEmpty().trim().lowercase()
        val seen = all.firstOrNull {
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && it.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ) ||
                title(it) in SEEN_TITLES
        }
        val reply = all.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true &&
                ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && action.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY) ||
                    "reply" in title(action) || title(action) in REPLY_TITLES)
        } ?: all.firstOrNull { it.remoteInputs?.any { input -> input.allowFreeFormInput } == true }
        return MessageActions(seen, reply)
    }

    /** The capsule buttons for [actions]: Seen runs the notification's own mark-as-read; Reply opens a box to type in. */
    fun buttons(context: Context, actions: MessageActions, sender: CharSequence?): List<CapsuleAction> = buildList {
        actions.seen?.actionIntent?.let { intent ->
            add(CapsuleAction(null, "Mark as read", label = "Seen") { runCatching { intent.send() } })
        }
        val reply = actions.reply
        val input = reply?.remoteInputs?.firstOrNull { it.allowFreeFormInput }
        if (reply?.actionIntent != null && input != null) {
            add(CapsuleAction(null, "Reply", label = "Reply") {
                context.startActivity(ReplyActivity.intent(context, reply.actionIntent, input, sender))
            })
        }
    }

    private val SEEN_TITLES = setOf("mark as read", "seen", "read", "mark read")
    private val REPLY_TITLES = setOf("reply", "respond", "answer")
}

/** Sends [text] as the answer to [input] through [action], the way a notification's reply box does. */
fun sendReply(context: Context, action: PendingIntent, input: RemoteInput, text: CharSequence) {
    val results = android.os.Bundle().apply { putCharSequence(input.resultKey, text) }
    val fill = Intent()
    RemoteInput.addResultsToIntent(arrayOf(input), fill, results)
    action.send(context, 0, fill)
}
