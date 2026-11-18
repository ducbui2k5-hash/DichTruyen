package com.dichtruyen.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * Quản lý 2 cửa sổ nổi:
 *  - Lớp phủ trong suốt vẽ bản dịch (không nhận chạm, ngón tay xuyên qua để lướt truyện)
 *  - Bong bóng tròn: chạm = bật/tắt dịch, kéo = di chuyển, giữ lâu = mở cài đặt
 */
class OverlayController(
    private val ctx: Context,
    private val screenW: Int,
    private val screenH: Int,
    private val onOpenSettings: () -> Unit,
) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val density = ctx.resources.displayMetrics.density
    private val bubbleSize = (54 * density).toInt()

    private val overlayView = TranslationOverlayView(ctx, screenW, screenH)
    private val bubble = TextView(ctx)

    @Volatile
    var enabled = true
        private set
    private var busy = false
    private var attached = false

    @Suppress("DEPRECATION")
    private val windowType =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    private val overlayParams = WindowManager.LayoutParams(
        screenW, screenH, windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0; y = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // Android 12+: lớp phủ phải hơi trong suốt (≤ 80%) thì chạm mới xuyên qua được xuống app truyện
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alpha = ctx.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
        }
    }

    private val bubbleParams = WindowManager.LayoutParams(
        bubbleSize, bubbleSize, windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = screenW - bubbleSize - (6 * density).toInt()
        y = screenH / 3
    }

    fun show() {
        if (attached) return
        setupBubble()
        wm.addView(overlayView, overlayParams)
        wm.addView(bubble, bubbleParams) // thêm sau để nằm trên lớp phủ
        attached = true
    }

    fun remove() {
        if (!attached) return
        try { wm.removeView(bubble) } catch (_: Exception) {}
        try { wm.removeView(overlayView) } catch (_: Exception) {}
        attached = false
    }

    fun showTranslations(blocks: List<TranslatedBlock>) = overlayView.setBlocks(blocks)
    fun clearTranslations() = overlayView.clear()
    fun translationRects(): List<Rect> = overlayView.drawnRects

    fun setBusy(value: Boolean) {
        busy = value
        refreshBubble()
    }

    fun bubbleRect(): Rect? {
        if (!attached || bubble.width == 0) return null
        val loc = IntArray(2)
        bubble.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + bubble.width, loc[1] + bubble.height)
    }

    private fun toggle() {
        enabled = !enabled
        if (!enabled) clearTranslations()
        refreshBubble()
    }

    private fun refreshBubble() {
        val color = when {
            !enabled -> Color.parseColor("#9E9E9E")
            busy -> Color.parseColor("#FB8C00")
            else -> Color.parseColor("#2E7D32")
        }
        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((2 * density).toInt(), Color.WHITE)
        }
        bubble.text = when {
            !enabled -> "Tắt"
            busy -> "…"
            else -> "Dịch"
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubble() {
        bubble.gravity = Gravity.CENTER
        bubble.setTextColor(Color.WHITE)
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        bubble.elevation = 6 * density
        refreshBubble()

        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        bubble.setOnTouchListener { _: View, e: MotionEvent ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = bubbleParams.x; startY = bubbleParams.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        bubbleParams.x = (startX + dx).toInt().coerceIn(0, screenW - bubbleSize)
                        bubbleParams.y = (startY + dy).toInt().coerceIn(0, screenH - bubbleSize)
                        try { wm.updateViewLayout(bubble, bubbleParams) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (e.eventTime - e.downTime > 600) onOpenSettings() else toggle()
                    }
                }
            }
            true
        }
    }
}
