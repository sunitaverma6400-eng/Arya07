package com.arya.ai.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design tokens for Arya — a voice-first, online-only personal assistant (every reply comes
 * from a free online model via Arya Relay — Groq/Gemini/OpenRouter).
 *
 * Previous palette (orange/blue/green primaries) was default Material-picker territory —
 * indistinguishable from a thousand other Android sample apps. This one is built from the
 * app's actual character: it's an assistant that *listens* (wake word, voice) and *answers
 * fast* — so the palette separates into an "Ink" family (the quiet "at rest" surface) and
 * "Signal" (the moment it's actively listening/answering).
 *
 * Named tokens:
 *  - Ink      #14121A  near-black with a violet cast, not flat black — the "at rest" surface
 *  - Signal   #7C5CFC  electric violet — wake word active, sending, primary actions
 *  - Ember    #FF7A45  warm coral — "this reply just came in" status indicator
 *  - Sprout   #34D399  fresh green — secondary accent color
 *  - Paper    #FAF8FF  near-white with the same violet cast as Ink, for light mode
 */

// Dark theme
val AryaInk = Color(0xFF14121A)
val AryaInkSurface = Color(0xFF1E1B27)
val AryaInkSurfaceVariant = Color(0xFF2A2634)
val AryaSignal = Color(0xFF7C5CFC)
val AryaSignalOn = Color(0xFFFFFFFF)
val AryaSignalContainerDark = Color(0xFF3A2C80)   // solid — used as primaryContainer in dark theme
val AryaOnSignalContainerDark = Color(0xFFE3D9FF)

// Light theme
val AryaPaper = Color(0xFFFAF8FF)
val AryaPaperSurface = Color(0xFFF1EEFA)
val AryaSignalDark = Color(0xFF5B3FE0) // deeper violet for enough contrast on light backgrounds
val AryaSignalContainerLight = Color(0xFFE7DFFF)  // solid — used as primaryContainer in light theme
val AryaOnSignalContainerLight = Color(0xFF241A5C)

// Shared accents (same hue in both themes — these are status colors, not surface colors)
val AryaEmber = Color(0xFFFF7A45)       // reply-source status indicator
val AryaEmberOn = Color(0xFF3D1400)
val AryaEmberContainer = Color(0xFFFFE0CF)
val AryaEmberContainerDark = Color(0xFF5C2A0F)

val AryaSprout = Color(0xFF34D399)      // secondary accent
val AryaSproutOn = Color(0xFF00391F)
val AryaSproutContainer = Color(0xFFB3F5DD)
val AryaSproutContainerDark = Color(0xFF0F4A34)

val AryaError = Color(0xFFE0526B)

val AryaSky = Color(0xFF4EA8FF)         // 4th accent — used for the "+" attach menu's Photo tile
val AryaSkyOn = Color(0xFF00264D)
val AryaSkyContainer = Color(0xFFD2E7FF)
val AryaSkyContainerDark = Color(0xFF123A66)

// Added for the "+" attach menu's fuller tile set (FIXES_LOG.md Phase 26) — Video/Music/etc
// needed more distinct accent hues than the original 3-color palette had.
val AryaGold = Color(0xFFF2B441)
val AryaRose = Color(0xFFFF6F9C)

// Hero/drawer-specific tokens lifted straight from the arya-ui.html mockup's :root vars —
// named to match (--text-dim, --text-faint, --ink-hairline) so HomeHeroSection/ToolsDrawer
// read as literal translations of that design rather than approximations via MaterialTheme's
// generic onSurfaceVariant.
val AryaTextDim = Color(0xFFB6AFC7)
val AryaTextFaint = Color(0xFF736C87)
val AryaHairline = Color(0xFF332E40)

// --- Deprecated aliases (kept temporarily so any missed call site still compiles; new code
// should reference the named tokens above directly) ---
@Deprecated("Use AryaSignal", ReplaceWith("AryaSignal"))
val AryaOrange = AryaEmber
@Deprecated("Use AryaSignalDark", ReplaceWith("AryaSignalDark"))
val AryaOrangeDark = AryaSignalDark
@Deprecated("Use AryaSignal", ReplaceWith("AryaSignal"))
val AryaBlue = AryaSignal
@Deprecated("Use AryaSprout", ReplaceWith("AryaSprout"))
val AryaGreen = AryaSprout
@Deprecated("Use AryaInk", ReplaceWith("AryaInk"))
val AryaBackground = AryaInk
@Deprecated("Use AryaInkSurface", ReplaceWith("AryaInkSurface"))
val AryaSurface = AryaInkSurface
