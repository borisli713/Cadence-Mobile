package com.cadence.shifts.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = Blue700,
    onPrimary = PureWhite,
    primaryContainer = Blue50,
    onPrimaryContainer = Blue700,
    secondary = Slate900,
    onSecondary = PureWhite,
    secondaryContainer = Slate100,
    onSecondaryContainer = Slate800,
    tertiary = Emerald600,
    onTertiary = PureWhite,
    tertiaryContainer = Emerald50,
    onTertiaryContainer = Emerald700,
    background = Slate50,
    onBackground = Slate900,
    surface = PureWhite,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate600,
    outline = Slate200,
    outlineVariant = Slate300,
    error = Rose600,
    onError = PureWhite,
    errorContainer = Rose50,
    onErrorContainer = Rose700
)

private val DarkColorScheme = darkColorScheme(
    primary = Blue500,
    onPrimary = Slate950,
    primaryContainer = Slate800,
    onPrimaryContainer = Blue100,
    secondary = Slate200,
    onSecondary = Slate900,
    secondaryContainer = Slate800,
    onSecondaryContainer = Slate200,
    tertiary = Emerald600,
    onTertiary = PureWhite,
    tertiaryContainer = Slate800,
    onTertiaryContainer = Emerald100,
    background = Slate950,
    onBackground = Slate100,
    surface = Slate900,
    onSurface = Slate100,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate400,
    outline = Slate700,
    outlineVariant = Slate800,
    error = Rose600,
    onError = PureWhite,
    errorContainer = Slate800,
    onErrorContainer = Rose100
)

val CadenceShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun CadenceTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = CadenceTypography,
        shapes = CadenceShapes,
        content = content
    )
}
