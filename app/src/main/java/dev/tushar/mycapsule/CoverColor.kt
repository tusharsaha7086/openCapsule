package dev.tushar.mycapsule

import android.graphics.Bitmap
import android.graphics.Color

/** Picks a capsule background colour from album art: saturation-weighted average, darkened for white text. */
object CoverColor {

    private const val SAMPLE = 16
    private const val MIN_VALUE = 0.18f
    private const val MAX_VALUE = 0.42f

    fun accent(bitmap: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bitmap, SAMPLE, SAMPLE, true)
        var r = 0f
        var g = 0f
        var b = 0f
        var total = 0f
        val hsv = FloatArray(3)
        for (x in 0 until SAMPLE) for (y in 0 until SAMPLE) {
            val p = small.getPixel(x, y)
            Color.colorToHSV(p, hsv)
            val weight = 0.1f + hsv[1]
            r += Color.red(p) * weight
            g += Color.green(p) * weight
            b += Color.blue(p) * weight
            total += weight
        }
        Color.colorToHSV(Color.rgb((r / total).toInt(), (g / total).toInt(), (b / total).toInt()), hsv)
        hsv[2] = hsv[2].coerceIn(MIN_VALUE, MAX_VALUE)
        return Color.HSVToColor(hsv)
    }
}
