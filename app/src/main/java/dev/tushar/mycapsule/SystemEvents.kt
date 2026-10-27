package dev.tushar.mycapsule

/** The headings the system events are listed under on the System events screen. */
enum class SystemGroup(val label: String) {
    BATTERY("Battery"),
    WIFI("Wi-Fi"),
    HOTSPOT("Hotspot"),
    BLUETOOTH("Bluetooth"),
    AIRPLANE("Airplane mode"),
    SOUND("Sound"),
    HEADPHONES("Headphones"),
    VPN("VPN"),
    DISPLAY("Display"),
    OTHER("Other"),
}

/**
 * Every phone event that can show a capsule, each with its own switch. Adding one here adds its row on the System
 * events screen; a source in SystemSources.kt then only has to emit it. Charging is on by default (it was the one
 * system capsule before); the rest start off, so nothing new pops up until it is asked for.
 */
enum class SystemEvent(
    val group: SystemGroup,
    val label: String,
    val hint: String,
    val icon: Int,
    val defaultOn: Boolean = false,
) {
    CHARGING_STARTED(SystemGroup.BATTERY, "Charging started", "When you connect the charger", R.drawable.ic_battery_charging_full, true),
    CHARGING_STOPPED(SystemGroup.BATTERY, "Charging stopped", "When you disconnect the charger", R.drawable.ic_power_off, true),
    LOW_BATTERY(SystemGroup.BATTERY, "Low battery", "When the battery drops to the level below", R.drawable.ic_battery_alert, true),

    WIFI_CONNECTED(SystemGroup.WIFI, "Wi-Fi connected", "When a Wi-Fi network connects", R.drawable.ic_wifi),
    WIFI_DISCONNECTED(SystemGroup.WIFI, "Wi-Fi disconnected", "When a Wi-Fi network disconnects", R.drawable.ic_wifi_off),

    HOTSPOT_ON(SystemGroup.HOTSPOT, "Hotspot turned on", "When your phone starts sharing its connection over Wi-Fi", R.drawable.ic_wifi_tethering),
    HOTSPOT_OFF(SystemGroup.HOTSPOT, "Hotspot turned off", "When your phone stops sharing its connection", R.drawable.ic_wifi_tethering_off),

    BLUETOOTH_ON(SystemGroup.BLUETOOTH, "Bluetooth turned on", "When Bluetooth is switched on", R.drawable.ic_bluetooth),
    BLUETOOTH_OFF(SystemGroup.BLUETOOTH, "Bluetooth turned off", "When Bluetooth is switched off", R.drawable.ic_bluetooth_disabled),

    AIRPLANE_ON(SystemGroup.AIRPLANE, "Airplane mode on", "When airplane mode is switched on", R.drawable.ic_airplanemode_active),
    AIRPLANE_OFF(SystemGroup.AIRPLANE, "Airplane mode off", "When airplane mode is switched off", R.drawable.ic_airplanemode_inactive),

    SOUND_MODE(SystemGroup.SOUND, "Sound mode", "When you switch between ring, vibrate and silent", R.drawable.ic_volume_up),
    DND_ON(SystemGroup.SOUND, "Do Not Disturb on", "When Do Not Disturb is switched on", R.drawable.ic_do_not_disturb_on),
    DND_OFF(SystemGroup.SOUND, "Do Not Disturb off", "When Do Not Disturb is switched off", R.drawable.ic_do_not_disturb_off),

    HEADPHONES_CONNECTED(SystemGroup.HEADPHONES, "Headphones connected", "Wired, USB or Bluetooth headphones", R.drawable.ic_headphones),
    HEADPHONES_DISCONNECTED(SystemGroup.HEADPHONES, "Headphones disconnected", "When they are unplugged or disconnect", R.drawable.ic_headset_off),

    VPN_CONNECTED(SystemGroup.VPN, "VPN connected", "When a VPN turns on", R.drawable.ic_vpn_key),
    VPN_DISCONNECTED(SystemGroup.VPN, "VPN disconnected", "When a VPN turns off", R.drawable.ic_vpn_key_off),

    ROTATE_ON(SystemGroup.DISPLAY, "Auto-rotate on", "When auto-rotate is switched on", R.drawable.ic_screen_rotation),
    ROTATE_OFF(SystemGroup.DISPLAY, "Auto-rotate off", "When auto-rotate is switched off", R.drawable.ic_screen_lock_rotation),

    UNLOCK(SystemGroup.OTHER, "Device unlocked", "After you unlock your phone", R.drawable.ic_lock_open),
}
