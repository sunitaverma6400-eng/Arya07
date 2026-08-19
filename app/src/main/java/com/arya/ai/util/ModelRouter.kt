package com.arya.ai.util

import com.arya.ai.data.OnlineModel

/**
 * Deterministic, zero-extra-LLM-cost router. It classifies the user's request locally and
 * scores the currently available models by capability. Live OpenRouter models use relay
 * supplied capability tags; static provider models use their id/name/note as a fallback.
 */
enum class QueryIntent { CODING, VISION, REASONING, LONG_CONTEXT, QUICK, GENERAL }

object ModelRouter {
    private val coding = listOf("code", "coding", "kotlin", "python", "java", "javascript", "typescript", "xml", "html", "css", "function", "class ", "bug", "exception", "compile", "syntax", "api", "regex", "sql", "git", "github", "programming", "algorithm", "debug", "stacktrace", "stack trace", "variable", "loop", "array", "json", "gradle", "android studio")
    private val vision = listOf("photo", "picture", "image", "camera", "screenshot", "what do you see", "look at", "read this image", "in this image", "visual")
    private val reasoning = listOf("why", "prove", "derive", "reason", "compare", "analyze", "analyse", "tradeoff", "trade-off", "pros and cons", "solve", "math", "logic", "architecture", "deeply", "step by step")
    private val longContext = listOf("pdf", "document", "documents", "contract", "book", "chapter", "long file", "large file", "entire file", "whole file", "summarize this")

    fun classify(prompt: String): QueryIntent {
        val p = prompt.lowercase()
        if (coding.any(p::contains)) return QueryIntent.CODING
        if (vision.any(p::contains)) return QueryIntent.VISION
        if (longContext.any(p::contains)) return QueryIntent.LONG_CONTEXT
        if (reasoning.any(p::contains)) return QueryIntent.REASONING
        val words = prompt.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
        return if (words in 1..6) QueryIntent.QUICK else QueryIntent.GENERAL
    }

    fun providerOrder(prompt: String, forceGeminiOnly: Boolean): List<ApiProvider> {
        if (forceGeminiOnly) return listOf(ApiProvider.GEMINI)
        return when (classify(prompt)) {
            QueryIntent.CODING -> listOf(ApiProvider.OPENROUTER, ApiProvider.GEMINI, ApiProvider.GROQ)
            QueryIntent.VISION -> listOf(ApiProvider.GEMINI, ApiProvider.OPENROUTER, ApiProvider.GROQ)
            QueryIntent.REASONING -> listOf(ApiProvider.OPENROUTER, ApiProvider.GEMINI, ApiProvider.GROQ)
            QueryIntent.LONG_CONTEXT -> listOf(ApiProvider.GEMINI, ApiProvider.OPENROUTER, ApiProvider.GROQ)
            QueryIntent.QUICK -> listOf(ApiProvider.GROQ, ApiProvider.GEMINI, ApiProvider.OPENROUTER)
            QueryIntent.GENERAL -> listOf(ApiProvider.GEMINI, ApiProvider.GROQ, ApiProvider.OPENROUTER)
        }
    }

    fun orderModels(provider: ApiProvider, intent: QueryIntent, models: List<OnlineModel>, selectedId: String): List<OnlineModel> {
        if (models.isEmpty()) return emptyList()
        return models.sortedWith(compareByDescending<OnlineModel> { score(provider, intent, it, selectedId) }.thenBy { it.id })
    }

    private fun score(provider: ApiProvider, intent: QueryIntent, model: OnlineModel, selectedId: String): Int {
        val blob = "${model.id} ${model.displayName} ${model.note}".lowercase()
        val tags = model.tags.map { it.lowercase() }.toSet()
        val modalities = model.inputModalities.map { it.lowercase() }.toSet()
        val params = model.supportedParameters.map { it.lowercase() }.toSet()
        var s = if (model.id == selectedId) 18 else 0
        when (intent) {
            QueryIntent.CODING -> {
                if ("coding" in tags || hasAny(blob, "code", "coding", "program", "developer", "swe", "terminal")) s += 100
                if (model.supportsTools || "tools" in params) s += 20
            }
            QueryIntent.VISION -> {
                if ("vision" in tags || modalities.any { it in setOf("image", "file", "video") } || hasAny(blob, "vision", "image", "multimodal", "visual")) s += 110
            }
            QueryIntent.REASONING -> {
                if (model.supportsReasoning || "reasoning" in tags || hasAny(blob, "reasoning", "math", "logic", "thinking", "deep")) s += 100
            }
            QueryIntent.LONG_CONTEXT -> {
                if ((model.contextLength ?: 0) >= 200_000) s += 110
                else if ("long_context" in tags || hasAny(blob, "long context", "1m", "million", "large context")) s += 80
            }
            QueryIntent.QUICK -> if (provider == ApiProvider.GROQ || "lightweight" in tags || hasAny(blob, "fast", "light", "mini", "small")) s += 70
            QueryIntent.GENERAL -> if ("general" in tags || hasAny(blob, "general", "chat", "assistant", "versatile")) s += 30
        }
        if (provider == ApiProvider.OPENROUTER && modalities.isNotEmpty() && "text" !in modalities) s -= 500
        if (provider == ApiProvider.GEMINI && intent == QueryIntent.VISION) s += 25
        if (provider == ApiProvider.GEMINI && intent == QueryIntent.LONG_CONTEXT) s += 20
        if (provider == ApiProvider.GROQ && intent == QueryIntent.QUICK) s += 20
        return s
    }

    private fun hasAny(text: String, vararg hints: String): Boolean = hints.any(text::contains)
}
