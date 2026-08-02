@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.arya.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.sp
import com.arya.ai.data.OnlineModel
import com.arya.ai.data.OnlineModels
import com.arya.ai.ui.theme.AryaBlue
import com.arya.ai.util.PreferencesManager

/**
 * Lets the user pick which free model each online provider (Groq/Gemini/OpenRouter) should
 * use. Sectioned exactly like the app's Groq/Gemini screenshots — a small blue all-caps
 * provider label, then one rounded card per free model, with the currently-selected one
 * checked. Selecting a card saves it immediately via [PreferencesManager] and
 * [com.arya.ai.util.OnlineChatHelper] picks it up on the next online call — no separate
 * "save" step needed.
 */
@Composable
fun OnlineModelsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { PreferencesManager(context) }

    var selectedGroq by remember { mutableStateOf(prefs.selectedGroqModel) }
    var selectedGemini by remember { mutableStateOf(prefs.selectedGeminiModel) }
    var selectedOpenRouter by remember { mutableStateOf(prefs.selectedOpenRouterModel) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Online free models") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(padding)
        ) {
            item {
                Text(
                    "Har provider ke liye sirf free-tier models — koi paid model list me nahi hai. " +
                        "Jo select karoge, chat me jab bhi online fallback chalega wahi model pehle try hoga; " +
                        "agar wo busy/rate-limited mile to isi provider ke baaki free models automatically try honge.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            item { ProviderHeader("GROQ") }
            items(OnlineModels.GROQ, key = { "groq_${it.id}" }) { model ->
                OnlineModelCard(
                    model = model,
                    isSelected = model.id == selectedGroq,
                    onClick = {
                        selectedGroq = model.id
                        prefs.selectedGroqModel = model.id
                    }
                )
            }

            item { ProviderHeader("GEMINI") }
            items(OnlineModels.GEMINI, key = { "gemini_${it.id}" }) { model ->
                OnlineModelCard(
                    model = model,
                    isSelected = model.id == selectedGemini,
                    onClick = {
                        selectedGemini = model.id
                        prefs.selectedGeminiModel = model.id
                    }
                )
            }

            item { ProviderHeader("OPENROUTER") }
            items(OnlineModels.OPENROUTER, key = { "openrouter_${it.id}" }) { model ->
                OnlineModelCard(
                    model = model,
                    isSelected = model.id == selectedOpenRouter,
                    onClick = {
                        selectedOpenRouter = model.id
                        prefs.selectedOpenRouterModel = model.id
                    }
                )
            }
        }
    }
}

@Composable
private fun ProviderHeader(label: String) {
    Text(
        label,
        color = AryaBlue,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun OnlineModelCard(model: OnlineModel, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) AryaBlue.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.displayName, style = MaterialTheme.typography.bodyLarge)
                if (model.note.isNotBlank()) {
                    Text(
                        model.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Icon(
                imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = if (isSelected) "Selected" else "Not selected",
                tint = if (isSelected) AryaBlue else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
