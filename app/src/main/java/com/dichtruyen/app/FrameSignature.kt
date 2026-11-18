package com.dichtruyen.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * "Dấu vân tay" thu nhỏ của một khung hình (lưới 32 x 64 ô xám).
 * Dùng để biết người dùng có đang lướt / đổi trang hay không
 * mà không phải chạy OCR liên tục (tiết kiệm pin).
 */
object FrameSignature {
    const val COLS = 32
    const val ROWS = 64

    /** Ô được coi là "đổi" nếu độ sáng lệch quá mức này (0–255). */
    private const val PIXEL_DIFF = 14

    fun compute(bmp: Bitmap): IntArray {
        // thu nhỏ 2 bước để lấy trung bình cho mượt
        val mid = Bitmap.createScaledBitmap(bmp, COLS * 6, ROWS * 6, true)
        val small = Bitmap.createScaledBitmap(mid, COLS, ROWS, true)
        val px = IntArray(COLS * ROWS)
        small.getPixels(px, 0, COLS, 0, 0, COLS, ROWS)
        if (small !== mid) small.recycle()
        if (mid !== bmp) mid.recycle()
        return IntArray(px.size) {
            val c = px[it]
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
    }

    /** Đánh dấu các ô nằm dưới những vùng cần bỏ qua (bản dịch đang hiển thị, bong bóng…). */
    fun mask(rects: List<Rect>, screenW: Int, screenH: Int): BooleanArray {
        val m = BooleanArray(COLS * ROWS)
        val cw = screenW.toFloat() / COLS
        val ch = screenH.toFloat() / ROWS
        for (r in rects) {
            val c0 = max(0, floor(r.left / cw).toInt() - 1)
            val c1 = min(COLS - 1, floor(r.right / cw).toInt() + 1)
            val r0 = max(0, floor(r.top / ch).toInt() - 1)
            val r1 = min(ROWS - 1, floor(r.bottom / ch).toInt() + 1)
            for (y in r0..r1) for (x in c0..c1) m[y * COLS + x] = true
        }
        return m
    }

    /** Tỉ lệ ô bị thay đổi (0..1), không tính các ô bị che. */
    fun changedFraction(a: IntArray, b: IntArray, mask: BooleanArray?): Float {
        var total = 0
        var changed = 0
        for (i in a.indices) {
            if (mask != null && mask[i]) continue
            total++
            if (abs(a[i] - b[i]) > PIXEL_DIFF) changed++
        }
        return if (total == 0) 0f else changed.toFloat() / total
    }
}
