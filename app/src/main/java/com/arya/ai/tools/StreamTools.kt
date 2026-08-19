package com.arya.ai.tools

import android.content.Context
import com.arya.ai.player.StreamPlayerManager
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Streaming subsystem — ported from the original assistant's `hls_stream_pipeline.py` /
 * `hls_quality.py` / `hls_player.py` + the streaming-related functions in `tools.py`
 * (`play_stream`, `search_radio`, `search_youtube`, `find_and_play`, saved streams, quality
 * control). Playback itself is delegated to [StreamPlayerManager] (ExoPlayer).
 *
 * Honest scope limits (same "say it plainly" stance as the rest of Arya's tool docs):
 *  - `search_radio` plays directly — Radio Browser gives a direct stream URL, ExoPlayer can
 *    play it as-is.
 *  - `search_youtube`/`search_videos` can only *search* and hand back links — actually
 *    resolving a YouTube watch page to a raw playable stream URL is what the original
 *    project used `yt-dlp` for, which needs a Python runtime; there's no Android/Kotlin
 *    equivalent bundled here. `find_and_play` therefore only auto-plays radio results; for
 *    a YouTube link it returns the link for [DeviceExtraTools.openApp]-style opening instead.
 *  - stream "quality" here is just a stored user preference tag surfaced back in tool
 *    replies — ExoPlayer already does adaptive bitrate switching on its own for HLS, so
 *    there's no manual bitrate-forcing knob like the original's ffmpeg pipeline had.
 */
object StreamTools {

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun prefs(context: Context) = context.getSharedPreferences("arya_streams", Context.MODE_PRIVATE)

    // ---- search ----

    fun searchRadio(query: String, maxResults: Int = 5): String {
        val json = NetTools.getText("https://de1.api.radio-browser.info/json/stations/search?name=${enc(query)}&limit=$maxResults&hidebroken=true")
        if (json.isBlank()) return "❌ Radio search fail hui (network issue)"
        return try {
            val arr = JSONArray(json)
            if (arr.length() == 0) return "❌ '$query' ke liye koi radio station nahi mili"
            "📻 Radio stations for '$query':\n" + (0 until arr.length()).joinToString("\n") { i ->
                val s = arr.getJSONObject(i)
                "• ${s.optString("name")} (${s.optString("countrycode")}) → ${s.optString("url_resolved")}"
            }
        } catch (e: Exception) {
            "❌ Radio results parse nahi ho paaye"
        }
    }

    /** YouTube's /results page doesn't render plain <a href="youtube.com/watch..."> anchor
     *  tags server-side — results live inside a `var ytInitialData = {...}` JSON blob embedded
     *  in a <script> tag, and the few anchors that DO exist use relative hrefs ("/watch?v=ID"),
     *  never the full "youtube.com/watch" substring. So instead of anchor-tag scraping (which
     *  always returned zero results), pull video IDs straight out of that embedded JSON via
     *  regex — the standard, reliable way to scrape this specific page. Shared by both
     *  [searchYoutube] (list results) and [playVideo] (auto-play the first one).
     *
     *  Bug fix (see chat history — user reported videos "not coming" at all): YouTube's search
     *  page occasionally changes markup or bot-blocks a mobile-network IP, which made this
     *  return zero results with no real fallback. Now falls back to a DuckDuckGo HTML search
     *  scoped to youtube.com — a completely different scraping surface, so a YouTube-side
     *  hiccup doesn't mean a flat failure anymore. */
    private fun youtubeVideoIds(query: String, maxResults: Int): Result<List<String>> {
        val html = NetTools.getText(
            "https://www.youtube.com/results?search_query=${enc(query)}",
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Android) AryaApp")
        )
        if (html.isNotBlank() && !looksBotBlocked(html)) {
            val ids = Regex("\"videoId\":\"([a-zA-Z0-9_-]{11})\"").findAll(html)
                .map { it.groupValues[1] }
                .distinct()
                .take(maxResults)
                .toList()
            if (ids.isNotEmpty()) return Result.success(ids)
        }

        val fallbackIds = youtubeIdsViaDuckDuckGo(query, maxResults)
        if (fallbackIds.isNotEmpty()) return Result.success(fallbackIds)

        return Result.failure(Exception("❌ Video search fail hui — YouTube aur backup search dono se result nahi mila"))
    }

    /** Backup video-ID source for [youtubeVideoIds] — DuckDuckGo's HTML results wrap each link
     *  in a redirect URL (`/l/?uddg=<url-encoded target>`), so this decodes each href before
     *  looking for a youtube.com/watch pattern inside it. */
    private fun youtubeIdsViaDuckDuckGo(query: String, maxResults: Int): List<String> {
        val html = NetTools.getText(
            "https://html.duckduckgo.com/html/?q=${enc(query)}+site:youtube.com",
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Android) AryaApp")
        )
        if (html.isBlank()) return emptyList()
        return try {
            Jsoup.parse(html).select("a[href]").asSequence()
                .mapNotNull { el ->
                    val href = el.attr("href")
                    val decoded = try {
                        java.net.URLDecoder.decode(href, "UTF-8")
                    } catch (e: Exception) {
                        href
                    }
                    Regex("""youtube\.com/watch\?v=([a-zA-Z0-9_-]{11})""").find(decoded)?.groupValues?.get(1)
                }
                .distinct()
                .take(maxResults)
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun searchYoutube(query: String, maxResults: Int = 5): String {
        val result = youtubeVideoIds(query, maxResults)
        val ids = result.getOrElse { return it.message ?: "❌ Video search fail hui" }
        if (ids.isEmpty()) return "❌ '$query' ke liye koi video result nahi mila"
        return "🎬 Videos for '$query' (\"Video dekho\" button se in-app play hoga):\n" +
            ids.joinToString("\n") { "• https://www.youtube.com/watch?v=$it" }
    }

    /** Searches YouTube and hands back the first result marked for immediate in-app playback —
     *  [com.arya.ai.tools.AryaToolRegistry] pulls the "AUTOPLAY:<url>" line out of this into its
     *  `lastAutoPlayVideo` side-channel (same pattern as `lastNowPlaying`), so
     *  [com.arya.ai.ui.ChatScreen] opens [com.arya.ai.ui.VideoPlayerDialog] right away instead
     *  of requiring a "Video dekho" tap. */
    fun playVideo(query: String): String {
        val result = youtubeVideoIds(query, 1)
        val ids = result.getOrElse { return it.message ?: "❌ Video search fail hui" }
        val id = ids.firstOrNull() ?: return "❌ '$query' ke liye koi video nahi mila"
        val url = "https://www.youtube.com/watch?v=$id"
        return "▶️ '$query' abhi play ho raha hai\nAUTOPLAY:$url"
    }

    fun searchVideos(query: String, maxResults: Int = 5): String = searchVideosGeneric(
        "https://html.duckduckgo.com/html/?q=${enc(query)}+video", query, maxResults, null
    )

    private fun searchVideosGeneric(url: String, query: String, maxResults: Int, mustContain: String?): String {
        val html = NetTools.getText(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Android) AryaApp"))
        if (html.isBlank()) return "❌ Video search fail hui (network issue)"
        if (looksBotBlocked(html)) return "❌ Search page ne bot-block/CAPTCHA dikhaya — try again later ya browser me search karo"
        return try {
            val doc = Jsoup.parse(html)
            val links = doc.select("a[href]")
                .mapNotNull { it.attr("href").takeIf { h -> mustContain == null || h.contains(mustContain) } }
                .distinct()
                .take(maxResults)
            if (links.isEmpty()) return "❌ '$query' ke liye koi video result nahi mila"
            "🎬 Videos for '$query' (\"Video dekho\" button se in-app play hoga):\n" + links.joinToString("\n") { "• $it" }
        } catch (e: Exception) {
            "❌ Video results parse nahi ho paaye"
        }
    }

    /** Heuristic port of the original's `looks_bot_blocked` — catches common CAPTCHA/block pages. */
    fun looksBotBlocked(html: String): Boolean {
        val lower = html.lowercase()
        return listOf("captcha", "verify you are human", "unusual traffic", "access denied", "detected unusual activity")
            .any { lower.contains(it) }
    }

    // ---- playback control ----

    // ---- UI parsing (Phase 2 — see chat history) ----

    /** A single parsed line from [searchRadio]'s result text, for [ChatBubble]'s tappable
     *  station list (so the person can tap-to-play instead of retyping the station name). */
    data class ParsedStation(val name: String, val url: String)

    /** Parses [searchRadio]'s "• Name (CC) → url" lines back out of its own result text.
     *  Returns an empty list for anything that isn't a `search_radio` result (including
     *  its own "no results"/error strings), so callers can safely call this on any tool
     *  reply without an extra type check first. */
    fun parseStationList(text: String): List<ParsedStation> {
        if (!text.startsWith("📻 Radio stations for")) return emptyList()
        return Regex("""• (.+?) \([^)]*\) → (\S+)""").findAll(text)
            .map { ParsedStation(it.groupValues[1], it.groupValues[2]) }
            .toList()
    }

    fun playStream(context: Context, url: String, label: String = url): String = StreamPlayerManager.play(context, url, label)
    fun pauseStream(): String = StreamPlayerManager.pause()
    fun resumeStream(): String = StreamPlayerManager.resume()
    fun stopStream(): String = StreamPlayerManager.stop()
    fun stopAllStreams(): String = StreamPlayerManager.stop()
    fun streamStatus(): String = StreamPlayerManager.status()

    /** Radio only auto-plays (see class doc); for anything else it just returns search results. */
    fun findAndPlay(context: Context, query: String): String {
        val radioResult = searchRadio(query, maxResults = 1)
        val firstUrl = Regex("→ (\\S+)").find(radioResult)?.groupValues?.get(1)
        if (firstUrl != null) return playStream(context, firstUrl, query)
        // No matching radio station — most "X chalao/lagao" requests (a song, an artist) mean
        // a YouTube video, not a radio station, so fall back to that instead of just failing.
        return playVideo(query)
    }

    /** Reachability check for video/audio/HLS URLs — content-type prefixes are much more varied
     *  here than images (video-, audio-prefixed types, HLS playlists often serve as an
     *  application- prefixed type too), so this checks against that wider set instead of
     *  reusing [ImageTools.testImageSource] (which only accepts image-prefixed types and would
     *  always report a valid stream URL as invalid). */
    fun testVideoSource(url: String): String {
        val (code, type) = NetTools.probeReachable(url)
        if (code == -1) return "❌ Source test fail hui"
        val lower = type.lowercase()
        val looksLikeMedia = lower.startsWith("video/") || lower.startsWith("audio/") ||
            lower.contains("mpegurl") || lower.contains("octet-stream") || lower.contains("dash+xml")
        return if (code in 200..299 && looksLikeMedia) "✅ Video/stream source valid hai ($type, HTTP $code)"
        else if (code in 200..299) "✅ URL reachable hai ($type, HTTP $code) — par content-type se pakka video/audio confirm nahi ho paaya"
        else "❌ Ye valid video/stream source nahi lag raha (HTTP $code, type $type)"
    }

    // ---- saved streams (SharedPreferences JSON — not sensitive, plain prefs is fine) ----

    private fun readSaved(context: Context): JSONObject = JSONObject(prefs(context).getString("saved", "{}") ?: "{}")
    private fun writeSaved(context: Context, obj: JSONObject) = prefs(context).edit().putString("saved", obj.toString()).apply()

    fun saveStream(context: Context, name: String, url: String): String {
        val saved = readSaved(context)
        saved.put(name, url)
        writeSaved(context, saved)
        return "💾 Stream save ki: $name"
    }

    fun listSavedStreams(context: Context): String {
        val saved = readSaved(context)
        if (saved.length() == 0) return "📻 Koi saved stream nahi hai"
        return "📻 Saved streams:\n" + saved.keys().asSequence().joinToString("\n") { "• $it → ${saved.getString(it)}" }
    }

    fun deleteSavedStream(context: Context, name: String): String {
        val saved = readSaved(context)
        if (!saved.has(name)) return "❌ '$name' saved streams me nahi hai"
        saved.remove(name)
        writeSaved(context, saved)
        return "🗑️ '$name' stream delete kar di"
    }

    fun playSavedStream(context: Context, name: String): String {
        val saved = readSaved(context)
        val url = saved.optString(name, null) ?: return "❌ '$name' saved streams me nahi hai"
        return playStream(context, url, name)
    }

    // ---- quality preference (stored tag; see class doc for why it's advisory-only) ----

    fun setDefaultStreamQuality(context: Context, quality: String): String {
        prefs(context).edit().putString("default_quality", quality).apply()
        return "🎚️ Default quality set: $quality (ExoPlayer HLS adaptive bitrate khud bhi manage karta hai)"
    }

    fun getDefaultStreamQuality(context: Context): String =
        "🎚️ Default quality: ${prefs(context).getString("default_quality", "auto")}"

    fun listStreamQualities(): String = "🎚️ Available: auto, low, medium, high (advisory tag only — ExoPlayer auto-adapts for HLS)"
}
