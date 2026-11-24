// Role-aware Help & Support Centre with live JavaScript-style dynamic search filtering,
// patient-specific vs nurse-specific FAQs, replacement vector iconography, and real-time
// Live Chat routing between Patient and Clinic Nurse.

package com.example.prog7314_poepart23

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.prog7314_poepart23.security.SecurePreferences
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.SessionManager
import com.google.firebase.auth.FirebaseAuth
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HelpSupportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROLE_OVERRIDE = "extra_help_role_override"
    }

    private data class AccordionItem(
        val container: View,
        val dividerAfter: View?,
        val header: View,
        val answerPanel: View,
        val questionText: String,
        val answerText: String,
        var manualExpanded: Boolean = false
    )

    private val accordionItems = mutableListOf<AccordionItem>()
    private lateinit var tvHelpSearchEmpty: TextView
    private lateinit var etSearchHelp: EditText
    private var isPatientMode: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.patient_settings_help_support)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        HapticFeedbackManager.initialise(this)
        isPatientMode = resolveRoleMode().equals(SessionManager.ROLE_PATIENT, ignoreCase = true)

        findViewById<View>(R.id.ivBack).setOnClickListener { finish() }
        tvHelpSearchEmpty = findViewById(R.id.tvHelpSearchEmpty)
        etSearchHelp = findViewById(R.id.etSearchHelp)

        applyRoleSpecificContent()
        setupFaqAccordion()
        setupDynamicSearch()
        setupNeedMoreHelpButtons()

        val launchLiveChat = View.OnClickListener {
            HapticFeedbackManager.confirmClick()
            startActivity(Intent(this, LiveSupportChatActivity::class.java))
        }
        findViewById<View>(R.id.btnLiveChat).setOnClickListener(launchLiveChat)
        findViewById<View>(R.id.btnContactSupport).setOnClickListener(launchLiveChat)

        // Call: opens the dialler with official support telephone
        findViewById<View>(R.id.btnCallUs).setOnClickListener {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:0614466943")))
        }

        // Email: opens the user's mail app pre-populated with official support address and diagnostic metadata
        findViewById<View>(R.id.btnEmailUs).setOnClickListener { sendSupportEmail() }
        findViewById<View>(R.id.btnReportProblem).setOnClickListener {
            HapticFeedbackManager.lightTick()
            sendSupportEmail("PulseLink Issue Report")
        }

        findViewById<View>(R.id.btnViewPlacementGuide).setOnClickListener {
            startActivity(Intent(this, ElectrodePlacementGuideActivity::class.java))
        }
    }

    private fun resolveRoleMode(): String {
        val explicit = intent.getStringExtra(EXTRA_ROLE_OVERRIDE)
        if (!explicit.isNullOrBlank()) return explicit
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val cached = uid?.let { SessionManager.getCachedRole(this, it) }
        return cached ?: SecurePreferences.getInstance(this).getUserRole() ?: SessionManager.ROLE_PATIENT
    }

    private fun applyRoleSpecificContent() {
        val btnViewPlacementGuide = findViewById<View>(R.id.btnViewPlacementGuide)
        val tvUserGuideTitle = findViewById<TextView>(R.id.tvUserGuideTitle)
        val tvUserGuideSubtitle = findViewById<TextView>(R.id.tvUserGuideSubtitle)

        if (!isPatientMode) {
            btnViewPlacementGuide.visibility = View.VISIBLE
            return
        }

        // Patients do not operate ECG hardware or attach electrodes: replace hardware FAQs and SVG icons
        btnViewPlacementGuide.visibility = View.GONE
        tvUserGuideTitle.setText(R.string.patient_faq_user_guide_title)
        tvUserGuideSubtitle.setText(R.string.patient_faq_user_guide_subtitle)

        configureFaqRow(
            iconId = R.id.ivFaqIcon1,
            iconRes = R.drawable.ic_clinical_release,
            bgRes = R.drawable.circle_blue_bg,
            questionId = R.id.tvFaqQuestion1,
            questionRes = R.string.patient_faq_q1,
            answerId = R.id.tvFaqAnswer1,
            answerRes = R.string.patient_faq_a1
        )
        configureFaqRow(
            iconId = R.id.ivFaqIcon2,
            iconRes = R.drawable.ic_prescription_pill,
            bgRes = R.drawable.circle_success_bg,
            questionId = R.id.tvFaqQuestion2,
            questionRes = R.string.patient_faq_q2,
            answerId = R.id.faqPairingAnswer,
            answerRes = R.string.patient_faq_a2
        )
        configureFaqRow(
            iconId = R.id.ivFaqIcon3,
            iconRes = R.drawable.ic_session_clock,
            bgRes = R.drawable.circle_warning_bg,
            questionId = R.id.tvFaqQuestion3,
            questionRes = R.string.patient_faq_q3,
            answerId = R.id.faqIrregularRhythmAnswer,
            answerRes = R.string.patient_faq_a3
        )
        configureFaqRow(
            iconId = R.id.ivFaqIcon4,
            iconRes = R.drawable.ic_map_pin,
            bgRes = R.drawable.circle_blue_bg,
            questionId = R.id.tvFaqQuestion4,
            questionRes = R.string.patient_faq_q4,
            answerId = R.id.faqSyncingAnswer,
            answerRes = R.string.patient_faq_a4
        )
        configureFaqRow(
            iconId = R.id.ivFaqIcon5,
            iconRes = R.drawable.ic_chat,
            bgRes = R.drawable.circle_success_bg,
            questionId = R.id.tvFaqQuestion5,
            questionRes = R.string.patient_faq_q5,
            answerId = R.id.faqBatteryAnswer,
            answerRes = R.string.patient_faq_a5
        )
        configureFaqRow(
            iconId = R.id.ivFaqIcon6,
            iconRes = R.drawable.ic_ambulance,
            bgRes = R.drawable.circle_danger_bg,
            questionId = R.id.tvFaqQuestion6,
            questionRes = R.string.patient_faq_q6,
            answerId = R.id.faqConnectivityAnswer,
            answerRes = R.string.patient_faq_a6
        )
        configureFaqRow(
            iconId = R.id.ivFaqIcon7,
            iconRes = R.drawable.ic_shield_check,
            bgRes = R.drawable.circle_blue_bg,
            questionId = R.id.tvFaqQuestion7,
            questionRes = R.string.patient_faq_q7,
            answerId = R.id.faqDataPrivacyAnswer,
            answerRes = R.string.patient_faq_a7
        )
    }

    private fun configureFaqRow(
        iconId: Int,
        iconRes: Int,
        bgRes: Int,
        questionId: Int,
        questionRes: Int,
        answerId: Int,
        answerRes: Int
    ) {
        findViewById<ImageView>(iconId).apply {
            setImageResource(iconRes)
            setBackgroundResource(bgRes)
        }
        findViewById<TextView>(questionId).setText(questionRes)
        findViewById<TextView>(answerId).setText(answerRes)
    }

    // ---- FAQ accordion & dynamic JavaScript-style search --------------------

    private fun setupFaqAccordion() {
        accordionItems.clear()
        registerAccordionItem(
            R.id.faqContainer1, R.id.faqDivider1,
            R.id.faqElectrodePlacement, R.id.faqElectrodePlacementAnswer,
            R.id.tvFaqQuestion1, R.id.tvFaqAnswer1
        )
        registerAccordionItem(
            R.id.faqContainer2, R.id.faqDivider2,
            R.id.faqPairing, R.id.faqPairingAnswer,
            R.id.tvFaqQuestion2, R.id.faqPairingAnswer
        )
        registerAccordionItem(
            R.id.faqContainer3, R.id.faqDivider3,
            R.id.faqIrregularRhythm, R.id.faqIrregularRhythmAnswer,
            R.id.tvFaqQuestion3, R.id.faqIrregularRhythmAnswer
        )
        registerAccordionItem(
            R.id.faqContainer4, R.id.faqDivider4,
            R.id.faqSyncing, R.id.faqSyncingAnswer,
            R.id.tvFaqQuestion4, R.id.faqSyncingAnswer
        )
        registerAccordionItem(
            R.id.faqContainer5, R.id.faqDivider5,
            R.id.faqBattery, R.id.faqBatteryAnswer,
            R.id.tvFaqQuestion5, R.id.faqBatteryAnswer
        )
        registerAccordionItem(
            R.id.faqContainer6, R.id.faqDivider6,
            R.id.faqConnectivity, R.id.faqConnectivityAnswer,
            R.id.tvFaqQuestion6, R.id.faqConnectivityAnswer
        )
        registerAccordionItem(
            R.id.faqContainer7, null,
            R.id.faqDataPrivacy, R.id.faqDataPrivacyAnswer,
            R.id.tvFaqQuestion7, R.id.faqDataPrivacyAnswer
        )
    }

    private fun registerAccordionItem(
        containerId: Int,
        dividerId: Int?,
        headerId: Int,
        answerPanelId: Int,
        questionTextId: Int,
        answerTextId: Int
    ) {
        val container = findViewById<View>(containerId)
        val divider = dividerId?.let { findViewById<View>(it) }
        val header = findViewById<View>(headerId)
        val answerPanel = findViewById<View>(answerPanelId)
        val questionText = findViewById<TextView>(questionTextId).text.toString()
        val answerText = findViewById<TextView>(answerTextId).text.toString()
        val item = AccordionItem(container, divider, header, answerPanel, questionText, answerText)
        accordionItems.add(item)

        header.setOnClickListener {
            HapticFeedbackManager.lightTick()
            item.manualExpanded = item.answerPanel.visibility != View.VISIBLE
            item.answerPanel.visibility = if (item.manualExpanded) View.VISIBLE else View.GONE
        }
    }

    private fun setupDynamicSearch() {
        etSearchHelp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                filterFaqItems(s?.toString().orEmpty())
            }
        })
    }

    private fun filterFaqItems(rawQuery: String) {
        val query = rawQuery.trim().lowercase(Locale.ROOT)
        var visibleCount = 0
        val matchingItems = mutableListOf<AccordionItem>()

        accordionItems.forEach { item ->
            val matches = query.isEmpty() ||
                item.questionText.lowercase(Locale.ROOT).contains(query) ||
                item.answerText.lowercase(Locale.ROOT).contains(query)

            item.container.visibility = if (matches) View.VISIBLE else View.GONE
            item.answerPanel.visibility = when {
                !matches -> View.GONE
                query.isNotEmpty() -> View.VISIBLE
                item.manualExpanded -> View.VISIBLE
                else -> View.GONE
            }
            if (matches) {
                visibleCount++
                matchingItems.add(item)
            }
            item.dividerAfter?.visibility = View.GONE
        }

        // Only display dividers between adjacent visible FAQ rows
        matchingItems.dropLast(1).forEach { it.dividerAfter?.visibility = View.VISIBLE }
        tvHelpSearchEmpty.visibility = if (visibleCount == 0) View.VISIBLE else View.GONE
    }

    // ---- Need more help? buttons --------------------------------------------

    private fun setupNeedMoreHelpButtons() {
        findViewById<View?>(R.id.btnUserGuide)?.setOnClickListener {
            HapticFeedbackManager.lightTick()
            val target = if (isPatientMode) {
                FacilityFinderActivity::class.java
            } else {
                ElectrodePlacementGuideActivity::class.java
            }
            startActivity(Intent(this, target))
        }
    }

    // ---- Contact support -----------------------------------------------------

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

    private fun buildDiagnosticEmailBody(): String {
        val appVersion = try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            "${packageInfo.versionName} (${longVersionCode(packageInfo)})"
        } catch (_: Exception) {
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
