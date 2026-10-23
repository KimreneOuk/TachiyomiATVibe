package eu.kanade.presentation.reader.appbars

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Ultra-thin Webtoon rail that dynamically bulges into a page indicator circle on touch.
 * Glides smoothly through continuous content and rests without snapping.
 */
@Composable
fun ReaderVerticalScrubber(
    visible: Boolean,
    currentPage: Int,
    totalPages: Int,
    onScrollProgress: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (totalPages <= 1) return

    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }

    val rawFraction = ((currentPage - 1).coerceAtLeast(0).toFloat() / (totalPages - 1).coerceAtLeast(1))
        .coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = rawFraction,
        animationSpec = tween(durationMillis = 120),
        label = "webtoon_scrub_fraction",
    )

    val currentFraction = if (isDragging) dragFraction else animatedFraction
    val displayPage = (currentFraction * (totalPages - 1)).roundToInt() + 1

    LaunchedEffect(displayPage) {
        if (isDragging) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    // Dynamic bulging animation on interaction
    val thumbSize by animateDpAsState(
        targetValue = if (isDragging) 36.dp else 6.dp,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "bulge_size",
    )
    val thumbAlpha by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0.6f,
        animationSpec = tween(150),
        label = "bulge_alpha",
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)) + slideInHorizontally(tween(150)) { it / 2 },
        exit = fadeOut(tween(100)) + slideOutHorizontally(tween(100)) { it / 2 },
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .height(300.dp)
                .width(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Ultra-thin 2dp guide line centered in the track
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(2.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        MaterialTheme.colorScheme.onSurface
                            .copy(alpha = if (isSystemInDarkTheme()) 0.25f else 0.35f),
                    )
                    .onGloballyPositioned {
                        trackHeightPx = it.size.height.toFloat()
                    },
            )

            // Touch interaction bounding box
            val maxThumbPx = with(density) { 36.dp.toPx() }
            val travelDistancePx = (trackHeightPx - maxThumbPx).coerceAtLeast(0f)
            val currentOffsetPx = (currentFraction * travelDistancePx).roundToInt()

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(44.dp)
                    .pointerInput(totalPages) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                val clampedY = offset.y.coerceIn(0f, trackHeightPx)
                                dragFraction = if (trackHeightPx > 0f) (clampedY / trackHeightPx).coerceIn(0f, 1f) else 0f
                                onScrollProgress(dragFraction)
                            },
                            onDragEnd = { isDragging = false },
                            onDragCancel = { isDragging = false },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                val clampedY = change.position.y.coerceIn(0f, trackHeightPx)
                                dragFraction = if (trackHeightPx > 0f) (clampedY / trackHeightPx).coerceIn(0f, 1f) else 0f
                                onScrollProgress(dragFraction)
                            },
                        )
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                // Bulging circle centered over the thin line
                Box(
                    modifier = Modifier
                        .offset { IntOffset(0, currentOffsetPx) }
                        .size(thumbSize)
                        .clip(CircleShape)
                        .shadow(if (isDragging) 8.dp else 0.dp, CircleShape)
                        .background(
                            if (isDragging) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = thumbAlpha)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isDragging) {
                        Text(
                            text = "$displayPage",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}
