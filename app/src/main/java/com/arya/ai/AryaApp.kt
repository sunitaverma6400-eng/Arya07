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

        // Warm the OpenRouter free-model catalog (see util/ModelCatalog.kt) — Arya's own
        // live discovery of which OpenRouter models are currently free and what each is good
        // for, instead of only trusting the hand-typed data/OnlineModels.kt list. The sync
        // call just reads the on-disk cache (cheap, mirrors it into process memory so
        // OnlineChatHelper's Context-less peek() has something from process start); the
        // background thread then does a real relay round-trip only if that cache is
        // empty/stale (see ModelCatalog's CACHE_TTL_MS), so this never blocks app startup.
        com.arya.ai.util.ModelCatalog.cached(this)
        Thread {
            try {
                com.arya.ai.util.ModelCatalog.getFreeOpenRouterModelsBlocking(this)
            } catch (_: Exception) {
                // Best-effort warmup only — OnlineChatHelper already falls back to the
                // static OnlineModels.OPENROUTER list if the catalog is empty, so a failed
                // warmup here just means that fallback stays in effect a bit longer.
            }
        }.start()
    }
}
