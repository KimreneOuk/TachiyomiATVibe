package eu.kanade.presentation.reader.sheet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.designsystem.DesignTokens
import eu.kanade.presentation.components.designsystem.SheetHeader
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Reader settings bottom sheet: reading mode, display adjustments and
 * navigation feel. Accessed via Settings in the reader overflow menu.
 */
@Composable
fun ReaderSettingsSheet(
    screenModel: ReaderSettingsScreenModel,
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
            color = DesignTokens.SheetBackground,
            shape = DesignTokens.ShapeSheet,
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
                SheetHeader(
                    icon = Icons.Outlined.Settings,
                    title = stringResource(ATMR.strings.reader_settings_title),
                    subtitle = stringResource(ATMR.strings.reader_sheet_subtitle),
                    onClose = onDismissRequest,
                )

                Spacer(modifier = Modifier.height(4.dp))

                ReaderSetupTab(
                    screenModel = screenModel,
                    onInteractingWithVisuals = { isInteractingWithVisuals = it },
                )
            }
        }
    }
}
