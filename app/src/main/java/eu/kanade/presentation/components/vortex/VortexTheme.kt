package eu.kanade.presentation.components.vortex

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens for the Vortex deep-charcoal and electric-orange theme.
 */
object VortexTokens {
    val CanvasDimmer = Color.Black.copy(alpha = 0.65f)
    val CanvasDimmerZero = Color.Transparent
    val SheetBackground = Color(0xFF121214)
    val CardFill = Color(0xFF1E1E22)
    val CardBorder = Color(0xFF2C2C32)
    val AccentPrimary = Color(0xFFFF6600)
    val AccentGlow = Color(0x40FF6600)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextMuted = Color(0xFF8E8E93)
    val TextOnAccent = Color(0xFF000000)

    val ShapeSheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val ShapeSquircle = RoundedCornerShape(14.dp)
    val ShapePill = RoundedCornerShape(50)
    val ShapeCard = RoundedCornerShape(16.dp)
    val ShapeBadge = RoundedCornerShape(10.dp)
}
