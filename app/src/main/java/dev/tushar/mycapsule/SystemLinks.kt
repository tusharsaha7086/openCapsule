package dev.tushar.mycapsule

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Where a system event's banner takes you: the settings page for the thing that changed. Android does not let an app
 * switch Wi-Fi, Bluetooth, the hotspot or airplane mode on or off itself, so the banner links to the page where you can.
 */
object SystemLinks {

    private const val SETTINGS_PACKAGE = "com.android.settings"

    /** Whether the banner for [event] stays until it is swiped away. Charging and unlocking are only a passing note. */
    fun sticky(event: SystemEvent) = when (event) {
        SystemEvent.CHARGING_STARTED, SystemEvent.CHARGING_STOPPED, SystemEvent.UNLOCK -> false
        else -> true
    }

    /** The pages to try for [event], best first; empty when there is nothing to open. */
    private fun candidates(event: SystemEvent): List<Intent> = when (event) {
        SystemEvent.CHARGING_STARTED, SystemEvent.CHARGING_STOPPED, SystemEvent.LOW_BATTERY ->
            listOf(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        SystemEvent.WIFI_CONNECTED, SystemEvent.WIFI_DISCONNECTED, SystemEvent.WIFI_ON, SystemEvent.WIFI_OFF ->
            listOf(Intent(Settings.ACTION_WIFI_SETTINGS))
        SystemEvent.LOCATION_ON, SystemEvent.LOCATION_OFF -> listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        // The hotspot has its own page on stock Android; other makers may not, so it falls back to the networks page.
        SystemEvent.HOTSPOT_ON, SystemEvent.HOTSPOT_OFF -> listOf(
            component("com.android.settings.Settings\$WifiTetherSettingsActivity"),
            component("com.android.settings.Settings\$TetherSettingsActivity"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
        SystemEvent.BLUETOOTH_ON, SystemEvent.BLUETOOTH_OFF, SystemEvent.HEADPHONES_CONNECTED, SystemEvent.HEADPHONES_DISCONNECTED ->
            listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        SystemEvent.AIRPLANE_ON, SystemEvent.AIRPLANE_OFF -> listOf(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
        SystemEvent.SOUND_MODE, SystemEvent.DND_ON, SystemEvent.DND_OFF -> listOf(Intent(Settings.ACTION_SOUND_SETTINGS))
        SystemEvent.VPN_CONNECTED, SystemEvent.VPN_DISCONNECTED -> listOf(Intent(Settings.ACTION_VPN_SETTINGS))
        SystemEvent.ROTATE_ON, SystemEvent.ROTATE_OFF -> listOf(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        SystemEvent.UNLOCK -> emptyList()
    }

    private fun component(className: String) = Intent().setComponent(ComponentName(SETTINGS_PACKAGE, className))

    /** The page to open for [event], or null if it has none. A page that this phone does not have is skipped. */
    fun intent(context: Context, event: SystemEvent): Intent? = candidates(event).firstOrNull { exists(context, it) }
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens the page for [event]. */
    fun open(context: Context, event: SystemEvent) {
        val intent = intent(context, event) ?: return
        runCatching { context.startActivity(intent) }
    }

    /** What tapping the banner does: the same page, as something the overlay can send. */
    fun pending(context: Context, event: SystemEvent): PendingIntent? {
        val intent = intent(context, event) ?: return null
        return PendingIntent.getActivity(context, event.ordinal, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Whether [intent] can be opened. Only pages named by their class need checking; the standard actions always exist. */
    private fun exists(context: Context, intent: Intent): Boolean {
        val target = intent.component ?: return true
        return runCatching { context.packageManager.getActivityInfo(target, 0) }.isSuccess
    }
}
