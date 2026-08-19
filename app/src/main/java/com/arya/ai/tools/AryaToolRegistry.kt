package com.arya.ai.tools

import android.content.Context
import com.arya.ai.data.DeviceActions
import com.arya.ai.inference.ToolCall
import com.arya.ai.inference.ToolDefinition
import com.arya.ai.inference.ToolParam
import com.arya.ai.inference.ToolGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything ported from the original assistant project's `tools.py` (~50 tools), reimplemented natively for
 * Android — no Termux, no Python backend. `self_evolve.py` (self-rewriting source code)
 * was NOT ported: an installed APK can't rewrite its own compiled code at runtime, so
 * that one doesn't have an Android equivalent.
 *
 * Uses the same JSON tool-calling convention as the rest of Arya
 * ([com.arya.ai.inference.ToolCallParser]) so any screen (Agent Skills, Mobile Actions,
 * Chat) can opt in just by adding [ALL_TOOLS] to its tool list and calling [execute].
 */
object AryaToolRegistry {

    /** Set by the `generate_image` branch below, right after [ImageGenTools.generate]
     *  succeeds. [execute] itself still just returns a confirmation String (unchanged, so
     *  none of the ~30 other branches needed touching) — callers that care about actually
     *  showing the picture (currently [com.arya.ai.viewmodel.ChatViewModel]'s tool-call loop)
     *  read this right after calling [execute] and clear it via [takeLastGeneratedImage]. */
    @Volatile
    private var lastGeneratedImage: android.graphics.Bitmap? = null

    /** Reads and clears [lastGeneratedImage] in one step, so a later unrelated tool call in
     *  the same conversation can't accidentally reattach a stale picture to its reply. */
    fun takeLastGeneratedImage(): android.graphics.Bitmap? {
        val img = lastGeneratedImage
        lastGeneratedImage = null
        return img
    }

    /** Same side-channel pattern as [lastGeneratedImage], but for `search_images` — that tool
     *  used to only return a text list of "title / url" lines for the model to paraphrase in
     *  prose, so the person never actually saw a picture (see chat history: "modern house"
     *  search where Arya described images that were never shown). Now the `search_images`
     *  branch below downloads a few of the result thumbnails as real Bitmaps and stashes them
     *  here so [com.arya.ai.viewmodel.ChatViewModel] can attach them to the reply exactly like
     *  [lastGeneratedImage], reusing the existing image-bubble rendering path. */
    @Volatile
    private var lastSearchedImages: List<android.graphics.Bitmap> = emptyList()

    /** Reads and clears [lastSearchedImages] in one step, same reasoning as [takeLastGeneratedImage]. */
    fun takeLastSearchedImages(): List<android.graphics.Bitmap> {
        val imgs = lastSearchedImages
        lastSearchedImages = emptyList()
        return imgs
    }

    /** Same side-channel pattern as [lastGeneratedImage], but for "a stream/radio just started
     *  playing" — set right after a successful `play_stream`/`find_and_play`/`play_saved_stream`
     *  call below. This is the *reliable* source for the chat UI's "📻 now playing" chip:
     *  [com.arya.ai.ui.ChatScreen]'s `findNowPlayingRadio` regex only catches it when the model's
     *  final reply happens to echo the tool's exact "▶️ Stream shuru: ..." string — which it
     *  often won't, since the system prompt explicitly asks it to answer "apne shabdon me"
     *  (in its own words) using the tool result, not repeat it verbatim. This side-channel
     *  doesn't depend on the model's phrasing at all. */
    @Volatile
    private var lastNowPlaying: String? = null

    /** Reads and clears [lastNowPlaying] in one step, same reasoning as [takeLastGeneratedImage]. */
    fun takeLastNowPlaying(): String? {
        val label = lastNowPlaying
        lastNowPlaying = null
        return label
    }

    private val NOW_PLAYING_REGEX = Regex("▶️ Stream shuru: (.+?) \\(background")
    private val NOW_PLAYING_TOOLS = setOf("play_stream", "find_and_play", "play_saved_stream")

    /** Sets [lastNowPlaying] if [toolName] is one of [NOW_PLAYING_TOOLS] and [result] is a
     *  successful "▶️ Stream shuru: ..." reply — no-ops on any error string or other tool. */
    private fun captureNowPlaying(toolName: String, result: String) {
        if (toolName !in NOW_PLAYING_TOOLS) return
        NOW_PLAYING_REGEX.find(result)?.groupValues?.get(1)?.let { lastNowPlaying = it }
    }

    /** Same side-channel pattern as [lastNowPlaying], but for "a video should start playing
     *  right now, in-app, without the person having to tap a 'Video dekho' button" — set by
     *  [play_video] below. Before this existed, `search_youtube` only ever handed back a link
     *  the model would print, requiring an extra tap on the chat bubble to actually watch it —
     *  this makes "arjit singh ka gaana chalao"-style requests play immediately instead. */
    @Volatile
    private var lastAutoPlayVideo: String? = null

    /** Reads and clears [lastAutoPlayVideo] in one step, same reasoning as [takeLastNowPlaying]. */
    fun takeLastAutoPlayVideo(): String? {
        val url = lastAutoPlayVideo
        lastAutoPlayVideo = null
        return url
    }

    private val AUTOPLAY_REGEX = Regex("""AUTOPLAY:(\S+)""")

    /** Strips a trailing "AUTOPLAY:<url>" marker off [result] (used by [playVideo]/
     *  [findAndPlay] when they resolve to a video) and, if present, stashes the url in
     *  [lastAutoPlayVideo] — same capture-then-strip shape as [captureNowPlaying]. */
    private fun captureAutoPlayVideo(result: String): String {
        AUTOPLAY_REGEX.find(result)?.groupValues?.get(1)?.let { lastAutoPlayVideo = it }
        return result.substringBefore("\nAUTOPLAY:")
    }

    val ALL_TOOLS: List<ToolDefinition> = listOf(
        // -- utility --
        ToolDefinition("get_current_time", "Abhi ka time/date batata hai"),
        ToolDefinition("calculate", "Arithmetic expression evaluate karta hai", listOf(ToolParam("expression", "string", "e.g. '12 * (3 + 4)'"))),
        ToolDefinition("convert_units", "Units convert karta hai (length/weight/volume/temperature)", listOf(
            ToolParam("value", "number", "e.g. 10"), ToolParam("from_unit", "string", "e.g. 'km'"), ToolParam("to_unit", "string", "e.g. 'miles'")
        )),
        ToolDefinition("generate_qr", "Text/URL ka QR code banata hai", listOf(ToolParam("text", "string", "QR me encode karne wala text"))),
        ToolDefinition("generate_password", "Random secure password banata hai", listOf(
            ToolParam("length", "number", "e.g. 16"), ToolParam("use_symbols", "boolean", "\"true\"/\"false\"")
        )),
        ToolDefinition("text_analyzer", "Text ke words/characters/sentences count karta hai", listOf(ToolParam("text", "string", "analyze karne wala text"))),
        ToolDefinition("get_random_quote", "Ek random Hindi motivational quote deta hai"),
        ToolDefinition("system_info", "Phone ka model, Android version, free storage batata hai"),

        // -- documents & charts (Claude jaisi document-creation/data-viz ability) --
        ToolDefinition("create_document", "Text/PDF document banata hai aur device pe save karta hai", listOf(
            ToolParam("name", "string", "e.g. 'meeting-notes'"), ToolParam("content", "string", "document ka poora text"),
            ToolParam("format", "string", "'pdf' ya 'txt', default 'pdf'"), ToolParam("title", "string", "optional — PDF ke top pe heading")
        )),
        ToolDefinition("generate_chart", "Numbers ka bar/line chart image banata hai", listOf(
            ToolParam("title", "string", "chart ka title"), ToolParam("labels", "string", "comma-separated labels, e.g. 'Mon,Tue,Wed'"),
            ToolParam("values", "string", "comma-separated numbers, e.g. '10,25,15'"), ToolParam("type", "string", "'bar' ya 'line', default 'bar'")
        )),

        // -- info / data APIs --
        ToolDefinition("get_weather", "Kisi shehar ka current weather batata hai", listOf(ToolParam("city", "string", "e.g. 'Mumbai'"))),
        ToolDefinition("get_crypto_price", "Kisi cryptocurrency ki price batata hai", listOf(ToolParam("coin", "string", "e.g. 'bitcoin'"))),
        ToolDefinition("convert_currency", "Currency convert karta hai", listOf(
            ToolParam("amount", "number", "e.g. 100"), ToolParam("from_currency", "string", "e.g. 'USD'"), ToolParam("to_currency", "string", "e.g. 'INR'")
        )),
        ToolDefinition("get_country_info", "Kisi desh ka capital/population/region batata hai", listOf(ToolParam("country", "string", "e.g. 'Japan'"))),
        ToolDefinition("get_ip_info", "IP address ki location/org batata hai (khaali chhodo apni current IP ke liye)", listOf(ToolParam("ip", "string", "optional"))),
        ToolDefinition("get_dictionary", "Kisi English word ka meaning batata hai", listOf(ToolParam("word", "string", "e.g. 'ephemeral'"))),
        ToolDefinition("translate_text", "Text translate karta hai (default Hindi me)", listOf(
            ToolParam("text", "string", "English text"), ToolParam("target_lang", "string", "e.g. 'hi', 'fr'")
        )),
        ToolDefinition("get_wikipedia_summary", "Wikipedia se ek topic ka summary deta hai", listOf(ToolParam("query", "string", "e.g. 'Black hole'"))),
        ToolDefinition("get_sunrise_sunset", "Kisi shehar ka sunrise/sunset time batata hai", listOf(ToolParam("city", "string", "e.g. 'Delhi'"))),
        ToolDefinition("get_public_holidays", "Kisi desh ke public holidays batata hai", listOf(
            ToolParam("country_code", "string", "e.g. 'IN'"), ToolParam("year", "number", "e.g. 2026")
        )),
        ToolDefinition("get_spacex_launches", "SpaceX ka latest ya upcoming launch batata hai", listOf(ToolParam("upcoming", "boolean", "\"true\"/\"false\""))),
        ToolDefinition("get_nasa_apod", "NASA ki Astronomy Picture of the Day batata hai"),
        ToolDefinition("get_nasa_iss_location", "ISS space station ki abhi ki location batata hai"),
        ToolDefinition("get_nasa_asteroids", "Aaj ke near-Earth asteroids batata hai"),
        ToolDefinition("get_nasa_mars_photos", "Curiosity rover ki latest Mars photo deta hai"),
        ToolDefinition("ask_wolfram", "Wolfram Alpha se factual/math question ka jawab leta hai (API key chahiye)", listOf(ToolParam("question", "string", "e.g. 'derivative of x^2'"))),

        // -- model self-discovery (see util/ModelCatalog.kt) — Arya khud check karti hai
        // OpenRouter par abhi konse models free hain aur kaunsa kis kaam ke liye best hai,
        // hardcoded list par depend kiye bina --
        ToolDefinition("list_free_models", "Abhi OpenRouter par kaunse free models available hain aur har ek kis kaam ke liye best hai, batata hai (live check)"),
        ToolDefinition("refresh_model_catalog", "OpenRouter ki free model list ko turant dobara check karta hai (cache ignore karke)"),
        ToolDefinition("best_model_for", "Ek specific kaam (coding/vision/reasoning/long context/fast) ke liye abhi ka best free model batata hai", listOf(
            ToolParam("need", "string", "e.g. 'coding', 'vision', 'long context', 'reasoning', 'fast'")
        )),

        // -- web --
        ToolDefinition("web_search", "Web par search karta hai", listOf(ToolParam("query", "string", "search query"))),
        ToolDefinition("scrape_webpage", "Ek webpage ka text/links/title nikalta hai", listOf(
            ToolParam("url", "string", "e.g. 'example.com'"), ToolParam("extract", "string", "'text' | 'links' | 'title'")
        )),
        ToolDefinition("smart_search", "Wikipedia + web search combine karke best jawab dhoondta hai", listOf(ToolParam("query", "string", "search query"))),
        ToolDefinition(
            "deep_research",
            "Kisi topic pe gehraai se research karta hai — topic ko kai angles me todke har ek pe alag " +
                "web search karta hai, phir sabko mila ke ek proper cited report banata hai. Ek simple " +
                "web_search se zyada thorough hai, isliye zyada time bhi leta hai (~4 searches + 2 LLM " +
                "calls) — user ne khud 'deep research', 'detailed research', ya 'poori tarah research karo' " +
                "jaisa bola ho tabhi ye use karo, casual sawaal ke liye plain web_search hi kaafi hai.",
            listOf(ToolParam("topic", "string", "e.g. 'electric vehicle market in India'"))
        ),

        // -- memory / todos --
        ToolDefinition("remember", "Ek fact yaad rakhta hai", listOf(ToolParam("key", "string", "e.g. 'birthday'"), ToolParam("value", "string", "e.g. '12 March'"))),
        ToolDefinition("recall", "Ek yaad rakha fact wapas deta hai", listOf(ToolParam("key", "string", "e.g. 'birthday'"))),
        ToolDefinition("list_memories", "Sab yaad rakhi cheezein list karta hai"),
        ToolDefinition("search_memories", "Saved memories me relevant facts search karta hai", listOf(ToolParam("query", "string", "e.g. 'birthday'"))),
        ToolDefinition("forget", "Ek yaad rakha fact bhula deta hai", listOf(ToolParam("key", "string", "e.g. 'birthday'"))),
        ToolDefinition("add_todo", "Ek naya todo add karta hai", listOf(ToolParam("task", "string", "e.g. 'milk lena hai'"), ToolParam("priority", "string", "'low'|'medium'|'high'"))),
        ToolDefinition("list_todos", "Saare todos list karta hai"),
        ToolDefinition("complete_todo", "Ek todo complete mark karta hai", listOf(ToolParam("task_id", "number", "e.g. 3"))),
        ToolDefinition("delete_todo", "Ek todo delete karta hai", listOf(ToolParam("task_id", "number", "e.g. 3"))),

        // -- persona --
        ToolDefinition("activate_persona", "Ek naya character/persona activate karta hai", listOf(
            ToolParam("character_name", "string", "e.g. 'Iron Man'"), ToolParam("description", "string", "character kaisa hai"), ToolParam("speaking_style", "string", "optional")
        )),
        ToolDefinition("deactivate_persona", "Active persona hata deta hai, normal Arya mode"),
        ToolDefinition("get_current_persona", "Abhi konsi persona active hai batata hai"),
        ToolDefinition("list_saved_personas", "Saari saved personas list karta hai"),
        ToolDefinition("switch_to_saved_persona", "Ek pehle se saved persona par switch karta hai", listOf(ToolParam("character_name", "string", "persona ka naam"))),

        // -- device (native Android, no Termux) --
        ToolDefinition("vibrate", "Phone ko vibrate karta hai", listOf(ToolParam("duration_ms", "number", "e.g. 500"))),
        ToolDefinition("get_battery_status", "Battery level aur charging status batata hai"),
        ToolDefinition("send_notification", "Ek notification bhejta hai", listOf(ToolParam("title", "string", "notification title"), ToolParam("content", "string", "notification text"))),
        ToolDefinition("set_alarm", "Alarm clock app me alarm set karta hai", listOf(
            ToolParam("hour", "number", "0-23"), ToolParam("minute", "number", "0-59"), ToolParam("message", "string", "optional label")
        )),
        ToolDefinition("make_call", "Dialer number ke saath khol deta hai", listOf(ToolParam("phone_number", "string", "e.g. '9876543210'"))),
        ToolDefinition("send_sms", "Messages app number+text ke saath khol deta hai", listOf(
            ToolParam("phone_number", "string", "e.g. '9876543210'"), ToolParam("message", "string", "SMS text")
        )),
        ToolDefinition("open_app", "Kisi installed app ko launch karta hai (exact package name se)", listOf(ToolParam("package_name", "string", "e.g. 'com.whatsapp'"))),
        ToolDefinition("open_app_by_name", "Kisi installed app ko uske naam se launch karta hai", listOf(ToolParam("app_name", "string", "e.g. 'WhatsApp', 'Instagram'"))),
        ToolDefinition("get_location", "Phone ki last-known GPS location batata hai"),
        ToolDefinition("toggle_torch", "Flashlight on/off karta hai", listOf(ToolParam("on", "boolean", "\"true\"/\"false\""))),
        ToolDefinition("open_wifi_settings", "WiFi settings/quick-panel kholta hai"),
        ToolDefinition("open_bluetooth_settings", "Bluetooth settings kholta hai"),
        ToolDefinition("open_dnd_settings", "Do Not Disturb settings kholta hai"),
        ToolDefinition("adjust_volume", "Media volume badhata/kam karta hai", listOf(ToolParam("up", "boolean", "\"true\" = badhao, \"false\" = kam karo"))),
        ToolDefinition("media_play_pause", "Chal rahe gaane/video ko play ya pause karta hai"),
        ToolDefinition("media_next", "Agla track/song play karta hai"),
        ToolDefinition("media_previous", "Pichla track/song play karta hai"),

        // -- contacts / clipboard / calendar (new) --
        ToolDefinition("find_contact_number", "Naam se contact ka phone number dhoondta hai", listOf(ToolParam("name", "string", "e.g. 'Rudra'"))),
        ToolDefinition("call_contact_by_name", "Naam se contact ko dialer me khol deta hai", listOf(ToolParam("name", "string", "e.g. 'Rudra'"))),
        ToolDefinition("read_clipboard", "Clipboard me abhi kya copy hai batata hai"),
        ToolDefinition("write_clipboard", "Text ko clipboard me copy karta hai", listOf(ToolParam("text", "string", "copy karne wala text"))),
        ToolDefinition("create_calendar_event", "Calendar app khol ke naya event pre-fill karta hai (confirm karke save karna hoga)", listOf(
            ToolParam("title", "string", "e.g. 'Doctor appointment'"),
            ToolParam("start_millis", "number", "event start time, epoch milliseconds"),
            ToolParam("duration_minutes", "number", "optional, default 60"),
            ToolParam("description", "string", "optional")
        )),

        // -- streaming (new) --
        ToolDefinition("search_radio", "Radio stations dhoondta hai aur direct stream URL deta hai", listOf(ToolParam("query", "string", "e.g. 'lofi', 'BBC Hindi'"))),
        ToolDefinition("search_youtube", "YouTube par video search karta hai — result par 'Video dekho' button se in-app play ho jaata hai", listOf(ToolParam("query", "string", "search terms"))),
        ToolDefinition("search_videos", "Web par video search karta hai — result par 'Video dekho' button se in-app play ho jaata hai", listOf(ToolParam("query", "string", "search terms"))),
        ToolDefinition("play_stream", "Ek direct audio/HLS stream URL play karta hai. Sirf search_radio/search_youtube/search_videos/find_and_play se mila REAL URL ya user ka diya hua URL yahan do — koi fake/placeholder URL kabhi mat banao. Agar koi real URL/result available nahi hai, to ye tool mat bulao, bas user ko bata do ki kuch nahi mila.", listOf(ToolParam("url", "string", "stream URL"), ToolParam("label", "string", "optional display name"))),
        ToolDefinition("pause_stream", "Chal raha stream pause karta hai"),
        ToolDefinition("resume_stream", "Pause kiya hua stream resume karta hai"),
        ToolDefinition("stop_stream", "Stream poori tarah stop karta hai"),
        ToolDefinition("stop_all_streams", "Sab streams stop karta hai"),
        ToolDefinition("stream_status", "Abhi ka playback status batata hai"),
        ToolDefinition("find_and_play", "Query search karke best-match radio stream seedha play kar deta hai", listOf(ToolParam("query", "string", "e.g. 'lofi radio'"))),
        ToolDefinition("play_video", "Query search karke best-match YouTube video seedha, in-app play kar deta hai (link dikhane ya button-tap ki zaroorat nahi) — jab user 'video chalao/dikhao/lagao' bole ye use karo, sirf link chahiye ho to search_youtube use karo", listOf(ToolParam("query", "string", "e.g. 'arjit singh songs'"))),
        ToolDefinition("test_video_source", "Ek video/stream URL reachable hai ya nahi check karta hai", listOf(ToolParam("url", "string", "URL"))),
        ToolDefinition("save_stream", "Ek stream URL naam ke saath save karta hai", listOf(ToolParam("name", "string", "e.g. 'morning radio'"), ToolParam("url", "string", "stream URL"))),
        ToolDefinition("list_saved_streams", "Saare saved streams list karta hai"),
        ToolDefinition("delete_saved_stream", "Ek saved stream delete karta hai", listOf(ToolParam("name", "string", "saved stream ka naam"))),
        ToolDefinition("play_saved_stream", "Ek saved stream naam se play karta hai", listOf(ToolParam("name", "string", "saved stream ka naam"))),
        ToolDefinition("set_default_stream_quality", "Default stream quality preference set karta hai", listOf(ToolParam("quality", "string", "'auto'|'low'|'medium'|'high'"))),
        ToolDefinition("get_default_stream_quality", "Abhi ki default stream quality batata hai"),
        ToolDefinition("list_stream_qualities", "Available quality options list karta hai"),

        // -- images (new) --
        ToolDefinition("search_images", "Openly-licensed images search karta hai", listOf(ToolParam("query", "string", "e.g. 'sunset mountains'"))),
        ToolDefinition("generate_image", "Text prompt se ek AI image banata hai, gallery me save karta hai (relay/Gemini pehle, Pollinations free fallback)", listOf(ToolParam("prompt", "string", "e.g. 'a tiger in the snow, digital art'"))),
        ToolDefinition("fetch_image_from_url", "Ek image URL download karke phone par save karta hai", listOf(ToolParam("url", "string", "image URL"))),
        ToolDefinition("test_image_source", "Ek image URL valid/reachable hai ya nahi check karta hai", listOf(ToolParam("url", "string", "URL"))),

        // -- saved sites & page watching (new) --
        ToolDefinition("save_site", "Ek website naam ke saath save karta hai", listOf(ToolParam("name", "string", "e.g. 'college portal'"), ToolParam("url", "string", "website URL"))),
        ToolDefinition("list_saved_sites", "Saare saved sites list karta hai"),
        ToolDefinition("delete_saved_site", "Ek saved site delete karta hai", listOf(ToolParam("name", "string", "saved site ka naam"))),
        ToolDefinition("play_saved_site", "Ek saved site browser me kholta hai", listOf(ToolParam("name", "string", "saved site ka naam"))),
        ToolDefinition("get_page_media", "Ek webpage ke images/videos nikalta hai", listOf(ToolParam("url", "string", "page URL"))),
        ToolDefinition("watch_page", "Ek webpage ko change ke liye periodically watch karta hai (~30 min)", listOf(ToolParam("name", "string", "watch ka naam"), ToolParam("url", "string", "page URL"))),
        ToolDefinition("stop_watch", "Ek page watch band karta hai", listOf(ToolParam("name", "string", "watch ka naam"))),
        ToolDefinition("list_page_watches", "Saare active page watches list karta hai"),

        // -- news & briefing (new) --
        ToolDefinition("get_news", "Top headlines ya kisi topic ki news batata hai", listOf(ToolParam("topic", "string", "optional, e.g. 'cricket'"))),
        ToolDefinition("morning_briefing", "Weather + top headlines + quote + time ko ek briefing me combine karta hai", listOf(ToolParam("city", "string", "optional, default IP se guess hoga"))),

        // -- location (new) --
        ToolDefinition("reverse_geocode", "Latitude/longitude se readable address batata hai", listOf(ToolParam("latitude", "number", "e.g. 28.6139"), ToolParam("longitude", "number", "e.g. 77.2090"))),
        ToolDefinition("search_place_osm", "Kisi jagah ko naam se dhoondta hai (OpenStreetMap)", listOf(ToolParam("query", "string", "e.g. 'India Gate Delhi'"))),

        // -- API key management (new) --
        ToolDefinition("list_api_keys", "Kaunse API providers configure hain batata hai (keys masked)"),
        ToolDefinition("delete_api_key", "Ek saved API key delete karta hai", listOf(ToolParam("provider", "string", "e.g. 'GROQ', 'NASA'"), ToolParam("key_suffix", "string", "key ke last 4 characters"))),

        // -- personality / mood (new) --
        ToolDefinition("get_current_mood", "Arya ka abhi ka mood/closeness batata hai"),
        ToolDefinition("get_personality_status", "Interaction count, closeness, feedback score dikhata hai"),
        ToolDefinition("remember_moment", "Baad me poochne/mention karne ke liye ek note yaad rakhta hai", listOf(ToolParam("note", "string", "e.g. 'poocho uska interview kaisa gaya'"))),
        ToolDefinition("get_pending_moments", "Saare pending 'yaad rakhe' moments list karta hai"),
        ToolDefinition("resolve_moment", "Ek moment resolve/complete mark karta hai", listOf(ToolParam("note", "string", "moment ka exact text"))),
        ToolDefinition("record_feedback", "User ka feedback (positive/negative) record karta hai", listOf(ToolParam("positive", "boolean", "\"true\"/\"false\""))),
        ToolDefinition("set_surprise_mode", "Occasional proactive check-in messages on/off karta hai", listOf(ToolParam("on", "boolean", "\"true\"/\"false\""))),
        ToolDefinition("get_recent_initiatives", "Arya ne recently kaunse proactive messages bheje hain batata hai"),
        ToolDefinition("list_capability_gaps", "Konse tools baar-baar missing config/permission ki wajah se fail ho rahe hain, list karta hai"),
        ToolDefinition("system_check", "Relay reachability, configured API keys, aur capability gaps — ek saath poora health check dikhata hai"),

        // -- custom reminders (new, distinct from set_alarm) --
        ToolDefinition("set_reminder", "Custom reminder set karta hai — one-time ya repeating (set_alarm se alag, silent WorkManager-based)", listOf(
            ToolParam("name", "string", "e.g. 'paani piyo'"), ToolParam("message", "string", "reminder text"),
            ToolParam("delay_minutes", "number", "one-time ke liye, e.g. 30"), ToolParam("repeat_every_minutes", "number", "optional, e.g. 120 for har 2 ghante")
        )),
        ToolDefinition("list_reminders", "Saare active reminders list karta hai"),
        ToolDefinition("cancel_reminder", "Ek reminder cancel karta hai", listOf(ToolParam("name", "string", "reminder ka naam"))),

        // -- Antargati: priority-mismatch tracker --
        ToolDefinition("set_priorities", "User ki life priorities ranked order me set karta hai (pehli = sabse zaroori)", listOf(
            ToolParam("priorities", "string", "comma-separated, order matters, e.g. 'parivar, sehat, career'")
        )),
        ToolDefinition("list_priorities", "Abhi set ki hui priorities unke rank ke saath dikhata hai"),
        ToolDefinition("log_time", "Aaj ka kuch samay ek priority ko darj karta hai", listOf(
            ToolParam("priority", "string", "priorities me se ek ka naam"),
            ToolParam("hours", "number", "e.g. 2.5"),
            ToolParam("note", "string", "optional, aaj ka ek pal")
        )),
        ToolDefinition("get_priority_report", "Pichle N din me chaha gaya samay vs asal me mila samay compare karta hai, sabse bada mismatch batata hai", listOf(
            ToolParam("days", "number", "optional, default 7")
        )),

        // -- Memory Continuity: family stories/memories --
        ToolDefinition("add_family_memory", "Ek family story/yaad save karta hai, kis vyakti ki hai uske saath", listOf(
            ToolParam("person", "string", "e.g. 'Dadi', 'Papa'"),
            ToolParam("title", "string", "chhota title"),
            ToolParam("story", "string", "poori kahani/yaad")
        )),
        ToolDefinition("list_family_memories", "Saari (ya ek vyakti ki) saved yaadein list karta hai", listOf(
            ToolParam("person", "string", "optional — sirf isi vyakti ki yaadein")
        )),
        ToolDefinition("recall_family_memory", "Ek random purani yaad sunata hai (jis vyakti ki hai, uska naam bata kar)", listOf(
            ToolParam("person", "string", "optional — sirf isi vyakti ki yaadon me se")
        )),

        // -- Life Simulator --
        ToolDefinition("get_decision_context", "User ki ranked priorities aur pichle 14 din ka samay-mismatch data deta hai, bade faisle (job/city/paisa) discuss karte waqt use karne ke liye")
    )

    /**
     * Tiny "always usable" set — kept in the prompt even when nothing in the user's
     * message matches a specific tool, so plain conversation / basic math / recall
     * still works without paying the cost of listing all 109 tools every time.
     */
    private val CORE_TOOL_NAMES = setOf(
        "get_current_time", "calculate", "remember", "recall", "web_search"
    )

    /**
     * Hand-picked Hinglish/English synonyms per tool, on top of the tool's own
     * underscore-split name tokens. Only needed for tools whose name alone
     * wouldn't naturally show up in how a user actually phrases the request
     * (e.g. "gaana lagao" → find_and_play, "mausam" → get_weather).
     */
    private val TOOL_SYNONYMS: Map<String, List<String>> = mapOf(
        "get_weather" to listOf("weather", "mausam", "temperature", "garmi", "sardi", "barish", "rain"),
        "get_crypto_price" to listOf("crypto", "bitcoin", "coin price"),
        "convert_currency" to listOf("currency", "rupee", "dollar", "paisa", "exchange rate"),
        "get_dictionary" to listOf("meaning", "matlab"),
        "translate_text" to listOf("translate", "anuvad", "tarjuma"),
        "get_wikipedia_summary" to listOf("wikipedia", "kya hai"),
        "get_country_info" to listOf("desh", "country info", "capital", "population"),
        "get_sunrise_sunset" to listOf("sunrise", "sunset", "suraj"),
        "get_public_holidays" to listOf("holiday", "chutti", "chuttiyan"),
        "ask_wolfram" to listOf("solve", "equation", "derivative", "integral"),
        "web_search" to listOf("search karo", "khojo", "dhundo", "google", "github", "documentation dekho", "docs check karo", "library check karo"),
        "scrape_webpage" to listOf("webpage", "website kholo", "repo dekho", "github kholo"),
        "smart_search" to listOf("dhundo"),
        "deep_research" to listOf("deep research", "detailed research", "gehraai se research", "poori research", "acchi tarah research", "research report", "vistrit jaankari"),
        "remember" to listOf("yaad rakho", "yaad rakh", "note kar lo"),
        "recall" to listOf("yaad hai kya", "kya tha"),
        "list_memories" to listOf("sab yaad rakhi", "memories dikhao"),
        "forget" to listOf("bhula do", "yaad se hatao"),
        "add_todo" to listOf("todo add", "kaam add", "task add"),
        "list_todos" to listOf("todo dikhao", "kaam list"),
        "complete_todo" to listOf("todo complete", "kaam ho gaya"),
        "delete_todo" to listOf("todo hatao"),
        "activate_persona" to listOf("persona", "character ban jao", "role play", "acting karo"),
        "deactivate_persona" to listOf("persona hatao", "normal ho jao"),
        "vibrate" to listOf("vibrate", "kampan"),
        "get_battery_status" to listOf("battery", "charge kitna"),
        "send_notification" to listOf("notification bhejo"),
        "set_alarm" to listOf("alarm laga", "jagao", "wake me"),
        "make_call" to listOf("call karo", "phone karo", "dial karo"),
        "send_sms" to listOf("sms bhejo", "message bhejo", "text bhejo"),
        "open_app" to listOf("app kholo", "open app"),
        "open_app_by_name" to listOf("kholo"),
        "get_location" to listOf("location batao", "kaha hun", "gps"),
        "toggle_torch" to listOf("torch", "flashlight", "batti jalao", "batti bujhao", "light on", "light off"),
        "open_wifi_settings" to listOf("wifi"),
        "open_bluetooth_settings" to listOf("bluetooth"),
        "open_dnd_settings" to listOf("dnd", "do not disturb", "silent mode"),
        "adjust_volume" to listOf("volume", "awaaz"),
        "media_play_pause" to listOf("play kar", "pause kar", "gaana"),
        "media_next" to listOf("next song", "agla gaana"),
        "media_previous" to listOf("previous song", "pichla gaana"),
        "find_contact_number" to listOf("number dhundo", "contact number"),
        "call_contact_by_name" to listOf("ko call karo", "ko phone karo"),
        "read_clipboard" to listOf("clipboard me kya hai"),
        "write_clipboard" to listOf("copy kar do"),
        "create_calendar_event" to listOf("calendar", "event bana", "meeting set karo"),
        "search_radio" to listOf("radio", "fm"),
        "search_youtube" to listOf("youtube", "video dhundo"),
        "search_videos" to listOf("video dhundo"),
        "play_stream" to listOf("stream kar", "play kar do", "radio", "radio laga", "gaana laga", "chala do"),
        "pause_stream" to listOf("pause kar do", "roko"),
        "resume_stream" to listOf("resume kar", "wapas chalao"),
        "stop_stream" to listOf("stop kar do", "band kar do"),
        "stop_all_streams" to listOf("sab band kar do"),
        "stream_status" to listOf("kya chal raha hai"),
        "find_and_play" to listOf("gaana lagao", "gana bajao", "gaana chalao", "play kar do"),
        "play_video" to listOf("video chalao", "video lagao", "video dikhao", "video play karo", "video"),
        "save_stream" to listOf("stream save karo"),
        "list_saved_streams" to listOf("saved streams dikhao"),
        "play_saved_stream" to listOf("saved stream chalao"),
        "search_images" to listOf("image dhundo", "photo dhundo", "picture dhundo"),
        "generate_image" to listOf("image banao", "photo banao", "draw karo"),
        "get_news" to listOf("news", "khabar", "headlines"),
        "morning_briefing" to listOf("briefing", "subah ka update", "good morning"),
        "reverse_geocode" to listOf("address batao"),
        "search_place_osm" to listOf("jagah dhundo", "place dhundo"),
        "get_current_mood" to listOf("mood kaisa hai", "tera mood"),
        "get_personality_status" to listOf("kitna kareeb", "closeness"),
        "remember_moment" to listOf("yaad dila dena", "baad me poochna"),
        "set_reminder" to listOf("reminder laga do", "yaad dila dena", "remind me"),
        "list_reminders" to listOf("reminders dikhao"),
        "cancel_reminder" to listOf("reminder hatao")
    )

    /**
     * Picks only the tools likely relevant to [userQuery] instead of dumping all
     * [ALL_TOOLS] (109 of them) into the system prompt on every single message.
     * Every prompt token costs latency and (for rate-limited free-tier online models)
     * quota, so a smaller, query-relevant tool list means noticeably faster, cheaper
     * responses. A tiny [CORE_TOOL_NAMES] set always stays
     * available so plain conversation doesn't break just because nothing
     * matched. Result is capped at [maxTools] to keep prompt size predictable.
     */
    fun relevantTools(userQuery: String, maxTools: Int = 10): List<ToolDefinition> {
        val q = userQuery.lowercase()
        val scored = ALL_TOOLS.map { tool ->
            var score = 0
            tool.name.split("_").forEach { token ->
                if (token.length > 2 && q.contains(token)) score += 2
            }
            TOOL_SYNONYMS[tool.name]?.forEach { syn ->
                if (q.contains(syn)) score += 3
            }
            tool to score
        }
        var matched = scored.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }

        // Bug fix (see chat history — user reported asking for a video sometimes got a text
        // apology instead of an actual video, and traced it to the model bouncing between
        // play_video/search_youtube/search_videos across several tool-call rounds): when the
        // person clearly wants to WATCH something (play_video matched) rather than get a
        // link/list (none of "link"/"dhundo"/"list"/"search" in the query), stop ALSO offering
        // the link-returning tools that overlap with it — one unambiguous tool the model can't
        // get confused about beats three similar-sounding ones it has to choose correctly
        // between every single time.
        if (matched.any { it.name == "play_video" } &&
            listOf("link", "dhundo", "list", "search").none { q.contains(it) }
        ) {
            matched = matched.filterNot { it.name in setOf("search_youtube", "search_videos", "play_stream", "find_and_play") }
        }

        val core = ALL_TOOLS.filter { it.name in CORE_TOOL_NAMES }
        return (matched + core).distinctBy { it.name }.take(maxTools)
    }

    /** Runs on Dispatchers.IO since almost every branch here does blocking network/IO work. */
    suspend fun execute(context: Context, call: ToolCall): String = withContext(Dispatchers.IO) {
        val validation = ToolGuard.validate(call, ALL_TOOLS)
        if (validation != null) return@withContext validation
        val a = call.args
        val timeoutMs = when (call.name) {
            "deep_research", "smart_search", "web_search", "scrape_webpage" -> 60_000L
            "generate_image" -> 120_000L
            else -> 30_000L
        }
        try {
            val result = withTimeout(timeoutMs) { when (call.name) {
                "get_current_time" -> UtilityTools.getCurrentTime()
                "calculate" -> "🧮 ${a["expression"]} = ${com.arya.ai.viewmodel.ArithmeticEvaluator.eval(a["expression"] ?: "0")}"
                "convert_units" -> UtilityTools.convertUnits(a["value"]?.toDoubleOrNull() ?: 0.0, a["from_unit"] ?: "", a["to_unit"] ?: "")
                "generate_qr" -> UtilityTools.generateQr(context, a["text"] ?: "")
                "generate_password" -> UtilityTools.generatePassword(a["length"]?.toIntOrNull() ?: 16, a["use_symbols"]?.toBoolean() ?: true)
                "text_analyzer" -> UtilityTools.textAnalyzer(a["text"] ?: "")
                "get_random_quote" -> UtilityTools.getRandomQuote()
                "generate_image" -> {
                    val prompt = a["prompt"] ?: ""
                    if (prompt.isBlank()) "❌ Kya image banau, prompt do"
                    else {
                        // Relay (Gemini) first — better quality; falls back to Pollinations
                        // (free, keyless) if relay isn't configured or the request fails, so
                        // image generation still works even without a relay set up.
                        val bitmap = ImageGenTools.generate(prompt) ?: ImageTools.fetchGeneratedBitmap(prompt)
                        if (bitmap == null) "❌ Image generate nahi ho payi (relay aur fallback dono fail)"
                        else {
                            lastGeneratedImage = bitmap
                            val file = ImageGenTools.saveToGallery(context, bitmap)
                            if (file != null) "🎨 Image ban gayi aur gallery me save ho gayi: ${file.name}"
                            else "🎨 Image ban gayi lekin save nahi ho payi"
                        }
                    }
                }
                "system_info" -> UtilityTools.systemInfo(context)

                "create_document" -> {
                    val name = a["name"]?.takeIf { it.isNotBlank() } ?: "arya-document"
                    val content = a["content"] ?: ""
                    val format = (a["format"] ?: "pdf").lowercase()
                    if (content.isBlank()) "❌ Document me daalne ke liye content do"
                    else if (format == "txt") {
                        val file = DocumentTools.createTextFile(context, name, content)
                        if (file != null) "📄 Text file ban gayi: ${file.name}" else "❌ Text file save nahi ho payi"
                    } else {
                        val file = DocumentTools.createPdf(context, name, a["title"] ?: name, content)
                        if (file != null) "📄 PDF ban gaya: ${file.name}"
                        else {
                            // PDF creation can fail (e.g. non-Latin content edge case) — fall
                            // back to a .txt so the user still gets *something* saved.
                            val fallback = DocumentTools.createTextFile(context, name, content)
                            if (fallback != null) "⚠️ PDF nahi ban paya, isliye text file bana di: ${fallback.name}"
                            else "❌ Document save nahi ho paya"
                        }
                    }
                }
                "generate_chart" -> {
                    val title = a["title"] ?: "Chart"
                    val labels = (a["labels"] ?: "").split(",").map { it.trim() }.filter { it.isNotBlank() }
                    val values = (a["values"] ?: "").split(",").mapNotNull { it.trim().toDoubleOrNull() }
                    val type = a["type"] ?: "bar"
                    if (labels.isEmpty() || values.isEmpty() || labels.size != values.size)
                        "❌ Chart banane ke liye labels aur values same count me chahiye (e.g. labels='Mon,Tue', values='10,20')"
                    else {
                        val file = ChartTools.generateChart(context, title, labels, values, type)
                        if (file == null) "❌ Chart generate nahi ho paya"
                        else {
                            ChartTools.loadBitmap(file)?.let { lastGeneratedImage = it }
                            "📊 Chart ban gaya: ${file.name}"
                        }
                    }
                }

                "list_free_models" -> ModelCatalogTools.listFreeModels(context)
                "refresh_model_catalog" -> ModelCatalogTools.refreshModelCatalog(context)
                "best_model_for" -> ModelCatalogTools.bestModelFor(context, a["need"] ?: "")

                "get_weather" -> InfoApiTools.getWeather(a["city"] ?: "")
                "get_crypto_price" -> InfoApiTools.getCryptoPrice(a["coin"] ?: "bitcoin")
                "convert_currency" -> InfoApiTools.convertCurrency(a["amount"]?.toDoubleOrNull() ?: 0.0, a["from_currency"] ?: "USD", a["to_currency"] ?: "INR")
                "get_country_info" -> InfoApiTools.getCountryInfo(a["country"] ?: "")
                "get_ip_info" -> InfoApiTools.getIpInfo(a["ip"] ?: "")
                "get_dictionary" -> InfoApiTools.getDictionary(a["word"] ?: "")
                "translate_text" -> InfoApiTools.translateText(a["text"] ?: "", a["target_lang"] ?: "hi")
                "get_wikipedia_summary" -> InfoApiTools.getWikipediaSummary(a["query"] ?: "")
                "get_sunrise_sunset" -> InfoApiTools.getSunriseSunset(a["city"] ?: "")
                "get_public_holidays" -> InfoApiTools.getPublicHolidays(a["country_code"] ?: "IN", a["year"]?.toIntOrNull() ?: java.util.Calendar.getInstance().get(java.util.Calendar.YEAR))
                "get_spacex_launches" -> InfoApiTools.getSpacexLaunches(a["upcoming"]?.toBoolean() ?: false)
                "get_nasa_apod" -> InfoApiTools.getNasaApod(context)
                "get_nasa_iss_location" -> InfoApiTools.getNasaIssLocation()
                "get_nasa_asteroids" -> InfoApiTools.getNasaAsteroids(context)
                "get_nasa_mars_photos" -> InfoApiTools.getNasaMarsPhotos(context)
                "ask_wolfram" -> InfoApiTools.askWolfram(context, a["question"] ?: "")

                "web_search" -> WebTools.webSearch(context, a["query"] ?: "")
                "scrape_webpage" -> WebTools.scrapeWebpage(a["url"] ?: "", a["extract"] ?: "text")
                "smart_search" -> WebTools.smartSearch(context, a["query"] ?: "")
                "deep_research" -> DeepResearchTool.run(context, a["topic"] ?: "")

                "remember" -> MemoryStore.remember(context, a["key"] ?: "", a["value"] ?: "")
                "recall" -> MemoryStore.recall(context, a["key"] ?: "")
                "list_memories" -> MemoryStore.listMemories(context)
                "search_memories" -> MemoryStore.search(context, a["query"] ?: "")
                "forget" -> MemoryStore.forget(context, a["key"] ?: "")
                "add_todo" -> MemoryStore.addTodo(context, a["task"] ?: "", a["priority"] ?: "medium")
                "list_todos" -> MemoryStore.listTodos(context)
                "complete_todo" -> MemoryStore.completeTodo(context, a["task_id"]?.toIntOrNull() ?: -1)
                "delete_todo" -> MemoryStore.deleteTodo(context, a["task_id"]?.toIntOrNull() ?: -1)

                "activate_persona" -> PersonaStore.activatePersona(context, a["character_name"] ?: "", a["description"] ?: "", a["speaking_style"] ?: "")
                "deactivate_persona" -> PersonaStore.deactivatePersona(context)
                "get_current_persona" -> PersonaStore.getCurrentPersona(context)
                "list_saved_personas" -> PersonaStore.listSavedPersonas(context)
                "switch_to_saved_persona" -> PersonaStore.switchToSavedPersona(context, a["character_name"] ?: "")

                "vibrate" -> DeviceExtraTools.vibrate(context, a["duration_ms"]?.toLongOrNull() ?: 500L)
                "get_battery_status" -> DeviceExtraTools.getBatteryStatus(context)
                "send_notification" -> DeviceExtraTools.sendNotification(context, a["title"] ?: "Arya", a["content"] ?: "")
                "set_alarm" -> DeviceExtraTools.setAlarm(context, a["hour"]?.toIntOrNull() ?: 7, a["minute"]?.toIntOrNull() ?: 0, a["message"] ?: "Arya Alarm")
                "make_call" -> DeviceExtraTools.makeCall(context, a["phone_number"] ?: "")
                "send_sms" -> DeviceExtraTools.sendSms(context, a["phone_number"] ?: "", a["message"] ?: "")
                "open_app" -> DeviceExtraTools.openApp(context, a["package_name"] ?: "")
                "open_app_by_name" -> DeviceExtraTools.openAppByName(context, a["app_name"] ?: "")
                "get_location" -> DeviceExtraTools.getLocation(context)
                "toggle_torch" -> DeviceActions.toggleFlashlight(context, a["on"]?.toBoolean() ?: true)
                "open_wifi_settings" -> DeviceExtraTools.openWifiSettings(context)
                "open_bluetooth_settings" -> DeviceExtraTools.openBluetoothSettings(context)
                "open_dnd_settings" -> DeviceExtraTools.openDndSettings(context)
                "adjust_volume" -> DeviceExtraTools.adjustVolume(context, a["up"]?.toBoolean() ?: true)
                "media_play_pause" -> DeviceExtraTools.mediaPlayPause(context)
                "media_next" -> DeviceExtraTools.mediaNext(context)
                "media_previous" -> DeviceExtraTools.mediaPrevious(context)

                "find_contact_number" -> ExpandedDeviceTools.findContactNumber(context, a["name"] ?: "")
                "call_contact_by_name" -> ExpandedDeviceTools.callContactByName(context, a["name"] ?: "")
                "read_clipboard" -> ExpandedDeviceTools.readClipboard(context)
                "write_clipboard" -> ExpandedDeviceTools.writeClipboard(context, a["text"] ?: "")
                "create_calendar_event" -> ExpandedDeviceTools.createCalendarEvent(
                    context,
                    a["title"] ?: "",
                    a["start_millis"]?.toLongOrNull() ?: 0L,
                    a["duration_minutes"]?.toIntOrNull() ?: 60,
                    a["description"] ?: ""
                )

                "search_radio" -> StreamTools.searchRadio(a["query"] ?: "")
                "search_youtube" -> StreamTools.searchYoutube(a["query"] ?: "")
                "search_videos" -> StreamTools.searchVideos(a["query"] ?: "")
                "play_stream" -> StreamTools.playStream(context, a["url"] ?: "", a["label"] ?: (a["url"] ?: ""))
                "pause_stream" -> StreamTools.pauseStream()
                "resume_stream" -> StreamTools.resumeStream()
                "stop_stream" -> StreamTools.stopStream()
                "stop_all_streams" -> StreamTools.stopAllStreams()
                "stream_status" -> StreamTools.streamStatus()
                "find_and_play" -> captureAutoPlayVideo(StreamTools.findAndPlay(context, a["query"] ?: ""))
                "play_video" -> captureAutoPlayVideo(StreamTools.playVideo(a["query"] ?: ""))
                "test_video_source" -> StreamTools.testVideoSource(a["url"] ?: "")
                "save_stream" -> StreamTools.saveStream(context, a["name"] ?: "", a["url"] ?: "")
                "list_saved_streams" -> StreamTools.listSavedStreams(context)
                "delete_saved_stream" -> StreamTools.deleteSavedStream(context, a["name"] ?: "")
                "play_saved_stream" -> StreamTools.playSavedStream(context, a["name"] ?: "")
                "set_default_stream_quality" -> StreamTools.setDefaultStreamQuality(context, a["quality"] ?: "auto")
                "get_default_stream_quality" -> StreamTools.getDefaultStreamQuality(context)
                "list_stream_qualities" -> StreamTools.listStreamQualities()

                "search_images" -> {
                    // maxResults bumped from the tool's own default (5) to 8, and the URL-take
                    // limit from 3 to 8 — search_images previously only ever fetched/showed the
                    // first 3 thumbnails even though up to 5 were listed in the text; now the
                    // mini image-gallery (see ChatScreen's horizontal image row) has enough to
                    // actually be worth scrolling through. Fetched in parallel (was sequential)
                    // since 8 one-by-one network round-trips would otherwise stall this tool
                    // call noticeably longer than the old 3-image version did.
                    val result = ImageTools.searchImages(a["query"] ?: "", maxResults = 8)
                    val urls = Regex("""^\s+(https?://\S+)$""", RegexOption.MULTILINE)
                        .findAll(result).map { it.groupValues[1] }.take(8).toList()
                    if (urls.isNotEmpty()) {
                        lastSearchedImages = coroutineScope {
                            urls.map { async { ImageTools.fetchThumbnail(it) } }.awaitAll().filterNotNull()
                        }
                    }
                    result
                }
                "fetch_image_from_url" -> ImageTools.fetchImageFromUrl(context, a["url"] ?: "")
                "test_image_source" -> ImageTools.testImageSource(a["url"] ?: "")

                "save_site" -> SiteTools.saveSite(context, a["name"] ?: "", a["url"] ?: "")
                "list_saved_sites" -> SiteTools.listSavedSites(context)
                "delete_saved_site" -> SiteTools.deleteSavedSite(context, a["name"] ?: "")
                "play_saved_site" -> SiteTools.playSavedSite(context, a["name"] ?: "")
                "get_page_media" -> SiteTools.getPageMedia(a["url"] ?: "")
                "watch_page" -> SiteTools.watchPage(context, a["name"] ?: "", a["url"] ?: "")
                "stop_watch" -> SiteTools.stopWatch(context, a["name"] ?: "")
                "list_page_watches" -> SiteTools.listPageWatches(context)

                "get_news" -> BriefingTools.getNews(a["topic"] ?: "")
                "morning_briefing" -> BriefingTools.morningBriefing(context, a["city"])

                "reverse_geocode" -> InfoApiTools.reverseGeocode(context, a["latitude"]?.toDoubleOrNull() ?: 0.0, a["longitude"]?.toDoubleOrNull() ?: 0.0)
                "search_place_osm" -> InfoApiTools.searchPlaceOsm(a["query"] ?: "")

                "list_api_keys" -> ApiKeyTools.listApiKeys(context)
                "delete_api_key" -> ApiKeyTools.deleteApiKey(context, a["provider"] ?: "", a["key_suffix"] ?: "")

                "get_current_mood" -> PersonalityStore.getCurrentMood(context)
                "get_personality_status" -> PersonalityStore.getPersonalityStatusText(context)
                "remember_moment" -> PersonalityStore.rememberMoment(context, a["note"] ?: "")
                "get_pending_moments" -> PersonalityStore.getPendingMoments(context)
                "resolve_moment" -> PersonalityStore.resolveMoment(context, a["note"] ?: "")
                "record_feedback" -> PersonalityStore.recordFeedback(context, a["positive"]?.toBoolean() ?: true)
                "set_surprise_mode" -> PersonalityStore.setSurpriseMode(context, a["on"]?.toBoolean() ?: true)
                "get_recent_initiatives" -> PersonalityStore.getRecentInitiatives(context)
                "list_capability_gaps" -> CuriosityStore.listGaps(context)
                "system_check" -> ApiKeyTools.systemCheck(context)

                "set_reminder" -> com.arya.ai.worker.ReminderTools.setReminder(
                    context, a["name"] ?: "reminder", a["message"] ?: "", a["delay_minutes"]?.toLongOrNull() ?: 0L, a["repeat_every_minutes"]?.toLongOrNull() ?: 0L
                )
                "list_reminders" -> com.arya.ai.worker.ReminderTools.listReminders(context)
                "cancel_reminder" -> com.arya.ai.worker.ReminderTools.cancelReminder(context, a["name"] ?: "")

                "set_priorities" -> PriorityStore.setPriorities(
                    context,
                    (a["priorities"] ?: "").split(",").map { it.trim() }.filter { it.isNotBlank() }
                )
                "list_priorities" -> PriorityStore.listPriorities(context)
                "log_time" -> PriorityStore.logTime(
                    context,
                    a["priority"] ?: "",
                    a["hours"]?.toDoubleOrNull() ?: 0.0,
                    a["note"]
                )
                "get_priority_report" -> PriorityStore.getReport(context, a["days"]?.toIntOrNull() ?: 7)

                "add_family_memory" -> FamilyMemoryStore.addMemory(context, a["person"] ?: "", a["title"] ?: "", a["story"] ?: "")
                "list_family_memories" -> FamilyMemoryStore.listMemories(context, a["person"])
                "recall_family_memory" -> FamilyMemoryStore.recallSpoken(context, a["person"])

                "get_decision_context" -> LifeSimulator.buildContext(context)

                else -> "🤔 Tool '${call.name}' pehchana nahi gaya."
            } }
            logCapabilityGap(context, call.name, result)
            captureNowPlaying(call.name, result)
            ToolGuard.capResult(captureAutoPlayVideo(result))
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            "❌ Tool '${call.name}' ka time out ho gaya. Thodi der baad dobara try karo."
        } catch (e: Exception) {
            "⚠️ '${call.name}' chalate waqt error: ${e.message}"
        }
    }

    /**
     * Phase 5 of the "advanced tools" upgrade (see chat history) — feeds [CuriosityStore]
     * whenever a tool's own error text points at a *fixable* gap (missing relay config, missing
     * permission) rather than an ordinary "not found"/parse-failure. [ReflectionWorker] reads
     * these later and turns repeat hits into one concrete question for Sudhanshu instead of
     * Arya silently failing the same way forever.
     */
    private fun logCapabilityGap(context: Context, toolName: String, result: String) {
        if (!result.startsWith("❌")) return
        val reason = when {
            result.contains("relay", ignoreCase = true) -> "relay_not_configured"
            result.contains("permission", ignoreCase = true) -> "permission_missing"
            result.contains("API key", ignoreCase = true) || result.contains("configur", ignoreCase = true) -> "missing_config"
            else -> return
        }
        CuriosityStore.logGap(context, toolName, reason)
    }

    /**
     * Phase 1 of the "advanced tools" upgrade (see chat history) — Arya's own capability
     * self-model. Generated straight from [ALL_TOOLS] instead of being hand-written text, so it
     * can NEVER go stale: naya tool [ALL_TOOLS] me add hote hi ye automatically usko bhi
     * describe karne lagega, kisi doosri jagah update karne ki zaroorat nahi.
     *
     * Grouped by rough category (matches the `// -- comment --` section markers already in
     * [ALL_TOOLS]) purely for prompt readability — the grouping itself is just a heading split
     * on tool-name prefixes/keywords, not a second source of truth.
     */
    fun capabilitySelfModel(): String {
        val names = ALL_TOOLS.joinToString("; ") { it.name }
        return "Tumhare paas ${ALL_TOOLS.size} tools hain jinse tum real duniya me actions le " +
            "sakti ho (radio/music bajana, weather/news/crypto batana, image banana, reminders " +
            "set karna, device control, memory me cheeze yaad rakhna, aur bahut kuch). Poori " +
            "list: $names. Jab koi request in tools se solve ho sakti ho, tool call karo — " +
            "guess mat karo. Agar koi cheez tumhare current tools se possible nahi hai (jaise " +
            "missing API key ya missing permission ki wajah se), to seedha, curious tareeke se " +
            "Sudhanshu ko bata do ki wo feature unlock karne ke liye kya chahiye. Tum har 6 " +
            "ghante ek chhota background check bhi chalati ho jo dekhta hai koi tool baar-baar " +
            "isi wajah se fail to nahi ho raha — agar hota hai to khud ek notification bhej " +
            "kar poochti ho, taaki wahi limitation baar-baar chup-chaap na ho."
    }
}

object PriorityStore {

    private fun prefs(context: Context) =
        context.getSharedPreferences("arya_priority_tracker", Context.MODE_PRIVATE)

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun dateKey(daysAgo: Int): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -daysAgo)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    // ---- priorities (set once, editable) ----

    /** Ordered array of {"name": "...", "weight": N} — first item = most important. */
    fun getPriorities(context: Context): JSONArray =
        JSONArray(prefs(context).getString("priorities", "[]") ?: "[]")

    private fun savePrioritiesRaw(context: Context, arr: JSONArray) =
        prefs(context).edit().putString("priorities", arr.toString()).apply()

    /** Replaces the whole ranked list. [orderedNames] first = most important. */
    fun setPriorities(context: Context, orderedNames: List<String>): String {
        if (orderedNames.size < 2) return "❌ Kam se kam 2 priorities do, taaki compare ho sake"
        val arr = JSONArray()
        val n = orderedNames.size
        orderedNames.forEachIndexed { i, name ->
            arr.put(JSONObject().apply {
                put("name", name.trim())
                put("weight", n - i) // first = highest weight
            })
        }
        savePrioritiesRaw(context, arr)
        return "🪔 Priorities set ho gayin (${orderedNames.size}): " +
            orderedNames.mapIndexed { i, n2 -> "${i + 1}. $n2" }.joinToString(", ")
    }

    fun listPriorities(context: Context): String {
        val arr = getPriorities(context)
        if (arr.length() == 0) return "❌ Abhi koi priority set nahi hai — pehle set_priorities use karo"
        val lines = (0 until arr.length()).map {
            val p = arr.getJSONObject(it)
            "${it + 1}. ${p.getString("name")}"
        }
        return "🪔 Tumhari priorities:\n" + lines.joinToString("\n")
    }

    // ---- daily check-ins ----

    /** One day's entry: {"date": "...", "hours": {"parivar": 2.5, ...}, "note": "..."} */
    private fun readCheckins(context: Context): JSONObject =
        JSONObject(prefs(context).getString("checkins", "{}") ?: "{}")

    private fun writeCheckins(context: Context, obj: JSONObject) =
        prefs(context).edit().putString("checkins", obj.toString()).apply()

    /** Most recent date (yyyy-MM-dd) this device has a check-in for — null if none yet. Used
     *  by Family Pulse as an activity signal, not a health/mood measurement (see that screen's
     *  doc comment). */
    fun getLastCheckinDate(context: Context): String? {
        val all = readCheckins(context)
        val keys = all.keys().asSequence().toList()
        return keys.maxOrNull()
    }

    /** How many of the last [days] calendar days have a check-in — the raw count Family Pulse's
     *  Sudden-Change Detection needs to tell "was checking in almost daily, then abruptly
     *  stopped" apart from "was already sporadic". Only the count crosses to Firebase (see
     *  [com.arya.ai.util.FirebaseSync.updateMyCircleSignal]'s doc comment) — never which
     *  specific days. */
    fun getRecentCheckinCount(context: Context, days: Int = 14): Int {
        val all = readCheckins(context)
        val recentDates = (0 until days).map { dateKey(it) }.toSet()
        return all.keys().asSequence().count { it in recentDates }
    }

    /**
     * Logs today's hours for one priority (adds to any hours already logged today for it,
     * so the person can call this a few times through the day instead of only once at night).
     */
    fun logTime(context: Context, priorityName: String, hours: Double, note: String? = null): String {
        val priorities = getPriorities(context)
        val known = (0 until priorities.length()).map { priorities.getJSONObject(it).getString("name") }
        val match = known.firstOrNull { it.equals(priorityName.trim(), ignoreCase = true) }
            ?: return "❌ '$priorityName' priorities me nahi mili. Pehle list_priorities check karo."

        val all = readCheckins(context)
        val key = todayKey()
        val today = all.optJSONObject(key) ?: JSONObject().put("date", key).put("hours", JSONObject())
        val hoursObj = today.getJSONObject("hours")
        val existing = hoursObj.optDouble(match, 0.0)
        hoursObj.put(match, existing + hours)
        if (!note.isNullOrBlank()) today.put("note", note)
        all.put(key, today)
        writeCheckins(context, all)
        return "🪔 Aaj '$match' me ${existing + hours} ghante darj ho gaye"
    }

    /** Human-readable comparison of stated importance vs actual time given, last [days] days. */
    fun getReport(context: Context, days: Int = 7): String {
        val priorities = getPriorities(context)
        if (priorities.length() == 0) return "❌ Abhi koi priority set nahi hai — pehle set_priorities use karo"

        val totalWeight = (0 until priorities.length()).sumOf { priorities.getJSONObject(it).getInt("weight") }
        val all = readCheckins(context)
        val sums = LinkedHashMap<String, Double>()
        (0 until priorities.length()).forEach { sums[priorities.getJSONObject(it).getString("name")] = 0.0 }

        var loggedDays = 0
        var grandTotal = 0.0
        for (i in 0 until days) {
            val entry = all.optJSONObject(dateKey(i)) ?: continue
            loggedDays++
            val hoursObj = entry.optJSONObject("hours") ?: continue
            hoursObj.keys().forEach { name ->
                val h = hoursObj.optDouble(name, 0.0)
                sums[name] = (sums[name] ?: 0.0) + h
                grandTotal += h
            }
        }

        if (loggedDays == 0) return "🪔 Pichle $days din me koi check-in darj nahi hua"

        val lines = mutableListOf<String>()
        var biggestGapName = ""
        var biggestGap = 0.0
        (0 until priorities.length()).forEach { i ->
            val p = priorities.getJSONObject(i)
            val name = p.getString("name")
            val wantedPct = (p.getInt("weight") * 100.0 / totalWeight)
            val gotPct = if (grandTotal > 0) (sums[name] ?: 0.0) * 100.0 / grandTotal else 0.0
            val gap = wantedPct - gotPct
            if (kotlin.math.abs(gap) > kotlin.math.abs(biggestGap)) {
                biggestGap = gap
                biggestGapName = name
            }
            lines.add("• $name — chaha ${"%.0f".format(wantedPct)}%, mila ${"%.0f".format(gotPct)}%")
        }

        val insight = if (kotlin.math.abs(biggestGap) < 8) {
            "✅ Overall tumhara samay tumhari priorities se kaafi mel khaata hai."
        } else if (biggestGap > 0) {
            "⚠️ '$biggestGapName' ko jitna zaroori bataya tha, utna samay nahi mil raha (~${"%.0f".format(biggestGap)}% kam)."
        } else {
            "⚠️ '$biggestGapName' me socha tha usse kaafi zyada samay ja raha hai (~${"%.0f".format(-biggestGap)}% zyada)."
        }

        return "🪔 Pichle $days din ka mismatch report ($loggedDays din darj hue):\n" +
            lines.joinToString("\n") + "\n\n$insight"
    }

    /**
     * Priority Weather Forecast — looks across ALL stored check-ins (not just a recent window
     * like [getReport]) for a weekday where one priority consistently gets shortchanged, e.g.
     * "Sundays, health always loses out." Pure local statistics, no LLM call — this is
     * pattern-detection over the person's own logged hours, not a prediction from any external
     * model. Needs at least [MIN_DAYS_PER_WEEKDAY] check-ins on a given weekday before it'll
     * surface anything for that weekday, so a couple of unusual days don't get over-read as a
     * "pattern". Returns null if no priorities are set, no check-ins exist yet, or no weekday
     * clears that minimum-data bar with a big-enough (>15 percentage point) average gap.
     */
    private const val MIN_DAYS_PER_WEEKDAY = 3
    private val HINDI_WEEKDAY_NAMES = mapOf(
        java.util.Calendar.SUNDAY to "रविवार", java.util.Calendar.MONDAY to "सोमवार",
        java.util.Calendar.TUESDAY to "मंगलवार", java.util.Calendar.WEDNESDAY to "बुधवार",
        java.util.Calendar.THURSDAY to "गुरुवार", java.util.Calendar.FRIDAY to "शुक्रवार",
        java.util.Calendar.SATURDAY to "शनिवार"
    )

    fun getWeekdayForecast(context: Context): String? {
        val priorities = getPriorities(context)
        if (priorities.length() == 0) return null
        val totalWeight = (0 until priorities.length()).sumOf { priorities.getJSONObject(it).getInt("weight") }

        val all = readCheckins(context)
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        // weekday (Calendar.SUNDAY..SATURDAY) -> (priority name -> summed hours), plus per-weekday total hours and day-count
        val sumsByWeekday = HashMap<Int, MutableMap<String, Double>>()
        val totalByWeekday = HashMap<Int, Double>()
        val daysByWeekday = HashMap<Int, Int>()

        all.keys().forEach { key ->
            val entry = all.optJSONObject(key) ?: return@forEach
            val hoursObj = entry.optJSONObject("hours") ?: return@forEach
            val date = try { dateFmt.parse(key) } catch (e: Exception) { null } ?: return@forEach
            val cal = java.util.Calendar.getInstance()
            cal.time = date
            val wd = cal.get(java.util.Calendar.DAY_OF_WEEK)
            daysByWeekday[wd] = (daysByWeekday[wd] ?: 0) + 1
            val sums = sumsByWeekday.getOrPut(wd) { mutableMapOf() }
            hoursObj.keys().forEach { name ->
                val h = hoursObj.optDouble(name, 0.0)
                sums[name] = (sums[name] ?: 0.0) + h
                totalByWeekday[wd] = (totalByWeekday[wd] ?: 0.0) + h
            }
        }

        var worstWeekday: Int? = null
        var worstPriority: String? = null
        var worstGap = 15.0 // threshold — nothing below this counts as a "pattern" worth surfacing

        daysByWeekday.forEach { (wd, dayCount) ->
            if (dayCount < MIN_DAYS_PER_WEEKDAY) return@forEach
            val total = totalByWeekday[wd] ?: return@forEach
            if (total <= 0) return@forEach
            val sums = sumsByWeekday[wd] ?: return@forEach
            (0 until priorities.length()).forEach { i ->
                val p = priorities.getJSONObject(i)
                val name = p.getString("name")
                val wantedPct = p.getInt("weight") * 100.0 / totalWeight
                val gotPct = (sums[name] ?: 0.0) * 100.0 / total
                val gap = wantedPct - gotPct
                if (gap > worstGap) {
                    worstGap = gap
                    worstWeekday = wd
                    worstPriority = name
                }
            }
        }

        val wd = worstWeekday ?: return null
        val priorityName = worstPriority ?: return null
        val dayName = HINDI_WEEKDAY_NAMES[wd] ?: return null
        return "🌦️ Pattern mila: $dayName ko '$priorityName' ko sabse kam samay milta hai — " +
            "औसतन ${"%.0f".format(worstGap)}% kam. Agle $dayName ke liye pehle se plan kar sakte ho."
    }
}

/**
 * Memory Continuity — family stories/memories the user (or a family member) records, so
 * they can be resurfaced later. Built for the "help an elderly parent, or someone in the
 * early stage of memory loss, hear their own life's stories again" use-case.
 *
 * Honest limitation: this does NOT clone anyone's actual voice — that's a real, separate ML
 * capability Arya doesn't have, and voice-cloning a specific family member without their
 * explicit consent raises real consent issues anyway. What this *does* do: Arya narrates the
 * story text in her own voice (same [com.arya.ai.util.VoiceHelper] TTS as everywhere else in
 * the app), while always naming whose story it is ("ye kahani Dadi ne batayi thi") — so the
 * *content* and the person it's tied to persist, even though the voice itself is Arya's.
 *
 * Uses [com.arya.ai.util.SecurePrefs] (encrypted at rest) like [MemoryStore] — these are
 * personal family stories, same sensitivity class as the facts `remember` stores.
 */
data class FamilyMemory(
    val id: Int, val person: String, val title: String, val story: String, val addedAt: Long,
    // Stored as a file path (see FamilyMemoryStore.savePhoto's doc comment), not embedded in
    // this JSON — SharedPreferences/EncryptedSharedPreferences aren't meant for large blobs.
    // Note for BackupManager: this means a memory's TEXT backs up, but its photo FILE doesn't
    // travel with a JSON backup — a known, accepted gap for now, not silently unhandled.
    val photoPath: String? = null
)

object FamilyMemoryStore {

    private fun prefs(context: Context) = com.arya.ai.util.SecurePrefs.get(context, "arya_family_memories")

    private fun readAll(context: Context): JSONArray =
        JSONArray(prefs(context).getString("memories", "[]") ?: "[]")

    private fun writeAll(context: Context, arr: JSONArray) =
        prefs(context).edit().putString("memories", arr.toString()).apply()

    /** Saves a photo permanently under the app's own private files dir (never needs storage
     *  permission, survives until the app is uninstalled) — same "save into an app-private
     *  folder, return the File" shape as [com.arya.ai.tools.ImageGenTools.saveToGallery], just
     *  internal storage instead of the public Pictures folder since this photo is personal, not
     *  meant to show up in the phone's gallery app. */
    fun savePhoto(context: Context, bitmap: android.graphics.Bitmap): java.io.File? = try {
        val dir = java.io.File(context.filesDir, "family_memory_photos").apply { mkdirs() }
        val file = java.io.File(dir, "memory_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out) }
        file
    } catch (e: Exception) {
        null
    }

    fun addMemory(context: Context, person: String, title: String, story: String, photoPath: String? = null): String {
        if (person.isBlank() || story.isBlank()) return "❌ Person ka naam aur kahani dono chahiye"
        val arr = readAll(context)
        val nextId = (0 until arr.length()).maxOfOrNull { arr.getJSONObject(it).optInt("id") } ?: 0
        val item = JSONObject().apply {
            put("id", nextId + 1)
            put("person", person.trim())
            put("title", title.ifBlank { "Ek yaad" })
            put("story", story.trim())
            put("addedAt", System.currentTimeMillis())
            if (photoPath != null) put("photoPath", photoPath)
        }
        arr.put(item)
        writeAll(context, arr)
        return "🪔 '${person.trim()}' ki ek yaad save ho gayi: \"${title.ifBlank { "Ek yaad" }}\""
    }

    fun deleteMemory(context: Context, id: Int): String {
        val arr = readAll(context)
        val kept = JSONArray()
        var found = false
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optInt("id") == id) {
                found = true
                // Clean up the photo file too — otherwise deleting a memory leaves an orphaned
                // image behind forever, slowly eating storage.
                obj.optString("photoPath").takeIf { it.isNotBlank() }?.let { path ->
                    try { java.io.File(path).delete() } catch (e: Exception) {}
                }
                continue
            }
            kept.put(obj)
        }
        if (!found) return "❌ Ye yaad nahi mili"
        writeAll(context, kept)
        return "🗑️ Yaad hata di gayi"
    }

    private fun toMemory(obj: JSONObject): FamilyMemory = FamilyMemory(
        id = obj.optInt("id"),
        person = obj.optString("person"),
        title = obj.optString("title"),
        story = obj.optString("story"),
        addedAt = obj.optLong("addedAt"),
        photoPath = obj.optString("photoPath").ifBlank { null }
    )

    fun getAll(context: Context, person: String? = null): List<FamilyMemory> {
        val arr = readAll(context)
        val all = (0 until arr.length()).map { toMemory(arr.getJSONObject(it)) }
        return if (person.isNullOrBlank()) all else all.filter { it.person.equals(person, ignoreCase = true) }
    }

    fun listPeople(context: Context): List<String> =
        getAll(context).map { it.person }.distinct()

    fun listMemories(context: Context, person: String? = null): String {
        val list = getAll(context, person)
        if (list.isEmpty()) return "❌ Abhi koi yaad save nahi hai"
        return "🪔 ${if (person.isNullOrBlank()) "Saari" else "$person ki"} yaadein:\n" +
            list.joinToString("\n") { "• ${it.title} (${it.person})" }
    }

    /** Random memory, optionally filtered to one person — used both by the `recall_family_memory`
     *  tool and the screen's "ek yaad sunao" button, so voice command and tap-to-play stay in sync. */
    fun getRandomMemory(context: Context, person: String? = null): FamilyMemory? =
        getAll(context, person).randomOrNull()

    fun recallSpoken(context: Context, person: String? = null): String {
        val mem = getRandomMemory(context, person) ?: return "❌ ${if (person.isNullOrBlank()) "Abhi koi yaad save nahi hai" else "'$person' ki koi yaad nahi mili"}"
        return "🪔 Ye kahani ${mem.person} ne batayi thi — \"${mem.title}\":\n${mem.story}"
    }

    // ---- cloned voice per person (Fish Audio voice_id, see VoiceHelper.speakClonedVoice) ----

    private fun readVoiceMap(context: Context): JSONObject =
        JSONObject(prefs(context).getString("voice_ids", "{}") ?: "{}")

    fun setPersonVoiceId(context: Context, person: String, voiceId: String) {
        val map = readVoiceMap(context)
        map.put(person.trim(), voiceId)
        prefs(context).edit().putString("voice_ids", map.toString()).apply()
    }

    /** Null if this person has no cloned voice yet — caller falls back to Arya's own voice. */
    fun getPersonVoiceId(context: Context, person: String): String? {
        val map = readVoiceMap(context)
        val id = map.optString(person.trim(), "")
        return id.ifBlank { null }
    }
}

/**
 * Life Simulator — grounds a big-decision question ("job chhodun?", "city shift karun?") in
 * the user's own [PriorityStore] data, so Arya's answer references their actual stated
 * priorities and real logged time-mismatch instead of generic advice.
 *
 * Two ways this gets used:
 *  1. As a tool ([AryaToolRegistry]'s `get_decision_context`) inside an ongoing chat — Arya's
 *     main model calls it, gets this context back, and reasons about the decision itself in
 *     its normal reply. No second LLM call needed; this just supplies grounding data.
 *  2. As a one-shot system prompt (`buildSystemPrompt`) for the dedicated Life Simulator
 *     screen, which has no ongoing conversation and calls [com.arya.ai.util.OnlineChatHelper]
 *     directly.
 */
object LifeSimulator {

    /** Plain grounding text: ranked priorities + last 14 days' chaha-vs-mila. */
    fun buildContext(context: Context): String {
        val priorities = PriorityStore.getPriorities(context)
        if (priorities.length() == 0) {
            return "User ne abhi apni priorities set nahi ki hain (Antargati feature)."
        }
        val rankedList = (0 until priorities.length()).joinToString(", ") {
            "${it + 1}. ${priorities.getJSONObject(it).getString("name")}"
        }
        val report = PriorityStore.getReport(context, 14)
        return "User ki ranked life priorities: $rankedList\n\nPichle 14 din ka samay-mismatch data:\n$report"
    }

    fun buildSystemPrompt(context: Context, category: String? = null): String {
        val trackRecord = category?.let { DecisionLog.getTrackRecordSummary(context, it) }
        val trackRecordLine = if (trackRecord != null) "\n\nTumhara apna track record is category me: $trackRecord" +
            " Ise dhyan me rakh kar salaah do — agar pichli salaah ulti disha me gayi thi, to isi tarah ki " +
            "salaah dobara dene se pehle soch lo ki kya farq karna chahiye." else ""
        return "Tum Arya ho, ek life-decisions me sochne-samajhne me madad karne wali saathi. User " +
            "ek bada faisla soch rahe hain (job, city, paisa, rishta, vagairah). Neeche unki " +
            "apni priorities aur pichle 14 din ka data diya hai — ISI data ke aadhar par jawaab " +
            "do, generic advice mat do. Jahan data kam pade, saaf bolo ki ye ek estimate hai, " +
            "guarantee nahi. Chhote paragraphs me: (1) is faisle ka unki top priorities par kya " +
            "asar ho sakta hai, (2) ek-do cheezein jo unhe khud sochni chahiye, (3) ek chhota " +
            "spasht sujhaav.\n\n${buildContext(context)}$trackRecordLine"
    }

    private val FUTURE_MARKER = Regex("""FUTURE_\d:""")

    /** Branching Future Selves — instead of one answer, 3 short first-person "ek saal baad"
     *  narrations (haan kiya / nahi kiya / beech ka raasta), grounded in the same priority
     *  data. Deliberately labeled as guesses (see the prompt's own "guess/estimate saaf
     *  bataye" instruction) — this dramatizes the SAME uncertain estimate [buildSystemPrompt]
     *  already gives in prose, not a claim of predicting the actual future. */
    fun buildBranchingPrompt(context: Context, question: String): String =
        "Tum Arya ho. User ek faisla soch raha hai: \"$question\". Unki priorities aur data:\n" +
            "${buildContext(context)}\n\n3 alag 'ek saal baad' scenarios likho — jaise woh khud " +
            "apne bhavishya se bol rahe hain, first-person me ('maine ye faisla liya...'). Har " +
            "ek sirf 3-4 chhoti lines ka ho, unki hi priority-data ke pattern par based — saaf " +
            "bolo ye ek guess/estimate hai, guarantee nahi. EXACT is format me jawaab do, kuch " +
            "aur mat likho:\nFUTURE_1:\n<agar ye faisla liya, first-person>\nFUTURE_2:\n<agar " +
            "nahi liya, first-person>\nFUTURE_3:\n<agar beech ka/aadha-adhura raasta liya, " +
            "first-person>"

    data class FutureBranch(val label: String, val text: String)

    private val BRANCH_LABELS = listOf("अगर हां किया", "अगर नहीं किया", "बीच का रास्ता")

    /** Splits [buildBranchingPrompt]'s LLM reply back into 3 labeled branches. Returns an empty
     *  list (rather than a partial/malformed split) if the reply doesn't actually contain all
     *  3 markers — a screen showing "kuch samajh nahi aaya" is better than silently showing 1
     *  or 2 branches as if that were the intended 3. */
    fun parseBranches(reply: String): List<FutureBranch> {
        val parts = reply.split(FUTURE_MARKER).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size != 3) return emptyList()
        return BRANCH_LABELS.zip(parts) { label, text -> FutureBranch(label, text) }
    }
}

/**
 * "Apne Past Self se baatcheet" — every Life Simulator question gets logged (with a category
 * tag the person picks) alongside a snapshot of their priority-mismatch report at that moment.
 * Later, asking about the same category resurfaces the old answer — optionally narrated in
 * the person's OWN cloned voice (reuses [FamilyMemoryStore]'s voice-id store, keyed under
 * whatever name they recorded themselves as, e.g. "Main" — same mechanism as a family member's
 * voice, just applied to yourself).
 */
val DECISION_CATEGORIES = listOf("Job/Career", "Ghar/City", "Paisa", "Rishta", "सेहत", "अन्य")

/** `verdict` is null until [DecisionLog.recordVerdict] is called — one of "sahi_disha",
 *  "koi_badlav_nahi", "ulta_hua". `verdictNote` is a short human-readable reason. */
data class Decision(
    val id: Int, val category: String, val question: String, val answer: String,
    val priorityShapshot: String, val timestampMs: Long,
    val verdict: String? = null, val verdictNote: String? = null
)

object DecisionLog {

    /** Below this age, evaluating an outcome is mostly noise — priority data hasn't had time
     *  to move. The screen hides the "check outcome" action until a decision passes this. */
    const val MIN_AGE_DAYS_FOR_EVALUATION = 14

    private fun prefs(context: Context) = com.arya.ai.util.SecurePrefs.get(context, "arya_decision_log")

    private fun readAll(context: Context): JSONArray =
        JSONArray(prefs(context).getString("decisions", "[]") ?: "[]")

    private fun writeAll(context: Context, arr: JSONArray) =
        prefs(context).edit().putString("decisions", arr.toString()).apply()

    fun save(context: Context, category: String, question: String, answer: String): Int {
        val arr = readAll(context)
        val nextId = (0 until arr.length()).maxOfOrNull { arr.getJSONObject(it).optInt("id") } ?: 0
        val snapshot = PriorityStore.getReport(context, 14)
        val item = JSONObject().apply {
            put("id", nextId + 1)
            put("category", category)
            put("question", question)
            put("answer", answer)
            put("priorityShapshot", snapshot)
            put("timestampMs", System.currentTimeMillis())
        }
        arr.put(item)
        writeAll(context, arr)
        return nextId + 1
    }

    private fun toDecision(obj: JSONObject) = Decision(
        id = obj.optInt("id"),
        category = obj.optString("category"),
        question = obj.optString("question"),
        answer = obj.optString("answer"),
        priorityShapshot = obj.optString("priorityShapshot"),
        timestampMs = obj.optLong("timestampMs"),
        verdict = obj.optString("verdict").ifBlank { null },
        verdictNote = obj.optString("verdictNote").ifBlank { null }
    )

    /** Past decisions in the same category, newest first, EXCLUDING [excludeId] (the one just
     *  saved this turn) — so the screen can show "pehle kya socha tha" without echoing itself. */
    fun getByCategory(context: Context, category: String, excludeId: Int? = null): List<Decision> {
        val arr = readAll(context)
        return (0 until arr.length())
            .map { toDecision(arr.getJSONObject(it)) }
            .filter { it.category == category && it.id != excludeId }
            .sortedByDescending { it.timestampMs }
    }

    fun getById(context: Context, id: Int): Decision? {
        val arr = readAll(context)
        return (0 until arr.length()).map { toDecision(arr.getJSONObject(it)) }.firstOrNull { it.id == id }
    }

    /**
     * Self-Correcting Advice Loop — this doesn't compute the verdict itself (that needs
     * genuinely comparing two priority-mismatch reports and Arya's own advice text, which reads
     * better as an LLM call than brittle string-diffing). This just builds the PROMPT for that
     * call; [ui.LifeSimulatorScreen] makes the actual [com.arya.ai.util.OnlineChatHelper] call
     * and passes the result back to [recordVerdictFromLlmReply] — same "data layer builds
     * prompts, UI layer makes the network call" split as [LifeSimulator.buildSystemPrompt].
     */
    fun evaluationPrompt(context: Context, decision: Decision): String {
        val currentReport = PriorityStore.getReport(context, 14)
        return "Tum ek imandaar fact-checker ho. Neeche ek purani salaah aur us waqt ka " +
            "priority-mismatch data, phir aaj ka priority-mismatch data diya hai. Batao ye " +
            "salaah maanne ki disha me cheezein badli ya nahi — ye judgement ki baat nahi, " +
            "sirf data compare karo.\n\n" +
            "Purani salaah: \"${decision.answer}\"\n\n" +
            "Us waqt ka data:\n${decision.priorityShapshot}\n\n" +
            "Aaj ka data:\n$currentReport\n\n" +
            "SIRF is format me jawaab do, kuch aur nahi:\n" +
            "LINE 1: sirf ek tag — sahi_disha, koi_badlav_nahi, ya ulta_hua\n" +
            "LINE 2: ek chhota (15-20 shabdo ka) Hindi/Hinglish reason"
    }

    /** Parses the LLM's evaluationPrompt reply and saves it against [decisionId]. Returns false
     *  (and saves nothing) if the reply doesn't match the expected 2-line shape — a malformed
     *  reply shouldn't silently store a wrong verdict. */
    fun recordVerdictFromLlmReply(context: Context, decisionId: Int, llmReply: String): Boolean {
        val lines = llmReply.trim().lines().map { it.trim() }.filter { it.isNotBlank() }
        val tag = lines.getOrNull(0)?.lowercase()?.filter { it.isLetter() || it == '_' }
        if (tag !in setOf("sahi_disha", "koi_badlav_nahi", "ulta_hua")) return false
        val note = lines.getOrNull(1) ?: ""
        val arr = readAll(context)
        var found = false
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optInt("id") == decisionId) {
                obj.put("verdict", tag)
                obj.put("verdictNote", note)
                found = true
                break
            }
        }
        if (found) writeAll(context, arr)
        return found
    }

    /** Short summary of how past advice in [category] actually panned out, e.g. "Pichli 3 me se
     *  2 salaah sahi disha me gayin, 1 me koi badlav nahi hua." Null if nothing's been evaluated
     *  yet. Folded into [LifeSimulator.buildSystemPrompt] so NEW advice is grounded in Arya's
     *  own track record for this category, not just the current snapshot. */
    fun getTrackRecordSummary(context: Context, category: String): String? {
        val evaluated = getByCategory(context, category).filter { it.verdict != null }
        if (evaluated.isEmpty()) return null
        val sahi = evaluated.count { it.verdict == "sahi_disha" }
        val koi = evaluated.count { it.verdict == "koi_badlav_nahi" }
        val ulta = evaluated.count { it.verdict == "ulta_hua" }
        val parts = mutableListOf<String>()
        if (sahi > 0) parts.add("$sahi sahi disha me gayi")
        if (koi > 0) parts.add("$koi me koi badlav nahi hua")
        if (ulta > 0) parts.add("$ulta ulti disha me gayi")
        return "Is category me pichli ${evaluated.size} evaluated salaah: ${parts.joinToString(", ")}."
    }
}

/** Local-only: which [FirebaseSync] family circle this device has joined, if any — the code
 *  and nickname live here so FamilyPulseScreen/FamilyVisionBoardScreen don't have to ask again
 *  every time they open. */
object FamilyCircleStore {
    private fun prefs(context: Context) = com.arya.ai.util.SecurePrefs.get(context, "arya_family_circle")

    fun save(context: Context, code: String, nickname: String) {
        prefs(context).edit().putString("code", code.trim()).putString("nickname", nickname.trim()).apply()
    }

    /** Null if this device hasn't joined a circle yet. */
    fun get(context: Context): Pair<String, String>? {
        val code = prefs(context).getString("code", null)
        val nickname = prefs(context).getString("nickname", null)
        return if (code.isNullOrBlank() || nickname.isNullOrBlank()) null else code to nickname
    }

    fun leave(context: Context) {
        prefs(context).edit().clear().apply()
    }
}

/**
 * Family Mediator Mode — grounds a family tension/disagreement in BOTH people's actual
 * priority-mismatch data (not just self-report), via [com.arya.ai.util.FirebaseSync]'s
 * `mediatorSummary` — a summary each person EXPLICITLY chooses to share (see
 * shareMediatorSummary's doc comment; this is opt-in, unlike Family Vision Board's single
 * auto-shared priority name). Arya's role here is explicitly a neutral mediator, not an
 * arbiter — the system prompt below is written to validate both sides and point at what the
 * data actually shows, not to hand down a verdict on who's "right".
 */
object FamilyMediator {
    fun buildSystemPrompt(
        personAName: String, personASummary: String,
        personBName: String, personBSummary: String
    ): String =
        "Tum Arya ho, ek neutral family mediator. Do log (jo dono family hain) ke beech kisi " +
            "baat par tension hai. Neeche dono ki apni priorities aur time-mismatch data diya " +
            "hai — dono taraf se DIYA GAYA data hai, tum kisi ek ka paksh mat lo. Tumhara kaam: " +
            "(1) dono ke nazariye ko sach me samjho aur dikhao ki tum samajh rahi ho, (2) data " +
            "me jo asli tension dikh rahi hai use saaf, bina blame ke bolo, (3) ek beech ka " +
            "raasta suggest karo jisme dono ki sabse important priority ka thoda khayal rahe. " +
            "Kisi ek ko 'galat' mat bolo — dono ka apna sach hai.\n\n" +
            "$personAName ka data:\n$personASummary\n\n" +
            "$personBName ka data:\n$personBSummary"
}

/**
 * Future Self Letter — write something today, seal it to a future date, and hear it back in
 * your own voice (reuses the same "Main"-keyed voice-clone [FamilyMemoryStore] already uses
 * for Life Simulator's past-self recall — one recording, shared across both features). A
 * [com.arya.ai.worker.ReminderTools] one-time reminder fires ON the seal date so the person
 * actually finds out the letter unlocked, rather than needing to remember to check.
 */
data class FutureLetter(val id: Int, val text: String, val sealDateMs: Long, val createdAtMs: Long)

object FutureLetterStore {

    private fun prefs(context: Context) = com.arya.ai.util.SecurePrefs.get(context, "arya_future_letters")

    private fun readAll(context: Context): JSONArray =
        JSONArray(prefs(context).getString("letters", "[]") ?: "[]")

    private fun writeAll(context: Context, arr: JSONArray) =
        prefs(context).edit().putString("letters", arr.toString()).apply()

    /** [sealDateMs] must be in the future — this doesn't validate that itself, the screen's
     *  duration-chip picker guarantees it by construction (always "now + N days"). Returns the
     *  new letter's id, so the caller can also register a matching reminder under the same id. */
    fun save(context: Context, text: String, sealDateMs: Long): Int {
        val arr = readAll(context)
        val nextId = (0 until arr.length()).maxOfOrNull { arr.getJSONObject(it).optInt("id") } ?: 0
        val item = JSONObject().apply {
            put("id", nextId + 1)
            put("text", text.trim())
            put("sealDateMs", sealDateMs)
            put("createdAtMs", System.currentTimeMillis())
        }
        arr.put(item)
        writeAll(context, arr)
        return nextId + 1
    }

    fun getAll(context: Context): List<FutureLetter> {
        val arr = readAll(context)
        return (0 until arr.length()).map {
            val obj = arr.getJSONObject(it)
            FutureLetter(
                id = obj.optInt("id"),
                text = obj.optString("text"),
                sealDateMs = obj.optLong("sealDateMs"),
                createdAtMs = obj.optLong("createdAtMs")
            )
        }.sortedBy { it.sealDateMs }
    }
}

/**
 * Digital Legacy Conversation — the most sensitive feature in this app, built with several
 * deliberate constraints instead of just "clone their voice and let people ask it anything":
 *
 * 1. STRICTLY grounded in recorded memory text. The system prompt below explicitly forbids
 *    inventing opinions, facts, or answers not present in the person's own saved
 *    [FamilyMemoryStore] entries — including telling the LLM to say "ye mujhe yaad nahi" for
 *    anything outside that data, rather than confabulating a plausible-sounding answer.
 * 2. Requires a MINIMUM number of memories ([MIN_MEMORIES_REQUIRED]) before this even offers
 *    itself — a persona built from one or two short stories is thin enough to feel more
 *    misleading than comforting.
 * 3. The screen keeps a persistent, non-dismissable disclaimer on screen at all times (not
 *    just a one-time consent dialog) — "this is Arya narrating recorded memories, not the
 *    person" — because the risk here isn't a one-time misunderstanding, it's forgetting mid-
 *    conversation. See LegacySpaceScreen's doc comment for why consent is framed the way it is.
 */
object LegacyMode {
    const val MIN_MEMORIES_REQUIRED = 3

    fun buildSystemPrompt(person: String, memories: List<FamilyMemory>): String {
        val memoryBlock = memories.joinToString("\n\n") { "\"${it.title}\": ${it.story}" }
        return "Tum Arya ho. Neeche '$person' ke baare me record ki gayi ASLI yaadein hain — " +
            "unhi ke apne shabdon/kahaniyon se li gayi hain, kisi aur ne likhi hain unke baare me. " +
            "Tumhara kaam hai in yaadon ke aadhar par '$person' jaisa lehja/andaaz me baat karna — " +
            "SIRF isi diye gaye data se. Kabhi koi naya fact, opinion, ya kahani mat banao jo yahan " +
            "nahi hai. Agar koi sawaal aisa poocha jaye jiska jawaab in yaadon me nahi hai, saaf " +
            "bolo 'ye mujhe yaad nahi hai' ya 'ye baat kabhi bataayi nahi gayi' — kabhi jhooth mat " +
            "bolna ki tumhe pata hai jab pata nahi hai. Aur ek baat hamesha yaad rakhna, agar koi " +
            "puche ya lage zaroori: tum '$person' nahi ho — tum unki record ki gayi yaadon se " +
            "banayi gayi ek pratimurti ho, Arya ki taraf se.\n\n" +
            "$person ki yaadein:\n$memoryBlock"
    }
}

/** Local-only: has the person USING this device acknowledged, for this specific [person]'s
 *  Legacy Space, that they understand it's memory-grounded narration and not the actual
 *  person? Re-asked once per person (not globally) — acknowledging it for one relative
 *  shouldn't silently apply to a different relative's space later. */
object LegacyModeStore {
    private fun prefs(context: Context) = com.arya.ai.util.SecurePrefs.get(context, "arya_legacy_mode")

    fun hasAcknowledged(context: Context, person: String): Boolean =
        prefs(context).getBoolean("ack_${person.trim().lowercase()}", false)

    fun acknowledge(context: Context, person: String) {
        prefs(context).edit().putBoolean("ack_${person.trim().lowercase()}", true).apply()
    }
}

/**
 * Family Debate Simulator — a REHEARSAL tool, not a transcript of what anyone actually said.
 * Uses only the lightweight, already-auto-shared [com.arya.ai.util.FirebaseSync.FamilyCircleMember.topPriority]
 * per member (same data Family Vision Board already shows) to sketch out where a proposed
 * family decision is likely to create friction, BEFORE the real conversation happens — so the
 * person can walk in prepared, not blindsided. Deliberately does NOT use Family Mediator's
 * fuller opted-in `mediatorSummary` data; that's for an already-surfaced tension between two
 * specific people, this is lighter-weight and proactive across the whole circle.
 */
object FamilyDebateSimulator {
    fun buildSystemPrompt(topic: String, members: List<Pair<String, String>>): String {
        val memberLines = members.joinToString("\n") { (nickname, priority) ->
            "${nickname}: sabse zaroori priority — ${priority.ifBlank { "pata nahi" }}"
        }
        return "Tum Arya ho. Family ek faisla lene wale hain: \"$topic\". Neeche family circle ke " +
            "har member ki sirf EK stated priority di hai — poora context nahi hai, isliye ye " +
            "REHEARSAL hai, kisi ke asli words/opinion ka daava nahi. Kisi ko quote mat karo jaise " +
            "unhone khud kaha ho — bolo 'shaayad', 'ho sakta hai'. Kaam: (1) har member ki stated " +
            "priority ke hisaab se, is faisle par unka rukh kya ho sakta hai, guess karo — " +
            "sirf ek-do line har member ke liye, (2) sabse zyada tension kahan ban sakti hai, " +
            "saaf batao, (3) asli baatcheet shuru karne ke liye ek chhota sujhaav do. Shuru me " +
            "yaad dilao ki ye sirf ek rehearsal hai, asli logon ki jagah nahi leta.\n\n" +
            "Family ki priorities:\n$memberLines"
    }
}

/**
 * Reverse Interview — logs each live correction Skill Mirror gives (see SkillCoachScreen),
 * so a personalized practice plan can be built from the person's OWN recurring mistakes in a
 * skill, instead of a generic lesson plan. Purely local storage (not shared to any Firebase
 * circle) — corrections about someone's cooking/workout/instrument technique are personal.
 */
object SkillCoachLog {
    private fun prefs(context: Context) = com.arya.ai.util.SecurePrefs.get(context, "arya_skill_coach_log")

    private fun readAll(context: Context): JSONArray =
        JSONArray(prefs(context).getString("corrections", "[]") ?: "[]")

    private fun writeAll(context: Context, arr: JSONArray) =
        prefs(context).edit().putString("corrections", arr.toString()).apply()

    fun logCorrection(context: Context, skill: String, correction: String) {
        val arr = readAll(context)
        arr.put(JSONObject().apply {
            put("skill", skill)
            put("correction", correction)
            put("timestampMs", System.currentTimeMillis())
        })
        writeAll(context, arr)
    }

    /** Correction texts for [skill] from the last [days] days, oldest first — enough recent
     *  history to spot a recurring pattern without dragging in corrections from months ago
     *  that may no longer apply. */
    fun getRecentCorrections(context: Context, skill: String, days: Int = 30): List<String> {
        val arr = readAll(context)
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        val out = mutableListOf<Pair<Long, String>>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("skill") != skill) continue
            val ts = obj.optLong("timestampMs")
            if (ts < cutoff) continue
            out.add(ts to obj.optString("correction"))
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    fun buildPracticePlanPrompt(skill: String, corrections: List<String>): String {
        val list = corrections.mapIndexed { i, c -> "${i + 1}. $c" }.joinToString("\n")
        return "Tum Arya ho, ek skill coach. Neeche '$skill' practice karte waqt pichle kuch " +
            "sessions me diye gaye live corrections hain (time ke saath). Inme jo baar-baar " +
            "aane wali cheez dikhe, use pehchano — sirf ek baar hui galti par zor mat do. " +
            "Chhota, practical practice plan do (3-4 points), jo isi vyakti ki asli recurring " +
            "galtiyo par based ho, generic tips nahi.\n\nCorrections:\n$list"
    }
}

/**
 * Ancestral Thread — weaves several family members' separately-recorded memories (about the
 * same event/topic, picked by the person using the app — there's no automatic "same event"
 * detection here, that's a judgment call the human makes) into one flowing narrative, each
 * contributor's part clearly attributed so [ui.AncestralThreadScreen] can play it back
 * switching between their own cloned voices (see FamilyMemoryStore's voice-id store) as the
 * narrative moves between people — same "strictly grounded, nothing invented" constraint as
 * [LegacyMode], since this is still real people's real recorded words being rearranged.
 */
object AncestralThread {
    private val SEGMENT_MARKER = Regex("""\[([^\]]+)\]:""")

    fun buildSystemPrompt(memories: List<FamilyMemory>): String {
        val block = memories.joinToString("\n\n") { "${it.person} ne bataya (\"${it.title}\"): ${it.story}" }
        return "Tum Arya ho, ek kahani jodne wali saathi. Neeche parivar ke alag-alag logo ne " +
            "ek hi tarah ke kissa/event ke baare me jo bataya hai, wo hai — kabhi ek hi baat " +
            "alag logo ne alag angle se bataya, kabhi alag hisse. Inko jodkar EK behta hua " +
            "kahani banao, jisme har hissa jiska hai uska naam pehle likha ho. SIRF diye gaye " +
            "data se — kuch naya fact ya kahani mat jodo, sirf jodne/behtar tarike se sunane " +
            "ka kaam karo. EXACT format follow karo: har paragraph se pehle '[NAAM]:' likho " +
            "(NAAM bilkul wahi jo neeche diya gaya hai), phir uska hissa. Kam se kam 2, zyada " +
            "se zyada 6 paragraphs.\n\nYaadein:\n$block"
    }

    data class ThreadSegment(val person: String, val text: String)

    /** Splits the woven narrative back into per-person segments for sequential multi-voice
     *  playback. Empty list (not a partial guess) if the reply didn't follow the "[NAAM]:"
     *  format at all — better to show an error than silently play something mis-attributed. */
    fun parseSegments(reply: String): List<ThreadSegment> {
        val matches = SEGMENT_MARKER.findAll(reply).toList()
        if (matches.isEmpty()) return emptyList()
        val segments = mutableListOf<ThreadSegment>()
        for (i in matches.indices) {
            val person = matches[i].groupValues[1].trim()
            val start = matches[i].range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else reply.length
            val text = reply.substring(start, end).trim()
            if (text.isNotBlank()) segments.add(ThreadSegment(person, text))
        }
        return segments
    }
}
