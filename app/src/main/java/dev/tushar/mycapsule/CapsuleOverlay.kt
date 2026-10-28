package dev.tushar.mycapsule

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import kotlin.math.abs

/**
 * The only class that draws capsules. It moves between three states:
 *
 *  - **Idle:** only the tiny [IdlePill] over the camera (when [withIdlePill] is on).
 *  - **Event:** a temporary capsule for one [CapsuleEvent]. A new event replaces it unless the current one
 *    has higher priority; long-press expands an event that has [CapsuleEvent.expandedActions].
 *  - **Deck:** opened by tapping the idle pill. Shows the cards from [deckProvider]; swipe sideways to
 *    switch, swipe up or tap to collapse. Only calls interrupt it.
 *
 * Every state change is a morph ([Morph]): a capsule flows out of the pill into its full shape, flows into a
 * different size when its content changes, and flows back into the pill when it closes.
 */
class CapsuleOverlay(
    private val context: Context,
    private val prefs: Prefs,
    private val withIdlePill: Boolean = false,
    private val deckProvider: () -> List<CapsuleEvent> = { emptyList() },
    private val onGesture: (GestureAction) -> Unit = {},
) {
    /**
     * How a freshly drawn capsule arrives:
     *  - [GROW]: out of the idle pill.
     *  - [MORPH]: from the size of the capsule it replaces, with its new content fading in.
     *  - [SLIDE_FROM_RIGHT] / [SLIDE_FROM_LEFT]: like [MORPH], and sliding in from that side (switching deck cards).
     *  - [UPDATE]: the same capsule with fresh data; only a real change of size is animated, and the content stays.
     */
    private enum class Anim { GROW, MORPH, SLIDE_FROM_RIGHT, SLIDE_FROM_LEFT, UPDATE }

    private class Size(val width: Int, val height: Int)

    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    /** What the capsule is made of in the current app style; null keeps the plain flat capsule. */
    private val look: CapsuleLook? get() = CapsuleLooks.of(prefs.uiStyle, density)

    /** How the capsule moves right now, from the animation settings. Read afresh for every movement. */
    private val motion: Motion get() = Motions.of(prefs)

    /** Whether the capsule being built is light, so its text and icons must be dark. Set at the start of building one. */
    private var onLight = false

    /** Where the capsule sits on the screen it is on right now (portrait, landscape, large screen). */
    private val position: Position get() = prefs.position(ScreenProfile.of(context))

    /**
     * The banners that stay until swiped away (system events and the torch), oldest first. The first is shown full size in
     * the centre; the others wait at the sides as small icons: the second on the right, the third on the left, the fourth
     * on the right again, and so on. It is kept even while another capsule has the screen, and comes back when it is gone.
     */
    private val tray = mutableListOf<CapsuleEvent>()

    /** What makes two banners the same thing: the same kind of change (Wi-Fi coming and going, say) replaces the older one. */
    private fun trayKey(e: CapsuleEvent): Any = e.system?.group ?: e.kind

    private fun inTray(e: CapsuleEvent) = e.persistent && (e.kind == Kind.SYSTEM || e.kind == Kind.FLASHLIGHT)

    /** Keeps the stack up to date with [event]: a banner that stays is added or replaces its older self; one that does not removes it. */
    private fun trayUpdate(event: CapsuleEvent) {
        val i = tray.indexOfFirst { trayKey(it) == trayKey(event) }
        if (inTray(event)) {
            if (i >= 0) tray[i] = event else tray += event
            // Too many to show: the oldest one waiting at a side is dropped.
            if (tray.size > MAX_TRAY) tray.removeAt(1)
        } else if (i >= 0) {
            tray.removeAt(i)
        }
    }

    /** Whether [event] is the banner in the centre of a stack that has more than one banner in it. */
    private fun shownWithSides(event: CapsuleEvent) =
        !deckOpen && tray.size > 1 && inTray(event) && trayKey(tray.first()) == trayKey(event)

    /** Whether the capsule being built is a chat one with buttons, which sit under the text instead of beside it. */
    private var stacked = false

    /** The text colour chosen in settings, or null while it is automatic. */
    private val ownDeck: Boolean get() = deckOpen && prefs.deckColors
    private val userInk: Int? get() = if (ownDeck) prefs.deckInk(context) else prefs.textInk()

    /**
     * A panel wash inside the deck: the text colour at [percent] opacity while the deck has colours of its own, so panels,
     * tiles and chips stay in the same colour family as the text and icons. Otherwise [plain], the fixed white or black wash.
     */
    private fun wash(percent: Int, plain: Int): Int =
        if (deckOpen) ((userInk ?: if (onLight) INK else WHITE) and 0x00FFFFFF) or (percent * 255 / 100 shl 24) else plain

    /** The corner radius of the capsule's body. The deck is rounder than a small capsule, unless the shape is set to square. */
    private fun shapeRadiusDp(): Int {
        val chosen = prefs.shape.radiusDp
        return if (deckOpen && chosen > Shape.SQUARE.radiusDp) maxOf(chosen, DECK_RADIUS_DP) else chosen
    }

    /** The deck's width: the same for every card, but never wider than a narrow phone's screen leaves room for. */
    private fun deckWidth() = minOf(dp(DECK_WIDTH_DP), context.resources.displayMetrics.widthPixels - 2 * dp(SIDE_MARGIN_DP + SHADOW_ROOM_DP))

    /** The colour of the capsule being built, for the icon on a filled button. */
    private var bodyColor = 0
    private fun ink(auto: Int) = userInk ?: auto
    private fun inkDim(auto: Int) = userInk?.let { (it and 0x00FFFFFF) or 0xBF000000.toInt() } ?: auto
    private fun inkTrack(auto: Int) = userInk?.let { (it and 0x00FFFFFF) or 0x44000000 } ?: auto
    private val hide = Runnable { collapse() }
    private val idle = IdlePill(context, prefs, onTap = { fire(Gesture.SINGLE_TAP) }, onGesture = ::fire)

    private var view: View? = null
    private var viewTarget: OverlayHost.Target? = null
    private var morph: Morph? = null

    /** The window's resting y, and how far above it the window is currently stretched to reach the pill. */
    private var viewBaseY = 0
    private var viewExtra = 0

    /** The window being replaced during a swap; it stays up until its replacement has drawn. */
    private var stale: View? = null
    private var current: CapsuleEvent? = null
    private var expanded = false

    private var deck: List<CapsuleEvent> = emptyList()
    private var deckIndex = 0
    private var deckOpen = false
    private var deckHeight = 0

    /** When the deck closes by itself. Fixed when it opens; only a press on the deck itself moves it. */
    private var deckCloseAt = 0L

    /** True while a finger is on the playback bar, so the ticker doesn't fight it. */
    private var seeking = false

    /** Moves the playback bar along while the deck shows a track. It reads the player afresh each time. */
    private val progressTick = object : Runnable {
        override fun run() {
            val v = view ?: return
            if (!deckOpen) return
            val playback = current?.playback?.invoke()
            if (playback != null && !seeking) {
                v.findViewWithTag<SeekBar>(TAG_SEEK)?.let {
                    it.max = playback.durationMs.toInt().coerceAtLeast(1)
                    it.progress = playback.positionMs.toInt()
                }
                v.findViewWithTag<TextView>(TAG_ELAPSED)?.text = formatTime(playback.positionMs)
                v.findViewWithTag<TextView>(TAG_TOTAL)?.text = formatTime(playback.durationMs)
            }
            handler.postDelayed(this, PROGRESS_TICK_MS)
        }
    }

    /** Shows the idle pill; call once the service is ready. */
    fun start() = showIdle()

    /** Restyles the idle pill after a setting changed; leaves a visible capsule alone. */
    fun refreshIdle() {
        if (view == null && withIdlePill) idle.refresh()
    }

    fun show(incoming: CapsuleEvent) {
        trayUpdate(incoming)
        // A banner that stays joins the stack, and the stack's first banner is the one in the centre.
        val event = if (inTray(incoming)) tray.first() else incoming
        if (deckOpen && event.kind != Kind.CALL) {
            refreshDeck(event.kind)
            return
        }
        val showing = current
        if (!deckOpen && showing != null && blocks(showing, event)) return

        // Same kind replacing itself (track change, progress tick) updates in place, without a fresh entrance.
        val sameKind = !deckOpen && showing?.kind == event.kind
        val keepExpanded = expanded && sameKind
        val previous = bodySize()
        resetState()
        expanded = keepExpanded
        render(
            event,
            when {
                sameKind -> Anim.UPDATE
                showing == null -> Anim.GROW
                else -> Anim.MORPH
            },
            previous,
        )
    }

    /** Removes the capsule at once and brings the idle pill back. */
    fun dismiss() {
        clear()
        // Banners that were waiting, because another capsule had the screen, come back now that it is gone.
        if (tray.isNotEmpty() && OverlayHost.canDraw(context)) {
            render(tray.first(), Anim.GROW, null)
        } else {
            showIdle()
        }
    }

    /** Flows the capsule back into the idle pill, then removes it. */
    fun collapse() {
        val v = view ?: return dismiss()
        val target = viewTarget
        handler.removeCallbacks(hide)
        if (deckOpen) haptic()
        if (morph == null || target == null) {
            deckOpen = false
            return dismiss()
        }

        // The capsule has to travel up to the pill, so its window is stretched up there first (a swap, so nothing jumps).
        val extra = extraTop(target, viewBaseY)
        if (extra == viewExtra) {
            deckOpen = false
            startExit(v)
        } else {
            swapWindow(extra) { startExit(it) }
            // Closed from this moment on, so an update that arrives during the swap or the shrink (a timer tick, say)
            // shows as a normal capsule instead of refreshing the deck back open. Set after the swap has been built,
            // which needs the deck layout.
            deckOpen = false
        }
    }

    private fun startExit(v: View) {
        val m = morph
        if (view !== v || m == null) return
        val pill = idle.bounds()
        val mo = motion
        if (mo.fade) {
            // Fades out where it is, instead of flowing back into the pill.
            val body = (v as ViewGroup).getChildAt(0)
            m.retarget(
                MorphShape(body.width.toFloat(), body.height.toFloat(), dp(shapeRadiusDp()).toFloat()),
                travelX = 0f,
                travelY = 0f,
                fadeBackground = true,
                startScale = mo.startScale,
            )
        } else {
            m.retarget(
                MorphShape(pill.width().toFloat(), pill.height().toFloat(), pill.height() / 2f),
                travelX = pill.exactCenterX() - centerX(v),
                travelY = -viewExtra.toFloat(),
            )
        }
        m.animateTo(0f, mo.exitMs, PathInterpolator(EXIT_X1, 0f, EXIT_X2, 1f)) {
            if (view !== v) return@animateTo
            // Removing a window draws its views once more without the animated clip, which flashed the full-size
            // capsule for a moment. Hiding it first and removing it on the next frame leaves nothing to draw.
            v.visibility = View.INVISIBLE
            v.postOnAnimation { if (view === v) dismiss() }
        }
    }

    /** Removes everything, including the idle pill; call when the service goes away. */
    fun destroy() {
        tray.clear()
        clear()
        idle.hide()
    }

    private fun showIdle() {
        if (withIdlePill && view == null) idle.show()
    }

    private fun clear() {
        removeView()
        resetState()
    }

    /**
     * Forgets what is showing without taking its window down. The next [render] puts the replacement up first and
     * removes the old window only after the new one has drawn, so there is never a moment with nothing on screen.
     */
    private fun resetState() {
        current = null
        expanded = false
        deckOpen = false
    }

    /** Whether [showing] should stay on screen and ignore [incoming]. */
    private fun blocks(showing: CapsuleEvent, incoming: CapsuleEvent) = when {
        showing.ambient -> false
        incoming.ambient && incoming.kind != showing.kind -> true
        else -> showing.kind.priority > incoming.kind.priority
    }

    private fun dismissByUser(event: CapsuleEvent) {
        event.onDismiss?.invoke()
        val i = tray.indexOfFirst { trayKey(it) == trayKey(event) }
        if (inTray(event) && i >= 0) {
            tray.removeAt(i)
            if (tray.isNotEmpty()) {
                // Others are waiting at the sides: the next one takes the centre.
                val previous = bodySize()
                resetState()
                render(tray.first(), Anim.MORPH, previous)
                return
            }
        }
        collapse()
    }

    /** A side icon was pressed: it takes the centre, and the banner that was there shrinks to the side it came from. */
    private fun promote(event: CapsuleEvent, fromRight: Boolean) {
        val i = tray.indexOfFirst { trayKey(it) == trayKey(event) }
        if (i <= 0) return
        haptic()
        java.util.Collections.swap(tray, 0, i)
        val previous = bodySize()
        resetState()
        render(tray.first(), if (fromRight) Anim.SLIDE_FROM_RIGHT else Anim.SLIDE_FROM_LEFT, previous)
    }

    /** A small round icon for a banner waiting at a side, in the capsule's own material. */
    private fun sideIcon(event: CapsuleEvent, fromRight: Boolean): View {
        val color = prefs.capsuleColor(context)
        val ink = userInk ?: if (isLightColor(color)) INK else WHITE
        val borderPx = dp(prefs.borderWidth.dp)
        return ImageView(context).apply {
            setImageDrawable(event.icon)
            setColorFilter(ink)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(SIDE_PAD_DP), dp(SIDE_PAD_DP), dp(SIDE_PAD_DP), dp(SIDE_PAD_DP))
            contentDescription = event.title
            background = look?.let { CapsuleLooks.body(it, color, dp(SIDE_ICON_DP).toFloat(), borderPx, prefs.borderColorValue()) }
                ?: GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(borderPx, prefs.borderColorValue())
                }
            elevation = dp(prefs.shadow.elevationDp).toFloat()
            setOnClickListener { promote(event, fromRight) }
        }
    }

    private fun removeView() {
        handler.removeCallbacks(hide)
        handler.removeCallbacks(progressTick)
        seeking = false
        morph?.cancel()
        removeStale()
        view?.let {
            it.animate().cancel()
            runCatching { viewTarget?.windowManager?.removeView(it) }
        }
        view = null
        viewTarget = null
        morph = null
        viewExtra = 0
    }

    /** The size of the capsule on screen right now, or null when there is none. */
    private fun bodySize(): Size? = (view as? ViewGroup)?.getChildAt(0)?.takeIf { it.width > 0 }?.let { Size(it.width, it.height) }

    // --- gestures on the idle pill and the deck ---

    /**
     * Performs a gesture by name, for broadcasts and tests: TAP (open the deck), NEXT, PREVIOUS and CLOSE
     * (while the deck is open), or any [Gesture] name, which runs the action mapped to it.
     */
    fun trigger(name: String?) {
        when (name) {
            TRIGGER_TAP -> openDeck()
            TRIGGER_NEXT -> if (deckOpen) switchCard(1)
            TRIGGER_PREVIOUS -> if (deckOpen) switchCard(-1)
            TRIGGER_CLOSE -> collapse()
            else -> Gesture.entries.firstOrNull { it.name == name }?.let(::fire)
        }
    }

    /**
     * Runs a gesture on the idle pill. While a card is live (music playing, a timer or stopwatch running and so on), the
     * gesture chosen under Dynamic cards opens that card; any other gesture runs the action mapped to it.
     */
    private fun fire(gesture: Gesture) {
        val live = if (gesture == prefs.dynamicCardGesture) deckProvider().firstOrNull { it.live } else null
        if (live != null) {
            openDeck(startAt = live.kind)
            return
        }
        val action = prefs.action(gesture)
        if (action == GestureAction.NONE) return
        if (action == GestureAction.EXPAND) {
            openDeck()
        } else {
            haptic()
            onGesture(action)
        }
    }

    private fun haptic() {
        if (prefs.haptics) (view ?: idle.root)?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** Opens the deck, on the card of kind [startAt] when it has one, else on the first card. */
    private fun openDeck(startAt: Kind? = null) {
        haptic()
        deck = deckProvider().ifEmpty { listOf(placeholder()) }
        deckIndex = deck.indexOfFirst { slot(it.kind) == slot(startAt) }.coerceAtLeast(0)
        val previous = bodySize()
        resetState()
        deckOpen = true
        restartDeckTimer()
        measureDeck()
        // From the pill when nothing is showing, otherwise out of the capsule that is on screen.
        renderDeck(if (previous == null) Anim.GROW else Anim.MORPH, previous)
    }

    /**
     * Starts the deck's countdown again. Called when it opens and when you press something on it. Redraws caused
     * by updates (a timer ticking, a new song) and touches anywhere else never call this, so the deck can't be
     * kept open by them.
     */
    private fun restartDeckTimer() {
        val ms = prefs.deckSeconds * MS_PER_SECOND
        deckCloseAt = SystemClock.uptimeMillis() + ms
        if (deckOpen) {
            handler.removeCallbacks(hide)
            handler.postDelayed(hide, ms)
        }
    }

    private fun renderDeck(anim: Anim, previous: Size? = bodySize()) {
        val card = deck[deckIndex]
        expanded = card.expandedActions.isNotEmpty()
        render(card, anim, previous)
        scheduleDeckTick()
    }

    /** Redraws the shown card when it changes by itself (see [CapsuleEvent.refreshMs]). Redrawing schedules the next one. */
    private val deckTick = Runnable {
        if (deckOpen) current?.let { refreshDeck(it.kind) }
    }

    private fun scheduleDeckTick() {
        handler.removeCallbacks(deckTick)
        val ms = deck.getOrNull(deckIndex)?.refreshMs ?: return
        if (deckOpen) handler.postDelayed(deckTick, ms)
    }

    private fun switchCard(delta: Int) {
        if (deck.size < 2) return
        deckIndex = (deckIndex + delta).mod(deck.size)
        haptic()
        restartDeckTimer()
        renderDeck(if (delta > 0) Anim.SLIDE_FROM_RIGHT else Anim.SLIDE_FROM_LEFT)
    }

    /** Re-reads the cards after a source changed; redraws only if the visible card is the one that changed. */
    private fun refreshDeck(changed: Kind) {
        val shownKind = current?.kind
        deck = deckProvider().ifEmpty { listOf(placeholder()) }
        deckIndex = deck.indexOfFirst { slot(it.kind) == slot(shownKind) }.takeIf { it >= 0 } ?: deckIndex.coerceAtMost(deck.lastIndex)
        measureDeck()
        if (slot(shownKind) == slot(changed) || shownKind == null) renderDeck(Anim.UPDATE)
    }

    /** The deck card a kind of event shows on. Kinds that share a card (timer and stopwatch, torch and phone events) answer alike. */
    private fun slot(kind: Kind?): Any? = kind?.let(DeckCard::pageOf)

    /** The tab chosen on each page of the deck, kept while the deck stays open. */
    private val tabSelection = mutableMapOf<Any, Int>()

    private fun placeholder() = CapsuleEvent(
        kind = Kind.MESSAGE,
        title = "My Capsule",
        text = "Nothing active right now",
        icon = context.packageManager.getApplicationIcon(context.packageName),
    )

    // --- drawing ---

    private fun render(event: CapsuleEvent, anim: Anim, previous: Size? = bodySize()) {
        // The window being replaced stays up until the new one has drawn. Switching cards also lets it slide and fade
        // out under the new card; any other replacement (a button changing the card, a timer tick) is seamless.
        val sliding = anim == Anim.SLIDE_FROM_RIGHT || anim == Anim.SLIDE_FROM_LEFT
        val outgoing = view
        if (outgoing != null) view = null
        removeView()
        idle.hide()
        val v = build(event)
        val target = OverlayHost.target(context)
        val params = layoutParams(target)
        val baseY = params.y

        // Growing out of the pill starts above the capsule's resting place, so the window is stretched up to the pill
        // for the duration of the entrance and shrinks back once the capsule has arrived.
        val extra = if (anim == Anim.GROW) extraTop(target, baseY) else 0
        if (extra > 0) {
            params.y = baseY - extra
            v.setPadding(v.paddingLeft, v.paddingTop + extra, v.paddingRight, v.paddingBottom)
        }
        try {
            target.windowManager.addView(v, params)
        } catch (e: WindowManager.BadTokenException) {
            // The accessibility layer's window has been taken away. Stop using it and draw the normal way instead of crashing.
            if (target.aboveStatusBar) {
                OverlayHost.drop()
                render(event, anim, previous)
            } else {
                current = null
            }
            return
        }

        view = v
        viewTarget = target
        viewBaseY = baseY
        viewExtra = extra
        current = event
        handler.removeCallbacks(progressTick)
        if (deckOpen && event.playback != null) handler.postDelayed(progressTick, PROGRESS_TICK_MS)
        if (outgoing != null) {
            stale = outgoing
            if (sliding) {
                val away = (if (anim == Anim.SLIDE_FROM_RIGHT) -1 else 1) * dp(SLIDE_DP).toFloat()
                outgoing.animate().alpha(0f).translationX(away).setDuration(motion.slideOutMs)
                    .withEndAction { if (stale === outgoing) removeStale() }.start()
            } else {
                // One more frame, so the new window is on screen before the old one goes.
                v.doOnPreDraw { v.post { if (stale === outgoing) removeStale() } }
            }
        }
        when {
            deckOpen -> handler.postDelayed(hide, (deckCloseAt - SystemClock.uptimeMillis()).coerceAtLeast(0L))
            !event.persistent -> handler.postDelayed(
                hide,
                if (expanded) prefs.deckSeconds * MS_PER_SECOND else prefs.durationMs(event.kind),
            )
        }
        if (anim == Anim.GROW) {
            // Everything after the capsule itself is a side icon: fade them in once the capsule has started to open.
            (v as ViewGroup).let { group ->
                for (i in 1 until group.childCount) {
                    group.getChildAt(i).apply {
                        alpha = 0f
                        animate().alpha(1f).setStartDelay(motion.enter.ms / 3).setDuration(SIDE_FADE_MS).start()
                    }
                }
            }
        }
        startMorph(v, anim, previous)
    }

    /** Sets up the morph before the first frame is drawn, so the capsule never flashes at its full size. */
    private fun startMorph(v: View, anim: Anim, previous: Size?) {
        val body = (v as ViewGroup).getChildAt(0) as ViewGroup
        val mo = motion
        val m = Morph(v, body, dp(shapeRadiusDp()).toFloat(), pop = mo.pop)
        morph = m
        v.doOnPreDraw {
            if (view !== v) return@doOnPreDraw
            val toWidth = body.width
            val toHeight = body.height
            when {
                anim == Anim.GROW -> {
                    val pill = idle.bounds()
                    if (mo.fade) {
                        // No travel: it fades in right where it will rest.
                        m.start(
                            from = MorphShape(toWidth.toFloat(), toHeight.toFloat(), dp(shapeRadiusDp()).toFloat()),
                            travelX = 0f,
                            travelY = 0f,
                            fadeBackground = true,
                            startScale = mo.startScale,
                        )
                    } else {
                        m.start(
                            from = MorphShape(pill.width().toFloat(), pill.height().toFloat(), pill.height() / 2f),
                            travelX = pill.exactCenterX() - centerX(v),
                            travelY = -viewExtra.toFloat(),
                        )
                    }
                    m.animateTo(1f, mo.enter.ms, mo.enter.interpolator()) {
                        // Arrived: swap the stretched window for one that is only as big as the capsule.
                        if (view === v && viewExtra > 0) swapWindow(0) {}
                    }
                }
                previous == null || anim == Anim.UPDATE && !isRealResize(previous, toWidth, toHeight) -> m.settle()
                else -> {
                    // From the old size when it was smaller; a capsule that gets smaller just changes content,
                    // because the window has already shrunk to the new size.
                    val from = MorphShape(
                        minOf(previous.width, toWidth).toFloat(),
                        minOf(previous.height, toHeight).toFloat(),
                        dp(shapeRadiusDp()).toFloat(),
                    )
                    val slide = when (anim) {
                        Anim.SLIDE_FROM_RIGHT -> dp(SLIDE_DP).toFloat()
                        Anim.SLIDE_FROM_LEFT -> -dp(SLIDE_DP).toFloat()
                        else -> 0f
                    }
                    m.start(
                        from,
                        travelX = 0f,
                        travelY = 0f,
                        slideX = slide,
                        fadeContent = anim != Anim.UPDATE,
                        fadeBackground = slide != 0f,
                    )
                    m.animateTo(1f, mo.morph.ms, mo.morph.interpolator())
                }
            }
        }
    }

    /** An in-place update only animates when the capsule really changed size, not for a digit or two of text. */
    private fun isRealResize(previous: Size, width: Int, height: Int) =
        abs(previous.width - width) > dp(RESIZE_THRESHOLD_DP) || abs(previous.height - height) > dp(RESIZE_THRESHOLD_DP)

    /** Screen x of the capsule's centre, ignoring any transform that is mid-animation. */
    private fun centerX(v: View): Float {
        val body = (v as ViewGroup).getChildAt(0)
        val location = IntArray(2)
        v.getLocationOnScreen(location)
        return location[0] - v.translationX + body.left + body.width / 2f
    }

    /**
     * How far above the capsule's resting window the pill is: the window must reach up to the pill for the morph to
     * start there. As a plain app overlay the window can't go above the content area, so it is limited to that.
     */
    private fun extraTop(target: OverlayHost.Target, baseY: Int): Int {
        val bodyTop = baseY + dp(SHADOW_ROOM_DP) + (if (target.aboveStatusBar) 0 else statusBarHeight())
        val extra = bodyTop - idle.bounds().top
        return if (target.aboveStatusBar) extra.coerceAtLeast(0) else extra.coerceIn(0, baseY.coerceAtLeast(0))
    }

    /**
     * Replaces the capsule's window with an identical one that reaches [extra] pixels higher (0 for none), without any
     * visible change. Moving a window and changing its padding can't be done in one instant, so the new window is drawn
     * in the same place first and the old one is removed only after that; [onReady] then gets the new window.
     */
    private fun swapWindow(extra: Int, onReady: (View) -> Unit) {
        val event = current ?: return
        val old = view ?: return
        val target = viewTarget ?: return

        val fresh = build(event)
        val params = layoutParams(target)
        params.y = viewBaseY - extra
        if (extra > 0) fresh.setPadding(fresh.paddingLeft, fresh.paddingTop + extra, fresh.paddingRight, fresh.paddingBottom)
        try {
            target.windowManager.addView(fresh, params)
        } catch (e: WindowManager.BadTokenException) {
            if (target.aboveStatusBar) OverlayHost.drop()
            return
        }

        removeStale()
        stale = old
        view = fresh
        viewExtra = extra
        val body = (fresh as ViewGroup).getChildAt(0) as ViewGroup
        val m = Morph(fresh, body, dp(shapeRadiusDp()).toFloat(), pop = motion.pop)
        morph = m
        fresh.doOnPreDraw {
            if (view !== fresh) return@doOnPreDraw
            m.settle()
            // One more frame so the new window is on screen before the old one disappears.
            handler.post {
                if (view !== fresh) return@post
                removeStale()
                onReady(fresh)
            }
        }
    }

    private fun removeStale() {
        stale?.let { runCatching { viewTarget?.windowManager?.removeView(it) } }
        stale = null
    }

    /**
     * The capsule's body for [event], without touch handling. In the deck every card is stacked the same
     * way (content centred, page dots at the bottom) and is at least [deckHeight] tall, so cards of
     * different kinds line up when you swipe between them.
     */
    private fun buildRoot(event: CapsuleEvent): LinearLayout {
        val buttons = if (expanded) event.expandedActions else event.actions
        stacked = !expanded && !deckOpen && event.kind == Kind.MESSAGE && event.actions.any { it.label != null }
        val vertical = expanded || event.image != null || deckOpen || stacked
        val root = LinearLayout(context).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            val color = if (ownDeck) prefs.deckColor(context) else event.accent.takeIf { prefs.coverColors } ?: prefs.capsuleColor(context)
            // Dark text on a light capsule. The styles' own text colours are made for dark capsules, so they step aside.
            onLight = isLightColor(color)
            bodyColor = color
            val radius = dp(shapeRadiusDp()).toFloat()
            background = look?.let { CapsuleLooks.body(it, color, radius, dp(prefs.borderWidth.dp), prefs.borderColorValue()) }
                ?: GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = radius
                    setStroke(dp(prefs.borderWidth.dp), prefs.borderColorValue())
                }
            if (deckOpen) setPadding(dp(DECK_PAD_DP), dp(DECK_PAD_DP), dp(DECK_PAD_DP), dp(DECK_PAD_DP))
            else setPadding(dp(14), dp(12), dp(if (vertical) 14 else 20), dp(12))
            elevation = dp(prefs.shadow.elevationDp).toFloat()
            if (vertical) minimumWidth = if (deckOpen) deckWidth() else dp(300)
            if (deckOpen) minimumHeight = deckHeight
        }

        val content = if (deckOpen) {
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // Headers start at the same place on every card, so they stay put as you swipe.
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                root.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        } else {
            root
        }
        if (deckOpen) {
            addDeckContent(content, event, gap = false)
        } else {
            content.addView(header(event))
            event.image?.let { content.addView(imagePanel(it)) }
            if (buttons.isNotEmpty()) content.addView(buttonRow(buttons, event.kind))
        }
        if (deckOpen && deck.size > 1) root.addView(dots())
        return root
    }

    /**
     * Fills a deck page with [card]. A page that gathers several cards either shows them as tabs, one at a time, or draws
     * them one above the other; each card keeps its own header, grid or list, playback bar and buttons.
     */
    private fun addDeckContent(into: LinearLayout, card: CapsuleEvent, gap: Boolean) {
        val wide = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        if (card.sections.isNotEmpty()) {
            if (card.tabs.isNotEmpty()) {
                val shown = selectedTab(card)
                into.addView(tabBar(card, shown), LinearLayout.LayoutParams(wide, wrap))
                addDeckContent(into, card.sections[shown], gap = true)
            } else {
                card.sections.forEachIndexed { i, section -> addDeckContent(into, section, gap = gap || i > 0) }
            }
            return
        }
        into.addView(deckHeader(card), LinearLayout.LayoutParams(wide, wrap).apply { if (gap) topMargin = dp(SECTION_GAP_DP) })
        card.image?.let { into.addView(imagePanel(it)) }
        if (card.tiles.isNotEmpty()) into.addView(tileGrid(card.tiles))
        if (card.rows.isNotEmpty()) into.addView(rowList(card.rows, card.kind))
        if (card.stats.isNotEmpty()) into.addView(statGrid(card.stats))
        // The playback bar belongs to the deck only; small and long-press-expanded capsules don't have one.
        card.playback?.invoke()?.let { into.addView(progressRow(it)) }
        val buttons = card.expandedActions.ifEmpty { card.actions }
        if (buttons.isNotEmpty()) into.addView(buttonRow(buttons, card.kind))
    }

    private fun selectedTab(card: CapsuleEvent) = (tabSelection[slot(card.kind)] ?: 0).coerceIn(0, card.sections.lastIndex)

    /** The row of tab names at the top of a page: the open one is a lighter pill, the others plain text. */
    private fun tabBar(card: CapsuleEvent, shown: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply { setColor(wash(8, 0)); cornerRadius = dp(DECK_TAB_DP).toFloat() }
        setPadding(dp(3), dp(3), dp(3), dp(3))
        card.tabs.forEachIndexed { i, name ->
            addView(TextView(context).apply {
                text = name
                textSize = 12f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setTextColor(if (i == shown) ink(if (onLight) INK else WHITE) else inkDim(if (onLight) INK_DIM else LIGHT_GRAY))
                if (i == shown) background = GradientDrawable().apply { setColor(wash(18, 0)); cornerRadius = dp(DECK_TAB_DP).toFloat() - dp(3) }
                isClickable = true
                setOnClickListener {
                    tabSelection[slot(card.kind) ?: return@setOnClickListener] = i
                    restartDeckTimer()
                    haptic()
                    renderDeck(Anim.UPDATE)
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(12) }
    }

    /**
     * Sets [deckHeight] so the deck keeps one height for every card: the tallest card, but never less than
     * [DECK_HEIGHT_DP], so the deck is the same size whether or not a timer is running. The width is fixed in [build].
     */
    private fun measureDeck() {
        val shown = expanded
        deckHeight = 0
        if (!prefs.uniformDeck) return
        val widthSpec = View.MeasureSpec.makeMeasureSpec(deckWidth(), View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        deckHeight = maxOf(dp(DECK_HEIGHT_DP), deck.maxOf { card ->
            expanded = card.expandedActions.isNotEmpty()
            buildRoot(card).apply { measure(widthSpec, heightSpec) }.measuredHeight
        })
        expanded = shown
    }

    private fun build(event: CapsuleEvent): View {
        val root = buildRoot(event)
        root.setOnTouchListener(if (deckOpen) deckGestures(event) else eventGestures(event))

        // The banners waiting behind this one: the second goes on the right, the third on the left, and so on.
        val waiting = if (shownWithSides(event)) tray.drop(1) else emptyList()
        val right = waiting.filterIndexed { i, _ -> i % 2 == 0 }
        val left = waiting.filterIndexed { i, _ -> i % 2 == 1 }
        // Room on both sides, equal, so the capsule itself stays in the middle.
        val sideRoom = dp(SIDE_SLOT_DP) * maxOf(left.size, right.size)

        // The window only draws inside its own bounds, so the shadow needs transparent room around the capsule.
        val frame = if (waiting.isEmpty()) FrameLayout(context) else WideFrame(context, context.resources.displayMetrics.widthPixels)
        return frame.apply {
            clipChildren = false
            clipToPadding = false
            setPadding(dp(SHADOW_ROOM_DP) + sideRoom, dp(SHADOW_ROOM_DP), dp(SHADOW_ROOM_DP) + sideRoom, dp(SHADOW_ROOM_DP))
            // Every deck card has exactly the same width, whatever is on it.
            addView(root, FrameLayout.LayoutParams(
                if (deckOpen) deckWidth() else FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
            // Icons sit in the room beside the capsule: a negative margin moves each past the capsule's own edge.
            right.forEachIndexed { k, e ->
                addView(sideIcon(e, fromRight = true), FrameLayout.LayoutParams(dp(SIDE_ICON_DP), dp(SIDE_ICON_DP), Gravity.END or Gravity.CENTER_VERTICAL)
                    .apply { marginEnd = -(dp(SIDE_GAP_DP) + k * dp(SIDE_SLOT_DP) + dp(SIDE_ICON_DP)) })
            }
            left.forEachIndexed { k, e ->
                addView(sideIcon(e, fromRight = false), FrameLayout.LayoutParams(dp(SIDE_ICON_DP), dp(SIDE_ICON_DP), Gravity.START or Gravity.CENTER_VERTICAL)
                    .apply { marginStart = -(dp(SIDE_GAP_DP) + k * dp(SIDE_SLOT_DP) + dp(SIDE_ICON_DP)) })
            }
            // A touch the capsule itself doesn't take lands here: either in the transparent margin around it, or
            // (reported by the window flag below) outside the window altogether. Both mean "outside the deck".
            setOnTouchListener { _, e ->
                val outside = e.actionMasked == MotionEvent.ACTION_OUTSIDE || e.actionMasked == MotionEvent.ACTION_DOWN
                if (outside && deckOpen && prefs.closeOnOutsideTap) {
                    collapse()
                    true
                } else {
                    false
                }
            }
        }
    }

    /** Cover/icon plus title and artist; only the sizes differ between compact and expanded. */
    private fun header(event: CapsuleEvent): View {
        val iconSize = if (expanded) 72 else 36
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val icon = ImageView(context).apply {
                setImageDrawable(event.icon)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)).apply { marginEnd = dp(12) }
            }
            val corner = if (expanded) 14 else 8
            // A plain glyph (system events, the torch, timers and so on) takes the text colour, like the buttons do. App
            // icons, avatars and album art keep their own colours.
            if (event.kind in GLYPH_KINDS) icon.setColorFilter(ink(if (onLight) INK else look?.icon ?: WHITE))
            // Notifications take the icon shape chosen in settings; every other capsule keeps its rounded corners.
            if (event.kind == Kind.MESSAGE) {
                prefs.iconShape.applyTo(icon, corner)
                addView(icon)
            } else {
                addView(rounded(icon, corner))
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val textWidth = if (stacked) 230 else 200
                addView(line(event.title, ink(if (onLight) INK else look?.title ?: WHITE), if (expanded) 17f else 14f, bold = true, maxWidthDp = textWidth))
                addView(line(event.text, inkDim(if (onLight) INK_DIM else look?.subtitle ?: LIGHT_GRAY), if (expanded) 14f else 12f, maxWidthDp = textWidth))
                event.progress?.let { addView(progressBar(it)) }
            })
        }
    }

    /**
     * The header of a deck card: a round icon, the title and a small monospace line under it. A song shows its cover as a
     * wide panel above its title, and a running timer or focus session adds a progress ring with the time in it.
     */
    private fun deckHeader(event: CapsuleEvent): View {
        val fg = ink(if (onLight) INK else WHITE)
        val dim = inkDim(if (onLight) INK_DIM else LIGHT_GRAY)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val wide = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT

        if (event.kind == Kind.MEDIA && event.icon != null) {
            column.addView(rounded(ImageView(context).apply {
                setImageDrawable(event.icon)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { setColor(wash(8, 0)) }
            }, DECK_PANEL_RADIUS_DP), LinearLayout.LayoutParams(wide, dp(DECK_ART_DP)))
            column.addView(line(event.title, fg, 17f, bold = true, maxWidthDp = DECK_INNER_DP),
                LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(10) })
            column.addView(line(event.text, dim, 13f, maxWidthDp = DECK_INNER_DP))
            return column
        }

        val ringed = event.progress != null && (event.kind == Kind.TIMER || event.kind == Kind.POMODORO)
        // A ring carries the time itself, so the header names the card; otherwise it shows the card's own title and text.
        val title = if (ringed) event.kind.label else event.title
        val subtitle = if (ringed) event.text?.takeIf { it.toString() != event.kind.label } else event.text

        val badge = FrameLayout(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(wash(14, 0)) }
            addView(ImageView(context).apply {
                setImageDrawable(event.icon)
                if (event.kind in GLYPH_KINDS) {
                    setColorFilter(fg)
                } else {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
            }, if (event.kind in GLYPH_KINDS) {
                FrameLayout.LayoutParams(dp(DECK_GLYPH_DP), dp(DECK_GLYPH_DP), Gravity.CENTER)
            } else {
                FrameLayout.LayoutParams(dp(DECK_BADGE_DP), dp(DECK_BADGE_DP), Gravity.CENTER)
            })
            if (event.kind !in GLYPH_KINDS) rounded(this, DECK_BADGE_DP / 2)
        }
        column.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(badge, LinearLayout.LayoutParams(dp(DECK_BADGE_DP), dp(DECK_BADGE_DP)).apply { marginEnd = dp(12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(line(title, fg, 17f, bold = true, maxWidthDp = DECK_INNER_DP - DECK_BADGE_DP - 12))
                addView(line(subtitle, dim, 12f, maxWidthDp = DECK_INNER_DP - DECK_BADGE_DP - 12).apply { typeface = Typeface.MONOSPACE })
            }, LinearLayout.LayoutParams(0, wrap, 1f))
        }, LinearLayout.LayoutParams(wide, wrap))

        event.progress?.let { fraction ->
            if (ringed) {
                column.addView(FrameLayout(context).apply {
                    addView(RingView(context, fraction, fg, wash(16, 0), dp(RING_STROKE_DP).toFloat()),
                        FrameLayout.LayoutParams(dp(RING_DP), dp(RING_DP), Gravity.CENTER))
                    addView(line(event.title, fg, 28f, bold = true, maxWidthDp = RING_DP - 2 * RING_STROKE_DP)
                        .apply { typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER },
                        FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))
                }, LinearLayout.LayoutParams(wide, dp(RING_DP)).apply { topMargin = dp(10) })
            } else {
                column.addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = PROGRESS_MAX
                    progress = (fraction * PROGRESS_MAX).toInt()
                    progressDrawable = barDrawable(fg, wash(16, 0))
                }, LinearLayout.LayoutParams(wide, dp(DECK_BAR_DP)).apply { topMargin = dp(12) })
            }
        }
        return column
    }

    /** A bar with round ends: a faint track with the text colour filling it from the left. */
    private fun barDrawable(fill: Int, track: Int): Drawable {
        fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(DECK_BAR_DP).toFloat() / 2 }
        return LayerDrawable(arrayOf(pill(track), ClipDrawable(pill(fill), Gravity.START, ClipDrawable.HORIZONTAL))).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    /** White panel so a barcode keeps its quiet zone and stays scannable on any capsule colour. */
    private fun imagePanel(image: Drawable) = rounded(ImageView(context).apply {
        setImageDrawable(image)
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(0xFFFFFFFF.toInt())
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(dp(IMAGE_WIDTH_DP), LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }, 12)

    /** The quick settings tiles in rows of [TILE_COLUMNS]: an icon over a name, brighter while the setting is on. */
    private fun tileGrid(tiles: List<Tile>) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(TILE_GRID_TOP_DP) }
        tiles.chunked(TILE_COLUMNS).forEach { rowTiles ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                rowTiles.forEach {
                    // The gaps are set here: the params given to addView replace any a tile carries itself.
                    addView(tileView(it), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { setMargins(dp(TILE_GAP_DP), dp(TILE_GAP_V_DP), dp(TILE_GAP_DP), dp(TILE_GAP_V_DP)) })
                }
                // A short last row keeps its tiles the same size as the rows above it.
                repeat(TILE_COLUMNS - rowTiles.size) { addView(View(context), LinearLayout.LayoutParams(0, 0, 1f)) }
            })
        }
    }

    private fun tileView(tile: Tile) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(10), dp(4), dp(10))
        background = GradientDrawable().apply {
            setColor(
                if (tile.on) wash(18, if (onLight) 0x33000000 else 0x44FFFFFF)
                else wash(7, if (onLight) 0x0F000000 else 0x14FFFFFF),
            )
            cornerRadius = dp(18).toFloat()
            // A lit tile gets a faint outline in the text colour, like the card's other highlights.
            if (tile.on && deckOpen) setStroke(dp(1), wash(35, 0))
        }
        contentDescription = "${tile.label}, ${if (tile.on) "on" else "off"}"
        addView(ImageView(context).apply {
            setImageDrawable(tile.icon)
            if (tile.tinted) setColorFilter(ink(if (onLight) INK else look?.icon ?: WHITE))
            alpha = if (tile.on || !tile.tinted) 1f else TILE_OFF_ALPHA
        }, LinearLayout.LayoutParams(dp(if (tile.tinted) TILE_ICON_DP else TILE_APP_ICON_DP), dp(if (tile.tinted) TILE_ICON_DP else TILE_APP_ICON_DP)))
        // The tile's name under its icon, as on the control deck: dimmer while the setting is off.
        addView(
            line(tile.label, if (tile.on) ink(if (onLight) INK else WHITE) else inkDim(if (onLight) INK_DIM else LIGHT_GRAY), 10f, maxWidthDp = TILE_LABEL_DP)
                .apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) },
        )
        isClickable = true
        setOnClickListener {
            if (deckOpen) restartDeckTimer()
            tile.run()
            if (tile.dismisses) collapse() else handler.postDelayed({ if (deckOpen) refreshDeck(Kind.SYSTEM) }, TILE_REFRESH_MS)
        }
    }

    /** A stats card's readings in rows of [STAT_COLUMNS]: a small icon and its value in a chip. */
    private fun statGrid(stats: List<Stat>) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(TILE_GRID_TOP_DP) }
        stats.chunked(STAT_COLUMNS).forEach { rowStats ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                rowStats.forEach { stat ->
                    // A panel like the telemetry card's: the reading's name small in the top corner with its icon, the value below it.
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = GradientDrawable().apply {
                            setColor(wash(8, if (onLight) 0x14000000 else 0x1FFFFFFF))
                            cornerRadius = dp(16).toFloat()
                        }
                        contentDescription = "${stat.description} ${stat.value}"
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(line(stat.description.substringBefore(','), inkDim(if (onLight) INK_DIM else LIGHT_GRAY), 11f, maxWidthDp = STAT_TEXT_DP)
                                .apply { typeface = Typeface.MONOSPACE }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                            addView(ImageView(context).apply {
                                setImageDrawable(stat.icon)
                                setColorFilter(ink(if (onLight) INK else look?.icon ?: WHITE))
                            }, LinearLayout.LayoutParams(dp(STAT_ICON_DP), dp(STAT_ICON_DP)))
                        })
                        addView(line(stat.value, ink(if (onLight) INK else look?.title ?: WHITE), 17f, bold = true, maxWidthDp = STAT_TEXT_DP + 24),
                            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(TILE_GAP_DP), dp(TILE_GAP_DP), dp(TILE_GAP_DP), dp(TILE_GAP_DP)) })
                }
                // A short last row keeps its chips the same size as the rows above it.
                repeat(STAT_COLUMNS - rowStats.size) { addView(View(context), LinearLayout.LayoutParams(0, 0, 1f)) }
            })
        }
    }

    /** A list card's lines, one under the other: a number, then the text on one line. A tap runs the row; a long press its other action. */
    private fun rowList(rows: List<Row>, kind: Kind) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        rows.forEach { row ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(ROW_HEIGHT_DP)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                background = GradientDrawable().apply {
                    setColor(wash(8, if (onLight) 0x14000000 else 0x1FFFFFFF))
                    cornerRadius = dp(if (deckOpen) 16 else 12).toFloat()
                }
                row.number?.let {
                    addView(line(it.toString(), inkDim(if (onLight) INK_DIM else look?.subtitle ?: LIGHT_GRAY), 12f, bold = true, maxWidthDp = ROW_NUMBER_DP)
                        .apply { minWidth = dp(ROW_NUMBER_DP) })
                }
                addView(line(row.text, ink(if (onLight) INK else look?.title ?: WHITE), 13f, maxWidthDp = ROW_TEXT_DP))
                isClickable = true
                setOnClickListener {
                    if (deckOpen) restartDeckTimer()
                    row.run()
                    if (row.dismisses) collapse() else handler.postDelayed({ if (deckOpen) refreshDeck(kind) }, TILE_REFRESH_MS)
                }
                row.onLongPress?.let { hold ->
                    setOnLongClickListener {
                        if (deckOpen) restartDeckTimer()
                        hold()
                        handler.postDelayed({ if (deckOpen) refreshDeck(kind) }, TILE_REFRESH_MS)
                        true
                    }
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(3) }
            })
        }
    }

    private fun progressBar(fraction: Float) =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = PROGRESS_MAX
            progress = (fraction * PROGRESS_MAX).toInt()
            progressTintList = ColorStateList.valueOf(ink(if (onLight) INK else look?.barActive ?: WHITE))
            progressBackgroundTintList = ColorStateList.valueOf(inkTrack(if (onLight) TRACK_LIGHT else look?.barTrack ?: TRACK))
            minimumWidth = dp(140)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4),
            ).apply { topMargin = dp(6) }
        }

    /** One dot per deck card, with the visible card highlighted. */
    private fun dots() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        deck.indices.forEach { i ->
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    // The card you are on is a longer pill, the others round dots.
                    cornerRadius = dp(DOT_DP).toFloat()
                    setColor(if (i == deckIndex) ink(if (onLight) INK else look?.dotOn ?: WHITE) else inkTrack(if (onLight) TRACK_LIGHT else look?.dotOff ?: DOT_OFF))
                }
                layoutParams = LinearLayout.LayoutParams(dp(if (i == deckIndex) DOT_ACTIVE_DP else DOT_DP), dp(DOT_DP)).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            })
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) }
    }

    /** Elapsed time, a bar, and total time. The bar can be dragged to seek when the player allows it. */
    private fun progressRow(playback: Playback) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14) }

        val elapsed = timeLabel(TAG_ELAPSED, playback.positionMs, Gravity.START)
        addView(SeekBar(context).apply {
            tag = TAG_SEEK
            max = playback.durationMs.toInt().coerceAtLeast(1)
            progress = playback.positionMs.toInt()
            progressDrawable = barDrawable(ink(if (onLight) INK else look?.barActive ?: WHITE), wash(16, 0))
            splitTrack = false
            minimumHeight = dp(SEEK_HEIGHT_DP)
            setPadding(dp(8), dp(6), dp(8), dp(6))

            val seekTo = playback.seekTo
            if (seekTo == null) {
                thumb = null
                isEnabled = false
            } else {
                thumb = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ink(if (onLight) INK else look?.barActive ?: WHITE))
                    setSize(dp(THUMB_DP), dp(THUMB_DP))
                }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        if (fromUser) elapsed.text = formatTime(value.toLong())
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) {
                        seeking = true
                        restartDeckTimer()
                    }

                    override fun onStopTrackingTouch(bar: SeekBar) {
                        seekTo(bar.progress.toLong())
                        seeking = false
                        restartDeckTimer()
                    }
                })
            }
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
            addView(elapsed, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(timeLabel(TAG_TOTAL, playback.durationMs, Gravity.END), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    private fun timeLabel(tag: String, ms: Long, gravity: Int) = TextView(context).apply {
        this.tag = tag
        text = formatTime(ms)
        this.gravity = gravity
        textSize = 11f
        setTextColor(inkDim(if (onLight) INK_DIM else look?.subtitle ?: LIGHT_GRAY))
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms / MS_PER_SECOND
        val hours = seconds / SECONDS_PER_HOUR
        val minutes = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val rest = seconds % SECONDS_PER_MINUTE
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
    }

    /**
     * The buttons of a capsule. In the deck each icon sits in a round wash, and the main one of a song (play or pause, between
     * previous and next) or a timer (pause or resume, the first) is a larger filled circle in the text colour.
     */
    private fun buttonRow(actions: List<CapsuleAction>, kind: Kind? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        val size = if (deckOpen) DECK_BUTTON_DP else if (expanded) 52 else 44
        val main = if (!deckOpen) -1 else when (kind) {
            Kind.MEDIA -> if (actions.size == 3) 1 else 0
            Kind.TIMER, Kind.POMODORO -> actions.indexOfFirst { it.label == null }
            else -> -1
        }
        actions.forEachIndexed { i, action ->
            addView(
                if (action.label != null) labelButton(action)
                else if (i == main) iconButton(action, DECK_PRIMARY_DP, primary = true)
                else iconButton(action, size),
            )
        }
        if (expanded || deckOpen || stacked) layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(if (deckOpen) 12 else 8) }
    }

    private fun iconButton(action: CapsuleAction, sizeDp: Int, primary: Boolean = false) = ImageButton(context).apply {
        setImageDrawable(action.icon)
        contentDescription = action.description
        val fg = ink(if (onLight) INK else look?.icon ?: WHITE)
        setColorFilter(if (primary) bodyColor else fg)
        background = if (deckOpen) GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (primary) fg else wash(12, 0))
        } else {
            null
        }
        // The icons are plain 24dp glyphs: keep them that size inside the touch target, not stretched to fill it.
        val pad = sizeDp / 4
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        setOnClickListener {
            if (deckOpen) restartDeckTimer()
            action.run()
            if (action.dismisses) current?.let(::dismissByUser)
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { marginStart = dp(8) }
    }

    private fun labelButton(action: CapsuleAction) = TextView(context).apply {
        text = action.label
        contentDescription = action.description
        setTextColor(ink(if (onLight) INK else look?.chipText ?: WHITE))
        textSize = 13f
        gravity = Gravity.CENTER
        setSingleLine()
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = look?.takeIf { !onLight && !ownDeck }?.chip?.invoke(dp(16).toFloat()) ?: GradientDrawable().apply {
            setColor(wash(14, if (onLight) 0x22000000 else 0x33FFFFFF))
            cornerRadius = dp(16).toFloat()
        }
        setOnClickListener {
            if (deckOpen) restartDeckTimer()
            action.run()
            if (action.dismisses) current?.let(::dismissByUser)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(8) }
    }

    // --- touch handling on a visible capsule ---

    /** Event capsule: tap opens its action, swipe up dismisses, long-press expands if it can. */
    private fun eventGestures(event: CapsuleEvent) = touchListener(
        onTap = {
            runCatching { event.onTap?.send() }
            dismissByUser(event)
        },
        onLongPress = {
            if (!expanded && event.expandedActions.isNotEmpty()) {
                expanded = true
                render(event, Anim.MORPH)
            }
        },
        onFling = { _, vy ->
            (vy < -FLING_VELOCITY).also { if (it) dismissByUser(event) }
        },
    )

    /** Deck card: tap runs its action and collapses, sideways swipes switch cards, swipe up collapses. */
    private fun deckGestures(event: CapsuleEvent) = touchListener(
        onTap = {
            runCatching { event.onTap?.send() }
            collapse()
        },
        onFling = { vx, vy ->
            when {
                abs(vx) > abs(vy) && abs(vx) > FLING_VELOCITY -> { switchCard(if (vx < 0) 1 else -1); true }
                vy < -FLING_VELOCITY -> { collapse(); true }
                else -> false
            }
        },
    )

    private fun touchListener(
        onTap: () -> Unit,
        onLongPress: () -> Unit = {},
        onFling: (vx: Float, vy: Float) -> Boolean = { _, _ -> false },
    ): View.OnTouchListener {
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTap()
                return true
            }

            override fun onLongPress(e: MotionEvent) = onLongPress()

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float) = onFling(vx, vy)
        })
        return View.OnTouchListener { _, e -> detector.onTouchEvent(e) }
    }

    private fun line(text: CharSequence?, color: Int, size: Float, bold: Boolean = false, maxWidthDp: Int = 200) = TextView(context).apply {
        this.text = text
        setTextColor(color)
        textSize = size
        if (bold) look?.boldTypeface?.let { typeface = it }
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = dp(maxWidthDp)
        visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun rounded(v: View, radiusDp: Int): View = v.apply {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(radiusDp).toFloat())
            }
        }
    }

    /** Above the status bar the capsule is placed from the very top; as a plain app overlay it starts below it. */
    private fun layoutParams(target: OverlayHost.Target) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        target.type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (target.aboveStatusBar) WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN else 0) or
            // Lets the open deck hear about touches elsewhere on the screen. They still reach the app underneath.
            (if (deckOpen && prefs.closeOnOutsideTap) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0),
        PixelFormat.TRANSLUCENT,
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val place = position
        gravity = Gravity.TOP or place.align.gravity
        // Offsets are for the capsule itself, so subtract the transparent shadow room around it. A window on the right
        // edge counts x inward (to the left), so there a positive offset, which means to the right, is taken off.
        val edge = if (place.align == Align.CENTER) 0 else dp(SIDE_MARGIN_DP - SHADOW_ROOM_DP)
        x = when (place.align) {
            Align.START -> edge + dp(place.offsetXDp)
            Align.END -> edge - dp(place.offsetXDp)
            Align.CENTER -> dp(place.offsetXDp)
        }
        y = if (deckOpen || expanded) belowPill(target) else eventTop(target)
    }

    /**
     * How far down the screen the deck's top edge is, in dp. This is the lowest the "distance from the top"
     * setting can put a small event capsule, so it never sits below the expanded view.
     */
    fun deckTopDp() = ((idle.bottom() + deckGap()) / density).toInt()

    /** The default distance from the top, in dp: just under the status bar, whatever its height on this phone. */
    fun defaultTopDp() = ((statusBarHeight() + dp(DEFAULT_GAP_BELOW_STATUS_BAR_DP)) / density).toInt()

    /**
     * Window y for a small event capsule whose top edge is [Prefs.topOffsetDp] from the top of the screen.
     * As a plain app overlay it can't go above the status bar, and its y counts from under it.
     */
    private fun eventTop(target: OverlayHost.Target): Int {
        // Never below the expanded view, even if the gap setting was shrunk after this distance was saved.
        val top = minOf(dp(position.topDp ?: defaultTopDp()), idle.bottom() + deckGap())
        return if (target.aboveStatusBar) {
            top - dp(SHADOW_ROOM_DP)
        } else {
            maxOf(top, statusBarHeight()) - statusBarHeight() - dp(SHADOW_ROOM_DP)
        }
    }

    /**
     * Window y that puts the capsule's top edge [Prefs.deckGapPercent] of the screen height under the pill.
     * As a plain app overlay, y counts from under the status bar, so that height is taken off.
     */
    private fun belowPill(target: OverlayHost.Target) =
        idle.bottom() + deckGap() - (if (target.aboveStatusBar) 0 else statusBarHeight()) - dp(SHADOW_ROOM_DP)

    private fun deckGap() = context.resources.displayMetrics.heightPixels * prefs.deckGapPercent / PERCENT

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private fun dp(v: Int) = (v * density).toInt()

    companion object {
        const val TRIGGER_TAP = "TAP"
        const val TRIGGER_NEXT = "NEXT"
        const val TRIGGER_PREVIOUS = "PREVIOUS"
        const val TRIGGER_CLOSE = "CLOSE"

        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val LIGHT_GRAY = 0xFFCCCCCC.toInt()
        private const val INK = 0xFF16161C.toInt()
        private const val INK_DIM = 0xFF50505C.toInt()
        private const val TRACK_LIGHT = 0x33000000
        private const val TRACK = 0x44FFFFFF
        private const val DOT_OFF = 0x55FFFFFF
        private const val SIDE_MARGIN_DP = 12
        private const val MAX_TRAY = 5
        private val GLYPH_KINDS = setOf(Kind.SYSTEM, Kind.FLASHLIGHT, Kind.TIMER, Kind.STOPWATCH, Kind.VOLUME, Kind.BARCODE, Kind.CLIPBOARD, Kind.NOTES,
            Kind.BATTERY, Kind.STATS, Kind.CALENDAR, Kind.LAUNCHER, Kind.POMODORO)
        private const val SIDE_ICON_DP = 40
        private const val SIDE_GAP_DP = 8
        private const val SIDE_SLOT_DP = SIDE_ICON_DP + SIDE_GAP_DP
        private const val SIDE_PAD_DP = 9
        private const val SIDE_FADE_MS = 200L
        private const val SHADOW_ROOM_DP = 16
        private const val PERCENT = 100
        private const val DEFAULT_GAP_BELOW_STATUS_BAR_DP = 8
        private const val IMAGE_WIDTH_DP = 260
        private const val DOT_DP = 6
        private const val DOT_ACTIVE_DP = 18
        private const val SECTION_GAP_DP = 14
        private const val TILE_COLUMNS = 4
        private const val TILE_LABEL_DP = 64
        private const val STAT_COLUMNS = 2
        private const val STAT_ICON_DP = 18
        private const val STAT_TEXT_DP = 100
        private const val DECK_RADIUS_DP = 28
        private const val DECK_PAD_DP = 16
        private const val DECK_WIDTH_DP = 340
        private const val DECK_HEIGHT_DP = 360
        private const val DECK_INNER_DP = DECK_WIDTH_DP - 2 * DECK_PAD_DP
        private const val DECK_PANEL_RADIUS_DP = 18
        private const val DECK_ART_DP = 120
        private const val DECK_GLYPH_DP = 20
        private const val DECK_BADGE_DP = 40
        private const val DECK_BAR_DP = 8
        private const val DECK_TAB_DP = 20
        private const val DECK_BUTTON_DP = 44
        private const val DECK_PRIMARY_DP = 58
        private const val RING_DP = 130
        private const val RING_STROKE_DP = 8
        private const val TILE_GRID_TOP_DP = 18
        private const val TILE_GAP_DP = 5
        private const val TILE_GAP_V_DP = 8
        private const val TILE_ICON_DP = 24
        private const val TILE_APP_ICON_DP = 32
        private const val TILE_OFF_ALPHA = 0.6f
        private const val TILE_REFRESH_MS = 350L
        private const val ROW_HEIGHT_DP = 34
        private const val ROW_NUMBER_DP = 20
        private const val ROW_TEXT_DP = 240
        private const val SEEK_HEIGHT_DP = 28
        private const val THUMB_DP = 12
        private const val PROGRESS_TICK_MS = 250L
        private const val SECONDS_PER_MINUTE = 60
        private const val SECONDS_PER_HOUR = 3_600
        private const val TAG_SEEK = "playback_seek"
        private const val TAG_ELAPSED = "playback_elapsed"
        private const val TAG_TOTAL = "playback_total"
        // Kept below SHADOW_ROOM_DP: the window only has that much room around the capsule, and a slide past it is clipped.
        private const val SLIDE_DP = 12
        private const val RESIZE_THRESHOLD_DP = 6
        private const val MS_PER_SECOND = 1_000L

        // Motion. The entrance and size changes are springs (damping below 1 gives a small, soft overshoot);
        // the exit is a smooth deceleration, since a bounce while leaving would feel unsettled.
        private const val EXIT_X1 = 0.4f
        private const val EXIT_X2 = 0.2f
        private const val FLING_VELOCITY = 800f
        private const val PROGRESS_MAX = 1000
    }
}

/** A ring that fills clockwise from the top: [fraction] of the full circle in [fill] over a faint [track]. */
private class RingView(context: Context, private val fraction: Float, fill: Int, track: Int, private val stroke: Float) : View(context) {
    private val rect = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; color = track
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; color = fill; strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val pad = stroke / 2
        rect.set(pad, pad, width - pad, height - pad)
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)
        canvas.drawArc(rect, -90f, 360f * fraction.coerceIn(0f, 1f), false, fillPaint)
    }
}

/**
 * A window frame allowed to be as wide as the screen. The system first measures a wrap-content overlay window at about 320
 * dp wide, which is fine for a capsule alone but squeezes it once icons take room at its sides.
 */
private class WideFrame(context: Context, private val maxWidth: Int) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        super.onMeasure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), heightMeasureSpec)
}
