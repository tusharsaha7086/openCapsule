package dev.tushar.mycapsule

import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doOnTextChanged
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider

/**
 * Setup and settings screen, in three tabs behind a floating bar at the bottom:
 *
 *  - **Capsule:** whether it is set up, which capsules appear, and the tools (timer, barcode).
 *  - **Customize:** how it looks, where it sits, the idle pill, gestures and feedback.
 *  - **App:** how this app looks, help, reset and credits.
 *
 * Each tab is a list of titled sections, and each section is one surface of icon rows. Rows are built from the same few
 * helpers (headRow, switchRow, chips, swatches, slider, button), driven by lists and enums. Any change updates the live
 * preview and shows a sample capsule on the phone.
 *
 * What things look like comes from the current [Skin]; the helpers only say what each piece is. Picking another
 * style under "App style" rebuilds the screen in place.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var overlay: CapsuleOverlay
    private lateinit var skin: Skin
    private lateinit var scroll: ScrollView
    private var previewHolder: FrameLayout? = null
    private var screenBackground: Drawable? = null
    private var cardIndex = 0
    private var tab = 0
    private val scrollOffsets = IntArray(TAB_COUNT)

    /** True while a drill-down page (such as System events) is open on top of the current tab. */
    private var inSub = false
    private var nav: View? = null
    private var navScrim: View? = null
    private var topInset = 0
    private var bottomInset = 0

    /** The system back button closes a drill-down page before it leaves the app. */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeSub()
    }

    private val density by lazy { resources.displayMetrics.density }

    /** One tab of the screen: its bar icon (plain and filled when selected) and how to fill it. */
    private class Page(val label: String, val icon: Int, val iconSelected: Int, val build: LinearLayout.() -> Unit)

    private val pages by lazy {
        listOf(
            Page("Capsule", R.drawable.ic_widgets, R.drawable.ic_widgets_fill) { capsulePage() },
            Page("Customize", R.drawable.ic_palette, R.drawable.ic_palette_fill) { customizePage() },
            Page("App", R.drawable.ic_settings, R.drawable.ic_settings_fill) { appPage() },
        )
    }

    /** The bar's buttons, for restyling the selected one. */
    private val navItems = mutableListOf<Pair<FrameLayout, ImageView>>()

    /** Each status row re-reads its own state when the screen comes back from a settings page. */
    private val statusRows = mutableListOf<() -> Unit>()

    /** Sections wait for the splash to finish, then rise in one after another. */
    private val pendingReveal = mutableListOf<View>()
    private var splash: SplashOverlay? = null

    /** The position editor saved or reset something: the page is built again so its sliders show the new values. */
    private val positionEditor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) rebuild()
    }

    /** Asks for permission to post the test message (Android 13 and later), and posts it once it is given. */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) TestMessage.post(this) else Toast.makeText(this, "The test message needs permission to post a notification", Toast.LENGTH_LONG).show()
    }

    /** Asks for permission to read the calendar, for the Calendar card. The card reads it afresh, so nothing more is needed here. */
    private val calendarPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(this, if (granted) "Calendar allowed" else "The Calendar card needs access to your calendar", Toast.LENGTH_SHORT).show()
    }

    /** The Calendar card sent us here to ask; the request is shown once and the flag is cleared so it is not asked again on rotation. */
    private fun askCalendarIfRequested(intent: Intent?) {
        if (intent?.getBooleanExtra(CalendarAccess.EXTRA_ASK, false) != true) return
        intent.removeExtra(CalendarAccess.EXTRA_ASK)
        if (!CalendarAccess.granted(this)) calendarPermission.launch(android.Manifest.permission.READ_CALENDAR)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        askCalendarIfRequested(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val systemSplash = installSplashScreen()
        // The chosen font and light or dark, before anything is drawn. Changing the theme later recreates the screen.
        Prefs(this).let {
            AppFonts.current = it.appFont
            AppCompatDelegate.setDefaultNightMode(it.appTheme.nightMode)
        }
        super.onCreate(savedInstanceState)
        // The in-app splash starts from the same frame, so the system one just goes away.
        systemSplash.setOnExitAnimationListener {
            it.remove()
            // Removing the system splash puts the theme's status bar colours back, so set ours again.
            applySystemBars()
        }
        prefs = Prefs(this)
        overlay = CapsuleOverlay(applicationContext, prefs)
        askCalendarIfRequested(intent)
        onBackPressedDispatcher.addCallback(this, backCallback)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        skin = Skins.of(prefs.uiStyle, this, density)
        tab = savedInstanceState?.getInt(STATE_TAB, 0)?.coerceIn(0, TAB_COUNT - 1) ?: 0

        val root = buildUi()
        // Only on a fresh launch, not when the screen is recreated (rotation, theme change).
        if (savedInstanceState == null && !prefs.reduceAnimations) {
            splash = SplashOverlay(this) { splash = null; applySystemBars(); revealCards() }.also {
                root.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                it.play()
            }
        } else {
            revealCards()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab)
    }

    /** Builds the whole screen in the current skin and makes it the content. Safe to call again to change style. */
    private fun buildUi(): FrameLayout {
        window.setBackgroundDrawable(ColorDrawable(skin.bg))

        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
        }
        // Content scrolls under the status bar, so a fade at the top keeps the clock and icons readable.
        val scrim = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(skin.scrim, skin.scrim and 0x00FFFFFF))
        }
        val bar = navBar()
        nav = bar
        // Content fades out behind the bar instead of ending in a hard line against it.
        val bottomScrim = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(skin.scrim, skin.scrim and 0x00FFFFFF))
        }
        navScrim = bottomScrim
        val barPad = paddingOf(skin.card(NAV_CARD_INDEX))
        val root = FrameLayout(this).apply {
            background = skin.screen().also { screenBackground = it }
            clipChildren = false
            addView(scroll)
            addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Metrics.SCRIM_MIN_DP)))
            addView(bottomScrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Metrics.NAV_CLEARANCE_DP), Gravity.BOTTOM))
            addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topInset = sys.top
            bottomInset = sys.bottom
            applyScrollPadding()
            scrim.layoutParams = scrim.layoutParams.apply { height = sys.top + dp(Metrics.SCRIM_FADE_DP) }
            bottomScrim.layoutParams = bottomScrim.layoutParams.apply { height = bars.bottom + dp(Metrics.NAV_CLEARANCE_DP) }
            // The bar floats above the gesture area; the surface reserves shadow room, so pull it in by that much.
            bar.layoutParams = (bar.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = bars.bottom + dp(Metrics.NAV_MARGIN_DP) - barPad.bottom
            }
            insets
        }
        setContentView(root)
        applySystemBars()
        showPage(tab, animate = false)
        (screenBackground as? Animatable)?.start()
        return root
    }

    /** Dark icons on a light screen, light icons on a dark one. Set once the window has its content, or it is lost. */
    private fun applySystemBars() {
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.isAppearanceLightStatusBars = !skin.dark
        bars.isAppearanceLightNavigationBars = !skin.dark
    }

    override fun onResume() {
        super.onResume()
        statusRows.forEach { it() }
        applySystemBars()
        (screenBackground as? Animatable)?.start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }

    override fun onPause() {
        (screenBackground as? Animatable)?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        splash?.dismissNow()
        overlay.dismiss()
        super.onDestroy()
    }

    /** Switches the whole screen to [style], staying on the same tab and scroll position. */
    private fun applyStyle(style: UiStyle) {
        if (style == skin.style) return
        prefs.uiStyle = style
        rebuild(style)
    }

    /** Builds the screen again in [style] (the current one by default), on the same tab and scroll position. */
    private fun rebuild(style: UiStyle = skin.style) {
        scrollOffsets[tab] = scroll.scrollY
        (screenBackground as? Animatable)?.stop()
        skin = Skins.of(style, this, density)
        val root = buildUi()
        if (!prefs.reduceAnimations) {
            root.alpha = 0f
            root.animate().alpha(1f).setDuration(STYLE_FADE_MS).start()
        }
        revealCards()
        // Show a sample capsule on the phone, so the new style is seen on the capsule too.
        changed()
    }

    // --- tabs ---

    /** Fills the screen with tab [index], remembering where the one before was scrolled to. */
    private fun showPage(index: Int, animate: Boolean = true) {
        if (!inSub && scroll.childCount > 0) scrollOffsets[tab] = scroll.scrollY
        inSub = false
        backCallback.isEnabled = false
        tab = index
        nav?.visibility = View.VISIBLE
        navScrim?.visibility = View.VISIBLE
        applyScrollPadding()
        fill(animate, scrollOffsets[index]) { pages[index].build(this) }
        styleNav()
    }

    /** Opens a drill-down page (with a back arrow, and no bar) on top of the current tab. */
    private fun openSub(title: String, build: LinearLayout.() -> Unit) {
        if (!inSub) scrollOffsets[tab] = scroll.scrollY
        inSub = true
        backCallback.isEnabled = true
        nav?.visibility = View.GONE
        navScrim?.visibility = View.GONE
        applyScrollPadding()
        fill(animate = true, offset = 0) {
            addView(subHeader(title))
            build()
        }
    }

    private fun closeSub() {
        if (inSub) showPage(tab)
    }

    /** Replaces the screen's content with whatever [build] adds, then scrolls to [offset]. */
    private fun fill(animate: Boolean, offset: Int, build: LinearLayout.() -> Unit) {
        statusRows.clear()
        pendingReveal.clear()
        previewHolder = null
        cardIndex = 0

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(Metrics.SIDE_DP), dp(16), dp(Metrics.SIDE_DP), dp(24))
        }
        column.build()
        scroll.removeAllViews()
        scroll.addView(column)
        statusRows.forEach { it() }
        refreshPreview()
        scroll.post { scroll.scrollTo(0, offset) }
        if (animate) {
            if (!prefs.reduceAnimations) {
                scroll.alpha = 0f
                scroll.animate().alpha(1f).setDuration(PAGE_FADE_MS).start()
            }
            revealCards()
        }
    }

    /** The scroll area leaves room for the floating bar while it shows, and only a little when it is hidden. */
    private fun applyScrollPadding() {
        val clearance = if (inSub) dp(Metrics.SUB_CLEARANCE_DP) else dp(Metrics.NAV_CLEARANCE_DP)
        scroll.updatePadding(top = topInset, bottom = bottomInset + clearance)
    }

    /** The top of a drill-down page: a back button and the page's title. */
    private fun subHeader(title: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        setPadding(0, dp(4), 0, dp(12))
        addView(iconTile(R.drawable.ic_arrow_back).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Back"
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                closeSub()
            }
        })
        addView(text(title, 26f, skin.screenText, bold = true, role = TextRole.HEADLINE, onScreen = true))
    }

    /** The floating bar: a surface in the current style holding one button per tab. */
    private fun navBar(): LinearLayout {
        navItems.clear()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            skinBackground(skin.card(NAV_CARD_INDEX), dp(8), dp(8), dp(8), dp(8))
        }
        val indicator = paddingOf(skin.chip(true))
        pages.forEachIndexed { i, page ->
            val icon = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = page.label
            }
            val item = FrameLayout(this).apply {
                isClickable = true
                isFocusable = true
                addView(icon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                setOnClickListener {
                    if (i == tab) return@setOnClickListener
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    showPage(i)
                }
            }
            navItems += item to icon
            bar.addView(item, LinearLayout.LayoutParams(
                dp(NAV_ITEM_W_DP) + indicator.left + indicator.right,
                dp(NAV_ITEM_H_DP) + indicator.top + indicator.bottom,
            ).apply { if (i > 0) marginStart = dp(NAV_GAP_DP) - indicator.left - indicator.right })
        }
        return bar
    }

    /** Highlights the current tab's button: the style's selected indicator behind a filled icon. */
    private fun styleNav() {
        navItems.forEachIndexed { i, (item, icon) ->
            val on = i == tab
            item.background = if (on) skin.chip(true) else null
            icon.setImageResource(if (on) pages[i].iconSelected else pages[i].icon)
            icon.setColorFilter(if (on) skin.chipText(true) else skin.textDim)
        }
    }

    // --- tab: Capsule ---

    private fun LinearLayout.capsulePage() {
        addView(brandHeader())
        section("Setup") {
            // Shown only while the other capsule app is enabled: both draw a pill over the camera, and the one that is on
            // top takes the taps, which can open its action (even the notification shade) instead of this deck.
            addRow(statusRow(R.drawable.ic_warning, "Material Capsule is also on",
                "Its pill sits on the same spot and takes taps meant for this one. Turn it off under Accessibility.",
                optional = false,
                isOn = { !otherCapsuleAppEnabled() },
                open = { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                hideWhenOk = true,
                badLabel = "Conflict"))
            addRow(statusRow(R.drawable.ic_layers, "Display over other apps", "Needed to draw the capsule", optional = false,
                isOn = { Settings.canDrawOverlays(this@MainActivity) },
                open = { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")) }))
            addRow(statusRow(R.drawable.ic_notifications, "Notification access", "Needed to read notifications and music", optional = false,
                isOn = {
                    Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                        ?.contains(ComponentName(this@MainActivity, CapsuleService::class.java).flattenToString()) == true
                },
                open = { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }))
            addRow(statusRow(R.drawable.ic_battery_alert, "Allowed to run in the background",
                "Needed so the system does not stop the capsule when the app is not open", optional = false,
                isOn = { getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) },
                open = { Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) },
                badLabel = "May be stopped in the background"))
            addRow(statusRow(R.drawable.ic_accessibility_new, "Draw above the status bar", "Optional. Also lets the Clipboard card record copies and paste into a field", optional = true,
                isOn = { OverlayHost.isAboveStatusBar },
                open = { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) }))
        }
        // The capsules, grouped by what they are about, with the sub-pages next to the group they belong to.
        section("Media and tools") {
            listOf(Kind.MEDIA, Kind.VOLUME, Kind.FLASHLIGHT, Kind.TIMER, Kind.STOPWATCH, Kind.BARCODE).forEach { kind ->
                addRow(switchRow(kindIcon(kind), kind.label, kindHint(kind), prefs.enabled(kind)) { prefs.setEnabled(kind, it) })
            }
        }
        section("Apps and calls") {
            listOf(Kind.MESSAGE, Kind.PROGRESS, Kind.NAVIGATION, Kind.CALL).forEach { kind ->
                addRow(switchRow(kindIcon(kind), kind.label, kindHint(kind), prefs.enabled(kind)) { prefs.setEnabled(kind, it) })
            }
            addRow(actionRow(R.drawable.ic_notifications, "Notification filters", "Which apps and kinds of notification show a capsule") {
                openSub("Notification filters") { notificationFiltersPage() }
            })
            addRow(actionRow(R.drawable.ic_chat, "Notification look", "Icon shape, chat layout, Seen and Reply buttons") {
                openSub("Notification look") { notificationLookPage() }
            })
        }
        section("Cards and phone events") {
            addRow(actionRow(R.drawable.ic_style, "Dynamic cards", "Which cards open, and with which gesture") {
                openSub("Dynamic cards") { dynamicCardsPage() }
            })
            addRow(actionRow(kindIcon(Kind.SYSTEM), "System events", kindHint(Kind.SYSTEM)) {
                openSub("System events") { systemEventsPage() }
            })
        }
        section("Start a tool") {
            addRow(timerRow())
            addRow(stopwatchRow())
            addRow(barcodeRow())
        }
    }

    private fun kindIcon(kind: Kind) = when (kind) {
        Kind.MEDIA -> R.drawable.ic_music_note
        Kind.VOLUME -> R.drawable.ic_volume_up
        Kind.MESSAGE -> R.drawable.ic_chat
        Kind.SYSTEM -> R.drawable.ic_tune
        Kind.FLASHLIGHT -> R.drawable.ic_flashlight_on
        Kind.TIMER -> R.drawable.ic_timer
        Kind.STOPWATCH -> R.drawable.ic_schedule
        Kind.BARCODE -> R.drawable.ic_qr_code_2
        Kind.CLIPBOARD -> R.drawable.ic_content_paste
        Kind.NOTES -> R.drawable.ic_edit_note
        Kind.BATTERY -> R.drawable.ic_battery_full
        Kind.STATS -> R.drawable.ic_memory
        Kind.CALENDAR -> R.drawable.ic_event
        Kind.LAUNCHER -> R.drawable.ic_apps
        Kind.POMODORO -> R.drawable.ic_timer
        Kind.PROGRESS -> R.drawable.ic_download
        Kind.NAVIGATION -> R.drawable.ic_navigation
        Kind.CALL -> R.drawable.ic_call
    }

    private fun kindHint(kind: Kind) = when (kind) {
        Kind.MEDIA -> "Track, artist and cover art"
        Kind.VOLUME -> "When you change the volume while music plays"
        Kind.MESSAGE -> "Notifications from your apps"
        Kind.SYSTEM -> "Battery, Wi-Fi, Bluetooth, sound and more"
        Kind.FLASHLIGHT -> "When the torch is on"
        Kind.TIMER -> "A running countdown"
        Kind.STOPWATCH -> "A running stopwatch"
        Kind.BARCODE -> "Your saved pass or code"
        Kind.CLIPBOARD -> "What you copied lately"
        Kind.NOTES -> "Quick notes"
        Kind.BATTERY -> "Battery level and state"
        Kind.STATS -> "Memory, storage and more"
        Kind.CALENDAR -> "Your next event"
        Kind.LAUNCHER -> "Your chosen apps"
        Kind.POMODORO -> "When a focus part ends and a break starts"
        Kind.PROGRESS -> "Downloads and other progress"
        Kind.NAVIGATION -> "Directions while you travel"
        Kind.CALL -> "An ongoing call"
    }

    private fun timerRow(): View {
        val minutes = field("Minutes", DEFAULT_TIMER_MINUTES.toString(), InputType.TYPE_CLASS_NUMBER)
        return controlRow(R.drawable.ic_timer, "Timer", "Counts down in the capsule. Pause or add a minute from it.",
            minutes,
            button("Start timer", filled = true) {
                val m = minutes.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: return@button
                sendBroadcast(TimerSource.startIntent(this@MainActivity, m * SECONDS_PER_MINUTE))
            })
    }

    private fun stopwatchRow(): View =
        controlRow(R.drawable.ic_schedule, "Stopwatch", "Counts up in the capsule. Pause or reset it there, and swipe it away to stop.",
            button("Start stopwatch", filled = true) { sendBroadcast(StopwatchSource.startIntent(this@MainActivity)) })

    private fun barcodeRow(): View {
        val saved = prefs.barcode
        var type = saved?.type ?: BarcodeType.QR
        val label = field("Label, e.g. Gym pass", saved?.label.orEmpty())
        val content = field("Number or text to encode", saved?.content.orEmpty())
        return controlRow(R.drawable.ic_qr_code_2, "Barcode", "Keep a pass or code one tap away.",
            label,
            content,
            chips(BarcodeType.entries, type, BarcodeType::label) { type = it },
            button("Save barcode", filled = true) {
                val value = content.text.toString().trim()
                if (BarcodeEncoder.bitmap(value, type) == null) {
                    Toast.makeText(this@MainActivity, "That content isn't valid for ${type.label}", Toast.LENGTH_LONG).show()
                } else {
                    prefs.barcode = SavedBarcode(label.text.toString().trim(), value, type)
                    Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
                }
            },
            button("Show on capsule", filled = false) { sendBroadcast(BarcodeSource.showIntent(this@MainActivity)) })
    }

    // --- Notification look ---

    private fun LinearLayout.notificationLookPage() {
        section("Icon") {
            addRow(controlRow(R.drawable.ic_rounded_corner, "Icon shape", "The shape of the app icon or avatar on a notification capsule",
                iconShapeTiles()))
        }
        section("Chat notifications") {
            addRow(switchRow(R.drawable.ic_chat, "Conversation layout",
                "Shows who wrote, their newest message and their avatar, instead of the app's name and icon",
                prefs.conversationLayout, after = {}) { prefs.conversationLayout = it })
            addRow(switchRow(R.drawable.ic_check_circle, "Seen and Reply buttons",
                "Shows the buttons a chat notification offers. Reply opens a small box to type in.",
                prefs.notificationButtons, after = {}) { prefs.notificationButtons = it })
        }
        section("Try it") {
            addRow(controlRow(R.drawable.ic_notifications, "Send a test message",
                "Posts a chat notification from this app, with a Seen and a Reply button, so you can see the look. Seen and Reply really work on it.",
                button("Send test message", filled = true) { sendTestMessage() }))
        }
    }

    private fun sendTestMessage() {
        val needsPermission = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TestMessage.post(this)
        }
    }

    /** The icon shapes as tiles that each show the app's icon cut to that shape; choosing one shows it on a sample capsule. */
    private fun iconShapeTiles(): View {
        val tiles = mutableListOf<Pair<IconShape, View>>()
        fun restyle(current: IconShape) = tiles.forEach { (shape, box) ->
            val on = shape == current
            box.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Fx.withAlpha(skin.text, 0.06f))
                setStroke(dp(if (on) 3 else 1), if (on) skin.accent else skin.hairline)
            }
        }
        val group = ChipGroup(this).apply {
            chipSpacingHorizontal = dp(10)
            chipSpacingVertical = dp(10)
            clipChildren = false
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        IconShape.entries.forEach { shape ->
            val icon = ImageView(this).apply { setImageDrawable(packageManager.getApplicationIcon(packageName)) }
            shape.applyTo(icon, 14)
            val box = FrameLayout(this).apply {
                addView(icon, FrameLayout.LayoutParams(dp(SHAPE_ICON_DP), dp(SHAPE_ICON_DP), Gravity.CENTER))
            }
            tiles += shape to box
            group.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                isFocusable = true
                contentDescription = shape.label
                addView(box, LinearLayout.LayoutParams(dp(SHAPE_TILE_DP), dp(SHAPE_TILE_DP)))
                addView(text(shape.label, 12f, skin.text, role = TextRole.CAPTION).apply { setPadding(0, dp(6), 0, 0); gravity = Gravity.CENTER })
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    prefs.iconShape = shape
                    restyle(shape)
                    changed()
                }
            }, ViewGroup.MarginLayoutParams(dp(SHAPE_TILE_DP), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        restyle(prefs.iconShape)
        return group
    }

    // --- Notification filters ---

    /** An app that can be launched, as the filters screen lists it. */
    private class AppEntry(val pkg: String, val label: String, val icon: Drawable)

    /**
     * Which notifications may show a capsule: by kind (silent, ongoing) and by app. Apps without a choice of their own
     * follow the default (allow all, or block all), so the same list works as a block list and as an allow list.
     */
    private fun LinearLayout.notificationFiltersPage() {
        section("Kinds of notification") {
            addRow(switchRow(R.drawable.ic_volume_off, "Silent notifications", "Messages that make no sound",
                prefs.showSilentNotifications, after = {}) { prefs.showSilentNotifications = it })
            addRow(switchRow(R.drawable.ic_download, "Ongoing notifications", "Downloads and navigation. Calls always show.",
                prefs.showOngoingNotifications, after = {}) { prefs.showOngoingNotifications = it })
        }
        section("Apps") {
            val list = this
            val toggles = mutableListOf<Pair<String, SkinSwitch>>()
            val rows = mutableListOf<Pair<String, View>>()

            addRow(controlRow(R.drawable.ic_notifications, "Apps you have not chosen", "What happens to an app that is not switched on or off below",
                chips(AppPolicy.entries, prefs.notificationDefault, AppPolicy::label, quiet = true) { policy ->
                    prefs.notificationDefault = policy
                    // The switches of apps without a choice follow the default, so they move with it.
                    toggles.forEach { (pkg, toggle) -> toggle.setChecked(prefs.notificationAllowed(pkg)) }
                }))

            val search = field("Search apps", "")
            search.doOnTextChanged { text, _, _, _ ->
                val query = text?.toString().orEmpty().trim().lowercase()
                rows.forEach { (name, row) -> row.visibility = if (query.isEmpty() || query in name) View.VISIBLE else View.GONE }
            }
            list.addView(search)

            val loading = text("Loading apps...", 13f, skin.textDim, role = TextRole.CAPTION).apply { setPadding(0, dp(16), 0, 0) }
            list.addView(loading)
            loadApps { apps ->
                list.removeView(loading)
                apps.forEach { app ->
                    val row = appRow(app, toggles)
                    rows += app.label.lowercase() to row
                    list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = dp(APP_ROW_GAP_DP) })
                }
            }
        }
    }

    /** One app: its icon, its name and a switch for whether its notifications may show a capsule. */
    private fun appRow(app: AppEntry, toggles: MutableList<Pair<String, SkinSwitch>>): View {
        val toggle = SkinSwitch(this, skin, prefs.notificationAllowed(app.pkg)) { prefs.setNotificationAllowed(app.pkg, it) }
            .apply { contentDescription = app.label }
        toggles += app.pkg to toggle
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(ImageView(context).apply { setImageDrawable(app.icon) },
                LinearLayout.LayoutParams(dp(TILE_DP), dp(TILE_DP)).apply { marginEnd = dp(14) })
            addView(text(app.label, 15f, skin.text, bold = true, role = TextRole.LABEL),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle)
        }
    }

    /** Finds the apps that can be launched, off the main thread (there are over a hundred), and hands them over sorted by name. */
    private fun loadApps(onLoaded: (List<AppEntry>) -> Unit) {
        Thread {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = packageManager.queryIntentActivities(launcher, 0)
                .filter { it.activityInfo.packageName != packageName }
                .distinctBy { it.activityInfo.packageName }
                .map { AppEntry(it.activityInfo.packageName, it.loadLabel(packageManager).toString(), it.loadIcon(packageManager)) }
                .sortedBy { it.label.lowercase() }
            runOnUiThread { if (!isDestroyed) onLoaded(apps) }
        }.start()
    }

    // --- Dynamic cards ---

    /** Which cards the expanded view may hold, and which gesture opens a card that is live. */
    private fun LinearLayout.dynamicCardsPage() {
        section("Cards") {
            DeckCard.entries.forEach { card ->
                addRow(switchRow(card.icon, card.label, card.hint, prefs.cardEnabled(card), after = {}) { prefs.setCardEnabled(card, it) })
            }
        }
        section("Order") {
            val list = this
            fun render() {
                list.removeAllViews()
                val order = prefs.cardOrder
                order.forEachIndexed { i, card ->
                    fun move(by: Int) {
                        prefs.cardOrder = order.toMutableList().apply { add(i + by, removeAt(i)) }
                        render()
                    }
                    val arrows = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        if (i > 0) addView(orderArrow("▲", "Move ${card.label} up") { move(-1) })
                        if (i < order.lastIndex) addView(orderArrow("▼", "Move ${card.label} down") { move(1) })
                    }
                    list.addRow(headRow(card.icon, card.label, "Position ${i + 1} in the deck", arrows).root)
                }
            }
            render()
        }
        section("Clipboard") {
            addRow(switchRow(R.drawable.ic_content_paste, "Remember what I copy",
                "Needs the accessibility service. Keeps the last 30 copies on this phone only. Anything an app marks as sensitive, like a password, is skipped.",
                prefs.clipboardRecording, after = {}) { prefs.clipboardRecording = it })
            addRow(actionRow(R.drawable.ic_close, "Clear clipboard history", "Forget everything copied so far") {
                ClipboardHistory.clear(this@MainActivity)
                Toast.makeText(this@MainActivity, "Clipboard history cleared", Toast.LENGTH_SHORT).show()
            })
        }
        section("Calendar and launcher") {
            addRow(actionRow(R.drawable.ic_event, "Calendar access", "The Calendar card reads your events to show the next one. Nothing is changed or sent.") {
                if (CalendarAccess.granted(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Calendar is already allowed", Toast.LENGTH_SHORT).show()
                } else {
                    calendarPermission.launch(android.Manifest.permission.READ_CALENDAR)
                }
            })
            addRow(actionRow(R.drawable.ic_apps, "Quick launcher apps", "Choose up to ${Prefs.MAX_LAUNCHER_APPS} apps for the launcher card") {
                openSub("Quick launcher apps") { launcherAppsPage() }
            })
        }
        section("Opening a card") {
            addRow(controlRow(R.drawable.ic_touch_app, "Open a live card with",
                "While music plays, a timer or stopwatch runs, the torch is on or the volume is in use, this gesture on the pill opens that card. The other gestures keep their own action.",
                chips(DYNAMIC_CARD_GESTURES, prefs.dynamicCardGesture, Gesture::label) { prefs.dynamicCardGesture = it }))
            addRow(headRow(R.drawable.ic_info, "Other gestures", "Single tap, double tap, long press and the swipes are set under Customize, Gestures.").root)
        }
    }

    /** A small arrow that moves a card one place in the deck order. */
    private fun orderArrow(symbol: String, description: String, onClick: () -> Unit) =
        text(symbol, 16f, skin.textDim, bold = true).apply {
            gravity = Gravity.CENTER
            contentDescription = description
            isClickable = true
            isFocusable = true
            minWidth = dp(44)
            minHeight = dp(44)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
        }

    /** Which apps the quick launcher card shows: a switch per app, in the order they are switched on, up to a limit. */
    private fun LinearLayout.launcherAppsPage() {
        section("Apps") {
            val list = this
            val rows = mutableListOf<Pair<String, View>>()

            val search = field("Search apps", "")
            search.doOnTextChanged { text, _, _, _ ->
                val query = text?.toString().orEmpty().trim().lowercase()
                rows.forEach { (name, row) -> row.visibility = if (query.isEmpty() || query in name) View.VISIBLE else View.GONE }
            }
            list.addView(search)

            val loading = text("Loading apps...", 13f, skin.textDim, role = TextRole.CAPTION).apply { setPadding(0, dp(16), 0, 0) }
            list.addView(loading)
            loadApps { apps ->
                list.removeView(loading)
                apps.forEach { app ->
                    lateinit var toggle: SkinSwitch
                    toggle = SkinSwitch(this@MainActivity, skin, app.pkg in prefs.launcherApps) { on ->
                        val chosen = prefs.launcherApps
                        when {
                            !on -> prefs.launcherApps = chosen - app.pkg
                            app.pkg in chosen -> Unit
                            chosen.size >= Prefs.MAX_LAUNCHER_APPS -> {
                                Toast.makeText(this@MainActivity, "Up to ${Prefs.MAX_LAUNCHER_APPS} apps", Toast.LENGTH_SHORT).show()
                                toggle.setChecked(false)
                            }
                            else -> prefs.launcherApps = chosen + app.pkg
                        }
                    }.apply { contentDescription = app.label }
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        clipChildren = false
                        addView(ImageView(context).apply { setImageDrawable(app.icon) },
                            LinearLayout.LayoutParams(dp(TILE_DP), dp(TILE_DP)).apply { marginEnd = dp(14) })
                        addView(text(app.label, 15f, skin.text, bold = true, role = TextRole.LABEL),
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(toggle)
                    }
                    rows += app.label.lowercase() to row
                    list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = dp(APP_ROW_GAP_DP) })
                }
            }
        }
    }

    // --- System events ---

    /** One switch per phone event, under the headings in [SystemGroup], with a master switch on top. */
    private fun LinearLayout.systemEventsPage() {
        section("General") {
            addRow(switchRow(R.drawable.ic_tune, "System events", "Show a capsule for the events below", prefs.enabled(Kind.SYSTEM)) {
                prefs.setEnabled(Kind.SYSTEM, it)
            })
        }
        section("Banner") {
            addRow(switchRow(R.drawable.ic_close, "Stay until swiped up",
                "A banner stays on screen until you swipe it up, so a change is not missed. The flashlight's stays while the torch is on. Charging and unlocking are only a passing note.",
                prefs.bannersSticky, after = {}) { prefs.bannersSticky = it })
            addRow(switchRow(R.drawable.ic_settings, "Settings button",
                "Adds a Settings button that opens the page for what changed (Wi-Fi, Bluetooth, the hotspot and so on). Tapping the banner does the same.",
                prefs.bannerSettingsButton, after = {}) { prefs.bannerSettingsButton = it })
        }
        SystemGroup.entries.forEach { group ->
            section(group.label) {
                SystemEvent.entries.filter { it.group == group }.forEach { event ->
                    addRow(switchRow(event.icon, event.label, event.hint, prefs.systemEnabled(event), after = { on ->
                        // Turning one on shows what it looks like.
                        if (on) previewEvent(event)
                    }) { prefs.setSystemEnabled(event, it) })
                }
                if (group == SystemGroup.BATTERY) {
                    addRow(controlRow(R.drawable.ic_battery_alert, "Low battery level", "The low battery capsule shows at or below this",
                        chips(Prefs.LOW_BATTERY_CHOICES, prefs.lowBatteryPercent, { "$it%" }, onPick = { prefs.lowBatteryPercent = it })))
                }
            }
        }
    }

    /** Shows a sample of [event] on the phone, drawn the way the real one will be. */
    private fun previewEvent(event: SystemEvent) {
        if (!OverlayHost.canDraw(this)) return
        overlay.show(CapsuleEvent(
            kind = Kind.SYSTEM,
            title = event.label,
            text = "Sample",
            icon = getDrawable(event.icon),
            system = event,
        ))
    }

    // --- tab: Customize ---

    private fun LinearLayout.customizePage() {
        addView(pageTitle("Customize", "Make the capsule yours"))
        section("Colors") {
            val now = if (isNight(applicationContext)) "Dark theme color (in use now)" else "Dark theme color"
            val nowLight = if (isNight(applicationContext)) "Light theme color" else "Light theme color (in use now)"
            addRow(controlRow(R.drawable.ic_palette, now, "The capsule's color while the phone is in its dark theme",
                swatches(Swatch.entries, prefs.swatch, Swatch::color, Swatch::label, custom = CustomColor(
                    saved = { prefs.customColorDark }, active = { prefs.useCustomDark },
                    use = { prefs.customColorDark = it; prefs.useCustomDark = true },
                    clear = { prefs.useCustomDark = false },
                )) { prefs.swatch = it }))
            addRow(controlRow(R.drawable.ic_opacity, nowLight, "The color in the light theme. Light colors get dark text.",
                swatches(Swatch.entries, prefs.swatchLight, Swatch::color, Swatch::label, custom = CustomColor(
                    saved = { prefs.customColorLight }, active = { prefs.useCustomLight },
                    use = { prefs.customColorLight = it; prefs.useCustomLight = true },
                    clear = { prefs.useCustomLight = false },
                )) { prefs.swatchLight = it }))
            addRow(switchRow(R.drawable.ic_format_color_text, "Automatic text color",
                "Light or dark text to suit the capsule's color. Turn it off to choose your own below.", prefs.textAuto, after = {
                    // The swatches below show a selection only while this is off, so the screen is built again.
                    window.decorView.post { rebuild() }
                }) { prefs.textAuto = it })
            addRow(controlRow(R.drawable.ic_format_color_text, "Text color",
                "For the text, icons and buttons on the capsule, in the brief capsules and in the expanded view",
                swatches(TextPreset.entries, prefs.textPreset, TextPreset::color, TextPreset::label, custom = CustomColor(
                    forText = true,
                    saved = { prefs.customTextColor }, active = { prefs.useCustomText && !prefs.textAuto },
                    use = { prefs.customTextColor = it; prefs.useCustomText = true; prefs.textAuto = false },
                    clear = { prefs.useCustomText = false },
                ), suppress = { prefs.textAuto }) {
                    prefs.textPreset = it
                    prefs.textAuto = false
                }))
            addRow(switchRow(R.drawable.ic_album, "Album-art colors for music", "Music capsules take their color from the cover", prefs.coverColors) { prefs.coverColors = it })
        }
        section("Shape and border") {
            addRow(controlRow(R.drawable.ic_rounded_corner, "Shape", "How round the corners are",
                chips(Shape.entries, prefs.shape, Shape::label) { prefs.shape = it }))
            addRow(controlRow(R.drawable.ic_border_style, "Border", "Thickness and color of the outline",
                chips(BorderWidth.entries, prefs.borderWidth, BorderWidth::label) { prefs.borderWidth = it },
                swatches(BorderColor.entries, prefs.borderColor, BorderColor::color, BorderColor::label, custom = CustomColor(
                    saved = { prefs.customBorderColor }, active = { prefs.useCustomBorder },
                    use = { prefs.customBorderColor = it; prefs.useCustomBorder = true },
                    clear = { prefs.useCustomBorder = false },
                )) { prefs.borderColor = it }))
            addRow(controlRow(R.drawable.ic_blur_on, "Shadow", "How strongly the capsule lifts off the screen",
                chips(Shadow.entries, prefs.shadow, Shadow::label) { prefs.shadow = it }))
        }
        section("Position") {
            val capsule = this@MainActivity.overlay
            // What is changed here belongs to the kind of screen the phone is in right now (portrait, landscape and so on).
            val profile = ScreenProfile.of(this@MainActivity)
            val place = prefs.position(profile)
            addRow(actionRow(R.drawable.ic_open_with, "Move the capsule", "Drag it where you want it on this screen: ${profile.label.lowercase()}") {
                positionEditor.launch(android.content.Intent(this@MainActivity, PositionEditorActivity::class.java))
            })
            addRow(controlRow(R.drawable.ic_open_with, "Side of the screen", "Where the capsule starts from. Dragging sets this to the middle.",
                chips(Align.entries, place.align, Align::label) { choice ->
                    prefs.updatePosition(profile) { it.copy(align = choice, offsetXDp = 0) }
                }))
            addRow(sliderRow(R.drawable.ic_height, "Distance from the top", capsule.deckTopDp(), place.topDp ?: capsule.defaultTopDp(), { "$it dp" }) { value ->
                prefs.updatePosition(profile) { it.copy(topDp = value) }
            })
            addRow(sliderRow(
                R.drawable.ic_circle, "Idle pill size",
                (Position.PILL_MAX - Position.PILL_MIN) / Position.PILL_STEP,
                (place.pillPercent - Position.PILL_MIN) / Position.PILL_STEP,
                { "${Position.PILL_MIN + it * Position.PILL_STEP}%" },
            ) { step -> prefs.updatePosition(profile) { it.copy(pillPercent = Position.PILL_MIN + step * Position.PILL_STEP) } })
        }
        section("Screen profiles") {
            val current = ScreenProfile.of(this@MainActivity)
            ScreenProfile.entries.forEach { profile ->
                val title = if (profile == current) "${profile.label} (this screen)" else profile.label
                addRow(headRow(profileIcon(profile), title, profileSummary(profile)).root)
            }
            addRow(actionRow(R.drawable.ic_restart_alt, "Reset this screen's position",
                "Go back to the settings that apply to every screen: ${current.label.lowercase()}") {
                prefs.resetPosition(current)
                rebuild()
            })
        }
        section("Expanded view") {
            addRow(sliderRow(R.drawable.ic_unfold_more, "Gap below the pill", Prefs.MAX_DECK_GAP_PERCENT, prefs.deckGapPercent, { "$it%" }) {
                prefs.deckGapPercent = it
            })
            // Counted from 0 so the slider starts at its left end; shown and stored as real seconds.
            addRow(sliderRow(R.drawable.ic_schedule, "Closes by itself after", Prefs.MAX_DECK_SECONDS - Prefs.MIN_DECK_SECONDS,
                prefs.deckSeconds - Prefs.MIN_DECK_SECONDS, { "${it + Prefs.MIN_DECK_SECONDS} s" }) {
                prefs.deckSeconds = it + Prefs.MIN_DECK_SECONDS
            })
            addRow(switchRow(R.drawable.ic_touch_app, "Close when tapping outside", "A tap anywhere else on the screen closes it, like the timer does", prefs.closeOnOutsideTap) {
                prefs.closeOnOutsideTap = it
            })
            addRow(switchRow(R.drawable.ic_height, "Same height for every card", "Cards keep their size as you swipe between them", prefs.uniformDeck) {
                prefs.uniformDeck = it
            })
        }
        section("Idle pill") {
            addRow(switchRow(R.drawable.ic_circle, "Show the pill", "A small pill over the camera. Tap it to expand your cards.", prefs.idleEnabled) { prefs.idleEnabled = it })
            addRow(switchRow(R.drawable.ic_stay_current_landscape, "Hide the pill in landscape",
                "Leaves the idle pill out while the phone is on its side. Capsules still appear.", prefs.hideIdleInLandscape, after = {}) {
                prefs.hideIdleInLandscape = it
            })
            addRow(controlRow(R.drawable.ic_opacity, "Opacity", "At 0% it blends into the camera but still takes touches",
                chips(IdleOpacity.entries, prefs.idleOpacity, IdleOpacity::label) { prefs.idleOpacity = it }))
        }
        section("Gestures") {
            Gesture.entries.forEach { gesture ->
                addRow(controlRow(gestureIcon(gesture), gesture.label, if (gesture == Gesture.SINGLE_TAP) "Set to Nothing and the pill will not open when tapped" else null,
                    chips(GestureAction.entries, prefs.action(gesture), GestureAction::label) { prefs.setAction(gesture, it) }))
            }
        }
        section("Animation") {
            addRow(expandStyleRow())
            addRow(sliderRow(
                R.drawable.ic_speed, "Animation duration",
                (Motions.MAX_PERCENT - Motions.MIN_PERCENT) / Motions.STEP_PERCENT,
                (prefs.animationDurationPercent - Motions.MIN_PERCENT) / Motions.STEP_PERCENT,
                { percentText(Motions.MIN_PERCENT + it * Motions.STEP_PERCENT) },
            ) { prefs.animationDurationPercent = Motions.MIN_PERCENT + it * Motions.STEP_PERCENT })
            addRow(controlRow(R.drawable.ic_swap_vert, "Bounce", "How far it overshoots. Smooth and Fade never bounce.",
                chips(BounceLevel.entries, prefs.bounce, BounceLevel::label) { prefs.bounce = it }))
            addRow(switchRow(R.drawable.ic_slow_motion_video, "Reduce animations",
                "Capsules fade in place and this app stops its moving effects. Helps on slower phones, and overrides the settings above.",
                prefs.reduceAnimations, after = {
                    // The glass backgrounds drift only while this is off, so the screen is built again.
                    window.decorView.post { rebuild() }
                }) { prefs.reduceAnimations = it })
        }
        section("Timing and feedback") {
            addRow(controlRow(R.drawable.ic_timer, "Display time", "How long a capsule stays before it goes",
                slider(DURATION_STEPS, durationToProgress(prefs.durationScale())) { prefs.setDurationScale(progressToDuration(it)) }))
            addRow(switchRow(R.drawable.ic_vibration, "Haptics", "A tick when opening, closing or switching cards", prefs.haptics) { prefs.haptics = it })
        }
    }

    private fun percentText(percent: Int) = if (percent == Motions.DEFAULT_PERCENT) "$percent% (normal)" else "$percent%"

    /**
     * The expand styles as tiles that each draw their own motion curve (a bounce shows as the line rising past the
     * dashed one), so a style can be judged before it is tried. Picking one plays it on a sample capsule.
     */
    private fun expandStyleRow(): View {
        val head = headRow(R.drawable.ic_animation, "Expand style", prefs.expandStyle.hint)
        val tiles = mutableListOf<Triple<ExpandStyle, View, CurveView>>()
        fun restyle(current: ExpandStyle) = tiles.forEach { (style, box, curve) ->
            val on = style == current
            box.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Fx.withAlpha(skin.text, 0.06f))
                setStroke(dp(if (on) 3 else 1), if (on) skin.accent else skin.hairline)
            }
            curve.lineColor = if (on) skin.accent else skin.textDim
            curve.invalidate()
        }
        val row = ChipGroup(this).apply {
            chipSpacingHorizontal = dp(10)
            chipSpacingVertical = dp(10)
            clipChildren = false
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        ExpandStyle.entries.forEach { style ->
            val curve = CurveView(this, Motions.curve(style), skin.textDim, skin.hairline)
            val box = FrameLayout(this).apply {
                addView(curve, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            tiles += Triple(style, box, curve)
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                isFocusable = true
                contentDescription = style.label
                addView(box, LinearLayout.LayoutParams(dp(CURVE_W_DP), dp(CURVE_H_DP)))
                addView(text(style.label, 12f, skin.text, role = TextRole.CAPTION).apply {
                    setPadding(0, dp(6), 0, 0)
                    gravity = Gravity.CENTER
                })
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    prefs.expandStyle = style
                    restyle(style)
                    head.subtitle?.text = style.hint
                    changed()
                }
            }, ViewGroup.MarginLayoutParams(dp(CURVE_W_DP), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        restyle(prefs.expandStyle)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(head.root)
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
    }

    private fun profileIcon(profile: ScreenProfile) = when (profile) {
        ScreenProfile.PORTRAIT -> R.drawable.ic_stay_current_portrait
        ScreenProfile.LANDSCAPE -> R.drawable.ic_stay_current_landscape
        ScreenProfile.PORTRAIT_WIDE, ScreenProfile.LANDSCAPE_WIDE -> R.drawable.ic_tablet_android
    }

    /** What a profile holds: its own position, or a note that it follows the settings that apply to every screen. */
    private fun profileSummary(profile: ScreenProfile): String {
        if (!prefs.hasOwnPosition(profile)) return "Not set yet: follows the settings that apply to every screen"
        val p = prefs.position(profile)
        val x = when {
            p.offsetXDp == 0 -> p.align.label.lowercase()
            p.offsetXDp > 0 -> "${p.offsetXDp} dp right"
            else -> "${-p.offsetXDp} dp left"
        }
        return "$x, ${p.topDp ?: "default"} dp from the top, pill ${p.pillPercent}%"
    }

    private fun gestureIcon(gesture: Gesture) = when (gesture) {
        Gesture.SINGLE_TAP -> R.drawable.ic_touch_app
        Gesture.LONG_PRESS -> R.drawable.ic_schedule
        Gesture.DOUBLE_TAP -> R.drawable.ic_ads_click
        Gesture.SWIPE_LEFT -> R.drawable.ic_swipe_left
        Gesture.SWIPE_RIGHT -> R.drawable.ic_swipe_right
    }

    // --- tab: App ---

    private fun LinearLayout.appPage() {
        addView(pageTitle("App", "Look, help and about"))
        section("App style") {
            val row = ChipGroup(this@MainActivity).apply {
                chipSpacingHorizontal = dp(12)
                chipSpacingVertical = dp(12)
                clipChildren = false
                setPadding(dp(2), dp(4), dp(2), dp(4))
            }
            UiStyle.entries.forEach { style -> row.addView(styleTile(style)) }
            addRow(controlRow(R.drawable.ic_style, "Style", "How this app and the capsule look. Capsule color, shape and border are under Customize.", row))
        }
        section("Appearance") {
            addRow(controlRow(R.drawable.ic_dark_mode, "Theme", "Light or dark for the Material You style. The other styles keep their own look.",
                chips(AppTheme.entries, prefs.appTheme, AppTheme::label, quiet = true) {
                    prefs.appTheme = it
                    // Changing it recreates this screen in the new theme.
                    AppCompatDelegate.setDefaultNightMode(it.nightMode)
                }))
            val fonts = chips(AppFont.entries, prefs.appFont, AppFont::label, quiet = true) {
                prefs.appFont = it
                AppFonts.current = it
                window.decorView.post { rebuild() }
            }
            // Each choice is written in its own font, so it can be judged before it is picked.
            (fonts as ViewGroup).let { group ->
                AppFont.entries.forEachIndexed { i, font -> (group.getChildAt(i) as? TextView)?.typeface = font.typeface(false) }
            }
            addRow(controlRow(R.drawable.ic_text_fields, "Font", "The typeface of this app's screens. The capsule keeps its own.", fonts))
            addRow(actionRow(R.drawable.ic_language, "Language", "English is the only language so far. Opens Android's page for this app's language.") { openLanguageSettings() })
        }
        section("Help") {
            addRow(actionRow(R.drawable.ic_lightbulb, "Tips", "Getting more out of it, and what to do when something stops") {
                openSub("Tips") { infoPage(Tips.items) }
            })
            addRow(actionRow(R.drawable.ic_battery_alert, "Battery optimisation", "Let the capsule keep running in the background") {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            })
            addRow(actionRow(R.drawable.ic_accessibility_new, "Accessibility settings", "Turn the status-bar overlay on or off") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            addRow(actionRow(R.drawable.ic_notifications, "Notification access", "Let the capsule read notifications and music") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
        }
        section("Reset") {
            addRow(actionRow(R.drawable.ic_restart_alt, "Reset all settings", "Return every setting to its default. Your saved barcode is kept.") { confirmReset() })
        }
        section("About") {
            addRow(headRow(R.drawable.ic_info, "openCapsule", "A dynamic island for your Android phone. Open source, and independent of any other capsule app.").root)
            addRow(actionRow(R.drawable.ic_new_releases, "What's new", "Version ${versionName()}") {
                openSub("What's new") { infoPage(ReleaseNotes.items) }
            })
            addRow(actionRow(R.drawable.ic_code, "Credits and licenses", "Libraries and icons this app uses") { showCredits() })
        }
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""

    /** A list of titled paragraphs: the What's new and Tips screens. */
    private fun LinearLayout.infoPage(items: List<InfoItem>) {
        section(if (items === Tips.items) "Tips" else "Version ${versionName()}") {
            items.forEach { addRow(headRow(it.icon, it.title, it.text).root) }
        }
    }

    /** Android's own page for choosing this app's language, or the phone's language page on versions before it. */
    private fun openLanguageSettings() {
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_LOCALE_SETTINGS)
        }
        runCatching { startActivity(intent) }
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset all settings?")
            .setMessage("This restores the default theme, gestures, positions, switches and app style. Your saved barcode stays.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset") { _, _ ->
                prefs.resetAll()
                recreate()
            }
            .show()
    }

    private fun showCredits() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Credits and licenses")
            .setMessage(
                "Icons: Material Symbols by Google, Apache License 2.0.\n\n" +
                    "ZXing core, Apache License 2.0, encodes the barcodes.\n\n" +
                    "AndroidX and Material Components for Android, Apache License 2.0.",
            )
            .setPositiveButton("Close", null)
            .show()
    }

    // --- headers ---

    private fun brandHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        setPadding(0, dp(8), 0, dp(12))
        addView(text("openCapsule", 32f, skin.screenText, bold = true, role = TextRole.HEADLINE, onScreen = true))
        addView(text("A dynamic island for your Android phone", 14f, skin.screenTextDim, onScreen = true).apply { setPadding(0, dp(4), 0, 0) })
        previewHolder = FrameLayout(context).apply { setPadding(0, dp(24), 0, dp(8)) }
        addView(previewHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(text("Live preview of your theme", 12f, skin.screenTextDim, role = TextRole.CAPTION, onScreen = true).apply { gravity = Gravity.CENTER })
    }

    private fun pageTitle(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(8), 0, dp(8))
        addView(text(title, 30f, skin.screenText, bold = true, role = TextRole.HEADLINE, onScreen = true))
        addView(text(subtitle, 14f, skin.screenTextDim, onScreen = true).apply { setPadding(0, dp(2), 0, 0) })
    }

    // --- app style picker ---

    /**
     * One style as a small scene drawn in that style (screen, card, button and chips), so the choice is made by
     * looking. The scene uses half-size dimensions so it fits the tile.
     */
    private fun styleTile(style: UiStyle): View {
        val preview = Skins.of(style, this, density * TILE_SCALE)
        val selected = style == skin.style
        val corner = dp(18).toFloat()

        val scene = FrameLayout(this).apply {
            background = preview.screen()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, corner)
                }
            }
            clipToOutline = true
            foreground = GradientDrawable().apply {
                cornerRadius = corner
                setStroke(dp(if (selected) 3 else 1), if (selected) skin.accent else skin.hairline)
            }
            addView(View(context).apply { background = preview.card(0) },
                FrameLayout.LayoutParams(dp(TILE_W - 16), dp(60), Gravity.TOP or Gravity.START).apply { setMargins(dp(8), dp(8), 0, 0) })
            addView(View(context).apply { background = preview.button(true, false) },
                FrameLayout.LayoutParams(dp(58), dp(30), Gravity.TOP or Gravity.START).apply { setMargins(dp(21), dp(23), 0, 0) })
            addView(View(context).apply { background = preview.chip(true) },
                FrameLayout.LayoutParams(dp(38), dp(24), Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(76), 0, 0) })
            addView(View(context).apply { background = preview.chip(false) },
                FrameLayout.LayoutParams(dp(38), dp(24), Gravity.TOP or Gravity.START).apply { setMargins(dp(54), dp(76), 0, 0) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            contentDescription = if (selected) "${style.label}, selected" else style.label
            addView(scene, LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H)))
            addView(text(style.label, 12f, if (selected) skin.text else skin.textDim, bold = selected, role = TextRole.CAPTION)
                .apply { setPadding(0, dp(8), 0, 0); gravity = Gravity.CENTER })
            layoutParams = ViewGroup.MarginLayoutParams(dp(TILE_W), ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                applyStyle(style)
            }
        }
    }

    // --- live preview ---

    /** Redraws the preview pill (on the Capsule tab) from the current settings. */
    private fun refreshPreview() {
        val holder = previewHolder ?: return
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(22), dp(12))
            val look = CapsuleLooks.of(prefs.uiStyle, density)
            val capsuleColor = prefs.capsuleColor(applicationContext)
            val light = isLightColor(capsuleColor)
            background = look?.let { CapsuleLooks.body(it, capsuleColor, dp(prefs.shape.radiusDp).toFloat(), dp(prefs.borderWidth.dp), prefs.borderColorValue()) }
                ?: GradientDrawable().apply {
                    setColor(capsuleColor)
                    cornerRadius = dp(prefs.shape.radiusDp).toFloat()
                    setStroke(dp(prefs.borderWidth.dp), prefs.borderColorValue())
                }
            elevation = dp(prefs.shadow.elevationDp).toFloat()
            addView(ImageView(context).apply {
                setImageDrawable(packageManager.getApplicationIcon(packageName))
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(plainText("Now playing", 14f, prefs.textInk() ?: if (light) CAPSULE_INK else CAPSULE_TEXT, bold = true))
                addView(plainText("Your music, your colors", 12f, prefs.textInk()?.let { (it and 0x00FFFFFF) or 0xBF000000.toInt() } ?: if (light) CAPSULE_INK_DIM else CAPSULE_TEXT_DIM))
            })
        }
        holder.removeAllViews()
        holder.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** Called after any setting changes: update the preview and show a sample capsule on the phone. */
    private fun changed() {
        refreshPreview()
        if (!OverlayHost.canDraw(this)) return
        overlay.show(
            CapsuleEvent(
                kind = Kind.MESSAGE,
                title = "Preview",
                text = "This is how your capsule looks",
                icon = packageManager.getApplicationIcon(packageName),
            )
        )
    }

    // --- sections and rows ---

    /** A titled section: a small heading on the screen, then one surface holding its rows. */
    private fun LinearLayout.section(title: String, content: LinearLayout.() -> Unit) {
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(text(title, 13f, skin.screenTextDim, bold = true, role = TextRole.LABEL, onScreen = true)
                .apply { setPadding(dp(14), 0, 0, dp(8)) })
            addView(surface(content))
        }
        addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(if (childCount == 0) 4 else Metrics.SECTION_GAP_DP) })
        fadeUp(group)
    }

    /** The surface behind a section's rows. */
    private fun surface(content: LinearLayout.() -> Unit): View {
        val background = skin.card(cardIndex++)
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            // Row icons and controls reserve shadow room that reaches into the padding, so it must not clip them.
            clipToPadding = false
            setPadding(dp(16), dp(16), dp(16), dp(18))
        }
        inner.content()
        val pad = paddingOf(background)
        return FrameLayout(this).apply {
            this.background = background
            clipChildren = false
            clipToPadding = false
            setPadding(pad.left, pad.top, pad.right, pad.bottom)
            addView(inner)
            // The surface reserves room around itself for its shadow; pull the margins in so the visible card still
            // lines up with the screen edges and sits right under its heading.
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(-pad.left, -pad.top, -pad.right, -pad.bottom) }
        }
    }

    /** Adds [v] as the next row of a section, with a gap above it unless it is the first. */
    private fun LinearLayout.addRow(v: View) {
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = if (childCount == 0) 0 else dp(Metrics.ROW_GAP_DP) })
    }

    /** The views of a row's header line, so callers can change its texts. */
    private class RowViews(val root: LinearLayout, val labels: LinearLayout, val title: TextView, val subtitle: TextView?)

    /** The line every row starts with: an icon tile, a title with an optional hint, and an optional view at the end. */
    private fun headRow(icon: Int, title: String, subtitle: String?, trailing: View? = null): RowViews {
        val titleView = text(title, 15f, skin.text, bold = true, role = TextRole.LABEL)
        val subtitleView = subtitle?.let { text(it, 12f, skin.textDim, role = TextRole.CAPTION) }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            subtitleView?.let { addView(it) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(iconTile(icon))
            addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            trailing?.let { addView(it) }
        }
        return RowViews(root, labels, titleView, subtitleView)
    }

    /** The icon at the start of a row, on the style's tile. The tile reserves shadow room, so it is sized and pulled in to match. */
    private fun iconTile(icon: Int): View {
        val tile = skin.tile()
        val pad = paddingOf(tile)
        return FrameLayout(this).apply {
            background = tile
            setPadding(pad.left, pad.top, pad.right, pad.bottom)
            addView(ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(skin.tileIcon)
            }, FrameLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(TILE_DP) + pad.left + pad.right, dp(TILE_DP) + pad.top + pad.bottom)
                .apply { setMargins(-pad.left, -pad.top, dp(14) - pad.right, -pad.bottom) }
        }
    }

    /** A row with a control (chips, a slider, fields, buttons) under its header. */
    private fun controlRow(icon: Int, title: String, subtitle: String?, vararg controls: View): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(headRow(icon, title, subtitle).root)
            controls.forEach { addView(it, controlParams(it)) }
        }

    /** Controls that carry their own layout (fields, buttons) keep it; the rest sit just under the header. */
    private fun controlParams(v: View): ViewGroup.LayoutParams =
        v.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }

    private fun sliderRow(icon: Int, title: String, max: Int, value: Int, format: (Int) -> String, onChange: (Int) -> Unit): View {
        val head = headRow(icon, title, format(value))
        val slider = slider(max, value) {
            head.subtitle?.text = format(it)
            onChange(it)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(head.root)
            addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
    }

    private fun switchRow(
        icon: Int,
        title: String,
        subtitle: String?,
        checked: Boolean,
        after: ((Boolean) -> Unit)? = null,
        onChange: (Boolean) -> Unit,
    ): View {
        val toggle = SkinSwitch(this, skin, checked) { on ->
            onChange(on)
            // Rows that preview themselves say so; the rest show the sample capsule.
            if (after != null) after(on) else changed()
        }.apply { contentDescription = title }
        return headRow(icon, title, subtitle, toggle).root
    }

    /** A row that does something when tapped, marked with a chevron. */
    private fun actionRow(icon: Int, title: String, subtitle: String?, onClick: () -> Unit): View {
        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(skin.textDim)
        }
        return headRow(icon, title, subtitle, chevron).root.apply {
            isClickable = true
            isFocusable = true
            val ripple = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            foreground = context.getDrawable(ripple.resourceId)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
        }
    }

    /**
     * A setup row: its icon, what it is for, and its state, with an Open button while it is not OK. [hideWhenOk] hides
     * the whole row while it is OK (for warnings that only matter when they apply); [badLabel] replaces the default wording.
     */
    private fun statusRow(
        icon: Int,
        title: String,
        hint: String,
        optional: Boolean,
        isOn: () -> Boolean,
        open: () -> Intent,
        hideWhenOk: Boolean = false,
        badLabel: String? = null,
    ): View {
        val action = button("Open", filled = false) { startActivity(open()) }.apply {
            minHeight = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val head = headRow(icon, title, hint, action)
        val state = text("", 12f, skin.textDim, bold = true, role = TextRole.CAPTION).apply { setPadding(0, dp(2), 0, 0) }
        head.labels.addView(state)
        statusRows += {
            val on = isOn()
            val color = if (on) skin.good else if (optional) skin.textDim else skin.warn
            state.text = if (on) "On" else badLabel ?: if (optional) "Off (optional)" else "Needs access"
            state.setTextColor(color)
            action.visibility = if (on) View.GONE else View.VISIBLE
            head.root.visibility = if (on && hideWhenOk) View.GONE else View.VISIBLE
        }
        return head.root
    }

    // --- controls ---

    /** Whether Material Capsule's accessibility service is switched on; its pill overlaps this app's. */
    private fun otherCapsuleAppEnabled() =
        Settings.Secure.getString(contentResolver, "enabled_accessibility_services")?.contains(OTHER_CAPSULE_PACKAGE) == true

    /** Single-choice pills over [options]; persists through [onPick]. */
    private fun <T> chips(options: List<T>, selected: T, label: (T) -> String, quiet: Boolean = false, onPick: (T) -> Unit): View {
        val views = mutableListOf<Pair<T, TextView>>()
        fun restyle(current: T) = views.forEach { (option, view) ->
            val on = option == current
            view.skinBackground(skin.chip(on), dp(16), dp(8), dp(16), dp(8))
            view.setTextColor(skin.chipText(on))
            view.isSelected = on
        }
        val group = ChipGroup(this).apply {
            chipSpacingHorizontal = dp(4)
            chipSpacingVertical = dp(8)
            clipChildren = false
        }
        options.forEach { option ->
            val chip = TextView(this).apply {
                text = label(option)
                textSize = 14f
                gravity = Gravity.CENTER
                typeface = skin.typeface(false)
                isClickable = true
                isFocusable = true
                skin.styleText(this, TextRole.BODY, false)
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    restyle(option)
                    onPick(option)
                    if (!quiet) changed()
                }
            }
            views += option to chip
            group.addView(chip)
        }
        restyle(selected)
        return group
    }

    /** A colour of the user's own beside the presets: what is saved, whether it is in use, and how to start or stop using it. */
    private class CustomColor(
        val forText: Boolean = false,
        val saved: () -> Int?,
        val active: () -> Boolean,
        val use: (Int) -> Unit,
        val clear: () -> Unit,
    )

    /**
     * Round colour dots; the selected one gets a bright ring. With [custom] a last dot opens the colour picker: a rainbow
     * until a colour has been made, then that colour. Choosing a preset puts the custom colour aside without losing it.
     */
    private fun <T> swatches(
        options: List<T>,
        selected: T,
        colorOf: (T) -> Int,
        label: (T) -> String,
        custom: CustomColor? = null,
        /** True while no preset should look selected (the setting is automatic). */
        suppress: () -> Boolean = { false },
        onPick: (T) -> Unit,
    ): View {
        val dots = mutableListOf<Pair<T, View>>()
        var customDot: View? = null
        var current = selected
        var customOn = custom?.active?.invoke() == true && custom.saved() != null
        fun ring(dot: View, color: Int, on: Boolean) {
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(dp(skin.swatchStrokeDp(on)), skin.swatchStroke(on))
            }
        }
        fun restyle(current: T) {
            dots.forEach { (option, dot) -> ring(dot, colorOf(option), option == current && !customOn && !suppress()) }
            customDot?.let { dot ->
                val color = custom?.saved?.invoke()
                if (color == null) {
                    dot.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        gradientType = GradientDrawable.SWEEP_GRADIENT
                        colors = intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED)
                        setStroke(dp(1), skin.hairline)
                    }
                } else {
                    ring(dot, color, customOn)
                }
            }
        }
        // The dots wrap onto more lines when they do not fit, so none runs past the card's edge.
        val row = ChipGroup(this).apply {
            chipSpacingHorizontal = dp(12)
            chipSpacingVertical = dp(12)
            clipChildren = false
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        options.forEach { option ->
            val dot = View(this).apply {
                contentDescription = label(option)
                layoutParams = ViewGroup.MarginLayoutParams(dp(40), dp(40))
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    customOn = false
                    current = option
                    custom?.clear?.invoke()
                    restyle(option)
                    onPick(option)
                    changed()
                }
            }
            dots += option to dot
            row.addView(dot)
        }
        if (custom != null) {
            val dot = View(this).apply {
                contentDescription = "Custom color"
                layoutParams = ViewGroup.MarginLayoutParams(dp(40), dp(40))
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    pickColor(custom.saved() ?: colorOf(current), custom.forText) { color ->
                        customOn = true
                        custom.use(color)
                        restyle(current)
                        changed()
                    }
                }
            }
            customDot = dot
            row.addView(dot)
        }
        restyle(current)
        return row
    }

    /**
     * A small colour picker: hue, saturation and brightness sliders and a hex field, kept in step, over a preview that
     * shows the capsule's text on the colour (dark text on light colours). [onPicked] gets an opaque colour.
     */
    private fun pickColor(initial: Int, forText: Boolean = false, onPicked: (Int) -> Unit) {
        val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
        var syncing = false

        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        val previewTitle = plainText("Now playing", 15f, CAPSULE_TEXT, bold = true)
        val previewText = plainText("Your music, your colors", 12f, CAPSULE_TEXT_DIM)
        preview.addView(previewTitle)
        preview.addView(previewText)

        val hex = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            hint = "#RRGGBB"
            filters = arrayOf(android.text.InputFilter.LengthFilter(7))
        }
        val hue = pickerSlider("Hue", 360)
        val saturation = pickerSlider("Saturation", 100)
        val brightness = pickerSlider("Brightness", 100)

        fun currentColor() = Color.HSVToColor(hsv)
        fun refresh() {
            val color = currentColor()
            val light = isLightColor(color)
            if (forText) {
                // Picking a text colour: show it on the capsule's own colour.
                preview.background = GradientDrawable().apply { setColor(prefs.capsuleColor(applicationContext)); cornerRadius = dp(20).toFloat() }
                previewTitle.setTextColor(color)
                previewText.setTextColor((color and 0x00FFFFFF) or 0xBF000000.toInt())
            } else {
                preview.background = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat() }
                previewTitle.setTextColor(if (light) CAPSULE_INK else CAPSULE_TEXT)
                previewText.setTextColor(if (light) CAPSULE_INK_DIM else CAPSULE_TEXT_DIM)
            }
        }
        fun pushToControls() {
            syncing = true
            hue.second.value = hsv[0].coerceIn(0f, 360f)
            saturation.second.value = (hsv[1] * 100).coerceIn(0f, 100f)
            brightness.second.value = (hsv[2] * 100).coerceIn(0f, 100f)
            hex.setText("#%06X".format(currentColor() and 0xFFFFFF))
            syncing = false
            refresh()
        }
        hue.second.addOnChangeListener { _, v, fromUser -> if (fromUser && !syncing) { hsv[0] = v; pushToControls() } }
        saturation.second.addOnChangeListener { _, v, fromUser -> if (fromUser && !syncing) { hsv[1] = v / 100f; pushToControls() } }
        brightness.second.addOnChangeListener { _, v, fromUser -> if (fromUser && !syncing) { hsv[2] = v / 100f; pushToControls() } }
        hex.doOnTextChanged { text, _, _, _ ->
            if (syncing) return@doOnTextChanged
            val typed = text?.toString().orEmpty().trim().let { if (it.startsWith("#")) it else "#$it" }
            val color = runCatching { Color.parseColor(typed) }.getOrNull()?.takeIf { typed.length == 7 } ?: return@doOnTextChanged
            Color.colorToHSV(color, hsv)
            syncing = true
            hue.second.value = hsv[0].coerceIn(0f, 360f)
            saturation.second.value = (hsv[1] * 100).coerceIn(0f, 100f)
            brightness.second.value = (hsv[2] * 100).coerceIn(0f, 100f)
            syncing = false
            refresh()
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(hex, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            listOf(hue, saturation, brightness).forEach { (box, _) -> addView(box) }
        }
        pushToControls()
        MaterialAlertDialogBuilder(this)
            .setTitle("Custom color")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use this color") { _, _ -> onPicked(currentColor() or OPAQUE_BLACK) }
            .show()
    }

    /** A labelled slider for the colour picker, returned with the slider itself. */
    private fun pickerSlider(title: String, max: Int): Pair<View, Slider> {
        val slider = Slider(this).apply {
            valueFrom = 0f
            valueTo = max.toFloat()
            labelBehavior = LabelFormatter.LABEL_GONE
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(plainText(title, 12f, skin.textDim).apply { setPadding(0, dp(10), 0, 0) })
            addView(slider)
        }
        return box to slider
    }

    private fun slider(max: Int, value: Int, onChange: (Int) -> Unit): View = Slider(this).apply {
        valueFrom = 0f
        valueTo = max.toFloat()
        stepSize = 1f
        this.value = value.toFloat().coerceIn(0f, max.toFloat())
        isTickVisible = false
        trackHeight = dp(skin.sliderTrackDp)
        labelBehavior = LabelFormatter.LABEL_GONE
        thumbTintList = ColorStateList.valueOf(skin.sliderThumb)
        thumbStrokeColor = ColorStateList.valueOf(skin.sliderThumbStroke)
        thumbStrokeWidth = dp(skin.sliderThumbStrokeDp).toFloat()
        thumbElevation = dp(skin.sliderThumbElevationDp).toFloat()
        trackActiveTintList = ColorStateList.valueOf(skin.sliderActive)
        trackInactiveTintList = ColorStateList.valueOf(skin.sliderInactive)
        haloTintList = ColorStateList.valueOf(Fx.withAlpha(skin.accent, 0.2f))
        addOnChangeListener { _, v, fromUser -> if (fromUser) onChange(v.toInt()) }
        addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) = changed()
        })
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        typeface = skin.typeface(true)
        setTextColor(skin.buttonText(filled))
        skin.styleText(this, TextRole.LABEL, false)
        isClickable = true
        isFocusable = true
        minHeight = dp(48)
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), skin.button(filled, true))
            addState(intArrayOf(), skin.button(filled, false))
        }
        skinBackground(states, dp(20), dp(13), dp(20), dp(13))
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onClick()
        }
        layoutParams = alignedParams(states, topDp = 12)
    }

    private fun field(hint: String, value: String, inputType: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        this.hint = hint
        setText(value)
        this.inputType = inputType
        setSingleLine()
        textSize = 15f
        typeface = skin.typeface(false)
        setTextColor(skin.text)
        setHintTextColor(skin.textDim)
        val surface = skin.field()
        skinBackground(surface, dp(16), dp(14), dp(16), dp(14))
        layoutParams = alignedParams(surface, topDp = 12)
    }

    private fun text(
        content: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        role: TextRole = TextRole.BODY,
        onScreen: Boolean = false,
    ) = plainText(content, size, color, bold).apply { skin.styleText(this, role, onScreen) }

    /** Text with no skin treatment, for things that are not part of the screen's style (the capsule preview). */
    private fun plainText(content: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = content
        textSize = size
        setTextColor(color)
        typeface = skin.typeface(bold)
    }

    /** Sets a skin surface as the background and keeps the padding it reserves for its shadow, plus [l], [t], [r], [b]. */
    private fun View.skinBackground(surface: Drawable, l: Int, t: Int, r: Int, b: Int) {
        background = surface
        val pad = paddingOf(surface)
        setPadding(pad.left + l, pad.top + t, pad.right + r, pad.bottom + b)
    }

    /** Full-width layout for a control whose surface reserves shadow room: the visible body lines up with the content. */
    private fun alignedParams(surface: Drawable, topDp: Int): LinearLayout.LayoutParams {
        val pad = paddingOf(surface)
        return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(-pad.left, dp(topDp) - pad.top, -pad.right, -pad.bottom) }
    }

    private fun paddingOf(surface: Drawable) = Rect().also { surface.getPadding(it) }

    /** Sections rise and fade in one after another when the screen opens. */
    private fun fadeUp(v: View) {
        v.alpha = 0f
        v.translationY = dp(Metrics.FADE_UP_DP).toFloat()
        pendingReveal += v
    }

    private fun revealCards() {
        if (prefs.reduceAnimations) {
            pendingReveal.forEach {
                it.alpha = 1f
                it.translationY = 0f
            }
            pendingReveal.clear()
            return
        }
        pendingReveal.forEachIndexed { i, v ->
            v.animate().alpha(1f).translationY(0f).setStartDelay(i * Metrics.FADE_STAGGER_MS).setDuration(Metrics.FADE_MS).start()
        }
        pendingReveal.clear()
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun dp(v: Float) = (v * density).toInt()

    private fun durationToProgress(scale: Float) = ((scale - MIN_SCALE) / SCALE_RANGE * DURATION_STEPS).toInt()

    private fun progressToDuration(progress: Int) = MIN_SCALE + SCALE_RANGE * progress / DURATION_STEPS

    /** Layout sizes shared by every skin. */
    private object Metrics {
        const val SIDE_DP = 20
        const val SECTION_GAP_DP = 22
        const val ROW_GAP_DP = 18
        const val SCRIM_MIN_DP = 24
        const val SCRIM_FADE_DP = 20
        const val NAV_MARGIN_DP = 10
        const val NAV_CLEARANCE_DP = 104
        const val SUB_CLEARANCE_DP = 32
        const val FADE_UP_DP = 24
        const val FADE_STAGGER_MS = 45L
        const val FADE_MS = 320L
    }

    private companion object {
        const val STATE_TAB = "tab"
        val DYNAMIC_CARD_GESTURES = listOf(Gesture.SINGLE_TAP, Gesture.DOUBLE_TAP, Gesture.LONG_PRESS)
        const val TAB_COUNT = 3
        const val NAV_CARD_INDEX = 4
        const val NAV_ITEM_W_DP = 64
        const val NAV_ITEM_H_DP = 44
        const val NAV_GAP_DP = 6
        const val TILE_DP = 40
        const val SHAPE_TILE_DP = 72
        const val SHAPE_ICON_DP = 48
        const val CURVE_W_DP = 88
        const val CURVE_H_DP = 64
        const val APP_ROW_GAP_DP = 14
        const val ICON_DP = 24
        const val MIN_SCALE = 0.5f
        const val SCALE_RANGE = 2.5f
        const val OTHER_CAPSULE_PACKAGE = "com.pryshedko.mtisland"
        const val DURATION_STEPS = 100
        const val DEFAULT_TIMER_MINUTES = 5
        const val SECONDS_PER_MINUTE = 60
        const val STYLE_FADE_MS = 240L
        const val PAGE_FADE_MS = 160L
        const val TILE_W = 104
        const val TILE_H = 112
        const val TILE_SCALE = 0.5f
        const val CAPSULE_TEXT = 0xFFF4F4F8.toInt()
        const val CAPSULE_TEXT_DIM = 0xFFCCCCCC.toInt()
        const val CAPSULE_INK = 0xFF16161C.toInt()
        const val OPAQUE_BLACK = 0xFF000000.toInt()
        const val CAPSULE_INK_DIM = 0xFF50505C.toInt()
    }
}
