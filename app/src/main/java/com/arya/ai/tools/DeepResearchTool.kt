package com.arya.ai.tools

import android.content.Context
import com.arya.ai.util.OnlineChatHelper
import com.arya.ai.util.PreferencesManager

/**
 * Arya's answer to Gemini's "Deep Research" — decomposes a topic into a handful of focused
 * sub-questions, runs a real [WebTools.webSearch] (Tavily via the relay, falling back to
 * DuckDuckGo) for each one, then feeds every source it found back into the LLM to write a
 * single synthesized, cited report instead of one shallow single-search answer.
 *
 * This is intentionally a much smaller version of the real thing: Google's Deep Research reads
 * dozens of pages with a purpose-built research agent; this does ~4 targeted searches (list
 * results only, not full page scrapes, to keep it fast enough for a chat reply) and one
 * synthesis pass. It's meant to noticeably beat a single web_search call, not match Gemini's.
 */
object DeepResearchTool {

    private const val SUB_QUESTION_COUNT = 4

    suspend fun run(context: Context, topic: String): String {
        if (topic.isBlank()) return "❌ Kis topic pe deep research karni hai, wo bhi bolo."
        val prefs = PreferencesManager(context)

        val subQuestions = planSubQuestions(prefs, topic)
        if (subQuestions.isEmpty()) return "❌ Research plan nahi ban paya, dubara try karo."

        // Sequential (not parallel) on purpose — Tavily/relay calls share the same relay app
        // secret and free-tier rate limits as every other tool call in this conversation;
        // firing 4 at once risks tripping those, and this already runs on a background
        // dispatcher (see AryaToolRegistry.execute) so the UI isn't blocked either way.
        val findings = StringBuilder()
        subQuestions.forEachIndexed { i, q ->
            val result = WebTools.webSearch(context, q, maxResults = 4)
            findings.append("### Sub-question ${i + 1}: $q\n$result\n\n")
        }

        return synthesizeReport(prefs, topic, subQuestions, findings.toString())
    }

    /** One LLM call: turn a broad topic into [SUB_QUESTION_COUNT] specific, individually
     *  searchable angles — e.g. "electric cars" -> "current EV market leaders 2026",
     *  "EV battery technology advances", "government EV incentives/policy", "EV vs ICE
     *  total cost of ownership" — instead of just searching "electric cars" once. */
    private suspend fun planSubQuestions(prefs: PreferencesManager, topic: String): List<String> {
        val systemPrompt =
            "Tum ek research planner ho. User ek broad topic dete hain, tumhe uske $SUB_QUESTION_COUNT " +
            "alag-alag, specific, web-searchable angles/sub-questions banane hain jo mil ke topic ko " +
            "achhi tarah cover karein (jaise: current state, recent developments, different " +
            "viewpoints/comparisons, practical implications — jo bhi is specific topic ke liye sahi baithe). " +
            "STRICT FORMAT: sirf $SUB_QUESTION_COUNT lines do, ek sub-question per line, koi numbering, " +
            "bullet, ya extra text nahi — bas plain questions, ek line ek question."
        val raw = try {
            OnlineChatHelper.generateOnlineResponse(prefs, topic, systemPrompt).text
        } catch (e: Exception) {
            return emptyList()
        }
        return raw.lines()
            .map { it.trim().trimStart('-', '*', '•', ' ').trim() }
            .map { it.replace(Regex("^\\d+[.)]\\s*"), "") } // strip "1. " / "1) " if the model added it anyway
            .filter { it.isNotBlank() }
            .take(SUB_QUESTION_COUNT)
    }

    /** Second LLM call: everything [run] found, turned into one coherent report instead of
     *  the raw sub-question-by-sub-question search dump. */
    private suspend fun synthesizeReport(
        prefs: PreferencesManager,
        topic: String,
        subQuestions: List<String>,
        findings: String
    ): String {
        val systemPrompt =
            "Tum ek research assistant ho. Neeche '$topic' par ${subQuestions.size} alag angles se " +
            "web search results diye hain. In sabko padhke ek single, well-organized report likho — " +
            "chhote-chhote headings ke saath (## se), har section me jo sabse relevant/important mila " +
            "wahi likho apne shabdon me (search result text seedha copy mat karo), aur jahan koi specific " +
            "fact/number ho wahan uska source URL bracket me do jaise [source: url]. Agar kisi " +
            "sub-question ka koi useful result nahi mila, us section ko skip kar do — khali/weak section " +
            "mat likho. Answer sirf report hi ho, koi meta-commentary jaise 'here is your report' nahi. " +
            "Hinglish me likho, conversational lekin thorough."
        val prompt = "Topic: $topic\n\nSearch findings:\n$findings"
        return try {
            "🔬 **Deep Research: $topic**\n\n" +
                OnlineChatHelper.generateOnlineResponse(prefs, prompt, systemPrompt).text.trim()
        } catch (e: Exception) {
            "❌ Report synthesize nahi ho paayi (${e.message}). Findings raw yahan hain:\n\n$findings"
        }
    }
}
