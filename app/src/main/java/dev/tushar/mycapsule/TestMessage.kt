package dev.tushar.mycapsule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.os.Bundle
import android.widget.Toast

/**
 * A chat notification posted by this app, with a Seen and a Reply button, so the notification look can be tried without
 * waiting for a real message. The capsule treats it like any other (it is marked so the usual "ignore our own
 * notifications" rule lets it through), and its buttons really work: [TestMessageReceiver] gets them.
 */
object TestMessage {
    const val EXTRA_TEST = "dev.tushar.mycapsule.TEST_MESSAGE"
    const val ACTION_SEEN = "dev.tushar.mycapsule.TEST_SEEN"
    const val ACTION_REPLY = "dev.tushar.mycapsule.TEST_REPLY"
    const val KEY_REPLY = "reply_text"

    private const val CHANNEL = "test_messages"
    const val ID = 4242
    private const val AVATAR_PX = 128

    fun post(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Test messages", NotificationManager.IMPORTANCE_DEFAULT))

        val me = Person.Builder().setName("You").build()
        val alex = Person.Builder().setName("Alex Rivera").setIcon(Icon.createWithBitmap(avatar("A", 0xFF7C5CFF.toInt()))).build()
        val now = System.currentTimeMillis()
        val style = Notification.MessagingStyle(me)
            .addMessage("Are we still on for tonight?", now - 60_000, alex)
            .addMessage("I booked the table for 8. Bring the tickets!", now, alex)

        fun receiver(action: String, mutable: Boolean) = PendingIntent.getBroadcast(
            context, action.hashCode(),
            // Addressed to the receiver itself: it has no intent filter, so an action alone would never reach it.
            Intent(context, TestMessageReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE,
        )

        val icon = Icon.createWithResource(context, R.drawable.ic_chat)
        val reply = Notification.Action.Builder(icon, "Reply", receiver(ACTION_REPLY, mutable = true))
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply to Alex").build())
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .build()
        val seen = Notification.Action.Builder(icon, "Mark as read", receiver(ACTION_SEEN, mutable = false))
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .build()

        manager.notify(
            ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_chat)
                .setStyle(style)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .addAction(reply)
                .addAction(seen)
                .addExtras(Bundle().apply { putBoolean(EXTRA_TEST, true) })
                .build(),
        )
    }

    /** A round avatar: a coloured disc with [letter] on it. */
    private fun avatar(letter: String, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(AVATAR_PX, AVATAR_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        canvas.drawCircle(AVATAR_PX / 2f, AVATAR_PX / 2f, AVATAR_PX / 2f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = AVATAR_PX * 0.5f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(letter, AVATAR_PX / 2f, AVATAR_PX / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        return bitmap
    }
}

/** Receives the test message's buttons: Seen and Reply do what a real chat app's would, in miniature. */
class TestMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        when (intent.action) {
            TestMessage.ACTION_SEEN -> {
                manager.cancel(TestMessage.ID)
                Toast.makeText(context, "Marked as read", Toast.LENGTH_SHORT).show()
            }
            TestMessage.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(TestMessage.KEY_REPLY)
                manager.cancel(TestMessage.ID)
                Toast.makeText(context, "Reply sent: $text", Toast.LENGTH_LONG).show()
            }
        }
    }
}
