package com.abap.privatep2p

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ABAPColors = darkColorScheme(
    primary = Color(0xFF79E2D0),
    onPrimary = Color(0xFF062B28),
    primaryContainer = Color(0xFF16443F),
    onPrimaryContainer = Color(0xFFB8F7EB),
    secondary = Color(0xFF9DB5F5),
    onSecondary = Color(0xFF17264A),
    secondaryContainer = Color(0xFF26385F),
    onSecondaryContainer = Color(0xFFDCE5FF),
    tertiary = Color(0xFFF2BD75),
    onTertiary = Color(0xFF3C2609),
    background = Color(0xFF090F18),
    onBackground = Color(0xFFEAF1F8),
    surface = Color(0xFF111B29),
    onSurface = Color(0xFFEAF1F8),
    surfaceVariant = Color(0xFF1B2939),
    onSurfaceVariant = Color(0xFFB4C1D0),
    outline = Color(0xFF405166),
    outlineVariant = Color(0xFF29384A),
    error = Color(0xFFFF8D91),
    onError = Color(0xFF410006)
)

private val ABAPShapes = Shapes(
    extraSmall = RoundedCornerShape(9.dp),
    small = RoundedCornerShape(13.dp),
    medium = RoundedCornerShape(19.dp),
    large = RoundedCornerShape(25.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

/** Premium dark visual system for ABAP Private. */
@Composable
fun ABAPPrivateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ABAPColors,
        shapes = ABAPShapes,
        content = content
    )
}
