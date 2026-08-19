@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.arya.ai.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arya.ai.viewmodel.ChatMessage
import com.arya.ai.viewmodel.ChatViewModel

/**
 * The magical signature element (per direct request — gold Arc Reactor + Doctor Strange
 * portal): a slowly rotating gold-to-silver sweep-gradient ring, drawn behind the "ARYA"
 * title. Deliberately restrained — one glowing ring, not glowing everything — per the
 * "spend your one bold idea in a signature moment" design principle: this is that moment,
 * echoed more subtly afterward via the same gold/silver gradient on the message bubbles.
 */
@Composable
private fun PortalRing(size: androidx.compose.ui.unit.Dp) {
    val infiniteTransition = rememberInfiniteTransition(label = "portalRing")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(7000, easing = LinearEasing)),
        label = "portalRingRotation"
    )
    Canvas(modifier = Modifier.size(size)) {
        rotate(rotation) {
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(
                        com.arya.ai.ui.theme.AryaSignal.copy(alpha = 0.85f),
                        com.arya.ai.ui.theme.AryaSilver.copy(alpha = 0.15f),
                        com.arya.ai.ui.theme.AryaSilver.copy(alpha = 0.6f),
                        com.arya.ai.ui.theme.AryaSignal.copy(alpha = 0.85f)
                    )
                ),
                startAngle = 0f,
                sweepAngle = 300f,
                useCenter = false,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onOpenLive: () -> Unit = {},
    onOpenNotebook: () -> Unit = {},
    onOpenCanvas: () -> Unit = {},
    onOpenPersonalIntelligence: () -> Unit = {},
    userName: String = ""
) {
    val messages by viewModel.messages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val pendingImages by viewModel.pendingImages.collectAsState()
    val lastReplySource by viewModel.lastReplySource.collectAsState()
    var input by remember { mutableStateOf("") }
    var toolsDrawerOpen by remember { mutableStateOf(false) }
    var attachMenuOpen by remember { mutableStateOf(false) }
    val attachMenuSheetState = androidx.compose.material3.rememberModalBottomSheetState()
    var videoUrlToPlay by remember { mutableStateOf<String?>(null) }
    var imageToView by remember { mutableStateOf<Bitmap?>(null) }
    val context = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val bitmap = decodeBitmap(uri, context)
            bitmap?.let { bmp -> viewModel.attachImage(bmp) }
        }
    }

    // Files/Drive tile (FIXES_LOG.md Phase 26) — Android's Storage Access Framework already
    // surfaces Google Drive (and any other installed document provider) as a source here with
    // zero setup on our side, unlike a real Drive API integration which would need a Google
    // Cloud OAuth client Sudhanshu would have to create himself. Images go straight into the
    // same attachImage() path as the gallery picker; anything else that looks like text gets
    // read and dropped into the input box so it can be sent as part of the message.
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mime = context.contentResolver.getType(uri) ?: ""
        if (mime.startsWith("image/")) {
            val bitmap = decodeBitmap(uri, context)
            bitmap?.let { bmp -> viewModel.attachImage(bmp) }
        } else if (mime.startsWith("text/") || mime == "application/json") {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                val trimmed = if (text.length > 4000) text.take(4000) + "\n...(trim kiya gaya)" else text
                input = if (input.isBlank()) trimmed else "$input\n\n$trimmed"
            } catch (e: Exception) {
                // Unreadable/binary despite the mime type — nothing we can usefully do, silently skip.
            }
        } else if (mime == "application/pdf") {
            // PDF text extraction via pdfbox-android (already a dependency for
            // com.arya.ai.util.SimpleRagHelper's document RAG) — same idea as the text/plain
            // branch above: pull the text out and drop it into the input box so it becomes
            // part of the next message, rather than silently no-op'ing like before.
            try {
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
                val text = context.contentResolver.openInputStream(uri)?.use { input ->
                    com.tom_roush.pdfbox.pdmodel.PDDocument.load(input).use { doc ->
                        com.tom_roush.pdfbox.text.PDFTextStripper().getText(doc)
                    }
                } ?: ""
                val trimmed = if (text.length > 4000) text.take(4000) + "\n...(PDF trim kiya gaya)" else text
                input = if (input.isBlank()) trimmed else "$input\n\n$trimmed"
            } catch (e: Exception) {
                // Scanned/image-only PDF or a corrupt file — PDFTextStripper can't do OCR,
                // so this silently no-ops for that case (same as before), rather than crashing.
            }
        }
        // docx/xlsx/pptx etc. still aren't previewable inline — no crash, just no-op.
    }

    var isListening by remember { mutableStateOf(false) }
    val recognizer = remember {
        if (android.speech.SpeechRecognizer.isRecognitionAvailable(context))
            android.speech.SpeechRecognizer.createSpeechRecognizer(context)
        else null
    }
    // Whisper (via Arya Relay) is tried first when configured — better Hindi/Hinglish accuracy
    // than the on-device recognizer. Falls back to [recognizer] (Android SpeechRecognizer) if
    // the relay isn't configured, or if a transcription attempt comes back empty/failed.
    val whisperRecorder = remember { com.arya.ai.util.WhisperRecorder(context) }
    var isWhisperRecording by remember { mutableStateOf(false) }
    val voiceCoroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    // Bug fix (see chat history): voice input used to just fill the text field, leaving the
    // person to tap Send themselves — same "/image" handling as the Send button's own onClick,
    // pulled out here so both the typed and spoken paths send exactly the same way.
    fun sendTypedText(text: String) {
        val trimmed = text.trim()
        // Only bail if there's truly nothing to send — a photo attached with no caption typed
        // used to hit this same blank check and silently do nothing when Send was tapped (see
        // chat history: this was the reported bug). viewModel.send() itself now also allows a
        // blank prompt when pendingImages isn't empty (see ChatViewModel.send).
        if (trimmed.isBlank() && pendingImages.isEmpty()) return
        if (trimmed.startsWith("/image ", ignoreCase = true)) {
            viewModel.generateImage(trimmed.removePrefix("/image ").removePrefix("/Image ").trim())
        } else if (trimmed.startsWith("/img ", ignoreCase = true)) {
            viewModel.generateImage(trimmed.removePrefix("/img ").removePrefix("/Img ").trim())
        } else if (trimmed.startsWith("/video ", ignoreCase = true)) {
            viewModel.generateVideo(context, trimmed.removePrefix("/video ").removePrefix("/Video ").trim())
        } else if (trimmed.startsWith("/music ", ignoreCase = true)) {
            viewModel.generateMusic(context, trimmed.removePrefix("/music ").removePrefix("/Music ").trim())
        } else {
            viewModel.send(trimmed)
        }
        input = ""
    }

    fun beginVoiceInput() {
        if (whisperRecorder.isAvailable) {
            whisperRecorder.startRecording()
            isWhisperRecording = true
            isListening = true
        } else if (recognizer != null) {
            startVoiceInput(
                recognizer,
                onListeningChange = { isListening = it },
                onResult = { text -> sendTypedText(if (input.isBlank()) text else "$input $text") }
            )
        }
    }

    fun endWhisperRecordingAndTranscribe() {
        isWhisperRecording = false
        voiceCoroutineScope.launch {
            val text = whisperRecorder.stopAndTranscribe()
            isListening = false
            if (text != null) {
                sendTypedText(if (input.isBlank()) text else "$input $text")
            } else if (recognizer != null) {
                // Whisper failed for this attempt — fall back to Android's built-in recognizer
                startVoiceInput(
                    recognizer,
                    onListeningChange = { isListening = it },
                    onResult = { t -> sendTypedText(if (input.isBlank()) t else "$input $t") }
                )
            } else {
                android.widget.Toast.makeText(
                    context,
                    "Voice input fail hui (${com.arya.ai.util.WhisperUploader.lastError ?: "wajah pata nahi"}) — dubara try karo",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            recognizer?.destroy()
            if (isWhisperRecording) whisperRecorder.cancelRecording()
        }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginVoiceInput()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(com.arya.ai.ui.theme.AryaInk)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = com.arya.ai.ui.theme.AryaSignal
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            PortalRing(size = 46.dp)
                            Text(
                                "ARYA",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    brush = Brush.linearGradient(
                                        listOf(com.arya.ai.ui.theme.AryaSignal, com.arya.ai.ui.theme.AryaSilver)
                                    )
                                ),
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                letterSpacing = 2.sp
                            )
                        }
                        Text(
                            "online · Groq/Gemini/OpenRouter",
                            style = com.arya.ai.ui.theme.AryaMonoStatus,
                            color = com.arya.ai.ui.theme.AryaSilver.copy(alpha = 0.75f)
                        )
                    }
                    // Gemini-Live-style continuous voice conversation — see FIXES_LOG.md #11.
                    IconButton(onClick = onOpenLive) {
                        Icon(
                            Icons.Filled.GraphicEq,
                            contentDescription = "Live conversation",
                            tint = com.arya.ai.ui.theme.AryaSignal
                        )
                    }
                    // Tools reference drawer — moved here from the chip row (Phase 17): it's a
                    // menu you check occasionally, not a per-message attachment like Photo/Camera/Live.
                    IconButton(onClick = { toolsDrawerOpen = true }) {
                        Icon(
                            Icons.Filled.Build,
                            contentDescription = "Tools",
                            tint = com.arya.ai.ui.theme.AryaSignal
                        )
                    }
                    // Without this there was no way to start a fresh conversation without
                    // going to Chat History and deleting the current session first —
                    // clear() resets both the visible messages and the ViewModel's
                    // sessionId, so the very next message creates a brand-new session
                    // instead of continuing to append to the old one.
                    IconButton(onClick = { viewModel.clear() }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "New chat",
                            tint = com.arya.ai.ui.theme.AryaSignal
                        )
                    }
                }
                // Shows which free online provider/model actually answered the last message.
                val replySource = lastReplySource
                if (replySource != null && !isGenerating) {
                    Text(
                        "● $replySource",
                        style = com.arya.ai.ui.theme.AryaMonoStatus,
                        color = com.arya.ai.ui.theme.AryaEmber,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            // Keeps the last bubble in view as it streams in word-by-word, and on every new
            // message — without this, a long streaming reply grows off the bottom of the screen.
            LaunchedEffect(messages.lastOrNull()?.text, messages.size) {
                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
            }
            if (messages.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    HomeHeroSection(
                        userName = userName,
                        onSuggestionLive = onOpenLive,
                        onSuggestionImage = { input = "/image " },
                        onSuggestionTodo = { input = "Aaj ke to-do dikhao" }
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { message ->
                        ChatBubble(
                            message,
                            onPlayVideo = { url -> videoUrlToPlay = url },
                            onPlayRadioStation = { list, index ->
                                viewModel.playRadioStation(context, list.map { it.name to it.url }, index)
                            },
                            onViewImage = { bmp -> imageToView = bmp }
                        )
                    }
                }
            }

            // Photo/Camera/Live now live inside the "+" attach menu (see AttachMenuSheet below)
            // instead of as a separate chip row here — one less thing sitting permanently above
            // the input dock, same actions either way.

            if (pendingImages.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(pendingImages) { bmp ->
                        Box {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            IconButton(
                                onClick = { viewModel.removePendingImage(bmp) },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Hatao", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // Persistent mini-player — sits right above the input dock so it's visible no
            // matter how far up the chat is scrolled, same idea as any music app's now-playing
            // bar. Only rendered while something is actually loaded (nowPlaying.label != null);
            // see StreamPlayerManager.uiState's own doc comment for what drives each field.
            val nowPlaying by com.arya.ai.player.StreamPlayerManager.uiState.collectAsState()
            if (nowPlaying.label != null) {
                NowPlayingBar(
                    state = nowPlaying,
                    onPlayPause = {
                        if (nowPlaying.isPlaying) com.arya.ai.player.StreamPlayerManager.pause()
                        else com.arya.ai.player.StreamPlayerManager.resume()
                    },
                    onPrevious = { com.arya.ai.player.StreamPlayerManager.previous(context) },
                    onNext = { com.arya.ai.player.StreamPlayerManager.next(context) },
                    onClose = { com.arya.ai.player.StreamPlayerManager.stop() },
                    onVolumeChange = { com.arya.ai.player.StreamPlayerManager.setVolume(it) }
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(28.dp),
                color = com.arya.ai.ui.theme.AryaInkSurface,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { attachMenuOpen = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Aur options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        if (isWhisperRecording) {
                            // Second tap while recording via Whisper = stop & transcribe
                            endWhisperRecordingAndTranscribe()
                            return@IconButton
                        }
                        if (!whisperRecorder.isAvailable && recognizer == null) {
                            android.widget.Toast.makeText(
                                context,
                                "Is phone pe speech recognition available nahi hai — Google app enable/update karke dekho (Settings > Apps > Google)",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            return@IconButton
                        }
                        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.RECORD_AUDIO
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (hasPermission) {
                            beginVoiceInput()
                        } else {
                            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    }) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = "Bolke likho",
                            tint = if (isListening) com.arya.ai.ui.theme.AryaSignal else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Arya ko likho ya bolo…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                        )
                    )
                    Box(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(44.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(com.arya.ai.ui.theme.AryaSignal)
                            .combinedClickable(
                                onClick = {
                                    if (isGenerating) {
                                        // Interrupt option (see chat history — mirrors Claude's
                                        // own stop button): cancels the in-flight reply instead
                                        // of leaving no way to break in mid-generation.
                                        viewModel.stopGenerating()
                                        return@combinedClickable
                                    }
                                    sendTypedText(input)
                                },
                                onLongClick = {}
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isGenerating) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = "Roko",
                                tint = com.arya.ai.ui.theme.AryaSignalOn,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                Icons.Filled.Send,
                                contentDescription = "Bhejo",
                                tint = com.arya.ai.ui.theme.AryaSignalOn
                            )
                        }
                    }
                }
            }
        }
    }

    ToolsDrawer(
        visible = toolsDrawerOpen,
        onClose = { toolsDrawerOpen = false },
        onExamplePrompt = { prompt -> input = prompt }
    )

    if (attachMenuOpen) {
        AttachMenuSheet(
            sheetState = attachMenuSheetState,
            onDismiss = { attachMenuOpen = false },
            onPhoto = {
                attachMenuOpen = false
                imagePicker.launch("image/*")
            },
            onCamera = {
                attachMenuOpen = false
                onOpenLive()
            },
            onImageGen = {
                attachMenuOpen = false
                input = "/image "
            },
            onVideoGen = {
                attachMenuOpen = false
                input = "/video "
            },
            onMusicGen = {
                attachMenuOpen = false
                input = "/music "
            },
            onCanvas = {
                attachMenuOpen = false
                onOpenCanvas()
            },
            onNotebook = {
                attachMenuOpen = false
                onOpenNotebook()
            },
            onFiles = {
                attachMenuOpen = false
                filePicker.launch(arrayOf("*/*"))
            },
            onPersonalIntelligence = {
                attachMenuOpen = false
                onOpenPersonalIntelligence()
            },
            onTodo = {
                attachMenuOpen = false
                input = "Aaj ke to-do dikhao"
            }
        )
    }

    videoUrlToPlay?.let { url ->
        VideoPlayerDialog(url = url, onDismiss = { videoUrlToPlay = null })
    }
    imageToView?.let { bmp ->
        ImageViewerDialog(bitmap = bmp, onDismiss = { imageToView = null })
    }
    }
}

private fun decodeBitmap(uri: Uri, context: android.content.Context): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    onPlayVideo: (String) -> Unit,
    // Passes the *whole* station list this tap came from + the tapped index, not just the one
    // station — so StreamPlayerManager can remember it as a playlist for the mini-player's
    // prev/next buttons (see NowPlayingBar below).
    onPlayRadioStation: (List<com.arya.ai.tools.StreamTools.ParsedStation>, Int) -> Unit,
    onViewImage: (Bitmap) -> Unit
) {
    val isUser = message.role == ChatMessage.Role.USER
    val bubbleShape = RoundedCornerShape(16.dp)
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val videoUrl = message.playableMediaUri ?: remember(message.text) { findPlayableVideoUrl(message.text) }
    val nowPlayingRadio = remember(message.text, message.nowPlaying) { message.nowPlaying ?: findNowPlayingRadio(message.text) }
    val stationList = remember(message.text) { com.arya.ai.tools.StreamTools.parseStationList(message.text) }
    val newsItems = remember(message.text) { com.arya.ai.tools.BriefingTools.parseNewsItems(message.text) }
    val searchResults = remember(message.text) { com.arya.ai.tools.WebTools.parseSearchResults(message.text) }
    val savedSites = remember(message.text) { com.arya.ai.tools.SiteTools.parseSavedSites(message.text) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (!isUser) {
            Text(
                "ARYA",
                style = com.arya.ai.ui.theme.AryaMonoStatus,
                color = com.arya.ai.ui.theme.AryaSignal,
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            Card(
                modifier = Modifier
                    .padding(4.dp)
                    .then(
                        // The signature gold/silver duotone, echoed here (more subtly) from
                        // PortalRing's title-bar treatment: user bubbles get a gold-to-silver
                        // gradient fill (a "solid" energy source), Arya's get a thin gradient
                        // glow border on the void surface (a "receiving" glow, not a fill —
                        // keeps the two roles visually distinct at a glance).
                        if (isUser) {
                            Modifier.background(
                                brush = Brush.linearGradient(
                                    listOf(com.arya.ai.ui.theme.AryaSignal, com.arya.ai.ui.theme.AryaSilver)
                                ),
                                shape = bubbleShape
                            )
                        } else {
                            Modifier.border(
                                width = 1.dp,
                                brush = Brush.linearGradient(
                                    listOf(
                                        com.arya.ai.ui.theme.AryaSignal.copy(alpha = 0.55f),
                                        com.arya.ai.ui.theme.AryaSilver.copy(alpha = 0.35f)
                                    )
                                ),
                                shape = bubbleShape
                            )
                        }
                    )
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            if (message.text.isNotBlank()) {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.text))
                                android.widget.Toast.makeText(context, "Copy ho gaya", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    ),
                shape = bubbleShape,
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = if (isUser) Color.Transparent
                    else com.arya.ai.ui.theme.AryaInkSurface
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Single image: shown big, tap to zoom. Multiple (search_images results):
                    // a horizontal scroll row of thumbnails instead of stacking them vertically
                    // — each is still tappable into the same full-screen zoom/save/share dialog.
                    if (message.images.size == 1) {
                        Image(
                            bitmap = message.images[0].asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .height(180.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .padding(bottom = 6.dp)
                                .clickable { onViewImage(message.images[0]) }
                        )
                    } else if (message.images.size > 1) {
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        ) {
                            items(message.images) { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(120.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onViewImage(bmp) }
                                )
                            }
                        }
                    }
                    Text(
                        text = message.text.ifBlank { "…" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) com.arya.ai.ui.theme.AryaSignalOn
                        else MaterialTheme.colorScheme.onSurface
                    )
                    // Arya's search_videos/search_youtube tools only ever return links (see
                    // FIXES_LOG.md Phase 19-20) — this button is what actually plays them
                    // in-app instead of leaving the person to tap a raw URL.
                    if (videoUrl != null) {
                        // play_video/find_and_play (explicit "chalao"/"lagao" request, not just
                        // a search) auto-opens the player directly — no tap needed. `remember`
                        // keyed on the message identity (text+url) ensures this fires once per
                        // message, not on every recomposition while the bubble is on screen.
                        if (message.autoPlayVideo) {
                            var autoPlayed by remember(message.text, videoUrl) { mutableStateOf(false) }
                            LaunchedEffect(message.text, videoUrl) {
                                if (!autoPlayed) {
                                    autoPlayed = true
                                    onPlayVideo(videoUrl)
                                }
                            }
                        }
                        androidx.compose.material3.TextButton(
                            onClick = { onPlayVideo(videoUrl) },
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = if (isUser) com.arya.ai.ui.theme.AryaSignalOn else com.arya.ai.ui.theme.AryaSignal,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "Video dekho",
                                modifier = Modifier.padding(start = 6.dp),
                                color = if (isUser) com.arya.ai.ui.theme.AryaSignalOn else com.arya.ai.ui.theme.AryaSignal
                            )
                        }
                    }
                    // Radio/stream ka "abhi kya chal raha hai" indicator — playStream()'s reply text
                    // ("▶️ Stream shuru: <name> ...") gets parsed here so the station name shows up
                    // as a small chip instead of just plain text (mirrors the "Video dekho" button above).
                    if (nowPlayingRadio != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Icon(
                                Icons.Filled.Radio,
                                contentDescription = null,
                                tint = if (isUser) com.arya.ai.ui.theme.AryaSignalOn else com.arya.ai.ui.theme.AryaSignal,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                nowPlayingRadio,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(start = 6.dp),
                                color = if (isUser) com.arya.ai.ui.theme.AryaSignalOn else com.arya.ai.ui.theme.AryaSignal
                            )
                        }
                    }
                    // Radio search results ("📻 Radio stations for '<query>'") get a tap-to-play
                    // row per station instead of leaving the person to retype the name they
                    // want — see ChatViewModel.playRadioStation for the actual play call.
                    if (stationList.isNotEmpty()) {
                        Column(modifier = Modifier.padding(top = 4.dp)) {
                            stationList.forEachIndexed { index, station ->
                                androidx.compose.material3.TextButton(
                                    onClick = { onPlayRadioStation(stationList, index) },
                                    modifier = Modifier.padding(vertical = 0.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Radio,
                                        contentDescription = null,
                                        tint = if (isUser) com.arya.ai.ui.theme.AryaSignalOn else com.arya.ai.ui.theme.AryaSignal,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        station.name,
                                        modifier = Modifier.padding(start = 6.dp),
                                        color = if (isUser) com.arya.ai.ui.theme.AryaSignalOn else com.arya.ai.ui.theme.AryaSignal
                                    )
                                }
                            }
                        }
                    }
                    // News/briefing results ("📰 ...") get a "🔗 Padho" button per headline that
                    // opens the article straight in the browser (via DeviceActions.openBrowser)
                    // instead of the person having to copy a raw URL out of the message text.
                    if (newsItems.isNotEmpty()) {
                        Column(modifier = Modifier.padding(top = 4.dp)) {
                            newsItems.forEach { item ->
                                androidx.compose.material3.TextButton(
                                    onClick = { com.arya.ai.data.DeviceActions.openBrowser(context, item.url) },
                                    modifier = Modifier.padding(vertical = 0.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.OpenInNew,
                                        contentDescription = null,
                                        tint = if (isUser) com.arya.ai.ui.theme.AryaSignalOn else com.arya.ai.ui.theme.AryaSignal,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        item.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(start = 6.dp),
                                        color = if (isUser) com.arya.ai.ui.theme.AryaSignalOn else com.arya.ai.ui.theme.AryaSignal
                                    )
                                }
                            }
                        }
                    }
                    // Web search results ("🔎 ...") get a "🔗 Kholo" button per result — same
                    // open-in-browser action as the news buttons above, just a different source.
                    if (searchResults.isNotEmpty()) {
                        Column(modifier = Modifier.padding(top = 4.dp)) {
                            searchResults.forEach { result ->
                                androidx.compose.material3.TextButton(
                                    onClick = { com.arya.ai.data.DeviceActions.openBrowser(context, result.url) },
                                    modifier = Modifier.padding(vertical = 0.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.OpenInNew,
                                        contentDescription = null,
                                        tint = if (isUser) com.arya.ai.ui.theme.AryaSignalOn else com.arya.ai.ui.theme.AryaSignal,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        result.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(start = 6.dp),
                                        color = if (isUser) com.arya.ai.ui.theme.AryaSignalOn else com.arya.ai.ui.theme.AryaSignal
                                    )
                                }
                            }
                        }
                    }
                    // Saved-sites list ("🌐 Saved sites:") gets a "🌐 Kholo" button per entry,
                    // same tap-to-open pattern as everything else in this batch.
                    if (savedSites.isNotEmpty()) {
                        Column(modifier = Modifier.padding(top = 4.dp)) {
                            savedSites.forEach { site ->
                                androidx.compose.material3.TextButton(
                                    onClick = { com.arya.ai.data.DeviceActions.openBrowser(context, site.url) },
                                    modifier = Modifier.padding(vertical = 0.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.OpenInNew,
                                        contentDescription = null,
                                        tint = if (isUser) com.arya.ai.ui.theme.AryaSignalOn else com.arya.ai.ui.theme.AryaSignal,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        site.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(start = 6.dp),
                                        color = if (isUser) com.arya.ai.ui.theme.AryaSignalOn else com.arya.ai.ui.theme.AryaSignal
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Persistent bottom mini-player — Radio/stream ka "abhi kya chal raha hai" bar, chat ke bottom
 * pe input dock ke upar fixed. Mirrors [com.arya.ai.player.StreamPlayerManager.uiState] live
 * (Player.Listener-driven, see that class), so it stays in sync even if playback was
 * paused/resumed from the system notification instead of this bar.
 */
@Composable
private fun NowPlayingBar(
    state: com.arya.ai.player.StreamPlayerManager.NowPlayingUi,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = com.arya.ai.ui.theme.AryaInkSurface,
        tonalElevation = 3.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Radio,
                    contentDescription = null,
                    tint = com.arya.ai.ui.theme.AryaSignal,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        state.label.orEmpty(),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    color = if (state.isPlaying) com.arya.ai.ui.theme.AryaSignal
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        )
                        Text(
                            when {
                                state.isBuffering -> "buffering…"
                                state.isPlaying -> "LIVE"
                                else -> "paused"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrevious, enabled = state.hasPrevious) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "Pichla station",
                        tint = if (state.hasPrevious) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier
                        .size(36.dp)
                        .background(com.arya.ai.ui.theme.AryaSignal, androidx.compose.foundation.shape.CircleShape)
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause karo" else "Play karo",
                        tint = com.arya.ai.ui.theme.AryaSignalOn
                    )
                }
                IconButton(onClick = onNext, enabled = state.hasNext) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "Agla station",
                        tint = if (state.hasNext) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Band karo", tint = MaterialTheme.colorScheme.error)
                }
                Slider(
                    value = state.volume,
                    onValueChange = onVolumeChange,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = com.arya.ai.ui.theme.AryaSignal,
                        activeTrackColor = com.arya.ai.ui.theme.AryaSignal
                    )
                )
            }
        }
    }
}

/** Pulls the station/stream name out of [com.arya.ai.player.StreamPlayerManager.play]'s reply
 *  text ("▶️ Stream shuru: <name> (background me bhi chalti rahegi...)") so [ChatBubble] can show
 *  it as a small radio chip instead of leaving the person to spot it in a wall of text. */
private fun findNowPlayingRadio(text: String): String? =
    Regex("""▶️ Stream shuru: (.+?) \(background""").find(text)?.groupValues?.get(1)

/**
 * Starts one-shot speech recognition via Android's built-in [android.speech.SpeechRecognizer]
 * (same mechanism [com.arya.ai.viewmodel.AudioScribeViewModel] uses for the separate Audio
 * Scribe screen) and appends the transcribed text into the message input instead of sending it
 * automatically — the user gets to review/edit before hitting send, same as typed text.
 */
private fun startVoiceInput(
    recognizer: android.speech.SpeechRecognizer,
    onListeningChange: (Boolean) -> Unit,
    onResult: (String) -> Unit
) {
    val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
    }
    recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) { onListeningChange(true) }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { onListeningChange(false) }
        override fun onError(error: Int) { onListeningChange(false) }
        override fun onResults(results: android.os.Bundle?) {
            onListeningChange(false)
            val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
            matches?.firstOrNull()?.let { onResult(it) }
        }
        override fun onPartialResults(partialResults: android.os.Bundle?) {}
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
    })
    recognizer.startListening(intent)
}
