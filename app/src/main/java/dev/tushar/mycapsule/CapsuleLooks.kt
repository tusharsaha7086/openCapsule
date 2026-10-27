package dev.tushar.mycapsule

import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import dev.tushar.mycapsule.Fx.diagonal
import dev.tushar.mycapsule.Fx.fill
import dev.tushar.mycapsule.Fx.inner
import dev.tushar.mycapsule.Fx.shade
import dev.tushar.mycapsule.Fx.stroke
import dev.tushar.mycapsule.Fx.vertical
import dev.tushar.mycapsule.Fx.withAlpha

private const val WHITE = -1
private const val BLACK = 0xFF000000.toInt()
private const val DEFAULT_SUBTITLE = 0xFFCCCCCC.toInt()

/**
 * How the capsule itself looks in one of the app styles: what its body is made of, and the colours of the text, icons,
 * bars and buttons on it. The capsule's own Theme settings (colour, shape, border, shadow) still apply: the colour is
 * what the material is tinted with, and the border is drawn on top.
 *
 * The capsule's body is clipped to its rounded outline while it morphs, so a look can only paint inside its own shape:
 * highlights, rims, gloss, glow and inner shadows. Drop shadows are the Theme's "Shadow" setting.
 */
class CapsuleLook(
    /** The body, tinted with the capsule colour, for corners of the given radius in pixels. */
    val fill: (color: Int, radius: Float) -> Drawable,
    /** The pill behind a labelled action button. */
    val chip: (radius: Float) -> Drawable,
    val title: Int = WHITE,
    val subtitle: Int = DEFAULT_SUBTITLE,
    val icon: Int = WHITE,
    val chipText: Int = WHITE,
    val barActive: Int = WHITE,
    val barTrack: Int = 0x44FFFFFF,
    val dotOn: Int = WHITE,
    val dotOff: Int = 0x55FFFFFF,
    val boldTypeface: Typeface? = null,
)

object CapsuleLooks {
    /** The look for [style], or null where the capsule keeps its plain flat look (Material You). */
    fun of(style: UiStyle, density: Float): CapsuleLook? = when (style) {
        UiStyle.MATERIAL -> null
        UiStyle.GLASS -> glass(density, liquid = false)
        UiStyle.LIQUID_GLASS -> glass(density, liquid = true)
        UiStyle.SKEUOMORPHIC -> skeuomorphic(density)
        UiStyle.NEUMORPHIC -> neumorphic(density)
        UiStyle.CLAY -> clay(density)
        UiStyle.BRUTAL -> brutal(density)
    }

    /** The capsule body: the look's material, with the user's border (if any) drawn over it. */
    fun body(look: CapsuleLook, color: Int, radius: Float, borderPx: Int, borderColor: Int): Drawable {
        val material = look.fill(color, radius)
        if (borderPx <= 0) return material
        val outline = GradientDrawable().apply {
            cornerRadius = radius
            setStroke(borderPx, borderColor)
        }
        return LayerDrawable(arrayOf(material, outline))
    }

    private fun rim(b: RectF, strong: Float, weak: Float) = diagonal(
        b, withAlpha(WHITE, strong), withAlpha(WHITE, 0.05f), withAlpha(WHITE, 0.05f), withAlpha(WHITE, weak),
        positions = floatArrayOf(0f, 0.35f, 0.65f, 1f),
    )

    private fun corner(r: Float, b: RectF) = minOf(r, b.height() / 2f)

    /** Translucent tinted glass over whatever is behind the capsule; liquid glass is clearer, thicker and brighter. */
    private fun glass(d: Float, liquid: Boolean) = CapsuleLook(
        fill = { color, radius ->
            FnDrawable { c, b ->
                val r = corner(radius, b)
                fill(c, b, r, withAlpha(color, if (liquid) 0.42f else 0.58f))
                if (liquid) {
                    fill(c, b, r, vertical(b, withAlpha(WHITE, 0.30f), 0, positions = floatArrayOf(0f, 0.5f)))
                    inner(c, b, r, 0f, 0f, 8f * d, withAlpha(WHITE, 0.30f))
                    // A thin highlight along the top edge, where the curved glass catches the light.
                    val glint = RectF(b.left + b.width() * 0.12f, b.top + 3f * d, b.left + b.width() * 0.55f, b.top + 3f * d + minOf(b.height() * 0.10f, 5f * d))
                    fill(c, glint, glint.height() / 2f, withAlpha(WHITE, 0.35f))
                } else {
                    fill(c, b, r, diagonal(b, withAlpha(WHITE, 0.22f), withAlpha(WHITE, 0.03f)))
                }
                stroke(c, b, r, (if (liquid) 1.5f else 1f) * d, rim(b, if (liquid) 0.95f else 0.6f, if (liquid) 0.6f else 0.3f))
            }
        },
        chip = { radius ->
            FnDrawable { c, b ->
                val r = corner(radius, b)
                fill(c, b, r, withAlpha(WHITE, 0.18f))
                stroke(c, b, r, d, rim(b, 0.6f, 0.25f))
            }
        },
        barTrack = 0x55FFFFFF,
    )

    /** Glossy glass in the colour, with the hard highlight across its upper half, and a light bevel inside the edge. */
    private fun skeuomorphic(d: Float) = CapsuleLook(
        fill = { color, radius ->
            FnDrawable { c, b ->
                val r = corner(radius, b)
                fill(c, b, r, vertical(b, shade(color, 0.30f), shade(color, 0.12f), shade(color, -0.12f), shade(color, 0.02f),
                    positions = floatArrayOf(0f, 0.49f, 0.5f, 1f)))
                val half = RectF(b.left + d, b.top + d, b.right - d, b.centerY())
                fill(c, half, corner(r, half), vertical(half, withAlpha(WHITE, 0.22f), withAlpha(WHITE, 0.02f)))
                inner(c, b, r, 0f, 1.5f * d, 0.5f, withAlpha(WHITE, 0.45f))
                stroke(c, b, r, d, withAlpha(BLACK, 0.8f))
            }
        },
        chip = { radius ->
            FnDrawable { c, b ->
                val r = corner(radius, b)
                fill(c, b, r, vertical(b, withAlpha(WHITE, 0.30f), withAlpha(WHITE, 0.10f)))
                stroke(c, b, r, d, withAlpha(WHITE, 0.35f))
            }
        },
    )

    /** One soft material lit from the top left: a light edge on that side, a shaded edge on the other. */
    private fun neumorphic(d: Float) = CapsuleLook(
        fill = { color, radius ->
            FnDrawable { c, b ->
                val r = corner(radius, b)
                // Lifted a little first, so a black capsule still has light and shade to show.
                val base = shade(color, 0.14f)
                fill(c, b, r, diagonal(b, shade(base, 0.18f), base, shade(base, -0.18f), positions = floatArrayOf(0f, 0.5f, 1f)))
                inner(c, b, r, 3f * d, 3f * d, 5f * d, withAlpha(WHITE, 0.30f))
                inner(c, b, r, -3f * d, -3f * d, 6f * d, withAlpha(BLACK, 0.50f))
            }
        },
        chip = { radius ->
            FnDrawable { c, b ->
                val r = corner(radius, b)
                fill(c, b, r, withAlpha(BLACK, 0.18f))
                inner(c, b, r, 2f * d, 2f * d, 3f * d, withAlpha(BLACK, 0.45f))
                inner(c, b, r, -2f * d, -2f * d, 3f * d, withAlpha(WHITE, 0.14f))
            }
        },
        title = 0xFFF2F5FA.toInt(),
        subtitle = 0xFFB8C2D3.toInt(),
    )

    /** Puffy, as if moulded: a bright rounded highlight top left and a deeper shade bottom right, inside the shape. */
    private fun clay(d: Float) = CapsuleLook(
        fill = { color, radius ->
            FnDrawable { c, b ->
                val r = corner(radius, b)
                val base = shade(color, 0.14f)
                fill(c, b, r, vertical(b, shade(base, 0.24f), base))
                inner(c, b, r, 3f * d, 3f * d, 5f * d, withAlpha(WHITE, 0.75f))
                inner(c, b, r, -4f * d, -4f * d, 8f * d, withAlpha(shade(base, -0.6f), 0.9f))
            }
        },
        chip = { radius ->
            FnDrawable { c, b ->
                val r = corner(radius, b)
                fill(c, b, r, withAlpha(WHITE, 0.22f))
                inner(c, b, r, 2f * d, 2f * d, 4f * d, withAlpha(WHITE, 0.6f))
                inner(c, b, r, -3f * d, -3f * d, 5f * d, withAlpha(BLACK, 0.25f))
            }
        },
    )

    /** Flat colour with a thick bright outline, and chips in solid yellow with a black edge. */
    private fun brutal(d: Float): CapsuleLook {
        val yellow = 0xFFFFE066.toInt()
        val ink = 0xFF111111.toInt()
        return CapsuleLook(
            fill = { color, radius ->
                FnDrawable { c, b ->
                    val r = corner(radius, b)
                    fill(c, b, r, color)
                    stroke(c, b, r, 3f * d, yellow)
                }
            },
            chip = { radius ->
                FnDrawable { c, b ->
                    val r = corner(radius, b)
                    fill(c, b, r, yellow)
                    stroke(c, b, r, 2f * d, ink)
                }
            },
            subtitle = yellow,
            chipText = ink,
            barActive = yellow,
            barTrack = 0x55FFFFFF,
            dotOn = yellow,
            boldTypeface = Typeface.create("sans-serif-black", Typeface.NORMAL),
        )
    }
}
