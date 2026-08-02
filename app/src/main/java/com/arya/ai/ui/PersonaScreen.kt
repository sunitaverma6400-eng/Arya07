@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.arya.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arya.ai.tools.PersonaStore

/**
 * Compose UI for [com.arya.ai.tools.PersonaStore] — previously this was only reachable by
 * typing a chat command like "persona activate karo naam X". Create/switch/deactivate/delete
 * all work from here now; the active persona is folded into the tool system prompt via
 * [PersonaStore.activeSystemPromptPrefix], wired into [com.arya.ai.MainActivity]'s
 * `identityContext` so it actually affects the main Chat screen (previously
 * `activeSystemPromptPrefix` existed but nothing in the main chat path ever called it).
 */
@Composable
fun PersonaScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var speakingStyle by remember { mutableStateOf("") }

    val personas = remember(refreshTick) { PersonaStore.listPersonasStructured(context) }
    val activeName = remember(refreshTick) { PersonaStore.activePersonaName(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Persona") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    "Persona ek character/role hai jo Arya activate kar ke uske hisaab se baat karti hai " +
                        "(naam, description, speaking style). Ek waqt me sirf ek persona active ho sakti hai; " +
                        "koi bhi active na ho to Arya apne normal mode me rehti hai.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Abhi active:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Text(
                            activeName ?: "Koi persona active nahi (normal mode)",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        if (activeName != null) {
                            TextButton(
                                modifier = Modifier.padding(top = 4.dp),
                                onClick = {
                                    PersonaStore.deactivatePersona(context)
                                    refreshTick++
                                }
                            ) { Text("Deactivate karo") }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Nayi persona banao", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Naam") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("Description (character kaisa hai)") },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                        OutlinedTextField(
                            value = speakingStyle,
                            onValueChange = { speakingStyle = it },
                            label = { Text("Speaking style (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            enabled = name.isNotBlank() && description.isNotBlank(),
                            onClick = {
                                PersonaStore.activatePersona(context, name.trim(), description.trim(), speakingStyle.trim())
                                name = ""; description = ""; speakingStyle = ""
                                refreshTick++
                            }
                        ) { Text("Banao aur activate karo") }
                    }
                }
            }

            if (personas.isNotEmpty()) {
                item {
                    Text("Saved personas", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
                items(personas, key = { it.name }) { persona ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (persona.name == activeName)
                            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        else CardDefaults.cardColors()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(persona.name, fontWeight = FontWeight.Bold)
                                Text(persona.description, style = MaterialTheme.typography.bodySmall)
                                if (persona.name != activeName) {
                                    TextButton(
                                        onClick = {
                                            PersonaStore.switchToSavedPersona(context, persona.name)
                                            refreshTick++
                                        }
                                    ) { Text("Switch karo") }
                                }
                            }
                            IconButton(onClick = {
                                PersonaStore.deletePersona(context, persona.name)
                                refreshTick++
                            }) { Icon(Icons.Filled.Delete, contentDescription = "Delete persona") }
                        }
                    }
                }
            }
        }
    }
}
