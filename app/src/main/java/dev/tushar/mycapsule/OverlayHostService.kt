package dev.tushar.mycapsule

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * An accessibility service that exists only to own an accessibility-overlay window layer for
 * [OverlayHost]. It receives no events and reads no screen content.
 */
class OverlayHostService : AccessibilityService() {

    override fun onServiceConnected() {
        OverlayHost.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        OverlayHost.detach(this)
        return super.onUnbind(intent)
    }
}
