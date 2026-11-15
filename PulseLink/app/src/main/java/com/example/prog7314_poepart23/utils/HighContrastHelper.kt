package com.example.prog7314_poepart23.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle

/**
 * High contrast mode (Settings > Appearance > High contrast switch).
 *
 * The app's colours all come from tokens (res/values/colors.xml and the dark set in
 * res/values-night/colors.xml). High contrast adds two more complete palettes:
 *
 *   res/values-round/colors.xml        high contrast, light  (pure white background, black text)
 *   res/values-round-night/colors.xml  high contrast, dark   (pure black background, white text)
 *
 * Android only switches resource folders on configuration qualifiers, and the "round screen"
 * qualifier is the one a phone app can switch per Activity without side effects (no phone app
 * ships round-screen resources otherwise). So when high contrast is on, each Activity's
 * configuration is flagged as a round screen before it inflates its layout, which makes Android
 * read the palettes above. Light / dark still follows Settings > Appearance > Theme, so there is a
 * high contrast version of both. No layout or drawable is touched.
 */
object HighContrastHelper {

    private const val PREFS_NAME = "pulselink_high_contrast_prefs"
    private const val KEY_ENABLED = "high_contrast_enabled"

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
        val wanted = if (isEnabled(activity)) Configuration.SCREENLAYOUT_ROUND_YES else Configuration.SCREENLAYOUT_ROUND_NO
        if ((res.configuration.screenLayout and Configuration.SCREENLAYOUT_ROUND_MASK) == wanted) return
        val config = Configuration(res.configuration)
        config.screenLayout = (config.screenLayout and Configuration.SCREENLAYOUT_ROUND_MASK.inv()) or wanted
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)
    }

    /** Turns high contrast on or off and rebuilds every open screen with the matching colours. */
    fun setEnabled(activity: Activity, enabled: Boolean) {
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        TextSizeHelper.recreateAll(activity)
    }
}
