package dev.tushar.mycapsule

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.tushar.mycapsule.Fx.diagonal
import dev.tushar.mycapsule.Fx.fill
import dev.tushar.mycapsule.Fx.inner
import dev.tushar.mycapsule.Fx.shade
import dev.tushar.mycapsule.Fx.shadow
import dev.tushar.mycapsule.Fx.stroke
import dev.tushar.mycapsule.Fx.vertical
import dev.tushar.mycapsule.Fx.withAlpha

private fun argb(v: Long) = v.toInt()

private const val WHITE = -1
private const val BLACK = 0xFF000000.toInt()

object Skins {
    fun of(style: UiStyle, context: Context, density: Float): Skin = when (style) {
        UiStyle.MATERIAL -> MaterialSkin(context, density)
        UiStyle.GLASS -> GlassSkin(density, liquid = false, animate = !Prefs(context).reduceAnimations)
        UiStyle.LIQUID_GLASS -> GlassSkin(density, liquid = true, animate = !Prefs(context).reduceAnimations)
        UiStyle.SKEUOMORPHIC -> SkeuoSkin(density)
        UiStyle.NEUMORPHIC -> NeumorphicSkin(density)
        UiStyle.CLAY -> ClaySkin(density)
        UiStyle.BRUTAL -> BrutalSkin(density)
    }
}

/**
 * Material You (Material 3). Colours come from the system's dynamic palette on Android 12 and newer, so the screen
 * picks up the wallpaper's colours; older phones get the baseline Material 3 purple. It follows the system's light
 * and dark setting. Surfaces are tonal (no shadows), buttons are pills, filter chips are softly rounded, and the
 * switch is the Material 3 one: a small grey thumb that grows and takes the primary colour when on.
 */
class MaterialSkin(private val context: Context, density: Float) : Skin(UiStyle.MATERIAL, density) {
    private val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun system(id: Int, fallback: Int) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ContextCompat.getColor(context, id) else fallback

    private val primary = if (night) system(android.R.color.system_accent1_200, argb(0xFFD0BCFF)) else system(android.R.color.system_accent1_600, argb(0xFF6750A4))
    private val onPrimary = if (night) system(android.R.color.system_accent1_800, argb(0xFF381E72)) else system(android.R.color.system_accent1_0, WHITE)
    private val secondaryContainer = if (night) system(android.R.color.system_accent2_700, argb(0xFF4A4458)) else system(android.R.color.system_accent2_100, argb(0xFFE8DEF8))
    private val onSecondaryContainer = if (night) system(android.R.color.system_accent2_100, argb(0xFFE8DEF8)) else system(android.R.color.system_accent2_900, argb(0xFF1D192B))
    private val onSurface = if (night) system(android.R.color.system_neutral1_100, argb(0xFFE6E0E9)) else system(android.R.color.system_neutral1_900, argb(0xFF1D1B20))
    private val onSurfaceVariant = if (night) system(android.R.color.system_neutral2_200, argb(0xFFCAC4D0)) else system(android.R.color.system_neutral2_700, argb(0xFF49454F))
    private val outline = system(android.R.color.system_neutral2_500, if (night) argb(0xFF938F99) else argb(0xFF79747E))

    // Surfaces, from the screen up to a raised container.
    private val screenColor = if (night) shade(system(android.R.color.system_neutral1_900, argb(0xFF1C1B1F)), -0.35f) else system(android.R.color.system_neutral1_10, argb(0xFFFEF7FF))
    private val container = if (night) shade(system(android.R.color.system_neutral1_900, argb(0xFF1C1B1F)), 0.03f) else system(android.R.color.system_neutral1_50, argb(0xFFF3EDF7))
    private val containerHigh = shade(container, if (night) 0.06f else -0.04f)

    override val dark = night
    override val bg = screenColor
    override val text = onSurface
    override val textDim = onSurfaceVariant
    override val accent = primary
    override val onAccent = onPrimary
    override val good = if (night) argb(0xFF7FD99A) else argb(0xFF1B7A3D)
    override val warn = if (night) argb(0xFFFFB74D) else argb(0xFF9A5B00)
    override val hairline = withAlpha(outline, 0.6f)

    override fun screen(): Drawable = ColorDrawable(bg)

    override fun card(index: Int) = fn { c, b -> fill(c, b, dp(24f), container) }

    override fun field() = fn { c, b -> stroke(c, b, dp(14f), dp(1f), outline) }

    override fun button(filled: Boolean, pressed: Boolean) = fn { c, b ->
        val r = b.height() / 2f
        fill(c, b, r, if (filled) primary else secondaryContainer)
        // The Material "state layer": a veil of the content colour while pressed.
        if (pressed) fill(c, b, r, withAlpha(if (filled) onPrimary else onSecondaryContainer, 0.12f))
    }

    override fun chip(selected: Boolean) = fn { c, b ->
        val r = dp(10f)
        if (selected) fill(c, b, r, secondaryContainer) else stroke(c, b, r, dp(1f), outline)
    }

    override fun chipText(selected: Boolean) = if (selected) onSecondaryContainer else onSurfaceVariant
    override fun buttonText(filled: Boolean) = if (filled) onPrimary else onSecondaryContainer

    override fun track(on: Boolean) = fn { c, b ->
        val r = b.height() / 2f
        if (on) {
            fill(c, b, r, primary)
        } else {
            fill(c, b, r, containerHigh)
            stroke(c, b, r, dp(2f), outline)
        }
    }

    override fun thumb(on: Boolean) = fn(pad(2f)) { c, b ->
        c.drawCircle(b.centerX(), b.centerY(), b.width() / 2f, Fx.paint(if (on) onPrimary else outline))
    }

    override fun tile() = fn { c, b -> fill(c, b, dp(14f), secondaryContainer) }
    override val tileIcon = onSecondaryContainer

    override val sliderActive = primary
    override val sliderInactive = secondaryContainer
    override val sliderThumb = primary
    override val sliderThumbElevationDp = 0f
    override val sliderTrackDp = 8f
    override fun swatchStroke(on: Boolean) = if (on) onSurface else hairline
}

/**
 * Glass: translucent panels with a bright edge over soft glowing colour. With [liquid] (the second generation) the
 * glass is clearer and thicker: stronger rims and edge glow, fully round controls, big glossy thumbs, and a
 * background that slowly drifts.
 */
class GlassSkin(density: Float, private val liquid: Boolean, private val animate: Boolean = true) :
    Skin(if (liquid) UiStyle.LIQUID_GLASS else UiStyle.GLASS, density) {

    override val bg = if (liquid) argb(0xFF05060F) else argb(0xFF0B1020)
    override val text = WHITE
    override val textDim = argb(0xFFC3CCE8)
    override val accent = if (liquid) argb(0xFF7DD3FC) else argb(0xFFA5B4FC)
    override val onAccent = argb(0xFF0B1020)
    override val good = argb(0xFF6EE7B7)
    override val warn = argb(0xFFFCD34D)
    override val scrim = withAlpha(bg, 0.7f)
    override val hairline = 0x59FFFFFF

    override val trackWidthDp = if (liquid) 58f else 52f
    override val trackHeightDp = if (liquid) 32f else 30f
    override fun thumbDp(on: Boolean) = if (liquid) (if (on) 32f else 28f) else (if (on) 24f else 22f)

    override val sliderActive = argb(0xE6FFFFFF)
    override val sliderInactive = 0x33FFFFFF
    override val sliderThumb = WHITE
    override val sliderThumbElevationDp = 3f
    override val sliderTrackDp = if (liquid) 8f else 6f
    override fun swatchStroke(on: Boolean) = if (on) WHITE else hairline

    override fun screen(): Drawable = AuroraDrawable(
        bg,
        if (liquid) listOf(
            AuroraDrawable.Blob(0.12f, 0.10f, 0.60f, argb(0xCC8B5CF6), 0f),
            AuroraDrawable.Blob(0.95f, 0.28f, 0.55f, argb(0xBB06B6D4), 2f),
            AuroraDrawable.Blob(0.18f, 0.72f, 0.60f, argb(0xBBEC4899), 4f),
            AuroraDrawable.Blob(0.88f, 0.92f, 0.55f, argb(0xAA3B82F6), 1f),
            AuroraDrawable.Blob(0.55f, 0.50f, 0.35f, argb(0x66F59E0B), 3f),
        ) else listOf(
            AuroraDrawable.Blob(0.15f, 0.12f, 0.55f, argb(0x998B5CF6), 0f),
            AuroraDrawable.Blob(0.90f, 0.30f, 0.50f, argb(0x8806B6D4), 2f),
            AuroraDrawable.Blob(0.20f, 0.78f, 0.55f, argb(0x88EC4899), 4f),
            AuroraDrawable.Blob(0.85f, 0.95f, 0.50f, argb(0x883B82F6), 1f),
        ),
        // The slow drift is movement, so Reduce animations holds it still.
        drift = liquid && animate,
    )

    private fun rim(b: RectF, strong: Float, weak: Float) = diagonal(
        b, withAlpha(WHITE, strong), withAlpha(WHITE, 0.05f), withAlpha(WHITE, 0.05f), withAlpha(WHITE, weak),
        positions = floatArrayOf(0f, 0.35f, 0.65f, 1f),
    )

    override fun card(index: Int) = fn(pad(14f, 8f, 14f, 22f)) { c, b ->
        val r = dp(if (liquid) 32f else 24f)
        shadow(c, b, r, 0f, dp(6f), dp(14f), withAlpha(BLACK, 0.35f), outsideOnly = true)
        fill(c, b, r, withAlpha(WHITE, if (liquid) 0.07f else 0.12f))
        if (liquid) {
            fill(c, b, r, vertical(b, withAlpha(WHITE, 0.16f), 0, 0, positions = floatArrayOf(0f, 0.45f, 1f)))
            inner(c, b, r, 0f, 0f, dp(14f), withAlpha(WHITE, 0.22f))
        } else {
            fill(c, b, r, diagonal(b, withAlpha(WHITE, 0.20f), withAlpha(WHITE, 0.03f)))
        }
        stroke(c, b, r, dp(if (liquid) 1.5f else 1f), rim(b, if (liquid) 0.9f else 0.5f, if (liquid) 0.55f else 0.2f))
    }

    override fun field() = fn(pad(2f)) { c, b ->
        val r = dp(if (liquid) 20f else 14f)
        fill(c, b, r, withAlpha(WHITE, 0.07f))
        inner(c, b, r, 0f, dp(2f), dp(4f), withAlpha(BLACK, 0.35f))
        stroke(c, b, r, dp(1f), rim(b, 0.5f, 0.3f))
    }

    override fun button(filled: Boolean, pressed: Boolean) = fn(pad(6f, 3f, 6f, 9f)) { c, b ->
        val r = if (liquid) b.height() / 2f else dp(16f)
        shadow(c, b, r, 0f, dp(3f), dp(6f), withAlpha(BLACK, 0.3f), outsideOnly = true)
        if (filled) fill(c, b, r, withAlpha(accent, if (pressed) 0.65f else 0.45f))
        else fill(c, b, r, withAlpha(WHITE, if (pressed) 0.22f else 0.10f))
        fill(c, b, r, vertical(b, withAlpha(WHITE, 0.22f), 0, positions = floatArrayOf(0f, 0.6f)))
        stroke(c, b, r, dp(if (liquid) 1.5f else 1f), rim(b, 0.8f, 0.4f))
    }

    override fun chip(selected: Boolean) = fn(pad(2f)) { c, b ->
        val r = b.height() / 2f
        fill(c, b, r, withAlpha(WHITE, if (selected) 0.26f else 0.08f))
        if (selected) fill(c, b, r, withAlpha(accent, 0.30f))
        stroke(c, b, r, dp(1f), rim(b, if (selected) 0.95f else 0.45f, if (selected) 0.6f else 0.2f))
    }

    override fun chipText(selected: Boolean) = if (selected) WHITE else textDim
    override fun buttonText(filled: Boolean) = WHITE

    override fun track(on: Boolean) = fn { c, b ->
        val r = b.height() / 2f
        if (on) fill(c, b, r, diagonal(b, withAlpha(accent, 0.85f), withAlpha(good, 0.75f)))
        else fill(c, b, r, withAlpha(WHITE, 0.12f))
        inner(c, b, r, 0f, dp(2f), dp(3f), withAlpha(BLACK, 0.3f))
        stroke(c, b, r, dp(1f), rim(b, 0.7f, 0.3f))
    }

    override fun thumb(on: Boolean) = fn(pad(4f, 3f, 4f, 6f)) { c, b ->
        val r = b.width() / 2f
        shadow(c, b, r, 0f, dp(2f), dp(4f), withAlpha(BLACK, 0.4f))
        c.drawCircle(b.centerX(), b.centerY(), r, Fx.paint(WHITE).apply { shader = vertical(b, WHITE, argb(0xFFDCE4FF)) })
        if (liquid) {
            val glint = RectF(b.left + b.width() * 0.2f, b.top + b.height() * 0.08f, b.right - b.width() * 0.2f, b.top + b.height() * 0.45f)
            fill(c, glint, glint.height() / 2f, withAlpha(WHITE, 0.85f))
        }
    }
}

/**
 * Skeuomorphic: real-world materials. A stitched leather desk, brushed-metal panels with light from above, glossy
 * bevelled buttons and knobs, inset fields, and letter-pressed text.
 */
class SkeuoSkin(density: Float) : Skin(UiStyle.SKEUOMORPHIC, density) {
    override val bg = argb(0xFF3B2A20)
    override val text = argb(0xFF2B2E33)
    override val textDim = argb(0xFF646A74)
    override val screenText = argb(0xFFF3E7D3)
    override val screenTextDim = argb(0xFFC9B79C)
    override val accent = argb(0xFF2F7DE1)
    override val onAccent = WHITE
    override val good = argb(0xFF2E8B3D)
    override val warn = argb(0xFFB06F00)
    override val scrim = argb(0xCC2A1D15)
    override val hairline = 0x66000000

    override val trackWidthDp = 56f
    override val trackHeightDp = 30f
    override fun thumbDp(on: Boolean) = 26f

    override val sliderActive = accent
    override val sliderInactive = argb(0xFFB3B8BF)
    override val sliderThumb = argb(0xFFF2F3F5)
    override val sliderThumbStroke = argb(0xFF6B7078)
    override val sliderThumbStrokeDp = 1.5f
    override val sliderThumbElevationDp = 3f
    override fun swatchStroke(on: Boolean) = if (on) text else hairline

    override fun screen(): Drawable = LeatherDrawable(density)

    override fun styleText(view: TextView, role: TextRole, onScreen: Boolean) {
        // Letter-pressed: a light edge under dark text on metal, a dark edge under light text on leather.
        if (onScreen) view.setShadowLayer(0.5f, 0f, -dp(1f), argb(0xCC000000))
        else view.setShadowLayer(0.5f, 0f, dp(1f), argb(0xCCFFFFFF))
    }

    override fun card(index: Int) = fn(pad(6f, 4f, 6f, 10f)) { c, b ->
        val r = dp(14f)
        shadow(c, b, r, 0f, dp(4f), dp(8f), argb(0x99000000))
        fill(c, b, r, vertical(b, argb(0xFFF8F8F9), argb(0xFFE4E6E9), argb(0xFFD2D5D9), argb(0xFFC4C8CD),
            positions = floatArrayOf(0f, 0.06f, 0.5f, 1f)))
        // Brushed metal: fine horizontal grain.
        c.save()
        val clip = Path().apply { addRoundRect(b, r, r, Path.Direction.CW) }
        c.clipPath(clip)
        val light = Fx.paint(withAlpha(WHITE, 0.08f)).apply { strokeWidth = 1f }
        val dark = Fx.paint(withAlpha(BLACK, 0.035f)).apply { strokeWidth = 1f }
        var y = b.top
        var flip = false
        while (y < b.bottom) {
            c.drawLine(b.left, y, b.right, y, if (flip) dark else light)
            flip = !flip
            y += dp(2.2f)
        }
        c.restore()
        inner(c, b, r, 0f, dp(1.5f), 0.5f, withAlpha(WHITE, 0.9f))
        inner(c, b, r, 0f, -dp(2f), dp(3f), withAlpha(BLACK, 0.22f))
        stroke(c, b, r, dp(1f), argb(0xFF5B6067))
    }

    override fun field() = fn(pad(1f, 1f, 1f, 2f)) { c, b ->
        val r = dp(8f)
        shadow(c, b, r, 0f, dp(1f), 0f, withAlpha(WHITE, 0.9f))
        fill(c, b, r, argb(0xFFFCFCFC))
        inner(c, b, r, 0f, dp(2f), dp(3f), withAlpha(BLACK, 0.38f))
        stroke(c, b, r, dp(1f), argb(0xFF8E949C))
    }

    private fun gloss(c: Canvas, b: RectF, r: Float, colors: IntArray, border: Int, pressed: Boolean) {
        shadow(c, b, r, 0f, dp(2f), dp(3f), withAlpha(BLACK, 0.5f))
        fill(c, b, r, vertical(b, colors[0], colors[1], colors[2], colors[3], positions = floatArrayOf(0f, 0.49f, 0.5f, 1f)))
        if (!pressed) {
            val half = RectF(b.left + dp(1f), b.top + dp(1f), b.right - dp(1f), b.centerY())
            fill(c, half, r, vertical(half, withAlpha(WHITE, 0.32f), withAlpha(WHITE, 0.04f)))
        } else {
            inner(c, b, r, 0f, dp(2f), dp(4f), withAlpha(BLACK, 0.35f))
        }
        stroke(c, b, r, dp(1f), border)
    }

    private val blue = intArrayOf(argb(0xFF86B9F9), argb(0xFF4F96EF), argb(0xFF1F63C8), argb(0xFF3A84E6))
    private val bluePressed = intArrayOf(argb(0xFF5E9BE0), argb(0xFF2F76D6), argb(0xFF174FA6), argb(0xFF2A6FD0))
    private val grey = intArrayOf(WHITE, argb(0xFFEDEEF0), argb(0xFFCFD2D6), argb(0xFFDDE0E3))
    private val greyPressed = intArrayOf(argb(0xFFD8DADD), argb(0xFFC4C7CB), argb(0xFFADB1B6), argb(0xFFBCC0C5))

    override fun button(filled: Boolean, pressed: Boolean) = fn(pad(1f, 1f, 1f, 3f)) { c, b ->
        val colors = if (filled) (if (pressed) bluePressed else blue) else (if (pressed) greyPressed else grey)
        gloss(c, b, dp(10f), colors, if (filled) argb(0xFF1A4A96) else argb(0xFF868A90), pressed)
    }

    override fun chip(selected: Boolean) = fn(pad(1f, 1f, 1f, 3f)) { c, b ->
        gloss(c, b, dp(9f), if (selected) bluePressed else grey, if (selected) argb(0xFF1A4A96) else argb(0xFF868A90), selected)
    }

    override fun chipText(selected: Boolean) = if (selected) WHITE else text
    override fun buttonText(filled: Boolean) = if (filled) WHITE else text

    override fun track(on: Boolean) = fn(pad(0f, 0f, 0f, 1f)) { c, b ->
        val r = b.height() / 2f
        shadow(c, b, r, 0f, dp(1f), 0f, withAlpha(WHITE, 0.8f))
        fill(c, b, r, if (on) vertical(b, argb(0xFF1F63C8), argb(0xFF5FA2F2)) else vertical(b, argb(0xFFB9BDC3), argb(0xFFE1E3E6)))
        inner(c, b, r, 0f, dp(2f), dp(3f), withAlpha(BLACK, 0.45f))
        stroke(c, b, r, dp(1f), if (on) argb(0xFF1A4A96) else argb(0xFF7E838A))
    }

    override fun thumb(on: Boolean) = fn(pad(3f, 2f, 3f, 5f)) { c, b ->
        val r = b.width() / 2f
        shadow(c, b, r, 0f, dp(2f), dp(3f), withAlpha(BLACK, 0.55f))
        c.drawCircle(b.centerX(), b.centerY(), r, Fx.paint(WHITE).apply { shader = vertical(b, WHITE, argb(0xFFD6D9DD), argb(0xFFA9ADB3)) })
        stroke(c, b, r, dp(1f), argb(0xFF72777E))
    }
}

/** Neumorphic: one soft material. Things rise out of the surface or press into it, lit from the top left. */
class NeumorphicSkin(density: Float) : Skin(UiStyle.NEUMORPHIC, density) {
    override val bg = argb(0xFFE0E5EC)
    override val text = argb(0xFF3F4A5E)
    override val textDim = argb(0xFF7B879C)
    override val accent = argb(0xFF5B6CFF)
    override val onAccent = WHITE
    override val good = argb(0xFF1E9B66)
    override val warn = argb(0xFFB87300)
    override val hairline = 0x33000000

    private val lightShadow = WHITE
    private val darkShadow = argb(0xFFA3B1C6)

    override val trackWidthDp = 56f
    override val trackHeightDp = 30f
    override fun thumbDp(on: Boolean) = 22f

    override val sliderActive = accent
    override val sliderInactive = argb(0xFFC5CEDB)
    override val sliderThumb = argb(0xFFE9EEF5)
    override val sliderThumbElevationDp = 6f

    override fun screen(): Drawable = ColorDrawable(bg)

    private fun raised(c: Canvas, b: RectF, r: Float, d: Float, base: Int = bg) {
        shadow(c, b, r, -d, -d, d * 1.6f, lightShadow)
        shadow(c, b, r, d, d, d * 1.6f, darkShadow)
        fill(c, b, r, diagonal(b, shade(base, 0.35f), base, shade(base, -0.06f), positions = floatArrayOf(0f, 0.5f, 1f)))
    }

    private fun sunken(c: Canvas, b: RectF, r: Float, d: Float, tint: Int = 0) {
        fill(c, b, r, bg)
        if (tint != 0) fill(c, b, r, tint)
        inner(c, b, r, d, d, d * 1.3f, withAlpha(darkShadow, 0.9f))
        inner(c, b, r, -d, -d, d * 1.3f, lightShadow)
    }

    override fun card(index: Int) = fn(pad(24f)) { c, b -> raised(c, b, dp(28f), dp(8f)) }

    override fun field() = fn(pad(8f)) { c, b -> sunken(c, b, dp(16f), dp(4f)) }

    override fun button(filled: Boolean, pressed: Boolean) = fn(pad(16f)) { c, b ->
        val r = dp(18f)
        if (pressed) {
            sunken(c, b, r, dp(4f), if (filled) withAlpha(accent, 0.85f) else 0)
        } else {
            raised(c, b, r, dp(5f))
            if (filled) fill(c, b, r, diagonal(b, argb(0xFF7B89FF), argb(0xFF4F5FF0)))
        }
    }

    override fun chip(selected: Boolean) = fn(pad(11f)) { c, b ->
        val r = b.height() / 2f
        if (selected) sunken(c, b, r, dp(3f)) else raised(c, b, r, dp(3f))
    }

    override fun chipText(selected: Boolean) = if (selected) accent else textDim
    override fun buttonText(filled: Boolean) = if (filled) WHITE else text

    override fun track(on: Boolean) = fn(pad(4f)) { c, b ->
        sunken(c, b, b.height() / 2f, dp(3f), if (on) withAlpha(accent, 0.28f) else 0)
    }

    override fun thumb(on: Boolean) = fn(pad(9f)) { c, b ->
        val r = b.width() / 2f
        raised(c, b, r, dp(3f))
        if (on) c.drawCircle(b.centerX(), b.centerY(), r * 0.38f, Fx.paint(accent))
    }
}

/** Clay: soft, puffy, pastel shapes with a rounded sheen, as if moulded from modelling clay. */
class ClaySkin(density: Float) : Skin(UiStyle.CLAY, density) {
    override val bg = argb(0xFFEFE9FF)
    override val text = argb(0xFF3A2F66)
    override val textDim = argb(0xFF73699F)
    override val accent = argb(0xFF7C5CFF)
    override val onAccent = WHITE
    override val good = argb(0xFF14996B)
    override val warn = argb(0xFFC27500)
    override val hairline = 0x553A2F66

    private val pastels = intArrayOf(
        argb(0xFFFFC7DD), argb(0xFFBFE6FF), argb(0xFFC9F5DD), argb(0xFFFFDDBF), argb(0xFFDCCBFF), argb(0xFFFFF0B3),
    )

    override val trackWidthDp = 58f
    override val trackHeightDp = 34f
    override fun thumbDp(on: Boolean) = 26f

    override val sliderActive = accent
    override val sliderInactive = argb(0xFFD5CCF2)
    override val sliderThumb = WHITE
    override val sliderThumbStroke = accent
    override val sliderThumbStrokeDp = 2f
    override val sliderThumbElevationDp = 4f
    override val sliderTrackDp = 8f

    override fun screen(): Drawable = fn { c, b ->
        c.drawRect(b, Paint().apply { shader = vertical(b, argb(0xFFF3EDFF), argb(0xFFE4F2FF)) })
    }

    private fun puffy(c: Canvas, b: RectF, r: Float, color: Int, lift: Float) {
        shadow(c, b, r, 0f, lift, lift * 1.6f, withAlpha(shade(color, -0.5f), 0.55f))
        fill(c, b, r, vertical(b, shade(color, 0.2f), color))
        inner(c, b, r, dp(5f), dp(5f), dp(8f), withAlpha(WHITE, 0.95f))
        inner(c, b, r, -dp(7f), -dp(7f), dp(11f), withAlpha(shade(color, -0.3f), 0.55f))
    }

    override fun card(index: Int) = fn(pad(22f, 10f, 22f, 30f)) { c, b ->
        puffy(c, b, dp(34f), pastels[index.mod(pastels.size)], dp(8f))
    }

    override fun field() = fn(pad(4f)) { c, b ->
        val r = dp(18f)
        fill(c, b, r, withAlpha(WHITE, 0.75f))
        inner(c, b, r, dp(3f), dp(3f), dp(6f), withAlpha(text, 0.30f))
    }

    override fun button(filled: Boolean, pressed: Boolean) = fn(pad(14f, 6f, 14f, 22f)) { c, b ->
        val body = if (pressed) RectF(b).apply { offset(0f, dp(3f)) } else b
        puffy(c, body, dp(22f), if (filled) accent else WHITE, if (pressed) dp(3f) else dp(8f))
    }

    override fun chip(selected: Boolean) = fn(pad(9f, 5f, 9f, 15f)) { c, b ->
        puffy(c, b, b.height() / 2f, if (selected) accent else WHITE, dp(5f))
    }

    override fun chipText(selected: Boolean) = if (selected) WHITE else text
    override fun buttonText(filled: Boolean) = if (filled) WHITE else text

    override fun track(on: Boolean) = fn(pad(6f, 4f, 6f, 12f)) { c, b ->
        puffy(c, b, b.height() / 2f, if (on) argb(0xFF7FE3B5) else argb(0xFFD9D2F2), dp(3f))
    }

    override fun thumb(on: Boolean) = fn(pad(9f, 5f, 9f, 14f)) { c, b ->
        puffy(c, b, b.width() / 2f, WHITE, dp(4f))
    }
}

/** Neo-brutalism: flat bold colour, thick black outlines, hard offset shadows and heavy type. */
class BrutalSkin(density: Float) : Skin(UiStyle.BRUTAL, density) {
    override val bg = argb(0xFFFFF4D6)
    override val text = argb(0xFF111111)
    override val textDim = argb(0xFF444444)
    override val accent = argb(0xFFFF4F81)
    override val onAccent = text
    override val good = argb(0xFF0A7F44)
    override val warn = argb(0xFFB45300)
    override val hairline = argb(0xFF111111)

    private val ink = argb(0xFF111111)
    private val colors = intArrayOf(
        WHITE, argb(0xFFFFE066), argb(0xFFFFB3C7), argb(0xFF9BE7F0), argb(0xFFC6F28B), argb(0xFFD3C2FF),
    )

    override val trackWidthDp = 56f
    override val trackHeightDp = 32f
    override fun thumbDp(on: Boolean) = 20f

    override val sliderActive = accent
    override val sliderInactive = 0x33111111
    override val sliderThumb = argb(0xFFFFD93D)
    override val sliderThumbStroke = ink
    override val sliderThumbStrokeDp = 3f
    override val sliderThumbElevationDp = 0f
    override val sliderTrackDp = 10f
    override fun swatchStrokeDp(on: Boolean) = if (on) 5f else 3f
    override fun swatchStroke(on: Boolean) = ink

    // Heavy type is part of this look, unless another font has been chosen.
    override fun typeface(bold: Boolean): Typeface = when {
        AppFonts.current != AppFont.DEFAULT -> AppFonts.current.typeface(bold)
        bold -> Typeface.create("sans-serif-black", Typeface.NORMAL)
        else -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    override fun styleText(view: TextView, role: TextRole, onScreen: Boolean) {
        if (role == TextRole.TITLE || role == TextRole.LABEL) {
            view.isAllCaps = true
            view.letterSpacing = 0.04f
        }
    }

    override fun screen(): Drawable = ColorDrawable(bg)

    /** A slab: black outline, flat colour, and a hard shadow of the same shape pushed down and right. */
    private fun slab(c: Canvas, b: RectF, r: Float, color: Int, border: Float, offset: Float) {
        shadow(c, b, r, offset, offset, 0f, ink)
        fill(c, b, r, color)
        stroke(c, b, r, border, ink)
    }

    override fun card(index: Int) = fn(pad(0f, 0f, 6f, 6f)) { c, b ->
        slab(c, b, dp(6f), colors[index.mod(colors.size)], dp(3f), dp(6f))
    }

    override fun field() = fn(pad(0f, 0f, 3f, 3f)) { c, b -> slab(c, b, dp(4f), WHITE, dp(3f), dp(3f)) }

    override fun button(filled: Boolean, pressed: Boolean) = fn(pad(0f, 0f, 5f, 5f)) { c, b ->
        val color = if (filled) accent else WHITE
        if (pressed) slab(c, RectF(b).apply { offset(dp(4f), dp(4f)) }, dp(4f), color, dp(3f), dp(1f))
        else slab(c, b, dp(4f), color, dp(3f), dp(5f))
    }

    override fun chip(selected: Boolean) = fn(pad(0f, 0f, 3f, 3f)) { c, b ->
        slab(c, b, dp(4f), if (selected) ink else WHITE, dp(2.5f), dp(3f))
    }

    override fun chipText(selected: Boolean) = if (selected) WHITE else text
    override fun buttonText(filled: Boolean) = text

    override fun track(on: Boolean) = fn { c, b ->
        fill(c, b, dp(4f), if (on) argb(0xFF7CF29A) else WHITE)
        stroke(c, b, dp(4f), dp(3f), ink)
    }

    override fun thumb(on: Boolean) = fn { c, b -> fill(c, b, dp(2f), ink) }

    override fun tile() = fn(pad(0f, 0f, 3f, 3f)) { c, b -> slab(c, b, dp(4f), WHITE, dp(2.5f), dp(3f)) }
}

/** Leather desk: a tiled grain, darkened towards the edges, with a stitched seam just inside them. */
private class LeatherDrawable(private val density: Float) : Drawable() {
    private val grain = Paint().apply {
        shader = BitmapShader(Leather.tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val vignette = Paint()
    private val seam = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
        color = argb(0xCCD9BE8C)
    }
    private val seamShade = Paint(seam).apply { color = argb(0x88000000) }

    override fun onBoundsChange(bounds: Rect) {
        vignette.shader = RadialGradient(
            bounds.exactCenterX(), bounds.height() * 0.4f, maxOf(bounds.width(), bounds.height()) * 0.8f,
            intArrayOf(0x00000000, 0xB3000000.toInt()), floatArrayOf(0.35f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, grain)
        canvas.drawRect(bounds, vignette)
        val inset = 8f * density
        val rect = RectF(bounds).apply { inset(inset, inset) }
        val r = 18f * density
        canvas.save()
        canvas.translate(0f, density)
        canvas.drawRoundRect(rect, r, r, seamShade)
        canvas.restore()
        canvas.drawRoundRect(rect, r, r, seam)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Required by Drawable")
    override fun getOpacity() = PixelFormat.OPAQUE
}

private object Leather {
    /** A small tile of leather grain: brown with pores and fine noise. It repeats, so it only has to be made once. */
    val tile: Bitmap by lazy {
        val size = 128
        val random = java.util.Random(11)
        val pixels = IntArray(size * size) {
            val n = random.nextInt(33) - 16 + if (random.nextInt(24) == 0) -22 else 0
            val r = (0x4A + n).coerceIn(0, 255)
            val g = (0x33 + n * 0.8f).toInt().coerceIn(0, 255)
            val b = (0x26 + n * 0.7f).toInt().coerceIn(0, 255)
            BLACK or (r shl 16) or (g shl 8) or b
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
