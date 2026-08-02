@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.arya.ai.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.arya.ai.viewmodel.AudioScribeViewModel

@Composable
fun AudioScribeScreen(viewModel: AudioScribeViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val isListening by viewModel.isListening.collectAsState()
    val transcript by viewModel.transcript.collectAsState()
    val translation by viewModel.translation.collectAsState()
    val isTranslating by viewModel.isTranslating.collectAsState()
    val error by viewModel.error.collectAsState()
    var targetLanguage by remember { mutableStateOf("Hindi") }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio Scribe") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            Text(
                "On-device speech recognition se transcribe hota hai — bina internet ke.",
                style = MaterialTheme.typography.bodyMedium
            )

            FilledIconButton(
                onClick = {
                    if (!hasPermission) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else if (isListening) {
                        viewModel.stopListening()
                    } else {
                        viewModel.startListening()
                    }
                },
                modifier = Modifier.padding(24.dp).size(80.dp)
            ) {
                Icon(
                    if (isListening) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = "Bolo",
                    modifier = Modifier.size(36.dp)
                )
            }

            if (!hasPermission) {
                Text("Mic permission chahiye — button dabao to grant karne ke liye poochega.")
            }
            error?.let { Text("⚠️ $it", color = MaterialTheme.colorScheme.error) }

            Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Transcript", style = MaterialTheme.typography.titleMedium)
                    Text(transcript.ifBlank { "…" }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                }
            }

            if (transcript.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = targetLanguage,
                        onValueChange = { targetLanguage = it },
                        label = { Text("Target language") },
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { viewModel.translateTranscript(targetLanguage) },
                        enabled = !isTranslating
                    ) { Text("Translate") }
                }
            }

            if (isTranslating) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
            }

            if (translation.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Translation ($targetLanguage)", style = MaterialTheme.typography.titleMedium)
                        Text(translation, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}
