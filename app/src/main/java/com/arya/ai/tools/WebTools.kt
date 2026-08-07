package com.arya.ai.tools

import android.content.Context
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Web search + page scraping, ported from the original assistant's `web_search` / `scrape_webpage`.
 * Tries Tavily (via Arya Relay — key lives server-side, see arya-relay/app.py) first, since it
 * returns clean structured results instead of scraped HTML. Falls back to scraping DuckDuckGo's
 * HTML endpoint directly with Jsoup (no key needed at all) if Tavily isn't configured or fails —
 * so web_search always works even before/without a relay setup.
 */
object WebTools {

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun webSearch(context: Context, query: String, maxResults: Int = 5): String {
        tavilySearch(context, query, maxResults)?.let { return it }
        return webSearchDuckDuckGo(query, maxResults)
    }

    /** Null (not an error string) on any failure, so [webSearch] can silently fall through to DuckDuckGo. */
    private fun tavilySearch(context: Context, query: String, maxResults: Int): String? {
        val relayUrl = com.arya.ai.BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) return null
        val base = relayUrl.removeSuffix("/v1/relay").removeSuffix("/")
        return try {
            val body = org.json.JSONObject().apply {
                put("query", query)
                put("max_results", maxResults)
            }
            val connection = (java.net.URL("$base/v1/tavily").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-App-Secret", com.arya.ai.BuildConfig.RELAY_APP_SECRET)
                connectTimeout = 20_000
                readTimeout = 20_000
                doOutput = true
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode !in 200..299) return null

            val json = org.json.JSONObject(connection.inputStream.bufferedReader().readText())
            val results = json.optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            val answer = json.optString("answer").takeIf { it.isNotBlank() }
            val lines = (0 until results.length()).map { i ->
                val r = results.getJSONObject(i)
                "🔎 ${r.optString("title")}\n${r.optString("content").take(300)}\n${r.optString("url")}"
            }
            (answer?.let { "💡 $it\n\n" } ?: "") + lines.joinToString("\n\n")
        } catch (e: Exception) {
            null
        }
    }

    private fun webSearchDuckDuckGo(query: String, maxResults: Int): String {
        val html = NetTools.getText(
            "https://html.duckduckgo.com/html/?q=${enc(query)}",
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Android) AryaApp")
        )
        if (html.isBlank()) return "❌ Web search fail hui (network issue)"
        return try {
            val doc = Jsoup.parse(html)
            val results = doc.select("div.result").take(maxResults)
            if (results.isEmpty()) return "❌ '$query' ke liye koi result nahi mila"
            results.joinToString("\n\n") { el ->
                val title = el.selectFirst("a.result__a")?.text().orEmpty()
                val snippet = el.selectFirst(".result__snippet")?.text().orEmpty()
                val link = el.selectFirst("a.result__a")?.attr("href").orEmpty()
                "🔎 $title\n$snippet\n$link"
            }
        } catch (e: Exception) {
            "❌ Search results parse nahi ho paaye"
        }
    }

    fun scrapeWebpage(url: String, extractMode: String = "text"): String {
        val fixedUrl = if (!url.startsWith("http")) "https://$url" else url
        val html = NetTools.getText(fixedUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Android) AryaApp"))
        if (html.isBlank()) return "❌ $fixedUrl load nahi ho paaya"
        return try {
            val doc = Jsoup.parse(html)
            when (extractMode) {
                "links" -> doc.select("a[href]").take(20).joinToString("\n") { "${it.text()} → ${it.attr("href")}" }
                "title" -> doc.title()
                else -> doc.body().text().take(2000)
            }
        } catch (e: Exception) {
            "❌ Page parse nahi ho paaya"
        }
    }

    /** Combines a Wikipedia lookup with a web search — mirrors the original assistant's `smart_search` heuristic. */
    fun smartSearch(context: Context, query: String): String {
        val wiki = InfoApiTools.getWikipediaSummary(query)
        if (!wiki.startsWith("❌")) return wiki
        return webSearch(context, query, maxResults = 3)
    }

    // ---- UI parsing (Phase 4 — see chat history) ----

    /** A single parsed result from [webSearch]'s output, for [com.arya.ai.ui.ChatBubble]'s
     *  tappable "🔗 Kholo" buttons. Both the Tavily and DuckDuckGo paths already produce
     *  "🔎 title\n...\nurl" blocks separated by blank lines, so one parser covers either source. */
    data class SearchResultLink(val title: String, val url: String)

    /** Splits [webSearch]'s output into its "🔎 ..." blocks and pulls the title (first line)
     *  and URL (the line that looks like a link) out of each. Returns an empty list for
     *  anything that isn't a search result (including error strings and Tavily's optional
     *  leading "💡 answer" block, which has no URL of its own). */
    fun parseSearchResults(text: String): List<SearchResultLink> {
        if (!text.contains("🔎")) return emptyList()
        return text.split("\n\n").mapNotNull { block ->
            val lines = block.lines()
            val titleLine = lines.firstOrNull { it.startsWith("🔎 ") } ?: return@mapNotNull null
            val urlLine = lines.lastOrNull { it.startsWith("http") } ?: return@mapNotNull null
            SearchResultLink(titleLine.removePrefix("🔎 ").trim(), urlLine.trim())
        }
    }
}
