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
    val note: String = "",
    val contextLength: Int? = null,
    val inputModalities: List<String> = emptyList(),
    val outputModalities: List<String> = emptyList(),
    val supportedParameters: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val supportsTools: Boolean = false,
    val supportsReasoning: Boolean = false
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
            note = "Auto-updating alias — hamesha latest Flash-Lite",
            supportsTools = true
        ),
        OnlineModel(
            id = "gemini-3.1-flash-lite",
            displayName = "Gemini gemini-3.1-flash-lite",
            note = "Sabse sasta/fast, high-volume ke liye",
            supportsTools = true
        ),
        OnlineModel(
            id = "gemini-flash-latest",
            displayName = "Gemini gemini-flash-latest",
            note = "Auto-updating alias — hamesha latest Flash",
            supportsTools = true
        ),
        OnlineModel(
            id = "gemini-3.5-flash",
            displayName = "Gemini gemini-3.5-flash",
            note = "Near-Pro quality, Flash-tier speed/cost",
            supportsTools = true
        ),
        OnlineModel(
            id = "gemini-2.5-flash-lite",
            displayName = "Gemini gemini-2.5-flash-lite",
            note = "Purana stable pick, dependable fallback",
            supportsTools = true
        ),
        OnlineModel(
            id = "gemini-2.5-flash",
            displayName = "Gemini gemini-2.5-flash",
            note = "Purana stable pick, best free-tier balance",
            supportsTools = true
        )
    )

    /**
     * OpenRouter's free lineup rotates the fastest of the three providers. Re-verified live
     * directly against openrouter.ai/collections/free-models on 9 Aug 2026 (see chat history
     * — several entries here had already been silently delisted since the 26 Jul 2026 pass:
     * `meta-llama/llama-3.3-70b-instruct:free`, `qwen/qwen3-next-80b-a3b-instruct:free`, and
     * `poolside/laguna-m.1:free` — the very model this app's coding-detection was pointed at
     * — were all gone. Re-check before trusting this list months from now; OpenRouter's own
     * page is the source of truth, not any blog/search result.
     */
    val OPENROUTER = listOf(
        // ---- Dedicated coding models (see OPENROUTER_BEST_CODING_ID / OnlineChatHelper's
        // coding-question routing) — ordered by OpenRouter's own "Programming" category rank
        // as of 9 Aug 2026: north-mini-code (#16) > laguna-s-2.1 (#50) > laguna-xs-2.1 (#31,
        // ranked lower overall but kept as the "faster/lighter" option, not strictly by rank).
        OnlineModel(
            id = "cohere/north-mini-code:free",
            displayName = "OpenRouter cohere/north-mini-code",
            note = "Coding ke liye default pick — agentic coding, terminal tasks, tool-use JSON"
        ),
        OnlineModel(
            id = "poolside/laguna-s-2.1:free",
            displayName = "OpenRouter poolside/laguna-s-2.1",
            note = "Strong coding agent, SWE-bench/Terminal-Bench pe achha"
        ),
        OnlineModel(
            id = "poolside/laguna-xs-2.1:free",
            displayName = "OpenRouter poolside/laguna-xs-2.1",
            note = "Halka/fast coding agent, cost-efficient"
        ),
        // ---- General-purpose ----
        OnlineModel(
            id = "nvidia/nemotron-3-super-120b-a12b:free",
            displayName = "OpenRouter nvidia/nemotron-3-super-120b-a12b",
            note = "Multi-agent apps, cross-document reasoning"
        ),
        OnlineModel(
            id = "nvidia/nemotron-3-ultra-550b-a55b:free",
            displayName = "OpenRouter nvidia/nemotron-3-ultra-550b-a55b",
            note = "1M context — long documents/deep research"
        ),
        OnlineModel(
            id = "google/gemma-4-26b-a4b-it:free",
            displayName = "OpenRouter google/gemma-4-26b-a4b-it",
            note = "Vision + text + short video input, 256K context"
        ),
        OnlineModel(
            id = "openai/gpt-oss-20b:free",
            displayName = "OpenRouter openai/gpt-oss-20b",
            note = "Lightweight, fast general-purpose"
        ),
        OnlineModel(
            id = "inclusionai/ling-3.0-tiny:free",
            displayName = "OpenRouter inclusionai/ling-3.0-tiny",
            note = "Halka, responsive multi-turn chat"
        )
    )

    /** See chat history — Arya routes coding-classified messages to this model first, ahead
     *  of even Gemini (see OnlineChatHelper.providerOrderFor/orderedModelsFor). Cohere North
     *  Mini Code specifically: OpenRouter's own "Programming" category rank #16 among all
     *  free models as of 9 Aug 2026 — the highest-ranked dedicated coding model available for
     *  free there (the Poolside Laguna tiers above rank lower, #50/#31, despite also being
     *  coding-focused). Re-verify this is still live before trusting it months from now. */
    const val OPENROUTER_BEST_CODING_ID = "cohere/north-mini-code:free"

    fun forProvider(provider: ApiProvider): List<OnlineModel> = when (provider) {
        ApiProvider.GROQ -> GROQ
        ApiProvider.GEMINI -> GEMINI
        ApiProvider.OPENROUTER -> OPENROUTER
        else -> emptyList()
    }
}
