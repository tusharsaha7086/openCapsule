package dev.tushar.mycapsule

import android.view.animation.Interpolator

/** The ways a capsule can open. The preset sets how it moves; duration, bounce and Reduce animations then adjust it. */
enum class ExpandStyle(val label: String, val hint: String) {
    FLUID("Fluid", "Springs out of the pill with a soft overshoot"),
    SMOOTH("Smooth", "Eases out of the pill and settles with no overshoot"),
    SNAPPY("Snappy", "Quick, with a small overshoot"),
    JELLY("Jelly", "Slow and wobbly"),
    FADE("Fade", "Fades in place, without growing out of the pill"),
}

/** How far a springy style overshoots its target. Smooth and Fade never do. */
enum class BounceLevel(val label: String) {
    NONE("None"),
    LOW("Low"),
    NORMAL("Normal"),
    HIGH("High"),
}

/** One spring: how long it runs and how it swings (see [SpringInterpolator]). */
class Spring(val ms: Long, val damping: Float, val omega: Float) {
    fun interpolator(): Interpolator = SpringInterpolator(damping, omega, ms)
}

/**
 * Everything the capsule's movement needs, worked out from the settings: the spring for growing out of the pill, the one
 * for changing size, how long the exit and the card slide take, how much it pops past its size, and whether it grows out
 * of the pill ([fade] false) or fades in place from [startScale] ([fade] true).
 */
class Motion(
    val enter: Spring,
    val morph: Spring,
    val exitMs: Long,
    val slideOutMs: Long,
    val pop: Float,
    val fade: Boolean,
    val startScale: Float,
)

/** Turns the animation settings into a [Motion]. */
object Motions {
    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 200
    const val STEP_PERCENT = 10
    const val DEFAULT_PERCENT = 100

    private const val SLIDE_OUT_MS = 260L

    private class Base(
        val enter: Spring,
        val morph: Spring,
        val exitMs: Long,
        val pop: Float,
        val springy: Boolean,
        val fade: Boolean = false,
        val startScale: Float = 1f,
    )

    // Every spring runs long enough to have settled (its decay is under about 1%) before it is cut off at the end.
    private val bases = mapOf(
        ExpandStyle.FLUID to Base(Spring(700, 0.72f, 12f), Spring(560, 0.80f, 14f), exitMs = 380, pop = 0.35f, springy = true),
        ExpandStyle.SMOOTH to Base(Spring(600, 1f, 12f), Spring(480, 1f, 14f), exitMs = 340, pop = 0f, springy = false),
        ExpandStyle.SNAPPY to Base(Spring(420, 0.82f, 22f), Spring(360, 0.85f, 24f), exitMs = 240, pop = 0.15f, springy = true),
        ExpandStyle.JELLY to Base(Spring(950, 0.45f, 11f), Spring(760, 0.50f, 13f), exitMs = 420, pop = 0.70f, springy = true),
        ExpandStyle.FADE to Base(Spring(300, 1f, 22f), Spring(260, 1f, 25f), exitMs = 240, pop = 0f, springy = false, fade = true, startScale = 0.92f),
    )

    /** What Reduce animations does: a quick fade in place, no travel, no growth, no bounce. */
    private val reduced = Motion(Spring(140, 1f, 50f), Spring(120, 1f, 55f), exitMs = 140, slideOutMs = 60, pop = 0f, fade = true, startScale = 1f)

    fun of(prefs: Prefs): Motion =
        if (prefs.reduceAnimations) reduced else build(prefs.expandStyle, prefs.animationDurationPercent, prefs.bounce)

    /** How open the capsule is over time in [style], for the picker to draw. */
    fun curve(style: ExpandStyle): Interpolator = build(style, DEFAULT_PERCENT, BounceLevel.NORMAL).enter.interpolator()

    private fun build(style: ExpandStyle, percent: Int, bounce: BounceLevel): Motion {
        val base = bases.getValue(style)
        // Slower means longer and a slower swing, so the curve keeps its shape and only stretches.
        val scale = percent / 100f

        fun damping(d: Float) = if (!base.springy) d else when (bounce) {
            BounceLevel.NONE -> 1f
            BounceLevel.LOW -> minOf(0.98f, d + 0.14f)
            BounceLevel.NORMAL -> d
            BounceLevel.HIGH -> maxOf(0.35f, d - 0.18f)
        }

        fun spring(s: Spring) = Spring((s.ms * scale).toLong(), damping(s.damping), s.omega / scale)

        val popFactor = if (!base.springy) 1f else when (bounce) {
            BounceLevel.NONE -> 0f
            BounceLevel.LOW -> 0.5f
            BounceLevel.NORMAL -> 1f
            BounceLevel.HIGH -> 1.5f
        }
        return Motion(
            enter = spring(base.enter),
            morph = spring(base.morph),
            exitMs = (base.exitMs * scale).toLong(),
            slideOutMs = (SLIDE_OUT_MS * scale).toLong(),
            pop = base.pop * popFactor,
            fade = base.fade,
            startScale = base.startScale,
        )
    }
}
