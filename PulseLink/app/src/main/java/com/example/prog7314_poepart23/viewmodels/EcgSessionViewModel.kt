package com.example.prog7314_poepart23.viewmodels

import android.content.Context
import android.os.CountDownTimer
import android.os.SystemClock
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.data.local.OfflineEcgDatabase
import com.example.prog7314_poepart23.data.local.models.CachedSession
import com.example.prog7314_poepart23.data.remote.models.EcgSessionSummaryDto
import com.example.prog7314_poepart23.data.repository.EcgRepository
import com.example.prog7314_poepart23.utils.LocaleHelper
import com.example.prog7314_poepart23.utils.RollingBpmAverage
import com.example.prog7314_poepart23.utils.RecordingSessionClock
import com.example.prog7314_poepart23.utils.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class EcgSessionViewModel(private val ecgRepository: EcgRepository = EcgRepository()) : ViewModel() {
    companion object {
        const val SESSION_DURATION_MS = 90_000L
        const val SAMPLE_POLL_INTERVAL_MS = 500L
    }

    var currentSessionId: String? = null
        private set
    var isOfflineMode = false
        private set
    private val clock = RecordingSessionClock(SystemClock::elapsedRealtime)
    private val bpmWindow = RollingBpmAverage(SystemClock::elapsedRealtime)
    private var sessionBpmSum = 0L
    private var sessionBpmCount = 0
    private var lastHardwareBpmAt: Long? = null
    private var lastHardwareHeartbeat: String? = null
    private var countDownTimer: CountDownTimer? = null
    private var samplePollingJob: Job? = null
    private var recordingStateJob: Job? = null
    private val _controlError = MutableLiveData<String>()
    val controlError: LiveData<String> = _controlError
    private var finalizing = false
    private var sessionStartedAt = ""
    private var sessionPatientId = ""
    private var sessionDeviceId = ""
    private var sessionPatientName: String? = null

    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> = _isRecording
    private val _isPaused = MutableLiveData(false)
    val isPaused: LiveData<Boolean> = _isPaused
    private val _isReady = MutableLiveData(false)
    val isReady: LiveData<Boolean> = _isReady
    private val _formattedTime = MutableLiveData("01:30")
    val formattedTime: LiveData<String> = _formattedTime
    private val _timeExpired = MutableLiveData(false)
    val timeExpired: LiveData<Boolean> = _timeExpired
    private val _currentBpm = MutableLiveData(0)
    val currentBpm: LiveData<Int> = _currentBpm
    private val _leadConnected = MutableLiveData(true)
    val leadConnected: LiveData<Boolean> = _leadConnected
    private val _incomingSamples = MutableLiveData<List<Int>>(emptyList())
    val incomingSamples: LiveData<List<Int>> = _incomingSamples
    private val _sessionSummary = MutableLiveData<Resource<EcgSessionSummaryDto>>()
    val sessionSummary: LiveData<Resource<EcgSessionSummaryDto>> = _sessionSummary

    fun startRecording(patientId: String, deviceId: String?, patientName: String? = null, context: Context? = null) {
        if (_isRecording.value == true || finalizing) return
        if (patientId.isBlank() || deviceId.isNullOrBlank()) {
            _sessionSummary.value = Resource.Error(LocaleHelper.string(R.string.l10n_err_no_device_paired))
            return
        }
        sessionPatientId = patientId
        sessionDeviceId = deviceId
        sessionPatientName = patientName
        clock.reset()
        bpmWindow.clear()
        sessionBpmSum = 0L
        sessionBpmCount = 0
        lastHardwareBpmAt = null
        lastHardwareHeartbeat = null
        _timeExpired.value = false
        _currentBpm.value = 0
        _incomingSamples.value = emptyList()
        _isPaused.value = true
        _isReady.value = false
        _isRecording.value = true
        _formattedTime.value = "01:30"
        _sessionSummary.value = Resource.Loading()
        viewModelScope.launch {
            val appContext = context?.applicationContext
            val result = ecgRepository.startSession(patientId, deviceId, patientName)
            isOfflineMode = result !is Resource.Success || result.data == null
            sessionStartedAt = if (!isOfflineMode) result.data!!.startTimeUtc else utcNow()
            currentSessionId = if (!isOfflineMode) result.data!!.sessionId else "offline_${UUID.randomUUID()}"
            if (isOfflineMode && appContext != null) {
                val activeOwnerUid = runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid }.getOrNull() ?: patientId
                withContext(Dispatchers.IO) {
                    OfflineEcgDatabase.getInstance(appContext).insertSession(CachedSession(
                        remoteSessionId = currentSessionId!!,
                        ownerUid = activeOwnerUid,
                        patientId = patientId,
                        patientName = patientName,
                        deviceId = deviceId,
                        startTimeIso = sessionStartedAt,
                        status = "Recording"
                    ))
                }
            }
            _isReady.value = true
            if (_leadConnected.value == true) resumeRecording() else pauseRecording()
            // Continue polling lead status while paused, so reconnection can be confirmed by hardware.
            startSamplePolling(currentSessionId!!, deviceId)
        }
    }

    fun pauseRecording() {
        clock.pause()
        countDownTimer?.cancel()
        _isPaused.value = true
        syncRecordingState(paused = true)
        bpmWindow.clear()
        _currentBpm.value = 0
        updateTime()
    }

    fun resumeRecording(): Boolean {
        if (_isReady.value != true || _isRecording.value != true || _leadConnected.value != true || finalizing) return false
        syncRecordingState(paused = false) {
            if (_leadConnected.value != true || _isRecording.value != true || finalizing) return@syncRecordingState
            _isPaused.value = false
            clock.resume()
            countDownTimer?.cancel()
            countDownTimer = object : CountDownTimer(clock.remainingMillis, 1000L) {
                override fun onTick(millisUntilFinished: Long) { updateTime() }
                override fun onFinish() {
                    pauseRecording()
                    _formattedTime.value = "00:00"
                    _timeExpired.value = true
                }
            }.start()
        }
        return true
    }

    private fun syncRecordingState(paused: Boolean, onSuccess: () -> Unit = {}) {
        val id = currentSessionId ?: return
        val previous = recordingStateJob
        recordingStateJob = viewModelScope.launch {
            previous?.join()
            if (currentSessionId != id) return@launch
            val result = if (isOfflineMode) Resource.Success(Unit) else ecgRepository.setRecordingState(id, paused)
            if (result is Resource.Success) onSuccess()
            else {
                clock.pause()
                countDownTimer?.cancel()
                _isPaused.value = true
                _controlError.value = LocaleHelper.string(R.string.live_state_sync_failed)
            }
        }
    }

    private fun updateTime() {
        _currentBpm.value = bpmWindow.average()
        val seconds = (clock.remainingMillis + 999L) / 1000L
        _formattedTime.value = String.format(Locale.UK, "%02d:%02d", seconds / 60, seconds % 60)
    }

    private fun startSamplePolling(sessionId: String, deviceId: String) {
        samplePollingJob?.cancel()
        samplePollingJob = viewModelScope.launch {
            while (isActive && _isRecording.value == true) {
                val statusResult = ecgRepository.getDeviceStatus(deviceId)
                if (statusResult is Resource.Success && statusResult.data != null && statusResult.data.isActuallyOnline()) {
                    updateLeadConnected(statusResult.data.leadConnected)
                    val status = statusResult.data
                    val fresh = status.lastHeartbeatUtc.isNotBlank() && status.lastHeartbeatUtc != lastHardwareHeartbeat
                    lastHardwareHeartbeat = status.lastHeartbeatUtc
                    if (fresh && _isPaused.value != true && status.leadConnected && status.estimatedBpm in 1..250) {
                        lastHardwareBpmAt = SystemClock.elapsedRealtime()
                        recordBpm(status.estimatedBpm)
                    }
                }
                if (!isOfflineMode) {
                    val result = ecgRepository.getSessionSamples(sessionId)
                    if (result is Resource.Success && result.data != null) _incomingSamples.value = result.data.samples
                }
                delay(SAMPLE_POLL_INTERVAL_MS)
            }
        }
    }

    suspend fun discardRecording(context: Context): Resource<Unit> {
        val id = currentSessionId ?: return Resource.Error(LocaleHelper.string(R.string.live_session_starting))
        if (finalizing) return Resource.Error(LocaleHelper.string(R.string.live_session_starting))
        pauseRecording()
        finalizing = true
        _isReady.value = false
        recordingStateJob?.join()
        val result = if (isOfflineMode) Resource.Success(Unit) else ecgRepository.discardSession(id)
        if (result is Resource.Success) {
            samplePollingJob?.cancel()
            withContext(Dispatchers.IO) { OfflineEcgDatabase.getInstance(context.applicationContext).deleteSession(id) }
            currentSessionId = null
            _incomingSamples.value = emptyList()
            _isRecording.value = false
        } else {
            _isReady.value = true
        }
        finalizing = false
        return result
    }

    fun stopRecording(context: Context? = null) {
        val id = currentSessionId ?: return
        if (finalizing || _isRecording.value != true) return
        pauseRecording()
        samplePollingJob?.cancel()
        finalizing = true
        _isReady.value = false
        _isRecording.value = false
        _sessionSummary.value = Resource.Loading()
        val duration = clock.elapsedSeconds
        val bpm = if (sessionBpmCount > 0) (sessionBpmSum / sessionBpmCount).toInt() else 0
        viewModelScope.launch {
            val endTime = utcNow()
            val appContext = context?.applicationContext
            if (isOfflineMode && appContext != null) {
                withContext(Dispatchers.IO) {
                    OfflineEcgDatabase.getInstance(appContext).updateSessionMetrics(id, bpm, duration, endTime)
                }
                com.example.prog7314_poepart23.sync.SyncScheduler.triggerImmediateSync(appContext)
            }
            recordingStateJob?.join()
            val result = if (!isOfflineMode) ecgRepository.stopSession(id, duration) else null
            _sessionSummary.value = if (result is Resource.Success) result else Resource.Success(EcgSessionSummaryDto(
                sessionId = id, patientId = sessionPatientId, patientName = sessionPatientName, deviceId = sessionDeviceId,
                averageBpm = bpm, durationSeconds = duration, startTimeUtc = sessionStartedAt, endTimeUtc = endTime,
                signalQuality = if (_leadConnected.value == true) "Unknown" else "LeadsDisconnected", status = "Completed"
            ))
            finalizing = false
        }
    }

    // Use waveform-derived readings only while no fresh hardware BPM source is available.
    fun updateEstimatedBpm(bpm: Int) {
        val lastHardware = lastHardwareBpmAt
        if (lastHardware == null || SystemClock.elapsedRealtime() - lastHardware >= 2_000L) recordBpm(bpm)
    }

    private fun recordBpm(bpm: Int) {
        if (_isRecording.value != true || _isPaused.value == true || _leadConnected.value != true) return
        if (bpmWindow.add(bpm)) {
            sessionBpmSum += bpm
            sessionBpmCount++
            _currentBpm.value = bpmWindow.average()
        }
    }

    fun updateLeadConnected(connected: Boolean) {
        // Publish the transition first so the UI can cache the final connected batch before BPM resets.
        val changed = _leadConnected.value != connected
        if (changed) _leadConnected.value = connected
        if (!connected && changed) pauseRecording()
    }

    private fun utcNow() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.UK).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    override fun onCleared() {
        countDownTimer?.cancel()
        samplePollingJob?.cancel()
        recordingStateJob?.cancel()
    }
}
