package com.arya.ai.util

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlin.math.ln

/**
 * A fully on-device retrieval helper. True neural vector-embedding RAG needs
 * an extra embedding model (not included here to keep the app lightweight —
 * and as of this pass, also because there's no network access available to
 * fetch one; see FIXES_LOG.md #11 for the honest state of this);
 * instead this uses **TF-IDF weighted term + bigram overlap**, cosine-normalized
 * by chunk length — noticeably better than plain keyword counting because rare,
 * distinctive words are weighted higher than common ones, phrase-level matches
 * (bigrams) count for more than two unrelated words happening to both appear,
 * and long chunks no longer win purely by containing more words. Still needs
 * no extra ML model or network call.
 */
class SimpleRagHelper(private val context: Context) {

    companion object {
        private const val CURRENT_INFO_FILENAME = "current_info.txt"
        /** Paragraphs longer than this get split further so one giant chunk can't dominate
         *  retrieval just by containing more of the document's vocabulary. */
        private const val MAX_CHUNK_CHARS = 600
    }

    private val docsDir = File(context.filesDir, "documents").apply { mkdirs() }
    private val chunks = mutableListOf<String>()
    private var docFrequency: Map<String, Int> = emptyMap()
    private var bigramDocFrequency: Map<String, Int> = emptyMap()

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
        reloadAllFromDisk()
    }

    fun importTextDocument(name: String, content: String) {
        File(docsDir, "$name.txt").writeText(content)
        reloadAllFromDisk()
    }

    /** Extracts text from a PDF (via SAF Uri) and indexes it the same way as a text doc. */
    fun importPdfDocument(name: String, uri: Uri) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc ->
                val text = PDFTextStripper().getText(doc)
                importTextDocument(name, text)
            }
        }
    }

    /**
     * Overwrites the single "current affairs" snapshot (date, recent headlines,
     * current position-holders, etc.) that gets refreshed periodically in the
     * background via [com.arya.ai.worker.CurrentInfoWorker] once the user has
     * saved at least one online API key. Unlike [importTextDocument], calling
     * this again replaces the previous snapshot instead of accumulating more
     * documents — there should only ever be one.
     */
    fun refreshCurrentInfo(summaryText: String) {
        File(docsDir, CURRENT_INFO_FILENAME).writeText(summaryText)
        reloadAllFromDisk()
    }

    /** Raw text of the latest current-affairs snapshot, or null if never synced yet. */
    fun getCurrentInfoRaw(): String? {
        val file = File(docsDir, CURRENT_INFO_FILENAME)
        return if (file.exists()) file.readText() else null
    }

    private fun reloadAllFromDisk() {
        chunks.clear()
        docsDir.listFiles()?.forEach { file ->
            if (file.extension == "txt") indexText(file.readText())
        }
        rebuildDocFrequency()
    }

    private fun indexText(content: String) {
        content.split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.length > 40 }
            .forEach { paragraph -> chunks.addAll(splitToMaxLength(paragraph)) }
    }

    /** Splits on sentence boundaries first, then packs sentences back together up to
     *  [MAX_CHUNK_CHARS] — keeps chunks reasonably sized without cutting mid-sentence. */
    private fun splitToMaxLength(paragraph: String): List<String> {
        if (paragraph.length <= MAX_CHUNK_CHARS) return listOf(paragraph)
        val sentences = paragraph.split(Regex("(?<=[.!?।])\\s+"))
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            if (current.isNotEmpty() && current.length + sentence.length > MAX_CHUNK_CHARS) {
                result.add(current.toString().trim())
                current.clear()
            }
            current.append(sentence).append(' ')
        }
        if (current.isNotEmpty()) result.add(current.toString().trim())
        return result.ifEmpty { listOf(paragraph.take(MAX_CHUNK_CHARS)) }
    }

    private fun words(text: String) =
        text.lowercase().split(Regex("\\W+")).filter { it.length > 2 }

    private fun bigrams(tokens: List<String>): List<String> =
        if (tokens.size < 2) emptyList() else tokens.zipWithNext { a, b -> "$a $b" }

    private fun rebuildDocFrequency() {
        val freq = mutableMapOf<String, Int>()
        val bigramFreq = mutableMapOf<String, Int>()
        chunks.forEach { chunk ->
            val tokens = words(chunk)
            tokens.toSet().forEach { w -> freq[w] = (freq[w] ?: 0) + 1 }
            bigrams(tokens).toSet().forEach { b -> bigramFreq[b] = (bigramFreq[b] ?: 0) + 1 }
        }
        docFrequency = freq
        bigramDocFrequency = bigramFreq
    }

    fun hasDocuments(): Boolean = chunks.isNotEmpty()

    /** Returns the top matching chunks for a query, joined as context text, ranked by
     *  cosine-normalized TF-IDF (unigram + bigram) overlap. */
    fun retrieveContext(query: String, topN: Int = 3): String {
        if (chunks.isEmpty()) return ""
        val queryTokens = words(query)
        val queryWords = queryTokens.toSet()
        val queryBigrams = bigrams(queryTokens).toSet()
        val totalDocs = chunks.size.coerceAtLeast(1)

        fun idf(word: String): Double {
            val df = docFrequency[word] ?: 0
            return ln((totalDocs + 1.0) / (df + 1.0)) + 1.0
        }
        fun bigramIdf(bigram: String): Double {
            val df = bigramDocFrequency[bigram] ?: 0
            return ln((totalDocs + 1.0) / (df + 1.0)) + 1.0
        }

        return chunks
            .map { chunk ->
                val chunkTokens = words(chunk)
                val chunkWordCounts = chunkTokens.groupingBy { it }.eachCount()
                val chunkBigramCounts = bigrams(chunkTokens).groupingBy { it }.eachCount()

                val unigramScore = queryWords.sumOf { qw ->
                    val tf = chunkWordCounts[qw] ?: 0
                    if (tf > 0) tf * idf(qw) else 0.0
                }
                // Bigram (phrase) matches weighted higher — "on device" matching "on device"
                // is a much stronger signal than the words "on" and "device" separately
                // matching two unrelated chunks.
                val bigramScore = queryBigrams.sumOf { qb ->
                    val tf = chunkBigramCounts[qb] ?: 0
                    if (tf > 0) tf * bigramIdf(qb) * 2.0 else 0.0
                }
                // Cosine-style length normalization — without this, a chunk that's simply
                // longer (more total words) tends to rank higher purely by containing more
                // vocabulary overlap by chance, not because it's more relevant.
                val norm = kotlin.math.sqrt(chunkTokens.size.toDouble()).coerceAtLeast(1.0)
                chunk to (unigramScore + bigramScore) / norm
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(topN)
            .joinToString("\n---\n") { it.first }
    }

    /**
     * Ported from the original assistant's `rag.py get_rag_stats` — a quick health/debug
     * readout of what's currently indexed. Useful for a Settings/Stats screen.
     */
    fun getRagStats(): String {
        val docCount = docsDir.listFiles()?.count { it.extension == "txt" } ?: 0
        val totalWords = docFrequency.values.sum()
        return "📚 RAG stats: $docCount document(s), ${chunks.size} chunk(s), ${docFrequency.size} unique term(s), $totalWords total term-occurrences"
    }

    /**
     * Ported from `rag.py summarize_old_turns` — the original SQLite version compacted old
     * chat turns into a single summary chunk so the RAG index doesn't grow unbounded. There's
     * no separate per-chat turn table here (chunks come from imported documents), so this
     * collapses the oldest indexed chunks past [keepRecent] into one condensed chunk instead —
     * same idea (bound growth, keep the gist), applied to what this helper actually stores.
     */
    fun summarizeOldTurns(keepRecent: Int = 200, maxCharsPerOldChunk: Int = 160): String {
        if (chunks.size <= keepRecent) return "📚 Kuch summarize karne layak nahi — sirf ${chunks.size} chunk(s) hain"
        val old = chunks.subList(0, chunks.size - keepRecent).toList()
        val recent = chunks.subList(chunks.size - keepRecent, chunks.size).toList()
        val condensed = old.joinToString(" ") { it.take(maxCharsPerOldChunk) }
        chunks.clear()
        chunks.add("[summarized ${old.size} older chunks] $condensed")
        chunks.addAll(recent)
        rebuildDocFrequency()
        return "📚 ${old.size} purane chunks ko 1 summary chunk me condense kar diya (${recent.size} recent chunks intact)"
    }
}
