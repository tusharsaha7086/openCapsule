package dev.tushar.mycapsule

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat

/**
 * Draws the logo through a rounded clip that grows from the capsule alone to the whole artwork, the same morph the
 * app uses when a capsule grows out of the idle pill: size and corner radius flow together on a spring, and the
 * colours pour out of the capsule's open end as the clip opens.
 *
 * Shape coordinates are in pixels of the source artwork (303 x 185), so they stay valid whatever size the bitmap
 * is exported at.
 */
private class LogoMorphView(context: Context, private val bitmap: Bitmap) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    private val matrix = Matrix()
    private val clip = RectF()

    /** 0 is the capsule alone, 1 is the whole logo. Spring values go a little past 1; the clip stops at 1. */
    var progress = 0f
        set(value) {
            field = value
            // The capsule starts in the middle of the screen and the logo settles around it.
            translationX = (1f - value) * travelPx()
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        translationX = (1f - progress) * travelPx()
    }

    private fun scale() = width / SRC_W

    private fun travelPx() = (SRC_W / 2f - (START_L + START_R) / 2f) * scale()

    override fun onDraw(canvas: Canvas) {
        val k = scale()
        val shape = progress.coerceIn(0f, 1f)
        clip.set(
            lerp(START_L, 0f, shape) * k,
            lerp(START_T, 0f, shape) * k,
            lerp(START_R, SRC_W, shape) * k,
            lerp(START_B, SRC_H, shape) * k,
        )
        val radius = lerp(START_RADIUS, 0f, shape) * k
        matrix.setScale(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        paint.shader.setLocalMatrix(matrix)

        // Past 1 the spring pops the whole logo slightly, like the capsule does.
        val pop = if (progress > 1f) 1f + (progress - 1f) * POP else 1f
        canvas.save()
        canvas.scale(pop, pop, width / 2f, height / 2f)
        canvas.drawRoundRect(clip, radius, radius, paint)
        canvas.restore()
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        const val SRC_W = 303f
        const val SRC_H = 185f

        // The first frame: just the black capsule, cut where its open end begins.
        private const val START_L = 6f
        private const val START_T = 30f
        private const val START_R = 132f
        private const val START_B = 160f
        private const val START_RADIUS = 44f
        private const val POP = 0.35f
    }
}

/**
 * The in-app splash. The system splash is only the brand background; this view fades the capsule in, opens it so the
 * rest of the logo pours out of its open end (the app's own morph, on the app's own spring), rises the wordmark in,
 * and eases away.
 */
class SplashOverlay(context: Context, private val onDone: () -> Unit) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val logo = LogoMorphView(context, decode(R.drawable.logo_full))
    private val wordmark = ImageView(context).apply {
        setImageResource(R.drawable.logo_wordmark)
        scaleType = ImageView.ScaleType.FIT_XY
    }

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private val running = mutableListOf<Animator>()
    private val leaveRunnable = Runnable { leave() }

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.ic_launcher_background))
        isClickable = true // swallow touches while it is up

        column.addView(logo, LinearLayout.LayoutParams(dp(LogoMorphView.SRC_W * DP_PER_SRC_PX), dp(LogoMorphView.SRC_H * DP_PER_SRC_PX)))
        column.addView(wordmark, LinearLayout.LayoutParams(dp(WORDMARK_SRC_W * DP_PER_SRC_PX), dp(WORDMARK_SRC_H * DP_PER_SRC_PX)).apply {
            topMargin = dp(GAP_DP)
        })
        addView(column, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // The whole logo, name included, is shown complete and grows from nothing at the centre.
        logo.progress = 1f
        column.scaleX = 0f
        column.scaleY = 0f
    }

    fun play() {
        spring(0L, SPRING_MS, SPRING_DAMPING, SPRING_OMEGA) { p ->
            val s = p.coerceAtLeast(0f)
            column.scaleX = s
            column.scaleY = s
        }
        postDelayed(leaveRunnable, HOLD_MS)
    }

    /** Ends it at once, for when the screen is being destroyed. */
    fun dismissNow() {
        running.forEach { it.cancel() }
        removeCallbacks(leaveRunnable)
        animate().cancel()
        (parent as? ViewGroup)?.removeView(this)
    }

    private fun leave() {
        animate().alpha(0f).scaleX(EXIT_SCALE).scaleY(EXIT_SCALE)
            .setDuration(EXIT_MS)
            .setInterpolator(PathInterpolator(EXIT_X1, 0f, EXIT_X2, 1f))
            .withEndAction {
                (parent as? ViewGroup)?.removeView(this)
                onDone()
            }
            .start()
    }

    /** Runs [update] with spring progress (it overshoots 1 a little, then settles). */
    private fun spring(delayMs: Long, durationMs: Long, damping: Float, omega: Float, update: (Float) -> Unit) {
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delayMs
            duration = durationMs
            interpolator = SpringInterpolator(damping, omega, durationMs)
            addUpdateListener { update(it.animatedValue as Float) }
        }
        running += animator
        animator.start()
    }

    private fun decode(res: Int): Bitmap =
        BitmapFactory.decodeResource(resources, res, BitmapFactory.Options().apply { inScaled = false })

    private fun dp(v: Float) = (v * density).toInt()

    private companion object {
        // One scale for both pieces of artwork, so they keep the proportions of the brand sheet.
        const val DP_PER_SRC_PX = 0.8f
        const val WORDMARK_SRC_W = 373f
        const val WORDMARK_SRC_H = 67f
        const val GAP_DP = 24f

        // Same spring the real capsule uses when it grows out of the idle pill.
        const val SPRING_MS = 700L
        const val SPRING_DAMPING = 0.72f
        const val SPRING_OMEGA = 12f


        const val HOLD_MS = 2100L
        const val EXIT_MS = 380L
        const val EXIT_SCALE = 1.05f
        const val EXIT_X1 = 0.4f
        const val EXIT_X2 = 0.2f
    }
}
