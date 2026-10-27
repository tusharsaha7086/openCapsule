package dev.tushar.mycapsule

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.ceil

/**
 * A countdown timer shown as a persistent, ambient capsule: it stays until dismissed, updates every
 * second, yields to other capsules, and offers pause/resume and +1 min. A timer is started with a
 * broadcast ([startIntent]), so the app, a widget or `adb` can all start one the same way.
 */
class TimerSource : ReceiverSource(listOf(ACTION_START), exported = true) {

    private enum class Phase { IDLE, RUNNING, PAUSED, FINISHED }

    private val main = Handler(Looper.getMainLooper())
    private val tick = Runnable { onTick() }

    private var context: Context? = null

    private var phase = Phase.IDLE
    private var totalMs = 0L
    private var remainingMs = 0L
    private var endAt = 0L
    private var hidden = false

    override fun start(context: Context, emit: (CapsuleEvent) -> Unit) {
        super.start(context, emit)
        this.context = context
    }

    override fun stop(context: Context) {
        super.stop(context)
        main.removeCallbacks(tick)
        phase = Phase.IDLE
    }

    /** The running timer, or while none runs a section that starts one: the clock card always shows the timer. */
    override fun card(context: Context): CapsuleEvent = if (phase == Phase.IDLE) idleCard(context) else event()

    private fun idleCard(context: Context) = CapsuleEvent(
        kind = Kind.TIMER,
        title = "Timer",
        text = "Start a countdown",
        icon = context.getDrawable(R.drawable.ic_timer),
        actions = QUICK_MINUTES.map { minutes ->
            CapsuleAction(null, "Start a $minutes minute timer", label = "$minutes min") { begin(minutes * MS_PER_MINUTE) }
        },
    )

    override fun onBroadcast(context: Context, intent: Intent) {
        val seconds = intent.getIntExtra(EXTRA_SECONDS, 0)
        if (seconds > 0) begin(seconds * MS_PER_SECOND)
    }

    private fun begin(durationMs: Long) {
        totalMs = durationMs
        remainingMs = durationMs
        endAt = now() + durationMs
        phase = Phase.RUNNING
        hidden = false
        scheduleTick()
        publish()
    }

    private fun pause() {
        if (phase != Phase.RUNNING) return
        remainingMs = (endAt - now()).coerceAtLeast(0)
        phase = Phase.PAUSED
        main.removeCallbacks(tick)
        publish()
    }

    private fun resume() {
        if (phase != Phase.PAUSED) return
        endAt = now() + remainingMs
        phase = Phase.RUNNING
        scheduleTick()
        publish()
    }

    private fun addMinute() {
        when (phase) {
            Phase.RUNNING -> { endAt += MS_PER_MINUTE; totalMs += MS_PER_MINUTE }
            Phase.PAUSED -> { remainingMs += MS_PER_MINUTE; totalMs += MS_PER_MINUTE }
            Phase.FINISHED -> { begin(MS_PER_MINUTE); return }
            Phase.IDLE -> return
        }
        publish()
    }

    private fun onTick() {
        remainingMs = (endAt - now()).coerceAtLeast(0)
        if (remainingMs == 0L) finish() else {
            publish()
            scheduleTick()
        }
    }

    private fun finish() {
        phase = Phase.FINISHED
        hidden = false
        context?.getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
        publish()
    }

    private fun scheduleTick() {
        main.removeCallbacks(tick)
        main.postDelayed(tick, MS_PER_SECOND)
    }

    /** A dismissed running timer keeps counting silently; the finished alert always shows. */
    private fun publish() {
        if (hidden && phase != Phase.FINISHED) return
        emit(event())
    }

    private fun event(): CapsuleEvent {
        val ctx = requireNotNull(context)
        val finished = phase == Phase.FINISHED
        val plusMinute = CapsuleAction(null, "Add one minute", label = "+1 min") { addMinute() }
        val toggle = if (phase == Phase.PAUSED) {
            CapsuleAction(ctx.getDrawable(R.drawable.ic_play_arrow), "Resume") { resume() }
        } else {
            CapsuleAction(ctx.getDrawable(R.drawable.ic_pause), "Pause") { pause() }
        }
        return CapsuleEvent(
            kind = Kind.TIMER,
            title = if (finished) "Time's up" else format(remainingMs),
            text = when (phase) {
                Phase.PAUSED -> "Paused"
                Phase.FINISHED -> "Tap to dismiss"
                else -> "Timer"
            },
            icon = ctx.getDrawable(R.drawable.ic_timer),
            actions = if (finished) listOf(plusMinute) else listOf(toggle, plusMinute),
            progress = if (finished) null else remainingMs.toFloat() / totalMs,
            persistent = true,
            ambient = !finished,
            live = true,
            onDismiss = { if (finished) phase = Phase.IDLE else hidden = true },
        )
    }

    private fun format(ms: Long): String {
        val total = ceil(ms / MS_PER_SECOND.toDouble()).toLong()
        val h = total / SECONDS_PER_HOUR
        val m = total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val s = total % SECONDS_PER_MINUTE
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun now() = android.os.SystemClock.elapsedRealtime()

    companion object {
        const val ACTION_START = "dev.tushar.mycapsule.START_TIMER"
        const val EXTRA_SECONDS = "seconds"

        /** The lengths offered on the clock card while no timer runs. */
        private val QUICK_MINUTES = listOf(1, 5, 10)

        private const val MS_PER_SECOND = 1_000L
        private const val MS_PER_MINUTE = 60_000L
        private const val SECONDS_PER_MINUTE = 60
        private const val SECONDS_PER_HOUR = 3_600

        fun startIntent(context: Context, seconds: Int) =
            Intent(ACTION_START).setPackage(context.packageName).putExtra(EXTRA_SECONDS, seconds)
    }
}
