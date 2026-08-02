package com.arya.ai.tools

import android.content.Context
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

/**
 * News + morning briefing, ported from `tools.py`'s `get_news` and `morning_briefing`.
 * Tries NewsAPI (via Arya Relay — key lives server-side, see arya-relay/app.py's `/v1/news`)
 * first for a proper dedicated news source. Falls back to parsing Google News' public RSS
 * feed (no key, Jsoup) if the relay isn't configured or the call fails — same "keyless first"
 * fallback stance as [WebTools.webSearch]'s Tavily->DuckDuckGo pattern.
 */
object BriefingTools {

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun relayBase(): String? {
        val relayUrl = com.arya.ai.BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) return null
        return relayUrl.removeSuffix("/v1/relay").removeSuffix("/")
    }

    /** Null on any failure/no-key, so [getNews] can silently fall through to the RSS scrape. */
    private fun newsApiArticles(topic: String, maxResults: Int): String? {
        val base = relayBase() ?: return null
        val url = if (topic.isBlank())
            "$base/v1/news?mode=headlines&country=in&page_size=$maxResults"
        else
            "$base/v1/news?mode=search&query=${enc(topic)}&page_size=$maxResults"
        val json = NetTools.getJson(url, headers = mapOf("X-App-Secret" to com.arya.ai.BuildConfig.RELAY_APP_SECRET))
            ?: return null
        val articles = json.optJSONArray("articles") ?: return null
        if (articles.length() == 0) return null
        return (0 until minOf(articles.length(), maxResults)).joinToString("\n") { i ->
            "• ${articles.getJSONObject(i).optString("title")}"
        }
    }

    fun getNews(topic: String = "", maxResults: Int = 5): String {
        val header = if (topic.isBlank()) "📰 Top headlines:" else "📰 News on '$topic':"
        newsApiArticles(topic, maxResults)?.let { return "$header\n$it" }
        return getNewsRss(topic, maxResults, header)
    }

    private fun getNewsRss(topic: String, maxResults: Int, header: String): String {
        val url = if (topic.isBlank()) "https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en"
        else "https://news.google.com/rss/search?q=${enc(topic)}&hl=en-IN&gl=IN&ceid=IN:en"
        val xml = NetTools.getText(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Android) AryaApp"))
        if (xml.isBlank()) return "❌ News fetch nahi ho paayi (network issue)"
        return try {
            val doc = Jsoup.parse(xml)
            val items = doc.select("item").take(maxResults)
            if (items.isEmpty()) return "❌ Koi news nahi mili"
            header + "\n" + items.joinToString("\n") { "• ${it.selectFirst("title")?.text().orEmpty()}" }
        } catch (e: Exception) {
            "❌ News parse nahi ho paayi"
        }
    }

    /**
     * Composes weather + top headlines + a quote + current time into one briefing string.
     * `city` should come from a saved preference (see [MorningBriefingWorker]); falls back to
     * IP-geolocation if the user hasn't set one, same fallback order as the original.
     */
    fun morningBriefing(context: Context, city: String?): String {
        val resolvedCity = city?.takeIf { it.isNotBlank() } ?: run {
            val ipInfo = InfoApiTools.getIpInfo("")
            Regex("→ ([^,]+),").find(ipInfo)?.groupValues?.getOrNull(1)?.trim() ?: "Delhi"
        }
        val now = SimpleDateFormat("EEEE, d MMMM, h:mm a", Locale.getDefault()).format(Date())
        val weather = InfoApiTools.getWeather(resolvedCity)
        val news = getNews(maxResults = 3)
        val quote = UtilityTools.getRandomQuote()
        return buildString {
            append("☀️ Good morning! Aaj hai $now\n\n")
            append("$weather\n\n")
            append("$news\n\n")
            append(quote)
        }
    }
}
