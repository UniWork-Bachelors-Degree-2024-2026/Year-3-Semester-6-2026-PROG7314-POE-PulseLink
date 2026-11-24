/*
 * Voice assistance (Settings > Appearance > Voice assistance).
 *
 * When the switch is on:
 *  - a microphone button is shown on every signed-in screen. Tap it and say a command to navigate
 *    ("open dashboard", "open reports", "go back" ...) or to hear information read aloud
 *    ("heart rate", "readings", "notifications"). Double tap and hold it for a menu with the same
 *    actions, which also works without speaking.
 *  - arrhythmia alerts and incoming notifications are read aloud automatically.
 *  - while a live ECG recording is open the current heart rate is read out every 20 seconds.
 *
 * Text-to-speech uses the platform TextToSpeech engine and speech input uses the system speech
 * recogniser dialog (RecognizerIntent). The app asks for the microphone permission (RECORD_AUDIO)
 * with a short explanation when voice assistance is switched on and again before listening if it
 * is still missing. Reading aloud needs no permission. No layout is changed while the switch is off.
 *
 * Reference List:
 * - Google Developers, 2024. TextToSpeech - Android Developers. [online] Available at: <https://developer.android.com/reference/android/speech/tts/TextToSpeech> [Accessed 5 November 2026].
 * - Google Developers, 2024. RecognizerIntent - Android Developers. [online] Available at: <https://developer.android.com/reference/android/speech/RecognizerIntent> [Accessed 5 November 2026].
 */

package com.example.prog7314_poepart23.utils

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.provider.Settings
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.prog7314_poepart23.AppearanceActivity
import com.example.prog7314_poepart23.BiometricLoginActivity
import com.example.prog7314_poepart23.BiometricSetupActivity
import com.example.prog7314_poepart23.DeviceManagementActivity
import com.example.prog7314_poepart23.DoctorAlertsActivity
import com.example.prog7314_poepart23.DoctorDashboardActivity
import com.example.prog7314_poepart23.DoctorProfileActivity
import com.example.prog7314_poepart23.DoctorReportsActivity
import com.example.prog7314_poepart23.DoctorSettingsActivity
import com.example.prog7314_poepart23.EcgPreSessionCheckActivity
import com.example.prog7314_poepart23.EmergencySosActivity
import com.example.prog7314_poepart23.HelpSupportActivity
import com.example.prog7314_poepart23.LanguageSelectionActivity
import com.example.prog7314_poepart23.LiveEcgMonitoringActivity
import com.example.prog7314_poepart23.LiveSupportChatActivity
import com.example.prog7314_poepart23.LoginActivity
import com.example.prog7314_poepart23.MainActivity
import com.example.prog7314_poepart23.NotificationsActivity
import com.example.prog7314_poepart23.NurseDashboardActivity
import com.example.prog7314_poepart23.NursePatientIntakeActivity
import com.example.prog7314_poepart23.NurseSettingsActivity
import com.example.prog7314_poepart23.OfflineSyncStatusActivity
import com.example.prog7314_poepart23.PatientDashboardActivity
import com.example.prog7314_poepart23.PatientEcgHistoryActivity
import com.example.prog7314_poepart23.PatientSessionStatusActivity
import com.example.prog7314_poepart23.PatientListActivity
import com.example.prog7314_poepart23.PatientProfileActivity
import com.example.prog7314_poepart23.Patient_Settings
import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.RegistrationActivity
import com.example.prog7314_poepart23.SiteAccessActivity
import com.example.prog7314_poepart23.SplashActivity
import com.example.prog7314_poepart23.data.repository.NotificationRepository
import com.example.prog7314_poepart23.security.SecurePreferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.util.Locale

object VoiceAssistant {

    private const val PREFS_NAME = "pulselink_voice_prefs"
    private const val KEY_ENABLED = "voice_assistance_enabled"
    private const val FAB_TAG = "pulselink_voice_fab"
    private const val UTTERANCE_ID = "pulselink_voice"
    private const val LIVE_HEART_RATE_INTERVAL_MS = 20_000L
    private const val MAX_NOTIFICATIONS_READ = 3

    // Screens before sign-in (or purely transitional) get no voice button.
    private val excludedScreens: Set<Class<out Activity>> = setOf(
        SplashActivity::class.java,
        LoginActivity::class.java,
        RegistrationActivity::class.java,
        BiometricLoginActivity::class.java,
        BiometricSetupActivity::class.java,
        MainActivity::class.java
    )

    private var appContext: Context? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsLanguage: String? = null
    private var pendingText: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Periodic heart rate read-out for the live recording screen.
    private var liveReadout: Runnable? = null

    // Number of screens currently in the foreground (0 means the app is in the background).
    private var resumedScreens = 0

    // ---------------------------------------------------------------------------------
    // Setting
    // ---------------------------------------------------------------------------------
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /** Turns voice assistance on or off and shows or hides the microphone button on [activity]. */
    fun setEnabled(activity: Activity, enabled: Boolean) {
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            refreshButton(activity)
            speak(activity, text(R.string.voice_enabled_spoken))
            // Ask for the microphone now so voice commands work the first time the button is tapped.
            withMicPermission(activity) {}
        } else {
            stop()
            refreshButton(activity)
            stopLiveReadout()
        }
    }

    /** Registers the hooks that show the button and run the live read-out. Call once from Application.onCreate(). */
    fun install(app: Application) {
        appContext = app.applicationContext
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedScreens++
                refreshButton(activity)
                if (activity is LiveEcgMonitoringActivity) startLiveReadout(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                resumedScreens = (resumedScreens - 1).coerceAtLeast(0)
                stop()
                if (activity is LiveEcgMonitoringActivity) stopLiveReadout()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    // ---------------------------------------------------------------------------------
    // Text to speech
    // ---------------------------------------------------------------------------------

    /** Speaks [message] now (replacing anything being spoken). Does nothing when voice assistance is off. */
    fun speak(context: Context, message: String) {
        if (message.isBlank() || !isEnabled(context)) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // Push notifications arrive on a background thread; TextToSpeech is driven from the main thread.
            mainHandler.post { speak(context, message) }
            return
        }
        val app = context.applicationContext
        appContext = app
        val engine = tts
        if (engine == null) {
            pendingText = message
            tts = TextToSpeech(app) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    pendingText?.let { speakNow(app, it) }
                    pendingText = null
                } else {
                    ttsReady = false
                    tts = null
                }
            }
            return
        }
        if (!ttsReady) {
            pendingText = message
            return
        }
        speakNow(app, message)
    }

    /**
     * Reads an alert or notification out automatically while the app is open. TalkBack already
     * announces dialogs and notifications, so this stays quiet while a screen reader is running to
     * avoid double speech.
     */
    fun announce(context: Context, message: String) {
        if (!isEnabled(context) || resumedScreens == 0 || isScreenReaderOn(context)) return
        speak(context, message)
    }

    fun stop() {
        pendingText = null
        tts?.stop()
    }

    private fun speakNow(context: Context, message: String) {
        val engine = tts ?: return
        val language = LocaleHelper.getLanguage(context)
        if (language != ttsLanguage) {
            val result = engine.setLanguage(Locale(language))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                android.widget.Toast.makeText(context, text(R.string.ui_voice_language_unavailable), android.widget.Toast.LENGTH_LONG).show()
                return
            }
            ttsLanguage = language
        }
        engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private fun isScreenReaderOn(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        return manager?.isEnabled == true && manager.isTouchExplorationEnabled
    }

    private fun text(resId: Int, vararg args: Any): String = LocaleHelper.string(resId, *args)

    // ---------------------------------------------------------------------------------
    // Microphone button
    // ---------------------------------------------------------------------------------
    private fun refreshButton(activity: Activity) {
        val content = activity.findViewById<ViewGroup?>(android.R.id.content) as? FrameLayout ?: return
        val existing = content.findViewWithTag<View>(FAB_TAG)
        val wanted = isEnabled(activity) && activity.javaClass !in excludedScreens && activity is ComponentActivity
        if (!wanted) {
            if (existing != null) content.removeView(existing)
            return
        }
        if (existing != null) return

        val density = activity.resources.displayMetrics.density
        val baseBottomMargin = (88 * density).toInt()
        val fab = FloatingActionButton(activity).apply {
            tag = FAB_TAG
            setImageResource(R.drawable.ic_voice_mic)
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.pulse_primary_fill))
            imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
            contentDescription = activity.getString(R.string.voice_button_description)
            elevation = 6 * density
            setOnClickListener { startListening(activity) }
            setOnLongClickListener {
                showMenu(activity)
                true
            }
        }
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply {
            marginEnd = (16 * density).toInt()
            bottomMargin = baseBottomMargin
        }
        ViewCompat.setOnApplyWindowInsetsListener(fab) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (v.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.bottomMargin = baseBottomMargin + bars.bottom
                it.marginEnd = (16 * density).toInt() + bars.right
                v.layoutParams = it
            }
            insets
        }
        content.addView(fab, params)
        ViewCompat.requestApplyInsets(fab)
    }

    private fun showMenu(activity: Activity) {
        val labels = arrayOf(
            activity.getString(R.string.voice_menu_speak),
            activity.getString(R.string.voice_menu_heart_rate),
            activity.getString(R.string.voice_menu_readings),
            activity.getString(R.string.voice_menu_notifications),
            activity.getString(R.string.voice_menu_commands)
        )
        MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_PulseLink_Dialog)
            .setTitle(R.string.voice_menu_title)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> startListening(activity)
                    1 -> readHeartRate(activity)
                    2 -> readReadings(activity)
                    3 -> readNotifications(activity)
                    else -> speakHelp(activity)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------------------------
    // Voice commands
    // ---------------------------------------------------------------------------------
    private fun hasMicPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Runs [onGranted] when the microphone permission is held, asking for it first (with a reason) if not. */
    private fun withMicPermission(activity: Activity, onGranted: () -> Unit) {
        val owner = activity as? ComponentActivity ?: return
        if (hasMicPermission(owner)) {
            onGranted()
            return
        }
        MaterialAlertDialogBuilder(owner, R.style.ThemeOverlay_PulseLink_Dialog)
            .setTitle(R.string.voice_mic_rationale_title)
            .setMessage(R.string.voice_mic_rationale_message)
            .setPositiveButton(R.string.voice_mic_allow) { _, _ ->
                lateinit var launcher: ActivityResultLauncher<String>
                launcher = owner.activityResultRegistry.register(
                    "pulselink_voice_permission_${System.nanoTime()}",
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    launcher.unregister()
                    if (granted) onGranted() else showMicDenied(owner)
                }
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> speak(owner, text(R.string.voice_mic_denied_spoken)) }
            .show()
    }

    // Explains what still works without the microphone and offers the app settings page.
    private fun showMicDenied(activity: Activity) {
        speak(activity, text(R.string.voice_mic_denied_spoken))
        MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_PulseLink_Dialog)
            .setTitle(R.string.voice_mic_denied_title)
            .setMessage(R.string.voice_mic_denied_message)
            .setPositiveButton(R.string.voice_open_settings) { _, _ ->
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startListening(activity: Activity) {
        val owner = activity as? ComponentActivity ?: return
        stop()
        withMicPermission(owner) { launchRecogniser(owner) }
    }

    private fun launchRecogniser(owner: ComponentActivity) {
        val activity: Activity = owner
        lateinit var launcher: ActivityResultLauncher<Intent>
        launcher = owner.activityResultRegistry.register(
            "pulselink_voice_${System.nanoTime()}",
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            launcher.unregister()
            val spoken = if (result.resultCode == Activity.RESULT_OK) {
                result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            } else null
            if (!spoken.isNullOrBlank()) handleCommand(owner, spoken)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale(LocaleHelper.getLanguage(activity)).toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, activity.getString(R.string.voice_listen_prompt))
        }
        try {
            launcher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            launcher.unregister()
            speak(activity, text(R.string.voice_not_available))
        }
    }

    private class Destination(
        val keywords: List<String>,
        val labelRes: Int,
        val screen: Class<out Activity>
    )

    private fun patientDestinations() = listOf(
        Destination(listOf("support"), R.string.voice_dest_support, HelpSupportActivity::class.java),
        Destination(listOf("sos", "emergency", "noodgeval"), R.string.voice_dest_emergency, EmergencySosActivity::class.java),
        Destination(listOf("appearance", "text size", "contrast", "theme", "voorkoms"), R.string.voice_dest_appearance, AppearanceActivity::class.java),
        Destination(listOf("language", "taal", "ulimi"), R.string.voice_dest_language, LanguageSelectionActivity::class.java),
        Destination(listOf("sync", "offline"), R.string.voice_dest_sync, OfflineSyncStatusActivity::class.java),
        Destination(listOf("notification", "alert", "kennisgewing", "waarskuwing", "izaziso", "imiyalezo"), R.string.voice_dest_notifications, NotificationsActivity::class.java),
        Destination(listOf("setting", "instelling", "izilungiselelo"), R.string.voice_dest_settings, Patient_Settings::class.java),
        Destination(listOf("profile", "profiel"), R.string.voice_dest_profile, PatientProfileActivity::class.java),
        Destination(listOf("report", "history", "verslae", "geskiedenis", "imibiko", "umlando"), R.string.voice_dest_reports, PatientEcgHistoryActivity::class.java),
        Destination(listOf("status", "session", "sessie", "isimo", "iseshini"), R.string.voice_dest_session_status, PatientSessionStatusActivity::class.java),
        Destination(listOf("dashboard", "home", "tuis", "ikhaya"), R.string.voice_dest_dashboard, PatientDashboardActivity::class.java)
    )

    private fun doctorDestinations() = listOf(
        Destination(listOf("appearance", "text size", "contrast", "theme", "voorkoms"), R.string.voice_dest_appearance, AppearanceActivity::class.java),
        Destination(listOf("language", "taal", "ulimi"), R.string.voice_dest_language, LanguageSelectionActivity::class.java),
        Destination(listOf("notification", "alert", "kennisgewing", "waarskuwing", "izaziso"), R.string.voice_dest_alerts, DoctorAlertsActivity::class.java),
        Destination(listOf("setting", "instelling", "izilungiselelo"), R.string.voice_dest_settings, DoctorSettingsActivity::class.java),
        Destination(listOf("profile", "profiel"), R.string.voice_dest_profile, DoctorProfileActivity::class.java),
        Destination(listOf("report", "verslae", "imibiko"), R.string.voice_dest_reports, DoctorReportsActivity::class.java),
        Destination(listOf("patient", "roster", "pasient", "pasiënt", "iziguli"), R.string.voice_dest_patients, PatientListActivity::class.java),
        Destination(listOf("dashboard", "home", "tuis", "ikhaya"), R.string.voice_dest_dashboard, DoctorDashboardActivity::class.java)
    )

    private fun nurseDestinations() = listOf(
        Destination(listOf("device", "pair", "toestel"), R.string.voice_dest_devices, DeviceManagementActivity::class.java),
        Destination(listOf("support"), R.string.voice_dest_support, HelpSupportActivity::class.java),
        Destination(listOf("chat"), R.string.voice_dest_nurse_chat, LiveSupportChatActivity::class.java),
        Destination(listOf("monitor", "ecg", "check"), R.string.voice_dest_nurse_monitor, EcgPreSessionCheckActivity::class.java),
        Destination(listOf("patient", "intake", "pasient", "pasiënt", "iziguli"), R.string.voice_dest_nurse_patients, NursePatientIntakeActivity::class.java),
        Destination(listOf("setting", "instelling", "izilungiselelo"), R.string.voice_dest_nurse_settings, NurseSettingsActivity::class.java),
        Destination(listOf("appearance", "voorkoms", "ukubukeka"), R.string.voice_dest_appearance, AppearanceActivity::class.java),
        Destination(listOf("language", "taal", "ulimi"), R.string.voice_dest_language, LanguageSelectionActivity::class.java),
        Destination(listOf("dashboard", "home", "tuis", "ikhaya"), R.string.voice_dest_dashboard, NurseDashboardActivity::class.java)
    )

    private fun staffDestinations() = listOf(
        Destination(listOf("appearance", "voorkoms", "ukubukeka"), R.string.voice_dest_appearance, AppearanceActivity::class.java),
        Destination(listOf("language", "taal", "ulimi"), R.string.voice_dest_language, LanguageSelectionActivity::class.java),
        Destination(listOf("dashboard", "home", "tuis", "ikhaya"), R.string.voice_dest_dashboard, SiteAccessActivity::class.java)
    )

    private fun isDoctor(activity: Activity): Boolean {
        val role = SecurePreferences.getInstance(activity).getUserRole()?.lowercase()
        if (role == SessionManager.ROLE_DOCTOR) return true
        if (role == SessionManager.ROLE_PATIENT) return false
        return activity.javaClass.simpleName.startsWith("Doctor") || activity is PatientListActivity
    }

    private fun has(spoken: String, vararg words: String) = words.any { spoken.contains(it) }

    private fun handleCommand(activity: Activity, raw: String) {
        val spoken = raw.lowercase(Locale.ROOT).trim()
        val reading = has(spoken, "read", "hear", "tell me", "what is", "what's", "speak", "lees", "vertel", "unread")
        when {
            has(spoken, "stop", "quiet", "silence", "hou op", "thula", "yeka") -> stop()
            has(spoken, "what can i say", "commands", "voice help", "hulp", "usizo", "imiyalo") || spoken == "help" -> speakHelp(activity)
            has(spoken, "heart rate", "heartrate", "bpm", "pulse", "hartklop", "polsslag", "ukushaya kwenhliziyo") -> readHeartRate(activity)
            has(spoken, "readings", "reading", "vitals", "metings", "izilinganiso", "funda isikrini") || spoken == "read" || spoken == "read screen" ->
                readReadings(activity)
            (reading || spoken.contains("funda")) && has(spoken, "notification", "alert", "message", "kennisgewing", "waarskuwing", "izaziso", "imiyalezo") ->
                readNotifications(activity)
            has(spoken, "go back", "back", "terug", "emuva") -> {
                speak(activity, text(R.string.voice_going_back))
                (activity as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
            }
            else -> navigate(activity, spoken)
        }
    }

    private fun navigate(activity: Activity, spoken: String) {
        val role = SecurePreferences.getInstance(activity).getUserRole()?.lowercase()
        if (role != SessionManager.ROLE_NURSE && listOf("device", "pair", "toestel").any { spoken.contains(it) }) {
            speak(activity, text(R.string.voice_devices_nurse_only))
            return
        }
        val destinations = when {
            role == SessionManager.ROLE_NURSE -> nurseDestinations()
            role == SessionManager.ROLE_ADMINISTRATOR || activity is SiteAccessActivity -> staffDestinations()
            isDoctor(activity) -> doctorDestinations()
            role == SessionManager.ROLE_PATIENT -> patientDestinations()
            else -> emptyList()
        }
        val match = destinations.firstOrNull { d -> d.keywords.any { spoken.contains(it) } }
        if (match == null) {
            speak(activity, text(R.string.voice_not_understood))
            return
        }
        if (activity.javaClass == match.screen) {
            speak(activity, text(R.string.voice_already_on))
            return
        }
        speak(activity, text(R.string.voice_opening, text(match.labelRes)))
        // Same flags as the bottom navigation so tabs reuse the existing screen instead of stacking.
        activity.startActivity(
            Intent(activity, match.screen).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun speakHelp(activity: Activity) {
        val role = SecurePreferences.getInstance(activity).getUserRole()
        val resource = when {
            role == SessionManager.ROLE_NURSE || role == SessionManager.ROLE_ADMINISTRATOR || activity is SiteAccessActivity -> R.string.ui_voice_help_staff
            isDoctor(activity) -> R.string.voice_help_doctor_spoken
            else -> R.string.voice_help_spoken
        }
        speak(activity, text(resource))
    }

    // ---------------------------------------------------------------------------------
    // Reading heart rate, readings and notifications aloud
    // ---------------------------------------------------------------------------------
    private val currentRateIds = intArrayOf(R.id.tvBpmValue, R.id.tvLiveBpm)
    private val latestRateIds = intArrayOf(R.id.tvAvgBpm, R.id.tvSessionAvgBpm, R.id.tvAlertBpm)
    private val readingIds = intArrayOf(
        R.id.tvSessionStatusPill, R.id.tvSessionLastDate, R.id.tvSessionDate,
        R.id.tvRhythmStatus, R.id.tvSensorStatus, R.id.tvLeadStatus
    )

    private fun visibleText(activity: Activity, id: Int): String? {
        val view = activity.findViewById<View?>(id) as? TextView ?: return null
        if (!view.isShown) return null
        return view.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun firstNumber(value: String?): Int? =
        value?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }?.takeIf { it > 0 }

    /** The heart rate sentence for the screen in front of the user, or null when no reading is shown. */
    fun heartRateSentence(activity: Activity): String? {
        for (id in currentRateIds) {
            firstNumber(visibleText(activity, id))?.let { return text(R.string.voice_heart_rate_current, it) }
        }
        for (id in latestRateIds) {
            firstNumber(visibleText(activity, id))?.let { return text(R.string.voice_heart_rate_latest, it) }
        }
        return null
    }

    fun readHeartRate(activity: Activity) {
        speak(activity, heartRateSentence(activity) ?: text(R.string.voice_heart_rate_none))
    }

    fun readReadings(activity: Activity) {
        val parts = ArrayList<String>()
        heartRateSentence(activity)?.let { parts.add(it) }
        readingIds.forEach { id -> visibleText(activity, id)?.let { parts.add(it) } }
        speak(activity, if (parts.isEmpty()) text(R.string.voice_readings_none) else parts.joinToString(". "))
    }

    fun readNotifications(activity: Activity) {
        val all = NotificationRepository.notifications.value
        if (all.isEmpty()) {
            speak(activity, text(R.string.voice_notifications_none))
            return
        }
        val unread = all.filter { it.isUnread }
        val shown = (if (unread.isNotEmpty()) unread else all.take(1)).take(MAX_NOTIFICATIONS_READ)
        val parts = ArrayList<String>()
        parts.add(
            when {
                unread.isEmpty() -> text(R.string.voice_notifications_all_read)
                unread.size == 1 -> text(R.string.voice_notifications_one)
                else -> text(R.string.voice_notifications_many, unread.size)
            }
        )
        shown.forEach { parts.add(text(R.string.voice_notification_item, it.title, it.messagePreview)) }
        speak(activity, parts.joinToString(" "))
    }

    // ---------------------------------------------------------------------------------
    // Live recording: read the current heart rate every few seconds
    // ---------------------------------------------------------------------------------
    private fun startLiveReadout(activity: Activity) {
        stopLiveReadout()
        if (!isEnabled(activity)) return
        val task = object : Runnable {
            override fun run() {
                if (!isEnabled(activity) || activity.isFinishing || activity.isDestroyed) return
                heartRateSentence(activity)?.let { announce(activity, it) }
                mainHandler.postDelayed(this, LIVE_HEART_RATE_INTERVAL_MS)
            }
        }
        liveReadout = task
        mainHandler.postDelayed(task, LIVE_HEART_RATE_INTERVAL_MS)
    }

    private fun stopLiveReadout() {
        liveReadout?.let { mainHandler.removeCallbacks(it) }
        liveReadout = null
    }
}
