package dev.tushar.mycapsule

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification

/**
 * Wires sources to the overlay. The enabled/permission gate lives in [emit], the one place every
 * source's events pass through, and [perform] is the one place idle-pill gestures turn into actions.
 * Sources start once notification access is actually connected, since media sessions can't be read
 * before that.
 */
class CapsuleService : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private val notifications = NotificationSource()
    private val media = MediaSource()
    private val flashlight = FlashlightSource()
    private val barcode = BarcodeSource()
    private val sources: List<CapsuleSource> =
        listOf(
            notifications, media, flashlight, TimerSource(), barcode, GestureReceiver(),
            ChargingSource(), SystemBroadcastSource(), NetworkSource(), HeadphonesSource(), RotationSource(),
            VolumeSource(), StopwatchSource(), ControlsSource(flashlight),
            ClipboardSource(), NotesSource(), CalendarSource(), BatterySource(), LauncherSource(), PomodoroSource(), StatsSource(),
        )

    private lateinit var prefs: Prefs
    private lateinit var overlay: CapsuleOverlay
    private lateinit var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener

    private lateinit var pet: PetOverlay

    /** The accessibility service connected or went away, so the pill (and the pet) move to the other window layer. */
    private val hostChanged: () -> Unit = {
        main.post {
            overlay.refreshIdle()
            pet.hostChanged()
            pet.sync()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        overlay = CapsuleOverlay(
            context = this,
            prefs = prefs,
            withIdlePill = true,
            // Cards switched off under Dynamic cards stay out of the deck, and the rest follow the order chosen there.
            deckProvider = {
                val order = prefs.cardOrder
                val cards = sources.mapNotNull { it.card(this) }
                    .filter { card -> DeckCard.of(card.kind)?.let(prefs::cardEnabled) ?: true }
                DeckCard.group(cards, order)
            },
            onGesture = ::perform,
        )
        pet = PetOverlay(this, prefs)
        prefsListener = prefs.listen {
            main.post {
                overlay.refreshIdle()
                pet.sync()
            }
        }
    }

    /** The phone switched between its light and dark theme (the pill takes the colour chosen for the new one), or turned. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        main.post {
            overlay.refreshIdle()
            pet.refreshWorld()
        }
    }

    override fun onListenerConnected() {
        OverlayHost.onChange(hostChanged)
        sources.forEach { it.start(this, ::emit) }
        overlay.start()
        pet.sync()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        // The ranking says how important the notification is to the user; the source uses it for the silent filter.
        val ranking = Ranking()
        val importance = if (rankingMap.getRanking(sbn.key, ranking)) ranking.importance else NotificationManager.IMPORTANCE_UNSPECIFIED
        notifications.onPosted(this, sbn, importance)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = notifications.onRemoved(sbn)

    private fun emit(event: CapsuleEvent) {
        if (!OverlayHost.canDraw(this) || !prefs.enabled(event.kind)) return
        // Phone events (Wi-Fi, Bluetooth and so on) each have a switch of their own on top of the kind's.
        val system = event.system
        if (system != null && !prefs.systemEnabled(system)) {
            // A turn-off nobody asked to see still ends the turn-on banner that is up: it swipes itself away.
            if (SystemLinks.ends(system)) main.post { overlay.retire(system.group) }
            return
        }
        main.post {
            overlay.show(event)
            pet.react(event)
        }
    }

    private fun perform(action: GestureAction) {
        when (action) {
            GestureAction.PLAY_PAUSE -> media.playPause()
            GestureAction.NEXT_TRACK -> media.skip(next = true)
            GestureAction.PREVIOUS_TRACK -> media.skip(next = false)
            GestureAction.FLASHLIGHT -> flashlight.toggle()
            GestureAction.BARCODE -> barcode.show(this)
            GestureAction.NONE, GestureAction.EXPAND -> Unit
        }
    }

    override fun onListenerDisconnected() = shutDown()

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }

    private fun shutDown() {
        OverlayHost.removeListener(hostChanged)
        sources.forEach { it.stop(this) }
        overlay.destroy()
        pet.stop()
    }

    /** Lets a broadcast perform a gesture by name (see [CapsuleOverlay.trigger]), e.g. from a widget or `adb`. */
    private inner class GestureReceiver : ReceiverSource(listOf(ACTION_GESTURE), exported = true) {
        override fun onBroadcast(context: Context, intent: Intent) {
            val name = intent.getStringExtra(EXTRA_NAME)
            main.post { overlay.trigger(name) }
        }
    }

    companion object {
        const val ACTION_GESTURE = "dev.tushar.mycapsule.GESTURE"
        const val EXTRA_NAME = "name"

        fun gestureIntent(context: Context, name: String) =
            Intent(ACTION_GESTURE).setPackage(context.packageName).putExtra(EXTRA_NAME, name)
    }
}

