package dev.tushar.mycapsule

import android.content.Context
import android.content.res.Configuration

/**
 * The kinds of screen the capsule can sit on, each with a position of its own: the same phone held upright and on its
 * side, and a large screen (a tablet, or a foldable unfolded) the same two ways. The position stored for one is not used
 * on another, so a spot that suits portrait can differ from the one that suits landscape.
 */
enum class ScreenProfile(val label: String) {
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
    PORTRAIT_WIDE("Portrait, large screen"),
    LANDSCAPE_WIDE("Landscape, large screen"),
    ;

    companion object {
        /** A screen at least this wide (its shorter side, in dp) counts as a large screen, as Android's own size classes do. */
        private const val LARGE_SCREEN_DP = 600

        /** The profile of the screen [context] is on right now. */
        fun of(context: Context): ScreenProfile {
            val config = context.resources.configuration
            val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
            val wide = config.smallestScreenWidthDp >= LARGE_SCREEN_DP
            return when {
                landscape && wide -> LANDSCAPE_WIDE
                landscape -> LANDSCAPE
                wide -> PORTRAIT_WIDE
                else -> PORTRAIT
            }
        }
    }
}

/**
 * Where the capsule sits on one kind of screen.
 *
 * [offsetXDp] moves it from its [align] spot, to the right when positive. [topDp] is how far down its top edge is, or
 * null for just under the status bar. [pillPercent] sizes the idle pill, for phones whose camera cutout is small.
 */
data class Position(val align: Align, val offsetXDp: Int, val topDp: Int?, val pillPercent: Int) {
    companion object {
        const val PILL_MIN = 50
        const val PILL_MAX = 200
        const val PILL_STEP = 10
        const val PILL_DEFAULT = 100
    }
}
