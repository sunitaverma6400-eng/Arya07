package com.arya.ai.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Saved sites + page-change watching, ported from `tools.py`'s `save_site`/`list_saved_sites`/
 * `play_saved_site`/`watch_page`/`stop_watch`/`list_page_watches`/`get_page_media`.
 * "Playing" a saved site here means opening it in the browser via an [Intent] — Arya has no
 * embedded WebView screen, so there's nothing to render a page in-app; same "open the system
 * UI, don't try to reinvent it" stance the README documents for `make_call`/`send_sms`.
 * Page-watch periodic checks run via [PageWatchWorker] on WorkManager (min interval enforced
 * by Android at 15 minutes — the original's finer-grained polling isn't possible in the
 * background on stock Android without a foreground service).
 */
object SiteTools {

    private fun prefs(context: Context) = context.getSharedPreferences("arya_sites", Context.MODE_PRIVATE)

    private fun readSites(context: Context): JSONObject = JSONObject(prefs(context).getString("sites", "{}") ?: "{}")
    private fun writeSites(context: Context, obj: JSONObject) = prefs(context).edit().putString("sites", obj.toString()).apply()

    private fun readWatches(context: Context): JSONObject = JSONObject(prefs(context).getString("watches", "{}") ?: "{}")
    private fun writeWatches(context: Context, obj: JSONObject) = prefs(context).edit().putString("watches", obj.toString()).apply()

    // ---- saved sites ----

    fun saveSite(context: Context, name: String, url: String): String {
        val sites = readSites(context)
        sites.put(name, if (!url.startsWith("http")) "https://$url" else url)
        writeSites(context, sites)
        return "💾 Site save ki: $name"
    }

    fun listSavedSites(context: Context): String {
        val sites = readSites(context)
        if (sites.length() == 0) return "🌐 Koi saved site nahi hai"
        return "🌐 Saved sites:\n" + sites.keys().asSequence().joinToString("\n") { "• $it → ${sites.getString(it)}" }
    }

    fun deleteSavedSite(context: Context, name: String): String {
        val sites = readSites(context)
        if (!sites.has(name)) return "❌ '$name' saved sites me nahi hai"
        sites.remove(name)
        writeSites(context, sites)
        return "🗑️ '$name' site delete kar di"
    }

    fun playSavedSite(context: Context, name: String): String {
        val sites = readSites(context)
        val url = sites.optString(name, null) ?: return "❌ '$name' saved sites me nahi hai"
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "🌐 Browser me khol diya: $name"
        } catch (e: Exception) {
            "❌ Site open nahi ho paayi: ${e.message}"
        }
    }

    // ---- page media extraction (mirrors `get_page_media`) ----

    fun getPageMedia(url: String): String {
        val fixedUrl = if (!url.startsWith("http")) "https://$url" else url
        val html = NetTools.getText(fixedUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Android) AryaApp"))
        if (html.isBlank()) return "❌ $fixedUrl load nahi ho paaya"
        return try {
            val doc = Jsoup.parse(html)
            val images = doc.select("img[src]").mapNotNull { it.attr("abs:src").takeIf(String::isNotBlank) }.distinct().take(10)
            val videos = doc.select("video source[src], video[src]").mapNotNull { it.attr("abs:src").takeIf(String::isNotBlank) }.distinct().take(10)
            buildString {
                append("🖼️ Images (${images.size}):\n").append(images.joinToString("\n") { "• $it" })
                append("\n\n🎬 Videos (${videos.size}):\n").append(videos.joinToString("\n") { "• $it" })
            }
        } catch (e: Exception) {
            "❌ Page media parse nahi ho paaya"
        }
    }

    // ---- page watching (WorkManager periodic diff-check) ----

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun watchPage(context: Context, name: String, url: String): String {
        val fixedUrl = if (!url.startsWith("http")) "https://$url" else url
        val html = NetTools.getText(fixedUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Android) AryaApp"))
        val hash = if (html.isNotBlank()) sha256(html) else ""

        val watches = readWatches(context)
        watches.put(name, JSONObject().apply { put("url", fixedUrl); put("hash", hash) })
        writeWatches(context, watches)

        val request = PeriodicWorkRequestBuilder<PageWatchWorker>(30, TimeUnit.MINUTES)
            .setInputData(workDataOf(PageWatchWorker.KEY_NAME to name))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "page_watch_$name", ExistingPeriodicWorkPolicy.UPDATE, request
        )
        return "👀 '$name' ($fixedUrl) watch pe daal diya — har ~30 min check hoga, change hone par notification aayega"
    }

    fun stopWatch(context: Context, name: String): String {
        val watches = readWatches(context)
        if (!watches.has(name)) return "❌ '$name' koi watch nahi mili"
        watches.remove(name)
        writeWatches(context, watches)
        WorkManager.getInstance(context).cancelUniqueWork("page_watch_$name")
        return "🛑 '$name' ka watch stop kar diya"
    }

    fun listPageWatches(context: Context): String {
        val watches = readWatches(context)
        if (watches.length() == 0) return "👀 Koi page watch nahi hai"
        return "👀 Active watches:\n" + watches.keys().asSequence().joinToString("\n") { "• $it → ${watches.getJSONObject(it).optString("url")}" }
    }

    // ---- UI parsing (Phase 4 — see chat history) ----

    /** A single parsed entry from [listSavedSites]'s output, for [com.arya.ai.ui.ChatBubble]'s
     *  tappable "🌐 Kholo" buttons. */
    data class SavedSite(val name: String, val url: String)

    /** Parses [listSavedSites]'s "• name → url" lines. Returns an empty list for anything that
     *  isn't a `list_saved_sites` result (including its own "koi saved site nahi" message). */
    fun parseSavedSites(text: String): List<SavedSite> {
        if (!text.startsWith("🌐 Saved sites:")) return emptyList()
        return Regex("""• (.+?) → (\S+)""").findAll(text)
            .map { SavedSite(it.groupValues[1], it.groupValues[2]) }
            .toList()
    }
}

/**
 * Background worker for [SiteTools.watchPage] — re-fetches the page, compares its content
 * hash to the last-seen one, and fires a notification if it changed. Android enforces a
 * 15-minute floor on periodic work; [SiteTools.watchPage] schedules this every 30 minutes to
 * stay comfortably clear of that floor and battery-friendly.
 */
class PageWatchWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    companion object { const val KEY_NAME = "watch_name" }

    override fun doWork(): Result {
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()
        val prefs = applicationContext.getSharedPreferences("arya_sites", Context.MODE_PRIVATE)
        val watches = JSONObject(prefs.getString("watches", "{}") ?: "{}")
        val entry = watches.optJSONObject(name) ?: return Result.success()
        val url = entry.optString("url")
        val oldHash = entry.optString("hash")

        val html = NetTools.getText(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Android) AryaApp"))
        if (html.isBlank()) return Result.retry()
        val newHash = MessageDigest.getInstance("SHA-256").digest(html.toByteArray()).joinToString("") { "%02x".format(it) }

        if (newHash != oldHash) {
            entry.put("hash", newHash)
            watches.put(name, entry)
            prefs.edit().putString("watches", watches.toString()).apply()
            com.arya.ai.tools.DeviceExtraTools.sendNotification(applicationContext, "👀 Page badla: $name", url)
        }
        return Result.success()
    }
}
