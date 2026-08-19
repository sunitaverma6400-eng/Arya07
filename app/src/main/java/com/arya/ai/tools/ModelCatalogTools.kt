package com.arya.ai.tools

import android.content.Context
import com.arya.ai.util.ModelCatalog

/**
 * Tool-facing wrapper around [ModelCatalog] — lets Arya answer questions like "abhi kaunse
 * free model available hain" or "coding ke liye best free model kaunsa hai" from its own
 * live-discovered OpenRouter catalog, and lets a user (or Arya itself, via a tool call)
 * force a re-check instead of waiting for the normal 6-hour cache TTL. See
 * [com.arya.ai.util.OnlineChatHelper]'s `liveOpenRouterModels`/`orderedModelsFor` for how the
 * same catalog silently drives actual model *selection*, not just this Q&A surface.
 */
object ModelCatalogTools {

    /** "list_free_models" tool — human-readable, tag-grouped summary of the cached catalog.
     *  Reads the on-disk cache first (may trigger a live relay fetch if it's stale/empty —
     *  see [ModelCatalog.getFreeOpenRouterModels]), so this is the one place guaranteed to
     *  actually warm the catalog even in a process that hasn't opened Chat/Settings yet. */
    suspend fun listFreeModels(context: Context, forceRefresh: Boolean = false): String {
        ModelCatalog.getFreeOpenRouterModels(context, forceRefresh)
        return ModelCatalog.summaryText(context)
    }

    /** "refresh_model_catalog" tool — explicit re-check against OpenRouter's live catalog,
     *  bypassing the cache entirely. Separate tool from [listFreeModels] so Arya/the user can
     *  ask specifically for a fresh check ("dobara check karo") without every normal
     *  "which models are free" question forcing a network round-trip. */
    suspend fun refreshModelCatalog(context: Context): String {
        val models = ModelCatalog.refresh(context)
        return if (models.isEmpty())
            "❌ OpenRouter se model list refresh nahi ho payi (relay down ho sakta hai ya offline ho) — purani cached list use ho rahi hai."
        else
            "✅ Model catalog refresh ho gayi — ${models.size} free OpenRouter models mile.\n\n" + ModelCatalog.summaryText(context)
    }

    /** "best_model_for" tool — which live-discovered model Arya would currently pick for a
     *  given need (coding/vision/long_context/reasoning/lightweight/general), so a curious
     *  user can ask this directly instead of it only being an invisible routing decision. */
    fun bestModelFor(context: Context, need: String): String {
        val tag = need.trim().lowercase().let {
            when {
                it.contains("cod") || it.contains("program") -> "coding"
                it.contains("vision") || it.contains("image") || it.contains("photo") -> "vision"
                it.contains("long") || it.contains("context") || it.contains("document") -> "long_context"
                it.contains("reason") || it.contains("math") || it.contains("logic") -> "reasoning"
                it.contains("fast") || it.contains("light") || it.contains("quick") -> "lightweight"
                else -> "general"
            }
        }
        val model = ModelCatalog.firstWithTag(context, tag)
            ?: return "❌ '$need' ke liye abhi koi live-discovered model nahi mili — 'refresh_model_catalog' try karo, ya Arya static fallback list use karegi."
        return "🎯 '$need' ke liye abhi ka best pick: **${model.name}** (`${model.id}`)\n${model.description.ifBlank { "(koi description nahi)" }}"
    }
}
