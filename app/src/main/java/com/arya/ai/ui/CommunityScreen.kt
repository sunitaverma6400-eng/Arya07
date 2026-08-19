@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.arya.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arya.ai.util.FirebaseSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Kitne log Arya use kar rahe hain" — total unique installs (all-time) and how many are
 * connected right now, read live from Firebase Realtime Database (see [FirebaseSync]).
 *
 * Doesn't show geography here — Firebase Console's Analytics -> Demographics tab already
 * gives country/city breakdown automatically, no custom code/screen needed for that. Doesn't
 * show individual chat messages either — those are meant for review in the Firebase Console's
 * Realtime Database viewer under `/chats`, not as an in-app feed (keeps this screen to a quick
 * glance, and avoids re-building a chat-log browser that the Console already is one).
 */
@Composable
fun CommunityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var totalUsers by remember { mutableStateOf<Long?>(null) }
    var onlineCount by remember { mutableStateOf<Long?>(null) }
    var loaded by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        FirebaseSync.observeCommunityStats(context) { total, online ->
            totalUsers = total
            onlineCount = online
            loaded = true
        }
        onDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Community") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
        ) {
            if (!loaded) {
                Text(
                    "Load ho raha hai...",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else if (totalUsers == null && onlineCount == null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Firebase configure nahi hai", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "app/google-services.json add karo (apne Firebase project se) — " +
                                "README.md ka \"Firebase setup\" section dekho.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatCard(
                        label = "Total users",
                        value = totalUsers?.toString() ?: "—",
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    StatCard(
                        label = "Abhi online",
                        value = onlineCount?.toString() ?: "—",
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "Geography (kaha kaha se log hain) Firebase Console -> Analytics -> " +
                        "Demographics me apne aap dikhta hai. Log kya baat kar rahe hain, wo " +
                        "Console -> Realtime Database -> /chats me dikhega (jinhone consent diya hai unka hi).",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 20.dp)
                )
                Spacer(Modifier.height(20.dp))
                HyperlocalSection()
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ============================================================================
// Community Intelligence — hyperlocal updates for the person's own named area, on
// the same Firebase RTDB CommunityScreen already uses for total/online counts
// (see FirebaseSync's doc comment for why this is area-name-keyed, not raw GPS).
// Only becomes genuinely useful once several people in the same area post — that's
// inherent to any hyperlocal feature, not something more code here can fix.
// ============================================================================

private val UPDATE_CATEGORIES = listOf("ट्रैफ़िक", "पानी", "सब्ज़ी/दुकान", "Doctor", "उधार/बांटना", "अन्य")
private const val SHARING_CATEGORY = "उधार/बांटना"

@Composable
private fun HyperlocalSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { com.arya.ai.util.PreferencesManager(context) }

    var area by remember { mutableStateOf("") }
    var areaConfirmed by remember { mutableStateOf(false) }
    var detectingLocation by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf(UPDATE_CATEGORIES.first()) }
    var updateText by remember { mutableStateOf("") }
    var posting by remember { mutableStateOf(false) }
    var updates by remember { mutableStateOf(listOf<FirebaseSync.CommunityUpdate>()) }

    LaunchedEffect(areaConfirmed, area) {
        if (areaConfirmed && area.isNotBlank()) {
            FirebaseSync.observeCommunityUpdates(context, area) { updates = it }
        }
    }

    Text("आपके इलाके की बातें", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(
        "Ye sirf isi ilaake ke logo ko dikhega, jinhone bhi yahi naam confirm kiya hai. " +
            "Live location kabhi save nahi hoti, sirf ilaake ka naam.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
    )

    if (!areaConfirmed) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = area,
                onValueChange = { area = it },
                placeholder = { Text("apna ilaaka/mohalla likho") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedButton(
                enabled = !detectingLocation,
                onClick = {
                    detectingLocation = true
                    scope.launch {
                        val guess = withContext(Dispatchers.IO) { guessAreaFromLocation(context) }
                        if (!guess.isNullOrBlank()) area = guess
                        detectingLocation = false
                    }
                }
            ) { Text(if (detectingLocation) "..." else "📍 pata karo") }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { if (area.isNotBlank()) areaConfirmed = true },
            enabled = area.isNotBlank(),
            colors = ButtonDefaults.buttonColors()
        ) { Text("ठीक है, यही इलाका") }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("📍 $area", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { areaConfirmed = false }) { Text("बदलें") }
        }
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            UPDATE_CATEGORIES.forEach { cat ->
                val active = category == cat
                OutlinedButton(onClick = { category = cat }) {
                    Text(cat, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = updateText,
            onValueChange = { updateText = it },
            placeholder = {
                Text(if (category == SHARING_CATEGORY) "kya hai jo tum share/lend kar sakte ho?" else "kya ho raha hai yahan?")
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (updateText.isNotBlank()) {
                    posting = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            FirebaseSync.postCommunityUpdate(context, area, category, updateText.trim(), prefs.installId)
                        }
                        updateText = ""
                        posting = false
                    }
                }
            },
            enabled = updateText.isNotBlank() && !posting,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (posting) "भेज रहे हैं..." else "पोस्ट करें") }

        Spacer(Modifier.height(16.dp))
        if (updates.isEmpty()) {
            Text(
                "अभी यहां कुछ पोस्ट नहीं हुआ — अपने आस-पास किसी और को भी Arya बताओ ताकि ये useful बने।",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            updates.forEach { update ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                update.category,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (update.resolved) Color(0xFF6E6A80) else Color(0xFF7FA37A)
                            )
                            if (update.category == SHARING_CATEGORY && !update.resolved && update.installId == prefs.installId) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                FirebaseSync.markItemResolved(context, area, update.key)
                                            }
                                        }
                                    }
                                ) { Text("मिल गया ✓", fontSize = 11.sp) }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            update.text,
                            fontSize = 14.sp,
                            color = if (update.resolved) Color(0xFF6E6A80) else Color.Unspecified,
                            textDecoration = if (update.resolved) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                        )
                    }
                }
            }
        }
    }
}

/** One-shot: last-known device location -> reverse-geocoded area name (no live tracking, no
 *  storage of the raw coordinates — see [FirebaseSync]'s doc comment). Null if no permission,
 *  no fix available yet, or geocoding fails; caller just leaves the area field for manual typing. */
private fun guessAreaFromLocation(context: android.content.Context): String? {
    val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.ACCESS_FINE_LOCATION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.ACCESS_COARSE_LOCATION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!hasFine && !hasCoarse) return null
    return try {
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val location = lm.getProviders(true).asSequence()
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        val readable = com.arya.ai.tools.InfoApiTools.reverseGeocode(context, location.latitude, location.longitude)
        if (readable.startsWith("❌")) null else readable.removePrefix("📍 ").trim()
    } catch (e: Exception) {
        null
    }
}
