package eu.kanade.presentation.reader.appbars

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.reader.components.ReaderQuickActionsMenu
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot

private val animationSpec = tween<IntOffset>(200)
private val fadeSpec = tween<Float>(200)

/**
 * Reader overlay chrome for the UX modernization prototype: a compact floating
 * header up top and a floating navigation dock (with quick actions menu and
 * batch status line) at the bottom. Both hide together during immersive
 * reading via [visible], exactly like the previous app bars.
 */
@Composable
fun ReaderAppBars(
    visible: Boolean,
    fullscreen: Boolean,

    mangaTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    onClickTopAppBar: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,

    viewer: Viewer?,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,

    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    fullscreenEnabled: Boolean,
    onClickToggleFullscreen: () -> Unit,
    onClickSettings: () -> Unit,
    onClickTranslationSettings: () -> Unit,
    translationState: Translation.State = Translation.State.NOT_TRANSLATED,
    translationBatchProgress: TranslationProgressSnapshot? = null,
    isBatchSession: Boolean = false,
    onClickTranslate: () -> Unit = {},
) {
    val isRtl = viewer is R2LPagerViewer
    var quickActionsExpanded by remember { mutableStateOf(false) }

    // The quick menu lives and dies with the reader chrome: hiding the controls
    // (page tap) or opening a sheet always collapses it, so it can never linger
    // over the artwork.
    LaunchedEffect(visible) {
        if (!visible) quickActionsExpanded = false
    }

    val modifierWithInsetsPadding = if (fullscreen) {
        Modifier.systemBarsPadding()
    } else {
        Modifier
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(
                    initialOffsetY = { -it },
                    animationSpec = animationSpec,
                ) + fadeIn(animationSpec = fadeSpec),
                exit = slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = animationSpec,
                ) + fadeOut(animationSpec = fadeSpec),
            ) {
                ReaderTopHeader(
                    modifier = modifierWithInsetsPadding.padding(horizontal = 8.dp, vertical = 4.dp),
                    mangaTitle = mangaTitle,
                    chapterTitle = chapterTitle,
                    navigateUp = navigateUp,
                    onClickTopAppBar = onClickTopAppBar,
                    bookmarked = bookmarked,
                    onToggleBookmarked = onToggleBookmarked,
                    onOpenInWebView = onOpenInWebView,
                    onOpenInBrowser = onOpenInBrowser,
                    onShare = onShare,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = animationSpec,
                ) + fadeIn(animationSpec = fadeSpec),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = animationSpec,
                ) + fadeOut(animationSpec = fadeSpec),
            ) {
                Column(
                    modifier = modifierWithInsetsPadding.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    ReaderQuickActionsMenu(
                        visible = quickActionsExpanded,
                        readingMode = readingMode,
                        onClickReadingMode = {
                            quickActionsExpanded = false
                            onClickReadingMode()
                        },
                        fullscreen = fullscreenEnabled,
                        onClickFullscreen = onClickToggleFullscreen,
                        onClickSettings = {
                            quickActionsExpanded = false
                            onClickSettings()
                        },
                        onClickTranslationSettings = {
                            quickActionsExpanded = false
                            onClickTranslationSettings()
                        },
                    )

                    if (isBatchSession) {
                        translationBatchProgress?.let { snapshot ->
                            ReaderTranslationStatusLine(snapshot = snapshot)
                        }
                    }

                    ReaderNavigationDock(
                        isRtl = isRtl,
                        onNextChapter = onNextChapter,
                        enabledNext = enabledNext,
                        onPreviousChapter = onPreviousChapter,
                        enabledPrevious = enabledPrevious,
                        currentPage = currentPage,
                        totalPages = totalPages,
                        onPageIndexChange = onPageIndexChange,
                        translationState = translationState,
                        translationBatchProgress = translationBatchProgress,
                        isBatchSession = isBatchSession,
                        onClickTranslate = onClickTranslate,
                        quickActionsExpanded = quickActionsExpanded,
                        onToggleQuickActions = { quickActionsExpanded = !quickActionsExpanded },
                    )
                }
            }
        }
    }
}
