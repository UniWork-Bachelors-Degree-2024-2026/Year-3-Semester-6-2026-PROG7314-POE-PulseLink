/*
 * Patient settings activity managing locale preferences, biometric security configuration,
 * notification options, connected device management, and medical privacy policies.
 *
 * Reference List:
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.LocaleHelper
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.openOnClick
import com.example.prog7314_poepart23.utils.wireBack

class Patient_Settings : AppCompatActivity() { // (Google Developers, 2024)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.patient_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        HapticFeedbackManager.initialise(this)
        wireSettingsRows()

        // Binds patient bottom navigation in detached state for secondary screen
        BottomNavHelper.setUpPatientNavDetached(this)
    }

    // Synchronises active language label when returning from language selection screen.
    override fun onResume() {
        super.onResume()
        findViewById<TextView?>(R.id.txtCurrentLanguage)?.text =
            LocaleHelper.displayName(LocaleHelper.getLanguage(this))
        BottomNavHelper.setUpPatientNavDetached(this)
    }

    // Configures click listeners for all interactive preferences and destination links.
    private fun wireSettingsRows() {
        wireBack(R.id.btnBack)

        // Account management
        openOnClick(R.id.rowPersonalInfo, PatientProfileActivity::class.java)
        openOnClick(R.id.rowSecurity, SecurityPasswordActivity::class.java)

        // General preferences
        openOnClick(R.id.rowNotifications, NotificationsActivity::class.java)
        openOnClick(R.id.rowDeviceManagement, DeviceManagementActivity::class.java)
        openOnClick(R.id.rowLanguage, LanguageSelectionActivity::class.java)

        // Privacy, legal & support
        openOnClick(R.id.rowPrivacyPolicy, AboutActivity::class.java)
        openOnClick(R.id.rowHelpCenter, HelpSupportActivity::class.java)
        openOnClick(R.id.rowAboutLegal, AboutActivity::class.java)

        // Appearance: System default / Light / Dark
        findViewById<android.view.View>(R.id.rowAppearance).setOnClickListener {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }
    }
}

