package com.drozanes.tefillin.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Manages dynamic runtime switching between languages (Hebrew, English, French, etc.).
 * To add a new language, simply create `res/values-<lang>/strings.xml` and register it in SUPPORTED_LANGUAGES.
 */
object LocaleManager {
    private const val PREF_NAME = "tefillin_prefs"
    private const val KEY_LANG = "tefillin_lang"

    data class Language(val code: String, val displayName: String, val shortCode: String)

    val SUPPORTED_LANGUAGES = listOf(
        Language(code = "he", displayName = "עברית", shortCode = "עב"),
        Language(code = "en", displayName = "English", shortCode = "EN"),
        Language(code = "fr", displayName = "Français", shortCode = "FR")
    )

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANG, "he") ?: "he"
    }

    fun setLanguage(context: Context, langCode: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANG, langCode).apply()
    }

    fun getNextLanguage(context: Context): Language {
        val currentCode = getLanguage(context)
        val currentIndex = SUPPORTED_LANGUAGES.indexOfFirst { it.code == currentCode }
        val nextIndex = if (currentIndex >= 0) (currentIndex + 1) % SUPPORTED_LANGUAGES.size else 0
        return SUPPORTED_LANGUAGES[nextIndex]
    }

    fun getCurrentLanguageInfo(context: Context): Language {
        val currentCode = getLanguage(context)
        return SUPPORTED_LANGUAGES.find { it.code == currentCode } ?: SUPPORTED_LANGUAGES[0]
    }

    fun applyLocale(context: Context): Context {
        val langCode = getLanguage(context)
        val locale = Locale(langCode)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        config.setLayoutDirection(locale)

        return context.createConfigurationContext(config)
    }
}
