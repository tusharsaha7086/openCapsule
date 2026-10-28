package dev.tushar.mycapsule

import android.graphics.Typeface
import androidx.appcompat.app.AppCompatDelegate

/** Light or dark for this app's screens. It shows in the Material You style; the other styles keep their own fixed look. */
enum class AppTheme(val label: String, val nightMode: Int) {
    SYSTEM("System", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("Light", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("Dark", AppCompatDelegate.MODE_NIGHT_YES),
}

/**
 * The typeface of this app's own screens. These are the families every Android phone has, so nothing is downloaded or
 * bundled. The capsule keeps its own type.
 */
enum class AppFont(val label: String, private val family: String, private val boldFamily: String) {
    DEFAULT("Default", "sans-serif", "sans-serif-medium"),
    SERIF("Serif", "serif", "serif"),
    MONO("Monospace", "monospace", "monospace"),
    CONDENSED("Condensed", "sans-serif-condensed", "sans-serif-condensed"),
    CASUAL("Casual", "casual", "casual"),
    ;

    fun typeface(bold: Boolean): Typeface =
        if (bold) Typeface.create(boldFamily, Typeface.BOLD) else Typeface.create(family, Typeface.NORMAL)
}

/** The font in use. Held here so every skin reads the same one when it picks a typeface. */
object AppFonts {
    var current: AppFont = AppFont.DEFAULT
}

/** One line of the What's new or Tips screen. */
class InfoItem(val icon: Int, val title: String, val text: String)

/** What this release contains, grouped as the settings are. */
object ReleaseNotes {
    val items = listOf(
        InfoItem(R.drawable.ic_widgets, "Capsules for what happens on your phone",
            "Notifications, music, calls, navigation, downloads, the timer, a stopwatch, the flashlight, the volume and a saved barcode each get a capsule that grows out of the idle pill."),
        InfoItem(R.drawable.ic_tune, "A redesigned deck",
            "Related cards now share a page with tabs: quick settings with the launcher, Pomodoro with the timers, the clipboard with notes and the calendar, and the battery with phone stats. Every page is the same size, and a running timer shows a progress ring. The pages and their order are under Capsule, Dynamic cards."),
        InfoItem(R.drawable.ic_palette, "Deck colors",
            "Give the deck its own background and text color for the light and the dark theme, from presets or any color of your own. Icons always match the text."),
        InfoItem(R.drawable.ic_tune, "System events",
            "Charging, low battery, Wi-Fi, the hotspot, Bluetooth, airplane mode, sound mode, Do Not Disturb, headphones, VPN, auto-rotate and unlocking, each with its own switch. A banner stays until you swipe it up and opens the right settings page, and it closes by itself when that thing is switched off."),
        InfoItem(R.drawable.ic_swap_vert, "Several banners at once",
            "The first stays in the centre; the rest wait at the sides as small icons. Press one to bring it forward."),
        InfoItem(R.drawable.ic_chat, "Chat notifications",
            "The sender and their avatar, a choice of icon shape, and Seen and Reply buttons that work."),
        InfoItem(R.drawable.ic_notifications, "Notification filters",
            "Allow or block each app, and leave out silent or ongoing notifications."),
        InfoItem(R.drawable.ic_style, "Seven looks",
            "Material You, Glass, Liquid Glass, Skeuomorphic, Neumorphic, Clay and Brutalist, for the settings and the capsule."),
        InfoItem(R.drawable.ic_palette, "Your colours",
            "A capsule colour for the light theme and another for the dark one, with presets or any colour of your own, and your own text and border colours."),
        InfoItem(R.drawable.ic_animation, "Motion",
            "Five expand styles, a duration, bounce, and a switch to reduce animations."),
        InfoItem(R.drawable.ic_open_with, "Position and screens",
            "Drag the capsule where you want it, resize the idle pill, and keep a separate position for portrait, landscape and large screens."),
    )
}

/** Short pointers for getting more out of the app, and for the things that most often go wrong. */
object Tips {
    val items = listOf(
        InfoItem(R.drawable.ic_circle, "Hide the pill, keep the touch",
            "Set the idle pill's opacity to 0% and it blends into the camera, but a tap on it still opens your cards."),
        InfoItem(R.drawable.ic_ads_click, "Swipe a banner up to dismiss it",
            "System banners stay until you do. When several are waiting, the first is in the centre and the others sit at the sides; press one to bring it forward."),
        InfoItem(R.drawable.ic_touch_app, "Open the cards with a gesture",
            "Tap the pill for the deck. While music plays or a timer runs, the gesture you pick under Capsule, Dynamic cards opens that card directly."),
        InfoItem(R.drawable.ic_open_with, "Small camera cutout?",
            "Under Customize, Position, size the pill and drag the capsule to sit just where you want it. Landscape and large screens keep their own spot."),
        InfoItem(R.drawable.ic_palette, "Different colours for day and night",
            "Under Customize, Colors, pick one capsule colour for the light theme and another for the dark one. Light colours get dark text on their own."),
        InfoItem(R.drawable.ic_battery_alert, "If the capsule stops showing up",
            "The system can stop apps in the background. Allow this app to run in the background (the card under Capsule, Setup), and check that notification access is still on."),
        InfoItem(R.drawable.ic_accessibility_new, "Accessibility turns itself off",
            "Force-stopping the app makes Android switch the optional accessibility service off, and some phones do it after a restart. Turn it on again in Accessibility settings. A sideloaded app may first need Allow restricted settings in its App info."),
        InfoItem(R.drawable.ic_warning, "One capsule app at a time",
            "Another capsule app puts its pill over the same camera, and the one on top takes the taps. The setup card warns you when it sees Material Capsule."),
        InfoItem(R.drawable.ic_slow_motion_video, "On a slower phone",
            "Turn on Reduce animations under Customize, Animation. Capsules then fade in place instead of growing."),
    )
}
