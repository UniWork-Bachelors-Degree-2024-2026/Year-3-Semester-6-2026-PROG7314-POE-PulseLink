package com.example.prog7314_poepart23.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Adjustable text size (Settings > Appearance > Text size slider).
 *
 * Every text in the layouts is sized in sp, so scaling the font scale of each Activity's resources
 * resizes the whole app. The chosen step is stored in its own preferences file (so signing out does
 * not reset it) and is applied to every Activity before it inflates its layout. The step is
 * multiplied with the system font size, so a user who already enlarged text in Android settings
 * keeps that and the app setting stacks on top of it. "Default" leaves the system size untouched.
 */
object TextSizeHelper {

    private const val PREFS_NAME = "pulselink_text_size_prefs"
    private const val KEY_STEP = "text_size_step"

    private const val DEFAULT_STEP = 1
    private const val MAX_FONT_SCALE = 2.0f

    // Small, Default, Large, Extra large
    private val MULTIPLIERS = floatArrayOf(0.85f, 1.0f, 1.15f, 1.3f)

    private val openActivities = ArrayList<WeakReference<Activity>>()

    fun getStep(context: Context): Int {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_STEP, DEFAULT_STEP)
        return stored.coerceIn(0, MULTIPLIERS.size - 1)
    }

    /** Registers the hook that applies the stored size to each Activity. Call once from Application.onCreate(). */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            // Runs before the Activity's onCreate (Android 10+), so the very first layout inflation is already scaled.
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyTo(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Older Android versions have no pre-create callback, so apply here as well.
                applyTo(activity)
                synchronized(openActivities) { openActivities.add(WeakReference(activity)) }
            }

            override fun onActivityDestroyed(activity: Activity) {
                synchronized(openActivities) { openActivities.removeAll { it.get() == null || it.get() === activity } }
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    /** Sets this Activity's font scale to (system font scale x chosen step). */
    private fun applyTo(activity: Activity) {
        val res = activity.resources
        val systemScale = Resources.getSystem().configuration.fontScale
        val target = (systemScale * MULTIPLIERS[getStep(activity)]).coerceAtMost(MAX_FONT_SCALE)
        if (res.configuration.fontScale == target) return
        val config = android.content.res.Configuration(res.configuration)
        config.fontScale = target
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)
    }

    /** Saves the chosen step (0 = Small ... 3 = Extra large) and rebuilds every open screen with it. */
    fun setStep(activity: Activity, step: Int) {
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_STEP, step).apply()
        recreateAll(activity)
    }

    /** Overall font scale a step gives (system font scale x step), used by the live preview. */
    fun scaleFor(step: Int): Float =
        (Resources.getSystem().configuration.fontScale * MULTIPLIERS[step.coerceIn(0, MULTIPLIERS.size - 1)])
            .coerceAtMost(MAX_FONT_SCALE)

    /** Rebuilds every open screen (including the ones behind [current]) so they all pick up a changed appearance setting. */
    fun recreateAll(current: Activity) {
        val toRecreate = synchronized(openActivities) { openActivities.mapNotNull { it.get() } }
        toRecreate.filter { !it.isFinishing && it !== current }.forEach { it.recreate() }
        current.recreate()
    }
}
