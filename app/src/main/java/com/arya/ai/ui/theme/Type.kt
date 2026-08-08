package com.arya.ai.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type pairing: a tightened, heavier-weight sans for headers (reads as precise/considered
 * rather than a default Material label), a plain sans for body copy so long chat replies stay
 * easy to read, and — the one deliberate typographic signature of this app — the system
 * monospace face for "technical readout" moments specifically: model names, sync timestamps,
 * the online reply-source badge. Arya is a tool that started life as Termux/CLI scripts;
 * monospace for its status text (not its conversation) keeps a thread of that character
 * without making the whole app look like a terminal.
 */
val AryaTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp)
)

/** The one typographic signature — see the class doc above. Used for status/readout text
 *  only (never for conversation content), e.g. `Text("online", style = AryaMonoStatus)`. */
val AryaMonoStatus = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    letterSpacing = 0.2.sp
)
