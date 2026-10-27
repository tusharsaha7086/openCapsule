package dev.tushar.mycapsule

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper

/**
 * Emits a capsule when the torch turns on or off, offers a flashlight card in the expanded capsule,
 * and runs the toggle gesture. Needs no camera permission: torch callbacks and
 * [CameraManager.setTorchMode] are permission-free.
 */
class FlashlightSource : CapsuleSource {

    private val main = Handler(Looper.getMainLooper())
    private val lastState = HashMap<String, Boolean>()

    private var manager: CameraManager? = null
    private var callback: CameraManager.TorchCallback? = null

    override fun start(context: Context, emit: (CapsuleEvent) -> Unit) {
        val cameras = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        manager = cameras

        val torch = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                // The first callback per camera only reports the current state, not a change.
                val previous = lastState.put(cameraId, enabled) ?: return
                if (previous == enabled) return
                emit(event(context, cameraId, enabled))
            }
        }
        callback = torch
        cameras.registerTorchCallback(torch, main)
    }

    override fun stop(context: Context) {
        callback?.let { manager?.unregisterTorchCallback(it) }
        callback = null
        lastState.clear()
    }

    override fun card(context: Context): CapsuleEvent? {
        val (id, enabled) = lastState.entries.firstOrNull() ?: return null
        return event(context, id, enabled, forCard = true)
    }

    fun toggle() {
        val (id, enabled) = lastState.entries.firstOrNull() ?: return
        runCatching { manager?.setTorchMode(id, !enabled) }
    }

    private fun event(context: Context, cameraId: String, enabled: Boolean, forCard: Boolean = false): CapsuleEvent {
        val switch = CapsuleAction(
            icon = context.getDrawable(R.drawable.ic_flashlight_off),
            description = if (enabled) "Turn off" else "Turn on",
            label = if (forCard) (if (enabled) "Turn off" else "Turn on") else null,
        ) { runCatching { manager?.setTorchMode(cameraId, !enabled) } }

        return CapsuleEvent(
            kind = Kind.FLASHLIGHT,
            title = if (enabled) "Flashlight on" else "Flashlight off",
            text = null,
            icon = context.getDrawable(R.drawable.ic_flashlight_on),
            // The brief on/off capsule only offers a button while the torch is on; the card always does.
            actions = if (enabled || forCard) listOf(switch) else emptyList(),
            live = enabled,
            // While the torch is on its banner stays, with the Turn off button, until it is swiped up.
            persistent = enabled && !forCard && Prefs(context).bannersSticky,
        )
    }
}
