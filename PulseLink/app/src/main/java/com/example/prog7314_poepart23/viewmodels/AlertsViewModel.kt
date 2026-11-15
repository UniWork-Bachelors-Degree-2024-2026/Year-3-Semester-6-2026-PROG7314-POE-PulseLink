/*
 * ViewModel managing clinical physiological threshold warnings and hardware detachment alerts
 * for DoctorAlertsActivity and Patient notifications.
 *
 * Reference List:
 * - Google Developers, 2024. ViewModel Overview - Android Developers. [online] Available at: <https://developer.android.com/topic/libraries/architecture/viewmodel> [Accessed 21 September 2026].
 * - JetBrains, 2024. Coroutines guide - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.viewmodels

import androidx.lifecycle.LiveData               // (Google Developers, 2024)
import androidx.lifecycle.MutableLiveData        // (Google Developers, 2024)
import androidx.lifecycle.ViewModel              // (Google Developers, 2024)
import androidx.lifecycle.viewModelScope         // (Google Developers, 2024)
import com.example.prog7314_poepart23.data.remote.models.AlertDto
import com.example.prog7314_poepart23.data.repository.EcgRepository
import com.example.prog7314_poepart23.utils.Resource
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch                 // (JetBrains, 2024)

class AlertsViewModel( // (Google Developers, 2024)
    private val ecgRepository: EcgRepository = EcgRepository()
) : ViewModel() {

    private val _alerts = MutableLiveData<Resource<List<AlertDto>>>() // (Google Developers, 2024)
    val alerts: LiveData<Resource<List<AlertDto>>> = _alerts         // (Google Developers, 2024)
    private var alertsJob: Job? = null

    // Fetches all active clinical alerts from the API, cancelling any slower in-flight request.
    fun loadActiveAlerts() {
        alertsJob?.cancel()
        _alerts.value = Resource.Loading()
        alertsJob = viewModelScope.launch {
            val result = ecgRepository.getActiveAlerts()
            _alerts.value = result
        }
    }

    fun cancelActiveAlertsLoad() {
        alertsJob?.cancel()
    }
}
