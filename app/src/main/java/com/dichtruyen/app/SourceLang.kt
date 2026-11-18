package com.dichtruyen.app

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage

/** Ngôn ngữ gốc của truyện. Đích luôn là tiếng Việt. */
enum class SourceLang(val code: String, val label: String, val isCjk: Boolean) {
    ENGLISH(TranslateLanguage.ENGLISH, "Tiếng Anh", false),
    CHINESE(TranslateLanguage.CHINESE, "Tiếng Trung", true),
    JAPANESE(TranslateLanguage.JAPANESE, "Tiếng Nhật", true),
    KOREAN(TranslateLanguage.KOREAN, "Tiếng Hàn", false);

    companion object {
        private const val PREFS = "dich_truyen"
        private const val KEY = "src_lang"

        fun load(ctx: Context): SourceLang {
            val code = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.code == code } ?: ENGLISH
        }

        fun save(ctx: Context, lang: SourceLang) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang.code).apply()
        }
    }
}
