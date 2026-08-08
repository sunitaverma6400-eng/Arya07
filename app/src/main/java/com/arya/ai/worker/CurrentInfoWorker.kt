package com.arya.ai.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.arya.ai.util.OnlineChatHelper
import com.arya.ai.util.PreferencesManager
import com.arya.ai.util.SimpleRagHelper

/**
 * Runs in the background (via WorkManager). Asks the relay-backed online model for a
 * short, factual current-affairs snapshot and stores it locally. This gets folded into
 * every reply's system prompt (see [com.arya.ai.util.SimpleRagHelper.getCurrentInfoRaw]) —
 * so Arya's replies stay grounded in roughly current information between full online
 * lookups, refreshed periodically whenever the phone has internet.
 */
class CurrentInfoWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = PreferencesManager(applicationContext)

        return try {
            val prompt = "Aaj ki date hai: ${com.arya.ai.util.DateTimeContext.currentDateTimeLine()} " +
                "Is date ko dhyaan me rakhte hue, is hafte ki 5-6 major world aur India " +
                "current-affairs headlines, aur kuch prominent current position-holders " +
                "(jaise major countries ke current heads of state/government, agar pata ho) " +
                "concise bullet points me do. Sirf factual jaankari — koi commentary nahi. " +
                "Ye ek AI assistant ke liye reference snapshot ban raha hai."

            val result = OnlineChatHelper.generateOnlineResponse(
                prefs = prefs,
                prompt = prompt,
                systemPrompt = "Tum ek concise current-affairs summarizer ho. Sirf bullet points me factual jaankari do."
            )

            SimpleRagHelper(applicationContext).refreshCurrentInfo(result.text)
            prefs.lastCurrentInfoSync = System.currentTimeMillis()

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
