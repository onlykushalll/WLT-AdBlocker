package com.wlt.adblocker.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * WLT brand color scheme.
 *
 * Per design guidelines (Task 6-8):
 *   PRIMARY = dark green / teal (NOT blue/indigo — the genre-default blue
 *   clash was the first thing flagged for replacement)
 *   SECONDARY = amber (warm contrast for accents, badges, active states)
 *
 * The dark theme uses very dark surfaces so the dashboard chart and stat
 * numbers read at a glance; the light theme keeps the same brand hues but
 * on a near-white background.
 */

// --- Dark theme palette (default — WLT is a "privacy tool" so dark by default) ---
val WltPrimary = Color(0xFF0F7B6C)            // dark green/teal
val WltOnPrimary = Color(0xFFFFFFFF)
val WltPrimaryContainer = Color(0xFF0A5A4F)
val WltOnPrimaryContainer = Color(0xFFB8EFE5)

val WltSecondary = Color(0xFFD97706)          // amber
val WltOnSecondary = Color(0xFFFFFFFF)
val WltSecondaryContainer = Color(0xFF5C3408)
val WltOnSecondaryContainer = Color(0xFFFFD9A8)

val WltTertiary = Color(0xFF6B7C8C)           // slate, for non-brand accents
val WltOnTertiary = Color(0xFFFFFFFF)

val WltBackground = Color(0xFF0A0F0E)         // near-black with green tint
val WltOnBackground = Color(0xFFE6EFEC)
val WltSurface = Color(0xFF141A19)            // dark surface, slight green tint
val WltOnSurface = Color(0xFFD8E2DF)
val WltSurfaceVariant = Color(0xFF1F2A28)
val WltOnSurfaceVariant = Color(0xFFB5C2BD)

val WltError = Color(0xFFDC2626)              // red
val WltOnError = Color(0xFFFFFFFF)
val WltErrorContainer = Color(0xFF5C1010)
val WltOnErrorContainer = Color(0xFFFFB3B3)

val WltOutline = Color(0xFF4A5A55)
val WltOutlineVariant = Color(0xFF2A3A35)

// --- Light theme palette (brand hues preserved; surfaces flipped) ---
val WltPrimaryLight = Color(0xFF0F7B6C)
val WltOnPrimaryLight = Color(0xFFFFFFFF)
val WltPrimaryContainerLight = Color(0xFFB8EFE5)
val WltOnPrimaryContainerLight = Color(0xFF053229)

val WltSecondaryLight = Color(0xFFD97706)
val WltOnSecondaryLight = Color(0xFFFFFFFF)
val WltSecondaryContainerLight = Color(0xFFFFD9A8)
val WltOnSecondaryContainerLight = Color(0xFF5C3408)

val WltBackgroundLight = Color(0xFFF7FAF9)
val WltOnBackgroundLight = Color(0xFF10181A)
val WltSurfaceLight = Color(0xFFFFFFFF)
val WltOnSurfaceLight = Color(0xFF10181A)
val WltSurfaceVariantLight = Color(0xFFD6E0DD)
val WltOnSurfaceVariantLight = Color(0xFF3F4A47)

val WltErrorLight = Color(0xFFB00020)
val WltOnErrorLight = Color(0xFFFFFFFF)

val WltOutlineLight = Color(0xFF7A8A85)
