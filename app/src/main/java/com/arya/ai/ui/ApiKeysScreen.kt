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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arya.ai.util.ApiKeyManager
import com.arya.ai.util.ApiProvider
import com.arya.ai.util.PreferencesManager
import com.arya.ai.worker.CurrentInfoScheduler
import java.text.DateFormat
import java.util.Date

/**
 * Compose replacement for the old (unreachable) classic-View `ApiKeysActivity`. Same
 * functionality — per-provider key list, masked values, add/remove, "Sync now" for Arya's
 * current-affairs snapshot — but now it's an actual NavHost destination
 * reachable from Menu -> "API Keys", not hidden behind a widget-only Activity chain.
 */
@Composable
fun ApiKeysScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val apiKeyManager = remember { ApiKeyManager(context) }
    val prefs = remember { PreferencesManager(context) }

    // Bumped to force LazyColumn/card recomposition after add/remove, since ApiKeyManager
    // reads/writes SharedPreferences directly rather than exposing a Flow.
    var refreshTick by remember { mutableIntStateOf(0) }
    var syncStatusText by remember {
        mutableStateOf(formatSyncStatus(prefs.lastCurrentInfoSync))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API Keys") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("🌐 Online chat — automatic", fontWeight = FontWeight.Bold)
                        Text(
                            "Current/real-time information wale sawaalon ke liye Arya khud-ba-khud " +
                                "Groq / Gemini / OpenRouter use karti hai (Arya Relay ke through) — " +
                                "kuch add karne ki zarurat nahi, ye har install me automatically kaam karta hai.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("🔄 Arya ko current rakho", fontWeight = FontWeight.Bold)
                        Text(
                            "Background me har 12 ghante me current-affairs summary sync hoti hai.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                        )
                        Text(syncStatusText, style = com.arya.ai.ui.theme.AryaMonoStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = {
                            CurrentInfoScheduler.syncNow(context)
                            syncStatusText = "Sync shuru ho gaya…"
                            android.widget.Toast.makeText(
                                context,
                                "Sync shuru ho gaya — thodi der me current info Arya ke jawaabon me bhi milegi",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }) { Text("Sync now") }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("🚀 NASA aur 🧠 Wolfram Alpha bhi automatic", fontWeight = FontWeight.Bold)
                        Text(
                            "Ye bhi ab Arya Relay ke through chalte hain — kuch add karne ki zarurat nahi.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            item {
                Text(
                    "Neeche wali key optional hai — ye on-device wake-word (\"Hey Arya\") SDK ke liye hai, " +
                        "isliye relay se proxy nahi ho sakti (audio phone pe hi process hota hai):",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Picovoice stays user-managed: it initializes an on-device SDK (Porcupine) that
            // processes microphone audio locally for wake-word detection — not an HTTP call a
            // server can proxy, so it can't move to Arya Relay like NASA/Wolfram did.
            items(listOf(ApiProvider.PICOVOICE)) { provider ->
                @Suppress("UNUSED_EXPRESSION") refreshTick
                ProviderKeyCard(
                    provider = provider,
                    apiKeyManager = apiKeyManager,
                    onChanged = { refreshTick++ }
                )
            }
        }
    }
}

@Composable
private fun ProviderKeyCard(
    provider: ApiProvider,
    apiKeyManager: ApiKeyManager,
    onChanged: () -> Unit
) {
    var input by remember(provider) { mutableStateOf("") }
    val keys = apiKeyManager.getKeys(provider)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "${provider.label}  (${keys.size}/${provider.maxKeys} keys)",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall
            )

            keys.forEach { savedKey ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(maskKey(savedKey), style = com.arya.ai.ui.theme.AryaMonoStatus)
                    IconButton(onClick = {
                        apiKeyManager.removeKey(provider, savedKey)
                        onChanged()
                    }) { Icon(Icons.Filled.Close, contentDescription = "Remove key") }
                }
            }

            val canAddMore = keys.size < provider.maxKeys
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    enabled = canAddMore,
                    placeholder = { Text("${provider.label} API key paste karo") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                TextButton(
                    enabled = canAddMore,
                    onClick = {
                        if (apiKeyManager.addKey(provider, input)) {
                            input = ""
                            onChanged()
                        }
                    }
                ) { Text("Add") }
            }
        }
    }
}

private fun maskKey(key: String): String {
    if (key.length <= 8) return "••••••••"
    return key.take(4) + "…" + key.takeLast(4)
}

private fun formatSyncStatus(lastSync: Long): String =
    if (lastSync == 0L) "Abhi tak koi sync nahi hua"
    else "Last synced: " + DateFormat.getDateTimeInstance().format(Date(lastSync))
