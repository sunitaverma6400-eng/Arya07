@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.arya.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import com.arya.ai.viewmodel.PROMPT_TEMPLATES
import com.arya.ai.viewmodel.PromptLabViewModel
import com.arya.ai.viewmodel.PromptTemplate

@Composable
fun PromptLabScreen(viewModel: PromptLabViewModel, onBack: () -> Unit) {
    val output by viewModel.output.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    var selectedTemplate by remember { mutableStateOf(PROMPT_TEMPLATES.first()) }
    var input by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Prompt Lab") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PROMPT_TEMPLATES) { template ->
                    FilterChip(
                        selected = template == selectedTemplate,
                        onClick = { selectedTemplate = template },
                        label = { Text(template.title) }
                    )
                }
            }
            Text(
                selectedTemplate.description,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Apna text yahan daalo") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4
            )
            Button(
                onClick = { viewModel.run(selectedTemplate, input) },
                enabled = !isGenerating,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Text(if (isGenerating) "Generating…" else "Run")
            }

            if (isGenerating && output.isBlank()) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
            }

            if (output.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Text(
                        output,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}
