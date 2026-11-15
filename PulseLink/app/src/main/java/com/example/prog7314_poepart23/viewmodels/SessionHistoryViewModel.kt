/*
 * ViewModel managing patient ECG session history lists and high-resolution sample retrieval
 * for rhythm strip playback in PatientEcgHistoryActivity and SessionDetailsActivity.
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
import com.example.prog7314_poepart23.data.remote.models.EcgSessionSamplesResponseDto
import com.example.prog7314_poepart23.data.remote.models.EcgSessionSummaryDto
import com.example.prog7314_poepart23.data.repository.EcgRepository
import com.example.prog7314_poepart23.utils.Resource
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch                 // (JetBrains, 2024)

class SessionHistoryViewModel( // (Google Developers, 2024)
    private val ecgRepository: EcgRepository = EcgRepository()
) : ViewModel() {

    private val _sessionHistory = MutableLiveData<Resource<List<EcgSessionSummaryDto>>>() // (Google Developers, 2024)
    val sessionHistory: LiveData<Resource<List<EcgSessionSummaryDto>>> = _sessionHistory   // (Google Developers, 2024)
    private var historyJob: Job? = null

    private val _sessionSamples = MutableLiveData<Resource<EcgSessionSamplesResponseDto>>() // (Google Developers, 2024)
    val sessionSamples: LiveData<Resource<EcgSessionSamplesResponseDto>> = _sessionSamples   // (Google Developers, 2024)
    private var samplesJob: Job? = null

    // Loads all historical ECG recording sessions for a patient, cancelling any slower in-flight request.
    fun loadPatientHistory(patientId: String) {
        historyJob?.cancel()
        _sessionHistory.value = Resource.Loading()
        historyJob = viewModelScope.launch {
            val result = ecgRepository.getPatientSessions(patientId)
            _sessionHistory.value = result
        }
    }

    // Loads the full 250 Hz sample array for a specific recording session, cancelling any slower in-flight request.
    fun loadSessionSamples(sessionId: String) {
        samplesJob?.cancel()
        _sessionSamples.value = Resource.Loading()
        samplesJob = viewModelScope.launch {
            val result = ecgRepository.getSessionSamples(sessionId)
            _sessionSamples.value = result
        }
    }

    fun clearState() {
        historyJob?.cancel()
        samplesJob?.cancel()
    }
}
