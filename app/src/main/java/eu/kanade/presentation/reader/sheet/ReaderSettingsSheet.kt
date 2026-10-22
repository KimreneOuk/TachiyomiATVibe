package eu.kanade.presentation.reader.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel

/**
 * Dedicated bottom drawer for Reader Setup (Reading Mode & Display Adjustments).
 * Accessed via Settings in the reader overflow menu.
 */
@Composable
fun ReaderSettingsSheet(
    screenModel: ReaderSettingsScreenModel,
    translateButtonWithControls: Boolean,
    onTranslateButtonWithControlsChange: (Boolean) -> Unit,
    onDismissRequest: () -> Unit,
    onShowMenus: () -> Unit,
    onHideMenus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isInteractingWithVisuals by remember { mutableStateOf(false) }

    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    LaunchedEffect(isInteractingWithVisuals) {
        if (isInteractingWithVisuals) {
            window?.setDimAmount(0f)
            onHideMenus()
        } else {
            window?.setDimAmount(0.6f)
            onShowMenus()
        }
    }

    AdaptiveSheet(
        forceSheetMode = true,
        onDismissRequest = {
            onDismissRequest()
            onShowMenus()
        },
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight(0.68f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
                Spacer(modifier = Modifier.height(4.dp))

                ReaderSheetHeader(
                    icon = Icons.Outlined.Settings,
                    title = "Reader Settings",
                    subtitle = "Display & Reading Mode",
                    onClose = onDismissRequest,
                )

                Spacer(modifier = Modifier.height(8.dp))

                ReaderSetupTab(
                    screenModel = screenModel,
                    translateButtonWithControls = translateButtonWithControls,
                    onTranslateButtonWithControlsChange = onTranslateButtonWithControlsChange,
                    onInteractingWithVisuals = { isInteractingWithVisuals = it },
                )
            }
        }
    }
}
