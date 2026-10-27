package dev.tushar.mycapsule

import android.content.Context
import android.content.SharedPreferences

/** Per-kind switches, display time and theme (colour, shape, position), backed by SharedPreferences. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("capsule", Context.MODE_PRIVATE)

    fun enabled(kind: Kind) = sp.getBoolean("on_${kind.name}", kind.defaultOn)

    fun setEnabled(kind: Kind, on: Boolean) = sp.edit().putBoolean("on_${kind.name}", on).apply()

    fun durationMs(kind: Kind) = (kind.defaultMs * durationScale()).toLong()

    fun durationScale() = sp.getFloat(DURATION_SCALE, DEFAULT_DURATION_SCALE)

    fun setDurationScale(scale: Float) = sp.edit().putFloat(DURATION_SCALE, scale).apply()

    var shape: Shape
        get() = read(SHAPE, Shape.ROUNDED)
        set(v) = write(SHAPE, v)

    /** The capsule colour in the phone's light theme. The dark theme uses [swatch]. */
    var swatchLight: Swatch
        get() = read(SWATCH_LIGHT, Swatch.BLACK)
        set(v) = write(SWATCH_LIGHT, v)

    /**
     * A colour of the user's own for the dark theme, kept even while a preset is in use so it can be picked again. It is
     * the capsule's colour only while [useCustomDark] is on; choosing a preset turns that off.
     */
    var customColorDark: Int?
        get() = if (sp.contains(CUSTOM_DARK)) sp.getInt(CUSTOM_DARK, 0) else null
        set(v) = sp.edit().apply { if (v == null) remove(CUSTOM_DARK) else putInt(CUSTOM_DARK, v or OPAQUE) }.apply()

    var useCustomDark: Boolean
        get() = sp.getBoolean(USE_CUSTOM_DARK, false)
        set(v) = sp.edit().putBoolean(USE_CUSTOM_DARK, v).apply()

    var customColorLight: Int?
        get() = if (sp.contains(CUSTOM_LIGHT)) sp.getInt(CUSTOM_LIGHT, 0) else null
        set(v) = sp.edit().apply { if (v == null) remove(CUSTOM_LIGHT) else putInt(CUSTOM_LIGHT, v or OPAQUE) }.apply()

    var useCustomLight: Boolean
        get() = sp.getBoolean(USE_CUSTOM_LIGHT, false)
        set(v) = sp.edit().putBoolean(USE_CUSTOM_LIGHT, v).apply()

    /** Whether the capsule's text colour is picked for you (light or dark, to suit the capsule) instead of chosen below. */
    var textAuto: Boolean
        get() = sp.getBoolean(TEXT_AUTO, true)
        set(v) = sp.edit().putBoolean(TEXT_AUTO, v).apply()

    var textPreset: TextPreset
        get() = read(TEXT_PRESET, TextPreset.WHITE)
        set(v) = write(TEXT_PRESET, v)

    var customTextColor: Int?
        get() = if (sp.contains(CUSTOM_TEXT)) sp.getInt(CUSTOM_TEXT, 0) else null
        set(v) = sp.edit().apply { if (v == null) remove(CUSTOM_TEXT) else putInt(CUSTOM_TEXT, v or OPAQUE) }.apply()

    var useCustomText: Boolean
        get() = sp.getBoolean(USE_CUSTOM_TEXT, false)
        set(v) = sp.edit().putBoolean(USE_CUSTOM_TEXT, v).apply()

    /** The colour chosen for the capsule's text, icons and bars, or null while it is automatic. */
    fun textInk(): Int? = if (textAuto) null else customTextColor?.takeIf { useCustomText } ?: textPreset.color

    /** A border colour of the user's own, kept while a preset is in use (see [customColorDark] for the idea). */
    var customBorderColor: Int?
        get() = if (sp.contains(CUSTOM_BORDER)) sp.getInt(CUSTOM_BORDER, 0) else null
        set(v) = sp.edit().apply { if (v == null) remove(CUSTOM_BORDER) else putInt(CUSTOM_BORDER, v or OPAQUE) }.apply()

    var useCustomBorder: Boolean
        get() = sp.getBoolean(USE_CUSTOM_BORDER, false)
        set(v) = sp.edit().putBoolean(USE_CUSTOM_BORDER, v).apply()

    /** The border colour in use: the custom one when chosen, else the preset. */
    fun borderColorValue(): Int = customBorderColor?.takeIf { useCustomBorder } ?: borderColor.color

    /** The capsule colour for the phone's current theme: the custom colour if it is in use, else the chosen preset. */
    fun capsuleColor(context: Context): Int =
        if (isNight(context)) {
            customColorDark?.takeIf { useCustomDark } ?: swatch.color
        } else {
            customColorLight?.takeIf { useCustomLight } ?: swatchLight.color
        }

    var align: Align
        get() = read(ALIGN, Align.CENTER)
        set(v) = write(ALIGN, v)

    var swatch: Swatch
        get() = read(SWATCH, Swatch.BLACK)
        set(v) = write(SWATCH, v)

    var borderWidth: BorderWidth
        get() = read(BORDER_WIDTH, BorderWidth.NONE)
        set(v) = write(BORDER_WIDTH, v)

    var borderColor: BorderColor
        get() = read(BORDER_COLOR, BorderColor.WHITE)
        set(v) = write(BORDER_COLOR, v)

    var shadow: Shadow
        get() = read(SHADOW, Shadow.SOFT)
        set(v) = write(SHADOW, v)

    /** Whether the small always-on pill is drawn over the camera. */
    var idleEnabled: Boolean
        get() = sp.getBoolean(IDLE_ENABLED, true)
        set(v) = sp.edit().putBoolean(IDLE_ENABLED, v).apply()

    /** Whether the idle pill is left out while the phone is on its side. On by default: a pill over a side camera is rarely useful. */
    var hideIdleInLandscape: Boolean
        get() = sp.getBoolean(HIDE_IDLE_LANDSCAPE, true)
        set(v) = sp.edit().putBoolean(HIDE_IDLE_LANDSCAPE, v).apply()

    var idleOpacity: IdleOpacity
        get() = read(IDLE_OPACITY, IdleOpacity.P40)
        set(v) = write(IDLE_OPACITY, v)

    /** Haptic tick when the capsule opens, closes or switches cards. */
    var haptics: Boolean
        get() = sp.getBoolean(HAPTICS, true)
        set(v) = sp.edit().putBoolean(HAPTICS, v).apply()

    fun action(gesture: Gesture): GestureAction = read("gesture_${gesture.name}", gesture.default)

    fun setAction(gesture: Gesture, action: GestureAction) = write("gesture_${gesture.name}", action)

    /** Calls [onChange] after any setting changes. The returned listener must be kept referenced. */
    fun listen(onChange: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        sp.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    /** The one barcode kept for the barcode capsule; null when none is saved. */
    var barcode: SavedBarcode?
        get() {
            val content = sp.getString(BARCODE_CONTENT, null) ?: return null
            return SavedBarcode(
                label = sp.getString(BARCODE_LABEL, "").orEmpty(),
                content = content,
                type = read(BARCODE_TYPE, BarcodeType.QR),
            )
        }
        set(v) = sp.edit().apply {
            if (v == null) {
                remove(BARCODE_CONTENT).remove(BARCODE_LABEL).remove(BARCODE_TYPE)
            } else {
                putString(BARCODE_CONTENT, v.content).putString(BARCODE_LABEL, v.label).putString(BARCODE_TYPE, v.type.name)
            }
        }.apply()

    /** Whether media capsules take their colour from album art instead of [swatch]. */
    var coverColors: Boolean
        get() = sp.getBoolean(COVER_COLORS, true)
        set(v) = sp.edit().putBoolean(COVER_COLORS, v).apply()

    /**
     * Distance of an event capsule from the top edge of the screen, or null for the default: just under the
     * status bar, which depends on the phone and so is worked out where it is used.
     */
    var topOffsetDp: Int?
        get() = if (sp.contains(TOP_OFFSET)) sp.getInt(TOP_OFFSET, 0) else null
        set(v) = sp.edit().apply { if (v == null) remove(TOP_OFFSET) else putInt(TOP_OFFSET, v) }.apply()

    /** How far under the idle pill the expanded view starts, as a percentage of the screen height. */
    var deckGapPercent: Int
        get() = sp.getInt(DECK_GAP, DEFAULT_DECK_GAP_PERCENT)
        set(v) = sp.edit().putInt(DECK_GAP, v).apply()

    /** Seconds the expanded view stays open before it closes by itself. */
    var deckSeconds: Int
        get() = sp.getInt(DECK_SECONDS, DEFAULT_DECK_SECONDS)
        set(v) = sp.edit().putInt(DECK_SECONDS, v).apply()

    /** Whether a tap anywhere outside the expanded view closes it. */
    var closeOnOutsideTap: Boolean
        get() = sp.getBoolean(CLOSE_ON_OUTSIDE_TAP, true)
        set(v) = sp.edit().putBoolean(CLOSE_ON_OUTSIDE_TAP, v).apply()

    /** Whether every card in the expanded view is as tall as the tallest one. */
    var uniformDeck: Boolean
        get() = sp.getBoolean(UNIFORM_DECK, true)
        set(v) = sp.edit().putBoolean(UNIFORM_DECK, v).apply()

    /**
     * Where the capsule sits on [profile]: the choice made for that kind of screen if there is one, else the settings that
     * apply to every screen ([align], [topOffsetDp]), an even position and a pill of normal size.
     */
    fun position(profile: ScreenProfile): Position {
        val k = "p_${profile.name}_"
        return Position(
            align = read("${k}align", align),
            offsetXDp = sp.getInt("${k}x", 0),
            topDp = if (sp.contains("${k}top")) sp.getInt("${k}top", 0) else topOffsetDp,
            pillPercent = sp.getInt("${k}pill", Position.PILL_DEFAULT).coerceIn(Position.PILL_MIN, Position.PILL_MAX),
        )
    }

    /** Saves [position] as the choice for [profile]. */
    fun setPosition(profile: ScreenProfile, position: Position) {
        val k = "p_${profile.name}_"
        sp.edit().apply {
            putString("${k}align", position.align.name)
            putInt("${k}x", position.offsetXDp)
            if (position.topDp == null) remove("${k}top") else putInt("${k}top", position.topDp)
            putInt("${k}pill", position.pillPercent)
        }.apply()
    }

    fun updatePosition(profile: ScreenProfile, change: (Position) -> Position) = setPosition(profile, change(position(profile)))

    /** Whether [profile] has a position of its own, rather than following the settings that apply to every screen. */
    fun hasOwnPosition(profile: ScreenProfile) = sp.contains("p_${profile.name}_align")

    /** Takes back the choice for [profile], so it follows the settings that apply to every screen again. */
    fun resetPosition(profile: ScreenProfile) {
        val k = "p_${profile.name}_"
        sp.edit().remove("${k}align").remove("${k}x").remove("${k}top").remove("${k}pill").apply()
    }

    /** Light or dark for this app's own screens (see [AppTheme]). */
    var appTheme: AppTheme
        get() = read(APP_THEME, AppTheme.SYSTEM)
        set(v) = write(APP_THEME, v)

    /** The typeface of this app's own screens (see [AppFont]). */
    var appFont: AppFont
        get() = read(APP_FONT, AppFont.DEFAULT)
        set(v) = write(APP_FONT, v)

    /** The shape of the icon or avatar on notification capsules. */
    var iconShape: IconShape
        get() = read(ICON_SHAPE, IconShape.ROUNDED)
        set(v) = write(ICON_SHAPE, v)

    /** Whether chat notifications show who wrote, their message and their avatar (and other large icons show as avatars). */
    var conversationLayout: Boolean
        get() = sp.getBoolean(CONVERSATION_LAYOUT, true)
        set(v) = sp.edit().putBoolean(CONVERSATION_LAYOUT, v).apply()

    /** Whether a notification's Seen and Reply buttons show on its capsule. */
    var notificationButtons: Boolean
        get() = sp.getBoolean(NOTIFICATION_BUTTONS, true)
        set(v) = sp.edit().putBoolean(NOTIFICATION_BUTTONS, v).apply()

    /** What happens to the notifications of an app that has no choice of its own (see [notificationAllowed]). */
    var notificationDefault: AppPolicy
        get() = read(NOTIFICATION_DEFAULT, AppPolicy.ALLOW_ALL)
        set(v) = write(NOTIFICATION_DEFAULT, v)

    /** Whether notifications that make no sound may show a capsule. Only messages count; see [NotificationSource]. */
    var showSilentNotifications: Boolean
        get() = sp.getBoolean(SHOW_SILENT, true)
        set(v) = sp.edit().putBoolean(SHOW_SILENT, v).apply()

    /** Whether ongoing notifications (downloads, navigation) may show a capsule. Calls always do. */
    var showOngoingNotifications: Boolean
        get() = sp.getBoolean(SHOW_ONGOING, true)
        set(v) = sp.edit().putBoolean(SHOW_ONGOING, v).apply()

    /**
     * Whether the notifications of [pkg] may show a capsule: its own choice if it has one, else [notificationDefault].
     * A choice stays a choice even when it matches the default, so changing the default later never overrides it.
     */
    fun notificationAllowed(pkg: String): Boolean = when {
        pkg in strings(NOTIFICATION_ALLOWED) -> true
        pkg in strings(NOTIFICATION_BLOCKED) -> false
        else -> notificationDefault == AppPolicy.ALLOW_ALL
    }

    fun setNotificationAllowed(pkg: String, allowed: Boolean) {
        val allow = strings(NOTIFICATION_ALLOWED)
        val block = strings(NOTIFICATION_BLOCKED)
        if (allowed) {
            allow += pkg
            block -= pkg
        } else {
            block += pkg
            allow -= pkg
        }
        sp.edit().putStringSet(NOTIFICATION_ALLOWED, allow).putStringSet(NOTIFICATION_BLOCKED, block).apply()
    }

    /** A copy of a stored set: the set SharedPreferences hands out must never be changed in place. */
    private fun strings(key: String): MutableSet<String> = sp.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()

    /** How a capsule opens (see [ExpandStyle]). */
    var expandStyle: ExpandStyle
        get() = read(EXPAND_STYLE, ExpandStyle.FLUID)
        set(v) = write(EXPAND_STYLE, v)

    /** How far a springy style overshoots. */
    var bounce: BounceLevel
        get() = read(BOUNCE, BounceLevel.NORMAL)
        set(v) = write(BOUNCE, v)

    /** The length of the capsule's movement relative to normal: 100 is as designed, more is slower. */
    var animationDurationPercent: Int
        get() = sp.getInt(ANIMATION_PERCENT, Motions.DEFAULT_PERCENT).coerceIn(Motions.MIN_PERCENT, Motions.MAX_PERCENT)
        set(v) = sp.edit().putInt(ANIMATION_PERCENT, v).apply()

    /** Capsules fade in place and the app's own moving effects stop. For slower phones. */
    var reduceAnimations: Boolean
        get() = sp.getBoolean(REDUCE_ANIMATIONS, false)
        set(v) = sp.edit().putBoolean(REDUCE_ANIMATIONS, v).apply()

    /** Whether a card is allowed in the expanded view. Cards are on unless switched off. */
    fun cardEnabled(card: DeckCard) = sp.getBoolean("card_${card.name}", true)

    fun setCardEnabled(card: DeckCard, on: Boolean) = sp.edit().putBoolean("card_${card.name}", on).apply()

    /**
     * The gesture that opens a live card (see [CapsuleEvent.live]) instead of running its own action, while something
     * is live. Any other gesture, and this one when nothing is live, does what it is mapped to.
     */
    var dynamicCardGesture: Gesture
        get() = read(DYNAMIC_CARD_GESTURE, Gesture.SINGLE_TAP)
        set(v) = write(DYNAMIC_CARD_GESTURE, v)

    /** Whether the capsule for one phone event (Wi-Fi connected, airplane mode on and so on) is switched on. */
    fun systemEnabled(event: SystemEvent) = sp.getBoolean("sys_${event.name}", event.defaultOn)

    fun setSystemEnabled(event: SystemEvent, on: Boolean) = sp.edit().putBoolean("sys_${event.name}", on).apply()

    /** Whether a system event's banner stays on screen until it is swiped up (instead of going after the display time). */
    var bannersSticky: Boolean
        get() = sp.getBoolean(BANNERS_STICKY, true)
        set(v) = sp.edit().putBoolean(BANNERS_STICKY, v).apply()

    /** Whether a system event's banner has a Settings button, and opens that settings page when tapped. */
    var bannerSettingsButton: Boolean
        get() = sp.getBoolean(BANNER_SETTINGS, true)
        set(v) = sp.edit().putBoolean(BANNER_SETTINGS, v).apply()

    /** The battery level, in percent, at or under which the low battery capsule shows. */
    var lowBatteryPercent: Int
        get() = sp.getInt(LOW_BATTERY, DEFAULT_LOW_BATTERY_PERCENT)
        set(v) = sp.edit().putInt(LOW_BATTERY, v).apply()

    /** How the settings screen looks. */
    var uiStyle: UiStyle
        get() = read(UI_STYLE, UiStyle.MATERIAL)
        set(v) = write(UI_STYLE, v)

    /** Puts every setting back to its default. The saved barcode is your data, not a setting, so it stays. */
    fun resetAll() = sp.edit().apply {
        sp.all.keys.filterNot { it in BARCODE_KEYS }.forEach { remove(it) }
    }.apply()

    private inline fun <reified T : Enum<T>> read(key: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == sp.getString(key, null) } ?: default

    private fun write(key: String, value: Enum<*>) = sp.edit().putString(key, value.name).apply()

    companion object {
        // The defaults are the choices made while building the app: a 5% gap under the pill, a slightly shorter
        // display time, rounded corners and a visible (40%) idle pill. Reset all settings returns to these.
        const val DEFAULT_LOW_BATTERY_PERCENT = 15
        val LOW_BATTERY_CHOICES = listOf(5, 10, 15, 20)
        const val DEFAULT_DECK_GAP_PERCENT = 5
        const val DEFAULT_DURATION_SCALE = 0.75f
        const val DEFAULT_DECK_SECONDS = 10
        const val MIN_DECK_SECONDS = 3
        const val MAX_DECK_SECONDS = 30
        const val MAX_DECK_GAP_PERCENT = 30

        private const val DURATION_SCALE = "duration_scale"
        private const val UI_STYLE = "ui_style"
        private const val APP_THEME = "app_theme"
        private const val APP_FONT = "app_font"
        private const val HIDE_IDLE_LANDSCAPE = "hide_idle_landscape"
        private const val BANNERS_STICKY = "banners_sticky"
        private const val BANNER_SETTINGS = "banner_settings"
        private const val ICON_SHAPE = "icon_shape"
        private const val CONVERSATION_LAYOUT = "conversation_layout"
        private const val NOTIFICATION_BUTTONS = "notification_buttons"
        private const val EXPAND_STYLE = "expand_style"
        private const val BOUNCE = "bounce"
        private const val ANIMATION_PERCENT = "animation_percent"
        private const val REDUCE_ANIMATIONS = "reduce_animations"
        private const val NOTIFICATION_DEFAULT = "notification_default"
        private const val NOTIFICATION_ALLOWED = "notification_allowed_apps"
        private const val NOTIFICATION_BLOCKED = "notification_blocked_apps"
        private const val SHOW_SILENT = "show_silent_notifications"
        private const val SHOW_ONGOING = "show_ongoing_notifications"
        private const val DYNAMIC_CARD_GESTURE = "dynamic_card_gesture"
        private const val LOW_BATTERY = "low_battery_percent"
        private const val SHAPE = "shape"
        private const val ALIGN = "align"
        private const val SWATCH = "swatch"
        private const val SWATCH_LIGHT = "swatch_light"
        private const val CUSTOM_DARK = "custom_color_dark"
        private const val CUSTOM_LIGHT = "custom_color_light"
        private const val USE_CUSTOM_DARK = "use_custom_dark"
        private const val USE_CUSTOM_LIGHT = "use_custom_light"
        private const val OPAQUE = 0xFF000000.toInt()
        private const val TEXT_AUTO = "text_auto"
        private const val TEXT_PRESET = "text_preset"
        private const val CUSTOM_TEXT = "custom_text_color"
        private const val USE_CUSTOM_TEXT = "use_custom_text"
        private const val CUSTOM_BORDER = "custom_border_color"
        private const val USE_CUSTOM_BORDER = "use_custom_border"
        private const val BORDER_WIDTH = "border_width"
        private const val BORDER_COLOR = "border_color"
        private const val SHADOW = "shadow"
        private const val IDLE_ENABLED = "idle_enabled"
        private const val IDLE_OPACITY = "idle_opacity"
        private const val HAPTICS = "haptics"
        private const val BARCODE_CONTENT = "barcode_content"
        private const val BARCODE_LABEL = "barcode_label"
        private const val BARCODE_TYPE = "barcode_type"
        private const val DECK_GAP = "deck_gap_percent"
        private const val DECK_SECONDS = "deck_seconds"
        private const val CLOSE_ON_OUTSIDE_TAP = "close_on_outside_tap"
        private const val UNIFORM_DECK = "uniform_deck"
        private val BARCODE_KEYS = setOf(BARCODE_CONTENT, BARCODE_LABEL, BARCODE_TYPE)
        private const val COVER_COLORS = "cover_colors"
        // A new key: the old "top_offset" counted from under the status bar, this one counts from the screen top.
        private const val TOP_OFFSET = "event_top_dp"
    }
}
