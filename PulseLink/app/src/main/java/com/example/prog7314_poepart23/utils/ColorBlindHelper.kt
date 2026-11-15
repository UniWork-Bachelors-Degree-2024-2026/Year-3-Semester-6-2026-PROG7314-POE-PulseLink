package com.example.prog7314_poepart23.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle

/**
 * Colour-blind friendly mode (Settings > Appearance > Colour-blind friendly switch).
 *
 * The normal palette tells "good", "warning" and "bad" apart mainly by green, amber and red, which
 * people with red-green colour blindness cannot separate. This mode swaps those status colours for a
 * blue / amber / magenta set (based on the Okabe-Ito colour-blind safe palette) and gives the status
 * dots different shapes (circle, triangle, diamond) so colour is never the only cue. Every status
 * in the app also keeps its text label.
 *
 * It works the same way as [HighContrastHelper]: the palettes live in resource folders and each
 * Activity's configuration is flagged before it inflates its layout so Android reads them:
 *
 *   res/values-desk/                 colour-blind palette, light
 *   res/values-desk-night/           colour-blind palette, dark
 *   res/values-round-desk/           colour-blind palette, high contrast light
 *   res/values-round-desk-night/     colour-blind palette, high contrast dark
 *   res/drawable-desk/               shape-coded status dots
 *
 * The "desk" UI mode is the qualifier used because a phone app never ships desk-dock resources
 * otherwise, so flagging it per Activity has no side effects. It combines with light / dark and with
 * high contrast. No layout is touched.
 */
object ColorBlindHelper {

    private const val PREFS_NAME = "pulselink_color_blind_prefs"
    private const val KEY_ENABLED = "color_blind_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /** Registers the hook that applies the stored setting to each Activity. Call once from Application.onCreate(). */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            // Runs before the Activity's onCreate (Android 10+), so the layout is inflated with the right palette.
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyTo(activity)
            }

            // Older Android versions have no pre-create callback, so apply here as well.
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyTo(activity)
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun applyTo(activity: Activity) {
        val res = activity.resources
        val wanted = if (isEnabled(activity)) Configuration.UI_MODE_TYPE_DESK else Configuration.UI_MODE_TYPE_NORMAL
        if ((res.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == wanted) return
        val config = Configuration(res.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or wanted
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)
    }

    /** Turns the mode on or off and rebuilds every open screen with the matching colours. */
    fun setEnabled(activity: Activity, enabled: Boolean) {
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        TextSizeHelper.recreateAll(activity)
    }
}
