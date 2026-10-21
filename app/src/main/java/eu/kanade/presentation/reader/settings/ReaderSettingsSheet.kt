package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.DialogWindowProvider
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Grouped reader configuration bottom sheet (UX prototype). One scrollable
 * surface with collapsible sections replacing the previous tabbed dialog;
 * every setting binds to the same [ReaderSettingsScreenModel] preferences as
 * before, so behavior and persistence are unchanged.
 */
@Composable
fun ReaderSettingsSheet(
    onDismissRequest: () -> Unit,
    onShowMenus: () -> Unit,
    onHideMenus: () -> Unit,
    screenModel: ReaderSettingsScreenModel,
    translateButtonWithControls: Boolean,
    onTranslateButtonWithControlsChange: (Boolean) -> Unit,
) {
    // Mirror the old dialog's live-preview behavior: while the display
    // adjustment section is open, drop the dialog dim and hide the reader
    // chrome so brightness/color filter changes are visible on the artwork.
    var displaySectionExpanded by remember { mutableStateOf(false) }
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    LaunchedEffect(displaySectionExpanded) {
        if (displaySectionExpanded) {
            window?.setDimAmount(0f)
            onHideMenus()
        } else {
            window?.setDimAmount(0.5f)
            onShowMenus()
        }
    }

    AdaptiveSheet(
        onDismissRequest = {
            onDismissRequest()
            onShowMenus()
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = MaterialTheme.padding.medium),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(ATMR.strings.reader_settings_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismissRequest) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(tachiyomi.i18n.MR.strings.action_close),
                    )
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                SheetSection(
                    title = stringResource(ATMR.strings.reader_section_appearance),
                    initiallyExpanded = true,
                ) {
                    AppearanceSection(screenModel)
                }

                SheetSection(
                    title = stringResource(ATMR.strings.reader_section_image_display),
                    initiallyExpanded = true,
                ) {
                    ImageDisplaySection(screenModel)
                }

                SheetSection(
                    title = stringResource(ATMR.strings.reader_section_display_adjustment),
                    initiallyExpanded = true,
                    onExpandedChange = { displaySectionExpanded = it },
                ) {
                    DisplayAdjustmentSection(screenModel)
                }

                SheetSection(
                    title = stringResource(tachiyomi.i18n.MR.strings.pref_category_reading_mode),
                    initiallyExpanded = true,
                ) {
                    ReadingModeSection(screenModel)
                }

                SheetSection(
                    title = stringResource(ATMR.strings.reader_section_navigation_gestures),
                ) {
                    NavigationGesturesSection(screenModel)
                }

                SheetSection(
                    title = stringResource(ATMR.strings.reader_section_floating_controls),
                    initiallyExpanded = true,
                ) {
                    FloatingControlsSection(
                        screenModel = screenModel,
                        translateButtonWithControls = translateButtonWithControls,
                        onTranslateButtonWithControlsChange = onTranslateButtonWithControlsChange,
                    )
                }
            }
        }
    }
}
