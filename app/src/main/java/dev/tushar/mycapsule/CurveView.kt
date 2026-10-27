package dev.tushar.mycapsule

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.Interpolator

/**
 * Draws a motion curve: how open the capsule is (up) as time goes by (across), with a dashed line where it ends up.
 * A bounce shows as the curve rising past that line and coming back, so a style can be judged before it is tried.
 */
class CurveView(
    context: Context,
    private val curve: Interpolator,
    var lineColor: Int,
    private val guideColor: Int,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val pad = 10f * density
        val w = width - pad * 2
        val h = height - pad * 2
        // Room above the target line for the biggest overshoot a style has.
        val top = RANGE
        fun y(value: Float) = pad + h - h * (value / top)

        guide.color = guideColor
        canvas.drawLine(pad, y(1f), pad + w, y(1f), guide)

        path.reset()
        for (i in 0..STEPS) {
            val t = i / STEPS.toFloat()
            val x = pad + w * t
            val py = y(curve.getInterpolation(t))
            if (i == 0) path.moveTo(x, py) else path.lineTo(x, py)
        }
        line.color = lineColor
        canvas.drawPath(path, line)
    }

    private companion object {
        const val STEPS = 64
        const val RANGE = 1.35f
    }
}
