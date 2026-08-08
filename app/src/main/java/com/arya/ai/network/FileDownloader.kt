package com.arya.ai.network

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal generic downloader — pulls any direct-download URL into a local file with
 * progress callbacks. Used by [com.arya.ai.util.UpdateInstaller] to download update APKs
 * from GitHub Releases.
 */
class FileDownloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // some files can be large; no read timeout
        .followRedirects(true)
        .build()

    /**
     * Downloads [url] into [destination], invoking [onProgress] with (bytesRead, totalBytes)
     * as the download proceeds. Throws IOException on failure. Safe to call from a
     * background coroutine/thread only — this blocks the calling thread.
     */
    @Throws(IOException::class)
    fun download(url: String, destination: File, onProgress: (Long, Long) -> Unit) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed: HTTP ${response.code} for $url")
            }
            val body = response.body ?: throw IOException("Empty response body for $url")
            val total = body.contentLength()
            var readBytes = 0L

            destination.parentFile?.mkdirs()
            val tmpFile = File(destination.parentFile, "${destination.name}.part")

            body.byteStream().use { input ->
                tmpFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        readBytes += read
                        onProgress(readBytes, total)
                    }
                    output.flush()
                }
            }
            if (!tmpFile.renameTo(destination)) {
                tmpFile.copyTo(destination, overwrite = true)
                tmpFile.delete()
            }
        }
    }
}
