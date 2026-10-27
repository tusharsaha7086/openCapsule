package dev.tushar.mycapsule

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * The media volume. It shows as a brief capsule when the volume changes while music is playing (off by default), and
 * as a card in the deck while music plays, with a bar and buttons to turn it down and up.
 */
class VolumeSource : ReceiverSource(listOf(ACTION_VOLUME_CHANGED), exported = false) {

    private var audio: AudioManager? = null
    private var last = -1

    override fun start(context: Context, emit: (CapsuleEvent) -> Unit) {
        super.start(context, emit)
        audio = context.getSystemService(AudioManager::class.java)
        last = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
    }

    override fun onBroadcast(context: Context, intent: Intent) {
        val manager = audio ?: return
        if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
        val level = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        // The system also announces repeats; only a real change counts, and only while music is what is playing.
        if (level == last) return
        last = level
        if (manager.isMusicActive) emit(event(context, manager))
    }

    override fun card(context: Context): CapsuleEvent? =
        audio?.takeIf { it.isMusicActive }?.let { event(context, it) }

    private fun event(context: Context, manager: AudioManager): CapsuleEvent {
        val level = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        fun step(icon: Int, description: String, direction: Int) =
            CapsuleAction(context.getDrawable(icon), description) {
                manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
            }

        return CapsuleEvent(
            kind = Kind.VOLUME,
            title = "Volume",
            text = "${level * PERCENT / max}%",
            icon = context.getDrawable(if (level == 0) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
            actions = listOf(
                step(R.drawable.ic_volume_down, "Volume down", AudioManager.ADJUST_LOWER),
                step(R.drawable.ic_volume_up, "Volume up", AudioManager.ADJUST_RAISE),
            ),
            progress = level.toFloat() / max,
            live = true,
        )
    }

    private companion object {
        // Not in the SDK's public constants, but sent by the audio system to every app that listens.
        const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val PERCENT = 100
    }
}

/**
 * A stopwatch shown as a persistent, ambient capsule: it counts up every second, stays until dismissed, yields to other
 * capsules, and offers pause/resume and reset. Dismissing it (tap or swipe up) stops it. It is started with a broadcast
 * ([startIntent]), like the timer, so the app, a widget or `adb` can all start one the same way; starting it again
 * begins from zero.
 */
class StopwatchSource : ReceiverSource(listOf(ACTION_START), exported = true) {

    private enum class Phase { IDLE, RUNNING, PAUSED }

    private val main = Handler(Looper.getMainLooper())
    private val tick = Runnable { onTick() }

    private var context: Context? = null
    private var phase = Phase.IDLE

    /** Time counted up to the last start or resume; while running, the time since then is added to it. */
    private var countedMs = 0L
    private var resumedAt = 0L

    override fun start(context: Context, emit: (CapsuleEvent) -> Unit) {
        super.start(context, emit)
        this.context = context
    }

    override fun stop(context: Context) {
        super.stop(context)
        main.removeCallbacks(tick)
        phase = Phase.IDLE
    }

    override fun card(context: Context): CapsuleEvent? = if (phase == Phase.IDLE) null else event()

    override fun onBroadcast(context: Context, intent: Intent) = begin()

    private fun begin() {
        countedMs = 0L
        resumedAt = now()
        phase = Phase.RUNNING
        scheduleTick()
        emit(event())
    }

    private fun pause() {
        if (phase != Phase.RUNNING) return
        countedMs = elapsed()
        phase = Phase.PAUSED
        main.removeCallbacks(tick)
        emit(event())
    }

    private fun resume() {
        if (phase != Phase.PAUSED) return
        resumedAt = now()
        phase = Phase.RUNNING
        scheduleTick()
        emit(event())
    }

    private fun reset() {
        if (phase == Phase.IDLE) return
        countedMs = 0L
        resumedAt = now()
        if (phase == Phase.RUNNING) scheduleTick()
        emit(event())
    }

    private fun onTick() {
        emit(event())
        scheduleTick()
    }

    /** Ticks on the whole second, so the digits change when the time does and not a little after. */
    private fun scheduleTick() {
        main.removeCallbacks(tick)
        main.postDelayed(tick, MS_PER_SECOND - elapsed() % MS_PER_SECOND)
    }

    private fun elapsed() = if (phase == Phase.RUNNING) countedMs + (now() - resumedAt) else countedMs

    private fun event(): CapsuleEvent {
        val ctx = requireNotNull(context)
        val toggle = if (phase == Phase.PAUSED) {
            CapsuleAction(ctx.getDrawable(R.drawable.ic_play_arrow), "Resume") { resume() }
        } else {
            CapsuleAction(ctx.getDrawable(R.drawable.ic_pause), "Pause") { pause() }
        }
        return CapsuleEvent(
            kind = Kind.STOPWATCH,
            title = format(elapsed()),
            text = if (phase == Phase.PAUSED) "Paused" else "Stopwatch",
            icon = ctx.getDrawable(R.drawable.ic_schedule),
            actions = listOf(toggle, CapsuleAction(null, "Reset to zero", label = "Reset") { reset() }),
            persistent = true,
            ambient = true,
            live = true,
            onDismiss = {
                main.removeCallbacks(tick)
                phase = Phase.IDLE
            },
        )
    }

    private fun format(ms: Long): String {
        val total = ms / MS_PER_SECOND
        val h = total / SECONDS_PER_HOUR
        val m = total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val s = total % SECONDS_PER_MINUTE
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun now() = SystemClock.elapsedRealtime()

    companion object {
        const val ACTION_START = "dev.tushar.mycapsule.START_STOPWATCH"

        private const val MS_PER_SECOND = 1_000L
        private const val SECONDS_PER_MINUTE = 60
        private const val SECONDS_PER_HOUR = 3_600

        fun startIntent(context: Context) = Intent(ACTION_START).setPackage(context.packageName)
    }
}
