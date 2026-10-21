package eu.kanade.presentation.reader.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * TachiyomiAT: floating top-right control shown while the current page is
 * zoomed past its normal scale; tapping returns it to normal. The wrapper
 * Box intercepts nothing — only the button consumes touches, so reader
 * gestures stay intact while the control is hidden.
 */
@Composable
fun ZoomResetAffordance(
    zoomed: Boolean,
    menuVisible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val surfaceColor = MaterialTheme.colorScheme
        .surfaceColorAtElevation(3.dp)
        .copy(alpha = if (dark) 0.9f else 0.95f)
    val topReserve by animateDpAsState(
        targetValue = if (menuVisible) 84.dp else 8.dp,
        label = "zoomResetTopReserve",
    )

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            zoomed,
            enter = fadeIn() + scaleIn(initialScale = 0.6f),
            exit = fadeOut() + scaleOut(targetScale = 0.6f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = topReserve)
                .padding(end = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(color = surfaceColor, shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onClick) {
                    Icon(
                        imageVector = Icons.Outlined.CenterFocusStrong,
                        contentDescription = stringResource(ATMR.strings.reader_zoom_reset),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
