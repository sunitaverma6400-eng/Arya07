package com.arya.ai.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.arya.ai.tools.CuriosityStore
import com.arya.ai.tools.DeviceExtraTools
import com.arya.ai.tools.PersonalityStore

/**
 * Phase 5 of the "advanced tools" upgrade (see chat history) — Arya's "idle time" reflection
 * pass. Scheduled every 6 hours by [AryaScheduler.registerDefaultJobs], same periodic-check
 * pattern [MorningBriefingWorker] already uses. Each run:
 *
 *  1. Asks [CuriosityStore] whether any tool has hit the same fixable gap (missing relay,
 *     missing permission, missing key) often enough to be worth mentioning.
 *  2. If so, turns that gap into one specific, plain-language question and sends it as a
 *     notification — same channel [PersonalityStore.runSurpriseCheckIn]'s check-ins use — then
 *     marks it asked so it won't repeat the same question every 6 hours.
 *  3. Does nothing otherwise. No gap logged, no notification — this is not a "let's talk
 *     anyway" chime, only ever a specific, actionable one.
 *
 * Kept as honest bookkeeping, not a claim of genuine autonomous thought: everything this
 * worker "notices" is exactly what [com.arya.ai.tools.AryaToolRegistry.logCapabilityGap] wrote
 * down earlier from real tool failures — there's no separate reasoning happening in the
 * background, just a scheduled check of that log.
 */
class ReflectionWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        val gap = CuriosityStore.nextUnaskedGap(applicationContext) ?: return Result.success()
        val tool = gap.optString("tool")
        val reason = gap.optString("reason")
        val count = gap.optInt("count")

        val question = when (reason) {
            "relay_not_configured" ->
                "Maine dekha '$tool' $count baar try hua lekin Arya Relay configured nahi hai " +
                    "(RELAY_URL khaali hai). Ye feature chalane ke liye relay set karna chahoge?"
            "permission_missing" ->
                "'$tool' ko $count baar chalane ki koshish ki lekin permission nahi mili. " +
                    "Settings me se allow kar doge, taaki ye feature use kar sakein?"
            "missing_config" ->
                "'$tool' $count baar fail hua kisi missing API key/config ki wajah se. " +
                    "Ise set karke dekhna chahoge?"
            else -> return Result.success()
        }

        DeviceExtraTools.sendNotification(applicationContext, "🧐 Arya ne kuch notice kiya", question)
        PersonalityStore.logReflection(applicationContext, question)
        CuriosityStore.markAsked(applicationContext, tool, reason)
        return Result.success()
    }
}
