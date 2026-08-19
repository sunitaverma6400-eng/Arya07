package com.arya.ai.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design tokens for Arya — magical/arcane identity (per direct request): Arc Reactor gold +
 * multiverse silver-thread energy against a deep void, everywhere in the app.
 *
 * Previous palette (violet "Signal") read as generic Android-sample-app purple. This one keeps
 * the same TOKEN NAMES (AryaSignal, AryaEmber, etc.) so every existing screen re-themes
 * automatically just from this file changing — but the VALUES now build a gold/silver duotone
 * against a near-black cosmic void, instead of the old violet/coral pairing:
 *  - Ink      #0B0A10  near-black with the faintest violet cast — "the space between worlds"
 *  - Signal   #E8AC3D  Arc Reactor gold — now THE primary accent everywhere (was violet)
 *  - Silver   #C9CDD8  arcane/multiverse-thread silver — the new secondary magical accent
 *  - Ember    #E8763D  molten copper — "this reply just came in" status, warmer than gold so
 *             it stays legible as a distinct status color instead of blending into the gold UI
 *  - Paper    #FAF8FF  unchanged — light mode still needs a plain readable base
 */

// Dark theme — void + gold
val AryaInk = Color(0xFF0B0A10)
val AryaInkSurface = Color(0xFF17141F)
val AryaInkSurfaceVariant = Color(0xFF211D2C)
val AryaSignal = Color(0xFFE8AC3D)                // Arc Reactor gold — was violet #7C5CFC
val AryaSignalOn = Color(0xFF2A1B00)              // deep bronze-black — dark text reads on gold
val AryaSignalContainerDark = Color(0xFF4A3410)   // solid — used as primaryContainer in dark theme
val AryaOnSignalContainerDark = Color(0xFFFFE9B8)

// Light theme
val AryaPaper = Color(0xFFFAF8FF)
val AryaPaperSurface = Color(0xFFF1EEE0)
val AryaSignalDark = Color(0xFFA97418) // deeper gold for enough contrast on light backgrounds
val AryaSignalContainerLight = Color(0xFFFFE9B8)
val AryaOnSignalContainerLight = Color(0xFF3D2900)

// Silver — the arcane/multiverse-thread secondary accent, new addition (not a renamed token,
// since nothing "silver" existed in the old palette to repurpose).
val AryaSilver = Color(0xFFC9CDD8)
val AryaSilverOn = Color(0xFF1C1E22)
val AryaSilverContainer = Color(0xFFE3E6EC)
val AryaSilverContainerDark = Color(0xFF3A3D45)

// Shared accents (same hue in both themes — these are status colors, not surface colors)
val AryaEmber = Color(0xFFE8763D)       // reply-source status indicator — copper, was coral #FF7A45
val AryaEmberOn = Color(0xFF3D1400)
val AryaEmberContainer = Color(0xFFFFE0CF)
val AryaEmberContainerDark = Color(0xFF5C2A0F)

val AryaSprout = Color(0xFF34D399)      // kept as-is — functional "success" green used elsewhere,
val AryaSproutOn = Color(0xFF00391F)    // not part of the gold/silver identity itself
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
val AryaTextDim = Color(0xFFC9C0D6)
val AryaTextFaint = Color(0xFF8A8296)
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
