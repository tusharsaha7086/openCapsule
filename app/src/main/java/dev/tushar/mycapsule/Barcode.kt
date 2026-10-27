package dev.tushar.mycapsule

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

/** Supported symbologies; [width] and [height] are the render size in pixels. */
enum class BarcodeType(val label: String, val format: BarcodeFormat, val width: Int, val height: Int) {
    QR("QR code", BarcodeFormat.QR_CODE, 512, 512),
    CODE_128("Code 128", BarcodeFormat.CODE_128, 640, 220),
    CODE_39("Code 39", BarcodeFormat.CODE_39, 640, 220),
    EAN_13("EAN-13", BarcodeFormat.EAN_13, 640, 220),
    UPC_A("UPC-A", BarcodeFormat.UPC_A, 640, 220),
}

data class SavedBarcode(val label: String, val content: String, val type: BarcodeType)

object BarcodeEncoder {

    /** Black-on-white bitmap, or null when [content] isn't valid for [type] (e.g. wrong EAN length). */
    fun bitmap(content: String, type: BarcodeType): Bitmap? = runCatching {
        val matrix = MultiFormatWriter().encode(content, type.format, type.width, type.height)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix.get(i % matrix.width, i / matrix.width)) Color.BLACK else Color.WHITE
        }
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}
