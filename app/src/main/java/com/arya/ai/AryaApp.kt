package com.arya.ai

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.arya.ai.util.PreferencesManager
import com.arya.ai.worker.CurrentInfoScheduler

class AryaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = PreferencesManager(this)
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.darkTheme) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        // Online chat (Groq/Gemini/OpenRouter via Arya Relay) is always available now —
        // no per-user API key needed — so the periodic current-info sync can just always
        // be scheduled. enqueueUniquePeriodicWork is safe to call every launch, it won't
        // duplicate the existing schedule.
        CurrentInfoScheduler.schedulePeriodicSync(this)

        // Registers the hourly morning-briefing check job (see AryaScheduler doc for why it's
        // hourly rather than pinned to an exact minute) — safe to call every launch, WorkManager
        // dedupes by unique name.
        com.arya.ai.worker.AryaScheduler.registerDefaultJobs(this)

        // Update check — only if a repo's been configured in Settings; safe to call every
        // launch (enqueueUniquePeriodicWork dedupes, same as the sync above).
        if (prefs.updateCheckRepo.isNotBlank()) {
            com.arya.ai.worker.UpdateCheckScheduler.schedulePeriodicCheck(this)
        }
    }
}
