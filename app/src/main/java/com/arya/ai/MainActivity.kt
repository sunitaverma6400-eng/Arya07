@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.arya.ai

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.arya.ai.data.AppDatabase
import com.arya.ai.data.UseCaseRoute
import com.arya.ai.data.UseCases
import com.arya.ai.ui.AgentSkillsScreen
import com.arya.ai.ui.ApiKeysScreen
import com.arya.ai.ui.AudioScribeScreen
import com.arya.ai.ui.ChatScreen
import com.arya.ai.ui.CommunityScreen
import com.arya.ai.ui.HomeScreen
import com.arya.ai.ui.MenuScreen
import com.arya.ai.ui.MobileActionsScreen
import com.arya.ai.ui.NotificationsScreen
import com.arya.ai.ui.OnlineModelsScreen
import com.arya.ai.ui.PersonaScreen
import com.arya.ai.ui.PromptLabScreen
import com.arya.ai.ui.SessionsScreen
import com.arya.ai.ui.SettingsScreen
import com.arya.ai.ui.TinyGardenScreen
import com.arya.ai.ui.UseCaseDetailScreen
import com.arya.ai.ui.theme.AryaTheme
import com.arya.ai.util.OnlineChatHelper
import com.arya.ai.viewmodel.AgentSkillsViewModel
import com.arya.ai.viewmodel.AudioScribeViewModel
import com.arya.ai.viewmodel.ChatViewModel
import com.arya.ai.viewmodel.MobileActionsViewModel
import com.arya.ai.viewmodel.PromptLabViewModel
import com.arya.ai.viewmodel.TinyGardenViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every permission the app can ever need — location, notifications, camera, mic — asked
 * together the moment the app opens (see the LaunchedEffect in [MainActivity.onCreate]),
 * so Arya's tools (`get_location`, `send_notification`, ...) work the first
 * time they're called instead of silently failing until some individual screen prompts.
 */
private fun allRuntimePermissions(): Array<String> {
    val perms = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        perms += Manifest.permission.POST_NOTIFICATIONS
    }
    return perms.toTypedArray()
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AryaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {

                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { /* results ignored here — each tool re-checks its own permission before acting */ }

                    // Shown once, on first launch, alongside the runtime-permission dialogs below —
                    // "Do you want to chat or share personal data to further improve AI?" This
                    // specifically gates chat-content sync to Firebase (FirebaseSync.logChatExchange);
                    // it does NOT gate the anonymous online/total-user counting in trackUser(), same
                    // as any app's basic install/DAU counting. See FirebaseSync's doc comment.
                    var showConsentDialog by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        val notGranted = allRuntimePermissions().filter {
                            ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (notGranted.isNotEmpty()) {
                            permissionLauncher.launch(notGranted.toTypedArray())
                        }
                        val prefs = com.arya.ai.util.PreferencesManager(this@MainActivity)
                        if (!prefs.dataConsentAsked) {
                            showConsentDialog = true
                        }
                        com.arya.ai.util.FirebaseSync.trackUser(this@MainActivity, prefs)
                        val micGranted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (prefs.wakeWordEnabled && micGranted) {
                            ContextCompat.startForegroundService(
                                this@MainActivity,
                                Intent(this@MainActivity, com.arya.ai.service.WakeWordService::class.java)
                            )
                        }
                    }

                    if (showConsentDialog) {
                        val consentPrefs = remember { com.arya.ai.util.PreferencesManager(this@MainActivity) }
                        AlertDialog(
                            onDismissRequest = { /* must pick one — no tap-outside dismiss */ },
                            title = { Text("Help improve Arya") },
                            text = {
                                Text(
                                    "Arya can share your chats with us so we can learn what's working, fix " +
                                        "what isn't, and make her smarter over time. Nothing is shared unless " +
                                        "you accept, and you can change your mind anytime from Settings."
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    consentPrefs.dataConsentGiven = true
                                    consentPrefs.dataConsentAsked = true
                                    showConsentDialog = false
                                }) { Text("Accept") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    consentPrefs.dataConsentGiven = false
                                    consentPrefs.dataConsentAsked = true
                                    showConsentDialog = false
                                }) { Text("Don't Accept") }
                            }
                        )
                    }

                    val navController = rememberNavController()

                    val prefsManager = remember { com.arya.ai.util.PreferencesManager(this@MainActivity) }
                    val chatDao = remember { AppDatabase.getInstance(this@MainActivity).chatDao() }
                    val noteDao = remember { AppDatabase.getInstance(this@MainActivity).noteDao() }

                    // First-launch onboarding: name poochho ek baar, permission dialogs ke
                    // baad. Jab tak naam save nahi hota, NavHost/Chat ki jagah sirf
                    // NameEntryScreen dikhta hai — neeche NavHost ke around ka if/else dekho.
                    var userNameSet by remember { mutableStateOf(prefsManager.userName.isNotBlank()) }

                    // Arya has no on-device model — every reply goes through the free online
                    // relay (Groq/Gemini/OpenRouter, see OnlineChatHelper's per-provider
                    // free-model fallback chain). (prompt, systemPrompt) -> (reply, "Provider/Model", emotion).
                    val onlineChat: suspend (String, String) -> Triple<String, String, String> = { prompt, systemPrompt ->
                        withContext(Dispatchers.IO) {
                            val result = OnlineChatHelper.generateOnlineResponse(
                                prefs = prefsManager,
                                prompt = prompt,
                                systemPrompt = systemPrompt
                            )
                            Triple(result.text, "${result.providerUsed}/${result.modelUsed}", result.emotion)
                        }
                    }
                    // Simpler variant for the feature screens (Agent Skills, Tiny Garden, Prompt
                    // Lab, Mobile Actions, Audio Scribe) that build one complete prompt themselves
                    // (system prompt + user turn already folded together) and just want text back.
                    val generateOnlineText: suspend (String) -> String = { fullPrompt ->
                        withContext(Dispatchers.IO) {
                            OnlineChatHelper.generateOnlineResponse(prefsManager, fullPrompt, "").text
                        }
                    }
                    // Streaming variant, wired into ChatScreen only (see ChatViewModel) — shows
                    // the reply word-by-word as it comes in instead of all at once.
                    val onlineChatStream: suspend (String, String, (String) -> Unit) -> Triple<String, String, String> =
                        { prompt, systemPrompt, onChunk ->
                            withContext(Dispatchers.IO) {
                                val result = OnlineChatHelper.streamOnlineResponse(prefsManager, prompt, systemPrompt, onChunk = onChunk)
                                Triple(result.text, "${result.providerUsed}/${result.modelUsed}", result.emotion)
                            }
                        }
                    val toolExecutor: suspend (com.arya.ai.inference.ToolCall) -> String = { call ->
                        com.arya.ai.util.FirebaseSync.logToolUsed(this@MainActivity, call.name)
                        com.arya.ai.tools.AryaToolRegistry.execute(this@MainActivity, call)
                    }
                    // See FirebaseSync's doc comment — no-ops unless the user opted in via the
                    // consent dialog above; shared by every ChatViewModel instance below.
                    val chatSync: (String, String) -> Unit = { userText, modelText ->
                        com.arya.ai.util.FirebaseSync.logChatExchange(this@MainActivity, prefsManager, userText, modelText)
                    }
                    // Reads typed chat replies aloud in the same emotional ElevenLabs voice as
                    // the voice/live-call paths — gated behind Settings -> "Bolke jawab do" so
                    // it doesn't start talking unexpectedly for anyone who didn't ask for it
                    // (see PreferencesManager.ttsEnabled, previously defined but unused).
                    val voiceHelper = remember { com.arya.ai.util.VoiceHelper(this@MainActivity) }
                    val activityScope = rememberCoroutineScope()
                    val speakReply: (String, String) -> Unit = { text, emotion ->
                        if (prefsManager.ttsEnabled) {
                            activityScope.launch(Dispatchers.IO) { voiceHelper.speak(text, emotion) }
                        }
                    }
                    // The current-affairs snapshot CurrentInfoWorker refreshes every 12h (see
                    // ApiKeysScreen's "Sync now") — folded into every reply's system prompt so
                    // Arya's answers stay grounded in roughly current info between syncs.
                    val ragHelper = remember { com.arya.ai.util.SimpleRagHelper(this@MainActivity) }
                    fun buildIdentityContext(): String {
                        val base = com.arya.ai.util.AryaIdentity.promptLine(prefsManager) +
                            " " + com.arya.ai.util.DateTimeContext.currentDateTimeLine() +
                            " " + com.arya.ai.util.LocationContext.currentLocationLine(this@MainActivity) +
                            " " + com.arya.ai.tools.PersonaStore.activeSystemPromptPrefix(this@MainActivity)
                        val currentInfo = ragHelper.getCurrentInfoRaw()
                        val withCurrentInfo = if (currentInfo.isNullOrBlank()) base else "$base Current-affairs reference: $currentInfo"
                        // Personal Intelligence tile (FIXES_LOG.md Phase 26) — opt-in, off by
                        // default; only appended when the user has both turned it on AND
                        // written something in PersonalIntelligenceScreen.
                        return if (prefsManager.personalIntelligenceEnabled && prefsManager.personalContext.isNotBlank()) {
                            "$withCurrentInfo User ke baare me: ${prefsManager.personalContext}"
                        } else {
                            withCurrentInfo
                        }
                    }

                    val chatViewModel = remember {
                        ChatViewModel(
                            onlineChat = onlineChat,
                            onlineChatStream = onlineChatStream,
                            chatDao = chatDao,
                            identityContext = { buildIdentityContext() },
                            toolExecutor = toolExecutor,
                            chatSync = chatSync,
                            speakReply = speakReply
                        )
                    }
                    val agentViewModel = remember { AgentSkillsViewModel(application, generateOnlineText) }
                    val tinyGardenViewModel = remember { TinyGardenViewModel(generateOnlineText) }
                    val promptLabViewModel = remember { PromptLabViewModel(generateOnlineText) }
                    val mobileActionsViewModel = remember {
                        MobileActionsViewModel(application, generateOnlineText)
                    }
                    val audioScribeViewModel = remember { AudioScribeViewModel(application, generateOnlineText) }

                    if (!userNameSet) {
                        com.arya.ai.ui.NameEntryScreen(onDone = { name ->
                            prefsManager.userName = name
                            userNameSet = true
                        })
                        return@Surface
                    }

                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            // Arya has no offline model gallery anymore — the app opens
                            // straight into Chat (online, via Arya Relay). "Use Cases" (Agent
                            // Skills, Tiny Garden, etc.) stays one tap away via Menu.
                            Box(modifier = Modifier.fillMaxSize()) {
                                ChatScreen(
                                    viewModel = chatViewModel,
                                    onBack = { navController.navigate("menu") },
                                    onOpenLive = { navController.navigate("live") },
                                    onOpenNotebook = { navController.navigate("notebook") },
                                    onOpenCanvas = { navController.navigate("canvas") },
                                    onOpenPersonalIntelligence = { navController.navigate("personal_intelligence") },
                                    userName = prefsManager.userName
                                )
                                // Update banner — reads prefs directly rather than a reactive
                                // Flow — WorkManager writes this in the background, so it'll
                                // reliably show up on the next recomposition/app open even if
                                // not instantly live.
                                var updateDismissed by remember { mutableStateOf(false) }
                                val availableUpdateVersion = prefsManager.availableUpdateVersion
                                if (!updateDismissed && availableUpdateVersion != null) {
                                    Card(
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Text(
                                                "Naya update available: v$availableUpdateVersion",
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Row(modifier = Modifier.padding(top = 10.dp)) {
                                                Button(onClick = { navController.navigate("settings") }) {
                                                    Text("Settings me jaake install karo")
                                                }
                                                TextButton(onClick = { updateDismissed = true }) {
                                                    Text("Baad me")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        composable("use_cases") {
                            HomeScreen(
                                onOpenDrawer = { navController.navigate("menu") },
                                onOpenUseCase = { useCase -> navController.navigate("usecase/${useCase.id}") }
                            )
                        }
                        composable(
                            "usecase/{useCaseId}",
                            arguments = listOf(navArgument("useCaseId") { type = NavType.StringType })
                        ) { entry ->
                            val useCase = UseCases.byId(entry.arguments?.getString("useCaseId") ?: "") ?: UseCases.aiChat
                            UseCaseDetailScreen(
                                useCase = useCase,
                                onBack = { navController.popBackStack() },
                                onOpenFeature = {
                                    val route = when (useCase.route) {
                                        UseCaseRoute.CHAT -> "chat"
                                        UseCaseRoute.VISION_CHAT -> "chat"
                                        UseCaseRoute.AGENT_CHAT -> "agent"
                                        UseCaseRoute.AUDIO_SCRIBE -> "audio_scribe"
                                        UseCaseRoute.PROMPT_LAB -> "prompt_lab"
                                        UseCaseRoute.TINY_GARDEN -> "tiny_garden"
                                        UseCaseRoute.MOBILE_ACTIONS -> "mobile_actions"
                                    }
                                    navController.navigate(route)
                                }
                            )
                        }
                        composable("chat") {
                            ChatScreen(
                                viewModel = chatViewModel,
                                onBack = { navController.popBackStack() },
                                onOpenLive = { navController.navigate("live") },
                                onOpenNotebook = { navController.navigate("notebook") },
                                onOpenCanvas = { navController.navigate("canvas") },
                                onOpenPersonalIntelligence = { navController.navigate("personal_intelligence") },
                                userName = prefsManager.userName
                            )
                        }
                        composable("notebook") {
                            com.arya.ai.ui.NotebookScreen(
                                noteDao = noteDao,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("canvas") {
                            com.arya.ai.ui.CanvasScreen(
                                initialContent = prefsManager.canvasDraft,
                                onBack = { navController.popBackStack() },
                                onDraftChanged = { prefsManager.canvasDraft = it },
                                onSendToArya = { text ->
                                    prefsManager.canvasDraft = ""
                                    chatViewModel.send(text)
                                    navController.popBackStack()
                                }
                            )
                        }
                        composable("personal_intelligence") {
                            com.arya.ai.ui.PersonalIntelligenceScreen(
                                prefsManager = prefsManager,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("live") {
                            com.arya.ai.ui.LiveConversationScreen(onClose = { navController.popBackStack() })
                        }
                        composable("agent") {
                            AgentSkillsScreen(viewModel = agentViewModel, onBack = { navController.popBackStack() })
                        }
                        composable("prompt_lab") {
                            PromptLabScreen(viewModel = promptLabViewModel, onBack = { navController.popBackStack() })
                        }
                        composable("tiny_garden") {
                            TinyGardenScreen(viewModel = tinyGardenViewModel, onBack = { navController.popBackStack() })
                        }
                        composable("mobile_actions") {
                            MobileActionsScreen(viewModel = mobileActionsViewModel, onBack = { navController.popBackStack() })
                        }
                        composable("audio_scribe") {
                            AudioScribeScreen(
                                viewModel = audioScribeViewModel,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("menu") {
                            MenuScreen(
                                onBack = { navController.popBackStack() },
                                onNavigate = { route -> navController.navigate(route) }
                            )
                        }
                        composable("api_keys") {
                            ApiKeysScreen(onBack = { navController.popBackStack() })
                        }
                        composable("sessions") {
                            SessionsScreen(
                                onBack = { navController.popBackStack() },
                                onOpenSession = { sessionId -> navController.navigate("chat_session/$sessionId") }
                            )
                        }
                        composable("persona") {
                            PersonaScreen(onBack = { navController.popBackStack() })
                        }
                        composable(
                            "chat_session/{sessionId}",
                            arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
                        ) { entry ->
                            val sessionId = entry.arguments?.getLong("sessionId") ?: -1L
                            val sessionViewModel = remember(sessionId) {
                                ChatViewModel(
                                    onlineChat = onlineChat,
                                    onlineChatStream = onlineChatStream,
                                    chatDao = chatDao,
                                    initialSessionId = sessionId,
                                    identityContext = { buildIdentityContext() },
                                    toolExecutor = toolExecutor,
                                    chatSync = chatSync,
                                    speakReply = speakReply
                                )
                            }
                            ChatScreen(
                                viewModel = sessionViewModel,
                                onBack = { navController.popBackStack() },
                                userName = prefsManager.userName
                            )
                        }
                        composable("settings") {
                            SettingsScreen(onBack = { navController.popBackStack() })
                        }
                        composable("online_models") {
                            OnlineModelsScreen(onBack = { navController.popBackStack() })
                        }
                        composable("notifications") {
                            NotificationsScreen(onBack = { navController.popBackStack() })
                        }
                        composable("community") {
                            CommunityScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
