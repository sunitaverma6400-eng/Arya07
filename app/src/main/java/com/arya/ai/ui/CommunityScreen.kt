@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.arya.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arya.ai.util.FirebaseSync

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
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
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
