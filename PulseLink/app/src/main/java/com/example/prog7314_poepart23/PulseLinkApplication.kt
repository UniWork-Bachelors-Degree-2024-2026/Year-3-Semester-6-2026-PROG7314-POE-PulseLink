// Task 12: Multi-Language Support (Localisation)
//
// Applying the stored locale once, at process start, means every activity in the app picks up
// the user's chosen language without each one needing its own attachBaseContext() override.

package com.example.prog7314_poepart23

import android.app.Application                             // (Google Developers, 2024)
import java.util.TimeZone
import com.example.prog7314_poepart23.utils.ThemeHelper
import com.example.prog7314_poepart23.utils.TextSizeHelper
import com.example.prog7314_poepart23.utils.HighContrastHelper
import com.example.prog7314_poepart23.utils.ColorBlindHelper
import com.example.prog7314_poepart23.utils.VoiceAssistant
import com.example.prog7314_poepart23.utils.AccessibilityHelper
import com.example.prog7314_poepart23.utils.DateTimeFormatter
import com.example.prog7314_poepart23.utils.InputValidator
import com.example.prog7314_poepart23.messaging.NotificationHelper
import com.example.prog7314_poepart23.sync.SyncScheduler
import com.example.prog7314_poepart23.utils.LocaleHelper
import com.example.prog7314_poepart23.utils.NetworkMonitor

class PulseLinkApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Show every date and time in South African Standard Time (SAST, UTC+2) regardless of
        // the device's own time zone setting.
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Johannesburg"))
        // Light / dark / follow-system, as chosen under Settings > Appearance (default: follow system)
        ThemeHelper.apply(this)
        // Adjustable text size (Settings > Appearance > Text size)
        TextSizeHelper.install(this)
        // High contrast palettes (Settings > Appearance > High contrast). Without this hook the switch is saved but never applied.
        HighContrastHelper.install(this)
        // Colour-blind friendly status colours and shapes (Settings > Appearance > Colour-blind friendly).
        ColorBlindHelper.install(this)
        // Voice assistance (Settings > Appearance > Voice assistance) and screen reader / touch target support.
        VoiceAssistant.install(this)
        AccessibilityHelper.install(this)
        LocaleHelper.applyStoredLanguage(this)                  // (Google Developers, 2024)
        // These two helpers resolve their messages from string resources but were never
        // initialised, so relative times and validation errors always fell back to English.
        DateTimeFormatter.init(this)
        InputValidator.init(this)
        runCatching { com.example.prog7314_poepart23.data.local.OfflineEcgDatabase.getInstance(this).recoverInterruptedUploads() }
        runCatching { com.example.prog7314_poepart23.data.repository.NotificationRepository.initialise(this) }
        NetworkMonitor.initialise(this)
        SyncScheduler.schedulePeriodicSync(this)                // (Google Developers, 2024)
        NotificationHelper.createNotificationChannels(this)
        com.example.prog7314_poepart23.messaging.ClinicalNotificationSync.schedule(this)
        var notificationOwner: String? = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        com.google.firebase.auth.FirebaseAuth.getInstance().addAuthStateListener { auth ->
            if (notificationOwner != auth.currentUser?.uid) {
                notificationOwner = auth.currentUser?.uid
                com.example.prog7314_poepart23.data.repository.NotificationRepository.bindUser(notificationOwner)
                (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancelAll()
            }
            if (auth.currentUser != null) com.example.prog7314_poepart23.messaging.ClinicalNotificationSync.enqueue(this)
            else {
                com.example.prog7314_poepart23.data.repository.NotificationRepository.clear()
                (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancelAll()
            }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                com.example.prog7314_poepart23.messaging.ClinicalNotificationSync.enqueue(this@PulseLinkApplication)
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }
}
