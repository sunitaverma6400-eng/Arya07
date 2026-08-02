package com.arya.ai.tools

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Image search + generation, ported from the original assistant's `tools.py`
 * (`search_images`, `generate_image`, `fetch_image_from_url`, `test_image_source`).
 *
 * The original had ~10 provider backends (Bing/Brave/Serper/SerpApi/etc), most of which
 * needed paid API keys. On Arya, only the keyless ones are kept:
 *  - search: Openverse (openly-licensed images, no key) — same "free/no-key first" stance
 *    as the rest of [InfoApiTools].
 *  - generate: Pollinations.ai's free, keyless text-to-image endpoint — mirrors the
 *    original's Pollinations fallback tier (the HF FLUX.1-schnell tier needed a paid/rate
 *    -limited HF token, so it's skipped here, same "free first" reasoning as everywhere else).
 */
object ImageTools {

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun searchImages(query: String, maxResults: Int = 5): String {
        val json = NetTools.getJson("https://api.openverse.org/v1/images/?q=${enc(query)}&page_size=$maxResults")
            ?: return "❌ Image search fail hui (network issue)"
        val results = json.optJSONArray("results") ?: JSONArray()
        if (results.length() == 0) return "❌ '$query' ke liye koi image nahi mili"
        return "🖼️ Images for '$query':\n" + (0 until results.length()).joinToString("\n") { i ->
            val item = results.getJSONObject(i)
            val title = item.optString("title", "untitled")
            val url = item.optString("url", "")
            "• $title\n  $url"
        }
    }

    /** Pollinations.ai builds the image straight from the URL — nothing to POST, no key needed. */
    fun generateImage(prompt: String): String {
        val url = "https://image.pollinations.ai/prompt/${enc(prompt)}?width=768&height=768&nologo=true"
        return "🎨 Image generate ho gayi: $url"
    }

    /** Downloads an image URL into the app's cache dir and returns the local file path. */
    fun fetchImageFromUrl(context: Context, url: String): String {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                requestMethod = "GET"
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            // Validate it's actually decodable image data before saving.
            if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) {
                return "❌ URL se valid image nahi mili"
            }
            val cacheDir = File(context.cacheDir, "fetched_images").apply { mkdirs() }
            val file = File(cacheDir, "${UUID.randomUUID()}.jpg")
            file.writeBytes(bytes)
            "🖼️ Image save ho gayi: ${file.absolutePath}"
        } catch (e: Exception) {
            "❌ Image fetch nahi ho paayi: ${e.message}"
        }
    }

    /** HEAD-style reachability + content-type check, same idea as `test_image_source` in tools.py. */
    fun testImageSource(url: String): String {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-1024")
            }
            val code = conn.responseCode
            val type = conn.contentType ?: "unknown"
            conn.disconnect()
            if (code in 200..299 && type.startsWith("image/")) "✅ Image source valid hai ($type, HTTP $code)"
            else "❌ Ye valid image source nahi lag raha (HTTP $code, type $type)"
        } catch (e: Exception) {
            "❌ Source test fail hui: ${e.message}"
        }
    }
}
