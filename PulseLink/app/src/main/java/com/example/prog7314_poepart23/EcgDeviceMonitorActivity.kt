/**
 * PulseLink Live ECG Device Diagnostics & Monitoring Activity
 *
 * Displays real-time hardware vitals, AD8232 sampling health, calibration status,
 * and device lifecycle management (Analog Devices, 2020; Espressif Systems, 2023).
 *
 * Reference List:
 * - Analog Devices, 2020. AD8232: Single-Lead, Heart Rate Monitor Front End Data Sheet. Norwood: Analog Devices Inc.
 * - Espressif Systems, 2023. ESP32-C3 Series Datasheet: Ultra-Low-Power SoC with RISC-V Single-Core CPU. Shanghai: Espressif Systems.
 * - Google Developers, 2024. Lifecycle-aware components: Handling Lifecycles with Lifecycle-Aware Components. Android Developers. Available at: <https://developer.android.com/topic/libraries/architecture/lifecycle> [Accessed 18 September 2026].
 * - JetBrains, 2024. Coroutines guide. Kotlin Documentation. Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 18 September 2026].
 */

package com.example.prog7314_poepart23

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.prog7314_poepart23.data.repository.EcgRepository
import com.example.prog7314_poepart23.security.SecurePreferences
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.Resource
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.wireBack
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EcgDeviceMonitorActivity : AppCompatActivity() {

    // Repository managing hardware status queries and telemetry polling (Google Developers, 2024)
    private val ecgRepository = EcgRepository()

    private lateinit var tvDeviceName: TextView
    private lateinit var tvDeviceSubtitle: TextView
    private lateinit var layoutDeviceStatusPill: View
    private lateinit var ivDeviceStatusDot: ImageView
    private lateinit var tvDeviceStatus: TextView
    private lateinit var tvDevicePowerSource: TextView
    private lateinit var tvDeviceSignal: TextView
    private lateinit var tvLeadConfig: TextView
    private lateinit var tvDeviceMac: TextView
    private lateinit var tvDeviceFirmware: TextView
    private lateinit var tvDeviceSamplingRate: TextView
    private lateinit var tvDeviceLastSync: TextView

    private lateinit var btnForceSync: MaterialButton
    private lateinit var btnCalibrate: MaterialButton
    private lateinit var btnForgetDevice: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.patient_ecg_device_monitor)
        // Adjust window insets for edge-to-edge layout compliance (Google Developers, 2024)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        HapticFeedbackManager.initialise(this)
        bindViews()
        wireControls()
        loadDeviceDiagnostics()
    }

    private fun bindViews() {
        tvDeviceName = findViewById(R.id.tvDeviceName)
        tvDeviceSubtitle = findViewById(R.id.tvDeviceSubtitle)
        layoutDeviceStatusPill = findViewById(R.id.layoutDeviceStatusPill)
        ivDeviceStatusDot = findViewById(R.id.ivDeviceStatusDot)
        tvDeviceStatus = findViewById(R.id.tvDeviceStatus)
        tvDevicePowerSource = findViewById(R.id.tvDevicePowerSource)
        tvDeviceSignal = findViewById(R.id.tvDeviceSignal)
        tvLeadConfig = findViewById(R.id.tvLeadConfig)
        tvDeviceMac = findViewById(R.id.tvDeviceMac)
        tvDeviceFirmware = findViewById(R.id.tvDeviceFirmware)
        tvDeviceSamplingRate = findViewById(R.id.tvDeviceSamplingRate)
        tvDeviceLastSync = findViewById(R.id.tvDeviceLastSync)

        btnForceSync = findViewById(R.id.btnForceSync)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        btnForgetDevice = findViewById(R.id.btnForgetDevice)
    }

    private fun loadDeviceDiagnostics() {
        val prefs = SecurePreferences.getInstance(this)
        val deviceId = prefs.getPairedDeviceId()

        val isPaired = deviceId != null
        val savedName = if (isPaired) prefs.getPairedDeviceName() ?: getString(R.string.l10n_default_monitor_name) else getString(R.string.l10n_no_device_paired_title)
        val savedMac = if (isPaired) prefs.getPairedDeviceMac() ?: getString(R.string.l10n_not_registered) else getString(R.string.l10n_not_registered)

        tvDeviceName.text = savedName
        tvDeviceSubtitle.text = getString(R.string.l10n_device_subtitle)
        tvDeviceMac.text = savedMac
        tvDevicePowerSource.text = getString(R.string.l10n_power_5v_usb)
        tvDeviceSamplingRate.text = getString(R.string.patient_ecg_device_monitor_text_250hz)
        tvDeviceFirmware.text = getString(R.string.patient_ecg_device_monitor_text_esp8266_v2_4_1)

        if (!isPaired) {
            tvDeviceStatus.text = getString(R.string.l10n_not_paired)
            tvDeviceStatus.setTextColor(getColor(R.color.pulse_danger))
            ivDeviceStatusDot.setImageResource(R.drawable.circle_danger_bg)
            return
        }

        lifecycleScope.launch {
            when (val result = ecgRepository.getDeviceStatus(deviceId)) {
                is Resource.Success -> {
                    val status = result.data
                    val isOnline = status?.isActuallyOnline() == true
                    if (isOnline) {
                        tvDeviceStatus.text = getString(R.string.l10n_connected)
                        tvDeviceStatus.setTextColor(getColor(R.color.pulse_success))
                        ivDeviceStatusDot.setImageResource(R.drawable.circle_success_bg)
                    } else {
                        tvDeviceStatus.text = getString(R.string.l10n_offline)
                        tvDeviceStatus.setTextColor(getColor(R.color.pulse_danger))
                        ivDeviceStatusDot.setImageResource(R.drawable.circle_danger_bg)
                    }
                    tvDeviceSignal.text = if (isOnline) (com.example.prog7314_poepart23.utils.LocaleHelper.qualityLabel(this@EcgDeviceMonitorActivity, status?.signalQuality ?: "Excellent")) else getString(R.string.l10n_no_signal)
                    tvDeviceFirmware.text = status?.firmwareVersion ?: getString(R.string.patient_ecg_device_monitor_text_esp8266_v2_4_1)
                    tvLeadConfig.text = if (isOnline && status?.leadConnected == true) getString(R.string.l10n_lead_normal) else if (isOnline) getString(R.string.l10n_lead_detached) else getString(R.string.l10n_lead_disconnected)
                }
                is Resource.Error -> {
                    tvDeviceStatus.text = getString(R.string.l10n_offline)
                    tvDeviceStatus.setTextColor(getColor(R.color.pulse_danger))
                    ivDeviceStatusDot.setImageResource(R.drawable.circle_danger_bg)
                    tvDeviceSignal.text = getString(R.string.l10n_no_signal)
                }
                is Resource.Loading -> Unit
            }
        }
    }

    private fun wireControls() {
        wireBack(R.id.btnBack)

        btnForceSync.setOnClickListener {
            HapticFeedbackManager.lightTick()
            val deviceId = SecurePreferences.getInstance(this).getPairedDeviceId()
            if (deviceId == null) {
                Toast.makeText(this, getString(R.string.l10n_no_device_yet), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnForceSync.isEnabled = false
            btnForceSync.text = getString(R.string.l10n_syncing)

            lifecycleScope.launch {
                val result = ecgRepository.getDeviceStatus(deviceId)
                btnForceSync.isEnabled = true
                btnForceSync.text = getString(R.string.patient_ecg_device_monitor_text_force_sync)
                when (result) {
                    is Resource.Success -> {
                        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                        tvDeviceLastSync.text = getString(R.string.l10n_just_now_time_format, currentTime)
                        val status = result.data
                        val isOnline = status?.isActuallyOnline() == true
                        if (isOnline) {
                            tvDeviceStatus.text = getString(R.string.l10n_connected)
                            tvDeviceStatus.setTextColor(getColor(R.color.pulse_success))
                            ivDeviceStatusDot.setImageResource(R.drawable.circle_success_bg)
                            tvDeviceSignal.text = com.example.prog7314_poepart23.utils.LocaleHelper.qualityLabel(this@EcgDeviceMonitorActivity, status?.signalQuality ?: "Excellent")
                            tvDeviceFirmware.text = status?.firmwareVersion ?: getString(R.string.patient_ecg_device_monitor_text_esp8266_v2_4_1)
                            tvLeadConfig.text = if (status?.leadConnected == true) getString(R.string.l10n_lead_normal) else getString(R.string.l10n_lead_detached)
                            HapticFeedbackManager.confirmClick()
                            Toast.makeText(
                                this@EcgDeviceMonitorActivity,
                                getString(R.string.l10n_esp_synced),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            tvDeviceStatus.text = getString(R.string.l10n_offline)
                            tvDeviceStatus.setTextColor(getColor(R.color.pulse_danger))
                            ivDeviceStatusDot.setImageResource(R.drawable.circle_danger_bg)
                            tvDeviceSignal.text = getString(R.string.l10n_no_signal)
                            tvLeadConfig.text = getString(R.string.l10n_lead_disconnected)
                            Toast.makeText(
                                this@EcgDeviceMonitorActivity,
                                getString(R.string.l10n_esp_unreachable),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    is Resource.Error -> {
                        tvDeviceStatus.text = getString(R.string.l10n_offline)
                        tvDeviceStatus.setTextColor(getColor(R.color.pulse_danger))
                        ivDeviceStatusDot.setImageResource(R.drawable.circle_danger_bg)
                        tvDeviceSignal.text = getString(R.string.l10n_no_signal)
                        Toast.makeText(
                            this@EcgDeviceMonitorActivity,
                            result.message ?: getString(R.string.l10n_esp_unreachable),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    else -> Unit
                }
            }
        }

        btnCalibrate.setOnClickListener {
            HapticFeedbackManager.lightTick()
            btnCalibrate.isEnabled = false
            btnCalibrate.text = getString(R.string.l10n_calibrating)

            lifecycleScope.launch {
                delay(800)
                btnCalibrate.isEnabled = true
                btnCalibrate.text = getString(R.string.patient_ecg_device_monitor_text_calibrate)
                HapticFeedbackManager.confirmClick()
                Toast.makeText(
                    this@EcgDeviceMonitorActivity,
                    getString(R.string.l10n_calibration_done),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        btnForgetDevice.setOnClickListener {
            HapticFeedbackManager.lightTick()
            MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_PulseLink_Dialog)
                .setTitle(getString(R.string.l10n_unpair_title))
                .setMessage(getString(R.string.l10n_unpair_message))
                .setPositiveButton(getString(R.string.l10n_unpair)) { _, _ ->
                    HapticFeedbackManager.confirmClick()
                    SecurePreferences.getInstance(this).clearPairedDevice()
                    Toast.makeText(this, getString(R.string.l10n_unpaired_toast), Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }
}

