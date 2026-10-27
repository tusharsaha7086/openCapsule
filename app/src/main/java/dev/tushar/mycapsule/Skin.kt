package dev.tushar.mycapsule

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import kotlin.math.max

/** The visual styles the settings screen can wear. [dark] says whether its screen is dark (for the status bar icons). */
enum class UiStyle(val label: String, val dark: Boolean) {
    MATERIAL("Material You", true),
    GLASS("Glass", true),
    LIQUID_GLASS("Liquid Glass", true),
    SKEUOMORPHIC("Skeuomorphic", true),
    NEUMORPHIC("Neumorphic", false),
    CLAY("Clay", false),
    BRUTAL("Brutalist", false),
}

/** Where a piece of text sits, so a skin can treat titles and captions differently. */
enum class TextRole { HEADLINE, TITLE, LABEL, BODY, CAPTION }

/**
 * Everything the settings screen needs to look like one visual style: colours, the drawable behind each kind of
 * surface, and a few text and control details. The screen is built from the same helpers whatever the skin; only the
 * skin changes.
 *
 * Surfaces are drawn by [FnDrawable]s. A drawable reserves room for its own shadow as padding, so shadows are never
 * clipped by the view and the content always sits inside the surface.
 */
abstract class Skin(val style: UiStyle, protected val density: Float) {
    /** Whether the screen is dark. Most styles are fixed; Material You follows the system setting. */
    open val dark: Boolean get() = style.dark

    abstract val bg: Int
    abstract val text: Int
    abstract val textDim: Int
    abstract val accent: Int
    abstract val onAccent: Int
    abstract val good: Int
    abstract val warn: Int

    /** Fade at the top of the screen, so the clock and icons stay readable over scrolling content. */
    open val scrim: Int get() = bg

    /** Text that sits straight on the screen background, not on a card. */
    open val screenText: Int get() = text
    open val screenTextDim: Int get() = textDim

    /** Outline of an unselected swatch. */
    open val hairline: Int = 0x33808080

    abstract fun screen(): Drawable
    abstract fun card(index: Int): Drawable
    abstract fun field(): Drawable
    abstract fun button(filled: Boolean, pressed: Boolean): Drawable
    abstract fun chip(selected: Boolean): Drawable
    abstract fun chipText(selected: Boolean): Int
    abstract fun buttonText(filled: Boolean): Int
    abstract fun track(on: Boolean): Drawable
    abstract fun thumb(on: Boolean): Drawable

    /** The small tile behind the icon at the start of a settings row. Styles without a special one use a round chip. */
    open fun tile(): Drawable = chip(false)
    open val tileIcon: Int get() = text

    open val trackWidthDp = 52f
    open val trackHeightDp = 32f
    open fun thumbDp(on: Boolean) = if (on) 24f else 16f

    abstract val sliderActive: Int
    abstract val sliderInactive: Int
    abstract val sliderThumb: Int
    open val sliderThumbStroke: Int = 0
    open val sliderThumbStrokeDp = 0f
    open val sliderThumbElevationDp = 1f
    open val sliderTrackDp = 6f

    open fun swatchStrokeDp(on: Boolean) = if (on) 3f else 1f
    open fun swatchStroke(on: Boolean) = if (on) text else hairline

    open fun typeface(bold: Boolean): Typeface = AppFonts.current.typeface(bold)

    /** Final touches on a piece of text: letter-press shadows, capitals and so on. */
    open fun styleText(view: TextView, role: TextRole, onScreen: Boolean) {}

    protected fun dp(v: Float) = v * density
    protected fun pad(l: Float, t: Float, r: Float, b: Float) = Rect(dp(l).toInt(), dp(t).toInt(), dp(r).toInt(), dp(b).toInt())
    protected fun pad(all: Float) = pad(all, all, all, all)
    protected fun fn(pad: Rect = Rect(), draw: (Canvas, RectF) -> Unit) = FnDrawable(pad, draw)
}

/** A drawable defined by a function. It is handed the body rectangle: its bounds minus the padding reserved for shadows. */
class FnDrawable(private val pad: Rect = Rect(), private val draw: (Canvas, RectF) -> Unit) : Drawable() {
    private val body = RectF()
    private var alphaValue = 255

    override fun draw(canvas: Canvas) {
        val b = bounds
        body.set(b.left + pad.left.toFloat(), b.top + pad.top.toFloat(), b.right - pad.right.toFloat(), b.bottom - pad.bottom.toFloat())
        if (body.width() <= 0f || body.height() <= 0f) return
        if (alphaValue == 255) {
            draw(canvas, body)
        } else {
            val saved = canvas.saveLayerAlpha(RectF(b), alphaValue)
            draw(canvas, body)
            canvas.restoreToCount(saved)
        }
    }

    /** Only claims padding when it reserves some, so a drawable with none leaves the view's own padding alone. */
    override fun getPadding(padding: Rect): Boolean {
        padding.set(pad)
        return pad.left != 0 || pad.top != 0 || pad.right != 0 || pad.bottom != 0
    }

    override fun getAlpha() = alphaValue
    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Required by Drawable")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Painting blocks the skins share: fills, strokes, soft and hard shadows, inner shadows and glows. */
object Fx {
    private val path = Path()

    fun paint(color: Int, style: Paint.Style = Paint.Style.FILL) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.style = style }

    fun fill(c: Canvas, b: RectF, r: Float, color: Int) = c.drawRoundRect(b, r, r, paint(color))

    fun fill(c: Canvas, b: RectF, r: Float, shader: Shader) =
        c.drawRoundRect(b, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })

    /** A stroke drawn inside [b]. */
    fun stroke(c: Canvas, b: RectF, r: Float, width: Float, color: Int) {
        val inset = RectF(b).apply { inset(width / 2f, width / 2f) }
        c.drawRoundRect(inset, max(r - width / 2f, 0f), max(r - width / 2f, 0f), paint(color, Paint.Style.STROKE).apply { strokeWidth = width })
    }

    fun stroke(c: Canvas, b: RectF, r: Float, width: Float, shader: Shader) {
        val inset = RectF(b).apply { inset(width / 2f, width / 2f) }
        c.drawRoundRect(inset, max(r - width / 2f, 0f), max(r - width / 2f, 0f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = width; this.shader = shader })
    }

    /**
     * A shadow of the shape, offset by ([dx], [dy]) and blurred by [blur]; a [blur] of 0 is a hard shadow. With
     * [outsideOnly] it is clipped away under the shape, so a see-through surface does not look darker.
     */
    fun shadow(c: Canvas, b: RectF, r: Float, dx: Float, dy: Float, blur: Float, color: Int, outsideOnly: Boolean = false) {
        c.save()
        if (outsideOnly) {
            path.reset()
            path.addRoundRect(b, r, r, Path.Direction.CW)
            c.clipOutPath(path)
        }
        val p = paint(color)
        if (blur > 0f) p.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        val moved = RectF(b).apply { offset(dx, dy) }
        c.drawRoundRect(moved, r, r, p)
        c.restore()
    }

    /** A shadow cast inside the shape from outside it: it darkens (or lights) the edges that face ([dx], [dy]). */
    fun inner(c: Canvas, b: RectF, r: Float, dx: Float, dy: Float, blur: Float, color: Int) {
        c.save()
        path.reset()
        path.addRoundRect(b, r, r, Path.Direction.CW)
        c.clipPath(path)
        val ring = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(RectF(b).apply { inset(-(blur * 3f + 8f), -(blur * 3f + 8f)) }, Path.Direction.CW)
            addRoundRect(b, r, r, Path.Direction.CCW)
        }
        val p = paint(color)
        if (blur > 0f) p.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        c.translate(dx, dy)
        c.drawPath(ring, p)
        c.restore()
    }

    fun vertical(b: RectF, vararg colors: Int, positions: FloatArray? = null) =
        android.graphics.LinearGradient(0f, b.top, 0f, b.bottom, colors, positions, Shader.TileMode.CLAMP)

    fun diagonal(b: RectF, vararg colors: Int, positions: FloatArray? = null) =
        android.graphics.LinearGradient(b.left, b.top, b.right, b.bottom, colors, positions, Shader.TileMode.CLAMP)

    fun withAlpha(color: Int, alpha: Float) = (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

    /** [color] mixed towards black ([amount] below 0) or white ([amount] above 0). */
    fun shade(color: Int, amount: Float): Int {
        val target = if (amount < 0) 0 else 255
        val t = kotlin.math.abs(amount).coerceIn(0f, 1f)
        fun mix(shift: Int): Int {
            val v = (color shr shift) and 0xFF
            return (v + (target - v) * t).toInt().coerceIn(0, 255)
        }
        return (color and 0xFF000000.toInt()) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}

/**
 * A background of soft glowing colour blobs on a dark base: what glass sits on. With [drift] the blobs wander slowly,
 * which is what makes liquid glass feel alive.
 */
class AuroraDrawable(private val base: Int, private val blobs: List<Blob>, private val drift: Boolean) : Drawable(), Animatable {

    class Blob(val x: Float, val y: Float, val radius: Float, val color: Int, val phase: Float)

    private val paints = blobs.map { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val basePaint = Paint().apply { color = base }
    private var time = 0f
    private var animator: ValueAnimator? = null

    override fun onBoundsChange(bounds: Rect) {
        val size = max(bounds.width(), bounds.height()).toFloat()
        blobs.forEachIndexed { i, blob ->
            val r = blob.radius * size
            paints[i].shader = android.graphics.RadialGradient(
                0f, 0f, r, intArrayOf(blob.color, Fx.withAlpha(blob.color, 0f)), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
            )
        }
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        canvas.drawRect(bounds, basePaint)
        val size = max(w, h)
        blobs.forEachIndexed { i, blob ->
            val wobble = if (drift) DRIFT else 0f
            val cx = w * blob.x + kotlin.math.sin(time + blob.phase) * wobble * w
            val cy = h * blob.y + kotlin.math.cos(time * 0.8f + blob.phase * 1.3f) * wobble * h
            canvas.save()
            canvas.translate(cx, cy)
            canvas.drawCircle(0f, 0f, blob.radius * size, paints[i])
            canvas.restore()
        }
    }

    override fun start() {
        if (!drift || animator != null) return
        animator = ValueAnimator.ofFloat(0f, TAU).apply {
            duration = LOOP_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { time = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    override fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun isRunning() = animator != null
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Required by Drawable")
    override fun getOpacity() = PixelFormat.OPAQUE

    private companion object {
        const val TAU = (Math.PI * 2).toFloat()
        const val LOOP_MS = 45_000L
        const val DRIFT = 0.07f
    }
}

/**
 * A switch drawn entirely by the skin. The thumb slides and grows between its off and on sizes, and the on and off
 * tracks and thumbs cross-fade, so every style gets its own kind of switch (a puffy one, an inset one, a square one).
 */
class SkinSwitch(context: Context, private val skin: Skin, checked: Boolean, private val onChange: (Boolean) -> Unit) : View(context) {

    private val d = resources.displayMetrics.density
    private val trackOff = skin.track(false)
    private val trackOn = skin.track(true)
    private val thumbOff = skin.thumb(false)
    private val thumbOn = skin.thumb(true)
    private val trackW = skin.trackWidthDp * d
    private val trackH = skin.trackHeightDp * d
    private val margin = MARGIN_DP * d
    private val thumbPad = Rect().also { thumbOff.getPadding(it) }
    private val trackPad = Rect().also { trackOff.getPadding(it) }

    private var position = if (checked) 1f else 0f
    var isOn = checked
        private set
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Switch"
                info.isCheckable = true
                info.isChecked = isOn
            }
        })
        setOnClickListener { toggle() }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val thumbMax = max(skin.thumbDp(false), skin.thumbDp(true)) * d + thumbPad.top + thumbPad.bottom
        val h = max(trackH + trackPad.top + trackPad.bottom, thumbMax) + margin * 2
        setMeasuredDimension((trackW + trackPad.left + trackPad.right + margin * 2).toInt(), h.toInt())
    }

    /** Moves the switch to [on] without telling anyone, for when something else has changed the setting it shows. */
    fun setChecked(on: Boolean) {
        if (isOn == on) return
        isOn = on
        animator?.cancel()
        position = if (on) 1f else 0f
        invalidate()
    }

    private fun toggle() {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        isOn = !isOn
        animator?.cancel()
        animator = ValueAnimator.ofFloat(position, if (isOn) 1f else 0f).apply {
            duration = SLIDE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { position = it.animatedValue as Float; invalidate() }
            start()
        }
        onChange(isOn)
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val left = margin
        // Track: its bounds include the padding it reserves for shadow.
        val tl = left.toInt()
        val tt = (cy - trackH / 2f - trackPad.top).toInt()
        val tb = (cy + trackH / 2f + trackPad.bottom).toInt()
        val tr = (left + trackW + trackPad.left + trackPad.right).toInt()
        drawFaded(canvas, trackOff, trackOn, Rect(tl, tt, tr, tb))

        val size = (skin.thumbDp(false) + (skin.thumbDp(true) - skin.thumbDp(false)) * position) * d
        val inset = trackH / 2f
        val minX = left + trackPad.left + inset
        val maxX = left + trackPad.left + trackW - inset
        val cx = minX + (maxX - minX) * position
        val half = size / 2f
        drawFaded(canvas, thumbOff, thumbOn, Rect(
            (cx - half - thumbPad.left).toInt(), (cy - half - thumbPad.top).toInt(),
            (cx + half + thumbPad.right).toInt(), (cy + half + thumbPad.bottom).toInt(),
        ))
    }

    private fun drawFaded(canvas: Canvas, off: Drawable, on: Drawable, bounds: Rect) {
        off.bounds = bounds
        on.bounds = bounds
        off.alpha = ((1f - position) * 255).toInt()
        on.alpha = (position * 255).toInt()
        if (off.alpha > 0) off.draw(canvas)
        if (on.alpha > 0) on.draw(canvas)
    }

    private companion object {
        const val SLIDE_MS = 180L
        const val MARGIN_DP = 2f
    }
}
