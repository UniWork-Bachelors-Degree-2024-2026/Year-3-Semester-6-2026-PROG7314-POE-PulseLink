// Patient emergency SOS activity providing one-touch South African national emergency
// dispatch (112 / 10177), automated contact alerting, and bottom navigation integration.

package com.example.prog7314_poepart23

import android.animation.ObjectAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.applyStoredAvatar
import com.example.prog7314_poepart23.utils.openOnClick
import com.google.firebase.auth.FirebaseAuth

class EmergencySosActivity : AppCompatActivity() {

    private lateinit var ivSettings: ImageView
    private lateinit var btnSosTrigger: FrameLayout
    private lateinit var tvSosButtonLabel: TextView
    private lateinit var cardCallHospital: CardView
    private lateinit var cardCallAmbulance: CardView
    private lateinit var cardAlertContacts: CardView

    private val auth = FirebaseAuth.getInstance()
    private var sosCountDownTimer: CountDownTimer? = null
    private var isSosCountdownActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.patient_emergency_sos)

        HapticFeedbackManager.initialise(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        bindViews()
        setupClickListeners()
        setupBottomNav()
        animateSosButtonPulse()
        showArrhythmiaBannerIfPresent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showArrhythmiaBannerIfPresent(intent)
    }

    // When opened from a real-time arrhythmia alert, explain why and offer the fastest next steps.
    private fun showArrhythmiaBannerIfPresent(source: Intent) {
        val message = source.getStringExtra(EXTRA_ALERT_MESSAGE) ?: return
        val action = source.getStringExtra(EXTRA_ALERT_ACTION).orEmpty()
        val critical = source.getBooleanExtra(EXTRA_ALERT_CRITICAL, false)
        // Consume the extras so rotating or returning to the screen does not repeat the dialog.
        source.removeExtra(EXTRA_ALERT_MESSAGE)

        HapticFeedbackManager.warningBuzz()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_PulseLink_Dialog)
            .setTitle(
                if (critical) R.string.l10n_arrhythmia_title_critical else R.string.l10n_arrhythmia_title_warning
            )
            .setMessage(getString(R.string.l10n_arrhythmia_sos_banner_format, message, action))
            .setPositiveButton(R.string.l10n_arrhythmia_action_call_now) { _, _ ->
                HapticFeedbackManager.warningBuzz()
                dialEmergencyNumber(getString(R.string.emergency_number_general))
            }
            .setNeutralButton(R.string.l10n_arrhythmia_action_start_sos) { _, _ ->
                if (!isSosCountdownActive) handleSosTriggerClick()
            }
            .setNegativeButton(R.string.l10n_arrhythmia_action_im_ok, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        applyStoredAvatar(R.id.btnProfileAvatar, auth.currentUser?.uid)
    }

    private fun bindViews() {
        ivSettings = findViewById(R.id.ivSettings)
        btnSosTrigger = findViewById(R.id.btnSosTrigger)
        tvSosButtonLabel = findViewById(R.id.tvSosButtonLabel)
        cardCallHospital = findViewById(R.id.cardCallHospital)
        cardCallAmbulance = findViewById(R.id.cardCallAmbulance)
        cardAlertContacts = findViewById(R.id.cardAlertContacts)
    }

    private fun setupClickListeners() {
        openOnClick(R.id.btnProfileAvatar, PatientProfileActivity::class.java)

        ivSettings.setOnClickListener {
            HapticFeedbackManager.lightTick()
            startActivity(Intent(this, Patient_Settings::class.java))
        }

        btnSosTrigger.setOnClickListener {
            handleSosTriggerClick()
        }

        // South African national general emergency services (112 - cellular routing)
        cardCallHospital.setOnClickListener {
            HapticFeedbackManager.warningBuzz()
            dialEmergencyNumber(getString(R.string.emergency_number_general))
        }

        // South African medical & ambulance emergency services (10177)
        cardCallAmbulance.setOnClickListener {
            HapticFeedbackManager.warningBuzz()
            dialEmergencyNumber(getString(R.string.emergency_number_ambulance))
        }

        // Alert designated family and clinical contacts with latest ECG metrics
        cardAlertContacts.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            dispatchEmergencyContactAlert()
        }

        // Browse reviewed public clinics & hospitals with Google Maps navigation
        findViewById<View?>(R.id.cardFindClinics)?.setOnClickListener {
            HapticFeedbackManager.lightTick()
            startActivity(Intent(this, FacilityFinderActivity::class.java))
        }
    }

    private fun setupBottomNav() {
        BottomNavHelper.setUpPatientNav(this, BottomNavHelper.PatientTab.SOS)
    }

    // Handles the primary red SOS button activation with a 3-second safety countdown.
    private fun handleSosTriggerClick() {
        if (isSosCountdownActive) {
            // Cancel pending SOS countdown
            sosCountDownTimer?.cancel()
            isSosCountdownActive = false
            tvSosButtonLabel.text = getString(R.string.patient_emergency_sos_text_sos)
            HapticFeedbackManager.lightTick()
            Toast.makeText(this, getString(R.string.l10n_sos_cancelled), Toast.LENGTH_SHORT).show()
        } else {
            // Start 3-second countdown to automated dispatch
            isSosCountdownActive = true
            HapticFeedbackManager.warningBuzz()

            sosCountDownTimer = object : CountDownTimer(3000L, 1000L) {
                override fun onTick(millisUntilFinished: Long) {
                    val secondsLeft = (millisUntilFinished / 1000) + 1
                    tvSosButtonLabel.text = "$secondsLeft"
                    HapticFeedbackManager.warningBuzz()
                }

                override fun onFinish() {
                    isSosCountdownActive = false
                    tvSosButtonLabel.text = getString(R.string.patient_emergency_sos_text_sos)
                    HapticFeedbackManager.warningBuzz()
                    executeEmergencyDispatch()
                }
            }.start()
        }
    }

    // Executes full emergency dispatch protocol: opens 112 dialer and sends emergency notification.
    private fun executeEmergencyDispatch() {
        Toast.makeText(
            this,
            getString(R.string.l10n_sos_connecting),
            Toast.LENGTH_LONG
        ).show()
        dialEmergencyNumber(getString(R.string.emergency_number_general))
    }

    // Opens Android telephony dialer with the specified emergency number.
    private fun dialEmergencyNumber(phoneNumber: String) {
        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$phoneNumber")
        }
        startActivity(dialIntent)
    }

    // Dispatches emergency alert message containing patient ID and AD8232 monitoring status.
    private fun dispatchEmergencyContactAlert() {
        val user = auth.currentUser
        val displayName = user?.displayName?.ifBlank { null } ?: user?.email?.substringBefore("@") ?: getString(R.string.l10n_unknown_patient)
        val message = getString(R.string.l10n_sos_message_format, displayName)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.l10n_sos_subject))
            putExtra(Intent.EXTRA_TEXT, message)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.l10n_sos_chooser)))
    }

    // Adds a subtle breathing scale pulse animation to the central SOS trigger button.
    private fun animateSosButtonPulse() {
        val scaleX = ObjectAnimator.ofFloat(btnSosTrigger, "scaleX", 1f, 1.05f, 1f).apply {
            duration = 1800
            repeatCount = ObjectAnimator.INFINITE
            interpolator = OvershootInterpolator()
        }
        val scaleY = ObjectAnimator.ofFloat(btnSosTrigger, "scaleY", 1f, 1.05f, 1f).apply {
            duration = 1800
            repeatCount = ObjectAnimator.INFINITE
            interpolator = OvershootInterpolator()
        }
        scaleX.start()
        scaleY.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        sosCountDownTimer?.cancel()
    }

    companion object {
        const val EXTRA_ALERT_MESSAGE = "extra_alert_message"
        const val EXTRA_ALERT_ACTION = "extra_alert_action"
        const val EXTRA_ALERT_BPM = "extra_alert_bpm"
        const val EXTRA_ALERT_CRITICAL = "extra_alert_critical"
    }
}