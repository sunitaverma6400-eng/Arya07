@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.arya.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arya.ai.util.PreferencesManager

/**
 * Personal Intelligence — the "+" attach menu's tile of the same name (FIXES_LOG.md Phase 26).
 * Gemini's version personalizes answers using your account history; Arya has no account/history
 * store to draw on, so this is the honest, buildable equivalent — a free-text "mere baare mein"
 * note that, when the toggle is on, gets appended to every system prompt (see
 * MainActivity.buildIdentityContext), so Arya's replies can actually take it into account. Off
 * by default.
 */
@Composable
fun PersonalIntelligenceScreen(prefsManager: PreferencesManager, onBack: () -> Unit) {
    var enabled by remember { mutableStateOf(prefsManager.personalIntelligenceEnabled) }
    var context by remember { mutableStateOf(prefsManager.personalContext) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Personal Intelligence", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = com.arya.ai.ui.theme.AryaSignal)
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = com.arya.ai.ui.theme.AryaInk
                )
            )
        },
        containerColor = com.arya.ai.ui.theme.AryaInk
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.padding(end = 12.dp)) {
                    Text("Personal context istemal karo", style = MaterialTheme.typography.titleSmall, color = androidx.compose.ui.graphics.Color(0xFFF1EEFA))
                    Text(
                        "On karne par neeche likhi baatein har jawaab me dhyan me rakhi jayengi",
                        style = MaterialTheme.typography.bodySmall,
                        color = com.arya.ai.ui.theme.AryaTextFaint
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        prefsManager.personalIntelligenceEnabled = it
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = com.arya.ai.ui.theme.AryaSignal)
                )
            }
            Text(
                "Mere baare mein",
                style = MaterialTheme.typography.titleSmall,
                color = androidx.compose.ui.graphics.Color(0xFFF1EEFA),
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
            )
            OutlinedTextField(
                value = context,
                onValueChange = {
                    context = it
                    prefsManager.personalContext = it
                },
                placeholder = { Text("e.g. Main ek developer hoon, Kotlin/Android pe kaam karta hoon, seedha jawaab pasand hai...") },
                modifier = Modifier.fillMaxWidth().fillMaxSize()
            )
        }
    }
}
