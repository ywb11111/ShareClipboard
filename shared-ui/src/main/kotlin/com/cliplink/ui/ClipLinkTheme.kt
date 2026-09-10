package com.cliplink.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF17211B)
private val Forest = Color(0xFF176B4D)
private val Mint = Color(0xFFC9F2DD)
private val Paper = Color(0xFFF7FAF7)
private val Warm = Color(0xFFFFF4D7)

private val LightColors = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    primaryContainer = Mint,
    onPrimaryContainer = Color(0xFF073B29),
    secondary = Color(0xFF506357),
    secondaryContainer = Color(0xFFDCE8DF),
    tertiary = Color(0xFF8A5D00),
    tertiaryContainer = Warm,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE8EEE9),
    outline = Color(0xFF718078),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF89D6AF),
    onPrimary = Color(0xFF003823),
    primaryContainer = Color(0xFF005235),
    onPrimaryContainer = Color(0xFFA6F3CA),
    secondary = Color(0xFFB8CCBE),
    secondaryContainer = Color(0xFF3A4B40),
    tertiary = Color(0xFFFFC95C),
    tertiaryContainer = Color(0xFF684500),
    background = Color(0xFF101512),
    onBackground = Color(0xFFE0E7E1),
    surface = Color(0xFF171D19),
    onSurface = Color(0xFFE0E7E1),
    surfaceVariant = Color(0xFF3F4943),
    outline = Color(0xFF89938C),
)

@Composable
fun ClipLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
