package com.arya.ai.worker

import android.content.Context
import androidx.work.*
import com.arya.ai.tools.DeviceExtraTools
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Custom reminders, ported from the spirit of `scheduler.py`'s generic job registry —
 * unlike [DeviceExtraTools.setAlarm] (which just opens the system Clock app's "set alarm"
 * screen, a one-off wall-clock alarm the user still has to confirm), `set_reminder` runs
 * silently in-app via WorkManager and can repeat on an interval, e.g. "har 2 ghante paani
 * peene ka reminder do" — there's no Android Clock-app equivalent for that, so it needed its
 * own tool rather than reusing `set_alarm`.
 */
object ReminderTools {

    private fun prefs(context: Context) = context.getSharedPreferences("arya_reminders", Context.MODE_PRIVATE)
    private fun readReminders(context: Context): JSONObject = JSONObject(prefs(context).getString("reminders", "{}") ?: "{}")
    private fun writeReminders(context: Context, obj: JSONObject) = prefs(context).edit().putString("reminders", obj.toString()).apply()

    /**
     * @param delayMinutes minutes from now for a one-time reminder (ignored if [repeatEveryMinutes] is set)
     * @param repeatEveryMinutes if > 0, reminder repeats on this interval instead of firing once (WorkManager clamps to a 15-min floor)
     */
    fun setReminder(context: Context, name: String, message: String, delayMinutes: Long, repeatEveryMinutes: Long = 0): String {
        val data = workDataOf(ReminderWorker.KEY_MESSAGE to message, ReminderWorker.KEY_NAME to name)
        val uniqueName = "reminder_$name"

        if (repeatEveryMinutes > 0) {
            val clamped = repeatEveryMinutes.coerceAtLeast(15)
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(clamped, TimeUnit.MINUTES)
                .setInputData(data)
                .setInitialDelay(clamped, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInputData(data)
                .setInitialDelay(delayMinutes.coerceAtLeast(0), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
        }

        val reminders = readReminders(context)
        reminders.put(name, JSONObject().apply {
            put("message", message)
            put("delay_minutes", delayMinutes)
            put("repeat_every_minutes", repeatEveryMinutes)
        })
        writeReminders(context, reminders)

        return if (repeatEveryMinutes > 0) "⏰ '$name' reminder har $repeatEveryMinutes min me repeat hoga: \"$message\""
        else "⏰ '$name' reminder $delayMinutes min me aayega: \"$message\""
    }

    fun listReminders(context: Context): String {
        val reminders = readReminders(context)
        if (reminders.length() == 0) return "⏰ Koi reminder set nahi hai"
        return "⏰ Reminders:\n" + reminders.keys().asSequence().joinToString("\n") {
            val r = reminders.getJSONObject(it)
            val repeat = r.optLong("repeat_every_minutes", 0)
            "• $it: \"${r.optString("message")}\" " + if (repeat > 0) "(har $repeat min)" else "(${r.optLong("delay_minutes")} min baad)"
        }
    }

    fun cancelReminder(context: Context, name: String): String {
        val reminders = readReminders(context)
        if (!reminders.has(name)) return "❌ '$name' reminder nahi mila"
        reminders.remove(name)
        writeReminders(context, reminders)
        WorkManager.getInstance(context).cancelUniqueWork("reminder_$name")
        return "🗑️ '$name' reminder cancel kar diya"
    }
}

/** Fires the notification for a scheduled [ReminderTools] entry. */
class ReminderWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    companion object {
        const val KEY_MESSAGE = "reminder_message"
        const val KEY_NAME = "reminder_name"
    }

    override fun doWork(): Result {
        val message = inputData.getString(KEY_MESSAGE) ?: return Result.failure()
        val name = inputData.getString(KEY_NAME) ?: "Reminder"
        DeviceExtraTools.sendNotification(applicationContext, "⏰ $name", message)
        return Result.success()
    }
}
