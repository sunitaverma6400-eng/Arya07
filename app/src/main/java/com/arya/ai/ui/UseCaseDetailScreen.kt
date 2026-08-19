@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.arya.ai.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arya.ai.data.UseCase

/**
 * "Try it" is always available now — Arya has no on-device model to download/load first,
 * every use case runs on the free online relay (Groq/Gemini/OpenRouter) as soon as you tap it.
 */
@Composable
fun UseCaseDetailScreen(
    useCase: UseCase,
    onBack: () -> Unit,
    onOpenFeature: () -> Unit
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(useCase.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onOpenFeature, icon = {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
            }, text = { Text("Try it") })
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(20.dp),
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .background(useCase.iconBackground),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(useCase.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                    Text(
                        useCase.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    if (useCase.experimental) {
                        AssistChip(onClick = {}, label = { Text("EXPERIMENTAL") }, modifier = Modifier.padding(top = 6.dp))
                    }
                    Text(
                        useCase.description,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    Row(modifier = Modifier.padding(top = 12.dp)) {
                        TextButton(onClick = { openUrl(context, useCase.apiDocsUrl) }) { Text("API Documentation") }
                    }
                    TextButton(onClick = { openUrl(context, useCase.exampleCodeUrl) }) { Text("Example code") }
                    Text(
                        "Free online model se chalta hai — Groq/Gemini/OpenRouter, koi download nahi chahiye.",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                    )
                }
            }
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) { /* no browser available — silently ignore */ }
}
