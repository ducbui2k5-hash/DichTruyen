package com.dichtruyen.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var overlayBtn: MaterialButton
    private lateinit var startBtn: MaterialButton
    private lateinit var stopBtn: MaterialButton

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == Activity.RESULT_OK && data != null) {
                val i = Intent(this, TranslateService::class.java).apply {
                    action = TranslateService.ACTION_START
                    putExtra(TranslateService.EXTRA_RESULT_CODE, res.resultCode)
                    putExtra(TranslateService.EXTRA_DATA, data)
                }
                ContextCompat.startForegroundService(this, i)
                statusText.text = "✅ Đang chạy! Mở app đọc truyện và lướt thôi."
                moveTaskToBack(true) // quay ra để bạn mở app truyện
            } else {
                statusText.text = "Bạn cần bấm \"Bắt đầu\" / \"Cho phép\" ghi màn hình thì app mới đọc được chữ."
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            prepareModelThenCapture() // có hay không quyền thông báo vẫn chạy được
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }

        root.addView(TextView(this).apply {
            text = "Dịch màn hình truyện tranh"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Lướt đến đâu dịch đến đó – chạy offline, miễn phí."
            setPadding(0, dp(4), 0, dp(16))
        })

        // --- Chọn ngôn ngữ ---
        root.addView(sectionTitle("1. Truyện bạn đọc là tiếng gì?"))
        val current = SourceLang.load(this)
        val group = RadioGroup(this)
        SourceLang.entries.forEach { lang ->
            val rb = RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = lang.label + " → Tiếng Việt"
                tag = lang
                isChecked = lang == current
                textSize = 16f
            }
            group.addView(rb)
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val lang = g.findViewById<RadioButton>(checkedId)?.tag as? SourceLang ?: return@setOnCheckedChangeListener
            SourceLang.save(this, lang)
            if (TranslateService.isRunning) {
                statusText.text = "Đã đổi ngôn ngữ. Bấm BẮT ĐẦU DỊCH lại để áp dụng."
            }
        }
        root.addView(group)

        // --- Quyền hiển thị ---
        root.addView(sectionTitle("2. Cho phép hiện trên ứng dụng khác"))
        overlayBtn = MaterialButton(this).apply {
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }
        root.addView(overlayBtn, matchWidth())

        // --- Bắt đầu ---
        root.addView(sectionTitle("3. Bắt đầu"))
        startBtn = MaterialButton(this).apply {
            text = "BẮT ĐẦU DỊCH"
            textSize = 17f
            setOnClickListener { onStartClicked() }
        }
        root.addView(startBtn, matchWidth())
        stopBtn = MaterialButton(this).apply {
            text = "Dừng dịch"
            setOnClickListener {
                stopService(Intent(this@MainActivity, TranslateService::class.java))
                postDelayed({ refreshState() }, 300)
            }
        }
        root.addView(stopBtn, matchWidth())

        statusText = TextView(this).apply {
            setPadding(0, dp(12), 0, dp(12))
            textSize = 15f
        }
        root.addView(statusText)

        // --- Hướng dẫn ---
        root.addView(sectionTitle("Cách dùng"))
        root.addView(TextView(this).apply {
            textSize = 14f
            setLineSpacing(0f, 1.2f)
            text = """
                • Lần đầu cần mạng để tải gói dịch (~30MB mỗi ngôn ngữ). Sau đó dùng offline.
                • Khi Android hỏi ghi/chia sẻ màn hình: chọn "Toàn bộ màn hình" rồi bấm Bắt đầu.
                • Mở app đọc truyện, lướt bình thường. Khi bạn dừng tay khoảng nửa giây, bản dịch tiếng Việt sẽ hiện đè lên chữ gốc.
                • Đang lướt thì bản dịch tự ẩn, dừng lại thì dịch tiếp đoạn mới.
                • Bong bóng tròn: chạm = bật/tắt dịch • kéo = di chuyển • giữ lâu = mở màn hình này.
                • Màu bong bóng: xanh = đang bật, cam = đang dịch, xám = tắt.
                • Một số app chặn chụp màn hình (màn hình đen) thì không dịch được.
            """.trimIndent()
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun refreshState() {
        if (!::overlayBtn.isInitialized) return
        val ok = Settings.canDrawOverlays(this)
        overlayBtn.text = if (ok) "✓ Đã cho phép hiển thị" else "Cấp quyền hiển thị"
        overlayBtn.isEnabled = !ok
        stopBtn.isEnabled = TranslateService.isRunning
        if (TranslateService.isRunning && statusText.text.isNullOrEmpty()) {
            statusText.text = "✅ Đang chạy."
        }
    }

    private fun onStartClicked() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Hãy bật \"Hiển thị trên ứng dụng khác\" cho Dịch Truyện trước", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        prepareModelThenCapture()
    }

    /** Đảm bảo gói dịch đã có trên máy, rồi xin quyền ghi màn hình. */
    private fun prepareModelThenCapture() {
        val lang = SourceLang.load(this)
        statusText.text = "⏳ Đang chuẩn bị gói dịch ${lang.label} → Tiếng Việt…\n(lần đầu có thể mất 1–2 phút)"
        startBtn.isEnabled = false

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(lang.code)
                .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                .build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.close()
                startBtn.isEnabled = true
                if (TranslateService.isRunning) {
                    stopService(Intent(this, TranslateService::class.java))
                }
                statusText.text = "Gói dịch đã sẵn sàng. Hãy chọn \"Toàn bộ màn hình\" và bấm Bắt đầu."
                val mpm = getSystemService(MediaProjectionManager::class.java)
                val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
                    mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                } else {
                    mpm.createScreenCaptureIntent()
                }
                projectionLauncher.launch(captureIntent)
            }
            .addOnFailureListener { e ->
                translator.close()
                startBtn.isEnabled = true
                statusText.text = "❌ Không tải được gói dịch. Hãy bật Wi-Fi/4G rồi thử lại.\n(${e.message})"
            }
    }
}
