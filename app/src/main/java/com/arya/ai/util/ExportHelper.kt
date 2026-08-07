package com.arya.ai.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.arya.ai.data.ChatMessageEntity
import java.io.File

object ExportHelper {

    fun exportAsText(context: Context, sessionTitle: String, messages: List<ChatMessageEntity>): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "${sessionTitle.replace(" ", "_")}_${System.currentTimeMillis()}.txt")

        file.writeText(buildString {
            appendLine("Arya Chat Export — $sessionTitle")
            appendLine("=".repeat(40))
            messages.forEach { m ->
                appendLine(if (m.isFromUser) "You:" else "Arya:")
                appendLine(m.text)
                appendLine()
            }
        })
        return file
    }

    /** Same content as [exportAsText] but as Markdown — bold speaker labels, a horizontal
     *  rule between turns, so it renders nicely if opened in any Markdown viewer/Obsidian/etc. */
    fun exportAsMarkdown(context: Context, sessionTitle: String, messages: List<ChatMessageEntity>): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "${sessionTitle.replace(" ", "_")}_${System.currentTimeMillis()}.md")

        file.writeText(buildString {
            appendLine("# Arya Chat Export — $sessionTitle")
            appendLine()
            messages.forEach { m ->
                appendLine(if (m.isFromUser) "**You:**" else "**Arya:**")
                appendLine()
                appendLine(m.text)
                appendLine()
                appendLine("---")
                appendLine()
            }
        })
        return file
    }

    fun shareFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mimeType = if (file.extension == "md") "text/markdown" else "text/plain"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Chat share karo"))
    }
}
