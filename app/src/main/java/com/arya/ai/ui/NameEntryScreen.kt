package com.arya.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arya.ai.ui.theme.AryaInk
import com.arya.ai.ui.theme.AryaSignal
import com.arya.ai.ui.theme.AryaSignalOn
import com.arya.ai.ui.theme.AryaTextFaint

/**
 * First-launch onboarding step, shown right after the runtime-permission dialogs (see
 * [com.arya.ai.MainActivity]) whenever [com.arya.ai.util.PreferencesManager.userName] is still
 * blank. Once saved, the name replaces the hardcoded "Sudhanshu" that used to sit in the home
 * greeting — every install now greets whoever's actually using that phone.
 */
@Composable
fun NameEntryScreen(onDone: (String) -> Unit) {
    var name by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AryaInk)
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Arya",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = AryaSignal
            )
            Text(
                "Shuru karne se pehle, tumhara naam kya hai?",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFF1EEFA),
                modifier = Modifier.padding(top = 10.dp, bottom = 28.dp)
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Apna naam likho…", color = AryaTextFaint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFFF1EEFA),
                    unfocusedTextColor = Color(0xFFF1EEFA),
                    focusedBorderColor = AryaSignal,
                    unfocusedBorderColor = AryaTextFaint,
                    cursorColor = AryaSignal
                )
            )
            Button(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) onDone(trimmed)
                },
                enabled = name.trim().isNotEmpty(),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AryaSignal,
                    contentColor = AryaSignalOn
                ),
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
                modifier = Modifier.padding(top = 22.dp)
            ) {
                Text("Aage badho", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
