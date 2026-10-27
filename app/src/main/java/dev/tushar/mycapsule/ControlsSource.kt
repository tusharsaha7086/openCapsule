package dev.tushar.mycapsule

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.provider.Settings

/**
 * The quick settings card of the deck: the flashlight, Wi-Fi, Bluetooth, hotspot, airplane mode, Do Not Disturb,
 * auto-rotate, location and VPN as tiles lit while they are on. It always has something to show. The torch switches
 * right on the tile; Android does not let an app change the others, so their tiles open the settings page for them.
 *
 * Nothing here watches for changes: the card reads every state afresh each time the deck builds it. The hotspot is the
 * one exception, since only the system's announcements tell its state.
 */
class ControlsSource(private val flashlight: FlashlightSource) : ReceiverSource(listOf(ACTION_HOTSPOT_STATE), exported = false) {

    /** Whether the hotspot is on, as far as we know: asked of the system at the start, then kept up by its announcements. */
    private var hotspot = false

    override fun start(context: Context, emit: (CapsuleEvent) -> Unit) {
        super.start(context, emit)
        // The hotspot has no public way to ask; where the system still answers to the old call it is used.
        hotspot = runCatching {
            WifiManager::class.java.getMethod("isWifiApEnabled").invoke(context.getSystemService(WifiManager::class.java)) as Boolean
        }.getOrDefault(false)
    }

    override fun onBroadcast(context: Context, intent: Intent) {
        when (intent.getIntExtra(EXTRA_HOTSPOT_STATE, -1)) {
            HOTSPOT_ENABLED -> hotspot = true
            HOTSPOT_DISABLED -> hotspot = false
        }
    }

    override fun card(context: Context): CapsuleEvent {
        val torch = flashlight.isOn()
        val tiles = buildList {
            if (torch != null) {
                add(Tile(context.getDrawable(if (torch) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off), "Flashlight", torch) {
                    flashlight.toggle()
                })
            }
            add(link(context, "Wi-Fi", isWifiOn(context), SystemEvent.WIFI_ON, SystemEvent.WIFI_OFF))
            add(link(context, "Bluetooth", isBluetoothOn(context), SystemEvent.BLUETOOTH_ON, SystemEvent.BLUETOOTH_OFF))
            add(link(context, "Hotspot", hotspot, SystemEvent.HOTSPOT_ON, SystemEvent.HOTSPOT_OFF))
            add(link(context, "Airplane", isAirplaneOn(context), SystemEvent.AIRPLANE_ON, SystemEvent.AIRPLANE_OFF))
            add(link(context, "Do Not Disturb", isDoNotDisturb(context), SystemEvent.DND_ON, SystemEvent.DND_OFF))
            add(link(context, "Auto-rotate", isRotateOn(context), SystemEvent.ROTATE_ON, SystemEvent.ROTATE_OFF))
            add(link(context, "Location", isLocationOn(context), SystemEvent.LOCATION_ON, SystemEvent.LOCATION_OFF))
            add(link(context, "VPN", isVpnOn(context), SystemEvent.VPN_CONNECTED, SystemEvent.VPN_DISCONNECTED))
        }
        return CapsuleEvent(
            kind = Kind.SYSTEM,
            title = "Quick settings",
            text = "Tap one to change it",
            icon = context.getDrawable(R.drawable.ic_tune),
            tiles = tiles,
            live = torch == true,
        )
    }

    /** A tile that opens the settings page for [onEvent] and [offEvent], with the icon of whichever state it is in. */
    private fun link(context: Context, label: String, on: Boolean, onEvent: SystemEvent, offEvent: SystemEvent) =
        Tile(context.getDrawable((if (on) onEvent else offEvent).icon), label, on, dismisses = true) {
            SystemLinks.open(context, onEvent)
        }

    private fun isWifiOn(context: Context) =
        runCatching { context.getSystemService(WifiManager::class.java)?.isWifiEnabled }.getOrNull() == true

    private fun isBluetoothOn(context: Context) =
        runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled }.getOrNull() == true

    private fun isAirplaneOn(context: Context) =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

    private fun isRotateOn(context: Context) =
        Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

    private fun isLocationOn(context: Context) =
        runCatching { context.getSystemService(LocationManager::class.java)?.isLocationEnabled }.getOrNull() == true

    private fun isDoNotDisturb(context: Context): Boolean {
        val filter = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        return filter != null && filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private fun isVpnOn(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities)
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    private companion object {
        // The hotspot's announcement has no public constants, but the system has always sent it to every app that listens.
        const val ACTION_HOTSPOT_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        const val EXTRA_HOTSPOT_STATE = "wifi_state"
        const val HOTSPOT_DISABLED = 11
        const val HOTSPOT_ENABLED = 13
    }
}
