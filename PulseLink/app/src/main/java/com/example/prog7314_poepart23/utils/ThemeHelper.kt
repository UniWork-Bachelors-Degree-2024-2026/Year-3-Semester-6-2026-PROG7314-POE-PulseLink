package com.example.prog7314_poepart23.utils

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.example.prog7314_poepart23.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Light / dark / follow-system appearance.
 *
 * The choice is stored in its own preferences file (not the session file) so signing out does not
 * reset it. [apply] is called from PulseLinkApplication.onCreate(); [setMode] / [showPicker] are used
 * by the Appearance row in Settings. AppCompatDelegate recreates the visible activities itself
 * when the mode changes, and the colours come from res/values-night/colors.xml.
 */
object ThemeHelper {

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    private const val PREFS_NAME = "pulselink_theme_prefs"
    private const val KEY_MODE = "theme_mode"

    fun getMode(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_MODE, MODE_SYSTEM)

    /** Applies the stored mode. Safe to call repeatedly. */
    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(toDelegateMode(getMode(context)))
    }

    fun setMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_MODE, mode).apply()
        AppCompatDelegate.setDefaultNightMode(toDelegateMode(mode))
    }

    /** Shows the System default / Light / Dark chooser. */
    fun showPicker(activity: Activity) {
        val labels = arrayOf(
            activity.getString(R.string.l10n_theme_system),
            activity.getString(R.string.l10n_theme_light),
            activity.getString(R.string.l10n_theme_dark)
        )
        MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_PulseLink_Dialog)
            .setTitle(activity.getString(R.string.patient_settings_text_appearance))
            .setSingleChoiceItems(labels, getMode(activity)) { dialog, which ->
                dialog.dismiss()
                setMode(activity, which)
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun toDelegateMode(mode: Int): Int = when (mode) {
        MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
}
