package dev.tushar.mycapsule

import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable

/**
 * Shows the saved barcode as a capsule when asked via [showIntent], and contributes it as a card in
 * the expanded capsule. The app's "Show on capsule" button, a gesture, a widget or `adb` all trigger
 * it the same way.
 */
class BarcodeSource : ReceiverSource(listOf(ACTION_SHOW), exported = true) {

    override fun onBroadcast(context: Context, intent: Intent) = show(context)

    fun show(context: Context) {
        event(context)?.let(emit)
    }

    override fun card(context: Context): CapsuleEvent? = event(context)

    private fun event(context: Context): CapsuleEvent? {
        val saved = Prefs(context).barcode ?: return null
        val bitmap = BarcodeEncoder.bitmap(saved.content, saved.type) ?: return null
        return CapsuleEvent(
            kind = Kind.BARCODE,
            title = saved.label.ifBlank { saved.type.label },
            text = saved.content,
            icon = context.getDrawable(R.drawable.ic_qr_code_2),
            image = BitmapDrawable(context.resources, bitmap),
        )
    }

    companion object {
        const val ACTION_SHOW = "dev.tushar.mycapsule.SHOW_BARCODE"

        fun showIntent(context: Context) = Intent(ACTION_SHOW).setPackage(context.packageName)
    }
}
