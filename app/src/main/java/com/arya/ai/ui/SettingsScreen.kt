@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.arya.ai.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.arya.ai.service.WakeWordService
import com.arya.ai.util.PreferencesManager
import com.arya.ai.util.UpdateChecker
import com.arya.ai.util.UpdateInstaller
import com.arya.ai.worker.UpdateCheckScheduler
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var streamingReplies by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context) }
    var wakeWordEnabled by remember { mutableStateOf(prefs.wakeWordEnabled) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            wakeWordEnabled = true
            prefs.wakeWordEnabled = true
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        }
    }

    fun setWakeWord(enabled: Boolean) {
        if (enabled) {
            val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!hasMic) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            wakeWordEnabled = true
            prefs.wakeWordEnabled = true
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        } else {
            wakeWordEnabled = false
            prefs.wakeWordEnabled = false
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            SettingsRow(
                title = "Streaming replies",
                subtitle = "Chat responses word-by-word dikhao jaise generate ho rahe hain",
                checked = streamingReplies,
                onCheckedChange = { streamingReplies = it }
            )
            Divider()
            SettingsRow(
                title = "\"Hey Arya\" wake word",
                subtitle = "Background me hamesha sunti rahe — \"Hello Arya\" / \"Hi Arya\" / \"Hey Arya\" bolke jagao. " +
                    "Har jawab Arya Relay (free online model) se aata hai. Battery zyada use hogi — " +
                    "OK Google jaisi dedicated low-power chip nahi hai yahan.",
                checked = wakeWordEnabled,
                onCheckedChange = { setWakeWord(it) }
            )
            Divider()

            Text("Permissions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            Text(
                "Location, notifications, camera aur mic — Arya khulte hi ek baar maangti hai. " +
                    "Agar koi permission deny kar di thi (ya \"don't ask again\" laga tha), yahan se seedha " +
                    "Android ki App Info screen khul jaayegi, wahan se dobara allow kar sakte ho.",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Button(onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }) {
                Text("Permissions dobara set karo")
            }
            if (wakeWordEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Text(
                    "⚠️ \"Hey Arya\" ko har waqt chalte rehne ke liye is app ke liye battery optimization band karo " +
                        "(upar wale button se App Info → Battery → \"Unrestricted\"), warna phone ka battery saver " +
                        "kuch der me isse band kar dega.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Divider(modifier = Modifier.padding(top = 20.dp))

            Text("App Updates", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            Text(
                "GitHub Releases se naya version check karti hai — taaki share ki hui APK baar-baar " +
                    "dobara bhejni na pade, dost bas yahan se update install kar sakein. Repo publish " +
                    "\"Releases\" karta ho, sirf Actions build artifact se nahi chalega.",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            var updateRepo by remember { mutableStateOf(prefs.updateCheckRepo) }
            OutlinedTextField(
                value = updateRepo,
                onValueChange = {
                    updateRepo = it
                    prefs.updateCheckRepo = it
                },
                label = { Text("owner/repo (jaise sunitaverma6400-eng/Jarvis)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            var checkStatus by remember { mutableStateOf<String?>(null) }
            var downloadPercent by remember { mutableIntStateOf(-1) }
            val scope = rememberCoroutineScope()
            val updateContext = LocalContext.current
            Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = updateRepo.isNotBlank() && downloadPercent < 0,
                    onClick = {
                        UpdateCheckScheduler.schedulePeriodicCheck(updateContext)
                        checkStatus = "Check ho raha hai…"
                        scope.launch {
                            val release = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                UpdateChecker.checkForUpdate(updateContext, updateRepo)
                            }
                            if (release != null) {
                                prefs.availableUpdateVersion = release.versionName
                                prefs.availableUpdateUrl = release.downloadUrl
                                prefs.availableUpdateNotes = release.releaseNotes
                                checkStatus = "Naya version mila: v${release.versionName}"
                            } else {
                                checkStatus = "Abhi latest version hi installed hai (ya release nahi mili)"
                            }
                        }
                    }
                ) { Text("Check now") }
                checkStatus?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 12.dp))
                }
            }
            if (prefs.availableUpdateVersion != null) {
                if (downloadPercent in 0..100) {
                    LinearProgressIndicator(
                        progress = downloadPercent / 100f,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
                Button(
                    enabled = downloadPercent < 0,
                    modifier = Modifier.padding(top = 8.dp),
                    onClick = {
                        val url = prefs.availableUpdateUrl ?: return@Button
                        val version = prefs.availableUpdateVersion ?: return@Button
                        downloadPercent = 0
                        scope.launch {
                            try {
                                UpdateInstaller.downloadAndInstall(updateContext, url, version) { percent ->
                                    downloadPercent = percent
                                }
                            } finally {
                                downloadPercent = -1
                            }
                        }
                    }
                ) { Text("v${prefs.availableUpdateVersion} download & install karo") }
            }
            Divider(modifier = Modifier.padding(top = 20.dp))

            Text("About", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            Text("Arya — AI ka Arya", style = MaterialTheme.typography.bodyMedium)
            Text("Version 0.3.0", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Free online AI assistant, powered by Arya Relay (Groq/Gemini/OpenRouter).",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
