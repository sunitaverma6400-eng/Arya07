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
    val images: List<Bitmap> = emptyList()
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
 * @param onlineChat Calls the online relay with (prompt, systemPrompt) and returns
 * (reply text, "Provider/Model" label used for [lastReplySource]). This is the only
 * way Arya generates a reply now.
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
 */
class ChatViewModel(
    private val onlineChat: suspend (String, String) -> Pair<String, String>,
    private val onlineChatStream: (suspend (String, String, onChunk: (String) -> Unit) -> Pair<String, String>)? = null,
    private val chatDao: ChatDao? = null,
    initialSessionId: Long? = null,
    private val identityContext: () -> String = { "" },
    private val toolExecutor: (suspend (com.arya.ai.inference.ToolCall) -> String)? = null,
    private val chatSync: ((userText: String, modelText: String) -> Unit)? = null
) : ViewModel() {

    /** Mutable so the very first exchange can create the row and every exchange after reuses it. */
    private var sessionId: Long? = initialSessionId

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
    fun generateImage(prompt: String) {
        if (prompt.isBlank() || _isGenerating.value) return
        _messages.value = _messages.value + ChatMessage(ChatMessage.Role.USER, prompt)
        _messages.value = _messages.value + ChatMessage(ChatMessage.Role.MODEL, "🎨 Image bana rahi hoon...")
        _isGenerating.value = true
        viewModelScope.launch {
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
     * Sends [prompt] (plus any [pendingImages]) to Arya. With an image attached, this goes
     * straight to [com.arya.ai.tools.VisionRelay] (Gemini vision via the relay) instead of the
     * text/tool-calling loop below — the relay's `/v1/relay` text-only path has no image param.
     */
    fun send(prompt: String) {
        if (prompt.isBlank() || _isGenerating.value) return
        val images = _pendingImages.value
        _messages.value = _messages.value + ChatMessage(ChatMessage.Role.USER, prompt, images)
        _messages.value = _messages.value + ChatMessage(ChatMessage.Role.MODEL, "")
        _pendingImages.value = emptyList()
        _isGenerating.value = true

        viewModelScope.launch {
            if (images.isNotEmpty()) {
                runVision(prompt, images.first())
            } else {
                runOnline(prompt)
            }
        }
    }

    private suspend fun runVision(prompt: String, image: Bitmap) {
        try {
            val reply = withContext(Dispatchers.IO) {
                com.arya.ai.tools.VisionRelay.describeImage(image, prompt)
            }
            if (reply != null) {
                replaceLastModelMessage(reply)
                _lastReplySource.value = "Gemini vision"
                persistExchange(prompt, reply)
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

    private suspend fun runOnline(prompt: String) {
        try {
            var conversationContext = prompt
            val toolSystemPrompt = if (toolExecutor != null) {
                val tools = com.arya.ai.tools.AryaToolRegistry.relevantTools(prompt, maxTools = 6)
                com.arya.ai.inference.ToolCallParser.buildSystemPrompt(tools, persona = identityContext())
            } else {
                identityContext() + "\n" + com.arya.ai.util.DateTimeContext.currentDateTimeLine()
            }

            var (reply, modelLabel) = streamOneTurn(conversationContext, toolSystemPrompt)
            var toolCall = if (toolExecutor != null) com.arya.ai.inference.ToolCallParser.parseToolCall(reply) else null
            val toolsUsed = mutableListOf<String>()
            val maxToolRounds = 4
            var round = 0
            while (toolCall != null && round < maxToolRounds) {
                round++
                toolsUsed.add(toolCall.name)
                replaceLastModelMessage("🔍 ${toolCall.name} chala raha hoon (step $round/$maxToolRounds)...")
                val toolResult = toolExecutor!!(toolCall)
                conversationContext += "\n\nArya ne '${toolCall.name}' tool chalaya, result mila:\n$toolResult\n\n" +
                    "Ab is result ke hisaab se: agar isse pehle sawaal ka poora jawaab ban sakta hai to seedha " +
                    "final jawaab do (koi tool-call JSON nahi); agar abhi bhi kisi aur tool ki zaroorat hai " +
                    "to agla tool-call do."
                val result = streamOneTurn(conversationContext, toolSystemPrompt)
                reply = result.first
                modelLabel = result.second
                toolCall = com.arya.ai.inference.ToolCallParser.parseToolCall(reply)
            }

            if (toolCall != null) {
                replaceLastModelMessage(
                    "⚠️ $maxToolRounds steps ho gaye (${toolsUsed.joinToString(" → ")}), ab tak ka result:\n\n$reply"
                )
                _lastReplySource.value = "$modelLabel · tools: ${toolsUsed.joinToString(", ")} (incomplete)"
                persistExchange(prompt, reply)
            } else {
                replaceLastModelMessage(reply)
                _lastReplySource.value = if (toolsUsed.isNotEmpty())
                    "$modelLabel · tools: ${toolsUsed.joinToString(", ")}" else modelLabel
                persistExchange(prompt, reply)
            }
        } catch (e: Exception) {
            replaceLastModelMessage("⚠️ Online model se jawaab nahi mila: ${e.message}")
        } finally {
            _isGenerating.value = false
        }
    }

    /**
     * Runs one (prompt, systemPrompt) -> (finalText, modelLabel) turn. If [onlineChatStream]
     * is wired, this fills the last message bubble in live as text arrives — UNLESS tool-calling
     * is on and the reply looks like it's building up a `{"tool": ...}` call, in which case it
     * buffers silently (so the user never sees raw tool-call JSON flash by) and only the
     * "🔍 ... chala raha hoon" status appears once the tool call is actually parsed, same as
     * before streaming existed. A reply that happens to start with "{" but ISN'T a tool call
     * (rare) just won't stream live — it still shows correctly once the full text is in, via the
     * final [replaceLastModelMessage] call in [runOnline].
     */
    private suspend fun streamOneTurn(prompt: String, systemPrompt: String): Pair<String, String> {
        val streamer = onlineChatStream
        if (streamer == null) {
            return withContext(Dispatchers.IO) { onlineChat(prompt, systemPrompt) }
        }
        val buffer = StringBuilder()
        var revealedAsText = toolExecutor == null
        return withContext(Dispatchers.IO) {
            streamer(prompt, systemPrompt) { delta ->
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

    private fun replaceLastModelMessage(text: String) {
        val current = _messages.value.toMutableList()
        val lastIndex = current.indexOfLast { it.role == ChatMessage.Role.MODEL }
        if (lastIndex >= 0) {
            current[lastIndex] = current[lastIndex].copy(text = text)
            _messages.value = current
        }
    }

    fun clear() {
        _messages.value = emptyList()
        sessionId = null
    }
}
