package dev.tushar.mycapsule

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.WindowManager

/**
 * Decides where capsule windows are added. When [OverlayHostService] is enabled, windows use the
 * accessibility overlay type, which draws above the status bar and over the camera. Otherwise they fall
 * back to a normal app overlay, which needs the "display over other apps" permission and sits below the
 * status bar. Callers keep the [Target] they added a view with and remove the view through it.
 */
object OverlayHost {

    class Target(val windowManager: WindowManager, val type: Int) {
        val aboveStatusBar get() = type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    }

    private var service: AccessibilityService? = null
    private val listeners = mutableListOf<() -> Unit>()

    fun attach(service: AccessibilityService) = swap(service)

    fun detach(service: AccessibilityService) {
        if (this.service === service) swap(null)
    }

    fun onChange(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** Whether the accessibility service is connected, i.e. capsules draw above the status bar. */
    val isAboveStatusBar get() = service != null

    /** True when capsules can be drawn at all: through the accessibility service or the overlay permission. */
    fun canDraw(context: Context) = service != null || Settings.canDrawOverlays(context)

    fun target(context: Context): Target {
        val accessibility = service
        return if (accessibility != null) {
            Target(
                accessibility.getSystemService(Context.WINDOW_SERVICE) as WindowManager,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            )
        } else {
            Target(
                context.getSystemService(Context.WINDOW_SERVICE) as WindowManager,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            )
        }
    }

    private fun swap(new: AccessibilityService?) {
        service = new
        listeners.toList().forEach { it() }
    }
}
