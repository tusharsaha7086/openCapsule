package dev.tushar.mycapsule

import android.view.Gravity

/** Corner rounding of the capsule. */
enum class Shape(val label: String, val radiusDp: Int) {
    PILL("Pill", 28),
    ROUNDED("Rounded", 14),
    SQUARE("Square", 4),
}

/** Which side of the screen the capsule sits on. */
enum class Align(val label: String, val gravity: Int) {
    START("Left", Gravity.START),
    CENTER("Center", Gravity.CENTER_HORIZONTAL),
    END("Right", Gravity.END),
}

/** Outline thickness; NONE draws no border. */
enum class BorderWidth(val label: String, val dp: Int) {
    NONE("None", 0),
    THIN("Thin", 1),
    MEDIUM("Medium", 2),
    THICK("Thick", 3),
}

enum class BorderColor(val label: String, val color: Int) {
    WHITE("White", 0xFFFFFFFF.toInt()),
    GRAY("Gray", 0xFF888888.toInt()),
    BLUE("Blue", 0xFF4FC3F7.toInt()),
    GREEN("Green", 0xFF66BB6A.toInt()),
    ORANGE("Orange", 0xFFFFB74D.toInt()),
}

/** Drop shadow strength, as view elevation. */
enum class Shadow(val label: String, val elevationDp: Int) {
    NONE("None", 0),
    SOFT("Soft", 6),
    STRONG("Strong", 14),
}

/** Preset background colours. The text on the capsule turns dark on the light ones, so any colour reads. */
enum class Swatch(val label: String, val color: Int) {
    BLACK("Black", 0xFF000000.toInt()),
    GRAPHITE("Graphite", 0xFF2B2B2B.toInt()),
    NAVY("Navy", 0xFF0B2A4A.toInt()),
    PLUM("Plum", 0xFF3B1F4F.toInt()),
    FOREST("Forest", 0xFF14372A.toInt()),
    CRIMSON("Crimson", 0xFF4A1620.toInt()),
    SNOW("Snow", 0xFFF4F4F8.toInt()),
    SAND("Sand", 0xFFF1E4C8.toInt()),
    SKY("Sky", 0xFFCFE8FF.toInt()),
    MINT("Mint", 0xFFD3F2DF.toInt()),
    ROSE("Rose", 0xFFFFD9E2.toInt()),
}

/** Whether the phone is in its dark theme right now. */
fun isNight(context: android.content.Context) =
    context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

/** Whether text on [color] has to be dark to be read. */
fun isLightColor(color: Int) = androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.5

/** Preset colours for the text, icons, bars and buttons on the capsule. Automatic (the default) picks light or dark to suit the capsule. */
enum class TextPreset(val label: String, val color: Int) {
    WHITE("White", 0xFFFFFFFF.toInt()),
    INK("Black", 0xFF16161C.toInt()),
    GOLD("Gold", 0xFFFFD36B.toInt()),
    MINT("Mint", 0xFF9BE7B8.toInt()),
    SKY("Sky", 0xFF8FD0FF.toInt()),
    ROSE("Rose", 0xFFFFA6C0.toInt()),
    ORANGE("Orange", 0xFFFF9B4A.toInt()),
}
