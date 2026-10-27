package dev.tushar.mycapsule

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.location.LocationManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/*
 * Sources for phone events: the charger, the battery, Wi-Fi, Bluetooth, airplane mode, sound mode, Do Not Disturb,
 * headphones, VPN, auto-rotate and unlocking. Each one only notices a change and calls emit with an event that
 * says which [SystemEvent] it is; the service holds back the ones the user has switched off.
 *
 * Every source remembers the last state it saw and only reports real changes, so starting up (or a system that
 * repeats itself) never shows a capsule for something that did not just happen.
 */

private fun capsule(context: Context, event: SystemEvent, title: String, text: String? = null, icon: Int = event.icon): CapsuleEvent {
    val prefs = Prefs(context)
    // The banner links to the settings page for what changed: a Settings button, and the same when it is tapped.
    val link = if (prefs.bannerSettingsButton) SystemLinks.pending(context, event) else null
    val actions = if (link != null) {
        listOf(CapsuleAction(null, "Open settings for ${event.label}", label = "Settings", dismisses = true) { SystemLinks.open(context, event) })
    } else {
        emptyList()
    }
    return CapsuleEvent(
        kind = Kind.SYSTEM,
        title = title,
        text = text,
        icon = context.getDrawable(icon),
        onTap = link,
        actions = actions,
        // Most stay until swiped up, so a change is not missed; charging and unlocking are only a passing note.
        persistent = prefs.bannersSticky && SystemLinks.sticky(event),
        system = event,
    )
}

/** The battery level in percent from a battery broadcast, or null if it did not say. */
private fun levelOf(intent: Intent): Int? {
    val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
    return if (level < 0 || scale <= 0) null else level * PERCENT / scale
}

private const val PERCENT = 100

/** The charger being plugged in or removed, and the battery running low. */
class ChargingSource : ReceiverSource(
    actions = listOf(Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED, Intent.ACTION_BATTERY_CHANGED),
    exported = false,
) {
    /** Whether the low-battery capsule has been shown for this drop; null until the first battery report. */
    private var lowShown: Boolean? = null

    override fun onBroadcast(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> emit(capsule(context, SystemEvent.CHARGING_STARTED, "Charging", batteryText(context)))
            Intent.ACTION_POWER_DISCONNECTED -> emit(capsule(context, SystemEvent.CHARGING_STOPPED, "Unplugged", batteryText(context)))
            Intent.ACTION_BATTERY_CHANGED -> onBattery(context, intent)
        }
    }

    private fun onBattery(context: Context, intent: Intent) {
        val level = levelOf(intent) ?: return
        val threshold = Prefs(context).lowBatteryPercent
        val charging = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
        val shown = lowShown
        when {
            // The first report is the sticky one sent on registering: it says where we are, it is not news.
            shown == null -> lowShown = level <= threshold
            charging || level > threshold -> lowShown = false
            !shown && level <= threshold -> {
                lowShown = true
                emit(capsule(context, SystemEvent.LOW_BATTERY, "Battery low", "$level% left"))
            }
        }
    }

    private fun batteryText(context: Context): String? {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        return levelOf(sticky)?.let { "$it%" }
    }
}

/** Bluetooth, airplane mode, sound mode and Do Not Disturb switching, and the phone being unlocked. */
class SystemBroadcastSource : ReceiverSource(
    actions = listOf(
        BluetoothAdapter.ACTION_STATE_CHANGED,
        Intent.ACTION_AIRPLANE_MODE_CHANGED,
        AudioManager.RINGER_MODE_CHANGED_ACTION,
        NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED,
        Intent.ACTION_USER_PRESENT,
        ACTION_HOTSPOT_STATE,
        WifiManager.WIFI_STATE_CHANGED_ACTION,
        LocationManager.MODE_CHANGED_ACTION,
        LocationManager.PROVIDERS_CHANGED_ACTION,
    ),
    exported = false,
) {
    private var bluetooth: Boolean? = null
    private var airplane: Boolean? = null
    private var ringer: Int? = null
    private var doNotDisturb: Boolean? = null

    /** Whether the hotspot is on, as far as we have been told. The system only announces changes, so this starts unknown. */
    private var hotspot: Boolean? = null
    private var wifi: Boolean? = null
    private var location: Boolean? = null

    override fun start(context: Context, emit: (CapsuleEvent) -> Unit) {
        // Learn where things stand now, so the first broadcast is compared with something.
        bluetooth = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled }.getOrNull()
        airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        ringer = context.getSystemService(AudioManager::class.java)?.ringerMode
        doNotDisturb = isDoNotDisturb(context)
        wifi = runCatching { context.getSystemService(WifiManager::class.java)?.isWifiEnabled }.getOrNull()
        location = isLocationOn(context)
        super.start(context, emit)
    }

    override fun onBroadcast(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> onBluetooth(context, intent)
            Intent.ACTION_AIRPLANE_MODE_CHANGED -> onAirplane(context, intent)
            AudioManager.RINGER_MODE_CHANGED_ACTION -> onRinger(context, intent)
            NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> onDoNotDisturb(context)
            ACTION_HOTSPOT_STATE -> onHotspot(context, intent)
            WifiManager.WIFI_STATE_CHANGED_ACTION -> onWifi(context, intent)
            LocationManager.MODE_CHANGED_ACTION, LocationManager.PROVIDERS_CHANGED_ACTION -> onLocation(context)
            Intent.ACTION_USER_PRESENT -> emit(capsule(context, SystemEvent.UNLOCK, "Unlocked"))
        }
    }

    private fun onWifi(context: Context, intent: Intent) {
        // Only the settled states count; "turning on" and "turning off" are on the way to one of these.
        val on = when (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)) {
            WifiManager.WIFI_STATE_ENABLED -> true
            WifiManager.WIFI_STATE_DISABLED -> false
            else -> return
        }
        if (wifi == on) return
        wifi = on
        emit(
            if (on) capsule(context, SystemEvent.WIFI_ON, "Wi-Fi on")
            else capsule(context, SystemEvent.WIFI_OFF, "Wi-Fi off"),
        )
    }

    private fun onLocation(context: Context) {
        val on = isLocationOn(context) ?: return
        // Android announces both the mode and the providers changing for one switch; only a real change counts.
        if (location == on) return
        location = on
        emit(
            if (on) capsule(context, SystemEvent.LOCATION_ON, "Location on")
            else capsule(context, SystemEvent.LOCATION_OFF, "Location off"),
        )
    }

    private fun isLocationOn(context: Context): Boolean? =
        runCatching { context.getSystemService(LocationManager::class.java)?.isLocationEnabled }.getOrNull()

    private fun onHotspot(context: Context, intent: Intent) {
        // Only the settled states count: 13 is on and 11 is off; 12 (turning on), 10 (turning off) and 14 (failed) are not news.
        val on = when (intent.getIntExtra(EXTRA_HOTSPOT_STATE, -1)) {
            HOTSPOT_ENABLED -> true
            HOTSPOT_DISABLED -> false
            else -> return
        }
        if (hotspot == on) return
        hotspot = on
        emit(
            if (on) capsule(context, SystemEvent.HOTSPOT_ON, "Hotspot on")
            else capsule(context, SystemEvent.HOTSPOT_OFF, "Hotspot off"),
        )
    }

    private fun onBluetooth(context: Context, intent: Intent) {
        // Only the settled states count; "turning on" and "turning off" are on the way to one of these.
        val on = when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
            BluetoothAdapter.STATE_ON -> true
            BluetoothAdapter.STATE_OFF -> false
            else -> return
        }
        if (bluetooth == on) return
        bluetooth = on
        emit(
            if (on) capsule(context, SystemEvent.BLUETOOTH_ON, "Bluetooth on")
            else capsule(context, SystemEvent.BLUETOOTH_OFF, "Bluetooth off"),
        )
    }

    private fun onAirplane(context: Context, intent: Intent) {
        val on = intent.getBooleanExtra("state", false)
        if (airplane == on) return
        airplane = on
        emit(
            if (on) capsule(context, SystemEvent.AIRPLANE_ON, "Airplane mode on")
            else capsule(context, SystemEvent.AIRPLANE_OFF, "Airplane mode off"),
        )
    }

    private fun onRinger(context: Context, intent: Intent) {
        val mode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)
        if (mode < 0 || ringer == mode) return
        ringer = mode
        val (title, icon) = when (mode) {
            AudioManager.RINGER_MODE_SILENT -> "Silent" to R.drawable.ic_volume_off
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate" to R.drawable.ic_vibration
            else -> "Ring" to R.drawable.ic_volume_up
        }
        emit(capsule(context, SystemEvent.SOUND_MODE, title, "Sound mode", icon))
    }

    private fun onDoNotDisturb(context: Context) {
        val on = isDoNotDisturb(context)
        if (doNotDisturb == on) return
        doNotDisturb = on
        emit(
            if (on) capsule(context, SystemEvent.DND_ON, "Do Not Disturb on")
            else capsule(context, SystemEvent.DND_OFF, "Do Not Disturb off"),
        )
    }

    private companion object {
        // The hotspot's announcement has no public constants, but the system has always sent it to every app that listens.
        const val ACTION_HOTSPOT_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        const val EXTRA_HOTSPOT_STATE = "wifi_state"
        const val HOTSPOT_DISABLED = 11
        const val HOTSPOT_ENABLED = 13
    }

    private fun isDoNotDisturb(context: Context): Boolean {
        val filter = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        return filter != null && filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }
}

/** Wi-Fi and VPN coming and going, watched through the system's network callbacks. */
class NetworkSource : CapsuleSource {

    private var manager: ConnectivityManager? = null
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()

    override fun start(context: Context, emit: (CapsuleEvent) -> Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        manager = cm

        track(
            cm,
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            initiallyUp = anyNetwork(cm, NetworkCapabilities.TRANSPORT_WIFI),
        ) { up ->
            emit(
                if (up) capsule(context, SystemEvent.WIFI_CONNECTED, "Wi-Fi connected")
                else capsule(context, SystemEvent.WIFI_DISCONNECTED, "Wi-Fi disconnected"),
            )
        }
        track(
            cm,
            // A VPN network does not claim to be "not a VPN", so that capability is dropped from the request.
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            initiallyUp = anyNetwork(cm, NetworkCapabilities.TRANSPORT_VPN),
        ) { up ->
            emit(
                if (up) capsule(context, SystemEvent.VPN_CONNECTED, "VPN connected")
                else capsule(context, SystemEvent.VPN_DISCONNECTED, "VPN disconnected"),
            )
        }
    }

    override fun stop(context: Context) {
        callbacks.forEach { runCatching { manager?.unregisterNetworkCallback(it) } }
        callbacks.clear()
    }

    /** Reports only changes between up and down; the callbacks also announce what is already there when they start. */
    private fun track(cm: ConnectivityManager, request: NetworkRequest, initiallyUp: Boolean, onChange: (Boolean) -> Unit) {
        var up = initiallyUp
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (up) return
                up = true
                onChange(true)
            }

            override fun onLost(network: Network) {
                if (!up) return
                up = false
                onChange(false)
            }
        }
        callbacks += callback
        cm.registerNetworkCallback(request, callback)
    }

    @Suppress("DEPRECATION")
    private fun anyNetwork(cm: ConnectivityManager, transport: Int) =
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(transport) == true }
}

/**
 * Headphones joining or leaving: wired, USB and Bluetooth ones alike, seen as audio outputs. Needs no Bluetooth
 * permission, since it watches the audio system and not the Bluetooth radio.
 */
class HeadphonesSource : CapsuleSource {

    private var audio: AudioManager? = null
    private var callback: AudioDeviceCallback? = null
    private val present = HashSet<Int>()

    override fun start(context: Context, emit: (CapsuleEvent) -> Unit) {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        audio = manager
        // Whatever is already connected is not news; the callback also lists those when it starts.
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter(::isHeadphones).forEach { present += it.id }

        val watcher = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                added.filter { isHeadphones(it) && present.add(it.id) }.firstOrNull()?.let {
                    emit(capsule(context, SystemEvent.HEADPHONES_CONNECTED, "Headphones connected", nameOf(it)))
                }
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                removed.filter { isHeadphones(it) && present.remove(it.id) }.firstOrNull()?.let {
                    emit(capsule(context, SystemEvent.HEADPHONES_DISCONNECTED, "Headphones disconnected", nameOf(it)))
                }
            }
        }
        callback = watcher
        manager.registerAudioDeviceCallback(watcher, Handler(Looper.getMainLooper()))
    }

    override fun stop(context: Context) {
        callback?.let { audio?.unregisterAudioDeviceCallback(it) }
        callback = null
        present.clear()
    }

    private fun isHeadphones(device: AudioDeviceInfo) = device.isSink && device.type in TYPES

    /** A Bluetooth headset has a name worth showing; a wired one reports the phone's own name, so it gets none. */
    private fun nameOf(device: AudioDeviceInfo): String? =
        if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || device.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
            device.productName?.toString()?.takeIf { it.isNotBlank() }
        } else {
            null
        }

    private companion object {
        val TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
    }
}

/** Auto-rotate being switched on or off, noticed through the setting itself. */
class RotationSource : CapsuleSource {

    private var observer: ContentObserver? = null

    override fun start(context: Context, emit: (CapsuleEvent) -> Unit) {
        val resolver = context.contentResolver
        var on = isOn(context)
        val watcher = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val now = isOn(context)
                if (now == on) return
                on = now
                emit(
                    if (now) capsule(context, SystemEvent.ROTATE_ON, "Auto-rotate on")
                    else capsule(context, SystemEvent.ROTATE_OFF, "Auto-rotate off"),
                )
            }
        }
        observer = watcher
        resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, watcher)
    }

    override fun stop(context: Context) {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    private fun isOn(context: Context) =
        Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
}
