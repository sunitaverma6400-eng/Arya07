package com.arya.ai.worker

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * General-purpose background-job registry, ported from the original assistant's
 * `scheduler.py` (APScheduler-based). Android has no long-running background process for a
 * regular app, so this wraps [androidx.work.WorkManager] periodic work instead — same idea
 * (named recurring jobs, listable, cancellable), different underlying engine. Android also
 * enforces a 15-minute floor on periodic intervals; anything shorter gets clamped up to that.
 */
object AryaScheduler {

    /** Registers (or updates) a named periodic job. [workerClass] must be a no-arg [ListenableWorker]. */
    fun <T : ListenableWorker> addJob(
        context: Context,
        jobName: String,
        workerClass: Class<T>,
        intervalMinutes: Long,
        inputData: Data = Data.EMPTY,
        requiresNetwork: Boolean = true
    ) {
        val clamped = intervalMinutes.coerceAtLeast(15)
        val request = PeriodicWorkRequest.Builder(workerClass, clamped, TimeUnit.MINUTES)
            .setInputData(inputData)
            .apply {
                if (requiresNetwork) setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            }
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(jobName, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelJob(context: Context, jobName: String) {
        WorkManager.getInstance(context).cancelUniqueWork(jobName)
    }

    /** Registers Arya's built-in default jobs — mirrors `scheduler.py`'s `register_default_jobs`. */
    fun registerDefaultJobs(context: Context, morningBriefingHour: Int = 8) {
        // WorkManager can't target a specific wall-clock hour directly for periodic work;
        // MorningBriefingWorker checks the current hour itself and no-ops outside the window,
        // while this runs hourly so it's checked close to on-time.
        addJob(context, "morning_briefing_check", MorningBriefingWorker::class.java, 60,
            inputData = workDataOf(MorningBriefingWorker.KEY_TARGET_HOUR to morningBriefingHour))

        // Phase 5 (see chat history) — Arya's idle-time reflection pass. Every 6 hours is
        // frequent enough to catch a real gap without becoming a battery/notification nuisance;
        // ReflectionWorker itself is a no-op most runs (only fires when something's actually
        // worth asking about).
        addJob(context, "arya_reflection", ReflectionWorker::class.java, 360)
    }

    fun listJobs(context: Context): List<String> = listOf("morning_briefing_check", "arya_reflection") // WorkManager has no direct "list all unique names" API
}
