/*
 * Doctor settings activity configuring clinician language preferences,
 * clinical BPM anomaly thresholds, and legal documentation.
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
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.LocaleHelper
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.openOnClick
import com.example.prog7314_poepart23.utils.wireBack

class DoctorSettingsActivity : AppCompatActivity() { // (Google Developers, 2024)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.doctor_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Task 12: Language selection (English / isiZulu / Afrikaans)
        findViewById<View>(R.id.rowLanguage).setOnClickListener {
            startActivity(Intent(this, LanguageSelectionActivity::class.java))
        }

        // About, Legal & Medical Privacy portal
        findViewById<View>(R.id.rowAboutLegal).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        HapticFeedbackManager.initialise(this)
        wireSettingsRows()
        BottomNavHelper.setUpDoctorNavDetached(this)
    }

    // Keeps the settings row subtitle in step with the language stored in SharedPreferences.
    override fun onResume() {
        super.onResume()
        BottomNavHelper.setUpDoctorNavDetached(this)
        findViewById<TextView>(R.id.txtCurrentLanguage).text =
            LocaleHelper.displayName(LocaleHelper.getLanguage(this))
    }

    // Every remaining row in Settings: the ones with a destination navigate, the rest say
    // plainly that they are not built yet rather than silently doing nothing.
    private fun wireSettingsRows() {
        wireBack(R.id.btnBack)

        openOnClick(R.id.rowPersonalInfo, DoctorProfileActivity::class.java)
        openOnClick(R.id.rowSecurity, SecurityPasswordActivity::class.java)
        openOnClick(R.id.rowNotifications, NotificationsActivity::class.java)
        openOnClick(R.id.rowHelpCenter, HelpSupportActivity::class.java)
        openOnClick(R.id.rowPrivacyPolicy, AboutActivity::class.java)

        findViewById<android.view.View>(R.id.rowAppearance).setOnClickListener {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }
    }
}
