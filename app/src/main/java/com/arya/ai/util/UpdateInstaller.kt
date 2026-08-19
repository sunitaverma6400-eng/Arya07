package com.arya.ai.util

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.arya.ai.network.FileDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads an update APK (from a GitHub release asset URL — see [UpdateChecker]) into the
 * app's cache dir, then hands it to Android's package installer via [FileProvider] + a
 * content:// URI (a plain file:// URI is blocked by FileProvider's StrictMode on API 24+).
 */
object UpdateInstaller {

    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        versionName: String,
        onProgress: (percent: Int) -> Unit
    ) {
        val destination = File(context.cacheDir, "updates").apply { mkdirs() }
            .resolve("arya-update-$versionName.apk")

        withContext(Dispatchers.IO) {
            FileDownloader().download(downloadUrl, destination) { bytesRead, total ->
                if (total > 0) onProgress(((bytesRead * 100) / total).toInt())
            }
        }

        withContext(Dispatchers.Main) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destination)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(installIntent)
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Install prompt nahi khul saka: ${e.message}. APK yahan hai: ${destination.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
