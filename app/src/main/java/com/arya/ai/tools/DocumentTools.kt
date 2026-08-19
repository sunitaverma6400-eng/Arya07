package com.arya.ai.tools

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File

/**
 * On-device document creation — the write-side counterpart to [com.arya.ai.util.SimpleRagHelper]
 * (which only reads/indexes PDFs). Uses `pdfbox-android`, already a dependency for RAG, so no
 * new library needed. Deliberately simple (plain wrapped text, one font, no images/tables) —
 * the goal is Arya being able to hand back a note/report/summary as an actual file the user
 * can open or share, not a full word-processor.
 */
object DocumentTools {

    private const val PAGE_WIDTH = 595f  // A4 at 72dpi
    private const val PAGE_HEIGHT = 842f
    private const val MARGIN = 50f
    private const val FONT_SIZE = 11f
    private const val LEADING = 16f

    private fun docsDir(context: Context): File =
        File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS), "Arya").apply { mkdirs() }

    private fun safeName(name: String): String =
        name.trim().ifBlank { "document" }.replace(Regex("[^A-Za-z0-9_\\- ]"), "_").take(60)

    /** Plain-text `.txt` file — the reliable fallback if [createPdf] ever fails, and the
     *  faster path when the user just wants a saved note, not a formatted document. */
    fun createTextFile(context: Context, name: String, content: String): File? = try {
        val file = File(docsDir(context), "${safeName(name)}.txt")
        file.writeText(content)
        file
    } catch (e: Exception) {
        null
    }

    /**
     * Wraps [content] into a simple multi-page A4 PDF with [title] as a header. Word-wraps by
     * measuring actual string width against the font (not just a fixed char count) so lines
     * don't overflow the page margin regardless of content language/character mix.
     */
    fun createPdf(context: Context, name: String, title: String, content: String): File? {
        return try {
            PDFBoxResourceLoader.init(context.applicationContext)
            val doc = PDDocument()
            val font = PDType1Font.HELVETICA
            val titleFont = PDType1Font.HELVETICA_BOLD
            val maxWidth = PAGE_WIDTH - 2 * MARGIN

            var page = PDPage()
            doc.addPage(page)
            var stream = PDPageContentStream(doc, page)
            var y = PAGE_HEIGHT - MARGIN

            fun newPageIfNeeded(linesNeeded: Int = 1) {
                if (y - linesNeeded * LEADING < MARGIN) {
                    stream.close()
                    page = PDPage()
                    doc.addPage(page)
                    stream = PDPageContentStream(doc, page)
                    y = PAGE_HEIGHT - MARGIN
                }
            }

            if (title.isNotBlank()) {
                stream.beginText()
                stream.setFont(titleFont, 16f)
                stream.newLineAtOffset(MARGIN, y)
                stream.showText(sanitizeForPdf(title))
                stream.endText()
                y -= LEADING * 2
            }

            for (paragraph in content.split("\n")) {
                val words = paragraph.split(" ")
                var line = StringBuilder()
                if (words.isEmpty() || paragraph.isBlank()) {
                    newPageIfNeeded()
                    y -= LEADING
                    continue
                }
                for (word in words) {
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    val width = font.getStringWidth(sanitizeForPdf(candidate)) / 1000 * FONT_SIZE
                    if (width > maxWidth && line.isNotEmpty()) {
                        newPageIfNeeded()
                        stream.beginText()
                        stream.setFont(font, FONT_SIZE)
                        stream.newLineAtOffset(MARGIN, y)
                        stream.showText(sanitizeForPdf(line.toString()))
                        stream.endText()
                        y -= LEADING
                        line = StringBuilder(word)
                    } else {
                        line = StringBuilder(candidate)
                    }
                }
                if (line.isNotEmpty()) {
                    newPageIfNeeded()
                    stream.beginText()
                    stream.setFont(font, FONT_SIZE)
                    stream.newLineAtOffset(MARGIN, y)
                    stream.showText(sanitizeForPdf(line.toString()))
                    stream.endText()
                    y -= LEADING
                }
            }
            stream.close()

            val file = File(docsDir(context), "${safeName(name)}.pdf")
            doc.save(file)
            doc.close()
            file
        } catch (e: Exception) {
            null
        }
    }

    /** PDType1Font's built-in encoding (WinAnsi/StandardEncoding) can't render Devanagari or
     *  most non-Latin scripts — Hinglish (Latin-script Hindi) works fine, but stripping any
     *  character outside that range avoids a hard crash on save for the rare paragraph that
     *  slips a Devanagari word in, at the cost of that word being dropped from the PDF only
     *  (the .txt fallback via [createTextFile] has no such limitation). */
    private fun sanitizeForPdf(text: String): String =
        text.filter { it.code < 256 }
}
