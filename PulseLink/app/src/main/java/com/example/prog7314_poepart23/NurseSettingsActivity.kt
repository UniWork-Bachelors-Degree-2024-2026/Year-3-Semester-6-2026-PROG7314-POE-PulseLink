/*
 * Nurse settings screen matching the PulseLink Settings design language, exposing
 * nurse account details, security, notifications, Nurse-only Device Management,
 * appearance, localisation, Help & Support, privacy policy, and sign-out.
 */
package com.example.prog7314_poepart23

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.prog7314_poepart23.security.SecurePreferences
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.LocaleHelper
import com.example.prog7314_poepart23.utils.PulseLinkModal
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.openOnClick
import com.example.prog7314_poepart23.utils.signOutToLogin
import com.example.prog7314_poepart23.utils.wireCommonSettingsRows
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth

class NurseSettingsActivity : AppCompatActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val prefs by lazy { SecurePreferences.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.nurse_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        HapticFeedbackManager.initialise(this)
        wireSettingsRows()
        BottomNavHelper.setUpNurseNavDetached(this)
    }

    override fun onResume() {
        super.onResume()
        BottomNavHelper.setUpNurseNavDetached(this)
        findViewById<TextView>(R.id.txtCurrentLanguage).text =
            LocaleHelper.displayName(LocaleHelper.getLanguage(this))
    }

    private fun wireSettingsRows() {
        wireCommonSettingsRows(onPersonalInfoClick = { showNursePersonalInfoModal() })
        openOnClick(R.id.rowDeviceManagement, DeviceManagementActivity::class.java)

        findViewById<MaterialButton>(R.id.btnNurseSignOut).setOnClickListener {
            HapticFeedbackManager.confirmClick()
            signOutToLogin()
        }
    }

    private fun showNursePersonalInfoModal() {
        val user = auth.currentUser
        val uid = user?.uid.orEmpty()
        val displayName = user?.displayName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.l10n_role_nurse)
        val email = user?.email?.takeIf { it.isNotBlank() }
            ?: getString(R.string.l10n_unavailable)
        val siteName = prefs.getNurseSiteName()?.takeIf { it.isNotBlank() }
            ?: getString(R.string.nurse_ui_assigned_site_none)
        val pairedDevice = prefs.getPairedDeviceId()?.takeIf { it.isNotBlank() }
            ?: getString(R.string.l10n_not_registered)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(detailText("${getString(R.string.l10n_role_nurse)}: $displayName", bold = true))
            addView(detailText("Email: $email").apply { setPadding(0, dp(6), 0, 0) })
            addView(detailText("UID: ${uid.ifBlank { getString(R.string.l10n_unavailable) }}").apply {
                setPadding(0, dp(6), 0, 0)
            })
            addView(detailText(siteName).apply { setPadding(0, dp(6), 0, 0) })
            addView(detailText("${getString(R.string.nurse_ui_hardware_unit_label)}: $pairedDevice").apply {
                setPadding(0, dp(6), 0, 0)
            })
        }
        PulseLinkModal.show(
            this,
            getString(R.string.nurse_ui_settings_title),
            body,
            listOf(getString(R.string.cd_close) to { d -> d.dismiss() })
        )
    }

    private fun detailText(text: String, bold: Boolean = false): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@NurseSettingsActivity, R.color.pulse_text_primary))
        }
    }
}
