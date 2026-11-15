package com.example.prog7314_poepart23.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.prog7314_poepart23.data.models.CarePlan
import com.example.prog7314_poepart23.data.remote.models.AccessPageDto
import com.example.prog7314_poepart23.data.remote.models.ClinicalVisitDto
import com.example.prog7314_poepart23.data.remote.models.CreateClinicalVisitDto
import com.example.prog7314_poepart23.data.remote.models.CreateDoctorNoteDto
import com.example.prog7314_poepart23.data.remote.models.DoctorDashboardDto
import com.example.prog7314_poepart23.data.remote.models.DoctorNoteDto
import com.example.prog7314_poepart23.data.remote.models.DoctorPatientSummaryDto
import com.example.prog7314_poepart23.data.remote.models.DoctorSessionReviewDto
import com.example.prog7314_poepart23.data.remote.models.PatientChartDto
import com.example.prog7314_poepart23.data.remote.models.PatientReleaseDto
import com.example.prog7314_poepart23.data.remote.models.ReportDocumentPayload
import com.example.prog7314_poepart23.data.repository.DoctorChartRepository
import com.example.prog7314_poepart23.data.repository.PatientPortalRepository
import com.example.prog7314_poepart23.data.repository.PatientPortalResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * ViewModel exposing authoritative doctor review, clinical chart, visit recording,
 * session release, and PDF report states for the doctor screens.
 *
 * Each asynchronous action cancels its previous in-flight Job so a slower earlier
 * response can never overwrite a newer empty or error state.
 */
class DoctorReviewViewModel(
    private val chartRepository: DoctorChartRepository = DoctorChartRepository(),
    private val reportRepository: PatientPortalRepository = PatientPortalRepository()
) : ViewModel() {

    private val _dashboard = MutableLiveData<PatientPortalResult<DoctorDashboardDto>>()
    val dashboard: LiveData<PatientPortalResult<DoctorDashboardDto>> = _dashboard
    private var dashboardJob: Job? = null

    private val _assignedPatients = MutableLiveData<PatientPortalResult<AccessPageDto<DoctorPatientSummaryDto>>>()
    val assignedPatients: LiveData<PatientPortalResult<AccessPageDto<DoctorPatientSummaryDto>>> = _assignedPatients
    private var assignedPatientsJob: Job? = null

    private val _patientChart = MutableLiveData<PatientPortalResult<PatientChartDto>>()
    val patientChart: LiveData<PatientPortalResult<PatientChartDto>> = _patientChart
    private var patientChartJob: Job? = null

    private val _patientVisits = MutableLiveData<PatientPortalResult<List<ClinicalVisitDto>>>()
    val patientVisits: LiveData<PatientPortalResult<List<ClinicalVisitDto>>> = _patientVisits
    private var patientVisitsJob: Job? = null

    private val _sessionReview = MutableLiveData<PatientPortalResult<DoctorSessionReviewDto>>()
    val sessionReview: LiveData<PatientPortalResult<DoctorSessionReviewDto>> = _sessionReview
    private var sessionReviewJob: Job? = null

    private val _savedNote = MutableLiveData<PatientPortalResult<DoctorNoteDto>>()
    val savedNote: LiveData<PatientPortalResult<DoctorNoteDto>> = _savedNote
    private var saveNoteJob: Job? = null

    private val _savedVisit = MutableLiveData<PatientPortalResult<ClinicalVisitDto>>()
    val savedVisit: LiveData<PatientPortalResult<ClinicalVisitDto>> = _savedVisit
    private var saveVisitJob: Job? = null

    private val _savedCarePlan = MutableLiveData<PatientPortalResult<CarePlan>>()
    val savedCarePlan: LiveData<PatientPortalResult<CarePlan>> = _savedCarePlan
    private var saveCarePlanJob: Job? = null

    private val _releaseResult = MutableLiveData<PatientPortalResult<PatientReleaseDto>>()
    val releaseResult: LiveData<PatientPortalResult<PatientReleaseDto>> = _releaseResult
    private var releaseJob: Job? = null

    private val _reportPdf = MutableLiveData<PatientPortalResult<ReportDocumentPayload>>()
    val reportPdf: LiveData<PatientPortalResult<ReportDocumentPayload>> = _reportPdf
    private var reportPdfJob: Job? = null

    fun loadDashboard() {
        dashboardJob?.cancel()
        dashboardJob = viewModelScope.launch {
            _dashboard.value = chartRepository.dashboard()
        }
    }

    fun loadAssignedPatients(after: String? = null, limit: Int = 50) {
        assignedPatientsJob?.cancel()
        assignedPatientsJob = viewModelScope.launch {
            _assignedPatients.value = chartRepository.assignedPatients(after, limit)
        }
    }

    fun loadPatientChart(patientId: String) {
        patientChartJob?.cancel()
        patientChartJob = viewModelScope.launch {
            _patientChart.value = chartRepository.patientChart(patientId)
        }
    }

    fun loadPatientVisits(patientId: String) {
        patientVisitsJob?.cancel()
        patientVisitsJob = viewModelScope.launch {
            _patientVisits.value = chartRepository.patientVisits(patientId)
        }
    }

    fun loadSessionReview(sessionId: String) {
        sessionReviewJob?.cancel()
        sessionReviewJob = viewModelScope.launch {
            _sessionReview.value = chartRepository.sessionReview(sessionId)
        }
    }

    fun saveAssessmentNote(request: CreateDoctorNoteDto, refreshSessionReviewOnSuccess: Boolean = true) {
        saveNoteJob?.cancel()
        saveNoteJob = viewModelScope.launch {
            val result = chartRepository.saveAssessmentNote(request)
            _savedNote.value = result
            if (refreshSessionReviewOnSuccess && result is PatientPortalResult.Ready) {
                _sessionReview.value = chartRepository.sessionReview(request.sessionId)
            }
        }
    }

    fun recordVisit(patientId: String, request: CreateClinicalVisitDto, refreshChartOnSuccess: Boolean = true) {
        saveVisitJob?.cancel()
        saveVisitJob = viewModelScope.launch {
            val result = chartRepository.createVisit(patientId, request)
            _savedVisit.value = result
            if (refreshChartOnSuccess && result is PatientPortalResult.Ready) {
                _patientChart.value = chartRepository.patientChart(patientId)
            }
        }
    }

    fun saveCarePlan(patientId: String, plan: CarePlan, refreshChartOnSuccess: Boolean = true) {
        saveCarePlanJob?.cancel()
        saveCarePlanJob = viewModelScope.launch {
            val result = chartRepository.saveCarePlan(patientId, plan)
            _savedCarePlan.value = result
            if (refreshChartOnSuccess && result is PatientPortalResult.Ready) {
                _patientChart.value = chartRepository.patientChart(patientId)
            }
        }
    }

    fun releaseSessionResults(sessionId: String, refreshSessionReviewOnSuccess: Boolean = true) {
        releaseJob?.cancel()
        releaseJob = viewModelScope.launch {
            val result = chartRepository.releaseSession(sessionId)
            _releaseResult.value = result
            if (refreshSessionReviewOnSuccess && result is PatientPortalResult.Ready) {
                _sessionReview.value = chartRepository.sessionReview(sessionId)
            }
        }
    }

    fun downloadAuthoritativeReportPdf(sessionId: String, forceRegenerate: Boolean = false) {
        reportPdfJob?.cancel()
        reportPdfJob = viewModelScope.launch {
            _reportPdf.value = reportRepository.downloadStaffReportPdf(sessionId, forceRegenerate)
        }
    }

    fun cancelAll() {
        dashboardJob?.cancel()
        assignedPatientsJob?.cancel()
        patientChartJob?.cancel()
        patientVisitsJob?.cancel()
        sessionReviewJob?.cancel()
        saveNoteJob?.cancel()
        saveVisitJob?.cancel()
        saveCarePlanJob?.cancel()
        releaseJob?.cancel()
        reportPdfJob?.cancel()
    }
}
