package dev.tushar.mycapsule

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.Interpolator
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** A size and corner radius, in pixels, that a capsule morphs from or into. */
class MorphShape(val width: Float, val height: Float, val radius: Float)

/**
 * A damped spring as an [Interpolator]: it overshoots its target a little and settles, which is what gives
 * the motion its fluid, living feel. [damping] below 1 bounces; [omega] is how fast it swings (rad/s).
 */
class SpringInterpolator(
    private val damping: Float,
    private val omega: Float,
    durationMs: Long,
) : Interpolator {
    private val seconds = durationMs / MS_PER_SECOND
    private val dampedOmega = if (damping < 1f) omega * sqrt(1 - damping * damping) else 0f

    override fun getInterpolation(t: Float): Float {
        if (t >= 1f) return 1f
        val time = t * seconds
        val decay = exp(-damping * omega * time)
        // Critically damped (damping 1): it arrives as fast as it can without ever passing its target.
        if (damping >= 1f) return 1f - decay * (1f + omega * time)
        return 1f - decay * (cos(dampedOmega * time) + damping * omega / dampedOmega * sin(dampedOmega * time))
    }

    private companion object {
        const val MS_PER_SECOND = 1000f
    }
}

/**
 * Morphs a capsule from one shape into another, so it flows between the idle pill and its full size instead of
 * fading or popping.
 *
 * The [body] is always laid out at its full size. Morphing animates a rounded clip over it (size and corner
 * radius), moves the whole [container] from where the source shape sits to where the capsule rests, and fades the
 * content in once the shape has opened enough. Progress 0 is the source shape, 1 is the resting capsule; the spring
 * pushes past 1 for a small pop.
 */
class Morph(
    private val container: View,
    private val body: ViewGroup,
    private val restRadius: Float,
    /** How far it grows past its size when the spring overshoots (0 for none). */
    private val pop: Float = DEFAULT_POP,
) {
    private var from = MorphShape(0f, 0f, 0f)
    private var travelX = 0f
    private var travelY = 0f
    private var slideX = 0f
    private var fadeContent = true
    private var fadeBackground = false
    private var startScale = 1f
    private var animator: ValueAnimator? = null

    private var clipWidth = 0f
    private var clipHeight = 0f
    private var clipRadius = 0f

    var progress = 1f
        private set

    init {
        body.clipToOutline = true
        body.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val left = ((view.width - clipWidth) / 2f).toInt()
                outline.setRoundRect(left, 0, (left + clipWidth).toInt(), clipHeight.toInt(), clipRadius)
            }
        }
    }

    /** Sets the source shape and puts the capsule in that state. Call once the body has been laid out. */
    fun start(
        from: MorphShape,
        travelX: Float,
        travelY: Float,
        slideX: Float = 0f,
        fadeContent: Boolean = true,
        fadeBackground: Boolean = false,
        startScale: Float = 1f,
    ) {
        this.from = from
        this.travelX = travelX
        this.travelY = travelY
        this.slideX = slideX
        this.fadeContent = fadeContent
        this.fadeBackground = fadeBackground
        this.startScale = startScale
        container.pivotX = container.width / 2f
        container.pivotY = body.top.toFloat()
        progress = 0f
        apply()
    }

    /** Points the way back at a new shape (the idle pill) without changing the current progress. */
    fun retarget(to: MorphShape, travelX: Float, travelY: Float, fadeBackground: Boolean = false, startScale: Float = 1f) {
        from = to
        this.travelX = travelX
        this.travelY = travelY
        slideX = 0f
        fadeContent = true
        this.fadeBackground = fadeBackground
        this.startScale = startScale
        container.pivotX = container.width / 2f
        container.pivotY = body.top.toFloat()
    }

    /** Animates progress to [target]; [onEnd] runs when it arrives, but not if it is cancelled or replaced. */
    fun animateTo(target: Float, durationMs: Long, interpolator: Interpolator, onEnd: () -> Unit = {}) {
        animator?.cancel()
        val start = progress
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            this.interpolator = interpolator
            addUpdateListener {
                progress = start + (target - start) * (it.animatedValue as Float)
                apply()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    progress = target
                    apply()
                    onEnd()
                }
            })
            start()
        }
    }

    /** Shows the capsule at rest, with no clip and no transforms. */
    fun settle() {
        animator?.cancel()
        progress = 1f
        apply()
    }

    fun cancel() {
        animator?.cancel()
    }

    private fun apply() {
        val p = progress
        val shape = p.coerceIn(0f, 1f)
        val restWidth = body.width.toFloat()
        val restHeight = body.height.toFloat()

        clipWidth = lerp(from.width, restWidth, shape)
        clipHeight = lerp(from.height, restHeight, shape)
        clipRadius = lerp(from.radius, restRadius, shape)
        body.invalidateOutline()

        // Travel and slide follow the spring all the way, so the overshoot shows up as a small bounce.
        container.translationX = (1f - p) * (travelX + slideX)
        container.translationY = (1f - p) * travelY
        // Past 1 the spring pops it slightly bigger; before that it grows from its start scale (1 unless it fades in place).
        val scale = if (p > 1f) 1f + (p - 1f) * pop else lerp(startScale, 1f, shape)
        container.scaleX = scale
        container.scaleY = scale

        val contentAlpha = if (fadeContent) ((shape - FADE_START) / FADE_SPAN).coerceIn(0f, 1f) else 1f
        for (i in 0 until body.childCount) body.getChildAt(i).alpha = contentAlpha

        // When this capsule replaces another of the same size (switching cards), its colour fades in too, so the two
        // blend into each other instead of the new colour appearing all at once.
        body.background?.alpha = if (fadeBackground) (shape * BACKGROUND_FADE_RATE).coerceAtMost(1f).times(MAX_ALPHA).toInt() else MAX_ALPHA
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        /** Content stays hidden until the shape is this far open, then fades in over [FADE_SPAN]. */
        const val FADE_START = 0.4f
        const val FADE_SPAN = 0.5f
        const val DEFAULT_POP = 0.35f
        const val BACKGROUND_FADE_RATE = 1.6f
        const val MAX_ALPHA = 255
    }
}
