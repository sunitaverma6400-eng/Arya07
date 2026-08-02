package com.arya.ai.data

import com.arya.ai.util.ApiProvider

/**
 * A single hosted/online chat model that's free to call on its provider's free tier.
 *
 * @param id the exact model string the provider's API expects (sent as `"model": id`)
 * @param displayName what the model picker (see `ui/OnlineModelsScreen.kt`) shows
 * @param note short one-line context (why pick this one, or a caveat)
 */
data class OnlineModel(
    val id: String,
    val displayName: String,
    val note: String = ""
)

/**
 * Curated, free-tier-only model lists per online provider. Researched live against each
 * provider's current docs/model catalog (26 Jul 2026) — free tiers rotate every few weeks,
 * especially OpenRouter's, so re-check before trusting this list months from now:
 *   - Groq: console.groq.com/docs/models
 *   - Gemini: ai.google.dev/gemini-api/docs/models (Google AI Studio free tier)
 *   - OpenRouter: openrouter.ai/models filtered to Price: Free
 *
 * Every model below has $0 pricing / a genuinely free tier with no credit card required —
 * paid-only models (e.g. Gemini 3.1 Pro Preview, Groq Enterprise-only models, any
 * OpenRouter model without a `:free` id) were deliberately left out.
 */
object OnlineModels {

    /**
     * Groq retired `llama-3.3-70b-versatile`/`llama-3.1-8b-instant`/`qwen/qwen3-32b` — its
     * current recommended free-tier lineup is just these three (console.groq.com/docs/models).
     */
    val GROQ = listOf(
        OnlineModel(
            id = "openai/gpt-oss-120b",
            displayName = "Groq openai/gpt-oss-120b",
            note = "Sabse capable — agentic/tool-use ke liye best, 1,000 req/day free"
        ),
        OnlineModel(
            id = "openai/gpt-oss-20b",
            displayName = "Groq openai/gpt-oss-20b",
            note = "Halka aur fast, roz-marra chat ke liye"
        ),
        OnlineModel(
            id = "qwen/qwen3.6-27b",
            displayName = "Groq qwen/qwen3.6-27b",
            note = "Vision-capable (multimodal), preview model"
        )
    )

    /** Google AI Studio free tier — no credit card, ~15 RPM / 250-1500 RPD depending on model. */
    val GEMINI = listOf(
        OnlineModel(
            id = "gemini-flash-lite-latest",
            displayName = "Gemini gemini-flash-lite-latest",
            note = "Auto-updating alias — hamesha latest Flash-Lite"
        ),
        OnlineModel(
            id = "gemini-3.1-flash-lite",
            displayName = "Gemini gemini-3.1-flash-lite",
            note = "Sabse sasta/fast, high-volume ke liye"
        ),
        OnlineModel(
            id = "gemini-flash-latest",
            displayName = "Gemini gemini-flash-latest",
            note = "Auto-updating alias — hamesha latest Flash"
        ),
        OnlineModel(
            id = "gemini-3.5-flash",
            displayName = "Gemini gemini-3.5-flash",
            note = "Near-Pro quality, Flash-tier speed/cost"
        ),
        OnlineModel(
            id = "gemini-2.5-flash-lite",
            displayName = "Gemini gemini-2.5-flash-lite",
            note = "Purana stable pick, dependable fallback"
        ),
        OnlineModel(
            id = "gemini-2.5-flash",
            displayName = "Gemini gemini-2.5-flash",
            note = "Purana stable pick, best free-tier balance"
        )
    )

    /**
     * OpenRouter's free lineup rotates the fastest of the three providers — DeepSeek/Mistral/
     * Gemini currently have zero `:free` models there, so this list sticks to what's verified
     * live (openrouter.ai/models, Price: Free) as of 26 Jul 2026.
     */
    val OPENROUTER = listOf(
        OnlineModel(
            id = "openai/gpt-oss-120b:free",
            displayName = "OpenRouter openai/gpt-oss-120b",
            note = "General reasoning + agentic tool use, 131K context"
        ),
        OnlineModel(
            id = "openai/gpt-oss-20b:free",
            displayName = "OpenRouter openai/gpt-oss-20b",
            note = "Lightweight, fast general-purpose"
        ),
        OnlineModel(
            id = "meta-llama/llama-3.3-70b-instruct:free",
            displayName = "OpenRouter meta-llama/llama-3.3-70b-instruct",
            note = "Sabse stable/established free pick, multilingual"
        ),
        OnlineModel(
            id = "qwen/qwen3-next-80b-a3b-instruct:free",
            displayName = "OpenRouter qwen/qwen3-next-80b-a3b-instruct",
            note = "RAG / tool-use / long multi-turn agent workflows"
        ),
        OnlineModel(
            id = "nvidia/nemotron-3-ultra-550b-a55b:free",
            displayName = "OpenRouter nvidia/nemotron-3-ultra-550b-a55b",
            note = "1M context — long documents/deep research"
        ),
        OnlineModel(
            id = "nvidia/nemotron-3-super-120b-a12b:free",
            displayName = "OpenRouter nvidia/nemotron-3-super-120b-a12b",
            note = "Multi-agent apps, cross-document reasoning"
        ),
        OnlineModel(
            id = "google/gemma-4-31b-it:free",
            displayName = "OpenRouter google/gemma-4-31b-it",
            note = "Vision + text input, 140+ languages"
        ),
        OnlineModel(
            id = "poolside/laguna-m.1:free",
            displayName = "OpenRouter poolside/laguna-m.1",
            note = "Agentic coding, 262K context"
        )
    )

    fun forProvider(provider: ApiProvider): List<OnlineModel> = when (provider) {
        ApiProvider.GROQ -> GROQ
        ApiProvider.GEMINI -> GEMINI
        ApiProvider.OPENROUTER -> OPENROUTER
        else -> emptyList()
    }
}
