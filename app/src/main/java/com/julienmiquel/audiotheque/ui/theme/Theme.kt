package com.julienmiquel.audiotheque.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.julienmiquel.audiotheque.core.settings.ThemeMode

/**
 * Thème sombre à accent vert de l'app actuelle (Material 3), thème clair en option.
 * Rôles de couleur issus d'une palette tonale M3 : contrastes conformes dans les deux thèmes.
 */
private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF88D982),
    onPrimary = Color(0xFF003A03),
    primaryContainer = Color(0xFF0F5313),
    onPrimaryContainer = Color(0xFFA3F69C),
    inversePrimary = Color(0xFF2A6B2A),
    secondary = Color(0xFFBACCB3),
    onSecondary = Color(0xFF253423),
    secondaryContainer = Color(0xFF3B4B38),
    onSecondaryContainer = Color(0xFFD5E8CF),
    tertiary = Color(0xFFA0CFD4),
    onTertiary = Color(0xFF00363B),
    tertiaryContainer = Color(0xFF1F4D52),
    onTertiaryContainer = Color(0xFFBCEBF0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101411),
    onBackground = Color(0xFFE0E4DB),
    surface = Color(0xFF101411),
    onSurface = Color(0xFFE0E4DB),
    surfaceVariant = Color(0xFF424940),
    onSurfaceVariant = Color(0xFFC2C9BD),
    surfaceTint = Color(0xFF88D982),
    inverseSurface = Color(0xFFE0E4DB),
    inverseOnSurface = Color(0xFF2D322C),
    outline = Color(0xFF8C9388),
    outlineVariant = Color(0xFF424940),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF363A35),
    surfaceDim = Color(0xFF101411),
    surfaceContainerLowest = Color(0xFF0B0F0B),
    surfaceContainerLow = Color(0xFF181D18),
    surfaceContainer = Color(0xFF1C211C),
    surfaceContainerHigh = Color(0xFF272B26),
    surfaceContainerHighest = Color(0xFF313630),
)

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF2A6B2A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFACF4A4),
    onPrimaryContainer = Color(0xFF002203),
    inversePrimary = Color(0xFF91D88A),
    secondary = Color(0xFF52634F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD5E8CF),
    onSecondaryContainer = Color(0xFF101F0F),
    tertiary = Color(0xFF38656A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBCEBF0),
    onTertiaryContainer = Color(0xFF002023),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7FBF1),
    onBackground = Color(0xFF181D17),
    surface = Color(0xFFF7FBF1),
    onSurface = Color(0xFF181D17),
    surfaceVariant = Color(0xFFDEE5D8),
    onSurfaceVariant = Color(0xFF424940),
    surfaceTint = Color(0xFF2A6B2A),
    inverseSurface = Color(0xFF2D322C),
    inverseOnSurface = Color(0xFFEEF2E9),
    outline = Color(0xFF72796F),
    outlineVariant = Color(0xFFC2C9BD),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7FBF1),
    surfaceDim = Color(0xFFD7DBD2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5EC),
    surfaceContainer = Color(0xFFEBEFE6),
    surfaceContainerHigh = Color(0xFFE6E9E0),
    surfaceContainerHighest = Color(0xFFE0E4DB),
)

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/** Les tailles de texte suivent la taille de police système (unités sp de la typographie M3). */
@Composable
fun AudiothequeTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDarkTheme(mode)) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
