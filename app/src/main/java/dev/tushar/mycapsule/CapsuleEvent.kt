package dev.tushar.mycapsule

import android.app.PendingIntent
import android.graphics.drawable.Drawable

/**
 * Every kind of capsule in one place. Adding a kind here automatically adds its settings toggle,
 * its default duration and its priority; nothing else needs to change.
 */
enum class Kind(val label: String, val defaultMs: Long, val priority: Int, val defaultOn: Boolean = true) {
    MEDIA("Media", 6_000, 0),
    VOLUME("Volume", 2_500, 1, defaultOn = false),
    MESSAGE("Notifications", 4_000, 1),
    SYSTEM("System events", 2_500, 1),
    FLASHLIGHT("Flashlight", 3_000, 1),
    TIMER("Timer", 0, 1),
    STOPWATCH("Stopwatch", 0, 1),
    BARCODE("Barcode", 30_000, 2),
    CLIPBOARD("Clipboard", 0, 1, defaultOn = false),
    NOTES("Notes", 0, 1, defaultOn = false),
    BATTERY("Battery", 0, 1, defaultOn = false),
    STATS("Phone stats", 0, 1, defaultOn = false),
    CALENDAR("Calendar", 0, 1, defaultOn = false),
    LAUNCHER("Quick launcher", 0, 1, defaultOn = false),
    POMODORO("Pomodoro", 5_000, 1),
    PROGRESS("Downloads and progress", 5_000, 1),
    NAVIGATION("Navigation", 5_000, 2),
    CALL("Calls", 8_000, 3),
}

/**
 * A button on the capsule. The overlay renders any list of these; it knows nothing about media or
 * timers. A non-null [label] draws a text pill instead of an icon.
 */
data class CapsuleAction(
    val icon: Drawable?,
    val description: String,
    val label: String? = null,
    /** Whether the capsule goes away once this button has been pressed (a button that leaves for another screen). */
    val dismisses: Boolean = false,
    val run: () -> Unit,
)

/**
 * Where a track is right now, for the playback bar in the deck. [positionMs] is already up to date (a source
 * works it out from the player's last report and the time since). [seekTo] is null when the player can't seek.
 */
class Playback(
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val seekTo: ((Long) -> Unit)?,
)

/**
 * A switch on the quick settings card: an icon over a name, lit while [on]. [run] does the change, or opens the page that
 * does, and [dismisses] closes the deck afterwards (for a tile that leaves for another screen).
 */
class Tile(
    val icon: Drawable?,
    val label: String,
    val on: Boolean,
    val dismisses: Boolean = false,
    /** False for a picture that keeps its own colours (an app's icon); a plain glyph takes the capsule's text colour. */
    val tinted: Boolean = true,
    val run: () -> Unit,
)

/**
 * One line of a list card (copied items, notes). [number] is drawn before the text. [run] happens on a tap, and
 * [dismisses] closes the deck afterwards; a row that stays redraws the card instead. [onLongPress] is optional.
 */
class Row(
    val text: String,
    val number: Int? = null,
    val dismisses: Boolean = true,
    val onLongPress: (() -> Unit)? = null,
    val run: () -> Unit,
)

/** The single model every [CapsuleSource] emits and [CapsuleOverlay] renders. */
data class CapsuleEvent(
    val kind: Kind,
    val title: CharSequence?,
    val text: CharSequence?,
    val icon: Drawable?,
    val onTap: PendingIntent? = null,
    /** Buttons on the compact capsule. */
    val actions: List<CapsuleAction> = emptyList(),
    /** Buttons on the expanded capsule (long-press); an empty list means it can't expand. */
    val expandedActions: List<CapsuleAction> = emptyList(),
    /** Background colour; null means the default black. */
    val accent: Int? = null,
    /** 0..1 shows a progress bar under the text; null shows none. */
    val progress: Float? = null,
    /**
     * Reads the current playback state, or null when there is none (a live stream has no length). It is a function
     * and not a value so the deck can ask again every moment and the bar keeps moving. Shown in the deck only.
     */
    val playback: (() -> Playback?)? = null,
    /** A large picture (e.g. a barcode) drawn on a white panel under the header. */
    val image: Drawable? = null,
    /** Stays on screen until the user dismisses it instead of hiding after the display time. */
    val persistent: Boolean = false,
    /** Never interrupts a capsule of another kind, and is replaced by any other event. */
    val ambient: Boolean = false,
    /** Called only when the user dismisses it (tap or swipe), not when another capsule replaces it. */
    val onDismiss: (() -> Unit)? = null,
    /** Which phone event this is, for the ones with a switch of their own; null for every other capsule. */
    val system: SystemEvent? = null,
    /**
     * Something is happening right now: music is playing, a timer or stopwatch is running, the torch is on, the volume
     * is in use. Only cards say so. While one is live, the chosen gesture on the pill opens it.
     */
    val live: Boolean = false,
    /** A deck card that holds several cards one above the other (the timer and the stopwatch); it draws these, not itself. */
    val sections: List<CapsuleEvent> = emptyList(),
    /** Switches drawn as a grid under the header, in the deck only. */
    val tiles: List<Tile> = emptyList(),
    /** Lines drawn as a list under the header, in the deck only. */
    val rows: List<Row> = emptyList(),
    /** A card that changes by itself (the battery, the phone's load, a focus session) asks to be redrawn this often while the deck is open. */
    val refreshMs: Long? = null,
)
