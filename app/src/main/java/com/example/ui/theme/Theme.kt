package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CipherDarkColorScheme = darkColorScheme(
    primary = EmeraldCipher,
    onPrimary = EmeraldOnPrimary,
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = EmeraldOnContainer,
    secondary = AmberHyper,
    onSecondary = AmberOnSecondary,
    secondaryContainer = AmberContainer,
    onSecondaryContainer = AmberOnContainer,
    tertiary = CyanInfo,
    onTertiary = Color(0xFF002447),
    background = ObsidianDeep,
    onBackground = CrispIce,
    surface = SurfaceSlate,
    onSurface = CrispIce,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = MutedSlate,
    outline = BorderSubtle,
    error = CrimsonAlert,
    onError = Color(0xFF3E0006),
    errorContainer = CrimsonContainer,
    onErrorContainer = Color(0xFFFFDAD6)
)

private val CipherLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = Color(0xFFD97706),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFEF3C7),
    onSecondaryContainer = Color(0xFF78350F),
    tertiary = Color(0xFF0284C7),
    onTertiary = Color.White,
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = Color(0xFFD1D5DB),
    error = Color(0xFFDC2626),
    onError = Color.White
)

@Composable
fun CipherKeyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) CipherDarkColorScheme else CipherLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = CipherKeyTypography,
        content = content
    )
}
