@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.arya.ai.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onOpenLive: () -> Unit = {},
    userName: String = ""
) {
    val messages by viewModel.messages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val pendingImages by viewModel.pendingImages.collectAsState()
    val lastReplySource by viewModel.lastReplySource.collectAsState()
    var input by remember { mutableStateOf("") }
    var toolsDrawerOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val bitmap = decodeBitmap(uri, context)
            bitmap?.let { bmp -> viewModel.attachImage(bmp) }
        }
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

    fun beginVoiceInput() {
        if (whisperRecorder.isAvailable) {
            whisperRecorder.startRecording()
            isWhisperRecording = true
            isListening = true
        } else if (recognizer != null) {
            startVoiceInput(
                recognizer,
                onListeningChange = { isListening = it },
                onResult = { text -> input = if (input.isBlank()) text else "$input $text" }
            )
        }
    }

    fun endWhisperRecordingAndTranscribe() {
        isWhisperRecording = false
        voiceCoroutineScope.launch {
            val text = whisperRecorder.stopAndTranscribe()
            isListening = false
            if (text != null) {
                input = if (input.isBlank()) text else "$input $text"
            } else if (recognizer != null) {
                // Whisper failed for this attempt — fall back to Android's built-in recognizer
                startVoiceInput(
                    recognizer,
                    onListeningChange = { isListening = it },
                    onResult = { t -> input = if (input.isBlank()) t else "$input $t" }
                )
            } else {
                android.widget.Toast.makeText(context, "Voice input fail hui, dubara try karo", android.widget.Toast.LENGTH_SHORT).show()
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
                        Text(
                            "ARYA",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            letterSpacing = 2.sp,
                            color = com.arya.ai.ui.theme.AryaSignal
                        )
                        Text(
                            "online · Groq/Gemini/OpenRouter",
                            style = com.arya.ai.ui.theme.AryaMonoStatus,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    items(messages) { message -> ChatBubble(message) }
                }
            }

            ChipRow(
                onPhoto = { imagePicker.launch("image/*") },
                onCamera = onOpenLive,
                onLive = onOpenLive
            )

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
                    IconButton(onClick = { imagePicker.launch("image/*") }) {
                        Icon(
                            Icons.Filled.Image,
                            contentDescription = "Image attach karo",
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
                            .then(
                                if (isGenerating) Modifier
                                else Modifier.combinedClickable(
                                    onClick = {
                                        // "/image <prompt>" or "/img <prompt>" — AI image generation
                                        // (see FIXES_LOG.md #11) instead of a normal chat turn.
                                        val trimmed = input.trim()
                                        if (trimmed.startsWith("/image ", ignoreCase = true)) {
                                            viewModel.generateImage(trimmed.removePrefix("/image ").removePrefix("/Image ").trim())
                                        } else if (trimmed.startsWith("/img ", ignoreCase = true)) {
                                            viewModel.generateImage(trimmed.removePrefix("/img ").removePrefix("/Img ").trim())
                                        } else {
                                            viewModel.send(input)
                                        }
                                        input = ""
                                    },
                                    onLongClick = {}
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = com.arya.ai.ui.theme.AryaSignalOn,
                                strokeWidth = 2.dp
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
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == ChatMessage.Role.USER
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
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
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            if (message.text.isNotBlank()) {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.text))
                                android.widget.Toast.makeText(context, "Copy ho gaya", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    ),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = if (isUser) com.arya.ai.ui.theme.AryaSignal
                    else com.arya.ai.ui.theme.AryaInkSurface
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    message.images.forEach { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .height(120.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .padding(bottom = 6.dp)
                        )
                    }
                    Text(
                        text = message.text.ifBlank { "…" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) com.arya.ai.ui.theme.AryaSignalOn
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

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
