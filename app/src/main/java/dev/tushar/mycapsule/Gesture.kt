package dev.tushar.mycapsule

/** What a gesture on the idle pill can do. EXPAND is handled by the overlay, the rest by the service. */
enum class GestureAction(val label: String) {
    NONE("Nothing"),
    EXPAND("Expand capsule"),
    PLAY_PAUSE("Play / pause"),
    NEXT_TRACK("Next track"),
    PREVIOUS_TRACK("Previous track"),
    FLASHLIGHT("Toggle flashlight"),
    BARCODE("Show barcode"),
}

/** Gestures on the idle pill that can be remapped. A plain tap always expands. */
enum class Gesture(val label: String, val default: GestureAction) {
    SINGLE_TAP("Single tap", GestureAction.EXPAND),
    LONG_PRESS("Long press", GestureAction.PLAY_PAUSE),
    DOUBLE_TAP("Double tap", GestureAction.NONE),
    SWIPE_LEFT("Swipe left", GestureAction.NEXT_TRACK),
    SWIPE_RIGHT("Swipe right", GestureAction.PREVIOUS_TRACK),
}

/** How visible the idle pill is. Fully clear suits OLED phones: the pill blends into the camera. */
enum class IdleOpacity(val label: String, val alpha: Float) {
    CLEAR("0% (recommended)", 0f),
    P20("20%", 0.2f),
    P40("40%", 0.4f),
    P60("60%", 0.6f),
    P80("80%", 0.8f),
    FULL("100%", 1f),
}
