package com.dichtruyen.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Lớp trong suốt phủ toàn màn hình, vẽ các ô bản dịch tiếng Việt
 * đè lên đúng vị trí chữ gốc.
 */
@SuppressLint("ViewConstructor")
class TranslationOverlayView(
    ctx: Context,
    private val screenW: Int,
    private val screenH: Int,
) : View(ctx) {

    private class Item(val rect: RectF, val layout: StaticLayout)

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB0B0B0"); style = Paint.Style.STROKE; strokeWidth = dp(1f)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; typeface = Typeface.DEFAULT_BOLD
    }

    private var items: List<Item> = emptyList()

    /** Vùng (toạ độ màn hình) mà các ô dịch đang chiếm. */
    @Volatile
    var drawnRects: List<Rect> = emptyList()
        private set

    fun setBlocks(blocks: List<TranslatedBlock>) {
        items = blocks.map { layoutBlock(it) }
        drawnRects = items.map {
            Rect(it.rect.left.toInt(), it.rect.top.toInt(), it.rect.right.toInt() + 1, it.rect.bottom.toInt() + 1)
        }
        invalidate()
    }

    fun clear() {
        items = emptyList()
        drawnRects = emptyList()
        invalidate()
    }

    private fun layoutBlock(b: TranslatedBlock): Item {
        val pad = dp(4f)
        val box = b.box
        val width = min(max(box.width().toFloat(), dp(110f)), screenW.toFloat())
        val innerW = max(1, (width - 2 * pad).toInt())
        val targetH = max(box.height().toFloat(), dp(26f))

        // Tìm cỡ chữ lớn nhất vừa với ô gốc
        val maxSize = dp(20f)
        val minSize = dp(10f)
        var size = maxSize
        var layout = makeLayout(b.text, innerW, size)
        while (size > minSize && layout.height + 2 * pad > targetH) {
            size -= dp(1f)
            layout = makeLayout(b.text, innerW, size)
        }

        val h = max(targetH, layout.height + 2 * pad)
        val cx = box.exactCenterX()
        val cy = box.exactCenterY()
        var left = cx - width / 2
        var top = cy - h / 2
        left = left.coerceIn(0f, max(0f, screenW - width))
        top = top.coerceIn(0f, max(0f, screenH - h))
        return Item(RectF(left, top, left + width, top + h), layout)
    }

    private fun makeLayout(text: String, width: Int, size: Float): StaticLayout {
        textPaint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(textPaint), width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }

    private val loc = IntArray(2)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return
        // đổi toạ độ màn hình -> toạ độ của view
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val radius = dp(6f)
        val pad = dp(4f)
        for (item in items) {
            canvas.drawRoundRect(item.rect, radius, radius, bgPaint)
            canvas.drawRoundRect(item.rect, radius, radius, borderPaint)
            canvas.save()
            val textTop = item.rect.top + (item.rect.height() - item.layout.height) / 2
            canvas.translate(item.rect.left + pad, textTop)
            item.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }
}
