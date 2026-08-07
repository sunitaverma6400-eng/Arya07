package com.arya.ai.tools

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Tiny shared HTTP client for all Arya's device/data tools (weather, news, wiki, nasa, etc).
 * Every call here is a plain synchronous OkHttp request — callers are expected to run
 * these from a coroutine on Dispatchers.IO (see [AryaToolRegistry.execute]).
 */
object NetTools {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * GET a URL and return the raw response body as text (empty string on failure).
     *
     * Phase 2 reliability pass (see chat history): retries once after a short backoff on
     * transient failures (timeout, DNS blip, non-2xx) before giving up — mobile data drops a
     * request here and there, and a single retry fixes most of those without meaningfully
     * slowing down the common "it worked first time" case. Deliberately capped at one retry:
     * this runs synchronously on the tool-call thread, so more than one retry starts making a
     * failing call (e.g. a genuinely dead station URL) block the chat for too long.
     */
    fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        repeat(2) { attempt ->
            val result = try {
                val builder = Request.Builder().url(url)
                headers.forEach { (k, v) -> builder.addHeader(k, v) }
                client.newCall(builder.build()).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() ?: "" else ""
                }
            } catch (e: Exception) {
                ""
            }
            if (result.isNotBlank()) return result
            if (attempt == 0) Thread.sleep(600)
        }
        return ""
    }

    /** GET a URL and parse the body as a JSON object. Returns null on any failure. */
    fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject? {
        val text = getText(url, headers)
        if (text.isBlank()) return null
        return try { JSONObject(text) } catch (e: Exception) { null }
    }

    /**
     * Range-GET reachability probe shared by [ImageTools.testImageSource] and
     * [StreamTools.testVideoSource] — returns the HTTP status code and content-type (or
     * null/"unknown" on failure) so each caller can apply its own type-prefix check instead
     * of duplicating the connection-handling code.
     */
    fun probeReachable(url: String): Pair<Int, String> {
        return try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-1024")
            }
            val code = conn.responseCode
            val type = conn.contentType ?: "unknown"
            conn.disconnect()
            code to type
        } catch (e: Exception) {
            -1 to "unknown"
        }
    }
}
