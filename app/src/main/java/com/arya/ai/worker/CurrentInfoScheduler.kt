package com.arya.ai.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object CurrentInfoScheduler {

    private const val PERIODIC_WORK_NAME = "arya_current_info_sync"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Call once an API key is saved — keeps Arya's current-affairs snapshot fresh every 12 hours. */
    fun schedulePeriodicSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<CurrentInfoWorker>(12, TimeUnit.HOURS)
            .setConstraints(networkConstraint)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** Triggers an immediate one-off sync (e.g. right after the first key is saved, or a manual "Sync now" tap). */
    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<CurrentInfoWorker>()
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }
}
