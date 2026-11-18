package com.dichtruyen.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** Một khối chữ đã dịch, toạ độ theo màn hình thật. */
data class TranslatedBlock(val box: Rect, val text: String)

/**
 * Nhận dạng chữ trên ảnh chụp màn hình (OCR) rồi dịch sang tiếng Việt.
 * Mọi thứ chạy offline bằng Google ML Kit.
 */
class OcrTranslateEngine(private val lang: SourceLang) {

    private val recognizer: TextRecognizer = when (lang) {
        SourceLang.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        SourceLang.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        SourceLang.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        SourceLang.ENGLISH -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(lang.code)
            .setTargetLanguage(TranslateLanguage.VIETNAMESE)
            .build()
    )

    /** Nhớ các câu đã dịch để lướt lại không phải dịch lần nữa. */
    private val cache = LruCache<String, String>(800)

    suspend fun ensureModel() {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    /**
     * @param ignoreAboveY bỏ qua chữ nằm trên vạch này (thanh trạng thái: giờ, pin…)
     * @param ignoreRects bỏ qua chữ chạm vào các vùng này (bong bóng nổi của app)
     */
    suspend fun process(bitmap: Bitmap, ignoreAboveY: Int, ignoreRects: List<Rect>): List<TranslatedBlock> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val out = ArrayList<TranslatedBlock>()
        for (block in result.textBlocks) {
            val box = block.boundingBox ?: continue
            if (box.bottom <= ignoreAboveY) continue
            if (ignoreRects.any { Rect.intersects(it, box) }) continue

            val source = joinLines(block)
            if (!isMeaningful(source)) continue

            val vi = cache.get(source) ?: try {
                translator.translate(source).await().also { cache.put(source, it) }
            } catch (e: Exception) {
                continue
            }
            out += TranslatedBlock(Rect(box), vi)
        }
        return out
    }

    fun close() {
        recognizer.close()
        translator.close()
    }

    // ---------- xử lý chữ ----------

    private fun joinLines(block: Text.TextBlock): String {
        val lines = block.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""
        if (lang.isCjk) return lines.joinToString("")

        val sb = StringBuilder()
        for (line in lines) {
            if (sb.isEmpty()) {
                sb.append(line)
            } else if (sb.endsWith("-") && line.first().isLetter()) {
                sb.setLength(sb.length - 1) // nối chữ bị ngắt dòng: "some-" + "thing"
                sb.append(line)
            } else {
                sb.append(' ').append(line)
            }
        }
        var text = sb.toString()
        if (lang == SourceLang.ENGLISH) text = fixAllCaps(text)
        return text
    }

    /** Truyện tranh tiếng Anh hay viết HOA toàn bộ – đổi về chữ thường cho máy dịch hiểu tốt hơn. */
    private fun fixAllCaps(text: String): String {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 4) return text
        val upper = letters.count { it.isUpperCase() }
        if (upper < letters.length * 0.8) return text

        val lower = text.lowercase()
        val sb = StringBuilder(lower.length)
        var capNext = true
        for (c in lower) {
            if (capNext && c.isLetter()) {
                sb.append(c.uppercaseChar()); capNext = false
            } else {
                sb.append(c)
            }
            if (c == '.' || c == '!' || c == '?') capNext = true
        }
        return sb.toString()
            .replace(Regex("\\bi\\b"), "I")
            .replace(Regex("\\bi'"), "I'")
    }

    private fun isMeaningful(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        return if (lang.isCjk || lang == SourceLang.KOREAN) letters >= 1 else letters >= 2
    }
}
