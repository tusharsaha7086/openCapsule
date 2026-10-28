package dev.tushar.mycapsule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How big the screen pet is. */
enum class PetSize(val label: String, val dp: Int) {
    SMALL("Small", 40),
    MEDIUM("Medium", 56),
    LARGE("Large", 80),
}

/** What the phone is doing that the pet takes notice of. */
class PetEnvironment(
    val music: Boolean = false,
    val focus: Boolean = false,
    val lowBattery: Boolean = false,
    val charging: Boolean = false,
)

/**
 * The screen pet: a small window that holds the pet's picture and is moved around the screen by [PetBrain]. Only the pet's own
 * square takes touches, so everything around it still reaches the app underneath. It uses the same window layer as the
 * capsule (above the status bar with the accessibility service, a normal overlay otherwise), runs at about 30 frames a second
 * and stops completely while the screen is off.
 *
 * What it does with you: a tap pats it, holding it still makes it purr (with soft taps on your finger), dragging picks it up,
 * a quick flick throws it, and shaking the phone leaves it dizzy. What it does with the phone: [moods] says what is going on
 * (music, a focus session, the battery) and [ledge] says where the open capsule or deck is, so the pet can land on its top edge.
 */
class PetOverlay(
    private val context: Context,
    private val prefs: Prefs,
    private val moods: () -> PetEnvironment = { PetEnvironment() },
    private val ledge: () -> Rect? = { null },
) {

    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val brain = PetBrain()

    private var pack: PetPack? = null
    private var view: PetView? = null
    private var target: OverlayHost.Target? = null
    private var params: WindowManager.LayoutParams? = null
    private var sizePx = 0
    private var lastTick = 0L
    private var frame = 0
    private var lastTimerAlert = 0L
    private var receiver: BroadcastReceiver? = null

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = ((now - lastTick) / MS_PER_SECOND).coerceIn(0f, MAX_STEP_SECONDS)
            lastTick = now
            if (frame++ % MOOD_EVERY_FRAMES == 0) takeNoticeOfPhone()
            watchLedge()
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
        listenForShakes()
        lastTick = SystemClock.uptimeMillis()
        frame = 0
        render()
        main.postDelayed(tick, FRAME_MS)
    }

    fun stop() {
        main.removeCallbacks(tick)
        unlistenToScreen()
        stopListeningForShakes()
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

    /** Whether the screen point ([rawX], [rawY]) is on the pet, with a little room round it. */
    fun isAt(rawX: Float, rawY: Float): Boolean {
        val at = params ?: return false
        val room = dp(TOUCH_ROOM_DP)
        return rawX >= at.x - room && rawX <= at.x + sizePx + room && rawY >= at.y - room && rawY <= at.y + sizePx + room
    }

    /** A capsule is about to show: a notification or a call makes the pet sit up and take notice. */
    fun react(event: CapsuleEvent) {
        if (view == null || !prefs.petReacts) return
        when (event.kind) {
            Kind.MESSAGE -> brain.alert(MESSAGE_ALERT_SECONDS)
            Kind.CALL -> brain.alert(CALL_ALERT_SECONDS)
            else -> Unit
        }
    }

    /** An event that is not shown as a capsule but still counts: a timer that has run out, a focus session's part ending. */
    fun observe(event: CapsuleEvent) {
        if (view == null || !prefs.petReacts) return
        val timerDone = event.kind == Kind.TIMER && event.progress == null && event.persistent
        val partEnded = event.kind == Kind.POMODORO
        if (!timerDone && !partEnded) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTimerAlert < ALERT_COOLDOWN_MS) return
        lastTimerAlert = now
        brain.alert(TIMER_ALERT_SECONDS)
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

    /** Once a second: music, a focus session, the battery. With reactions off the pet is just a pet. */
    private fun takeNoticeOfPhone() {
        if (!prefs.petReacts) {
            brain.setMood(music = false, focus = false, lowBattery = false, charging = false)
            return
        }
        val phone = moods()
        brain.setMood(phone.music, phone.focus, phone.lowBattery, phone.charging)
    }

    /** Where the open capsule or deck is, for the pet to land on. */
    private fun watchLedge() {
        val box = ledge()
        if (box != null && box.width() > 0) {
            brain.setPlatform(box.left.toFloat(), box.top.toFloat(), box.right.toFloat())
        } else {
            brain.clearPlatform()
        }
    }

    // --- drawing ---

    private fun render() {
        val pet = view ?: return
        val layout = params ?: return
        val loaded = pack ?: return
        val pose = brain.pose()
        val frames = loaded.frames(pose.animation)
        val index = (brain.clock * loaded.fps(pose.animation)).toInt() % frames
        if (pet.show(loaded.frame(pose.animation, index), pose.rotation, pose.mirror)) pet.invalidate()
        val x = brain.x.roundToInt()
        val y = brain.y.roundToInt()
        if (x != layout.x || y != layout.y) {
            layout.x = x
            layout.y = y
            runCatching { target?.windowManager?.updateViewLayout(pet, layout) }
        }
    }

    // --- touch: a tap pats it, holding purrs, dragging picks it up, a flick throws it ---

    private fun touch(): View.OnTouchListener {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var dragging = false
        var purring = false
        var tracker: VelocityTracker? = null

        val pulse = object : Runnable {
            override fun run() {
                if (!purring) return
                if (prefs.haptics) view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                main.postDelayed(this, PURR_PULSE_MS)
            }
        }
        val startPurr = Runnable {
            purring = true
            brain.purr(true)
            pulse.run()
        }

        fun track(e: MotionEvent) {
            // In screen coordinates: the pet moves under the finger, so the finger's place on the pet would not change.
            val screen = MotionEvent.obtain(e)
            screen.setLocation(e.rawX, e.rawY)
            tracker?.addMovement(screen)
            screen.recycle()
        }

        fun finish(release: Boolean, tap: Boolean) {
            main.removeCallbacks(startPurr)
            main.removeCallbacks(pulse)
            if (dragging) {
                val t = tracker
                t?.computeCurrentVelocity(VELOCITY_UNITS)
                if (release) brain.release(t?.xVelocity ?: 0f, t?.yVelocity ?: 0f)
            } else if (purring) {
                brain.purr(false)
            } else if (tap) {
                brain.cheer()
            }
            purring = false
            dragging = false
            tracker?.recycle()
            tracker = null
        }

        return View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    purring = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain()
                    track(e)
                    main.postDelayed(startPurr, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    track(e)
                    if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop) {
                        main.removeCallbacks(startPurr)
                        main.removeCallbacks(pulse)
                        if (purring) brain.purr(false)
                        purring = false
                        dragging = true
                        brain.grab()
                    }
                    if (dragging) {
                        brain.dragTo(e.rawX, e.rawY)
                        render()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    track(e)
                    finish(release = true, tap = true)
                }
                MotionEvent.ACTION_CANCEL -> finish(release = true, tap = false)
            }
            true
        }
    }

    // --- shaking the phone ---

    private var sensors: SensorManager? = null
    private var shakeCount = 0
    private var lastShake = 0L
    private var shakeCooldownUntil = 0L

    private val shakes = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!prefs.petReacts) return
            val pull = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
            if (abs(pull - SensorManager.GRAVITY_EARTH) < SHAKE_THRESHOLD) return
            val now = SystemClock.uptimeMillis()
            if (now - lastShake > SHAKE_WINDOW_MS) shakeCount = 0
            lastShake = now
            shakeCount++
            if (shakeCount >= SHAKES_NEEDED && now >= shakeCooldownUntil) {
                shakeCount = 0
                shakeCooldownUntil = now + SHAKE_COOLDOWN_MS
                brain.dizzy()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun listenForShakes() {
        val manager = context.getSystemService(SensorManager::class.java) ?: return
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensors = manager
        manager.registerListener(shakes, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopListeningForShakes() {
        sensors?.unregisterListener(shakes)
        sensors = null
    }

    // --- sleeping with the screen ---

    private fun listenToScreen() {
        val listener = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    main.removeCallbacks(tick)
                    stopListeningForShakes()
                } else if (view != null) {
                    lastTick = SystemClock.uptimeMillis()
                    main.removeCallbacks(tick)
                    main.postDelayed(tick, FRAME_MS)
                    listenForShakes()
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
        const val MOOD_EVERY_FRAMES = 30
        const val TOUCH_ROOM_DP = 12
        const val VELOCITY_UNITS = 1000
        const val PURR_PULSE_MS = 260L
        const val MESSAGE_ALERT_SECONDS = 1.8f
        const val CALL_ALERT_SECONDS = 4f
        const val TIMER_ALERT_SECONDS = 3f
        const val ALERT_COOLDOWN_MS = 4000L
        const val SHAKE_THRESHOLD = 14f
        const val SHAKES_NEEDED = 3
        const val SHAKE_WINDOW_MS = 700L
        const val SHAKE_COOLDOWN_MS = 5000L
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
