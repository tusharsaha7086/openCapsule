package dev.tushar.mycapsule

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.ceil

/**
 * A focus session in the deck: 25 minutes of work, a 5 minute break, and a 15 minute break after every fourth round. A
 * phase that ends buzzes, shows a brief capsule and starts the next one by itself. The card always exists: it offers
 * Start while no session runs, and Pause, Skip and Reset while one does.
 */
class PomodoroSource : CapsuleSource {

    private enum class Phase(val label: String, val minutes: Int) {
        FOCUS("Focus", FOCUS_MINUTES),
        SHORT_BREAK("Short break", SHORT_BREAK_MINUTES),
        LONG_BREAK("Long break", LONG_BREAK_MINUTES),
    }

    private val main = Handler(Looper.getMainLooper())
    private val phaseEnd = Runnable { advance(announce = true) }

    private var context: Context? = null
    private var emit: (CapsuleEvent) -> Unit = {}

    private var active = false
    private var running = false
    private var phase = Phase.FOCUS
    private var round = 1
    private var endAt = 0L
    private var remainingMs = 0L

    override fun start(context: Context, emit: (CapsuleEvent) -> Unit) {
        this.context = context
        this.emit = emit
    }

    override fun stop(context: Context) {
        main.removeCallbacks(phaseEnd)
        active = false
        running = false
    }

    override fun card(context: Context): CapsuleEvent {
        val left = if (!active) Phase.FOCUS.minutes * MS_PER_MINUTE else if (running) (endAt - now()).coerceAtLeast(0) else remainingMs
        val actions = when {
            !active -> listOf(CapsuleAction(context.getDrawable(R.drawable.ic_play_arrow), "Start a focus session") { begin() })
            else -> listOf(
                if (running) {
                    CapsuleAction(context.getDrawable(R.drawable.ic_pause), "Pause") { pause() }
                } else {
                    CapsuleAction(context.getDrawable(R.drawable.ic_play_arrow), "Resume") { resume() }
                },
                CapsuleAction(context.getDrawable(R.drawable.ic_skip_next), "Skip to the next part") { advance(announce = false) },
                CapsuleAction(context.getDrawable(R.drawable.ic_restart_alt), "Stop the session") { reset() },
            )
        }
        return CapsuleEvent(
            kind = Kind.POMODORO,
            title = format(left),
            text = when {
                !active -> "Ready to focus"
                else -> "${phase.label}  ·  round $round of $ROUNDS" + if (running) "" else "  ·  Paused"
            },
            icon = context.getDrawable(R.drawable.ic_timer),
            progress = if (active) 1f - left.toFloat() / (phase.minutes * MS_PER_MINUTE) else null,
            actions = actions,
            live = active && running,
            refreshMs = if (running) REFRESH_MS else null,
        )
    }

    private fun begin() {
        active = true
        round = 1
        startPhase(Phase.FOCUS)
    }

    private fun startPhase(next: Phase) {
        phase = next
        remainingMs = next.minutes * MS_PER_MINUTE
        resume()
    }

    private fun pause() {
        if (!running) return
        remainingMs = (endAt - now()).coerceAtLeast(0)
        running = false
        main.removeCallbacks(phaseEnd)
    }

    private fun resume() {
        endAt = now() + remainingMs
        running = true
        main.removeCallbacks(phaseEnd)
        main.postDelayed(phaseEnd, remainingMs)
    }

    private fun reset() {
        main.removeCallbacks(phaseEnd)
        active = false
        running = false
    }

    /** Moves to the next part: a break after focus, focus after a break. A part that ran out tells the user; a skipped one does not. */
    private fun advance(announce: Boolean) {
        if (!active) return
        val next = when (phase) {
            Phase.FOCUS -> if (round % ROUNDS == 0) Phase.LONG_BREAK else Phase.SHORT_BREAK
            Phase.SHORT_BREAK -> Phase.FOCUS.also { round++ }
            Phase.LONG_BREAK -> Phase.FOCUS.also { round = 1 }
        }
        startPhase(next)
        if (announce) announce(next)
    }

    private fun announce(next: Phase) {
        val ctx = context ?: return
        ctx.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
        emit(
            CapsuleEvent(
                kind = Kind.POMODORO,
                title = if (next == Phase.FOCUS) "Back to focus" else next.label,
                text = "${next.minutes} min  ·  round $round of $ROUNDS",
                icon = ctx.getDrawable(R.drawable.ic_timer),
            ),
        )
    }

    private fun format(ms: Long): String {
        val total = ceil(ms / MS_PER_SECOND.toDouble()).toLong()
        return "%02d:%02d".format(total / SECONDS_PER_MINUTE, total % SECONDS_PER_MINUTE)
    }

    private fun now() = SystemClock.elapsedRealtime()

    private companion object {
        const val FOCUS_MINUTES = 25
        const val SHORT_BREAK_MINUTES = 5
        const val LONG_BREAK_MINUTES = 15
        const val ROUNDS = 4
        const val MS_PER_SECOND = 1_000L
        const val MS_PER_MINUTE = 60_000L
        const val SECONDS_PER_MINUTE = 60
        const val REFRESH_MS = 1_000L
    }
}
