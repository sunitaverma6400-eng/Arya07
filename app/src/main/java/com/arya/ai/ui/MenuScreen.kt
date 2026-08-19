@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.arya.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.arya.ai.tools.PriorityStore
import com.arya.ai.ui.theme.AryaEmber
import com.arya.ai.ui.theme.AryaEmberContainerDark
import com.arya.ai.ui.theme.AryaInk
import com.arya.ai.ui.theme.AryaInkSurface
import com.arya.ai.ui.theme.AryaInkSurfaceVariant
import com.arya.ai.ui.theme.AryaSignal
import org.json.JSONArray

// ---- Real-Time Skill Mirror imports (reuses existing camera/vision/voice pipeline) ----
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.camera.view.PreviewView
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.arya.ai.util.CameraFrameCapture
import com.arya.ai.util.VisionFrameProvider
import com.arya.ai.util.VoiceHelper
import com.arya.ai.tools.VisionRelay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class MenuItem(val title: String, val subtitle: String, val icon: ImageVector, val route: String)

private val MENU_ITEMS = listOf(
    MenuItem("Use Cases", "AI Chat, Vision, Audio Scribe, Agent Skills aur baaki tiles", Icons.Filled.Apps, "use_cases"),
    MenuItem("Settings", "Manage application settings", Icons.Filled.Settings, "settings"),
    MenuItem("Online free models", "Groq/Gemini/OpenRouter free model pick karo", Icons.Filled.Cloud, "online_models"),
    MenuItem("Notifications", "View scheduled notifications", Icons.Filled.Notifications, "notifications"),
    MenuItem("API Keys", "Groq/Gemini/OpenRouter/NASA/Wolfram/Picovoice", Icons.Filled.VpnKey, "api_keys"),
    MenuItem("Chat History", "Saved past conversations", Icons.Filled.History, "sessions"),
    MenuItem("Persona", "Character/role banao aur activate karo", Icons.Filled.TheaterComedy, "persona"),
    MenuItem("Notebook", "Apni notes save karo", Icons.Filled.Book, "notebook"),
    MenuItem("Community", "Kitne log jude hue hain + apne ilaake ki hyperlocal updates", Icons.Filled.Groups, "community"),
    MenuItem("अंतर्गति", "Priorities vs asal me diya gaya samay — weekly mismatch report", Icons.Filled.LocalFireDepartment, "priority_tracker"),
    MenuItem("Skill Mirror", "Camera se cooking/workout/instrument me live correction", Icons.Filled.Videocam, "skill_coach"),
    MenuItem("पुरानी यादें", "Family stories save karo, Arya unhe sunaye", Icons.Filled.Favorite, "family_memories"),
    MenuItem("Life Simulator", "Bade faisle apni priorities ke data ke hisab se soch kar dekho", Icons.Filled.Lightbulb, "life_simulator"),
    MenuItem("Family Pulse", "Family circle me kaun kitna active hai — ek call karne ka bahana", Icons.Filled.Favorite, "family_pulse"),
    MenuItem("Family Vision", "Sabki priorities ek saath — parivar ki saanjhi tasveer", Icons.Filled.Groups, "family_vision"),
    MenuItem("Family Mediator", "Data ke aadhar par ek neutral third-party jaisi baat", Icons.Filled.Balance, "family_mediator"),
    MenuItem("भविष्य को चिट्ठी", "Aaj kuch record karo, kal apni hi awaaz me wapas suno", Icons.Filled.Send, "future_letter"),
    MenuItem("Legacy Space", "Kisi ki record ki gayi yaadon se, unke andaaz me baat karo", Icons.Filled.AutoAwesome, "legacy_space"),
    MenuItem("Family Debate", "Bade faisle se pehle, kahan tension ban sakti hai andaaza lagao", Icons.Filled.Forum, "family_debate"),
    MenuItem("Ancestral Thread", "Alag-alag logo ki yaadein jodkar, unhi ki awaazon me ek kahani", Icons.Filled.AutoStories, "ancestral_thread"),
    MenuItem("Backup/Restore", "Antargati, yaadein, decisions — sab kuch ek file me save karo", Icons.Filled.CloudUpload, "backup_restore"),
    MenuItem("Time Capsule", "Poora parivaar milkar ek sealed message banaye, apni-apni awaaz me", Icons.Filled.Inventory2, "time_capsule")
)

@Composable
fun MenuScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Menu", fontWeight = FontWeight.SemiBold, color = com.arya.ai.ui.theme.AryaSignal) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = com.arya.ai.ui.theme.AryaSignal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = com.arya.ai.ui.theme.AryaInk)
            )
        },
        containerColor = com.arya.ai.ui.theme.AryaInk
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding)
        ) {
            items(MENU_ITEMS) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.1f)
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                listOf(
                                    com.arya.ai.ui.theme.AryaSignal.copy(alpha = 0.45f),
                                    com.arya.ai.ui.theme.AryaSilver.copy(alpha = 0.25f)
                                )
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                        ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = com.arya.ai.ui.theme.AryaInkSurface),
                    onClick = { onNavigate(item.route) }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = com.arya.ai.ui.theme.AryaSignal,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = com.arya.ai.ui.theme.AryaPaper
                        )
                        Text(
                            item.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = com.arya.ai.ui.theme.AryaSilver.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }
    }
}

private val PRESET_PRIORITIES = listOf("परिवार", "सेहत", "करियर", "पढ़ाई", "रिश्ते", "पैसा", "आराम", "शौक")

/**
 * Antargati (अंतर्गति) — "kya kaha vs kya kiya" mismatch tracker. Reuses [PriorityStore],
 * the same store the `set_priorities`/`log_time`/`get_priority_report` voice/chat tools write
 * to (see AryaToolRegistry) — so a check-in done here shows up if Arya is asked about it in
 * chat, and vice versa; one store, two entry points.
 */
@Composable
fun PriorityTrackerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var priorities by remember { mutableStateOf(PriorityStore.getPriorities(context)) }
    var editingPriorities by remember { mutableStateOf(priorities.length() == 0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("अंतर्गति", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = AryaSignal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AryaInk)
            )
        },
        containerColor = AryaInk
    ) { padding ->
        if (editingPriorities) {
            PriorityOnboarding(
                modifier = Modifier.padding(padding),
                onDone = { ordered ->
                    PriorityStore.setPriorities(context, ordered)
                    priorities = PriorityStore.getPriorities(context)
                    editingPriorities = false
                }
            )
        } else {
            PriorityCheckIn(
                modifier = Modifier.padding(padding),
                priorities = priorities,
                onEditPriorities = { editingPriorities = true }
            )
        }
    }
}

@Composable
private fun PriorityOnboarding(modifier: Modifier = Modifier, onDone: (List<String>) -> Unit) {
    var chosen by remember { mutableStateOf(listOf<String>()) }
    var customText by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "आपकी असली प्राथमिकताएं क्या हैं?",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "जिस क्रम में चुनें, वही क्रम अहमियत का माना जाएगा। कम से कम 2 चुनें।",
            color = Color(0xFF9C97AF),
            fontSize = 13.sp
        )

        // FlowRow needs an ExperimentalLayoutApi opt-in on this project's compose-bom version,
        // so chips wrap in fixed rows of 2 instead — same visual result, no experimental API.
        PRESET_PRIORITIES.chunked(2).forEach { rowNames ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowNames.forEach { name ->
                    val idx = chosen.indexOf(name)
                    val active = idx != -1
                    PriorityChip(
                        label = if (active) "${idx + 1}. $name" else name,
                        active = active,
                        onClick = { chosen = if (active) chosen - name else chosen + name }
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = customText,
                onValueChange = { customText = it },
                placeholder = { Text("अपनी कोई और प्राथमिकता लिखें", color = Color(0xFF6E6A80)) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedButton(onClick = {
                val v = customText.trim()
                if (v.isNotEmpty() && v !in chosen) {
                    chosen = chosen + v
                    customText = ""
                }
            }) { Text("जोड़ें") }
        }

        if (chosen.isNotEmpty()) {
            Text("क्रम ठीक करें (सबसे ज़रूरी ऊपर):", color = Color(0xFF9C97AF), fontSize = 12.sp)
            chosen.forEachIndexed { idx, name ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = AryaInkSurface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${idx + 1}. $name", color = Color.White, fontSize = 14.sp)
                        Row {
                            IconButton(onClick = {
                                if (idx > 0) chosen = chosen.toMutableList().apply { add(idx - 1, removeAt(idx)) }
                            }) { Text("↑", color = Color(0xFFB7B2CB)) }
                            IconButton(onClick = {
                                if (idx < chosen.size - 1) chosen = chosen.toMutableList().apply { add(idx + 1, removeAt(idx)) }
                            }) { Text("↓", color = Color(0xFFB7B2CB)) }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = { onDone(chosen) },
            enabled = chosen.size >= 2,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AryaEmber)
        ) { Text("शुरू करें", color = Color(0xFF1A1410), fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun PriorityChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) AryaEmber else Color.Transparent)
            .then(
                if (!active) Modifier.border(1.dp, Color(0xFF3A3650), RoundedCornerShape(20.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (active) Color(0xFF1A1410) else Color(0xFFD8D4E6), fontSize = 13.sp)
    }
}

@Composable
private fun PriorityCheckIn(modifier: Modifier = Modifier, priorities: JSONArray, onEditPriorities: () -> Unit) {
    val context = LocalContext.current
    val names = remember(priorities) { (0 until priorities.length()).map { priorities.getJSONObject(it).getString("name") } }
    var hours by remember(names) { mutableStateOf(names.associateWith { 0f }) }
    var note by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var forecast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        report = PriorityStore.getReport(context, 7)
        forecast = PriorityStore.getWeekdayForecast(context)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        forecast?.let { f ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                    Text(f, color = Color(0xFFE0C368), fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(14.dp))
                }
            }
        }
        item {
            Text("आज कितने घंटे किसमें गए?", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        items(names) { name ->
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, color = Color(0xFFD8D4E6), fontSize = 14.sp)
                    Text("${hours[name] ?: 0f} घं", color = AryaEmber, fontSize = 14.sp)
                }
                Slider(
                    value = hours[name] ?: 0f,
                    onValueChange = { v -> hours = hours.toMutableMap().apply { put(name, v) }; saved = false },
                    valueRange = 0f..12f,
                    steps = 23,
                    colors = SliderDefaults.colors(thumbColor = AryaEmber, activeTrackColor = AryaEmber)
                )
            }
        }
        item {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it; saved = false },
                placeholder = { Text("आज का एक पल जो याद रहेगा (वैकल्पिक)", color = Color(0xFF6E6A80)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
        }
        item {
            Button(
                onClick = {
                    names.forEach { name ->
                        val h = (hours[name] ?: 0f).toDouble()
                        if (h > 0) PriorityStore.logTime(context, name, h, note.ifBlank { null })
                    }
                    saved = true
                    report = PriorityStore.getReport(context, 7)
                },
                enabled = !saved,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AryaEmber)
            ) {
                Text(
                    if (saved) "आज दर्ज हो चुका ✓" else "आज का दिन दर्ज करें",
                    color = Color(0xFF1A1410),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("पिछले 7 दिन", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        report ?: "लोड हो रहा है...",
                        color = Color(0xFFB7B2CB),
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            }
        }
        item {
            OutlinedButton(onClick = onEditPriorities, modifier = Modifier.fillMaxWidth()) {
                Text("प्राथमिकताएं बदलें")
            }
        }
    }
}

// ============================================================================
// Real-Time Skill Mirror — camera-based live coaching for physical skills
// (cooking, workout, playing an instrument). Deliberately reuses the exact
// camera/vision/voice pipeline LiveConversationScreen already uses
// (CameraFrameCapture -> VisionFrameProvider -> VisionRelay -> VoiceHelper)
// instead of a new pipeline — same relay call, just a coaching-specific
// system prompt and a timer loop instead of a voice trigger.
//
// Honest caveat (same spirit as GeminiLiveSession's doc comment): each
// correction round-trips through the relay to Gemini's vision endpoint, so
// on a slow connection or a rate-limited free API key, corrections will lag
// behind what's actually happening in front of the camera — this is a
// still-image-every-few-seconds "mirror", not true continuous video
// understanding. CHECK_INTERVAL_MS was lowered from 6000ms to 3000ms per
// request for faster feedback — if this starts hitting the relay/API key's
// rate limit under real use, raise it back up rather than lowering further.
// ============================================================================

private val SKILL_PRESETS = listOf("खाना बनाना", "वर्कआउट", "वाद्य यंत्र")
private const val SKILL_CHECK_INTERVAL_MS = 3000L

private fun skillSystemPrompt(skill: String): String = when (skill) {
    "खाना बनाना" -> "Tum ek cooking coach ho jo camera se dekh kar batati ho. Cutting technique, " +
        "aanch, ya safety me kuch galat lage to turant chhota sa (1-2 line) correction do, spoken-style. " +
        "Sab theek lage to bas chhota encouragement do."
    "वर्कआउट" -> "Tum ek fitness coach ho jo camera se exercise form dekh rahi ho. Form galat ho " +
        "(jaise kamar/ghutna galat angle) to turant chhota sa (1-2 line) correction do, spoken-style. " +
        "Form theek ho to chhota encouragement do."
    "वाद्य यंत्र" -> "Tum ek music teacher ho jo camera se hand position/posture dekh rahi ho. Kuch " +
        "galat lage to turant chhota sa (1-2 line) correction do, spoken-style. Theek lage to encouragement do."
    else -> "Tum ek live skill coach ho jo camera se '$skill' practice dekh rahi ho. Kuch sudhaarne " +
        "layak lage to turant chhota sa (1-2 line) correction do, spoken-style."
}

@Composable
fun SkillCoachScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val voiceHelper = remember { VoiceHelper(context) }

    var selectedSkill by remember { mutableStateOf<String?>(null) }
    var customSkill by remember { mutableStateOf("") }
    var coaching by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    var cameraCapture by remember { mutableStateOf<CameraFrameCapture?>(null) }
    var isFrontFacing by remember { mutableStateOf(false) }
    var practicePlanSkill by remember { mutableStateOf<String?>(null) }
    var practicePlanText by remember { mutableStateOf("") }
    var practicePlanStatus by remember { mutableStateOf("idle") } // idle | loading | done | error
    val prefs = remember { com.arya.ai.util.PreferencesManager(context) }

    DisposableEffect(Unit) {
        onDispose { cameraCapture?.stop() }
    }

    LaunchedEffect(coaching, selectedSkill) {
        if (!coaching || selectedSkill.isNullOrBlank()) return@LaunchedEffect
        val skill = selectedSkill!!
        val prompt = skillSystemPrompt(skill)
        while (isActive) {
            delay(SKILL_CHECK_INTERVAL_MS)
            val frame = VisionFrameProvider.freshFrame(SKILL_CHECK_INTERVAL_MS + 3000)
            if (frame != null) {
                val result = withContext(Dispatchers.IO) {
                    VisionRelay.describeImage(frame, "Ek chhota live correction do", prompt)
                }
                if (result != null && isActive) {
                    feedback = result.text
                    com.arya.ai.tools.SkillCoachLog.logCorrection(context, skill, result.text)
                    scope.launch { voiceHelper.speak(result.text, result.emotion) }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Skill Mirror", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = {
                        cameraCapture?.stop()
                        onBack()
                    }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = AryaSignal) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AryaInk)
            )
        },
        containerColor = AryaInk
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (selectedSkill == null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        "आज क्या practice कर रहे हो?",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Camera on rahega, Arya har ${SKILL_CHECK_INTERVAL_MS / 1000} second me dekh kar bolegi.",
                        color = Color(0xFF9C97AF),
                        fontSize = 13.sp
                    )
                    SKILL_PRESETS.forEach { skill ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = AryaInkSurface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { selectedSkill = skill },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) { Text(skill, color = Color.White, fontSize = 15.sp) }
                                val recentCount = remember(skill) {
                                    com.arya.ai.tools.SkillCoachLog.getRecentCorrections(context, skill).size
                                }
                                if (recentCount >= 3) {
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedButton(
                                        onClick = { practicePlanSkill = skill },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("📋 इस हफ़्ते का practice plan देखो", fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = customSkill,
                            onValueChange = { customSkill = it },
                            placeholder = { Text("kuch aur likho...", color = Color(0xFF6E6A80)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Button(
                            onClick = { if (customSkill.isNotBlank()) selectedSkill = customSkill.trim() },
                            colors = ButtonDefaults.buttonColors(containerColor = AryaEmber)
                        ) { Text("शुरू करें", color = Color(0xFF1A1410)) }
                    }

                    practicePlanSkill?.let { skill ->
                        LaunchedEffect(skill) {
                            practicePlanStatus = "loading"
                            practicePlanText = ""
                            try {
                                val corrections = withContext(Dispatchers.IO) {
                                    com.arya.ai.tools.SkillCoachLog.getRecentCorrections(context, skill)
                                }
                                val prompt = com.arya.ai.tools.SkillCoachLog.buildPracticePlanPrompt(skill, corrections)
                                val result = withContext(Dispatchers.IO) {
                                    com.arya.ai.util.OnlineChatHelper.generateOnlineResponse(prefs, "Practice plan do", prompt)
                                }
                                practicePlanText = result.text
                                practicePlanStatus = "done"
                            } catch (e: Exception) {
                                practicePlanStatus = "error"
                            }
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("$skill — practice plan", color = AryaEmber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    IconButton(onClick = { practicePlanSkill = null }) { Text("✕", color = Color.White) }
                                }
                                Spacer(Modifier.height(8.dp))
                                when (practicePlanStatus) {
                                    "loading" -> Text("बना रही हूं...", color = Color(0xFF9C97AF), fontSize = 13.sp)
                                    "error" -> Text("❌ नहीं बन पाया, दोबारा try करो", color = Color(0xFFD9636B), fontSize = 13.sp)
                                    "done" -> Text(practicePlanText, color = Color(0xFFD8D4E6), fontSize = 14.sp, lineHeight = 20.sp)
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).also { previewView ->
                                val capture = CameraFrameCapture(ctx, lifecycleOwner, previewView)
                                capture.start(intervalMs = 2500)
                                cameraCapture = capture
                                coaching = true
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.15f))
                    )
                    // Front camera lets the person point the phone at themselves — useful for
                    // workout form or instrument hand-position, where "cooking" (pointing at the
                    // board/pan) needs the back camera. switchLens() rebinds without resetting
                    // the correction-interval timer (see CameraFrameCapture's doc comment).
                    IconButton(
                        onClick = {
                            cameraCapture?.switchLens()
                            isFrontFacing = !isFrontFacing
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .background(AryaInkSurface.copy(alpha = 0.6f), androidx.compose.foundation.shape.CircleShape)
                    ) {
                        Icon(Icons.Filled.Cameraswitch, contentDescription = "Camera badlo", tint = Color.White)
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface.copy(alpha = 0.92f))) {
                            Column(Modifier.padding(14.dp)) {
                                Text(selectedSkill ?: "", color = AryaEmber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    feedback.ifBlank { "Dekh rahi hoon... ${SKILL_CHECK_INTERVAL_MS / 1000}s me pehla correction aayega." },
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                coaching = false
                                cameraCapture?.stop()
                                cameraCapture = null
                                selectedSkill = null
                                feedback = ""
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("रोकें") }
                    }
                }
            }
        }
    }
}

// ============================================================================
// Memory Continuity — family stories, resurfaced later. Arya narrates in her own
// voice (see FamilyMemoryStore's doc comment for why — no real voice-cloning here),
// but always says whose story it is.
// ============================================================================

@Composable
fun FamilyMemoriesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voiceHelper = remember { VoiceHelper(context) }

    var memories by remember { mutableStateOf(com.arya.ai.tools.FamilyMemoryStore.getAll(context)) }
    var showAddForm by remember { mutableStateOf(false) }
    var showRecordForm by remember { mutableStateOf(false) }
    var personFilter by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<com.arya.ai.tools.FamilyMemory?>(null) }
    var playingClonedVoice by remember { mutableStateOf(false) }

    fun refresh() { memories = com.arya.ai.tools.FamilyMemoryStore.getAll(context) }

    val people = remember(memories) { memories.map { it.person }.distinct() }
    val visible = remember(memories, personFilter) {
        if (personFilter.isNullOrBlank()) memories else memories.filter { it.person == personFilter }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("पुरानी यादें", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = AryaSignal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AryaInk)
            )
        },
        containerColor = AryaInk
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Button(
                    onClick = {
                        val mem = com.arya.ai.tools.FamilyMemoryStore.getRandomMemory(context, personFilter)
                        if (mem != null) {
                            playing = mem
                            val voiceId = com.arya.ai.tools.FamilyMemoryStore.getPersonVoiceId(context, mem.person)
                            playingClonedVoice = voiceId != null
                            val text = "Ye kahani ${mem.person} ne batayi thi. ${mem.title}. ${mem.story}"
                            scope.launch {
                                val spokeInClonedVoice = if (voiceId != null) {
                                    voiceHelper.speakClonedVoice(text, voiceId)
                                } else false
                                if (!spokeInClonedVoice) {
                                    playingClonedVoice = false
                                    voiceHelper.speak(text)
                                }
                            }
                        }
                    },
                    enabled = memories.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AryaEmber)
                ) {
                    Text(
                        if (personFilter.isNullOrBlank()) "🪔 एक याद सुनाओ" else "🪔 $personFilter की एक याद सुनाओ",
                        color = Color(0xFF1A1410),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            playing?.let { mem ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(mem.person, color = AryaEmber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (playingClonedVoice) "🎙 ${mem.person} की अपनी आवाज़ में" else "Arya की आवाज़ में",
                                    color = Color(0xFF6E6A80),
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(mem.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            mem.photoPath?.let { path ->
                                val bmp = remember(path) {
                                    try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null }
                                }
                                bmp?.let {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = mem.title,
                                        modifier = Modifier.fillMaxWidth().height(180.dp).padding(top = 8.dp),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(mem.story, color = Color(0xFFD8D4E6), fontSize = 14.sp, lineHeight = 20.sp)
                        }
                    }
                }
            }

            if (people.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PriorityChip(label = "सभी", active = personFilter == null, onClick = { personFilter = null })
                        people.forEach { p ->
                            PriorityChip(label = p, active = personFilter == p, onClick = { personFilter = p })
                        }
                    }
                }
            }

            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showAddForm = !showAddForm; showRecordForm = false },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (showAddForm) "बंद करें" else "+ नई याद जोड़ें") }
                    OutlinedButton(
                        onClick = { showRecordForm = !showRecordForm; showAddForm = false },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (showRecordForm) "बंद करें" else "🎙 असली आवाज़ जोड़ें") }
                }
            }

            if (showAddForm) {
                item {
                    AddMemoryForm(
                        onSave = { person, title, story, photoPath ->
                            com.arya.ai.tools.FamilyMemoryStore.addMemory(context, person, title, story, photoPath)
                            refresh()
                            showAddForm = false
                        }
                    )
                }
            }

            if (showRecordForm) {
                item {
                    RecordVoiceForm(
                        onDone = { showRecordForm = false }
                    )
                }
            }

            if (visible.isEmpty()) {
                item {
                    Text(
                        "अभी कोई याद save नहीं है। ऊपर से एक जोड़ें।",
                        color = Color(0xFF6E6A80),
                        fontSize = 13.sp
                    )
                }
            }

            items(visible) { mem ->
                Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(mem.person, color = AryaEmber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                if (mem.photoPath != null) {
                                    Text(" 📷", fontSize = 12.sp)
                                }
                            }
                            IconButton(onClick = {
                                com.arya.ai.tools.FamilyMemoryStore.deleteMemory(context, mem.id)
                                refresh()
                                if (playing?.id == mem.id) playing = null
                            }) { Text("🗑", fontSize = 14.sp) }
                        }
                        Text(mem.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(mem.story, color = Color(0xFFB7B2CB), fontSize = 13.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddMemoryForm(onSave: (person: String, title: String, story: String, photoPath: String?) -> Unit) {
    val context = LocalContext.current
    var person by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var story by remember { mutableStateOf("") }
    var photoBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            photoBitmap = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                    android.graphics.ImageDecoder.decodeBitmap(source)
                } else {
                    @Suppress("DEPRECATION")
                    android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = person,
                onValueChange = { person = it },
                placeholder = { Text("किसकी याद है? (जैसे Dadi, Papa)", color = Color(0xFF6E6A80)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("छोटा title", color = Color(0xFF6E6A80)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = story,
                onValueChange = { story = it },
                placeholder = { Text("पूरी कहानी यहां लिखें...", color = Color(0xFF6E6A80)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4
            )
            if (photoBitmap != null) {
                Box {
                    Image(
                        bitmap = photoBitmap!!.asImageBitmap(),
                        contentDescription = "चुनी हुई फ़ोटो",
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                    IconButton(
                        onClick = { photoBitmap = null },
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) { Text("✕", color = Color.White) }
                }
            } else {
                OutlinedButton(
                    onClick = { photoPicker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("📷 फ़ोटो जोड़ो (वैकल्पिक)") }
            }
            Button(
                onClick = {
                    if (person.isNotBlank() && story.isNotBlank()) {
                        val photoPath = photoBitmap?.let { bmp ->
                            com.arya.ai.tools.FamilyMemoryStore.savePhoto(context, bmp)?.absolutePath
                        }
                        onSave(person.trim(), title.trim(), story.trim(), photoPath)
                    }
                },
                enabled = person.isNotBlank() && story.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AryaEmber)
            ) { Text("याद save करें", color = Color(0xFF1A1410), fontWeight = FontWeight.SemiBold) }
        }
    }
}

/**
 * Records ~10-15 seconds of a family member's voice (consent assumed already given — see
 * FISHAUDIO_TTS relay doc comment) and uploads it via [com.arya.ai.util.FamilyVoiceRecorder]
 * to get back a Fish Audio voice_id, saved against that person in [FamilyMemoryStore]. After
 * this, "एक याद सुनाओ" for that person plays in their own cloned voice instead of Arya's.
 */
@Composable
private fun RecordVoiceForm(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { com.arya.ai.util.FamilyVoiceRecorder(context) }

    var personName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("idle") } // idle | recording | uploading | error | done
    var errorText by remember { mutableStateOf("") }

    val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.RECORD_AUDIO
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "10-15 second ka saaf audio record karo — sirf ek vyakti bole, background shor kam ho.",
                color = Color(0xFF9C97AF),
                fontSize = 12.sp
            )
            OutlinedTextField(
                value = personName,
                onValueChange = { personName = it },
                placeholder = { Text("Ye kiski awaaz hai? (jaise Dadi)", color = Color(0xFF6E6A80)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = status == "idle" || status == "error"
            )

            if (!hasMicPermission) {
                Text("Mic permission chahiye — Settings me Arya ko permission do.", color = Color(0xFFD9636B), fontSize = 12.sp)
            }

            when (status) {
                "idle", "error" -> {
                    if (status == "error") {
                        Text("❌ Save nahi hua: $errorText — dobara try karo.", color = Color(0xFFD9636B), fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            if (personName.isNotBlank() && hasMicPermission) {
                                recorder.startRecording()
                                status = "recording"
                            }
                        },
                        enabled = personName.isNotBlank() && hasMicPermission,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AryaEmber)
                    ) { Text("🎙 Recording शुरू करो", color = Color(0xFF1A1410), fontWeight = FontWeight.SemiBold) }
                }
                "recording" -> {
                    Text("🔴 Record ho raha hai...", color = Color(0xFFD9636B), fontSize = 13.sp)
                    Button(
                        onClick = {
                            status = "uploading"
                            scope.launch {
                                val voiceId = recorder.stopAndClone(personName.trim())
                                if (voiceId != null) {
                                    com.arya.ai.tools.FamilyMemoryStore.setPersonVoiceId(context, personName.trim(), voiceId)
                                    status = "done"
                                } else {
                                    errorText = recorder.lastError ?: "pata nahi kya hua"
                                    status = "error"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AryaEmber)
                    ) { Text("रोकें और save करो", color = Color(0xFF1A1410), fontWeight = FontWeight.SemiBold) }
                    OutlinedButton(
                        onClick = { recorder.cancelRecording(); status = "idle" },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("रद्द करें") }
                }
                "uploading" -> {
                    Text("⏳ Voice save ho rahi hai...", color = Color(0xFF9C97AF), fontSize = 13.sp)
                }
                "done" -> {
                    Text("✅ '$personName' ki awaaz save ho gayi — ab unki yaadein isi awaaz me sunengi.", color = Color(0xFF7FA37A), fontSize = 13.sp)
                    Button(
                        onClick = onDone,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AryaEmber)
                    ) { Text("ठीक है", color = Color(0xFF1A1410), fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

// ============================================================================
// Life Simulator — grounds a big-decision question in the user's own Antargati
// (priority-mismatch) data, then sends it through the same online-LLM pipeline
// ChatScreen uses (OnlineChatHelper), so no new AI infrastructure needed. See
// LifeSimulator's doc comment (tools/AryaToolRegistry.kt) for why this is a
// single-shot call here rather than a tool-in-chat.
// ============================================================================

@Composable
fun LifeSimulatorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { com.arya.ai.util.PreferencesManager(context) }
    val voiceHelper = remember { com.arya.ai.util.VoiceHelper(context) }

    var category by remember { mutableStateOf(com.arya.ai.tools.DECISION_CATEGORIES.first()) }
    var question by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("idle") } // idle | loading | done | error
    var resultText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    var pastDecisions by remember { mutableStateOf(listOf<com.arya.ai.tools.Decision>()) }
    var showRecordSelfVoice by remember { mutableStateOf(false) }
    var hasSelfVoice by remember { mutableStateOf(com.arya.ai.tools.FamilyMemoryStore.getPersonVoiceId(context, "Main") != null) }
    var currentDecisionId by remember { mutableStateOf<Int?>(null) }
    var evaluatingId by remember { mutableStateOf<Int?>(null) }
    var branches by remember { mutableStateOf(listOf<com.arya.ai.tools.LifeSimulator.FutureBranch>()) }
    var branchStatus by remember { mutableStateOf("idle") } // idle | loading | done | error
    var speakingBranchIndex by remember { mutableStateOf(-1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Life Simulator", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = AryaSignal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AryaInk)
            )
        },
        containerColor = AryaInk
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    "कोई बड़ा फ़ैसला सोच रहे हो?",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "जैसे \"अगर मैं ये job छोड़ दूं?\" — Arya तुम्हारी अपनी Antargati priorities " +
                        "के data के हिसाब से सोचेगी, generic advice नहीं देगी।",
                    color = Color(0xFF9C97AF),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.arya.ai.tools.DECISION_CATEGORIES.forEach { cat ->
                        PriorityChip(label = cat, active = category == cat, onClick = { category = cat })
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    placeholder = { Text("अपना सवाल लिखो...", color = Color(0xFF6E6A80)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    enabled = status != "loading"
                )
            }
            item {
                Button(
                    onClick = {
                        if (question.isNotBlank()) {
                            status = "loading"
                            resultText = ""
                            scope.launch {
                                try {
                                    val systemPrompt = withContext(Dispatchers.IO) {
                                        com.arya.ai.tools.LifeSimulator.buildSystemPrompt(context, category)
                                    }
                                    val result = withContext(Dispatchers.IO) {
                                        com.arya.ai.util.OnlineChatHelper.generateOnlineResponse(
                                            prefs, question, systemPrompt
                                        )
                                    }
                                    resultText = result.text
                                    status = "done"
                                    val newId = withContext(Dispatchers.IO) {
                                        com.arya.ai.tools.DecisionLog.save(context, category, question, result.text)
                                    }
                                    currentDecisionId = newId
                                    pastDecisions = withContext(Dispatchers.IO) {
                                        com.arya.ai.tools.DecisionLog.getByCategory(context, category, excludeId = newId)
                                    }
                                } catch (e: Exception) {
                                    errorText = e.message ?: "kuch galat ho gaya"
                                    status = "error"
                                }
                            }
                        }
                    },
                    enabled = question.isNotBlank() && status != "loading",
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AryaEmber)
                ) {
                    Text(
                        if (status == "loading") "सोच रही हूं..." else "🔮 Simulate करो",
                        color = Color(0xFF1A1410),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (status == "error") {
                item {
                    Text("❌ $errorText", color = Color(0xFFD9636B), fontSize = 13.sp)
                }
            }
            if (status == "done" && resultText.isNotBlank()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(resultText, color = Color(0xFFD8D4E6), fontSize = 14.sp, lineHeight = 21.sp)
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = {
                            branchStatus = "loading"
                            branches = emptyList()
                            scope.launch {
                                try {
                                    val branchPrompt = withContext(Dispatchers.IO) {
                                        com.arya.ai.tools.LifeSimulator.buildBranchingPrompt(context, question)
                                    }
                                    val result = withContext(Dispatchers.IO) {
                                        com.arya.ai.util.OnlineChatHelper.generateOnlineResponse(prefs, question, branchPrompt)
                                    }
                                    val parsed = com.arya.ai.tools.LifeSimulator.parseBranches(result.text)
                                    if (parsed.isEmpty()) {
                                        branchStatus = "error"
                                    } else {
                                        branches = parsed
                                        branchStatus = "done"
                                    }
                                } catch (e: Exception) {
                                    branchStatus = "error"
                                }
                            }
                        },
                        enabled = branchStatus != "loading",
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (branchStatus == "loading") "3 भविष्य सोच रही हूं..." else "🔀 3 मुमकिन भविष्य सुनो")
                    }
                }
                if (branchStatus == "error") {
                    item {
                        Text("❌ भविष्य नहीं बना पाई, दोबारा try करो", color = Color(0xFFD9636B), fontSize = 12.sp)
                    }
                }
                itemsIndexed(branches) { index, branch ->
                    Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(branch.label, color = AryaEmber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                IconButton(onClick = {
                                    scope.launch {
                                        speakingBranchIndex = index
                                        val voiceId = if (hasSelfVoice) com.arya.ai.tools.FamilyMemoryStore.getPersonVoiceId(context, "Main") else null
                                        val spoke = if (voiceId != null) voiceHelper.speakClonedVoice(branch.text, voiceId) else false
                                        if (!spoke) voiceHelper.speak(branch.text)
                                        speakingBranchIndex = -1
                                    }
                                }) { Text(if (speakingBranchIndex == index) "⏸" else "🎙", fontSize = 14.sp) }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(branch.text, color = Color(0xFFD8D4E6), fontSize = 14.sp, lineHeight = 20.sp)
                        }
                    }
                }
            }

            if (pastDecisions.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text("पहले क्या सोचा था — इसी category में", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    val trackRecord = remember(category, pastDecisions) {
                        com.arya.ai.tools.DecisionLog.getTrackRecordSummary(context, category)
                    }
                    if (trackRecord != null) {
                        Text(
                            "📊 $trackRecord",
                            color = Color(0xFF7FA37A),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    if (!hasSelfVoice) {
                        Text(
                            "अपनी आवाज़ record करो तो ये पुराने जवाब तुम्हारी अपनी आवाज़ में सुन पाओगे।",
                            color = Color(0xFF6E6A80),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        OutlinedButton(
                            onClick = { showRecordSelfVoice = true },
                            modifier = Modifier.padding(top = 8.dp)
                        ) { Text("🎙 अपनी आवाज़ record करो") }
                    }
                }
                items(pastDecisions) { dec ->
                    val dateStr = remember(dec.timestampMs) {
                        java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US).format(java.util.Date(dec.timestampMs))
                    }
                    val ageDays = remember(dec.timestampMs) {
                        ((System.currentTimeMillis() - dec.timestampMs) / (1000L * 60 * 60 * 24)).toInt()
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(dateStr, color = Color(0xFF6E6A80), fontSize = 11.sp)
                                if (hasSelfVoice) {
                                    IconButton(onClick = {
                                        scope.launch {
                                            val voiceId = com.arya.ai.tools.FamilyMemoryStore.getPersonVoiceId(context, "Main")
                                            if (voiceId != null) {
                                                val spoke = voiceHelper.speakClonedVoice(dec.answer, voiceId)
                                                if (!spoke) voiceHelper.speak(dec.answer)
                                            }
                                        }
                                    }) { Text("🎙", fontSize = 14.sp) }
                                }
                            }
                            Text(dec.question, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(dec.answer, color = Color(0xFFB7B2CB), fontSize = 13.sp, maxLines = 4)

                            // Self-Correcting Advice Loop: once this decision is old enough,
                            // let the person check whether things actually moved the way the
                            // advice suggested — and remember the verdict for future advice
                            // in this same category (see DecisionLog.getTrackRecordSummary).
                            when {
                                dec.verdict != null -> {
                                    val (emoji, label) = when (dec.verdict) {
                                        "sahi_disha" -> "✅" to "सही दिशा में गया"
                                        "ulta_hua" -> "⚠️" to "उल्टा हुआ"
                                        else -> "➖" to "कोई बदलाव नहीं"
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text("$emoji $label", color = AryaEmber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    if (!dec.verdictNote.isNullOrBlank()) {
                                        Text(dec.verdictNote, color = Color(0xFF9C97AF), fontSize = 12.sp)
                                    }
                                }
                                ageDays >= com.arya.ai.tools.DecisionLog.MIN_AGE_DAYS_FOR_EVALUATION -> {
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = {
                                            evaluatingId = dec.id
                                            scope.launch {
                                                try {
                                                    val evalPrompt = withContext(Dispatchers.IO) {
                                                        com.arya.ai.tools.DecisionLog.evaluationPrompt(context, dec)
                                                    }
                                                    val evalResult = withContext(Dispatchers.IO) {
                                                        com.arya.ai.util.OnlineChatHelper.generateOnlineResponse(prefs, evalPrompt, "")
                                                    }
                                                    withContext(Dispatchers.IO) {
                                                        com.arya.ai.tools.DecisionLog.recordVerdictFromLlmReply(context, dec.id, evalResult.text)
                                                    }
                                                    pastDecisions = withContext(Dispatchers.IO) {
                                                        com.arya.ai.tools.DecisionLog.getByCategory(context, category, excludeId = currentDecisionId)
                                                    }
                                                } finally {
                                                    evaluatingId = null
                                                }
                                            }
                                        },
                                        enabled = evaluatingId != dec.id
                                    ) { Text(if (evaluatingId == dec.id) "जांच रही हूं..." else "इसका नतीजा जांचो") }
                                }
                            }
                        }
                    }
                }
            }

            if (showRecordSelfVoice) {
                item {
                    Text(
                        "नीचे \"Main\" लिखो (या जो भी नाम बाद में याद रहे) ताकि Arya पहचान सके ये तुम्हारी अपनी आवाज़ है।",
                        color = Color(0xFF9C97AF),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    RecordVoiceForm(onDone = {
                        showRecordSelfVoice = false
                        hasSelfVoice = com.arya.ai.tools.FamilyMemoryStore.getPersonVoiceId(context, "Main") != null
                    })
                }
            }
        }
    }
}

// ============================================================================
// Family Circle join form — shared by FamilyPulseScreen and FamilyVisionBoardScreen so
// joining once (via either screen) works for both, without duplicating this UI twice.
// ============================================================================

@Composable
private fun FamilyCircleJoinForm(onJoined: (code: String, nickname: String) -> Unit) {
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "परिवार के सबको एक जैसा कोई भी code बताओ (जैसे \"SHARMA123\") — जो भी ये code डालेगा, वो जुड़ जाएगा।",
            color = Color(0xFF9C97AF),
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            placeholder = { Text("Family code", color = Color(0xFF6E6A80)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it },
            placeholder = { Text("तुम्हारा नाम (जैसे Dadi, Papa)", color = Color(0xFF6E6A80)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(
            onClick = {
                if (code.isNotBlank() && nickname.isNotBlank()) {
                    val prefs = com.arya.ai.util.PreferencesManager(context)
                    com.arya.ai.tools.FamilyCircleStore.save(context, code, nickname)
                    com.arya.ai.util.FirebaseSync.joinFamilyCircle(context, code, prefs.installId, nickname)
                    onJoined(code.trim(), nickname.trim())
                }
            },
            enabled = code.isNotBlank() && nickname.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AryaEmber)
        ) { Text("Circle जॉइन करो", color = Color(0xFF1A1410), fontWeight = FontWeight.SemiBold) }
    }
}

/** Days between a "yyyy-MM-dd" date string and today — null if unparseable/blank. */
private fun daysSince(dateStr: String): Int? {
    if (dateStr.isBlank()) return null
    return try {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val then = fmt.parse(dateStr) ?: return null
        val diffMs = System.currentTimeMillis() - then.time
        (diffMs / (1000 * 60 * 60 * 24)).toInt()
    } catch (e: Exception) {
        null
    }
}

// ============================================================================
// Family Pulse — shows how recently each Family Circle member has checked in
// (Antargati) or added a memory. This is an ACTIVITY signal only — how often
// someone opens the app — not a measurement of their health, mood, or loneliness.
// Software can't responsibly infer those from app-usage gaps alone; what this
// screen gives family is a low-effort nudge ("Dadi ne 6 din se check-in nahi
// kiya — call kar lo?"), not a diagnosis. The UI says this explicitly too.
// ============================================================================

@Composable
fun FamilyPulseScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var circle by remember { mutableStateOf(com.arya.ai.tools.FamilyCircleStore.get(context)) }
    var members by remember { mutableStateOf(listOf<com.arya.ai.util.FirebaseSync.FamilyCircleMember>()) }

    LaunchedEffect(circle) {
        val c = circle ?: return@LaunchedEffect
        val (code, _) = c
        val prefs = com.arya.ai.util.PreferencesManager(context)
        withContext(Dispatchers.IO) {
            val lastCheckin = com.arya.ai.tools.PriorityStore.getLastCheckinDate(context)
            val lastMemoryDate = com.arya.ai.tools.FamilyMemoryStore.getAll(context)
                .maxByOrNull { it.addedAt }?.addedAt?.let {
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(it))
                }
            val topPriority = com.arya.ai.tools.PriorityStore.getPriorities(context)
                .let { if (it.length() > 0) it.getJSONObject(0).getString("name") else null }
            val recentCount = com.arya.ai.tools.PriorityStore.getRecentCheckinCount(context, 14)
            com.arya.ai.util.FirebaseSync.updateMyCircleSignal(
                context, code, prefs.installId, lastCheckin, lastMemoryDate, topPriority, recentCount
            )
        }
        com.arya.ai.util.FirebaseSync.observeFamilyCircle(context, code) { members = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Family Pulse", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = AryaSignal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AryaInk)
            )
        },
        containerColor = AryaInk
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(20.dp)) {
            if (circle == null) {
                FamilyCircleJoinForm(onJoined = { code, nickname -> circle = code to nickname })
            } else {
                var showQr by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Code: ${circle!!.first}", color = Color(0xFF9C97AF), fontSize = 12.sp, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { showQr = !showQr }) { Text(if (showQr) "छुपाओ" else "📱 QR दिखाओ") }
                }
                if (showQr) {
                    val qrBitmap = remember(circle) {
                        com.arya.ai.tools.UtilityTools.generateQrBitmap(circle!!.first)
                    }
                    qrBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Family circle QR",
                            modifier = Modifier
                                .padding(top = 10.dp)
                                .size(180.dp)
                                .align(Alignment.CenterHorizontally)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "ये सिर्फ activity ka signal hai — kisi ki health ya mood ki jaanch nahi. " +
                        "Bas call karne ka ek bahana samjho.",
                    color = Color(0xFF9C97AF),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(14.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(members) { m ->
                        val days = daysSince(m.lastCheckinDate)
                        // Sudden-Change Detection: same gap in days means something different
                        // for someone who was checking in almost daily vs someone who was
                        // already sporadic — recentCheckinCount14d (pure activity count, see
                        // FirebaseSync's doc comment) tells those apart. -1 means that device
                        // hasn't reported this signal yet (older data) — treated as "unknown",
                        // falls back to the plain gap message rather than guessing.
                        val wasFrequent = m.recentCheckinCount14d >= 8
                        val wasAlreadyLow = m.recentCheckinCount14d in 0..3
                        Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(m.nickname, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    when {
                                        days == null -> "अभी तक कोई check-in नहीं"
                                        days == 0 -> "आज active थे"
                                        days == 1 -> "कल check-in किया"
                                        else -> "$days दिन से check-in नहीं किया"
                                    },
                                    color = if (days != null && days >= 3) Color(0xFFD9636B) else Color(0xFFB7B2CB),
                                    fontSize = 13.sp
                                )
                                if (days != null && days >= 3) {
                                    when {
                                        wasFrequent -> Text(
                                            "🔴 अचानक activity रुक गई — पहले लगभग रोज़ active थे",
                                            color = Color(0xFFD9636B), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                        wasAlreadyLow -> Text(
                                            "पहले से भी कम active थे, नया pattern नहीं",
                                            color = Color(0xFF6E6A80), fontSize = 12.sp,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                        else -> {}
                                    }
                                    Text("एक call कर लो? 📞", color = AryaEmber, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                }
                if (members.isEmpty()) {
                    Text(
                        "अभी सिर्फ तुम इस circle में हो — बाकी परिवार को भी यही code दो।",
                        color = Color(0xFF6E6A80),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 20.dp)
                    )
                }
            }
        }
    }
}

// ============================================================================
// Family Vision Board — each Family Circle member's #1 stated priority, side by
// side. A shared picture of what the family collectively cares about, not a
// detailed comparison of anyone's daily time (that stays private to Antargati).
// ============================================================================

@Composable
fun FamilyVisionBoardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var circle by remember { mutableStateOf(com.arya.ai.tools.FamilyCircleStore.get(context)) }
    var members by remember { mutableStateOf(listOf<com.arya.ai.util.FirebaseSync.FamilyCircleMember>()) }

    LaunchedEffect(circle) {
        val c = circle ?: return@LaunchedEffect
        val (code, _) = c
        val prefs = com.arya.ai.util.PreferencesManager(context)
        withContext(Dispatchers.IO) {
            val topPriority = com.arya.ai.tools.PriorityStore.getPriorities(context)
                .let { if (it.length() > 0) it.getJSONObject(0).getString("name") else null }
            com.arya.ai.util.FirebaseSync.updateMyCircleSignal(context, code, prefs.installId, null, null, topPriority)
        }
        com.arya.ai.util.FirebaseSync.observeFamilyCircle(context, code) { members = it }
    }

    val commonPriority = remember(members) {
        members.filter { it.topPriority.isNotBlank() }
            .groupingBy { it.topPriority }
            .eachCount()
            .maxByOrNull { it.value }
            ?.takeIf { it.value >= 2 }
            ?.key
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Family Vision", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = AryaSignal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AryaInk)
            )
        },
        containerColor = AryaInk
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(20.dp)) {
            if (circle == null) {
                FamilyCircleJoinForm(onJoined = { code, nickname -> circle = code to nickname })
            } else {
                if (commonPriority != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("परिवार में सबसे ज़्यादा साझा प्राथमिकता", color = Color(0xFF9C97AF), fontSize = 12.sp)
                            Text(commonPriority, color = AryaEmber, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(members) { m ->
                        Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(m.nickname, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    m.topPriority.ifBlank { "—" },
                                    color = AryaEmber,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
                if (members.isEmpty()) {
                    Text(
                        "अभी सिर्फ तुम इस circle में हो — बाकी परिवार को भी यही code दो।",
                        color = Color(0xFF6E6A80),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 20.dp)
                    )
                }
            }
        }
    }
}

// ============================================================================
// Family Mediator Mode — a neutral, data-grounded conversation between two Family
// Circle members, using each person's OWN priority-mismatch summary — explicitly
// opt-in shared per FirebaseSync.shareMediatorSummary's doc comment, not the passive
// auto-push Family Vision Board uses for a single priority name. Arya validates both
// sides rather than declaring a winner (see FamilyMediator's system-prompt design).
// ============================================================================

@Composable
fun FamilyMediatorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { com.arya.ai.util.PreferencesManager(context) }

    var circle by remember { mutableStateOf(com.arya.ai.tools.FamilyCircleStore.get(context)) }
    var members by remember { mutableStateOf(listOf<com.arya.ai.util.FirebaseSync.FamilyCircleMember>()) }
    var myShared by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var selectedOther by remember { mutableStateOf<com.arya.ai.util.FirebaseSync.FamilyCircleMember?>(null) }
    var question by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("idle") } // idle | loading | done | error
    var resultText by remember { mutableStateOf("") }

    LaunchedEffect(circle) {
        val c = circle ?: return@LaunchedEffect
        val (code, _) = c
        com.arya.ai.util.FirebaseSync.observeFamilyCircle(context, code) { list -> members = list }
    }

    LaunchedEffect(members) {
        val installId = prefs.installId
        myShared = members.firstOrNull { it.installId == installId }?.mediatorSummary?.isNotBlank() == true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Family Mediator", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = AryaSignal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AryaInk)
            )
        },
        containerColor = AryaInk
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (circle == null) {
                item {
                    Text(
                        "पहले Family Circle जॉइन करो (Family Pulse या Family Vision से) — फिर यहां वापस आओ।",
                        color = Color(0xFF9C97AF),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    FamilyCircleJoinForm(onJoined = { code, nickname -> circle = code to nickname })
                }
            } else {
                item {
                    Text(
                        "अपना priority-data share करो, तब ही Arya दोनों तरफ का सच देखकर बात कर पाएगी। " +
                            "ये अपने-आप नहीं होता — तुम्हें ख़ुद बटन दबाना होगा।",
                        color = Color(0xFF9C97AF),
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    if (myShared) {
                        Text("✅ तुमने अपना data share कर रखा है", color = Color(0xFF7FA37A), fontSize = 13.sp)
                    } else {
                        Button(
                            onClick = {
                                sharing = true
                                scope.launch {
                                    val (code, _) = circle!!
                                    val summary = withContext(Dispatchers.IO) {
                                        com.arya.ai.tools.LifeSimulator.buildContext(context)
                                    }
                                    withContext(Dispatchers.IO) {
                                        com.arya.ai.util.FirebaseSync.shareMediatorSummary(context, code, prefs.installId, summary)
                                    }
                                    myShared = true
                                    sharing = false
                                }
                            },
                            enabled = !sharing,
                            colors = ButtonDefaults.buttonColors(containerColor = AryaEmber)
                        ) {
                            Text(
                                if (sharing) "share हो रहा है..." else "अपना priority-data share करो",
                                color = Color(0xFF1A1410), fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                val othersWithData = members.filter {
                    it.installId != prefs.installId && it.mediatorSummary.isNotBlank()
                }

                item {
                    Spacer(Modifier.height(6.dp))
                    Text("किसके साथ बात करनी है?", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (othersWithData.isEmpty()) {
                        Text(
                            "अभी circle में किसी और ने अपना data share नहीं किया — उन्हें भी बताओ।",
                            color = Color(0xFF6E6A80), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            othersWithData.forEach { m ->
                                PriorityChip(
                                    label = m.nickname,
                                    active = selectedOther?.installId == m.installId,
                                    onClick = { selectedOther = m }
                                )
                            }
                        }
                    }
                }

                if (selectedOther != null && myShared) {
                    item {
                        OutlinedTextField(
                            value = question,
                            onValueChange = { question = it },
                            placeholder = { Text("किस बात पर तनाव है?", color = Color(0xFF6E6A80)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            enabled = status != "loading"
                        )
                    }
                    item {
                        Button(
                            onClick = {
                                val other = selectedOther ?: return@Button
                                if (question.isNotBlank()) {
                                    status = "loading"
                                    resultText = ""
                                    scope.launch {
                                        try {
                                            val mySummary = withContext(Dispatchers.IO) {
                                                com.arya.ai.tools.LifeSimulator.buildContext(context)
                                            }
                                            val systemPrompt = com.arya.ai.tools.FamilyMediator.buildSystemPrompt(
                                                "Main", mySummary, other.nickname, other.mediatorSummary
                                            )
                                            val result = withContext(Dispatchers.IO) {
                                                com.arya.ai.util.OnlineChatHelper.generateOnlineResponse(prefs, question, systemPrompt)
                                            }
                                            resultText = result.text
                                            status = "done"
                                        } catch (e: Exception) {
                                            resultText = "❌ ${e.message ?: "kuch galat ho gaya"}"
                                            status = "error"
                                        }
                                    }
                                }
                            },
                            enabled = question.isNotBlank() && status != "loading",
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AryaEmber)
                        ) {
                            Text(
                                if (status == "loading") "सोच रही हूं..." else "🤝 Mediate करो",
                                color = Color(0xFF1A1410), fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                if (resultText.isNotBlank()) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(resultText, color = Color(0xFFD8D4E6), fontSize = 14.sp, lineHeight = 21.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// Future Self Letter — record something today, seal it to a future date, hear it
// back in your own cloned voice. Reuses FamilyMemoryStore's "Main"-keyed voice-id
// (same recording as Life Simulator's past-self recall) and ReminderTools for the
// unlock-day notification. See FutureLetterStore's doc comment for the data model.
// ============================================================================

private val LETTER_DURATIONS = listOf(
    "1 महीना" to 30, "3 महीने" to 90, "6 महीने" to 180, "1 साल" to 365
)

@Composable
fun FutureLetterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voiceHelper = remember { com.arya.ai.util.VoiceHelper(context) }

    var letters by remember { mutableStateOf(com.arya.ai.tools.FutureLetterStore.getAll(context)) }
    var showWriteForm by remember { mutableStateOf(false) }
    var showRecordSelfVoice by remember { mutableStateOf(false) }
    var hasSelfVoice by remember { mutableStateOf(com.arya.ai.tools.FamilyMemoryStore.getPersonVoiceId(context, "Main") != null) }
    var letterText by remember { mutableStateOf("") }
    var selectedDurationDays by remember { mutableStateOf(LETTER_DURATIONS.first().second) }

    fun refresh() { letters = com.arya.ai.tools.FutureLetterStore.getAll(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("भविष्य को चिट्ठी", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = AryaSignal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AryaInk)
            )
        },
        containerColor = AryaInk
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    "आज कुछ लिखो — Arya उसे सील कर देगी, चुनी हुई तारीख़ पर ही खुलेगी।",
                    color = Color(0xFF9C97AF),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                if (!hasSelfVoice) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "अपनी आवाज़ record करो तो letter उसी आवाज़ में वापस सुनोगे, नहीं तो Arya अपनी आवाज़ में पढ़ेगी।",
                        color = Color(0xFF6E6A80),
                        fontSize = 12.sp
                    )
                    OutlinedButton(
                        onClick = { showRecordSelfVoice = true },
                        modifier = Modifier.padding(top = 8.dp)
                    ) { Text("🎙 अपनी आवाज़ record करो") }
                }
            }

            item {
                OutlinedButton(onClick = { showWriteForm = !showWriteForm }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showWriteForm) "बंद करें" else "+ नई चिट्ठी लिखो")
                }
            }

            if (showWriteForm) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = letterText,
                                onValueChange = { letterText = it },
                                placeholder = { Text("क्या कहना है भविष्य के अपने आप को?", color = Color(0xFF6E6A80)) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 4
                            )
                            Text("कब खुले?", color = Color(0xFF9C97AF), fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LETTER_DURATIONS.forEach { (label, days) ->
                                    PriorityChip(
                                        label = label,
                                        active = selectedDurationDays == days,
                                        onClick = { selectedDurationDays = days }
                                    )
                                }
                            }
                            Button(
                                onClick = {
                                    if (letterText.isNotBlank()) {
                                        val sealDateMs = System.currentTimeMillis() + selectedDurationDays * 24L * 60 * 60 * 1000
                                        val id = com.arya.ai.tools.FutureLetterStore.save(context, letterText.trim(), sealDateMs)
                                        val delayMinutes = selectedDurationDays * 24L * 60
                                        com.arya.ai.worker.ReminderTools.setReminder(
                                            context,
                                            "future_letter_$id",
                                            "तुम्हारी एक पुरानी चिट्ठी आज खुलने वाली है — Arya खोलो",
                                            delayMinutes
                                        )
                                        letterText = ""
                                        showWriteForm = false
                                        refresh()
                                    }
                                },
                                enabled = letterText.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = AryaEmber)
                            ) { Text("सील करो 🔒", color = Color(0xFF1A1410), fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }

            if (showRecordSelfVoice) {
                item {
                    Text(
                        "नीचे \"Main\" लिखो ताकि Arya पहचान सके ये तुम्हारी अपनी आवाज़ है।",
                        color = Color(0xFF9C97AF),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    RecordVoiceForm(onDone = {
                        showRecordSelfVoice = false
                        hasSelfVoice = com.arya.ai.tools.FamilyMemoryStore.getPersonVoiceId(context, "Main") != null
                    })
                }
            }

            if (letters.isEmpty()) {
                item {
                    Text("अभी कोई चिट्ठी नहीं लिखी — ऊपर से एक शुरू करो।", color = Color(0xFF6E6A80), fontSize = 13.sp)
                }
            }

            items(letters) { letter ->
                val sealed = letter.sealDateMs > System.currentTimeMillis()
                val dateStr = remember(letter.sealDateMs) {
                    java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US).format(java.util.Date(letter.sealDateMs))
                }
                Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        if (sealed) {
                            val daysLeft = ((letter.sealDateMs - System.currentTimeMillis()) / (1000L * 60 * 60 * 24)).toInt() + 1
                            Text("🔒 सील है — $dateStr को खुलेगी ($daysLeft दिन बाद)", color = Color(0xFF6E6A80), fontSize = 13.sp)
                        } else {
                            Text("🔓 $dateStr को खुल चुकी है", color = Color(0xFF7FA37A), fontSize = 12.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(letter.text, color = Color(0xFFD8D4E6), fontSize = 14.sp, lineHeight = 20.sp)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val voiceId = com.arya.ai.tools.FamilyMemoryStore.getPersonVoiceId(context, "Main")
                                    val spoke = if (voiceId != null) voiceHelper.speakClonedVoice(letter.text, voiceId) else false
                                    if (!spoke) voiceHelper.speak(letter.text)
                                }
                            }) { Text("🎙 सुनो") }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// Digital Legacy Conversation — see LegacyMode's doc comment (tools/AryaToolRegistry.kt)
// for the full design reasoning behind the constraints here (minimum-memory threshold,
// strict grounding, persistent on-screen disclaimer). The disclaimer Card below is
// deliberately placed inside the LazyColumn's item list, not a dismissable banner —
// it scrolls WITH the conversation so it stays visible as a reminder, not a one-time
// popup that's easy to forget after closing.
// ============================================================================

private data class LegacyExchange(val question: String, val answer: String)

@Composable
fun LegacySpaceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { com.arya.ai.util.PreferencesManager(context) }
    val voiceHelper = remember { com.arya.ai.util.VoiceHelper(context) }

    val eligiblePeople = remember {
        com.arya.ai.tools.FamilyMemoryStore.listPeople(context).filter {
            com.arya.ai.tools.FamilyMemoryStore.getAll(context, it).size >= com.arya.ai.tools.LegacyMode.MIN_MEMORIES_REQUIRED
        }
    }
    var selectedPerson by remember { mutableStateOf<String?>(null) }
    var acknowledged by remember(selectedPerson) {
        mutableStateOf(selectedPerson?.let { com.arya.ai.tools.LegacyModeStore.hasAcknowledged(context, it) } ?: false)
    }
    var speakInVoice by remember { mutableStateOf(true) }
    var question by remember { mutableStateOf("") }
    var exchanges by remember(selectedPerson) { mutableStateOf(listOf<LegacyExchange>()) }
    var loading by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Legacy Space", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = AryaSignal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AryaInk)
            )
        },
        containerColor = AryaInk
    ) { padding ->
        if (selectedPerson == null) {
            Column(modifier = Modifier.padding(padding).fillMaxSize().padding(20.dp)) {
                Text(
                    "ये सिर्फ़ उन लोगों के लिए है जिनकी कम से कम ${com.arya.ai.tools.LegacyMode.MIN_MEMORIES_REQUIRED} " +
                        "यादें 'पुरानी यादें' में save हैं — कम data से एक अधूरी, भ्रामक तस्वीर बन सकती है।",
                    color = Color(0xFF9C97AF),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(16.dp))
                if (eligiblePeople.isEmpty()) {
                    Text(
                        "अभी किसी की भी इतनी यादें save नहीं हैं। पहले 'पुरानी यादें' में जाकर कुछ जोड़ो।",
                        color = Color(0xFF6E6A80),
                        fontSize = 13.sp
                    )
                } else {
                    eligiblePeople.forEach { person ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = AryaInkSurface),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedPerson = person }
                                    .padding(16.dp)
                            ) { Text(person, color = Color.White, fontSize = 16.sp) }
                        }
                    }
                }
            }
        } else if (!acknowledged) {
            Column(modifier = Modifier.padding(padding).fillMaxSize().padding(20.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("शुरू करने से पहले", color = AryaEmber, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "$selectedPerson जो नहीं हैं — ये सिर्फ़ उनकी record की गई यादों से Arya की बनाई हुई एक " +
                                "आवाज़/अंदाज़ है। सिर्फ़ उन्हीं बातों का जवाब देगी जो पहले से record हैं — बाकी सब में साफ़ " +
                                "कहेगी 'ये मुझे नहीं पता'। ये असली व्यक्ति की जगह नहीं ले सकता।",
                            color = Color(0xFFD8D4E6),
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                        Button(
                            onClick = {
                                com.arya.ai.tools.LegacyModeStore.acknowledge(context, selectedPerson!!)
                                acknowledged = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AryaEmber)
                        ) { Text("समझ गया, आगे बढ़ें", color = Color(0xFF1A1410), fontWeight = FontWeight.SemiBold) }
                        OutlinedButton(onClick = { selectedPerson = null }, modifier = Modifier.fillMaxWidth()) {
                            Text("वापस")
                        }
                    }
                }
            }
        } else {
            val person = selectedPerson!!
            val memories = remember(person) { com.arya.ai.tools.FamilyMemoryStore.getAll(context, person) }
            val hasVoice = remember(person) { com.arya.ai.tools.FamilyMemoryStore.getPersonVoiceId(context, person) != null }

            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = com.arya.ai.ui.theme.AryaEmberContainerDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "⚠️ ये $person नहीं हैं — Arya उनकी record की गई yaadon ke aadhar par bol rahi hai।",
                            color = Color(0xFFFFE0CF),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                if (hasVoice) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PriorityChip(label = if (speakInVoice) "🎙 आवाज़ में" else "सिर्फ़ text", active = true, onClick = { speakInVoice = !speakInVoice })
                        }
                    }
                }
                items(exchanges) { ex ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(ex.question, color = AryaSignal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                            Text(ex.answer, color = Color(0xFFD8D4E6), fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(14.dp))
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        placeholder = { Text("$person से क्या पूछना है?", color = Color(0xFF6E6A80)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        enabled = !loading
                    )
                }
                item {
                    Button(
                        onClick = {
                            if (question.isNotBlank()) {
                                val asked = question
                                loading = true
                                question = ""
                                scope.launch {
                                    try {
                                        val systemPrompt = com.arya.ai.tools.LegacyMode.buildSystemPrompt(person, memories)
                                        val result = withContext(Dispatchers.IO) {
                                            com.arya.ai.util.OnlineChatHelper.generateOnlineResponse(prefs, asked, systemPrompt)
                                        }
                                        exchanges = exchanges + LegacyExchange(asked, result.text)
                                        if (speakInVoice && hasVoice) {
                                            val voiceId = com.arya.ai.tools.FamilyMemoryStore.getPersonVoiceId(context, person)
                                            if (voiceId != null) {
                                                val spoke = voiceHelper.speakClonedVoice(result.text, voiceId)
                                                if (!spoke) voiceHelper.speak(result.text)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        exchanges = exchanges + LegacyExchange(asked, "❌ ${e.message ?: "kuch galat ho gaya"}")
                                    } finally {
                                        loading = false
                                    }
                                }
                            }
                        },
                        enabled = question.isNotBlank() && !loading,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AryaEmber)
                    ) {
                        Text(if (loading) "सोच रही हूं..." else "पूछो", color = Color(0xFF1A1410), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ============================================================================
// Family Debate Simulator — see FamilyDebateSimulator's doc comment
// (tools/AryaToolRegistry.kt) for why this stays deliberately lightweight (just
// each member's single stated top priority) and always frames itself as a
// rehearsal, never as a claim about what someone actually thinks.
// ============================================================================

@Composable
fun FamilyDebateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { com.arya.ai.util.PreferencesManager(context) }

    var circle by remember { mutableStateOf(com.arya.ai.tools.FamilyCircleStore.get(context)) }
    var members by remember { mutableStateOf(listOf<com.arya.ai.util.FirebaseSync.FamilyCircleMember>()) }
    var topic by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("idle") } // idle | loading | done | error
    var resultText by remember { mutableStateOf("") }

    LaunchedEffect(circle) {
        val c = circle ?: return@LaunchedEffect
        val (code, _) = c
        com.arya.ai.util.FirebaseSync.observeFamilyCircle(context, code) { list -> members = list }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Family Debate", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = AryaSignal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AryaInk)
            )
        },
        containerColor = AryaInk
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (circle == null) {
                item {
                    Text(
                        "पहले Family Circle जॉइन करो (Family Pulse या Family Vision से) — फिर यहां वापस आओ।",
                        color = Color(0xFF9C97AF),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    FamilyCircleJoinForm(onJoined = { code, nickname -> circle = code to nickname })
                }
            } else {
                item {
                    Text(
                        "ये एक rehearsal है — असली parivaar ke logon ne jo socha hai, uska daava " +
                            "nahi. सिर्फ़ हर सदस्य की एक stated priority के आधार पर एक अंदाज़ा।",
                        color = Color(0xFF9C97AF),
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
                val withPriority = members.filter { it.topPriority.isNotBlank() }
                if (withPriority.isEmpty()) {
                    item {
                        Text(
                            "अभी circle में किसी की भी priority share नहीं हुई — Family Vision पहले खोलो।",
                            color = Color(0xFF6E6A80),
                            fontSize = 12.sp
                        )
                    }
                } else {
                    item {
                        Text("Circle में शामिल:", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        withPriority.forEach { m ->
                            Text("• ${m.nickname} — ${m.topPriority}", color = Color(0xFFB7B2CB), fontSize = 13.sp)
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = topic,
                            onValueChange = { topic = it },
                            placeholder = { Text("कौन सा फ़ैसला सोच रहे हो?", color = Color(0xFF6E6A80)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            enabled = status != "loading"
                        )
                    }
                    item {
                        Button(
                            onClick = {
                                if (topic.isNotBlank()) {
                                    status = "loading"
                                    resultText = ""
                                    scope.launch {
                                        try {
                                            val memberPairs = withPriority.map { it.nickname to it.topPriority }
                                            val systemPrompt = com.arya.ai.tools.FamilyDebateSimulator.buildSystemPrompt(topic, memberPairs)
                                            val result = withContext(Dispatchers.IO) {
                                                com.arya.ai.util.OnlineChatHelper.generateOnlineResponse(prefs, topic, systemPrompt)
                                            }
                                            resultText = result.text
                                            status = "done"
                                        } catch (e: Exception) {
                                            resultText = "❌ ${e.message ?: "kuch galat ho gaya"}"
                                            status = "error"
                                        }
                                    }
                                }
                            },
                            enabled = topic.isNotBlank() && status != "loading",
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AryaEmber)
                        ) {
                            Text(
                                if (status == "loading") "सोच रही हूं..." else "🎭 Rehearsal शुरू करो",
                                color = Color(0xFF1A1410), fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                if (resultText.isNotBlank()) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(resultText, color = Color(0xFFD8D4E6), fontSize = 14.sp, lineHeight = 21.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// Ancestral Thread — see AncestralThread's doc comment (tools/AryaToolRegistry.kt)
// for the weaving/attribution design. Playback plays each segment SEQUENTIALLY in
// that contributor's own cloned voice (falling back to Arya's voice per-segment,
// not for the whole narrative, so a mix of voice-recorded and not-yet-recorded
// family members still works) — a simple suspend-function loop naturally
// serializes this since speakClonedVoice/speak are both suspend calls.
// ============================================================================

@Composable
fun AncestralThreadScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { com.arya.ai.util.PreferencesManager(context) }
    val voiceHelper = remember { com.arya.ai.util.VoiceHelper(context) }

    val allMemories = remember { com.arya.ai.tools.FamilyMemoryStore.getAll(context) }
    val selectedIds = remember { mutableStateListOf<Int>() }
    var status by remember { mutableStateOf("idle") } // idle | loading | done | error
    var segments by remember { mutableStateOf(listOf<com.arya.ai.tools.AncestralThread.ThreadSegment>()) }
    var playingIndex by remember { mutableStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ancestral Thread", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = AryaSignal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AryaInk)
            )
        },
        containerColor = AryaInk
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "जो यादें एक ही किस्से/मौके की हों (अलग-अलग लोगों की बताई हुई), उन्हें चुनो — " +
                        "Arya उन्हें जोड़कर एक कहानी बनाएगी, हर हिस्सा उसी की आवाज़ में।",
                    color = Color(0xFF9C97AF),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
            if (allMemories.isEmpty()) {
                item {
                    Text("अभी कोई याद save नहीं है — पहले 'पुरानी यादें' में जाकर कुछ जोड़ो।", color = Color(0xFF6E6A80), fontSize = 13.sp)
                }
            } else {
                items(allMemories) { mem ->
                    val checked = selectedIds.contains(mem.id)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AryaInkSurface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (checked) selectedIds.remove(mem.id) else selectedIds.add(mem.id)
                            }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    if (it) selectedIds.add(mem.id) else selectedIds.remove(mem.id)
                                }
                            )
                            Column(Modifier.padding(start = 4.dp)) {
                                Text(mem.person, color = AryaEmber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(mem.title, color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            status = "loading"
                            segments = emptyList()
                            scope.launch {
                                try {
                                    val selectedMemories = allMemories.filter { it.id in selectedIds }
                                    val systemPrompt = com.arya.ai.tools.AncestralThread.buildSystemPrompt(selectedMemories)
                                    val result = withContext(Dispatchers.IO) {
                                        com.arya.ai.util.OnlineChatHelper.generateOnlineResponse(prefs, "Kahani jodo", systemPrompt)
                                    }
                                    val parsed = com.arya.ai.tools.AncestralThread.parseSegments(result.text)
                                    if (parsed.isEmpty()) {
                                        status = "error"
                                    } else {
                                        segments = parsed
                                        status = "done"
                                    }
                                } catch (e: Exception) {
                                    status = "error"
                                }
                            }
                        },
                        enabled = selectedIds.size >= 2 && status != "loading",
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AryaEmber)
                    ) {
                        Text(
                            when {
                                status == "loading" -> "कहानी जोड़ रही हूं..."
                                selectedIds.size < 2 -> "कम से कम 2 यादें चुनो"
                                else -> "🧵 कहानी बुनो"
                            },
                            color = Color(0xFF1A1410), fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            if (status == "error") {
                item {
                    Text("❌ कहानी नहीं बन पाई, दोबारा try करो", color = Color(0xFFD9636B), fontSize = 12.sp)
                }
            }
            if (segments.isNotEmpty()) {
                item {
                    OutlinedButton(
                        onClick = {
                            if (!isPlaying) {
                                scope.launch {
                                    isPlaying = true
                                    for (i in segments.indices) {
                                        playingIndex = i
                                        val seg = segments[i]
                                        val voiceId = com.arya.ai.tools.FamilyMemoryStore.getPersonVoiceId(context, seg.person)
                                        val spoke = if (voiceId != null) voiceHelper.speakClonedVoice(seg.text, voiceId) else false
                                        if (!spoke) voiceHelper.speak(seg.text)
                                    }
                                    playingIndex = -1
                                    isPlaying = false
                                }
                            }
                        },
                        enabled = !isPlaying,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (isPlaying) "🎙 सुनाई जा रही है..." else "▶ पूरी कहानी सुनो") }
                }
                itemsIndexed(segments) { index, seg ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (playingIndex == index) AryaEmberContainerDark else AryaInkSurface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(seg.person, color = AryaEmber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(seg.text, color = Color(0xFFD8D4E6), fontSize = 14.sp, lineHeight = 20.sp)
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// Backup/Restore — see BackupManager's doc comment (util/BackupManager.kt) for
// which stores this covers and why (everything in this app's 11 new features
// lives ONLY in local SharedPreferences, no cloud backup, so losing the phone
// loses years of family memories/decisions with no way back).
// ============================================================================

@Composable
fun BackupRestoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var backupStatus by remember { mutableStateOf("idle") } // idle | working | done | error
    var restoreMessage by remember { mutableStateOf("") }
    var restoreStatus by remember { mutableStateOf("idle") } // idle | working | done | error

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        restoreStatus = "working"
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } ?: ""
                if (text.isBlank()) {
                    restoreMessage = "❌ File padh nahi payi"
                    restoreStatus = "error"
                } else {
                    val json = org.json.JSONObject(text)
                    val result = withContext(Dispatchers.IO) {
                        com.arya.ai.util.BackupManager.importAll(context, json)
                    }
                    restoreMessage = result
                    restoreStatus = if (result.startsWith("✅")) "done" else "error"
                }
            } catch (e: Exception) {
                restoreMessage = "❌ Ye file sahi backup nahi lagti"
                restoreStatus = "error"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup/Restore", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = AryaSignal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AryaInk)
            )
        },
        containerColor = AryaInk
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Antargati, पुरानी यादें (आवाज़ें समेत), Life Simulator के फ़ैसले, भविष्य की चिट्ठियां, " +
                    "Family Circle की जानकारी — ये सब सिर्फ़ इसी फ़ोन में save है, कहीं और backup नहीं। " +
                    "फ़ोन खोने/बदलने से पहले एक बैकअप बना लो।",
                color = Color(0xFF9C97AF),
                fontSize = 13.sp,
                lineHeight = 19.sp
            )

            Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("बैकअप बनाओ", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "एक file बनेगी, जिसे तुम Google Drive, Files app, या ख़ुद को WhatsApp पर भेजकर रख सकते हो।",
                        color = Color(0xFF6E6A80), fontSize = 12.sp
                    )
                    Button(
                        onClick = {
                            backupStatus = "working"
                            scope.launch {
                                try {
                                    val file = withContext(Dispatchers.IO) {
                                        com.arya.ai.util.BackupManager.createBackupFile(context)
                                    }
                                    com.arya.ai.util.BackupManager.shareBackupFile(context, file)
                                    backupStatus = "done"
                                } catch (e: Exception) {
                                    backupStatus = "error"
                                }
                            }
                        },
                        enabled = backupStatus != "working",
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AryaEmber)
                    ) {
                        Text(
                            if (backupStatus == "working") "बना रही हूं..." else "📦 बैकअप बनाओ और save करो",
                            color = Color(0xFF1A1410), fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (backupStatus == "error") {
                        Text("❌ बैकअप नहीं बन पाया, दोबारा try करो", color = Color(0xFFD9636B), fontSize = 12.sp)
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("पुराना बैकअप वापस लाओ", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "⚠️ ये अभी की सारी Antargati/यादें/फ़ैसले पुराने बैकअप से बदल देगा — ध्यान से चुनो।",
                        color = Color(0xFFD9636B), fontSize = 12.sp
                    )
                    OutlinedButton(
                        onClick = { restoreLauncher.launch("application/json") },
                        enabled = restoreStatus != "working",
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (restoreStatus == "working") "restore हो रहा है..." else "📂 बैकअप file चुनो")
                    }
                    if (restoreMessage.isNotBlank()) {
                        Text(
                            restoreMessage,
                            color = if (restoreStatus == "done") Color(0xFF7FA37A) else Color(0xFFD9636B),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// Family Time Capsule Vault — a SHARED sealed message, unlike the personal Future
// Self Letter. See FirebaseSync's doc comment for the "seal date gates reading,
// not writing" design choice. Playback sequences through each contributor's own
// cloned voice, same pattern as Ancestral Thread.
// ============================================================================

@Composable
fun TimeCapsuleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { com.arya.ai.util.PreferencesManager(context) }
    val voiceHelper = remember { com.arya.ai.util.VoiceHelper(context) }

    var circle by remember { mutableStateOf(com.arya.ai.tools.FamilyCircleStore.get(context)) }
    var capsules by remember { mutableStateOf(listOf<com.arya.ai.util.FirebaseSync.TimeCapsule>()) }
    var showNewForm by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newDurationDays by remember { mutableStateOf(LETTER_DURATIONS.first().second) }
    var playingCapsuleId by remember { mutableStateOf<String?>(null) }
    var playingContribIndex by remember { mutableStateOf(-1) }

    LaunchedEffect(circle) {
        val c = circle ?: return@LaunchedEffect
        val (code, _) = c
        com.arya.ai.util.FirebaseSync.observeTimeCapsules(context, code) { list -> capsules = list }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Time Capsule", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = AryaSignal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AryaInk)
            )
        },
        containerColor = AryaInk
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (circle == null) {
                item {
                    Text(
                        "पूरा परिवार मिलकर एक चिट्ठी सील करे — हर कोई अपना हिस्सा अपनी आवाज़ में जोड़े। " +
                            "पहले Family Circle जॉइन करो।",
                        color = Color(0xFF9C97AF), fontSize = 13.sp, lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    FamilyCircleJoinForm(onJoined = { code, nickname -> circle = code to nickname })
                }
            } else {
                val (code, myNickname) = circle!!
                item {
                    OutlinedButton(onClick = { showNewForm = !showNewForm }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (showNewForm) "बंद करें" else "+ नया Time Capsule बनाओ")
                    }
                }
                if (showNewForm) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = newTitle,
                                    onValueChange = { newTitle = it },
                                    placeholder = { Text("जैसे \"Dadi की 70वीं सालगिरह\"", color = Color(0xFF6E6A80)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Text("कब खुले?", color = Color(0xFF9C97AF), fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LETTER_DURATIONS.forEach { (label, days) ->
                                        PriorityChip(label = label, active = newDurationDays == days, onClick = { newDurationDays = days })
                                    }
                                }
                                Button(
                                    onClick = {
                                        if (newTitle.isNotBlank()) {
                                            val sealDateMs = System.currentTimeMillis() + newDurationDays * 24L * 60 * 60 * 1000
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    com.arya.ai.util.FirebaseSync.createTimeCapsule(context, code, newTitle.trim(), sealDateMs)
                                                }
                                                newTitle = ""
                                                showNewForm = false
                                            }
                                        }
                                    },
                                    enabled = newTitle.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = AryaEmber)
                                ) { Text("सील करो 🔒", color = Color(0xFF1A1410), fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }
                }
                if (capsules.isEmpty()) {
                    item {
                        Text("अभी कोई Time Capsule नहीं बना — ऊपर से एक शुरू करो।", color = Color(0xFF6E6A80), fontSize = 13.sp)
                    }
                }
                items(capsules) { capsule ->
                    val sealed = capsule.sealDateMs > System.currentTimeMillis()
                    val dateStr = remember(capsule.sealDateMs) {
                        java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US).format(java.util.Date(capsule.sealDateMs))
                    }
                    val myContribution = capsule.contributions.firstOrNull { it.installId == prefs.installId }
                    var contribText by remember(capsule.id) { mutableStateOf("") }

                    Card(colors = CardDefaults.cardColors(containerColor = AryaInkSurface), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(capsule.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (sealed) "🔒 $dateStr को खुलेगा — अभी तक ${capsule.contributions.size} लोगों ने जोड़ा"
                                else "🔓 $dateStr को खुल चुका है — ${capsule.contributions.size} लोगों की बातें",
                                color = if (sealed) Color(0xFF9C97AF) else Color(0xFF7FA37A),
                                fontSize = 12.sp
                            )

                            if (sealed) {
                                if (myContribution == null) {
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = contribText,
                                        onValueChange = { contribText = it },
                                        placeholder = { Text("अपना हिस्सा लिखो...", color = Color(0xFF6E6A80)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Button(
                                        onClick = {
                                            if (contribText.isNotBlank()) {
                                                scope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        com.arya.ai.util.FirebaseSync.addCapsuleContribution(
                                                            context, code, capsule.id, prefs.installId, myNickname, contribText.trim()
                                                        )
                                                    }
                                                    contribText = ""
                                                }
                                            }
                                        },
                                        enabled = contribText.isNotBlank(),
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = AryaEmber)
                                    ) { Text("अपना हिस्सा जोड़ो", color = Color(0xFF1A1410), fontWeight = FontWeight.SemiBold) }
                                } else {
                                    Text("✅ तुमने अपना हिस्सा जोड़ दिया है", color = Color(0xFF7FA37A), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                                }
                            } else {
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        if (playingCapsuleId != capsule.id) {
                                            scope.launch {
                                                playingCapsuleId = capsule.id
                                                for (i in capsule.contributions.indices) {
                                                    playingContribIndex = i
                                                    val c = capsule.contributions[i]
                                                    val voiceId = com.arya.ai.tools.FamilyMemoryStore.getPersonVoiceId(context, c.nickname)
                                                    val spoke = if (voiceId != null) voiceHelper.speakClonedVoice(c.text, voiceId) else false
                                                    if (!spoke) voiceHelper.speak(c.text)
                                                }
                                                playingCapsuleId = null
                                                playingContribIndex = -1
                                            }
                                        }
                                    },
                                    enabled = playingCapsuleId != capsule.id,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(if (playingCapsuleId == capsule.id) "🎙 सुनाई जा रही है..." else "▶ सबकी बातें सुनो") }
                                capsule.contributions.forEachIndexed { i, c ->
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (playingCapsuleId == capsule.id && playingContribIndex == i)
                                                AryaEmberContainerDark else AryaInk
                                        ),
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                    ) {
                                        Column(Modifier.padding(10.dp)) {
                                            Text(c.nickname, color = AryaEmber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                            Text(c.text, color = Color(0xFFD8D4E6), fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
