package com.arya.ai.util

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Checks a GitHub repo's Releases for a newer version than what's installed, so friends who
 * were given the APK once don't need it re-sent every time — they just tap "Download &
 * Install" inside the app itself when a banner shows up.
 *
 * Requires the repo to actually publish GitHub **Releases** (not just Actions build
 * artifacts, which are temporary and not fetchable by this API) with the APK attached as a
 * release asset — see FIXES_LOG.md for the one-time CI change needed.
 */
object UpdateChecker {

    data class ReleaseInfo(
        val versionName: String,
        val downloadUrl: String,
        val releaseNotes: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * @param repo "owner/repo" (e.g. "sunitaverma6400-eng/Jarvis") — set in Settings, not
     * hardcoded, since guessing this wrong would silently make update checks do nothing.
     * @return the latest release's info if it's newer than the installed version, else null
     * (covers "already up to date", "no releases published yet", and "check failed" alike —
     * callers don't need to distinguish those for a background check).
     */
    fun checkForUpdate(context: Context, repo: String): ReleaseInfo? {
        if (repo.isBlank() || !repo.contains("/")) return null
        return try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val tag = json.optString("tag_name").removePrefix("v").removePrefix("V")
                if (tag.isBlank()) return null

                val assets = json.optJSONArray("assets") ?: return null
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                if (apkUrl.isNullOrBlank()) return null

                val currentVersion = context.packageManager
                    .getPackageInfo(context.packageName, 0).versionName ?: "0"

                if (!isNewer(tag, currentVersion)) return null

                ReleaseInfo(
                    versionName = tag,
                    downloadUrl = apkUrl,
                    releaseNotes = json.optString("body").take(500)
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Simple dotted-numeric version comparison ("1.10.0" > "1.9.0", unlike a plain string
     *  compare which would get that backwards). Falls back to != if either side has any
     *  non-numeric segment (e.g. a "-beta" suffix) — treats that as "different, so newer". */
    private fun isNewer(candidate: String, current: String): Boolean {
        val c = candidate.split(".").map { it.toIntOrNull() }
        val cur = current.split(".").map { it.toIntOrNull() }
        if (c.any { it == null } || cur.any { it == null }) return candidate != current
        val len = maxOf(c.size, cur.size)
        for (i in 0 until len) {
            val a = c.getOrElse(i) { 0 } ?: 0
            val b = cur.getOrElse(i) { 0 } ?: 0
            if (a != b) return a > b
        }
        return false
    }
}
