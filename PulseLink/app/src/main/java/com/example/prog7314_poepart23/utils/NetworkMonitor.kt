/*
 * Lifecycle-aware Network Connectivity Monitor conforming to medical application offline-first resilience.
 * Employs ConnectivityManager.NetworkCallback to observe real-time network capability changes and automatically
 * triggers background draining of cached un-synced ECG telemetry sessions upon reconnection.
 *
 * Reference List:
 * - Google Developers, 2024. Monitor connectivity status and connection metering. [online] Available at: <https://developer.android.com/training/monitoring-device-state/connectivity-status-type> [Accessed 21 September 2026].
 * - JetBrains, 2024. StateFlow and SharedFlow - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.utils

import android.content.Context
import android.net.ConnectivityManager               // (Google Developers, 2024)
import android.net.Network                           // (Google Developers, 2024)
import android.net.NetworkCapabilities               // (Google Developers, 2024)
import android.net.NetworkRequest                    // (Google Developers, 2024)
import com.example.prog7314_poepart23.data.local.OfflineEcgDatabase
import com.example.prog7314_poepart23.sync.SyncScheduler
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow      // (JetBrains, 2024)
import kotlinx.coroutines.flow.StateFlow             // (JetBrains, 2024)
import kotlinx.coroutines.flow.asStateFlow            // (JetBrains, 2024)

object NetworkMonitor { // (Google Developers, 2024)

    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private var appContext: Context? = null
    private var isInitialised = false

    fun initialise(context: Context) {
        if (isInitialised) return
        isInitialised = true
        appContext = context.applicationContext

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return

        // Check initial connectivity status
        val currentConnected = checkInitialConnection(connectivityManager)
        _isOnline.value = currentConnected

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val wasOffline = !_isOnline.value
                _isOnline.value = true

                if (wasOffline) {
                    appContext?.let { ctx ->
                        // Automatically trigger offline data synchronization if pending records exist
                        val patientId = FirebaseAuth.getInstance().currentUser?.uid
                        val pendingCount = if (patientId != null) {
                            OfflineEcgDatabase.getInstance(ctx).getPendingSyncCount(patientId)
                        } else {
                            0
                        }
                        if (pendingCount > 0) {
                            SyncScheduler.triggerImmediateSync(ctx)
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                _isOnline.value = checkInitialConnection(connectivityManager)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                _isOnline.value = hasInternet
            }
        })
    }

    private fun checkInitialConnection(cm: ConnectivityManager): Boolean {
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun isCurrentlyOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return checkInitialConnection(cm)
    }
}
