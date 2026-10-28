package dev.tushar.mycapsule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.roundToInt

/** How big the screen pet is. */
enum class PetSize(val label: String, val dp: Int) {
    SMALL("Small", 40),
    MEDIUM("Medium", 56),
    LARGE("Large", 80),
}

/**
 * The screen pet: a small window that holds the pet's picture and is moved around the screen by [PetBrain]. Only the pet's own
 * square takes touches, so everything around it still reaches the app underneath. It uses the same window layer as the
 * capsule (above the status bar with the accessibility service, a normal overlay otherwise), runs at about 30 frames a second
 * and stops completely while the screen is off.
 */
class PetOverlay(private val context: Context, private val prefs: Prefs) {

    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val brain = PetBrain()

    private var pack: PetPack? = null
    private var view: PetView? = null
    private var target: OverlayHost.Target? = null
    private var params: WindowManager.LayoutParams? = null
    private var sizePx = 0
    private var lastTick = 0L
    private var receiver: BroadcastReceiver? = null

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = ((now - lastTick) / MS_PER_SECOND).coerceIn(0f, MAX_STEP_SECONDS)
            lastTick = now
            brain.tick(dt)
            render()
            main.postDelayed(this, FRAME_MS)
        }
    }

    /** Starts, stops or restarts the pet to match the settings: on or off, and its size. */
    fun sync() {
        val wanted = prefs.petEnabled && OverlayHost.canDraw(context)
        when {
            !wanted -> stop()
            view == null -> start()
            sizePx != dp(prefs.petSize.dp) -> {
                stop()
                start()
            }
        }
    }

    fun start() {
        if (view != null) return
        val loaded = pack ?: PetPack.load(context) ?: return
        pack = loaded
        sizePx = dp(prefs.petSize.dp)
        val host = OverlayHost.target(context)
        target = host
        val pet = PetView(context).apply {
            contentDescription = "Screen pet"
            setOnTouchListener(touch())
        }
        val layout = WindowManager.LayoutParams(
            sizePx, sizePx, host.type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        updateWorld()
        layout.x = brain.x.roundToInt()
        layout.y = brain.y.roundToInt()
        try {
            host.windowManager.addView(pet, layout)
        } catch (e: Exception) {
            return
        }
        view = pet
        params = layout
        listenToScreen()
        lastTick = SystemClock.uptimeMillis()
        render()
        main.postDelayed(tick, FRAME_MS)
    }

    fun stop() {
        main.removeCallbacks(tick)
        unlistenToScreen()
        view?.let { runCatching { target?.windowManager?.removeView(it) } }
        view = null
        params = null
    }

    /** The accessibility service connected or went away: the pet moves to the other window layer. */
    fun hostChanged() {
        if (view == null) return
        stop()
        start()
    }

    /** The screen turned or changed size: the pet finds its feet again. */
    fun refreshWorld() {
        if (view == null) return
        updateWorld()
        render()
    }

    /** A capsule is about to show (a notification, a call): the pet perks up. */
    fun react(event: CapsuleEvent) {
        if (view == null) return
        if (event.kind == Kind.MESSAGE || event.kind == Kind.CALL) brain.cheer()
    }

    // --- the world ---

    private fun updateWorld() {
        val loaded = pack ?: return
        val windows = context.getSystemService(WindowManager::class.java) ?: return
        val width: Int
        val height: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windows.maximumWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            windows.defaultDisplay.getRealSize(size)
            width = size.x
            height = size.y
        }
        // It stands on the navigation bar's top edge, and hangs from the bottom of the status bar.
        brain.setWorld(width, height - systemBar("navigation_bar_height"), systemBar("status_bar_height"), sizePx, loaded.footShare)
    }

    private fun systemBar(name: String): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    // --- drawing ---

    private fun render() {
        val pet = view ?: return
        val layout = params ?: return
        val loaded = pack ?: return
        val pose = brain.pose()
        val frames = loaded.frames(pose.animation)
        val frame = (brain.clock * loaded.fps(pose.animation)).toInt() % frames
        if (pet.show(loaded.frame(pose.animation, frame), pose.rotation, pose.mirror)) pet.invalidate()
        val x = brain.x.roundToInt()
        val y = brain.y.roundToInt()
        if (x != layout.x || y != layout.y) {
            layout.x = x
            layout.y = y
            runCatching { target?.windowManager?.updateViewLayout(pet, layout) }
        }
    }

    // --- touch: a tap pats it, dragging picks it up ---

    private fun touch(): View.OnTouchListener {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var dragging = false
        return View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop) {
                        dragging = true
                        brain.grab()
                    }
                    if (dragging) {
                        brain.dragTo(e.rawX, e.rawY)
                        render()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) brain.release() else if (e.actionMasked == MotionEvent.ACTION_UP) brain.cheer()
                    dragging = false
                }
            }
            true
        }
    }

    // --- sleeping with the screen ---

    private fun listenToScreen() {
        val listener = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    main.removeCallbacks(tick)
                } else if (view != null) {
                    lastTick = SystemClock.uptimeMillis()
                    main.removeCallbacks(tick)
                    main.postDelayed(tick, FRAME_MS)
                }
            }
        }
        receiver = listener
        ContextCompat.registerReceiver(
            context, listener,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unlistenToScreen() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private fun dp(v: Int) = (v * density).roundToInt()

    private companion object {
        const val FRAME_MS = 33L
        const val MS_PER_SECOND = 1000f
        const val MAX_STEP_SECONDS = 0.1f
    }
}

/** Draws one picture of the pet, nearest-neighbour so the pixels stay sharp, mirrored and turned as asked. */
private class PetView(context: Context) : View(context) {

    private var bitmap: Bitmap? = null
    private var turn = 0f
    private var mirror = false
    private val paint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }
    private val bounds = RectF()

    /** Sets what to draw; true when it differs from what was drawn before. */
    fun show(picture: Bitmap, degrees: Float, mirrored: Boolean): Boolean {
        val changed = picture !== bitmap || degrees != turn || mirrored != mirror
        bitmap = picture
        turn = degrees
        mirror = mirrored
        return changed
    }

    override fun onDraw(canvas: Canvas) {
        val picture = bitmap ?: return
        val s = width.toFloat()
        canvas.save()
        canvas.translate(s / 2, s / 2)
        canvas.rotate(turn)
        if (mirror) canvas.scale(-1f, 1f)
        bounds.set(-s / 2, -s / 2, s / 2, s / 2)
        canvas.drawBitmap(picture, null, bounds, paint)
        canvas.restore()
    }
}
