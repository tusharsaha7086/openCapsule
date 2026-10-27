package dev.tushar.mycapsule

/**
 * The cards in the expanded view (the deck) that can be switched on or off. A card is shown only while it has something
 * to show: the player when there is a song, the volume while music plays, the timer and stopwatch while they run, the
 * torch, and the saved barcode. Switching one off keeps it out of the deck; it does not touch the brief capsules,
 * which have their own switches under Capsules.
 */
enum class DeckCard(val kind: Kind, val label: String, val hint: String, val icon: Int) {
    MEDIA(Kind.MEDIA, "Media player", "The song that is playing, with its controls", R.drawable.ic_music_note),
    VOLUME(Kind.VOLUME, "Volume", "The media volume while music is playing", R.drawable.ic_volume_up),
    TIMER(Kind.TIMER, "Timer", "A running countdown", R.drawable.ic_timer),
    STOPWATCH(Kind.STOPWATCH, "Stopwatch", "A running stopwatch", R.drawable.ic_schedule),
    FLASHLIGHT(Kind.FLASHLIGHT, "Flashlight", "Turn the torch on or off", R.drawable.ic_flashlight_on),
    BARCODE(Kind.BARCODE, "Barcode", "Your saved pass or code", R.drawable.ic_qr_code_2),
    ;

    companion object {
        fun of(kind: Kind) = entries.firstOrNull { it.kind == kind }
    }
}
