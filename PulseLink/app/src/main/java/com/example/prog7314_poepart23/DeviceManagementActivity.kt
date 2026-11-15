/**
 * PulseLink Telemetry & Device Management Activity
 *
 * Provides connection status diagnostics, battery telemetry, manual synchronisation,
 * and configuration routes for paired ECG hardware monitors (Espressif Systems, 2023).
 *
 * Reference List:
 * - Espressif Systems, 2023. ESP32-C3 Series Datasheet: Ultra-Low-Power SoC with RISC-V Single-Core CPU. Shanghai: Espressif Systems.
 * - Google Developers, 2024. Lifecycle-aware components: Handling Lifecycles with Lifecycle-Aware Components. Android Developers. Available at: <https://developer.android.com/topic/libraries/architecture/lifecycle> [Accessed 18 September 2026].
 * - Google Developers, 2024. View binding and Material Design components in Android. Android Developers. Available at: <https://developer.android.com/guide/topics/ui> [Accessed 18 September 2026].
 */

package com.example.prog7314_poepart23

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.prog7314_poepart23.data.repository.EcgRepository
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.Resource
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.openOnClick
import com.example.prog7314_poepart23.utils.wireBack
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceManagementActivity : AppCompatActivity() {

    // Repository managing device status queries and remote telemetry caching (Google Developers, 2024)
    private val ecgRepository = EcgRepository()
    private lateinit var tvDeviceName: TextView
    private lateinit var tvDeviceSubtitle: TextView
    private lateinit var tvDeviceLastSync: TextView
    private lateinit var tvDeviceConnectionStatus: TextView
    private lateinit var tvDevicePowerSource: TextView
    private lateinit var tvDeviceSignalQuality: TextView
    private lateinit var tvDeviceMac: TextView
    private lateinit var tvDeviceFirmware: TextView
    private lateinit var btnForceSync: MaterialButton
    private lateinit var layoutDeviceOfflineBanner: android.view.View
    private lateinit var cardPrimaryDevice: CardView
    private lateinit var cardNoDevice: CardView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.patient_device_management)
        // Adjust window insets for edge-to-edge layout compliance (Google Developers, 2024)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        HapticFeedbackManager.initialise(this)
        bindViews()
        wireControls()
        fetchLatestDeviceTelemetry()

        BottomNavHelper.setUpPatientNav(this, BottomNavHelper.PatientTab.DASHBOARD)
    }

    override fun onResume() {
        super.onResume()
        BottomNavHelper.setUpPatientNav(this, BottomNavHelper.PatientTab.DASHBOARD)
        fetchLatestDeviceTelemetry()
    }

    private fun bindViews() {
        tvDeviceName = findViewById(R.id.tvDeviceName)
        tvDeviceSubtitle = findViewById(R.id.tvDeviceSubtitle)
        tvDeviceLastSync = findViewById(R.id.tvDeviceLastSync)
        tvDeviceConnectionStatus = findViewById(R.id.tvDeviceConnectionStatus)
        tvDevicePowerSource = findViewById(R.id.tvDevicePowerSource)
        tvDeviceSignalQuality = findViewById(R.id.tvDeviceSignalQuality)
        tvDeviceMac = findViewById(R.id.tvDeviceMac)
        tvDeviceFirmware = findViewById(R.id.tvDeviceFirmware)
        btnForceSync = findViewById(R.id.btnForceSync)
        layoutDeviceOfflineBanner = findViewById(R.id.layoutDeviceOfflineBanner)
        cardPrimaryDevice = findViewById(R.id.cardPrimaryDevice)
        cardNoDevice = findViewById(R.id.cardNoDevice)
    }

    private fun wireControls() {
        wireBack(R.id.btnBack)

        // Pairing new device
        openOnClick(R.id.btnPairNewDevice, DevicePairingActivity::class.java)

        // Device details & configuration
        openOnClick(R.id.cardPrimaryDevice, EcgDeviceMonitorActivity::class.java)
        openOnClick(R.id.btnConfigureDevice, EcgDeviceMonitorActivity::class.java)

        // Guides and support
        openOnClick(R.id.rowUserManual, ElectrodePlacementGuideActivity::class.java)
        openOnClick(R.id.rowTroubleshooting, HelpSupportActivity::class.java)

        // Force synchronise telemetry
        btnForceSync.setOnClickListener {
            forceSyncDevice()
        }
    }

    // Fetches live ESP32 connection state from the remote backend for the device the
    // patient has actually paired. Shows an empty state if no device is paired yet.
    private fun fetchLatestDeviceTelemetry() {
        val prefs = com.example.prog7314_poepart23.security.SecurePreferences.getInstance(this)
        val deviceId = prefs.getPairedDeviceId()

        if (deviceId == null) {
            cardPrimaryDevice.visibility = android.view.View.GONE
            cardNoDevice.visibility = android.view.View.VISIBLE
            layoutDeviceOfflineBanner.visibility = android.view.View.GONE
            return
        }

        cardPrimaryDevice.visibility = android.view.View.VISIBLE
        cardNoDevice.visibility = android.view.View.GONE

        val savedName = prefs.getPairedDeviceName() ?: getString(R.string.l10n_default_monitor_name)
        val savedMac = prefs.getPairedDeviceMac() ?: getString(R.string.l10n_not_registered)
        tvDeviceName.text = savedName
        tvDeviceMac.text = savedMac
        tvDeviceSubtitle.text = getString(R.string.l10n_device_subtitle)
        tvDevicePowerSource.text = getString(R.string.l10n_usb_powered)

        lifecycleScope.launch {
            when (val result = ecgRepository.getDeviceStatus(deviceId)) {
                is Resource.Success -> {
                    val status = result.data
                    val isOnline = status?.isActuallyOnline() == true
                    if (isOnline) {
                        tvDeviceConnectionStatus.text = getString(R.string.l10n_connected)
                        tvDeviceConnectionStatus.setBackgroundResource(R.drawable.bg_pill_success)
                        tvDeviceConnectionStatus.setTextColor(getColor(R.color.pulse_success))
                        layoutDeviceOfflineBanner.visibility = android.view.View.GONE
                    } else {
                        tvDeviceConnectionStatus.text = getString(R.string.l10n_offline)
                        tvDeviceConnectionStatus.setBackgroundResource(R.drawable.bg_pill_danger)
                        tvDeviceConnectionStatus.setTextColor(getColor(R.color.pulse_danger))
                        layoutDeviceOfflineBanner.visibility = android.view.View.VISIBLE
                    }
                    tvDeviceSignalQuality.text = if (isOnline) (com.example.prog7314_poepart23.utils.LocaleHelper.qualityLabel(this@DeviceManagementActivity, status?.signalQuality ?: "Excellent")) else getString(R.string.l10n_no_signal)
                    tvDeviceFirmware.text = status?.firmwareVersion ?: getString(R.string.patient_ecg_device_monitor_text_esp8266_v2_4_1)
                }
                is Resource.Error -> {
                    tvDeviceConnectionStatus.text = getString(R.string.l10n_offline)
                    tvDeviceConnectionStatus.setBackgroundResource(R.drawable.bg_pill_danger)
                    tvDeviceConnectionStatus.setTextColor(getColor(R.color.pulse_danger))
                    layoutDeviceOfflineBanner.visibility = android.view.View.VISIBLE
                    tvDeviceSignalQuality.text = getString(R.string.l10n_no_signal)
                }
                is Resource.Loading -> Unit
            }
        }
    }

    // Sends an instantaneous ping / sync probe to refresh hardware telemetry.
    private fun forceSyncDevice() {
        HapticFeedbackManager.lightTick()
        btnForceSync.isEnabled = false
        btnForceSync.text = getString(R.string.l10n_syncing)

        val deviceId = com.example.prog7314_poepart23.security.SecurePreferences.getInstance(this).getPairedDeviceId()
        if (deviceId == null) {
            btnForceSync.isEnabled = true
            btnForceSync.text = getString(R.string.patient_device_management_text_force_sync)
            Toast.makeText(this, getString(R.string.l10n_no_device_yet), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val result = ecgRepository.getDeviceStatus(deviceId)
            btnForceSync.isEnabled = true
            btnForceSync.text = getString(R.string.patient_device_management_text_force_sync)

            when (result) {
                is Resource.Success -> {
                    val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    tvDeviceLastSync.text = getString(R.string.l10n_just_now_time_format, currentTime)
                    val status = result.data
                    val isOnline = status?.isActuallyOnline() == true
                    if (isOnline) {
                        tvDeviceConnectionStatus.text = getString(R.string.l10n_connected)
                        tvDeviceConnectionStatus.setBackgroundResource(R.drawable.bg_pill_success)
                        tvDeviceConnectionStatus.setTextColor(getColor(R.color.pulse_success))
                        layoutDeviceOfflineBanner.visibility = android.view.View.GONE
                        tvDeviceSignalQuality.text = com.example.prog7314_poepart23.utils.LocaleHelper.qualityLabel(this@DeviceManagementActivity, status?.signalQuality ?: "Excellent")
                        tvDeviceFirmware.text = status?.firmwareVersion ?: getString(R.string.patient_ecg_device_monitor_text_esp8266_v2_4_1)
                        HapticFeedbackManager.confirmClick()
                        Toast.makeText(
                            this@DeviceManagementActivity,
                            getString(R.string.l10n_esp_synced),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        tvDeviceConnectionStatus.text = getString(R.string.l10n_offline)
                        tvDeviceConnectionStatus.setBackgroundResource(R.drawable.bg_pill_danger)
                        tvDeviceConnectionStatus.setTextColor(getColor(R.color.pulse_danger))
                        layoutDeviceOfflineBanner.visibility = android.view.View.VISIBLE
                        tvDeviceSignalQuality.text = getString(R.string.l10n_no_signal)
                        Toast.makeText(
                            this@DeviceManagementActivity,
                            getString(R.string.l10n_esp_unreachable),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                is Resource.Error -> {
                    tvDeviceConnectionStatus.text = getString(R.string.l10n_offline)
                    tvDeviceConnectionStatus.setBackgroundResource(R.drawable.bg_pill_danger)
                    tvDeviceConnectionStatus.setTextColor(getColor(R.color.pulse_danger))
                    layoutDeviceOfflineBanner.visibility = android.view.View.VISIBLE
                    tvDeviceSignalQuality.text = getString(R.string.l10n_no_signal)
                    Toast.makeText(
                        this@DeviceManagementActivity,
                        result.message ?: getString(R.string.l10n_esp_unreachable),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is Resource.Loading -> Unit
            }
        }
    }
}

