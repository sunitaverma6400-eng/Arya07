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
import androidx.compose.material3.Card
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

private data class MenuItem(val title: String, val subtitle: String, val icon: ImageVector, val route: String)

private val MENU_ITEMS = listOf(
    MenuItem("Use Cases", "AI Chat, Vision, Audio Scribe, Agent Skills aur baaki tiles", Icons.Filled.Apps, "use_cases"),
    MenuItem("Settings", "Manage application settings", Icons.Filled.Settings, "settings"),
    MenuItem("Online free models", "Groq/Gemini/OpenRouter free model pick karo", Icons.Filled.Cloud, "online_models"),
    MenuItem("Notifications", "View scheduled notifications", Icons.Filled.Notifications, "notifications"),
    MenuItem("API Keys", "Groq/Gemini/OpenRouter/NASA/Wolfram/Picovoice", Icons.Filled.VpnKey, "api_keys"),
    MenuItem("Chat History", "Saved past conversations", Icons.Filled.History, "sessions"),
    MenuItem("Persona", "Character/role banao aur activate karo", Icons.Filled.TheaterComedy, "persona"),
    MenuItem("Community", "Kitne log jude hue hain, kitne abhi online hain", Icons.Filled.Groups, "community")
)

@Composable
fun MenuScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Menu") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
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
                    modifier = Modifier.fillMaxWidth().aspectRatio(1.1f),
                    onClick = { onNavigate(item.route) }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Icon(item.icon, contentDescription = null, modifier = Modifier.padding(bottom = 12.dp))
                        Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(item.subtitle, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
