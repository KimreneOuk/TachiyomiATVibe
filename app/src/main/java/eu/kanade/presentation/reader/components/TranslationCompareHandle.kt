package eu.kanade.presentation.reader.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

// Idle auto-hide: after this many ms with the menu collapsed and no
// interaction, the floater dims and tucks toward its docked edge.
private const val IDLE_DELAY_MS = 2500L
private const val IDLE_ALPHA = 0.38f

// Shared by every position animation so an interrupted snap restarts with the
// same easing instead of fighting another spec.
private val DockSnapSpring = spring<Float>(stiffness = 380f)

/**
 * Draggable translator floater (chat-head style) for quick original/translated
 * comparison on the CURRENT page.
 *
 * The two [Animatable] fractions are the single source of truth: drag deltas
 * are applied with ordered [Animatable.snapTo] calls and release snaps with
 * [DockSnapSpring], so motion is continuous across grab/release and menu
 * open/close. The menu is a positioned sibling of the handle, offset and
 * clamped independently to stay inside the container, so expanding it never
 * moves the handle. Release snaps to the nearest left/right edge; the docked
 * side and normalized vertical position are persisted by the caller ONLY at
 * drag end.
 */
@Composable
fun TranslationCompareHandle(
    visible: Boolean,
    hasTranslation: Boolean,
    showingTranslated: Boolean,
    autoTranslate: Boolean,
    onSelectOriginal: () -> Unit,
    onSelectTranslated: () -> Unit,
    onToggleAutoTranslate: () -> Unit,
    onOpenSettings: () -> Unit,
    idleFadeEnabled: Boolean,
    dockEnd: Boolean,
    onDockChange: (Boolean) -> Unit,
    verticalFraction: Float,
    onPositionChange: (Float) -> Unit,
    bottomReservePx: Float = 0f,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var containerWidthPx by remember { mutableIntStateOf(0) }
    var containerHeightPx by remember { mutableIntStateOf(0) }
    var menuSizePx by remember { mutableStateOf(IntSize.Zero) }
    var expanded by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var isIdle by remember { mutableStateOf(false) }

    val handleSizePx = with(density) { 40.dp.toPx() }
    val menuSpacingPx = with(density) { 6.dp.toPx() }
    val tuckPx = with(density) { 8.dp.toPx() }

    // Normalized position, animated. x: 0 = start edge, 1 = end edge;
    // y: 0..1 top-to-bottom. Read only inside offset lambdas, so drag
    // frames invalidate layout without recomposing the reader.
    val xFraction = remember { Animatable(if (dockEnd) 1f else 0f) }
    val yFraction = remember { Animatable(verticalFraction.coerceIn(0f, 1f)) }

    // Follow preference changes (settings resets, dock-side flips) while idle.
    LaunchedEffect(dockEnd, verticalFraction, containerWidthPx) {
        if (dragging) return@LaunchedEffect
        launch { xFraction.animateTo(if (dockEnd) 1f else 0f, DockSnapSpring) }
        launch { yFraction.animateTo(verticalFraction.coerceIn(0f, 1f), DockSnapSpring) }
    }

    // Idle timer: armed whenever the menu is collapsed and no drag is active.
    LaunchedEffect(expanded, dragging, idleFadeEnabled) {
        if (!expanded && !dragging && idleFadeEnabled) {
            delay(IDLE_DELAY_MS)
            isIdle = true
        } else {
            isIdle = false
        }
    }

    val handleAlpha by animateFloatAsState(
        targetValue = if (isIdle && !expanded) IDLE_ALPHA else 1f,
        animationSpec = tween(200),
        label = "floater-alpha",
    )

    Box(
        modifier = modifier.onSizeChanged {
            containerWidthPx = it.width
            containerHeightPx = it.height
        },
    ) {
        val insetPx = with(density) { 8.dp.toPx() }
        val usableWidth = (containerWidthPx - 2 * insetPx - handleSizePx).coerceAtLeast(1f)
        val usableHeight = (containerHeightPx - handleSizePx).coerceAtLeast(1f)

        // Anchored at the handle's top-left; sized only by the handle so menu
        // layout can never shift it.
        Box(
            modifier = Modifier
                .offset {
                    val tuck = if (isIdle && !expanded) {
                        if (xFraction.value > 0.5f) tuckPx else -tuckPx
                    } else {
                        0f
                    }
                    IntOffset(
                        (insetPx + xFraction.value * usableWidth + tuck).roundToInt(),
                        (yFraction.value * usableHeight)
                            .coerceAtMost((usableHeight - bottomReservePx).coerceAtLeast(0f))
                            .roundToInt(),
                    )
                }
                .alpha(handleAlpha),
        ) {
            HandleButton(
                expanded = expanded,
                onClick = {
                    expanded = !expanded
                    isIdle = false
                },
                modifier = Modifier
                    .pointerInput(usableWidth, usableHeight) {
                        // Gesture-local truth for end-of-gesture decisions; the
                        // animatables catch up via ordered snapTo launches.
                        var lastX = xFraction.value
                        var lastY = yFraction.value
                        detectDragGestures(
                            onDragStart = {
                                dragging = true
                                isIdle = false
                                lastX = xFraction.value
                                lastY = yFraction.value
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                lastX = (lastX + dragAmount.x / usableWidth).coerceIn(0f, 1f)
                                lastY = (lastY + dragAmount.y / usableHeight).coerceIn(0f, 1f)
                                val x = lastX
                                val y = lastY
                                scope.launch {
                                    xFraction.snapTo(x)
                                    yFraction.snapTo(y)
                                }
                            },
                            onDragEnd = {
                                dragging = false
                                val endSide = lastX > 0.5f
                                if (endSide != dockEnd) onDockChange(endSide)
                                if (lastY !in verticalFraction - 0.001f..verticalFraction + 0.001f) {
                                    onPositionChange(lastY)
                                }
                                scope.launch {
                                    xFraction.animateTo(if (endSide) 1f else 0f, DockSnapSpring)
                                }
                            },
                            onDragCancel = {
                                dragging = false
                                scope.launch {
                                    xFraction.animateTo(if (dockEnd) 1f else 0f, DockSnapSpring)
                                }
                            },
                        )
                    },
            )

            FloaterMenu(
                hasTranslation = hasTranslation,
                showingTranslated = showingTranslated,
                autoTranslate = autoTranslate,
                onSelectOriginal = {
                    onSelectOriginal()
                    expanded = false
                },
                onSelectTranslated = {
                    onSelectTranslated()
                    expanded = false
                },
                onToggleAutoTranslate = onToggleAutoTranslate,
                onOpenSettings = {
                    onOpenSettings()
                    expanded = false
                },
                onSizeChangedPx = { menuSizePx = it },
                visible = expanded,
                dockEnd = dockEnd,
                modifier = Modifier.offset {
                    val menuWidth = menuSizePx.width.toFloat()
                    val menuHeight = menuSizePx.height.toFloat()
                    if (menuWidth < 1f || menuHeight < 1f) return@offset IntOffset.Zero
                    // Center on the handle, then clamp so the whole menu stays
                    // inside the container and clear of the bottom reserve.
                    val anchorTop = (yFraction.value * usableHeight)
                        .coerceAtMost((usableHeight - bottomReservePx).coerceAtLeast(0f))
                    val dyMin = -anchorTop
                    val dyMax = (containerHeightPx - bottomReservePx - menuHeight - anchorTop)
                        .coerceAtLeast(dyMin)
                    val dy = ((handleSizePx - menuHeight) / 2f).coerceIn(dyMin, dyMax)
                    val anchorLeft = insetPx + xFraction.value * usableWidth
                    val dx = if (dockEnd) {
                        (-(menuWidth + menuSpacingPx)).coerceAtLeast(-anchorLeft)
                    } else {
                        (handleSizePx + menuSpacingPx)
                            .coerceAtMost(containerWidthPx - menuWidth - anchorLeft)
                    }
                    IntOffset(dx.roundToInt(), dy.roundToInt())
                },
            )
        }
    }
}

@Composable
private fun HandleButton(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                shape = RoundedCornerShape(percent = 50),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.CompareArrows,
            contentDescription = stringResource(ATMR.strings.reader_compare_handle),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FloaterMenu(
    hasTranslation: Boolean,
    showingTranslated: Boolean,
    autoTranslate: Boolean,
    onSelectOriginal: () -> Unit,
    onSelectTranslated: () -> Unit,
    onToggleAutoTranslate: () -> Unit,
    onOpenSettings: () -> Unit,
    onSizeChangedPx: (IntSize) -> Unit,
    visible: Boolean,
    dockEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) +
            expandHorizontally(tween(150), expandFrom = if (dockEnd) Alignment.End else Alignment.Start),
        exit = fadeOut(tween(90)) +
            shrinkHorizontally(tween(120), shrinkTowards = if (dockEnd) Alignment.End else Alignment.Start),
        modifier = modifier,
    ) {
        val activeColor = MaterialTheme.colorScheme.primary
        val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant
        val disabledAlpha = if (hasTranslation) 1f else 0.38f

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
            modifier = Modifier
                .widthIn(max = 200.dp)
                .onSizeChanged { onSizeChangedPx(IntSize(it.width, it.height)) },
        ) {
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                FloaterMenuRow(
                    icon = Icons.Outlined.Image,
                    label = stringResource(ATMR.strings.reader_compare_show_original),
                    tint = if (hasTranslation && !showingTranslated) activeColor else inactiveColor,
                    enabled = hasTranslation,
                    rowAlpha = disabledAlpha,
                    onClick = onSelectOriginal,
                )
                FloaterMenuRow(
                    icon = Icons.Outlined.Translate,
                    label = stringResource(ATMR.strings.reader_compare_show_translated),
                    tint = if (hasTranslation && showingTranslated) activeColor else inactiveColor,
                    enabled = hasTranslation,
                    rowAlpha = disabledAlpha,
                    onClick = onSelectTranslated,
                )
                Row(
                    modifier = Modifier
                        .clickable(onClick = onToggleAutoTranslate)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = if (autoTranslate) activeColor else inactiveColor,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(ATMR.strings.reader_floater_auto_translate),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = autoTranslate,
                        onCheckedChange = { onToggleAutoTranslate() },
                    )
                }
                FloaterMenuRow(
                    icon = Icons.Outlined.Settings,
                    label = stringResource(ATMR.strings.reader_compare_open_settings),
                    tint = inactiveColor,
                    enabled = true,
                    rowAlpha = 1f,
                    onClick = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun FloaterMenuRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean,
    rowAlpha: Float,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp)
            .alpha(if (enabled) rowAlpha else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
        )
    }
}
