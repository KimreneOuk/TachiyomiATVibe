package eu.kanade.presentation.components.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens dynamic bridge to the active Tachiyomi Material 3 theme.
 */
object DesignTokens {
    val CanvasDimmer = Color.Black.copy(alpha = 0.65f)
    val CanvasDimmerZero = Color.Transparent

    val SheetBackground: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surfaceContainerHigh

    val CardFill: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surfaceContainer

    val CardBorder: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.outlineVariant

    val AccentPrimary: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.primary

    val AccentGlow: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)

    val TextPrimary: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSurface

    val TextMuted: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSurfaceVariant

    val TextOnAccent: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onPrimary

    val ShapeSheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val ShapeSquircle = RoundedCornerShape(14.dp)
    val ShapePill = RoundedCornerShape(50)
    val ShapeCard = RoundedCornerShape(16.dp)
    val ShapeBadge = RoundedCornerShape(10.dp)
}
