package dev.tushar.mycapsule

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where the capsule sits, set by dragging it. This screen fills the whole display and draws a sample capsule at its real
 * size, so the spot you drop it on is exactly where the real one will appear. The idle pill is drawn over the camera, and a
 * slider sizes it. What is saved belongs to the kind of screen this is (portrait, landscape, large screen), so another
 * kind of screen keeps its own position.
 */
class PositionEditorActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var overlay: CapsuleOverlay
    private lateinit var profile: ScreenProfile
    private lateinit var capsule: View
    private lateinit var ghost: View
    private lateinit var guide: View
    private lateinit var readout: TextView

    private val density by lazy { resources.displayMetrics.density }
    private var pillPercent = Position.PILL_DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        overlay = CapsuleOverlay(applicationContext, prefs)
        profile = ScreenProfile.of(this)
        val start = prefs.position(profile)
        pillPercent = start.pillPercent
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BACKDROP))

        val stage = FrameLayout(this).apply { clipChildren = false }
        guide = View(this).apply { setBackgroundColor(GUIDE_IDLE) }
        stage.addView(guide, FrameLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))

        // The idle pill, where it is over the camera and at the size the slider gives it.
        ghost = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 1000f
                setColor(0x33FFFFFF)
                setStroke(dp(1), 0x88FFFFFF.toInt())
            }
        }
        stage.addView(ghost, FrameLayout.LayoutParams(0, 0))
        placeGhost()

        capsule = sampleCapsule()
        stage.addView(capsule, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val panel = controls()
        stage.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(stage)

        ViewCompat.setOnApplyWindowInsetsListener(stage) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            panel.setPadding(panel.paddingLeft, panel.paddingTop, panel.paddingRight, dp(16) + bars.bottom)
            insets
        }
        // Once measured, put the capsule where it is now (or where it would go by default).
        capsule.post {
            val top = (start.topDp ?: overlay.defaultTopDp()) * density
            val centerX = resources.displayMetrics.widthPixels / 2f + start.offsetXDp * density
            capsule.x = (centerX - capsule.width / 2f).coerceIn(0f, (resources.displayMetrics.widthPixels - capsule.width).toFloat().coerceAtLeast(0f))
            capsule.y = top.coerceIn(minTop(), maxTop())
            updateReadout()
        }
        capsule.setOnTouchListener(dragger())
    }

    override fun onDestroy() {
        overlay.dismiss()
        super.onDestroy()
    }

    /** A capsule like the real ones, with the colour, shape and border set under Customize. */
    private fun sampleCapsule(): View {
        // The phone's own light or dark theme, not this app's, since that is what the real capsule follows.
        val color = prefs.capsuleColor(applicationContext)
        val light = isLightColor(color)
        val ink = prefs.textInk() ?: if (light) 0xFF16161C.toInt() else Color.WHITE
        val dim = (ink and 0x00FFFFFF) or 0xBF000000.toInt()
        val look = CapsuleLooks.of(prefs.uiStyle, density)
        val radius = dp(prefs.shape.radiusDp).toFloat()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(20), dp(12))
            background = look?.let { CapsuleLooks.body(it, color, radius, dp(prefs.borderWidth.dp), prefs.borderColorValue()) }
                ?: GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = radius
                    setStroke(dp(prefs.borderWidth.dp), prefs.borderColorValue())
                }
            elevation = dp(prefs.shadow.elevationDp).toFloat()
            addView(ImageView(context).apply { setImageDrawable(packageManager.getApplicationIcon(packageName)) },
                LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = "Drag me"; textSize = 14f; setTextColor(ink); typeface = android.graphics.Typeface.DEFAULT_BOLD })
                addView(TextView(context).apply { text = "This is where capsules appear"; textSize = 12f; setTextColor(dim) })
            })
        }
    }

    /** Moves the capsule with the finger, keeps it on the screen and out of the status bar, and snaps it to the middle. */
    private fun dragger(): View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        return View.OnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = v.x; startY = v.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val screen = resources.displayMetrics.widthPixels
                    var x = (startX + e.rawX - downX).coerceIn(0f, (screen - v.width).toFloat().coerceAtLeast(0f))
                    // Close to the middle it holds there, with the guide line lit, so centred is easy to get.
                    val snapped = abs(x + v.width / 2f - screen / 2f) < dp(SNAP_DP)
                    if (snapped) x = screen / 2f - v.width / 2f
                    guide.setBackgroundColor(if (snapped) GUIDE_SNAPPED else GUIDE_IDLE)
                    v.x = x
                    v.y = (startY + e.rawY - downY).coerceIn(minTop(), maxTop())
                    updateReadout()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    /** The highest the capsule can sit: a plain overlay cannot go over the status bar. */
    private fun minTop(): Float = if (OverlayHost.isAboveStatusBar) 0f else statusBarPx().toFloat()

    /** The lowest it can sit: never below where the expanded view starts, as in the app's own limit. */
    private fun maxTop(): Float = (overlay.deckTopDp() * density).coerceAtLeast(minTop())

    private fun statusBarPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun placeGhost() {
        val r = IdlePill(this, prefs, {}, {}).bounds(pillPercent)
        ghost.layoutParams = FrameLayout.LayoutParams(r.width(), r.height()).apply { leftMargin = r.left; topMargin = r.top }
    }

    private fun offsetXDp(): Int = ((capsule.x + capsule.width / 2f - resources.displayMetrics.widthPixels / 2f) / density).roundToInt()

    private fun topDp(): Int = (capsule.y / density).roundToInt()

    private fun updateReadout() {
        val x = offsetXDp()
        val side = when {
            x == 0 -> "centred"
            x > 0 -> "$x dp right of centre"
            else -> "${-x} dp left of centre"
        }
        readout.text = "${topDp()} dp from the top, $side"
    }

    /** The panel at the bottom: what is being edited, the pill's size, and Reset, Cancel and Save. */
    private fun controls(): View {
        readout = TextView(this).apply { textSize = 13f; setTextColor(DIM) }
        val size = TextView(this).apply { text = sizeText(); textSize = 13f; setTextColor(DIM) }
        val slider = Slider(this).apply {
            valueFrom = Position.PILL_MIN.toFloat()
            valueTo = Position.PILL_MAX.toFloat()
            stepSize = Position.PILL_STEP.toFloat()
            value = pillPercent.toFloat().coerceIn(valueFrom, valueTo)
            labelBehavior = LabelFormatter.LABEL_GONE
            addOnChangeListener { _, v, _ ->
                pillPercent = v.toInt()
                size.text = sizeText()
                placeGhost()
            }
        }
        fun button(label: String, outlined: Boolean, onClick: () -> Unit) = MaterialButton(
            this, null,
            if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle,
        ).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Reset", outlined = true) { reset() })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(button("Cancel", outlined = true) { finish() })
            addView(button("Save", outlined = false) { save() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(PANEL)
                cornerRadii = floatArrayOf(dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), 0f, 0f, 0f, 0f)
            }
            addView(TextView(context).apply {
                text = "Position for ${profile.label.lowercase()}"
                textSize = 17f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                text = "Drag the capsule where you want it. It holds in the middle when you get close. Another kind of screen keeps its own position."
                textSize = 12f
                setTextColor(DIM)
                setPadding(0, dp(4), 0, dp(8))
            })
            addView(readout)
            addView(size, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(slider)
            addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
    }

    private fun sizeText() = "Idle pill size: $pillPercent%"

    private fun save() {
        prefs.setPosition(profile, Position(Align.CENTER, offsetXDp(), topDp(), pillPercent))
        setResult(RESULT_OK)
        finish()
    }

    /** Back to following the settings that apply to every screen. */
    private fun reset() {
        prefs.resetPosition(profile)
        setResult(RESULT_OK)
        finish()
    }

    private fun dp(v: Int) = (v * density).toInt()

    private companion object {
        const val SNAP_DP = 10
        const val BACKDROP = 0xFF0A0A0F.toInt()
        const val PANEL = 0xFF1B1B24.toInt()
        const val DIM = 0xFFB4B4C4.toInt()
        const val GUIDE_IDLE = 0x22FFFFFF
        const val GUIDE_SNAPPED = 0xAA8F7CFF.toInt()
    }
}
