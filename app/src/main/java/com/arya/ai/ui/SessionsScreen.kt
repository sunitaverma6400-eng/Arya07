@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.arya.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arya.ai.data.AppDatabase
import com.arya.ai.data.ChatSessionEntity
import com.arya.ai.util.ExportHelper
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Compose replacement for the old (unreachable) classic-View `SessionsActivity`. Shows every
 * saved chat session (now written by [com.arya.ai.viewmodel.ChatViewModel] on every exchange)
 * and lets you reopen or delete one.
 */
@Composable
fun SessionsScreen(onBack: () -> Unit, onOpenSession: (Long) -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.getInstance(context).chatDao() }
    val scope = rememberCoroutineScope()
    val sessions by dao.getAllSessions().collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat History") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        if (sessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "Abhi koi saved chat nahi hai — ek naya message bhejo, wo yahan apne aap save ho jaayega.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(32.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(sessions, key = { it.id }) { session: ChatSessionEntity ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onOpenSession(session.id) }
                    ) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    session.title.ifBlank { "New chat" },
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "${session.modelName} • ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.createdAt))}",
                                    style = com.arya.ai.ui.theme.AryaMonoStatus,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    val msgs = dao.getMessagesForSessionOnce(session.id)
                                    val file = ExportHelper.exportAsMarkdown(context, session.title, msgs)
                                    ExportHelper.shareFile(context, file)
                                }
                            }) { Icon(Icons.Filled.Share, contentDescription = "Export/share session") }
                            IconButton(onClick = {
                                scope.launch {
                                    dao.deleteMessagesForSession(session.id)
                                    dao.deleteSession(session)
                                }
                            }) { Icon(Icons.Filled.Delete, contentDescription = "Delete session") }
                        }
                    }
                }
            }
        }
    }
}
