package com.arya.ai.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.arya.ai.util.PreferencesManager
import com.arya.ai.util.UpdateChecker

/**
 * Runs in the background (via WorkManager) to check [PreferencesManager.updateCheckRepo]'s
 * GitHub Releases for a newer version than what's installed. If one's found, caches it in
 * prefs — MainActivity's home screen shows a banner reading it, same pattern as the existing
 * onboarding banner. This is what lets a build shared once with friends update itself instead
 * of needing to be re-sent every time.
 */
class UpdateCheckWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = PreferencesManager(applicationContext)
        val repo = prefs.updateCheckRepo
        if (repo.isBlank()) return Result.success()

        return try {
            val release = UpdateChecker.checkForUpdate(applicationContext, repo)
            prefs.lastUpdateCheck = System.currentTimeMillis()
            if (release != null) {
                prefs.availableUpdateVersion = release.versionName
                prefs.availableUpdateUrl = release.downloadUrl
                prefs.availableUpdateNotes = release.releaseNotes
            } else {
                // No newer release — clear any stale cached update (e.g. user already
                // installed it manually, or it turned out not to be newer after all).
                prefs.availableUpdateVersion = null
                prefs.availableUpdateUrl = null
                prefs.availableUpdateNotes = null
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
