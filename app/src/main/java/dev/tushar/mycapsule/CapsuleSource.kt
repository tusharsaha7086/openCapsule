package dev.tushar.mycapsule

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat

/** Something that produces capsule events. Sources never draw; they only call [emit]. */
interface CapsuleSource {
    fun start(context: Context, emit: (CapsuleEvent) -> Unit)
    fun stop(context: Context) {}

    /** The card this source contributes to the expanded capsule, or null when it has nothing to show. */
    fun card(context: Context): CapsuleEvent? = null
}

/**
 * Turns posted notifications into events. Kind is decided in one place ([classify]); ongoing
 * notifications (call, navigation) are allowed through but de-duplicated so progress
 * updates that don't change the text don't re-trigger the capsule.
 */
class NotificationSource : CapsuleSource {

    private var emit: (CapsuleEvent) -> Unit = {}
    private val lastText = HashMap<String, CharSequence?>()

    override fun start(context: Context, emit: (CapsuleEvent) -> Unit) {
        this.emit = emit
    }

    fun onPosted(context: Context, sbn: StatusBarNotification, importance: Int = NotificationManager.IMPORTANCE_UNSPECIFIED) {
        val n = sbn.notification
        // Our own notifications are ignored, except the test message the notification look screen posts.
        if (sbn.packageName == context.packageName && !n.extras.getBoolean(TestMessage.EXTRA_TEST)) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category == Notification.CATEGORY_TRANSPORT) return // MediaSource owns media

        val prefs = Prefs(context)
        if (!prefs.notificationAllowed(sbn.packageName)) return

        val progress = progressOf(n)
        val kind = if (progress != null) Kind.PROGRESS else classify(n)
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        if (ongoing && kind == Kind.MESSAGE) return
        // Calls always show. Other ongoing ones (downloads, navigation) can be switched off.
        if (ongoing && kind != Kind.CALL && !prefs.showOngoingNotifications) return
        // "Silent" means the app marked it low importance. Only messages are held back for it: a download's progress is
        // nearly always low importance, and hiding it would hide every download.
        if (kind == Kind.MESSAGE && !prefs.showSilentNotifications && isSilent(importance)) return

        var title = n.extras.getCharSequence(Notification.EXTRA_TITLE)
        var text = n.extras.getCharSequence(Notification.EXTRA_TEXT)
        // Chat notifications: who wrote, their newest message and their avatar, in place of the app's name and icon.
        val chat = if (kind == Kind.MESSAGE && prefs.conversationLayout) NotificationLook.conversation(context, n) else null
        chat?.title?.takeIf { it.isNotBlank() }?.let { title = it }
        chat?.text?.takeIf { it.isNotBlank() }?.let { text = it }
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        // Progress is bucketed to 5% steps so a download doesn't re-trigger on every byte.
        val signature = "$title|$text|${progress?.let { (it * PROGRESS_STEPS).toInt() }}"
        if (lastText.put(sbn.key, signature) == signature) return

        val icon = chat?.avatar ?: runCatching { context.packageManager.getApplicationIcon(sbn.packageName) }.getOrNull()
        // The buttons the notification itself offers; Reply opens a small box to type in.
        val actions = if (kind == Kind.MESSAGE && prefs.notificationButtons) {
            NotificationLook.buttons(context, NotificationLook.actions(n), title)
        } else {
            emptyList()
        }
        emit(CapsuleEvent(kind, title, text, icon, n.contentIntent, actions = actions, progress = progress))
    }

    private fun isSilent(importance: Int) =
        importance != NotificationManager.IMPORTANCE_UNSPECIFIED && importance <= NotificationManager.IMPORTANCE_LOW

    /** 0..1 for a determinate, unfinished progress notification; null otherwise. */
    private fun progressOf(n: Notification): Float? {
        val max = n.extras.getInt(Notification.EXTRA_PROGRESS_MAX)
        if (max <= 0 || n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) return null
        return (n.extras.getInt(Notification.EXTRA_PROGRESS).toFloat() / max).takeIf { it < 1f }?.coerceAtLeast(0f)
    }

    fun onRemoved(sbn: StatusBarNotification) {
        lastText.remove(sbn.key)
    }

    private fun classify(n: Notification) = when (n.category) {
        Notification.CATEGORY_CALL -> Kind.CALL
        Notification.CATEGORY_NAVIGATION -> Kind.NAVIGATION
        else -> Kind.MESSAGE
    }

    private companion object {
        const val PROGRESS_STEPS = 20
    }
}


/**
 * A source driven by broadcasts. Subclasses declare which actions they listen for and handle them in
 * [onBroadcast]; registration and cleanup live here once.
 */
abstract class ReceiverSource(
    private val actions: List<String>,
    private val exported: Boolean,
) : CapsuleSource {

    protected var emit: (CapsuleEvent) -> Unit = {}
    private var receiver: BroadcastReceiver? = null

    protected abstract fun onBroadcast(context: Context, intent: Intent)

    override fun start(context: Context, emit: (CapsuleEvent) -> Unit) {
        this.emit = emit
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = onBroadcast(c, intent)
        }
        ContextCompat.registerReceiver(
            context,
            r,
            IntentFilter().apply { actions.forEach(::addAction) },
            if (exported) ContextCompat.RECEIVER_EXPORTED else ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiver = r
    }

    override fun stop(context: Context) {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }
}
