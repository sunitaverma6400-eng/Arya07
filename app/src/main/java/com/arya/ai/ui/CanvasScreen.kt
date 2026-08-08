@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.arya.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Canvas — the "+" attach menu's Canvas tile (FIXES_LOG.md Phase 26). A plain full-screen
 * scratchpad: write or paste a longer chunk of text/code here, then hand it to Arya as a
 * message with one tap. Deliberately NOT Gemini's real Canvas (a sandboxed code-execution
 * environment) — Arya has no code runner, so faking that would be exactly the kind of dead
 * button this menu is meant to avoid. This is the honest, buildable subset: a big text box
 * plus a "bhejo" button, with the draft kept so switching tabs doesn't lose it.
 */
@Composable
fun CanvasScreen(
    initialContent: String,
    onBack: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onSendToArya: (String) -> Unit
) {
    var content by remember { mutableStateOf(initialContent) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Canvas", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { onDraftChanged(content); onBack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = com.arya.ai.ui.theme.AryaSprout)
                    }
                },
                actions = {
                    TextButton(
                        onClick = { onDraftChanged(content); onSendToArya(content) },
                        enabled = content.isNotBlank()
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = null, tint = com.arya.ai.ui.theme.AryaSprout, modifier = Modifier.padding(end = 6.dp))
                        Text("Arya ko bhejo", color = com.arya.ai.ui.theme.AryaSprout, fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = com.arya.ai.ui.theme.AryaInk
                )
            )
        },
        containerColor = com.arya.ai.ui.theme.AryaInk
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                "Yahan likho ya paste karo, phir Arya ko bhej do jab poora ho jaaye.",
                style = MaterialTheme.typography.bodySmall,
                color = com.arya.ai.ui.theme.AryaTextFaint,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                placeholder = { Text("Draft, code, notes...") },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
