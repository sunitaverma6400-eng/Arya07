package com.arya.ai.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.arya.ai.tools.BriefingTools
import com.arya.ai.tools.DeviceExtraTools
import java.text.SimpleDateFormat
import java.util.*

/**
 * Runs hourly (scheduled by [AryaScheduler.registerDefaultJobs]) and fires the morning
 * briefing exactly once per day, at whichever hour the check happens to land closest to
 * [KEY_TARGET_HOUR]. WorkManager periodic work can't be pinned to an exact wall-clock time,
 * so this trades precision for simplicity — same trade-off the original `scheduler.py`
 * avoided only because a always-on server process could sleep-until-exact-time; a phone app
 * that isn't always running can't guarantee that.
 */
class MorningBriefingWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    companion object {
        const val KEY_TARGET_HOUR = "target_hour"
        private const val PREFS = "arya_briefing"
    }

    override fun doWork(): Result {
        val targetHour = inputData.getInt(KEY_TARGET_HOUR, 8)
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        if (currentHour != targetHour) return Result.success()

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (prefs.getString("last_sent_date", "") == today) return Result.success() // already sent today

        val city = prefs.getString("city", null)
        val briefing = BriefingTools.morningBriefing(applicationContext, city)
        DeviceExtraTools.sendNotification(applicationContext, "☀️ Aaj ki briefing", briefing.take(200))
        prefs.edit().putString("last_sent_date", today).apply()
        return Result.success()
    }
}
