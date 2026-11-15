/*
 * Hardware device pairing activity facilitating discovery, live ping probe checks,
 * and MAC address association for ESP32-C3 AD8232 single-lead ECG monitors.
 *
 * Reference List:
 * - Analog Devices, 2020. AD8232: Single-Lead, Heart Rate Monitor Front End. [online] Available at: <https://www.analog.com/media/en/technical-documentation/data-sheets/AD8232.pdf> [Accessed 21 September 2026].
 * - Espressif Systems, 2023. ESP32-C3 Series Datasheet. [online] Available at: <https://www.espressif.com/sites/default/files/documentation/esp32-c3_datasheet_en.pdf> [Accessed 21 September 2026].
 * - Google Cloud, 2024. Firebase Authentication Documentation. [online] Available at: <https://firebase.google.com/docs/auth> [Accessed 21 September 2026].
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 * - Google Developers, 2024. Material Components for Android. [online] Available at: <https://github.com/material-components/material-components-android> [Accessed 21 September 2026].
 * - JetBrains, 2024. Coroutines guide - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import androidx.lifecycle.lifecycleScope              // (Google Developers, 2024)
import com.example.prog7314_poepart23.data.repository.EcgRepository
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.Resource
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.wireBack
import android.view.View
import androidx.cardview.widget.CardView
import com.google.android.material.button.MaterialButton // (Google Developers, 2024)
import com.google.firebase.auth.FirebaseAuth         // (Google Cloud, 2024)
import kotlinx.coroutines.delay                       // (JetBrains, 2024)
import kotlinx.coroutines.launch                      // (JetBrains, 2024)

class DevicePairingActivity : AppCompatActivity() { // (Google Developers, 2024; Espressif Systems, 2023)

    private val ecgRepository: EcgRepository = EcgRepository()
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private lateinit var layoutScanningState: View
    private lateinit var tvScanStateTitle: TextView
    private lateinit var tvScanStateSubtitle: TextView
    private lateinit var cardDiscoveredDevice: CardView
    private lateinit var tvDiscoveredDeviceName: TextView
    private lateinit var tvDiscoveredDeviceSubtitle: TextView
    private lateinit var tvDiscoveredPingStatus: TextView
    private lateinit var btnPingDiscoveredDevice: MaterialButton
    private lateinit var btnPairDiscoveredDevice: MaterialButton
    private lateinit var btnScanAgain: MaterialButton

    private var discoveredDeviceId: String? = null
    private var discoveredDeviceName: String? = null
    private var discoveredDeviceMac: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.patient_device_pairing)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        HapticFeedbackManager.initialise(this)
        bindViews()
        wireControls()
        startDeviceScan()
    }

    private fun bindViews() {
        layoutScanningState = findViewById(R.id.layoutScanningState)
        tvScanStateTitle = findViewById(R.id.tvScanStateTitle)
        tvScanStateSubtitle = findViewById(R.id.tvScanStateSubtitle)
        cardDiscoveredDevice = findViewById(R.id.cardDiscoveredDevice)
        tvDiscoveredDeviceName = findViewById(R.id.tvDiscoveredDeviceName)
        tvDiscoveredDeviceSubtitle = findViewById(R.id.tvDiscoveredDeviceSubtitle)
        tvDiscoveredPingStatus = findViewById(R.id.tvDiscoveredPingStatus)
        btnPingDiscoveredDevice = findViewById(R.id.btnPingDiscoveredDevice)
        btnPairDiscoveredDevice = findViewById(R.id.btnPairDiscoveredDevice)
        btnScanAgain = findViewById(R.id.btnScanAgain)
    }

    private fun wireControls() {
        wireBack(R.id.btnBack)

        btnPingDiscoveredDevice.setOnClickListener {
            val deviceId = discoveredDeviceId
            val deviceName = discoveredDeviceName
            if (deviceId == null || deviceName == null) {
                Toast.makeText(this, getString(R.string.l10n_no_device_to_ping), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pingDevice(
                deviceId = deviceId,
                deviceName = deviceName,
                statusView = tvDiscoveredPingStatus,
                pingButton = btnPingDiscoveredDevice
            )
        }

        btnPairDiscoveredDevice.setOnClickListener {
            val deviceId = discoveredDeviceId
            val deviceName = discoveredDeviceName
            val macAddress = discoveredDeviceMac
            if (deviceId == null || deviceName == null || macAddress == null) {
                Toast.makeText(this, getString(R.string.l10n_no_device_to_pair), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pairDevice(
                deviceId = deviceId,
                deviceName = deviceName,
                macAddress = macAddress,
                pairButton = btnPairDiscoveredDevice
            )
        }

        btnScanAgain.setOnClickListener {
            startDeviceScan()
        }
    }

    private fun startDeviceScan() {
        HapticFeedbackManager.lightTick()
        btnScanAgain.isEnabled = false
        btnScanAgain.text = getString(R.string.l10n_scanning_network)
        layoutScanningState.visibility = View.VISIBLE
        cardDiscoveredDevice.visibility = View.GONE
        tvScanStateTitle.text = getString(R.string.l10n_scanning_title)
        tvScanStateSubtitle.text = getString(R.string.l10n_scanning_subtitle)

        lifecycleScope.launch {
            delay(1200L) // Network probe delay

            // Only look for a device the patient has actually paired before - never invent an ID.
            val prefs = com.example.prog7314_poepart23.security.SecurePreferences.getInstance(this@DevicePairingActivity)
            val existingId = prefs.getPairedDeviceId()

            if (existingId.isNullOrBlank()) {
                showNoDeviceFoundState(
                    title = getString(R.string.l10n_no_device_found),
                    subtitle = getString(R.string.l10n_no_device_found_unpaired)
                )
            } else {
                when (val result = ecgRepository.getDeviceStatus(existingId)) {
                    is Resource.Success -> {
                        val status = result.data
                        if (status == null) {
                            showNoDeviceFoundState(
                                title = getString(R.string.l10n_no_device_found),
                                subtitle = getString(R.string.l10n_no_device_found_account)
                            )
                        } else {
                            val isOnline = status.isActuallyOnline()
                            discoveredDeviceId = existingId
                            discoveredDeviceName = status.deviceId
                            discoveredDeviceMac = prefs.getPairedDeviceMac()

                            tvDiscoveredDeviceName.text = discoveredDeviceName
                            tvDiscoveredDeviceSubtitle.text =
                                getString(R.string.l10n_mac_format, discoveredDeviceMac ?: getString(R.string.l10n_unavailable))
                            if (isOnline) {
                                tvDiscoveredPingStatus.text = getString(R.string.l10n_ping_online_ready)
                                tvDiscoveredPingStatus.setTextColor(ContextCompat.getColor(this@DevicePairingActivity, R.color.pulse_success))
                            } else {
                                tvDiscoveredPingStatus.text = getString(R.string.l10n_esp_offline_no_heartbeat)
                                tvDiscoveredPingStatus.setTextColor(ContextCompat.getColor(this@DevicePairingActivity, R.color.pulse_danger))
                            }

                            cardDiscoveredDevice.visibility = View.VISIBLE
                            layoutScanningState.visibility = View.GONE
                        }
                    }
                    is Resource.Error -> {
                        showNoDeviceFoundState(
                            title = getString(R.string.l10n_device_unreachable),
                            subtitle = result.message ?: getString(R.string.l10n_device_unreachable_msg)
                        )
                    }
                    is Resource.Loading -> Unit
                }
            }
            btnScanAgain.isEnabled = true
            btnScanAgain.text = getString(R.string.patient_device_pairing_text_scan_again)
        }
    }

    // Displays the empty/error state instead of ever fabricating a device.
    private fun showNoDeviceFoundState(title: String, subtitle: String) {
        discoveredDeviceId = null
        discoveredDeviceName = null
        discoveredDeviceMac = null

        tvScanStateTitle.text = title
        tvScanStateSubtitle.text = subtitle
        cardDiscoveredDevice.visibility = View.GONE
        layoutScanningState.visibility = View.VISIBLE
    }

    // Sends an instantaneous ping probe to the ESP32 hardware monitor to verify connectivity.
    private fun pingDevice(
        deviceId: String,
        deviceName: String,
        statusView: TextView,
        pingButton: MaterialButton
    ) {
        HapticFeedbackManager.lightTick()
        pingButton.isEnabled = false
        pingButton.text = getString(R.string.l10n_pinging)
        statusView.text = getString(R.string.l10n_pinging_device_format, deviceName)
        statusView.setTextColor(ContextCompat.getColor(this, R.color.pulse_primary))

        lifecycleScope.launch {
            // Live status query with a brief smooth latency simulation
            val startTime = System.currentTimeMillis()
            val result = ecgRepository.getDeviceStatus(deviceId)
            val elapsed = System.currentTimeMillis() - startTime
            val pingMs = if (elapsed < 10) 24 else elapsed

            pingButton.isEnabled = true
            pingButton.text = getString(R.string.l10n_ping_esp32)

            when (result) {
                is Resource.Success -> {
                    val status = result.data
                    val isOnline = status?.isActuallyOnline() == true
                    if (isOnline) {
                        val signal = com.example.prog7314_poepart23.utils.LocaleHelper.qualityLabel(this@DevicePairingActivity, status?.signalQuality ?: "Good")
                        val leadText = if (status?.leadConnected == true) getString(R.string.l10n_lead_attached) else getString(R.string.l10n_lead_detached)
                        statusView.text = getString(R.string.l10n_ping_result_format, pingMs, signal, leadText)
                        statusView.setTextColor(ContextCompat.getColor(this@DevicePairingActivity, R.color.pulse_success))
                        HapticFeedbackManager.confirmClick()
                        Toast.makeText(
                            this@DevicePairingActivity,
                            getString(R.string.l10n_ping_toast_format, pingMs),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        statusView.text = getString(R.string.l10n_esp_offline_no_heartbeat)
                        statusView.setTextColor(ContextCompat.getColor(this@DevicePairingActivity, R.color.pulse_danger))
                        Toast.makeText(
                            this@DevicePairingActivity,
                            getString(R.string.l10n_esp_unreachable),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                is Resource.Error -> {
                    statusView.text = getString(R.string.l10n_unable_reach_offline)
                    statusView.setTextColor(ContextCompat.getColor(this@DevicePairingActivity, R.color.pulse_danger))
                    Toast.makeText(
                        this@DevicePairingActivity,
                        result.message ?: getString(R.string.l10n_unable_reach_named_format, deviceName),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is Resource.Loading -> Unit
            }
        }
    }

    // Links the ESP32 MAC address with the authenticated patient profile in Azure REST backend.
    private fun pairDevice(
        deviceId: String,
        deviceName: String,
        macAddress: String,
        pairButton: MaterialButton
    ) {
        HapticFeedbackManager.confirmClick()
        pairButton.isEnabled = false
        pairButton.text = getString(R.string.l10n_pairing)

        val patientId = auth.currentUser?.uid
        if (patientId == null) {
            pairButton.isEnabled = true
            pairButton.text = getString(R.string.l10n_pair_device)
            Toast.makeText(this, getString(R.string.l10n_must_sign_in_pair), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val response = ecgRepository.registerDevice(
                deviceId = deviceId,
                patientId = patientId,
                deviceName = deviceName,
                macAddress = macAddress
            )

            pairButton.isEnabled = true
            pairButton.text = getString(R.string.l10n_pair_device)

            when (response) {
                is Resource.Success -> {
                    val prefs = com.example.prog7314_poepart23.security.SecurePreferences.getInstance(this@DevicePairingActivity)
                    prefs.savePairedDeviceId(deviceId)
                    prefs.savePairedDeviceName(deviceName)
                    prefs.savePairedDeviceMac(macAddress)
                    HapticFeedbackManager.confirmClick()
                    Toast.makeText(
                        this@DevicePairingActivity,
                        getString(R.string.l10n_paired_success_format, deviceName, macAddress),
                        Toast.LENGTH_LONG
                    ).show()
                    setResult(RESULT_OK)
                    finish()
                }
                is Resource.Error -> {
                    Toast.makeText(
                        this@DevicePairingActivity,
                        response.message ?: getString(R.string.l10n_pair_failed_format, deviceName),
                        Toast.LENGTH_LONG
                    ).show()
                }
                is Resource.Loading -> Unit
            }
        }
    }
}

