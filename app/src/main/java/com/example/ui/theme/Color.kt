package com.example.ui.theme

import androidx.compose.ui.graphics.Color

/* ─────────────────────────────────────────────────────────────
 *  MIDNIGHT AURORA — ReelGuard design language
 *  Obsidian depths + violet/aqua aurora gradients.
 * ───────────────────────────────────────────────────────────── */

// Canvas layers
val Ink = Color(0xFF07070C)          // app background — near-black with a blue undertone
val Obsidian = Color(0xFF0E0E17)     // card base
val ObsidianHi = Color(0xFF151522)   // raised card base
val Surface1 = Color(0xFF10101B)     // primary card surface
val Surface2 = Color(0xFF171726)     // raised surface / dialogs
val Surface3 = Color(0xFF1F1F33)     // chips, inputs

// Aurora accents
val Violet = Color(0xFFA78BFA)       // primary
val VioletDeep = Color(0xFF7C3AED)   // pressed / gradients
val Aqua = Color(0xFF22D3EE)         // secondary accent
val Mint = Color(0xFF34D399)         // success
val Amber = Color(0xFFFBBF24)        // streak / warning
val Rose = Color(0xFFFB7185)         // danger

// Typography inks
val TextBright = Color(0xFFF4F4F8)
val TextMuted = Color(0xFF9B9BB0)
val TextFaint = Color(0xFF5C5C74)

// Hairlines
val HairLine = Color(0x14FFFFFF)     // 8% white border
val HairLineSoft = Color(0x0DFFFFFF) // 5% white border

/* ── Legacy aliases ──
 * Old palette names are kept as aliases so every legacy reference
 * automatically picks up the new Aurora colors. */
val PureBlack = Ink
val CardBlack = Surface1
val DialogBlack = Surface2
val ElectricCyan = Violet
val TealAccent = Aqua
val DangerRed = Rose
val SuccessGreen = Mint
val TextGray = TextMuted
val LabelGray = TextFaint
val PrimaryOrange = Amber
