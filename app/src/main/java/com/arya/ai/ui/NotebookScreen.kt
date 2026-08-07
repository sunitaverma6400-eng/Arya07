@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.arya.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arya.ai.data.NoteDao
import com.arya.ai.data.NoteEntity
import kotlinx.coroutines.launch

/**
 * Notebook — the "+" attach menu's Notebook tile (FIXES_LOG.md Phase 26). Plain local notes,
 * Room-backed, no AI/sync involved — this is deliberately the simplest possible real feature
 * rather than a decorative button, same "no dead entries" rule as the rest of the menu.
 */
@Composable
fun NotebookScreen(noteDao: NoteDao, onBack: () -> Unit) {
    val notes by noteDao.getAll().collectAsState(initial = emptyList())
    var editingNote by remember { mutableStateOf<NoteEntity?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (editingNote != null || isCreatingNew) {
        NoteEditor(
            note = editingNote,
            onBack = { editingNote = null; isCreatingNew = false },
            onSave = { title, content ->
                scope.launch {
                    val existing = editingNote
                    if (existing != null) {
                        noteDao.update(existing.copy(title = title, content = content, updatedAt = System.currentTimeMillis()))
                    } else {
                        noteDao.insert(NoteEntity(title = title, content = content))
                    }
                }
                editingNote = null
                isCreatingNew = false
            },
            onDelete = editingNote?.let { note ->
                {
                    scope.launch { noteDao.delete(note) }
                    editingNote = null
                }
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notebook", fontWeight = FontWeight.SemiBold) },
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
        floatingActionButton = {
            FloatingActionButton(
                onClick = { isCreatingNew = true },
                containerColor = com.arya.ai.ui.theme.AryaSignal
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Nayi note", tint = com.arya.ai.ui.theme.AryaSignalOn)
            }
        },
        containerColor = com.arya.ai.ui.theme.AryaInk
    ) { padding ->
        if (notes.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "Koi note nahi hai abhi — + dabao aur pehli note likho",
                    color = com.arya.ai.ui.theme.AryaTextFaint,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(notes) { note ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = com.arya.ai.ui.theme.AryaInkSurface),
                        onClick = { editingNote = note }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                note.title.ifBlank { "Bina title" },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = androidx.compose.ui.graphics.Color(0xFFF1EEFA)
                            )
                            if (note.content.isNotBlank()) {
                                Text(
                                    note.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = com.arya.ai.ui.theme.AryaTextFaint,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteEditor(
    note: NoteEntity?,
    onBack: () -> Unit,
    onSave: (title: String, content: String) -> Unit,
    onDelete: (() -> Unit)?
) {
    var title by remember { mutableStateOf(note?.title ?: "") }
    var content by remember { mutableStateOf(note?.content ?: "") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (note == null) "Nayi note" else "Note edit karo") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = com.arya.ai.ui.theme.AryaSignal)
                    }
                },
                actions = {
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = com.arya.ai.ui.theme.AryaError)
                        }
                    }
                    TextButton(
                        onClick = { onSave(title, content) },
                        enabled = title.isNotBlank() || content.isNotBlank()
                    ) {
                        Text("Save", color = com.arya.ai.ui.theme.AryaSignal, fontWeight = FontWeight.SemiBold)
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
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.titleMedium
            )
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                placeholder = { Text("Likho...") },
                modifier = Modifier.fillMaxSize().padding(top = 12.dp)
            )
        }
    }
}
