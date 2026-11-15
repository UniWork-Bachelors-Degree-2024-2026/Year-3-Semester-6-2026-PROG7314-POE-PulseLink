// Multi-Language Support (Localisation)
//
// Single source of truth for the app's display language. The user's choice is persisted in
// SharedPreferences ("pulselink_locale" / "app_language") so it survives process death and
// re-installs of the activity stack, and is applied through AppCompatDelegate's per-app
// language API, which re-creates every running activity with the new Configuration.
//
// Supported locales: English (default, res/values), isiZulu (res/values-zu) and
// Afrikaans (res/values-af).

package com.example.prog7314_poepart23.utils

import android.content.Context
import androidx.annotation.StringRes
import com.example.prog7314_poepart23.R
import androidx.appcompat.app.AppCompatActivity       // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatDelegate       // (Google Developers, 2024)
import androidx.core.os.LocaleListCompat              // (Google Developers, 2024)
import java.util.Locale

object LocaleHelper { // (Google Developers, 2024)

    // ------------------------------------------------------------------------------------
    // Supported language tags. These must match the res/values-<tag> qualifier folders.
    // ------------------------------------------------------------------------------------
    const val LANG_ENGLISH = "en"
    const val LANG_ZULU = "zu"
    const val LANG_AFRIKAANS = "af"

    val SUPPORTED_LANGUAGES = listOf(LANG_ENGLISH, LANG_ZULU, LANG_AFRIKAANS)

    private const val PREFS_NAME = "pulselink_locale"
    private const val KEY_LANGUAGE = "app_language"

    // ------------------------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------------------------

    /** Returns the stored language tag, falling back to English when nothing is stored yet. */
    fun getLanguage(context: Context): String {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null)
        return if (stored != null && stored in SUPPORTED_LANGUAGES) stored else LANG_ENGLISH
    }

    /** Persists [language] without applying it. Used when the user is still choosing. */
    fun saveLanguage(context: Context, language: String) {
        val safe = if (language in SUPPORTED_LANGUAGES) language else LANG_ENGLISH
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, safe)
            .apply()
    }

    /** True when the user has explicitly chosen a language at least once. */
    fun hasStoredLanguage(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(KEY_LANGUAGE)

    // ------------------------------------------------------------------------------------
    // Application
    // ------------------------------------------------------------------------------------

    /**
     * Persists [language] and immediately updates the Locale configuration. AppCompat recreates
     * the visible activity, so every string resource is re-resolved against the new locale.
     */
    fun setLanguage(context: Context, language: String) {
        saveLanguage(context, language)
        applyLocale(language)
    }

    /** Re-applies whatever language is stored. Called once from PulseLinkApplication.onCreate(). */
    fun applyStoredLanguage(context: Context) {
        appContext = context.applicationContext
        applyLocale(getLanguage(context))
    }

    // Application context used by non-UI code (repositories, view models, validators) so that
    // user-facing messages can still be resolved in the language the user picked.
    private var appContext: Context? = null

    /**
     * Resolves a string resource in the user's chosen language from anywhere in the app, even
     * where no Activity context is available. Returns an empty string only if the helper has
     * not been initialised yet (never the case after PulseLinkApplication.onCreate()).
     */
    fun string(@StringRes resId: Int, vararg args: Any): String {
        val base = appContext ?: return ""
        val localised = wrapContext(base)
        return if (args.isEmpty()) localised.getString(resId) else localised.getString(resId, *args)
    }

    /**
     * Like [string], but falls back to [fallback] (an English format template) when the helper
     * has not been initialised, e.g. in plain JVM unit tests where no Application exists.
     * Inside the running app this always resolves in the language the user selected.
     */
    fun stringOr(@StringRes resId: Int, fallback: String, vararg args: Any): String {
        val base = appContext
            ?: return if (args.isEmpty()) fallback else String.format(Locale.ROOT, fallback, *args)
        val localised = wrapContext(base)
        return if (args.isEmpty()) localised.getString(resId) else localised.getString(resId, *args)
    }

    /** Same as [string] but for quantity strings (plurals). */
    fun quantityString(resId: Int, quantity: Int, vararg args: Any): String {
        val base = appContext ?: return ""
        return wrapContext(base).resources.getQuantityString(resId, quantity, *args)
    }

    private fun applyLocale(language: String) {
        val locales = LocaleListCompat.forLanguageTags(language)
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    /**
     * Wraps a base Context in the stored locale. Activities that need the correct Configuration
     * before AppCompat has attached its delegate (for example a splash screen that formats text
     * in onCreate) can call this from attachBaseContext().
     */
    fun wrapContext(base: Context): Context {
        val locale = Locale.forLanguageTag(getLanguage(base))
        Locale.setDefault(locale)
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    // ------------------------------------------------------------------------------------
    // Display helpers
    // ------------------------------------------------------------------------------------

    /** Endonym (the language's own name) used on the language selection screen. */
    fun displayName(language: String): String = when (language) {
        LANG_ZULU -> "isiZulu"
        LANG_AFRIKAANS -> "Afrikaans"
        else -> "English"
    }

    // ------------------------------------------------------------------------------------
    // Server-supplied values -> localised labels
    // The API sends English keywords such as "Excellent", "Flagged" or "ElevatedHeartRate".
    // These helpers map the known ones onto string resources so they follow the app language;
    // anything unrecognised is shown unchanged rather than hidden.
    // ------------------------------------------------------------------------------------

    /** Signal quality keyword (Excellent / Good / Fair / Poor) as a localised label. */
    fun qualityLabel(context: Context, raw: String?): String = when (raw?.trim()?.lowercase()) {
        "excellent" -> context.getString(R.string.l10n_quality_excellent)
        "good" -> context.getString(R.string.l10n_quality_good)
        "fair" -> context.getString(R.string.l10n_quality_fair)
        "poor" -> context.getString(R.string.l10n_quality_poor)
        null, "", "unknown" -> context.getString(R.string.l10n_unknown)
        else -> raw
    }

    /** Localised recording lifecycle; legacy rhythm labels are not clinical evidence. */
    fun statusLabel(context: Context, raw: String?): String = context.getString(SessionStatusLabels.resource(raw))

    /**
     * Facility type as stored in the directory (English keyword such as "Tertiary Hospital")
     * mapped to a localised label. Unknown types are shown unchanged.
     */
    fun facilityTypeLabel(context: Context, raw: String?): String = when (raw?.trim()?.lowercase()) {
        "tertiary hospital" -> context.getString(R.string.rt_facility_type_tertiary)
        "provincial hospital" -> context.getString(R.string.rt_facility_type_provincial)
        "regional hospital" -> context.getString(R.string.rt_facility_type_regional)
        "community health centre" -> context.getString(R.string.rt_facility_type_chc)
        "public primary healthcare clinic" -> context.getString(R.string.rt_facility_type_clinic)
        else -> raw.orEmpty()
    }

    /** Alert type keyword from the API as a localised title. */
    fun alertTypeLabel(context: Context, raw: String?): String = when (raw?.trim()?.lowercase()?.replace(" ", "")) {
        "elevatedheartrate" -> context.getString(R.string.l10n_alert_elevated_hr)
        "tachycardia" -> context.getString(R.string.l10n_alert_tachycardia)
        "bradycardia" -> context.getString(R.string.l10n_alert_bradycardia)
        "leaddetached", "leadsoff", "leaddisconnected" -> context.getString(R.string.l10n_alert_lead_detached)
        "asystole", "nopulse" -> context.getString(R.string.l10n_alert_asystole)
        else -> raw.orEmpty()
    }

    /**
     * Gender is stored as the label that was on screen when it was saved, so a value saved while
     * the app was in English ("Male") must still read correctly after switching to isiZulu or
     * Afrikaans. Matches the stored text against the gender labels of every supported language and
     * returns the label in the current language; unknown free text is returned unchanged.
     */
    fun genderLabel(context: Context, stored: String?): String? {
        if (stored.isNullOrBlank()) return null
        val ids = intArrayOf(
            R.string.patient_profile_gender_male,
            R.string.patient_profile_gender_female,
            R.string.patient_profile_gender_other,
            R.string.patient_profile_gender_prefer_not_to_say
        )
        for (lang in SUPPORTED_LANGUAGES) {
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocale(Locale.forLanguageTag(lang))
            val langContext = context.createConfigurationContext(config)
            for (i in ids.indices) {
                if (langContext.getString(ids[i]).equals(stored.trim(), ignoreCase = true)) {
                    return context.getString(ids[i])
                }
            }
        }
        return stored
    }

    /** Restarts [activity] so the whole back stack is rebuilt in the newly chosen language. */
    fun recreateActivity(activity: AppCompatActivity) {
        activity.recreate()
    }
}
