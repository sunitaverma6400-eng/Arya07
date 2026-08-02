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

    /** GET a URL and return the raw response body as text (empty string on failure). */
    fun getText(url: String, headers: Map<String, String> = emptyMap()): String = try {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() ?: "" else ""
        }
    } catch (e: Exception) {
        ""
    }

    /** GET a URL and parse the body as a JSON object. Returns null on any failure. */
    fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject? {
        val text = getText(url, headers)
        if (text.isBlank()) return null
        return try { JSONObject(text) } catch (e: Exception) { null }
    }
}
