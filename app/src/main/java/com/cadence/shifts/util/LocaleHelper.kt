package com.cadence.shifts.util

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import com.cadence.shifts.R
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import java.util.Locale

data class AppLanguageOption(
    val tag: String,
    val nativeLabel: String,
    @StringRes val labelRes: Int
)

object LocaleHelper {
    private const val PREFS_NAME = "cadence_locale_prefs"
    private const val KEY_LANGUAGE_TAG = "language_tag"

    val SUPPORTED_LANGUAGES: List<AppLanguageOption> = listOf(
        AppLanguageOption(tag = "", nativeLabel = "", labelRes = R.string.lang_system_default),
        AppLanguageOption(tag = "en", nativeLabel = "English", labelRes = R.string.lang_english),
        AppLanguageOption(tag = "zh-Hant", nativeLabel = "繁體中文", labelRes = R.string.lang_zh_hant),
        AppLanguageOption(tag = "zh-Hans", nativeLabel = "简体中文", labelRes = R.string.lang_zh_hans),
        AppLanguageOption(tag = "ja", nativeLabel = "日本語", labelRes = R.string.lang_japanese),
        AppLanguageOption(tag = "ko", nativeLabel = "한국어", labelRes = R.string.lang_korean),
        AppLanguageOption(tag = "es", nativeLabel = "Español", labelRes = R.string.lang_spanish),
        AppLanguageOption(tag = "de", nativeLabel = "Deutsch", labelRes = R.string.lang_german),
        AppLanguageOption(tag = "fr", nativeLabel = "Français", labelRes = R.string.lang_french),
        AppLanguageOption(tag = "pt", nativeLabel = "Português", labelRes = R.string.lang_portuguese),
        AppLanguageOption(tag = "it", nativeLabel = "Italiano", labelRes = R.string.lang_italian),
        AppLanguageOption(tag = "vi", nativeLabel = "Tiếng Việt", labelRes = R.string.lang_vietnamese),
        AppLanguageOption(tag = "th", nativeLabel = "ไทย", labelRes = R.string.lang_thai),
        AppLanguageOption(tag = "in", nativeLabel = "Bahasa Indonesia", labelRes = R.string.lang_indonesian)
    )

    fun normalizeTag(rawTag: String): String {
        val clean = rawTag.trim().replace('_', '-')
        if (clean.isBlank()) return ""
        val lower = clean.lowercase(Locale.ROOT)
        return when {
            lower == "id" || lower.startsWith("id-") || lower == "in" || lower.startsWith("in-") -> "in"
            lower.startsWith("zh-hant") ||
                lower == "zh-tw" ||
                lower == "zh-hk" ||
                lower == "zh-mo" -> "zh-Hant"
            lower.startsWith("zh-hans") ||
                lower == "zh-cn" ||
                lower == "zh-sg" ||
                lower == "zh" -> "zh-Hans"
            lower.startsWith("ja") -> "ja"
            lower.startsWith("ko") -> "ko"
            lower.startsWith("es") -> "es"
            lower.startsWith("de") -> "de"
            lower.startsWith("fr") -> "fr"
            lower.startsWith("pt") -> "pt"
            lower.startsWith("it") -> "it"
            lower.startsWith("vi") -> "vi"
            lower.startsWith("th") -> "th"
            lower.startsWith("en") -> "en"
            else -> ""
        }
    }

    fun getSelectedLanguageTag(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val lm = context.getSystemService(LocaleManager::class.java)
            val appLocales = lm?.applicationLocales
            if (appLocales != null) {
                if (appLocales.isEmpty) return ""
                val first = appLocales.get(0)
                if (first != null) {
                    return normalizeTag(first.toLanguageTag())
                }
            }
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return normalizeTag(prefs.getString(KEY_LANGUAGE_TAG, "").orEmpty())
    }

    fun resolveDisplayOption(context: Context): AppLanguageOption {
        val tag = getSelectedLanguageTag(context)
        return SUPPORTED_LANGUAGES.firstOrNull { it.tag == tag } ?: SUPPORTED_LANGUAGES.first()
    }

    fun setAppLanguage(context: Context, rawTag: String) {
        val tag = normalizeTag(rawTag)
        val currentTag = getSelectedLanguageTag(context)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE_TAG, tag)
            .commit()

        applyFirebaseAuthLanguage(tag)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val lm = context.getSystemService(LocaleManager::class.java)
            val targetLocales = if (tag.isBlank()) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(tag)
            }
            if (lm != null && lm.applicationLocales != targetLocales) {
                lm.applicationLocales = targetLocales
            }
        } else if (currentTag != tag) {
            findActivity(context)?.recreate()
        }
    }

    fun syncRuntimeLocale(context: Context) {
        val tag = getSelectedLanguageTag(context)
        if (tag.isNotBlank()) {
            val locale = parseLocaleForTag(tag)
            Locale.setDefault(locale)
        }
        applyFirebaseAuthLanguage(tag)
    }

    fun wrapContext(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            syncRuntimeLocale(base)
            return base
        }
        val prefs = base.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val tag = normalizeTag(prefs.getString(KEY_LANGUAGE_TAG, "").orEmpty())
        if (tag.isBlank()) {
            applyFirebaseAuthLanguage("")
            return base
        }
        val locale = parseLocaleForTag(tag)
        Locale.setDefault(locale)
        applyFirebaseAuthLanguage(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * Returns a context configured with the user's current app language even if invoked from
     * an Application or background worker context on API < 33.
     */
    fun localizedContext(context: Context): Context = wrapContext(context)

    private fun parseLocaleForTag(tag: String): Locale {
        return when (tag) {
            "zh-Hant" -> Locale.forLanguageTag("zh-Hant-TW")
            "zh-Hans" -> Locale.forLanguageTag("zh-Hans-CN")
            "in" -> Locale.forLanguageTag("id")
            else -> Locale.forLanguageTag(tag)
        }
    }

    private fun applyFirebaseAuthLanguage(tag: String) {
        runCatching {
            val firebaseCode = when (tag) {
                "" -> null
                "zh-Hant" -> "zh-TW"
                "zh-Hans" -> "zh-CN"
                "in" -> "id"
                else -> tag
            }
            if (firebaseCode == null) {
                Firebase.auth.useAppLanguage()
            } else {
                Firebase.auth.setLanguageCode(firebaseCode)
            }
        }
    }

    private tailrec fun findActivity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }
}
