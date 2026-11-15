// Help & Support Centre.
// - Interactive FAQ accordion covering pairing, ECG electrode placement (AD8232),
//   irregular-rhythm flags, offline sync, battery life, and connectivity.
// - "Email Us" opens an ACTION_SENDTO intent pre-populated with device diagnostic
//   metadata (app version, OS version, device model, signed-in role, network state)
//   so support can triage without back-and-forth.
// - "Where do the ECG electrodes go?" links into ElectrodePlacementGuideActivity,
//   a step-by-step visual instruction manual for 3-lead placement.
// - "User Guide" under Need More Help also opens ElectrodePlacementGuideActivity,
//   since it's currently the only standalone instructional screen in the app.

package com.example.prog7314_poepart23

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.prog7314_poepart23.utils.SessionManager
import com.google.firebase.auth.FirebaseAuth
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HelpSupportActivity : AppCompatActivity() {

    // One entry per accordion row: the tappable header and its answer panel.
    // No chevron indicator - the row itself is the button, and tapping it
    // toggles the answer panel directly.
    private data class AccordionItem(val header: View, val answerPanel: View)

    private val accordionItems = mutableListOf<AccordionItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.patient_settings_help_support)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<View>(R.id.ivBack).setOnClickListener { finish() }

        setupFaqAccordion()
        setupNeedMoreHelpButtons()

        // Call: opens the dialer with official support telephone
        findViewById<View>(R.id.btnCallUs).setOnClickListener {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:0614466943")))
        }

        // Email: opens the user's mail app pre-populated with official support address,
        // subject, and diagnostic metadata to speed up triage.
        findViewById<View>(R.id.btnEmailUs).setOnClickListener { sendSupportEmail() }

        // Electrode placement guide: opens the full step-by-step visual guide.
        findViewById<View>(R.id.btnViewPlacementGuide).setOnClickListener {
            startActivity(Intent(this, ElectrodePlacementGuideActivity::class.java))
        }

        // Live chat: placeholder until that channel exists.
        findViewById<View>(R.id.btnLiveChat).setOnClickListener {
            Toast.makeText(this, getString(R.string.l10n_live_chat_soon), Toast.LENGTH_SHORT).show()
        }
    }

    // ---- FAQ accordion -----------------------------------------------------

    private fun setupFaqAccordion() {
        registerAccordionItem(R.id.faqElectrodePlacement, R.id.faqElectrodePlacementAnswer)
        registerAccordionItem(R.id.faqPairing, R.id.faqPairingAnswer)
        registerAccordionItem(R.id.faqIrregularRhythm, R.id.faqIrregularRhythmAnswer)
        registerAccordionItem(R.id.faqSyncing, R.id.faqSyncingAnswer)
        registerAccordionItem(R.id.faqBattery, R.id.faqBatteryAnswer)
        registerAccordionItem(R.id.faqConnectivity, R.id.faqConnectivityAnswer)
        registerAccordionItem(R.id.faqDataPrivacy, R.id.faqDataPrivacyAnswer)
    }

    private fun registerAccordionItem(headerId: Int, answerId: Int) {
        val header = findViewById<View>(headerId)
        val answerPanel = findViewById<View>(answerId)
        val item = AccordionItem(header, answerPanel)
        accordionItems.add(item)

        header.setOnClickListener { toggleAccordionItem(item) }
    }

    // Expands/collapses a single FAQ row without affecting sibling rows, so a
    // patient can have several answers open at once while comparing them.
    private fun toggleAccordionItem(item: AccordionItem) {
        val isExpanded = item.answerPanel.visibility == View.VISIBLE
        item.answerPanel.visibility = if (isExpanded) View.GONE else View.VISIBLE
    }

    // ---- Need more help? buttons --------------------------------------------

    private fun setupNeedMoreHelpButtons() {
        findViewById<View?>(R.id.btnUserGuide)?.setOnClickListener {
            startActivity(Intent(this, ElectrodePlacementGuideActivity::class.java))
        }
    }

    // ---- Contact support -----------------------------------------------------

    // Opens the device's email app via ACTION_SENDTO with the official support address,
    // a subject, and a body pre-populated with diagnostic metadata that helps
    // support reproduce issues without asking the patient for details.
    private fun sendSupportEmail(subjectOverride: String? = null) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:ST10435031@myemeris.edu.za")
            putExtra(
                Intent.EXTRA_SUBJECT,
                subjectOverride ?: getString(R.string.patient_settings_help_support_email_subject)
            )
            putExtra(Intent.EXTRA_TEXT, buildDiagnosticEmailBody())
        }

        val chooser = Intent.createChooser(
            intent,
            getString(R.string.patient_settings_help_support_email_chooser_title)
        )

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(chooser)
        } else {
            Toast.makeText(this, getString(R.string.patient_settings_help_support_email_no_client), Toast.LENGTH_SHORT).show()
        }
    }

    // Assembles a plain-text diagnostic block: app version, OS version, device
    // model, signed-in role, and current network state. No health data is
    // included -- only metadata useful for reproducing a support issue.
    private fun buildDiagnosticEmailBody(): String {
        val appVersion = try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            "${packageInfo.versionName} (${longVersionCode(packageInfo)})"
        } catch (e: Exception) {
            getString(R.string.l10n_diag_unknown)
        }

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val role = uid?.let { SessionManager.getCachedRole(this, it) } ?: getString(R.string.l10n_diag_unknown)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(Date())

        return buildString {
            appendLine(getString(R.string.l10n_diag_intro))
            appendLine()
            appendLine(getString(R.string.l10n_diag_header))
            appendLine(getString(R.string.l10n_diag_app_version, appVersion))
            appendLine(getString(R.string.l10n_diag_android_version, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
            appendLine(getString(R.string.l10n_diag_device, "${Build.MANUFACTURER} ${Build.MODEL}"))
            appendLine(getString(R.string.l10n_diag_role, role))
            appendLine(getString(R.string.l10n_diag_network, networkStatusDescription()))
            appendLine(getString(R.string.l10n_diag_timestamp, timestamp))
        }
    }

    private fun longVersionCode(packageInfo: android.content.pm.PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }

    private fun networkStatusDescription(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return getString(R.string.l10n_diag_unknown)
        val activeNetwork = cm.activeNetwork ?: return getString(R.string.l10n_diag_offline)
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return getString(R.string.l10n_diag_offline)

        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getString(R.string.l10n_diag_mobile_data)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> getString(R.string.l10n_diag_connected)
        }

        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return if (validated) transport else getString(R.string.l10n_diag_no_internet_format, transport)
    }
}