package com.dichtruyen.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dịch vụ chạy nền: chụp màn hình liên tục, phát hiện khi bạn lướt,
 * chờ màn hình đứng yên rồi OCR + dịch và hiển thị bản dịch đè lên.
 */
class TranslateService : Service() {

    companion object {
        const val ACTION_START = "com.dichtruyen.app.START"
        const val ACTION_STOP = "com.dichtruyen.app.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        private const val TAG = "DichTruyen"
        private const val CHANNEL_ID = "dich_truyen"
        private const val NOTIF_ID = 1

        /** Bao lâu kiểm tra màn hình 1 lần (ms). */
        private const val POLL_MS = 200L
        /** Màn hình phải đứng yên bao nhiêu lần kiểm tra liên tiếp mới dịch. */
        private const val STABLE_TICKS = 2
        /** > 5% ô thay đổi = coi như đã lướt / đổi trang. */
        private const val CHANGE_THRESHOLD = 0.05f

        @Volatile
        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlay: OverlayController? = null
    private var engine: OcrTranslateEngine? = null
    private var loopJob: Job? = null

    private var screenW = 0
    private var screenH = 0
    private var statusBarH = 0
    private var statusRect = Rect()

    /** Khung hình mới nhất đã lấy được (+ thời điểm, theo System.nanoTime). */
    @Volatile private var latestFrame: Bitmap? = null
    @Volatile private var latestFrameTs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // Bắt buộc gọi startForeground TRƯỚC khi lấy MediaProjection (Android 10+)
                startForegroundCompat()
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                if (data == null) {
                    stopSelf(); return START_NOT_STICKY
                }
                if (projection == null) setup(code, data)
            }
        }
        return START_NOT_STICKY
    }

    private fun setup(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection lỗi", e); null
        }
        if (mp == null) {
            Toast.makeText(this, "Không lấy được quyền ghi màn hình", Toast.LENGTH_LONG).show()
            stopSelf(); return
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // người dùng tắt chia sẻ màn hình từ hệ thống
                scope.launch { stopSelf() }
            }
        }, Handler(Looper.getMainLooper()))

        readScreenSize()
        val dpi = resources.displayMetrics.densityDpi
        val reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = mp.createVirtualDisplay(
            "DichTruyenCapture", screenW, screenH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )

        val lang = SourceLang.load(this)
        engine = OcrTranslateEngine(lang)

        overlay = OverlayController(this, screenW, screenH) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.also { it.show() }

        isRunning = true
        Toast.makeText(this, "Đang dịch ${lang.label} → Tiếng Việt. Chạm bong bóng để bật/tắt.", Toast.LENGTH_LONG).show()
        startLoop()
    }

    private fun readScreenSize() {
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels; screenH = dm.heightPixels
        }
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        statusBarH = if (id > 0) resources.getDimensionPixelSize(id) else (24 * resources.displayMetrics.density).toInt()
        statusRect = Rect(0, 0, screenW, statusBarH)
    }

    /** Lấy khung hình mới (null nếu màn hình không đổi kể từ lần trước). */
    private fun grabBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return null
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val full = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            val bmp = if (rowPadding == 0) full else Bitmap.createBitmap(full, 0, 0, image.width, image.height)
            latestFrame = bmp
            latestFrameTs = image.timestamp
            return bmp
        } catch (e: Exception) {
            Log.e(TAG, "Đọc khung hình lỗi", e)
            return null
        } finally {
            image.close()
        }
    }

    private enum class Phase { WAITING, SHOWING }

    private fun startLoop() {
        loopJob = scope.launch {
            var phase = Phase.WAITING
            var candidate: Bitmap? = null
            var candidateSig: IntArray? = null
            var stableTicks = 0
            var shownSig: IntArray? = null
            var shownBubble: Rect? = null
            var clearedAt = System.nanoTime()

            fun resetToWaiting() {
                phase = Phase.WAITING
                candidate = null; candidateSig = null; stableTicks = 0
                clearedAt = System.nanoTime()
            }

            while (isActive) {
                delay(POLL_MS)
                val ov = overlay ?: break
                val eng = engine ?: break

                if (!ov.enabled) {
                    if (phase == Phase.SHOWING) ov.clearTranslations()
                    resetToWaiting()
                    withContext(Dispatchers.Default) { grabBitmap() } // bỏ khung cũ
                    continue
                }

                val frame = withContext(Dispatchers.Default) { grabBitmap() }

                when (phase) {
                    Phase.SHOWING -> {
                        if (frame == null) continue // màn hình đứng yên
                        val sig = withContext(Dispatchers.Default) { FrameSignature.compute(frame) }
                        val ignore = ov.translationRects() + listOfNotNull(ov.bubbleRect(), shownBubble, statusRect)
                        val mask = FrameSignature.mask(ignore, screenW, screenH)
                        val base = shownSig ?: sig
                        if (FrameSignature.changedFraction(sig, base, mask) > CHANGE_THRESHOLD) {
                            // Bạn vừa lướt -> ẩn bản dịch cũ, chờ màn hình dừng
                            ov.clearTranslations()
                            resetToWaiting()
                        }
                    }

                    Phase.WAITING -> {
                        val ignore = listOfNotNull(ov.bubbleRect(), statusRect)
                        if (frame != null) {
                            val sig = withContext(Dispatchers.Default) { FrameSignature.compute(frame) }
                            val prev = candidateSig
                            val mask = FrameSignature.mask(ignore, screenW, screenH)
                            stableTicks = if (prev != null &&
                                FrameSignature.changedFraction(sig, prev, mask) <= CHANGE_THRESHOLD
                            ) stableTicks + 1 else 0
                            candidate = frame; candidateSig = sig
                        } else if (candidate != null) {
                            stableTicks++
                        } else {
                            // không có khung mới: dùng khung gần nhất nếu nó chụp sau khi đã xoá bản dịch
                            val lf = latestFrame
                            if (lf != null && latestFrameTs > clearedAt + 80_000_000L) {
                                candidate = lf
                                candidateSig = withContext(Dispatchers.Default) { FrameSignature.compute(lf) }
                                stableTicks = STABLE_TICKS
                            }
                        }

                        val cand = candidate ?: continue
                        val candSig = candidateSig ?: continue
                        if (stableTicks < STABLE_TICKS) continue

                        // ---- Màn hình đã đứng yên: OCR + dịch ----
                        val bubbleNow = ov.bubbleRect()
                        ov.setBusy(true)
                        val blocks = try {
                            eng.process(cand, statusBarH, listOfNotNull(bubbleNow))
                        } catch (e: Exception) {
                            Log.e(TAG, "OCR/dịch lỗi", e)
                            emptyList()
                        }
                        ov.setBusy(false)
                        if (!ov.enabled) continue

                        // Nếu trong lúc dịch bạn đã lướt tiếp thì bỏ kết quả cũ
                        val after = withContext(Dispatchers.Default) { grabBitmap() }
                        if (after != null) {
                            val s2 = withContext(Dispatchers.Default) { FrameSignature.compute(after) }
                            val m2 = FrameSignature.mask(listOfNotNull(ov.bubbleRect(), bubbleNow, statusRect), screenW, screenH)
                            if (FrameSignature.changedFraction(s2, candSig, m2) > CHANGE_THRESHOLD) {
                                candidate = after; candidateSig = s2; stableTicks = 0
                                continue
                            }
                        }

                        ov.showTranslations(blocks)
                        shownSig = candSig
                        shownBubble = bubbleNow
                        phase = Phase.SHOWING
                    }
                }
            }
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Dịch màn hình", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Dịch Truyện đang chạy")
            .setContentText("Lướt truyện, bản dịch sẽ hiện khi bạn dừng lại")
            .setContentIntent(openIntent)
            .addAction(0, "Dừng", stopIntent)
            .setOngoing(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
    }

    override fun onDestroy() {
        isRunning = false
        loopJob?.cancel()
        scope.cancel()
        overlay?.remove(); overlay = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        val p = projection
        projection = null
        try { p?.stop() } catch (_: Exception) {}
        engine?.close(); engine = null
        super.onDestroy()
    }
}
