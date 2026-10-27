package dev.tushar.mycapsule

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The small always-on pill that sits over the front camera. At 0% opacity it is fully transparent, so it
 * blends into the cutout, but it still takes touches: a tap expands the capsule and the other
 * gestures run whatever action the user mapped to them.
 */
class IdlePill(
    private val context: Context,
    private val prefs: Prefs,
    private val onTap: () -> Unit,
    private val onGesture: (Gesture) -> Unit,
) {
    private val density = context.resources.displayMetrics.density

    private var view: View? = null
    private var target: OverlayHost.Target? = null

    /** The pill's view while it is showing, for haptic feedback. */
    val root: View? get() = view

    fun show() {
        if (view != null || !prefs.idleEnabled || !OverlayHost.canDraw(context)) return
        // On its side the pill is left out (a setting), so nothing sits over a camera that is no longer at the top.
        val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (landscape && prefs.hideIdleInLandscape) return
        val v = View(context).apply {
            background = background()
            setOnTouchListener(gestures())
        }
        val t = OverlayHost.target(context)
        runCatching { t.windowManager.addView(v, layoutParams(t)) }.onSuccess {
            view = v
            target = t
        }
    }

    /**
     * In the current app style the pill is made of the same material as the capsule, faded to the chosen opacity (so
     * at 0% it is still invisible). Material You keeps the plain flat pill.
     */
    private fun background(): Drawable {
        val opacity = prefs.idleOpacity
        // A border would give away an otherwise invisible pill, so it only shows with some opacity.
        val borderPx = if (opacity != IdleOpacity.CLEAR) dp(prefs.borderWidth.dp) else 0
        val look = CapsuleLooks.of(prefs.uiStyle, density)
        if (look != null) {
            return CapsuleLooks.body(look, prefs.capsuleColor(context), FULLY_ROUND, borderPx, prefs.borderColorValue())
                .apply { alpha = (opacity.alpha * MAX_ALPHA).toInt() }
        }
        return GradientDrawable().apply {
            setColor(ColorUtils.setAlphaComponent(prefs.capsuleColor(context), (opacity.alpha * MAX_ALPHA).toInt()))
            cornerRadius = FULLY_ROUND
            if (borderPx > 0) setStroke(borderPx, prefs.borderColorValue())
        }
    }

    fun hide() {
        val v = view
        if (v != null) runCatching { target?.windowManager?.removeView(v) }
        view = null
        target = null
    }

    fun refresh() {
        hide()
        show()
    }

    /** Where the pill is, or would be, in screen pixels. Lets the expanded capsule position itself below it. */
    private class Frame(val x: Int, val y: Int, val width: Int, val height: Int) {
        val bottom get() = y + height
    }

    /** Bottom edge of the pill in screen pixels, whether or not it is currently showing. */
    fun bottom() = frame(OverlayHost.target(context).windowManager).bottom

    /** The pill's rectangle in screen pixels, whether or not it is showing. Capsules morph out of and into it. */
    fun bounds(percent: Int = pillPercent()): Rect =
        frame(OverlayHost.target(context).windowManager, percent).let { Rect(it.x, it.y, it.x + it.width, it.bottom) }

    /** How big the pill is on this screen, as a percentage of its normal size. */
    private fun pillPercent() = prefs.position(ScreenProfile.of(context)).pillPercent

    private fun frame(windowManager: WindowManager, percent: Int = pillPercent()): Frame {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val cutout = cutoutRect(windowManager)
        val baseWidth = (cutout?.width() ?: 0).coerceAtLeast(dp(MIN_WIDTH_DP)) + dp(PAD_WIDTH_DP)
        val baseHeight = (cutout?.height() ?: 0).coerceAtLeast(dp(MIN_HEIGHT_DP)) + dp(PAD_HEIGHT_DP)
        // Sized about the same centre, so a bigger or smaller pill still sits over the camera.
        val width = baseWidth * percent / PERCENT
        val height = baseHeight * percent / PERCENT
        return Frame(
            x = centerX(cutout, screenWidth) - width / 2,
            y = (cutout?.top ?: 0) - dp(PAD_HEIGHT_DP) / 2 - (height - baseHeight) / 2,
            width = width,
            height = height,
        )
    }

    private fun layoutParams(t: OverlayHost.Target): WindowManager.LayoutParams {
        val frame = frame(t.windowManager)

        return WindowManager.LayoutParams(
            frame.width,
            frame.height,
            t.type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = frame.x
            y = frame.y
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    /**
     * Horizontal centre of the camera. Some phones report a centred punch-hole as a rectangle that only
     * covers one side of it, so a cutout that lies on the screen's centre line is centred on the screen.
     */
    private fun centerX(cutout: Rect?, screenWidth: Int): Int {
        val screenCenter = screenWidth / 2
        if (cutout == null) return screenCenter
        val touchesCenterLine = cutout.left <= screenCenter && cutout.right >= screenCenter - dp(CENTER_SLACK_DP)
        return if (touchesCenterLine) screenCenter else cutout.centerX()
    }

    /** The top camera cutout in screen pixels, or null when the phone has none (or is older than Android 11). */
    private fun cutoutRect(windowManager: WindowManager): Rect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return windowManager.currentWindowMetrics.windowInsets.displayCutout?.boundingRectTop?.takeIf { !it.isEmpty }
    }

    /**
     * Tap, double tap, long press and sideways swipes. A tap in the system's sense must stay within about 8 dp, which a
     * real finger often doesn't on a spot this small and this close to the edge. So a quick touch that slips a little
     * further (up to [TAP_FORGIVENESS_DP]) and did nothing else still counts as a tap; a longer pull is left alone,
     * and the system treats a long pull down from the top edge as opening the notification shade.
     */
    private fun gestures(): View.OnTouchListener {
        var handled = false
        var downX = 0f
        var downY = 0f
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val forgiveness = dp(TAP_FORGIVENESS_DP)

        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handled = true
                onTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                handled = true
                onGesture(Gesture.DOUBLE_TAP)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                handled = true
                onGesture(Gesture.LONG_PRESS)
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < FLING_VELOCITY || abs(vx) < abs(vy)) return false
                handled = true
                onGesture(if (vx < 0) Gesture.SWIPE_LEFT else Gesture.SWIPE_RIGHT)
                return true
            }
        })

        return View.OnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                handled = false
                downX = e.rawX
                downY = e.rawY
            }
            val result = detector.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP && !handled) {
                val moved = hypot(e.rawX - downX, e.rawY - downY)
                val quick = e.eventTime - e.downTime < ViewConfiguration.getLongPressTimeout()
                // Inside the touch slop the detector reports the tap itself (after its double-tap wait).
                if (moved > touchSlop && moved <= forgiveness && quick) onTap()
            }
            result
        }
    }

    private fun dp(v: Int) = (v * density).toInt()

    private companion object {
        const val MAX_ALPHA = 255
        const val PERCENT = 100
        const val FULLY_ROUND = 1000f
        const val MIN_WIDTH_DP = 32
        const val MIN_HEIGHT_DP = 20
        const val PAD_WIDTH_DP = 28
        const val PAD_HEIGHT_DP = 6
        const val CENTER_SLACK_DP = 8
        const val FLING_VELOCITY = 800f
        const val TAP_FORGIVENESS_DP = 28
    }
}

