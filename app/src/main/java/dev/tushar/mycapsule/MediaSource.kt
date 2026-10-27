package dev.tushar.mycapsule

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Watches active media sessions (any player app). It emits a capsule when the track or play state
 * changes, contributes the player card to the expanded capsule, and runs the media gestures.
 * Reading sessions requires notification access, which the app already has through [CapsuleService].
 */
class MediaSource : CapsuleSource {

    private val main = Handler(Looper.getMainLooper())
    private val callbacks = HashMap<MediaController, MediaController.Callback>()
    private val lastShown = HashMap<MediaController, String>()

    private var manager: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var controllers: List<MediaController> = emptyList()

    override fun start(context: Context, emit: (CapsuleEvent) -> Unit) {
        val listenerComponent = ComponentName(context, CapsuleService::class.java)
        val m = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        manager = m

        val onSessions = MediaSessionManager.OnActiveSessionsChangedListener { list ->
            watch(context, list.orEmpty(), emit)
        }
        sessionsListener = onSessions
        runCatching {
            m.addOnActiveSessionsChangedListener(onSessions, listenerComponent, main)
            watch(context, m.getActiveSessions(listenerComponent), emit)
        }
    }

    override fun stop(context: Context) {
        sessionsListener?.let { runCatching { manager?.removeOnActiveSessionsChangedListener(it) } }
        callbacks.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        callbacks.clear()
        lastShown.clear()
        controllers = emptyList()
        sessionsListener = null
    }

    override fun card(context: Context): CapsuleEvent? = active()?.let { eventFor(context, it) }

    fun playPause() {
        val controller = active() ?: return
        val controls = controller.transportControls
        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
    }

    fun skip(next: Boolean) {
        val controls = active()?.transportControls ?: return
        if (next) controls.skipToNext() else controls.skipToPrevious()
    }

    /** The player that is playing, else any session that has a track loaded. */
    private fun active() = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        ?: controllers.firstOrNull { it.metadata != null }

    /** Replaces callbacks on the current set of sessions; called whenever the set changes. */
    private fun watch(context: Context, list: List<MediaController>, emit: (CapsuleEvent) -> Unit) {
        callbacks.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        callbacks.clear()
        controllers = list
        lastShown.keys.retainAll(list.toSet())

        list.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = emitFor(context, controller, emit)
                override fun onMetadataChanged(metadata: MediaMetadata?) = emitFor(context, controller, emit)
            }
            controller.registerCallback(callback, main)
            callbacks[controller] = callback
        }
    }

    private fun emitFor(context: Context, controller: MediaController, emit: (CapsuleEvent) -> Unit) {
        val state = controller.playbackState?.state
        if (state != PlaybackState.STATE_PLAYING && state != PlaybackState.STATE_PAUSED) return

        val event = eventFor(context, controller) ?: return
        val signature = "${event.title}|${event.text}|$state"
        if (lastShown.put(controller, signature) == signature) return
        emit(event)
    }

    private fun eventFor(context: Context, controller: MediaController): CapsuleEvent? {
        val meta = controller.metadata ?: return null
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING

        val art = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        ).firstNotNullOfOrNull { meta.getBitmap(it) }
        val icon = art?.let { BitmapDrawable(context.resources, it) }
            ?: runCatching { context.packageManager.getApplicationIcon(controller.packageName) }.getOrNull()

        val controls = controller.transportControls
        fun action(iconRes: Int, description: String, run: () -> Unit) =
            CapsuleAction(context.getDrawable(iconRes), description, run = run)

        val previous = action(R.drawable.ic_skip_previous, "Previous") { controls.skipToPrevious() }
        val next = action(R.drawable.ic_skip_next, "Next") { controls.skipToNext() }
        val toggle = if (playing) {
            action(R.drawable.ic_pause, "Pause") { controls.pause() }
        } else {
            action(R.drawable.ic_play_arrow, "Play") { controls.play() }
        }

        return CapsuleEvent(
            kind = Kind.MEDIA,
            title = title,
            text = artist,
            icon = icon,
            onTap = controller.sessionActivity,
            actions = listOf(toggle),
            expandedActions = listOf(previous, toggle, next),
            accent = art?.let(CoverColor::accent),
            playback = { playbackOf(controller) },
            live = playing,
        )
    }

    /** The track's position now and its length, or null when the player doesn't report a length (e.g. a live stream). */
    private fun playbackOf(controller: MediaController): Playback? {
        val state = controller.playbackState ?: return null
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (duration <= 0L) return null

        val playing = state.state == PlaybackState.STATE_PLAYING
        // The player reports its position at one moment; while playing, add the time that has passed since.
        val sinceUpdate = if (playing) ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong() else 0L
        val seekable = state.actions and PlaybackState.ACTION_SEEK_TO != 0L
        return Playback(
            positionMs = (state.position + sinceUpdate).coerceIn(0L, duration),
            durationMs = duration,
            playing = playing,
            seekTo = if (seekable) { ms -> controller.transportControls.seekTo(ms) } else null,
        )
    }
}
