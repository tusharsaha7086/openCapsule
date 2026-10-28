package dev.tushar.mycapsule

/** Which cards share one page of the deck, and whether the page shows them as tabs ([tabbed]) or one above the other. */
enum class DeckPage(val cards: List<DeckCard>, val tabbed: Boolean = true) {
    PLAYER(listOf(DeckCard.MEDIA, DeckCard.VOLUME), tabbed = false),
    CONTROL(listOf(DeckCard.CONTROLS, DeckCard.LAUNCHER)),
    FOCUS(listOf(DeckCard.POMODORO, DeckCard.CLOCK)),
    NOTES(listOf(DeckCard.CLIPBOARD, DeckCard.NOTES, DeckCard.CALENDAR)),
    SYSTEM(listOf(DeckCard.BATTERY, DeckCard.STATS)),
    BARCODE(listOf(DeckCard.BARCODE)),
    ;

    companion object {
        fun of(card: DeckCard) = entries.first { card in it.cards }
    }
}

/**
 * The cards in the expanded view (the deck) that can be switched on or off. A card is shown only while it has something
 * to show: the player when there is a song, the volume while music plays, the clock while a timer or stopwatch runs, the
 * barcode once one is saved. Quick settings is always there. Switching one off keeps it out of the deck; it does not
 * touch the brief capsules, which have their own switches under Capsules.
 *
 * Some cards gather several kinds: [CLOCK] holds the timer and the stopwatch, [CONTROLS] the flashlight, Wi-Fi,
 * Bluetooth, hotspot and the other phone settings. [kind] is the one that names the card in the deck.
 */
enum class DeckCard(val kind: Kind, val label: String, val hint: String, val icon: Int) {
    MEDIA(Kind.MEDIA, "Media player", "The song that is playing, with its controls", R.drawable.ic_music_note),
    VOLUME(Kind.VOLUME, "Volume", "The media volume while music is playing", R.drawable.ic_volume_up),
    CLOCK(Kind.TIMER, "Timer and stopwatch", "A running countdown and stopwatch, one above the other", R.drawable.ic_timer),
    CONTROLS(Kind.SYSTEM, "Quick settings", "Flashlight, Wi-Fi, Bluetooth, hotspot and other phone switches", R.drawable.ic_tune),
    BARCODE(Kind.BARCODE, "Barcode", "Your saved pass or code", R.drawable.ic_qr_code_2),
    CLIPBOARD(Kind.CLIPBOARD, "Clipboard", "What you copied lately; tap one to paste it into the open field", R.drawable.ic_content_paste),
    NOTES(Kind.NOTES, "Notes", "Quick notes, written and opened from the deck", R.drawable.ic_edit_note),
    CALENDAR(Kind.CALENDAR, "Calendar", "What is on now or next, with a Join button for video calls", R.drawable.ic_event),
    BATTERY(Kind.BATTERY, "Battery", "Level, charging, temperature and health", R.drawable.ic_battery_full),
    LAUNCHER(Kind.LAUNCHER, "Quick launcher", "Your chosen apps, one tap away", R.drawable.ic_apps),
    POMODORO(Kind.POMODORO, "Pomodoro", "Focus sessions with breaks", R.drawable.ic_timer),
    STATS(Kind.STATS, "Phone stats", "Memory, storage, network, processor and heat", R.drawable.ic_memory),
    ;

    companion object {
        /** The card a kind of event belongs to, or null for a kind that is not a card. */
        fun of(kind: Kind) = when (kind) {
            Kind.TIMER, Kind.STOPWATCH -> CLOCK
            Kind.FLASHLIGHT, Kind.SYSTEM -> CONTROLS
            else -> entries.firstOrNull { it.kind == kind }
        }

        /** The deck page a kind of event is on: related cards share one page, so the deck has fewer pages. */
        fun pageOf(kind: Kind): Any = of(kind)?.let { DeckPage.of(it) } ?: kind

        /** The short name of a card on its page's tab bar. */
        private fun tabName(kind: Kind) = when (kind) {
            Kind.TIMER, Kind.STOPWATCH -> "Timers"
            Kind.FLASHLIGHT, Kind.SYSTEM -> "Settings"
            Kind.LAUNCHER -> "Apps"
            Kind.POMODORO -> "Focus"
            Kind.STATS -> "Phone"
            else -> kind.label
        }

        /**
         * Gathers the cards that belong together onto one page, as the reference decks do: quick settings with the
         * launcher, focus with the timers, clipboard with notes and the calendar, battery with phone stats, and a song
         * with its volume. Pages with tabs show one card at a time; the song and its volume sit one above the other.
         * A card whose page-mates are not showing stays as it is. Pages come in the order of their first card.
         */
        fun group(cards: List<CapsuleEvent>, order: List<DeckCard>): List<CapsuleEvent> {
            fun rank(card: CapsuleEvent) = of(card.kind)?.let(order::indexOf) ?: order.size
            val pages = mergeClock(cards).groupBy { pageOf(it.kind) }.values
            return pages.map { members ->
                val page = of(members.first().kind)?.let { DeckPage.of(it) }
                // Within a page the cards keep the page's own order (settings before apps), whatever the saved order is.
                val sorted = members.sortedBy { card -> page?.cards?.indexOf(of(card.kind)) ?: 0 }
                if (sorted.size == 1) return@map sorted.first()
                val tabbed = page?.tabbed == true
                sorted.first().copy(
                    actions = emptyList(),
                    expandedActions = emptyList(),
                    progress = null,
                    image = null,
                    tiles = emptyList(),
                    rows = emptyList(),
                    stats = emptyList(),
                    live = sorted.any { it.live },
                    playback = sorted.firstNotNullOfOrNull { it.playback },
                    refreshMs = sorted.mapNotNull { it.refreshMs }.minOrNull(),
                    sections = sorted,
                    tabs = if (tabbed) sorted.map { tabName(it.kind) } else emptyList(),
                )
            }.sortedBy { page -> (page.sections.ifEmpty { listOf(page) }).minOf(::rank) }
        }

        /**
         * Puts the timer and the stopwatch into one card, one above the other. Each shows its running time, or a button
         * to start it while it is not running, so the card is always there.
         */
        fun mergeClock(cards: List<CapsuleEvent>): List<CapsuleEvent> {
            val clocks = cards.filter { it.kind == Kind.TIMER || it.kind == Kind.STOPWATCH }
            if (clocks.size < 2) return cards
            val merged = clocks.first().copy(
                actions = emptyList(),
                progress = null,
                live = clocks.any { it.live },
                sections = clocks,
            )
            return cards.mapNotNull { card ->
                when {
                    card === clocks.first() -> merged
                    card in clocks -> null
                    else -> card
                }
            }
        }
    }
}
