package com.arya.ai.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arya.ai.data.ChatDao
import com.arya.ai.data.ChatMessageEntity
import com.arya.ai.data.ChatSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatMessage(
    val role: Role,
    val text: String,
    val images: List<Bitmap> = emptyList(),
    /** Set when this reply came from a play_stream/find_and_play/play_saved_stream tool call
     *  (or a direct [playRadioStation] tap) that actually started playing something — the
     *  station/stream's display label, so the UI can show a reliable "📻 now playing" chip
     *  instead of depending on the model's final reply text happening to contain it. */
    val nowPlaying: String? = null,
    /** Set by [generateVideo] to a local `content://` URI for a freshly generated video —
     *  lets [com.arya.ai.ui.ChatScreen]'s ChatBubble show the same "▶️ play" button it shows
     *  for search_videos/search_youtube results, without needing the URI to also match
     *  [com.arya.ai.ui.findPlayableVideoUrl]'s http(s)-only regex (FIXES_LOG.md Phase 26). */
    val playableMediaUri: String? = null,
    /** Set when [playableMediaUri] came from a `play_video`/`find_and_play` tool call the
     *  person explicitly asked to "play"/"chalao" (as opposed to just search/list results) —
     *  [com.arya.ai.ui.ChatScreen] opens [com.arya.ai.ui.VideoPlayerDialog] immediately for
     *  these instead of waiting for a "Video dekho" tap. */
    val autoPlayVideo: Boolean = false
) {
    enum class Role { USER, MODEL }
}

/**
 * Arya has no on-device model anymore — every reply goes through the free online
 * relay (Groq/Gemini/OpenRouter, see [com.arya.ai.util.OnlineChatHelper]). This
 * ViewModel is intentionally simple as a result: no "which engine", no "is a model
 * loaded" branching — just build a prompt (+ tool definitions, if wired), call
 * [onlineChat], run the tool-call loop if the reply asks for one, show the result.
 *
 * @param onlineChat Calls the online relay with (prompt, systemPrompt, history) and returns
 * (reply text, "Provider/Model" label used for [lastReplySource], emotion tag from the
 * relay's `[emotion:xxx]` reply prefix — see [com.arya.ai.util.AvatarEmotion]). `history` is
 * this ViewModel's own prior turns (see [buildHistoryForRelay]) — bug fix (see chat history:
 * user reported every reply "forgetting" what was just discussed) for what used to be a fully
 * stateless call every single turn.
 * @param onlineChatStream Same contract as [onlineChat] but also takes an `onChunk` callback
 * invoked with each text delta as it streams in from [com.arya.ai.util.OnlineChatHelper] —
 * used to fill in the reply bubble live instead of all at once. Null (the default) disables
 * this and falls back to [onlineChat]'s all-at-once behavior, so this ViewModel still works
 * standalone/in tests without wiring streaming.
 * @param chatDao Room DAO used to persist every exchange as a chat session (Settings/History ->
 * "Chat History" screen). Null disables persistence entirely (kept optional so this ViewModel
 * still works standalone/in tests without a real database).
 * @param initialSessionId When non-null, loads that session's past messages from Room on init
 * and appends every new exchange to the same session instead of starting a new one — this is
 * how re-opening a session from the History screen shows its previous messages.
 * @param identityContext Supplies the "who built you"/current-location/persona context line
 * added to every system prompt. Returning "" (the default) disables this; MainActivity wires it
 * to [com.arya.ai.util.AryaIdentity] + [com.arya.ai.util.LocationContext] + persona.
 * @param toolExecutor Runs a parsed [com.arya.ai.inference.ToolCall] against
 * [com.arya.ai.tools.AryaToolRegistry] and returns its result text. Null (the default) disables
 * tool use entirely — every reply is then plain conversation.
 * @param chatSync Optional hook called with (userText, modelText) after every exchange, right
 * alongside [persistExchange]'s local Room save — MainActivity wires this to
 * [com.arya.ai.util.FirebaseSync.logChatExchange], which itself no-ops unless the user opted in
 * via the data-sharing consent dialog. Null (the default) disables this entirely.
 * @param speakReply Optional hook called with (replyText, emotion) once a text reply is final —
 * MainActivity wires this to [com.arya.ai.util.VoiceHelper.speak] (gated behind
 * [com.arya.ai.util.PreferencesManager.ttsEnabled]) so typed chat replies get read aloud in
 * the same emotional ElevenLabs voice as the voice/live-call paths, not just there. Null (the
 * default) keeps text chat silent, same as before this existed.
 */
class ChatViewModel(
    private val onlineChat: suspend (String, String, List<Pair<String, String>>, List<com.arya.ai.inference.ToolDefinition>) -> Triple<String, String, String>,
    private val onlineChatStream: (suspend (String, String, List<Pair<String, String>>, List<com.arya.ai.inference.ToolDefinition>, onChunk: (String) -> Unit) -> Triple<String, String, String>)? = null,
    private val chatDao: ChatDao? = null,
    initialSessionId: Long? = null,
    private val identityContext: () -> String = { "" },
    private val ragContext: (suspend (String) -> String)? = null,
    private val toolExecutor: (suspend (com.arya.ai.inference.ToolCall) -> String)? = null,
    private val chatSync: ((userText: String, modelText: String) -> Unit)? = null,
    private val speakReply: ((replyText: String, emotion: String) -> Unit)? = null
) : ViewModel() {

    /** Mutable so the very first exchange can create the row and every exchange after reuses it. */
    private var sessionId: Long? = initialSessionId

    /** How many PAST exchanges (user+model pairs) get sent as `history` on every new relay
     *  call — see [buildHistoryForRelay]. Not "as many as possible": every turn of history is
     *  tokens the relay resends to the model on every subsequent call too, so this trades
     *  memory depth against request size/cost. 8 exchanges (16 messages) comfortably covers
     *  "what did we just say a couple of messages ago" without the request growing unbounded
     *  over a long chat. */
    private val MAX_HISTORY_TURNS = 8

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _pendingImages = MutableStateFlow<List<Bitmap>>(emptyList())
    val pendingImages: StateFlow<List<Bitmap>> = _pendingImages.asStateFlow()

    /** Which online provider/model produced the last reply — shown in the UI so the user
     *  knows which free model actually answered. */
    private val _lastReplySource = MutableStateFlow<String?>(null)
    val lastReplySource: StateFlow<String?> = _lastReplySource.asStateFlow()

    init {
        val sid = initialSessionId
        if (sid != null && chatDao != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val stored = chatDao.getMessagesForSessionOnce(sid)
                val loaded = stored.map {
                    ChatMessage(if (it.isFromUser) ChatMessage.Role.USER else ChatMessage.Role.MODEL, it.text)
                }
                withContext(Dispatchers.Main) { _messages.value = loaded }
            }
        }
    }

    fun attachImage(bitmap: Bitmap) {
        _pendingImages.value = _pendingImages.value + bitmap
    }

    fun removePendingImage(bitmap: Bitmap) {
        _pendingImages.value = _pendingImages.value - bitmap
    }

    /**
     * Text-to-image via [com.arya.ai.tools.ImageGenTools] — kept separate from [send]'s
     * tool-calling loop (which only knows how to feed a *text* tool result back to the model)
     * since an image result doesn't fit that loop. Appends the user's prompt + the generated
     * image directly as a [ChatMessage.Role.MODEL] message. Not persisted to [chatDao] (session
     * history) yet — a known gap, same "in-memory only for this session" limitation as the
     * rest of this pass's newer features.
     */
    /**
     * Phase 2 of the "advanced tools" upgrade (see chat history) — plays a station directly
     * when the user taps one of the buttons [ChatBubble] renders under a `search_radio` result,
     * instead of them having to retype the station name as a new message. Posts its own
     * user-visible "Playing X" message so the tap has the same visible feedback a typed
     * "X radio lagao" request would.
     */
    /** @param stationList The full result list this tap came from (a `search_radio` card) and
     *  @param index this station's position in it — passed through to [com.arya.ai.player.StreamPlayerManager]
     *  so the persistent mini-player's ⏮/⏭ can cycle through the rest of that list afterwards.
     *  A single-station call (no list context) can pass a one-item list with index 0. */
    fun playRadioStation(context: android.content.Context, stationList: List<Pair<String, String>>, index: Int) {
        if (_isGenerating.value || index !in stationList.indices) return
        val (name, _) = stationList[index]
        _messages.value = _messages.value + ChatMessage(ChatMessage.Role.USER, "▶️ $name lagao")
        _messages.value = _messages.value + ChatMessage(ChatMessage.Role.MODEL, "📻 $name se connect ho rahi hoon...")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                com.arya.ai.player.StreamPlayerManager.playFromList(context, stationList, index)
            }
            val nowPlaying = if (result.startsWith("▶️")) name else null
            _messages.value = _messages.value.dropLast(1) + ChatMessage(ChatMessage.Role.MODEL, result, nowPlaying = nowPlaying)
        }
    }

    fun generateImage(prompt: String) {
        if (prompt.isBlank() || _isGenerating.value) return
        _messages.value = _messages.value + ChatMessage(ChatMessage.Role.USER, prompt)
        _messages.value = _messages.value + ChatMessage(ChatMessage.Role.MODEL, "🎨 Image bana rahi hoon...")
        _isGenerating.value = true
        generationJob = viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) { com.arya.ai.tools.ImageGenTools.generate(prompt) }
            _messages.value = _messages.value.dropLast(1) + if (bitmap != null) {
                ChatMessage(ChatMessage.Role.MODEL, "", images = listOf(bitmap))
            } else {
                ChatMessage(ChatMessage.Role.MODEL, "❌ Image generate nahi ho payi — relay configured hai ya nahi check karo")
            }
            _isGenerating.value = false
        }
    }

    /**
     * Text-to-video, "/video <prompt>" (FIXES_LOG.md Phase 26). Genuinely uses Veo 3.1 via the
     * relay's plain Gemini API key — no separate Vertex AI/billing setup needed, verified
     * against Google's current docs. Can take a couple of minutes; the UI stays on the "bana
     * rahi hoon" placeholder until it resolves. On success the reply's text carries a
     * `content://` URI that [com.arya.ai.ui.ChatScreen]'s video-URL detection picks up as a
     * playable link, same as any other video result.
     */
    fun generateVideo(context: android.content.Context, prompt: String) {
        if (prompt.isBlank() || _isGenerating.value) return
        _messages.value = _messages.value + ChatMessage(ChatMessage.Role.USER, prompt)
        _messages.value = _messages.value + ChatMessage(ChatMessage.Role.MODEL, "🎬 Video bana rahi hoon, thoda time lagega...")
        _isGenerating.value = true
        generationJob = viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) { com.arya.ai.tools.VideoGenTools.generate(context, prompt) }
            _messages.value = _messages.value.dropLast(1) + if (uri != null) {
                ChatMessage(ChatMessage.Role.MODEL, "🎬 Video ban gaya", playableMediaUri = uri)
            } else {
                ChatMessage(
                    ChatMessage.Role.MODEL,
                    "❌ Video generate nahi ho paya — relay configured hai ya nahi check karo, " +
                        "ya Gemini key ka free-tier Veo quota khatam ho gaya ho sakta hai."
                )
            }
            _isGenerating.value = false
        }
    }

    /**
     * Text-to-music, "/music <prompt>" (FIXES_LOG.md Phase 26). Lyria 3 Clip, same relay/key
     * setup as [generateVideo]. On success this plays the 30-second clip immediately via
     * [com.arya.ai.tools.StreamTools.playStream] (same playback path as radio) rather than
     * just linking to it, since there's no reason to make the person tap twice for a clip
     * that's already ready.
     */
    fun generateMusic(context: android.content.Context, prompt: String) {
        if (prompt.isBlank() || _isGenerating.value) return
        _messages.value = _messages.value + ChatMessage(ChatMessage.Role.USER, prompt)
        _messages.value = _messages.value + ChatMessage(ChatMessage.Role.MODEL, "🎵 Music bana rahi hoon...")
        _isGenerating.value = true
        generationJob = viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) { com.arya.ai.tools.MusicGenTools.generate(context, prompt) }
            _messages.value = _messages.value.dropLast(1) + if (uri != null) {
                val playResult = withContext(Dispatchers.IO) { com.arya.ai.tools.StreamTools.playStream(context, uri, prompt) }
                val nowPlaying = if (playResult.startsWith("▶️")) prompt else null
                ChatMessage(ChatMessage.Role.MODEL, "🎵 Music ban gaya, bajaya ja raha hai", nowPlaying = nowPlaying)
            } else {
                ChatMessage(
                    ChatMessage.Role.MODEL,
                    "❌ Music generate nahi ho paya — relay configured hai ya nahi check karo, " +
                        "ya Gemini key ka free-tier Lyria quota khatam ho gaya ho sakta hai."
                )
            }
            _isGenerating.value = false
        }
    }

    /**
     * Sends [prompt] (plus any [pendingImages]) to Arya. With an image attached, this goes
     * straight to [com.arya.ai.tools.VisionRelay] (Gemini vision via the relay) instead of the
     * text/tool-calling loop below — the relay's `/v1/relay` text-only path has no image param.
     */
    private var generationJob: kotlinx.coroutines.Job? = null

    fun send(prompt: String) {
        val images = _pendingImages.value
        // Previously bailed out on any blank prompt regardless of images — meaning selecting a
        // photo with no caption and hitting send silently did nothing (see chat history: this
        // is the exact bug report). Now only blocks when there's truly nothing to send.
        if ((prompt.isBlank() && images.isEmpty()) || _isGenerating.value) return
        val bubbleText = prompt.ifBlank { "📷" } // just the chat-bubble label for an image-only send
        _messages.value = _messages.value + ChatMessage(ChatMessage.Role.USER, bubbleText, images)
        _messages.value = _messages.value + ChatMessage(ChatMessage.Role.MODEL, "")
        _pendingImages.value = emptyList()
        _isGenerating.value = true

        generationJob = viewModelScope.launch {
            if (images.isNotEmpty()) {
                runVision(prompt, images.first())
            } else {
                runOnline(prompt)
            }
        }
    }

    /**
     * User tapped "stop" mid-reply (see chat history — mirrors the interrupt button Claude's
     * own chat UI has). [streamOneTurn] already updates the last message bubble live as text
     * streams in, so cancelling here just stops it from getting any further — whatever's
     * already on screen stays, same as stopping a real conversation mid-sentence rather than
     * erasing what was already said.
     */
    fun stopGenerating() {
        if (!_isGenerating.value) return
        generationJob?.cancel()
        _isGenerating.value = false
        val last = _messages.value.lastOrNull()
        if (last != null && last.role == ChatMessage.Role.MODEL) {
            val stoppedText = if (last.text.isBlank()) "⏹️ Roka gaya" else "${last.text}\n\n⏹️ _(yahin roka gaya)_"
            replaceLastModelMessage(stoppedText, images = last.images)
        }
    }

    private suspend fun runVision(promptRaw: String, image: Bitmap) {
        // No caption typed — instead of sending Gemini a blank prompt (inconsistent results),
        // explicitly ask for a genuinely thorough read of the image: what's in it, any text,
        // context/situation, anything notable. This is the "deep search the photo" behavior
        // (see chat history) rather than a one-line guess.
        val prompt = promptRaw.ifBlank {
            "Is image ko dhyan se, detail mein describe karo — jo bhi dikh raha hai (log, jagah, objects, " +
                "activity), agar koi text/likha hua ho to wo bhi batao, overall context/situation samjho, " +
                "aur jo kuch specific ya important lage wo highlight karo."
        }
        try {
            val result = withContext(Dispatchers.IO) {
                com.arya.ai.tools.VisionRelay.describeImage(
                    image,
                    prompt,
                    systemPrompt = "Tum Arya ho. User ne ek photo bheji hai chat me. Usko dhyan se, thoroughly " +
                        "analyze karke poora aur useful jawab do — ye voice/live mode nahi hai, isliye chhota " +
                        "one-liner mat do jab tak user ne khud chhota jawab na maanga ho."
                )
            }
            if (result != null) {
                replaceLastModelMessage(result.text)
                _lastReplySource.value = "Gemini vision"
                persistExchange(prompt, result.text)
                speakReply?.invoke(result.text, com.arya.ai.util.AvatarEmotion.sanitize(result.emotion))
                _isGenerating.value = false
            } else {
                // Vision call failed/relay not configured — fall back to a plain text reply.
                runOnline(prompt)
            }
        } catch (e: Exception) {
            replaceLastModelMessage("⚠️ Image samajhne me dikkat aayi: ${e.message}")
            _isGenerating.value = false
        }
    }

    /**
     * Turns the messages already in [_messages] (everything BEFORE the current turn — i.e.
     * before the just-appended user text + placeholder model bubble) into the (role, content)
     * pairs [onlineChat]/[onlineChatStream] send as `history`. This is the actual fix for the
     * "Arya forgets the conversation" bug: capped to the last [MAX_HISTORY_TURNS] messages so
     * a long-running chat doesn't grow the request unboundedly, and skips anything without
     * plain text (an image-only bubble, a "🔍 tool chala raha hoon..." status line that got
     * replaced before the final reply landed) since those aren't meaningful turns to replay to
     * a model that never saw the image itself.
     */
    private fun buildHistoryForRelay(): List<Pair<String, String>> {
        val all = _messages.value
        // Drop the last 2 (the user message + the empty/placeholder model bubble this exact
        // turn just added) — those become this call's own `prompt`, not history.
        val prior = if (all.size >= 2) all.dropLast(2) else emptyList()
        return prior.takeLast(MAX_HISTORY_TURNS * 2)
            .filter { it.text.isNotBlank() }
            .map { (if (it.role == ChatMessage.Role.USER) "user" else "assistant") to it.text }
    }

    private suspend fun runOnline(prompt: String) {
        try {
            var conversationContext = prompt
            val history = buildHistoryForRelay()
            val rag = ragContext?.invoke(prompt)?.trim().orEmpty()
            val groundedIdentity = if (rag.isBlank()) {
                identityContext()
            } else {
                identityContext() + "\n[RAG_CONTEXT]\n" + rag.take(10_000) + "\n[/RAG_CONTEXT]"
            }
            val activeTools = if (toolExecutor != null) com.arya.ai.tools.AryaToolRegistry.relevantTools(prompt, maxTools = 6) else emptyList()
            val toolSystemPrompt = if (toolExecutor != null) {
                com.arya.ai.inference.ToolCallParser.buildSystemPrompt(activeTools, persona = groundedIdentity)
            } else {
                groundedIdentity + "\n" + com.arya.ai.util.DateTimeContext.currentDateTimeLine()
            }

            var (reply, modelLabel, emotion) = streamOneTurn(conversationContext, toolSystemPrompt, history, activeTools)
            var toolCall = if (toolExecutor != null) com.arya.ai.inference.ToolCallParser.parseToolCall(reply) else null
            val toolsUsed = mutableListOf<String>()
            val toolSignatures = mutableSetOf<String>()
            var generatedImage: Bitmap? = null
            var searchedImages: List<Bitmap> = emptyList()
            var nowPlaying: String? = null
            var autoPlayVideoUrl: String? = null
            val maxToolRounds = 4
            var round = 0
            while (toolCall != null && round < maxToolRounds) {
                round++
                val signature = com.arya.ai.inference.ToolCallParser.signature(toolCall)
                if (!toolSignatures.add(signature)) {
                    // Prevent an agent loop from repeatedly issuing the exact same call when a
                    // provider ignores the previous result or the tool failed without changing state.
                    reply = "⚠️ Arya ne same tool request dobara bhejne ki koshish ki, isliye loop rok diya."
                    break
                }
                toolsUsed.add(toolCall.name)
                replaceLastModelMessage("🔍 ${toolCall.name} chala raha hoon (step $round/$maxToolRounds)...")
                val toolResult = try {
                    toolExecutor!!(toolCall).take(12_000)
                } catch (e: Exception) {
                    "❌ Tool '${toolCall.name}' fail hua: ${e.message ?: "unknown error"}"
                }
                com.arya.ai.tools.AryaToolRegistry.takeLastGeneratedImage()?.let { generatedImage = it }
                com.arya.ai.tools.AryaToolRegistry.takeLastSearchedImages().takeIf { it.isNotEmpty() }?.let { searchedImages = it }
                com.arya.ai.tools.AryaToolRegistry.takeLastNowPlaying()?.let { nowPlaying = it }
                com.arya.ai.tools.AryaToolRegistry.takeLastAutoPlayVideo()?.let { autoPlayVideoUrl = it }
                // Keep a compact agent transcript. The previous implementation appended only
                // the latest tool result to the original prompt, so after tool A -> tool B the
                // model could forget which calls/results had already happened and call A again.
                conversationContext += "\n\n[TOOL_CALL] ${toolCall.name} args=${toolCall.args}\n" +
                    "[TOOL_RESULT] $toolResult\n" +
                    "[AGENT_INSTRUCTION] Result ko use karo. Agar user ka sawaal poora ho gaya hai " +
                    "to final jawab do; warna sirf ek naya, different tool-call JSON do."
                val result = streamOneTurn(conversationContext, toolSystemPrompt, history, activeTools)
                reply = result.first
                modelLabel = result.second
                emotion = result.third
                toolCall = com.arya.ai.inference.ToolCallParser.parseToolCall(reply)
            }

            if (toolCall != null) {
                replaceLastModelMessage(
                    "⚠️ $maxToolRounds steps ho gaye (${toolsUsed.joinToString(" → ")}), ab tak ka result:\n\n$reply",
                    images = searchedImages.ifEmpty { listOfNotNull(generatedImage) },
                    nowPlaying = nowPlaying,
                    autoPlayVideoUrl = autoPlayVideoUrl
                )
                _lastReplySource.value = "$modelLabel · tools: ${toolsUsed.joinToString(", ")} (incomplete)"
                persistExchange(prompt, reply)
            } else {
                replaceLastModelMessage(
                    reply,
                    images = searchedImages.ifEmpty { listOfNotNull(generatedImage) },
                    nowPlaying = nowPlaying,
                    autoPlayVideoUrl = autoPlayVideoUrl
                )
                _lastReplySource.value = if (toolsUsed.isNotEmpty())
                    "$modelLabel · tools: ${toolsUsed.joinToString(", ")}" else modelLabel
                persistExchange(prompt, reply)
            }
            // Read the final reply aloud (if wired) — same emotion tag the avatar/relay
            // resolved for it, so the voice and any visible face expression agree. Tool-result
            // text (nowPlaying, generated images etc.) already reads fine on its own; this
            // speaks whatever text ended up in the bubble either way.
            if (reply.isNotBlank()) speakReply?.invoke(reply, emotion)
        } catch (e: Exception) {
            replaceLastModelMessage("⚠️ Online model se jawaab nahi mila: ${e.message}")
        } finally {
            _isGenerating.value = false
        }
    }

    /**
     * Runs one (prompt, systemPrompt, history) -> (finalText, modelLabel) turn. If
     * [onlineChatStream] is wired, this fills the last message bubble in live as text arrives —
     * UNLESS tool-calling is on and the reply looks like it's building up a `{"tool": ...}`
     * call, in which case it buffers silently (so the user never sees raw tool-call JSON flash
     * by) and only the "🔍 ... chala raha hoon" status appears once the tool call is actually
     * parsed, same as before streaming existed. A reply that happens to start with "{" but
     * ISN'T a tool call (rare) just won't stream live — it still shows correctly once the full
     * text is in, via the final [replaceLastModelMessage] call in [runOnline].
     */
    private suspend fun streamOneTurn(
        prompt: String, systemPrompt: String, history: List<Pair<String, String>>, tools: List<com.arya.ai.inference.ToolDefinition> = emptyList()
    ): Triple<String, String, String> {
        val streamer = onlineChatStream
        if (streamer == null) {
            return withContext(Dispatchers.IO) { onlineChat(prompt, systemPrompt, history, tools) }
        }
        val buffer = StringBuilder()
        var revealedAsText = toolExecutor == null
        return withContext(Dispatchers.IO) {
            streamer(prompt, systemPrompt, history, tools) { delta ->
                buffer.append(delta)
                if (!revealedAsText) {
                    val trimmed = buffer.toString().trimStart()
                    if (trimmed.isNotEmpty()) revealedAsText = !trimmed.startsWith("{")
                }
                if (revealedAsText) replaceLastModelMessage(buffer.toString())
            }
        }
    }

    /**
     * Saves one user+model exchange to Room. Creates the session row on the very first
     * exchange (title = first ~40 chars of the user's prompt), then reuses that same
     * [sessionId] for every exchange after — this is what makes the Chat History screen work.
     * No-op if no [chatDao] was provided (e.g. previews/tests).
     */
    private fun persistExchange(userText: String, modelText: String) {
        chatSync?.invoke(userText, modelText)
        val dao = chatDao ?: return
        viewModelScope.launch(Dispatchers.IO) {
            var sid = sessionId
            if (sid == null) {
                sid = dao.insertSession(
                    ChatSessionEntity(
                        title = userText.take(40).ifBlank { "New chat" },
                        modelPath = "online",
                        modelName = "Online free model"
                    )
                )
                sessionId = sid
            }
            dao.insertMessage(ChatMessageEntity(sessionId = sid, text = userText, isFromUser = true))
            dao.insertMessage(ChatMessageEntity(sessionId = sid, text = modelText, isFromUser = false))
        }
    }

    private fun replaceLastModelMessage(
        text: String,
        images: List<Bitmap> = emptyList(),
        nowPlaying: String? = null,
        autoPlayVideoUrl: String? = null
    ) {
        val current = _messages.value.toMutableList()
        val lastIndex = current.indexOfLast { it.role == ChatMessage.Role.MODEL }
        if (lastIndex >= 0) {
            current[lastIndex] = current[lastIndex].copy(
                text = text,
                images = if (images.isNotEmpty()) images else current[lastIndex].images,
                nowPlaying = nowPlaying ?: current[lastIndex].nowPlaying,
                playableMediaUri = autoPlayVideoUrl ?: current[lastIndex].playableMediaUri,
                autoPlayVideo = autoPlayVideoUrl != null || current[lastIndex].autoPlayVideo
            )
            _messages.value = current
        }
    }

    fun clear() {
        _messages.value = emptyList()
        sessionId = null
    }
}
