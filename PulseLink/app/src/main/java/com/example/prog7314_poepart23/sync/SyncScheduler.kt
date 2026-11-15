/*
 * Utility managing scheduled background and on-demand synchronisation
 * between local SQLite offline cache and the PulseLink Azure REST API.
 *
 * Reference List:
 * - Google Developers, 2024. Schedule tasks with WorkManager. [online] Available at: <https://developer.android.com/topic/libraries/architecture/workmanager> [Accessed 21 September 2026].
 * - Oracle, 2024. Class TimeUnit (Java Platform SE 8). [online] Available at: <https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/TimeUnit.html> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.sync

import android.content.Context
import androidx.lifecycle.LiveData               // (Google Developers, 2024)
import androidx.work.Constraints                  // (Google Developers, 2024)
import androidx.work.ExistingPeriodicWorkPolicy   // (Google Developers, 2024)
import androidx.work.ExistingWorkPolicy           // (Google Developers, 2024)
import androidx.work.NetworkType                  // (Google Developers, 2024)
import androidx.work.OneTimeWorkRequestBuilder    // (Google Developers, 2024)
import androidx.work.PeriodicWorkRequestBuilder   // (Google Developers, 2024)
import androidx.work.WorkInfo                     // (Google Developers, 2024)
import androidx.work.WorkManager                  // (Google Developers, 2024)
import java.util.concurrent.TimeUnit              // (Oracle, 2024)

object SyncScheduler { // (Google Developers, 2024)

    private const val UNIQUE_ONE_TIME_SYNC = "PulseLinkImmediateOfflineSync"
    private const val UNIQUE_PERIODIC_SYNC = "PulseLinkPeriodicOfflineSync"

    // Triggers an immediate one-time sync task when network connectivity is available.
    fun triggerImmediateSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                UNIQUE_ONE_TIME_SYNC,
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )
    }

    // Schedules recurring periodic background synchronization (every 15 minutes).
    fun schedulePeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<OfflineSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_SYNC,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
    }

    // Exposes LiveData tracking the current state of immediate offline sync execution.
    fun getImmediateSyncWorkInfo(context: Context): LiveData<List<WorkInfo>> {
        return WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWorkLiveData(UNIQUE_ONE_TIME_SYNC)
    }
}
