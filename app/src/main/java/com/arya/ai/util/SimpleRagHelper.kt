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
        private const val INDEX_FILENAME = "rag_index.json"
        private const val MAX_CHUNK_CHARS = 700
        private const val MAX_INDEX_CHUNKS = 20_000
    }

    private data class Chunk(
        val id: String,
        val document: String,
        val text: String
    )

    private val docsDir = File(context.filesDir, "documents").apply { mkdirs() }
    private val indexFile = File(context.filesDir, INDEX_FILENAME)
    private val chunks = mutableListOf<Chunk>()
    private var docFrequency: Map<String, Int> = emptyMap()
    private var bigramDocFrequency: Map<String, Int> = emptyMap()

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
        reloadAllFromDisk()
    }

    fun importTextDocument(name: String, content: String) {
        val safeName = sanitizeName(name)
        File(docsDir, "$safeName.txt").writeText(content)
        reloadAllFromDisk()
    }

    fun importPdfDocument(name: String, uri: Uri) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc ->
                val text = PDFTextStripper().getText(doc)
                importTextDocument(name, text)
            }
        }
    }

    fun refreshCurrentInfo(summaryText: String) {
        File(docsDir, CURRENT_INFO_FILENAME).writeText(summaryText)
        reloadAllFromDisk()
    }

    fun getCurrentInfoRaw(): String? {
        val file = File(docsDir, CURRENT_INFO_FILENAME)
        return if (file.exists()) file.readText() else null
    }

    /** Returns document names currently indexed, excluding the current-info snapshot. */
    fun getDocumentNames(): List<String> = docsDir.listFiles()
        ?.filter { it.extension == "txt" && it.name != CURRENT_INFO_FILENAME }
        ?.map { it.nameWithoutExtension }
        ?.sorted()
        ?: emptyList()

    private fun sanitizeName(name: String): String = name
        .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
        .trim('_')
        .ifBlank { "document" }
        .take(120)

    private fun reloadAllFromDisk() {
        chunks.clear()
        docsDir.listFiles()
            ?.filter { it.extension == "txt" }
            ?.sortedBy { it.name }
            ?.forEach { file -> indexText(file.nameWithoutExtension, file.readText()) }

        if (chunks.size > MAX_INDEX_CHUNKS) {
            val kept = chunks.takeLast(MAX_INDEX_CHUNKS)
            chunks.clear()
            chunks.addAll(kept)
        }
        rebuildDocFrequency()
        persistIndexMetadata()
    }

    private fun indexText(document: String, content: String) {
        content.split(Regex("\\n{2,}"))
            .map { it.trim() }
            .filter { it.length > 20 }
            .forEach { paragraph ->
                splitToMaxLength(paragraph).forEachIndexed { index, text ->
                    chunks += Chunk("$document:$index:${text.hashCode()}", document, text)
                }
            }
    }

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

    // Unicode-aware tokenisation: the old \\W+ split could discard useful Devanagari/Hinglish
    // tokens. Keeping letters/numbers makes Hindi + English retrieval behave consistently.
    private fun words(text: String): List<String> = text.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 1 }

    private fun bigrams(tokens: List<String>): List<String> =
        if (tokens.size < 2) emptyList() else tokens.zipWithNext { a, b -> "$a $b" }

    private fun rebuildDocFrequency() {
        val freq = mutableMapOf<String, Int>()
        val bigramFreq = mutableMapOf<String, Int>()
        chunks.forEach { chunk ->
            words(chunk.text).toSet().forEach { w -> freq[w] = (freq[w] ?: 0) + 1 }
            bigrams(words(chunk.text)).toSet().forEach { b -> bigramFreq[b] = (bigramFreq[b] ?: 0) + 1 }
        }
        docFrequency = freq
        bigramDocFrequency = bigramFreq
    }

    private fun persistIndexMetadata() {
        // The actual source text remains in the documents directory. This tiny metadata file
        // makes the index format/version explicit and gives future migrations a safe anchor.
        try {
            val json = org.json.JSONObject()
                .put("version", 2)
                .put("updatedAt", System.currentTimeMillis())
                .put("documents", getDocumentNames().size)
                .put("chunks", chunks.size)
            indexFile.writeText(json.toString())
        } catch (_: Exception) { /* indexing itself must never fail because metadata can't save */ }
    }

    fun hasDocuments(): Boolean = chunks.isNotEmpty()

    /** Hybrid lexical retrieval: TF-IDF + phrase matches + a small document diversity bonus. */
    fun retrieveContext(query: String, topN: Int = 4): String {
        if (chunks.isEmpty() || query.isBlank()) return ""
        val queryTokens = words(query)
        val queryWords = queryTokens.toSet()
        val queryBigrams = bigrams(queryTokens).toSet()
        val totalDocs = chunks.size.coerceAtLeast(1)

        fun idf(word: String): Double {
            val df = docFrequency[word] ?: 0
            return ln((totalDocs + 1.0) / (df + 1.0)) + 1.0
        }
        fun bigramIdf(value: String): Double {
            val df = bigramDocFrequency[value] ?: 0
            return ln((totalDocs + 1.0) / (df + 1.0)) + 1.0
        }

        val ranked = chunks.map { chunk ->
            val tokens = words(chunk.text)
            val counts = tokens.groupingBy { it }.eachCount()
            val biCounts = bigrams(tokens).groupingBy { it }.eachCount()
            val unigram = queryWords.sumOf { q -> (counts[q] ?: 0) * idf(q) }
            val phrase = queryBigrams.sumOf { q -> (biCounts[q] ?: 0) * bigramIdf(q) * 2.5 }
            val exactPhraseBonus = if (query.trim().length >= 4 &&
                chunk.text.contains(query.trim(), ignoreCase = true)) 3.0 else 0.0
            val norm = kotlin.math.sqrt(tokens.size.toDouble()).coerceAtLeast(1.0)
            chunk to ((unigram + phrase) / norm + exactPhraseBonus)
        }.filter { it.second > 0 }.sortedByDescending { it.second }

        val selected = mutableListOf<Chunk>()
        val perDocument = mutableMapOf<String, Int>()
        for ((chunk, _) in ranked) {
            val count = perDocument[chunk.document] ?: 0
            if (count >= 2) continue
            selected += chunk
            perDocument[chunk.document] = count + 1
            if (selected.size >= topN.coerceIn(1, 8)) break
        }
        return selected.joinToString("\n---\n") { "[Source: ${it.document}]\n${it.text}" }
    }

    fun getRagStats(): String {
        val docCount = getDocumentNames().size + if (File(docsDir, CURRENT_INFO_FILENAME).exists()) 1 else 0
        val totalWords = docFrequency.values.sum()
        return "📚 RAG stats: $docCount document(s), ${chunks.size} chunk(s), ${docFrequency.size} unique term(s), $totalWords indexed term-occurrences"
    }

    fun summarizeOldTurns(keepRecent: Int = 200, maxCharsPerOldChunk: Int = 160): String {
        if (chunks.size <= keepRecent) return "📚 Kuch summarize karne layak nahi — sirf ${chunks.size} chunk(s) hain"
        // Never mutate the on-disk document corpus here. The old implementation changed only
        // the in-memory list and then lost that work on restart. Rebuilding from source files is
        // now deterministic, so this method simply reports that compaction is unnecessary.
        reloadAllFromDisk()
        return "📚 Source documents preserved hain; RAG index deterministic hai (${chunks.size} chunks)"
    }
}
