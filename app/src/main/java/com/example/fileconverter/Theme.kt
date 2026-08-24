package com.example.fileconverter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Holds every color role the app needs. */
@Immutable
data class AppColors(
    // Backgrounds
    val background: Color,
    val surface: Color,
    val card: Color,
    // Text
    val onBackground: Color,
    val onSurface: Color,
    val onCard: Color,
    val muted: Color,
    // Borders & shadows
    val border: Color,
    // Accents
    val accent: Color,        // primary action (yellow in Default)
    val accentText: Color,    // text on accent
    // Pie-chart & badge colors
    val pieChart: Color,
    val badge: Color,
    // Specific UI elements
    val inputBg: Color,
    val inputBorder: Color,
    val sliderTrack: Color,
    val drawerBg: Color,
    val overlayBg: Color,
    // Named palette colors used in pie-chart / badges
    val yellow: Color,
    val pink: Color,
    val green: Color,
    val purple: Color,
    val blue: Color,
    val orange: Color,
    val brown: Color,
)

/** Default "Brutalist" theme — the original custom palette. */
private val DefaultColors = AppColors(
    background    = Color(0xFFFFF3C4),
    surface       = Color.White,
    card          = Color.White,
    onBackground  = Color(0xFF111111),
    onSurface     = Color(0xFF111111),
    onCard        = Color(0xFF111111),
    muted         = Color(0xFF7A7A7A),
    border        = Color(0xFF111111),
    accent        = Color(0xFFFFD400),
    accentText    = Color(0xFF111111),
    pieChart      = Color(0xFFE8CFA0),
    badge         = Color(0xFF111111),
    inputBg       = Color.White,
    inputBorder   = Color(0xFF111111),
    sliderTrack   = Color.White,
    drawerBg      = Color(0xFFFFF3C4),
    overlayBg     = Color(0xFFFFF3C4),
    yellow        = Color(0xFFFFD400),
    pink          = Color(0xFFFF5FA2),
    green         = Color(0xFF4ADE80),
    purple        = Color(0xFF9B6CFF),
    blue          = Color(0xFF4D96FF),
    orange        = Color(0xFFFF9F1C),
    brown         = Color(0xFF8B6914),
)

/** Light theme — clean white/grey. */
private val LightColors = AppColors(
    background    = Color(0xFFF5F5F5),
    surface       = Color.White,
    card          = Color.White,
    onBackground  = Color(0xFF1A1A1A),
    onSurface     = Color(0xFF1A1A1A),
    onCard        = Color(0xFF1A1A1A),
    muted         = Color(0xFF888888),
    border        = Color(0xFF222222),
    accent        = Color(0xFF2979FF),
    accentText    = Color.White,
    pieChart      = Color(0xFFE0E0E0),
    badge         = Color(0xFF222222),
    inputBg       = Color.White,
    inputBorder   = Color(0xFFBBBBBB),
    sliderTrack   = Color.White,
    drawerBg      = Color.White,
    overlayBg     = Color(0xFFF5F5F5),
    yellow        = Color(0xFFFFD400),
    pink          = Color(0xFFFF5FA2),
    green         = Color(0xFF4ADE80),
    purple        = Color(0xFF9B6CFF),
    blue          = Color(0xFF4D96FF),
    orange        = Color(0xFFFF9F1C),
    brown         = Color(0xFF8B6914),
)

/** Dark theme — dark grey/charcoal. */
private val DarkColors = AppColors(
    background    = Color(0xFF121212),
    surface       = Color(0xFF1E1E1E),
    card          = Color(0xFF2A2A2A),
    onBackground  = Color(0xFFE8E8E8),
    onSurface     = Color(0xFFE8E8E8),
    onCard        = Color(0xFFE8E8E8),
    muted         = Color(0xFF999999),
    border        = Color(0xFFCCCCCC),
    accent        = Color(0xFFFFD400),
    accentText    = Color(0xFF111111),
    pieChart      = Color(0xFF333333),
    badge         = Color(0xFFCCCCCC),
    inputBg       = Color(0xFF2A2A2A),
    inputBorder   = Color(0xFF555555),
    sliderTrack   = Color(0xFF333333),
    drawerBg      = Color(0xFF1E1E1E),
    overlayBg     = Color(0xFF121212),
    yellow        = Color(0xFFFFD400),
    pink          = Color(0xFFFF5FA2),
    green         = Color(0xFF4ADE80),
    purple        = Color(0xFF9B6CFF),
    blue          = Color(0xFF4D96FF),
    orange        = Color(0xFFFF9F1C),
    brown         = Color(0xFF8B6914),
)

/** CompositionLocal that provides the active [AppColors]. */
val LocalAppColors = staticCompositionLocalOf { DefaultColors }

/** Returns the correct [AppColors] for the given [ThemeMode]. */
fun colorsForMode(mode: ThemeMode): AppColors = when (mode) {
    ThemeMode.SYSTEM  -> DefaultColors  // fallback; caller should use isSystemInDarkTheme
    ThemeMode.LIGHT   -> LightColors
    ThemeMode.DARK    -> DarkColors
    ThemeMode.DEFAULT -> DefaultColors
}

/** Top-level theme wrapper. Pass the current [ThemeMode]. */
@Composable
fun AppThemeProvider(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val colors = colorsForMode(themeMode)
    CompositionLocalProvider(LocalAppColors provides colors) {
        content()
    }
}
