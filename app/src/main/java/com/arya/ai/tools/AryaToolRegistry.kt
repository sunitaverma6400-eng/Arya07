package com.arya.ai.tools

import android.content.Context
import com.arya.ai.data.DeviceActions
import com.arya.ai.inference.ToolCall
import com.arya.ai.inference.ToolDefinition
import com.arya.ai.inference.ToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        ToolDefinition("generate_image", "Text prompt se ek image banata hai (AI image generation), gallery me save karta hai", listOf(
            ToolParam("prompt", "string", "e.g. 'ek sunset over mountains, cinematic'")
        )),
        ToolDefinition("system_info", "Phone ka model, Android version, free storage batata hai"),

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

        // -- web --
        ToolDefinition("web_search", "Web par search karta hai", listOf(ToolParam("query", "string", "search query"))),
        ToolDefinition("scrape_webpage", "Ek webpage ka text/links/title nikalta hai", listOf(
            ToolParam("url", "string", "e.g. 'example.com'"), ToolParam("extract", "string", "'text' | 'links' | 'title'")
        )),
        ToolDefinition("smart_search", "Wikipedia + web search combine karke best jawab dhoondta hai", listOf(ToolParam("query", "string", "search query"))),

        // -- memory / todos --
        ToolDefinition("remember", "Ek fact yaad rakhta hai", listOf(ToolParam("key", "string", "e.g. 'birthday'"), ToolParam("value", "string", "e.g. '12 March'"))),
        ToolDefinition("recall", "Ek yaad rakha fact wapas deta hai", listOf(ToolParam("key", "string", "e.g. 'birthday'"))),
        ToolDefinition("list_memories", "Sab yaad rakhi cheezein list karta hai"),
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
        ToolDefinition("search_youtube", "YouTube par video search karta hai (sirf links, direct play nahi)", listOf(ToolParam("query", "string", "search terms"))),
        ToolDefinition("search_videos", "Web par video search karta hai (sirf links, direct play nahi)", listOf(ToolParam("query", "string", "search terms"))),
        ToolDefinition("play_stream", "Ek direct audio/HLS stream URL play karta hai", listOf(ToolParam("url", "string", "stream URL"), ToolParam("label", "string", "optional display name"))),
        ToolDefinition("pause_stream", "Chal raha stream pause karta hai"),
        ToolDefinition("resume_stream", "Pause kiya hua stream resume karta hai"),
        ToolDefinition("stop_stream", "Stream poori tarah stop karta hai"),
        ToolDefinition("stop_all_streams", "Sab streams stop karta hai"),
        ToolDefinition("stream_status", "Abhi ka playback status batata hai"),
        ToolDefinition("find_and_play", "Query search karke best-match radio stream seedha play kar deta hai", listOf(ToolParam("query", "string", "e.g. 'lofi radio'"))),
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
        ToolDefinition("generate_image", "Text prompt se ek AI image banata hai (Pollinations, free)", listOf(ToolParam("prompt", "string", "e.g. 'a tiger in the snow, digital art'"))),
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

        // -- custom reminders (new, distinct from set_alarm) --
        ToolDefinition("set_reminder", "Custom reminder set karta hai — one-time ya repeating (set_alarm se alag, silent WorkManager-based)", listOf(
            ToolParam("name", "string", "e.g. 'paani piyo'"), ToolParam("message", "string", "reminder text"),
            ToolParam("delay_minutes", "number", "one-time ke liye, e.g. 30"), ToolParam("repeat_every_minutes", "number", "optional, e.g. 120 for har 2 ghante")
        )),
        ToolDefinition("list_reminders", "Saare active reminders list karta hai"),
        ToolDefinition("cancel_reminder", "Ek reminder cancel karta hai", listOf(ToolParam("name", "string", "reminder ka naam")))
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
        "get_sunrise_sunset" to listOf("sunrise", "sunset", "suraj"),
        "get_public_holidays" to listOf("holiday", "chutti", "chuttiyan"),
        "ask_wolfram" to listOf("solve", "equation", "derivative", "integral"),
        "web_search" to listOf("search karo", "khojo", "dhundo", "google", "github", "documentation dekho", "docs check karo", "library check karo"),
        "scrape_webpage" to listOf("webpage", "website kholo", "repo dekho", "github kholo"),
        "smart_search" to listOf("dhundo"),
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
        val matched = scored.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
        val core = ALL_TOOLS.filter { it.name in CORE_TOOL_NAMES }
        return (matched + core).distinctBy { it.name }.take(maxTools)
    }

    /** Runs on Dispatchers.IO since almost every branch here does blocking network/IO work. */
    suspend fun execute(context: Context, call: ToolCall): String = withContext(Dispatchers.IO) {
        val a = call.args
        try {
            when (call.name) {
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
                        val bitmap = ImageGenTools.generate(prompt)
                        if (bitmap == null) "❌ Image generate nahi ho payi (relay/key issue)"
                        else {
                            val file = ImageGenTools.saveToGallery(context, bitmap)
                            if (file != null) "🎨 Image ban gayi aur gallery me save ho gayi: ${file.name}"
                            else "🎨 Image ban gayi lekin save nahi ho payi"
                        }
                    }
                }
                "system_info" -> UtilityTools.systemInfo(context)

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

                "remember" -> MemoryStore.remember(context, a["key"] ?: "", a["value"] ?: "")
                "recall" -> MemoryStore.recall(context, a["key"] ?: "")
                "list_memories" -> MemoryStore.listMemories(context)
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
                "find_and_play" -> StreamTools.findAndPlay(context, a["query"] ?: "")
                "test_video_source" -> StreamTools.testVideoSource(a["url"] ?: "")
                "save_stream" -> StreamTools.saveStream(context, a["name"] ?: "", a["url"] ?: "")
                "list_saved_streams" -> StreamTools.listSavedStreams(context)
                "delete_saved_stream" -> StreamTools.deleteSavedStream(context, a["name"] ?: "")
                "play_saved_stream" -> StreamTools.playSavedStream(context, a["name"] ?: "")
                "set_default_stream_quality" -> StreamTools.setDefaultStreamQuality(context, a["quality"] ?: "auto")
                "get_default_stream_quality" -> StreamTools.getDefaultStreamQuality(context)
                "list_stream_qualities" -> StreamTools.listStreamQualities()

                "search_images" -> ImageTools.searchImages(a["query"] ?: "")
                "generate_image" -> ImageTools.generateImage(a["prompt"] ?: "")
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

                "set_reminder" -> com.arya.ai.worker.ReminderTools.setReminder(
                    context, a["name"] ?: "reminder", a["message"] ?: "", a["delay_minutes"]?.toLongOrNull() ?: 0L, a["repeat_every_minutes"]?.toLongOrNull() ?: 0L
                )
                "list_reminders" -> com.arya.ai.worker.ReminderTools.listReminders(context)
                "cancel_reminder" -> com.arya.ai.worker.ReminderTools.cancelReminder(context, a["name"] ?: "")

                else -> "🤔 Tool '${call.name}' pehchana nahi gaya."
            }
        } catch (e: Exception) {
            "⚠️ '${call.name}' chalate waqt error: ${e.message}"
        }
    }
}
