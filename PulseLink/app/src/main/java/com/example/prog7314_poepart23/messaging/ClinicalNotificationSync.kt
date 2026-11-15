package com.example.prog7314_poepart23.messaging

import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.prog7314_poepart23.NotificationDetailsActivity
import com.example.prog7314_poepart23.NotificationsActivity
import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.data.models.NotificationItem
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiClient
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiService
import com.example.prog7314_poepart23.data.repository.NotificationRepository
import com.example.prog7314_poepart23.data.repository.UserRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ClinicalNotificationDto(
    val notificationId: String,
    val recipientUid: String,
    val type: String,
    val patientId: String,
    val sessionId: String,
    val createdAtUtc: String,
    val doctorName: String? = null,
    val patientName: String? = null,
    val prescriptionSummary: String? = null,
    val clinicalAdvice: String? = null
)

data class PushRegistrationDto(val token: String)

data class ClinicalNotificationPresentation(
    val title: String,
    val body: String,
    val iconRes: Int
)

object ClinicalNotificationSync {
    @JvmOverloads
    fun enqueue(context: Context, replace: Boolean = false) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val request = OneTimeWorkRequestBuilder<ClinicalNotificationSyncWorker>()
            .setInputData(workDataOf("uid" to uid))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork("clinical-inbox-$uid", policy, request)
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ClinicalNotificationSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "clinical-inbox-recovery",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun resolvePresentation(context: Context, item: ClinicalNotificationDto): ClinicalNotificationPresentation? =
        resolvePresentationWithStrings(
            item = item,
            reportTitle = context.getString(R.string.clinical_report_ready),
            reportGenericBody = context.getString(R.string.clinical_report_ready_body),
            reportNamedBody = { patient -> context.getString(R.string.clinical_report_ready_named_body, patient) },
            releasedTitle = context.getString(R.string.clinical_results_released_title),
            releasedGenericBody = context.getString(R.string.clinical_results_released_body),
            releasedDoctorBody = { doctor -> context.getString(R.string.clinical_results_released_doctor_body, doctor) },
            prescriptionTitle = context.getString(R.string.clinical_prescription_issued_title),
            prescriptionDoctorBody = { doctor, rx -> context.getString(R.string.clinical_prescription_issued_body, doctor, rx) },
            prescriptionGenericBody = { rx -> context.getString(R.string.clinical_prescription_issued_generic_body, rx) },
            adviceTitle = context.getString(R.string.clinical_advice_issued_title),
            adviceDoctorBody = { doctor, advice -> context.getString(R.string.clinical_advice_issued_body, doctor, advice) },
            adviceGenericBody = { advice -> context.getString(R.string.clinical_advice_issued_generic_body, advice) }
        )

    internal fun resolvePresentationWithStrings(
        item: ClinicalNotificationDto,
        reportTitle: String = "Report ready",
        reportGenericBody: String = "A completed ECG report is ready for your review.",
        reportNamedBody: (String) -> String = { "Completed ECG report for $it is ready for clinical review." },
        releasedTitle: String = "ECG assessment released",
        releasedGenericBody: String = "Your care team has released your results. Open PulseLink to view them.",
        releasedDoctorBody: (String) -> String = { "$it reviewed your ECG recording and released your clinical summary." },
        prescriptionTitle: String = "Prescription updated",
        prescriptionDoctorBody: (String, String) -> String = { doc, rx -> "$doc updated your medication plan: $rx." },
        prescriptionGenericBody: (String) -> String = { rx -> "Your doctor updated your medication plan: $rx." },
        adviceTitle: String = "Clinical follow-up guidance",
        adviceDoctorBody: (String, String) -> String = { doc, advice -> "$doc shared follow-up guidance: $advice" },
        adviceGenericBody: (String) -> String = { advice -> "Follow-up guidance from your care team: $advice" }
    ): ClinicalNotificationPresentation? {
        val doctor = item.doctorName?.trim()?.takeIf { it.isNotEmpty() }
        val patient = item.patientName?.trim()?.takeIf { it.isNotEmpty() }
        val rx = item.prescriptionSummary?.trim()?.takeIf { it.isNotEmpty() }
        val advice = item.clinicalAdvice?.trim()?.takeIf { it.isNotEmpty() }
        return when (item.type) {
            "REPORT_READY" -> ClinicalNotificationPresentation(
                title = reportTitle,
                body = if (patient != null) reportNamedBody(patient) else reportGenericBody,
                iconRes = R.drawable.ic_document
            )
            "RESULTS_RELEASED" -> ClinicalNotificationPresentation(
                title = releasedTitle,
                body = if (doctor != null) releasedDoctorBody(doctor) else releasedGenericBody,
                iconRes = R.drawable.ic_check_circle
            )
            "PRESCRIPTION_ISSUED" -> {
                val summary = rx ?: return null
                ClinicalNotificationPresentation(
                    title = prescriptionTitle,
                    body = if (doctor != null) prescriptionDoctorBody(doctor, summary) else prescriptionGenericBody(summary),
                    iconRes = R.drawable.ic_medical_badge
                )
            }
            "CLINICAL_ADVICE" -> {
                val guidance = advice ?: return null
                ClinicalNotificationPresentation(
                    title = adviceTitle,
                    body = if (doctor != null) adviceDoctorBody(doctor, guidance) else adviceGenericBody(guidance),
                    iconRes = R.drawable.ic_stethoscope
                )
            }
            else -> null
        }
    }

    internal fun ingestFetchedItems(
        uid: String,
        items: List<ClinicalNotificationDto>,
        resolveText: (ClinicalNotificationDto) -> ClinicalNotificationPresentation?,
        currentUidProvider: () -> String?,
        onNewUnreadNotification: (ClinicalNotificationDto, ClinicalNotificationPresentation) -> Unit
    ) {
        for (item in items.sortedBy { it.createdAtUtc }) {
            if (currentUidProvider() != uid) return
            val presentation = resolveText(item) ?: continue
            val label = runCatching {
                OffsetDateTime.parse(item.createdAtUtc)
                    .atZoneSameInstant(ZoneId.of("Africa/Johannesburg"))
                    .format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.getDefault()))
            }.getOrDefault(item.createdAtUtc)
            val createdMillis = runCatching {
                OffsetDateTime.parse(item.createdAtUtc).toInstant().toEpochMilli()
            }.getOrDefault(System.currentTimeMillis())
            val existing = NotificationRepository.getNotificationById(item.notificationId)
            NotificationRepository.addNotification(
                NotificationItem(
                    notificationId = item.notificationId,
                    title = presentation.title,
                    messagePreview = presentation.body,
                    timestampLabel = label,
                    isUnread = true,
                    iconRes = presentation.iconRes,
                    sessionId = item.sessionId,
                    sessionStatus = "Completed"
                ),
                ownerUid = uid,
                createdAtMillis = createdMillis
            )
            // SQLite retains read/deleted flags, including when an event is retried after a restart.
            val saved = NotificationRepository.getNotificationById(item.notificationId)
            if (existing == null && saved?.isUnread == true && currentUidProvider() == uid) {
                onNewUnreadNotification(item, presentation)
            }
        }
    }

    suspend fun syncInboxNow(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val user = UserRepository()
        val uid = user.currentUser?.uid ?: return@withContext true
        try {
            if (user.refreshAuthToken().isNullOrBlank() || user.currentUser?.uid != uid) {
                return@withContext false
            }
            val api = PulseLinkApiClient.apiService
            // Inbox recovery must still work when Google Play Services cannot return a push token.
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                if (user.currentUser?.uid == uid && token.isNotBlank()) {
                    api.registerClinicalPushToken(PushRegistrationDto(token))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
            val items = fetchAllForRecipient(api, uid) { user.currentUser?.uid } ?: return@withContext false
            NotificationRepository.initialise(appContext, uid)
            ingestFetchedItems(
                uid = uid,
                items = items,
                resolveText = { resolvePresentation(appContext, it) },
                currentUidProvider = { user.currentUser?.uid },
                onNewUnreadNotification = { item, presentation ->
                    val intent = Intent(appContext, NotificationDetailsActivity::class.java)
                        .putExtra(NotificationsActivity.EXTRA_NOTIFICATION_ID, item.notificationId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    NotificationHelper.showNotification(
                        appContext,
                        presentation.title,
                        presentation.body,
                        targetIntent = intent,
                        notificationId = item.notificationId.hashCode()
                    )
                }
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    internal suspend fun fetchAllForRecipient(
        api: PulseLinkApiService,
        uid: String,
        currentUidProvider: () -> String?
    ): List<ClinicalNotificationDto>? {
        val items = mutableListOf<ClinicalNotificationDto>()
        var cursor: String? = null
        do {
            val response = api.getClinicalNotifications(cursor)
            if (currentUidProvider() != uid) return emptyList()
            if (!response.isSuccessful) {
                return if (response.code() in listOf(401, 403)) emptyList() else null
            }
            val page = response.body() ?: return null
            items.addAll(page.items.filter { it.recipientUid == uid })
            if (page.nextCursor != null && page.nextCursor == cursor) return null
            cursor = page.nextCursor
        } while (cursor != null)
        return items
    }
}

// FCM is a wake-up hint. The authenticated API remains the source of the recipient's inbox.
class ClinicalNotificationSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val currentUid = UserRepository().currentUser?.uid ?: return@withContext Result.success()
        if (inputData.getString("uid")?.let { it != currentUid } == true) {
            return@withContext Result.success()
        }
        if (ClinicalNotificationSync.syncInboxNow(applicationContext)) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}
