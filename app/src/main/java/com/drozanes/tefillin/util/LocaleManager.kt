package com.drozanes.tefillin.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * Manages runtime switching between Hebrew and English.
 * Uses official AndroidX AppCompatDelegate per-app locales API with full RTL support.
 */
object LocaleManager {
    private const val PREF_NAME = "tefillin_prefs"
    private const val KEY_LANG = "tefillin_lang"

    const val LANG_HEBREW = "he"
    const val LANG_ENGLISH = "en"

    data class Language(val code: String, val displayName: String, val shortCode: String)

    val SUPPORTED_LANGUAGES = listOf(
        Language(code = LANG_HEBREW, displayName = "עברית", shortCode = "עב"),
        Language(code = LANG_ENGLISH, displayName = "English", shortCode = "EN")
    )

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANG, LANG_HEBREW) ?: LANG_HEBREW
    }

    fun setLanguage(context: Context, langCode: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANG, langCode).apply()

        // Apply using official AndroidX per-app locales API
        val localeList = LocaleListCompat.forLanguageTags(langCode)
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    fun getNextLanguage(context: Context): String {
        val current = getLanguage(context)
        return if (current == LANG_HEBREW) LANG_ENGLISH else LANG_HEBREW
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
