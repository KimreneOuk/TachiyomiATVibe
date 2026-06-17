package eu.kanade.translation.model

data class PageView(
    val imageName: String?,
    val renderRevision: Long,
    val lifecycle: PageLifecycle,
    val overlay: OverlayState,
    val errorMessage: String?,
) {
    enum class OverlayState {
        Idle,
        Running,
        Error,
    }
}

fun PageTranslation?.toPageView(): PageView {
    if (this == null) {
        return PageView(
            imageName = null,
            renderRevision = 0L,
            lifecycle = PageLifecycle.Pending,
            overlay = PageView.OverlayState.Idle,
            errorMessage = null,
        )
    }
    val imageName = renderedImageName ?: cleanedImageName
    val overlay = when {
        isStageRunning && imageName == null -> PageView.OverlayState.Running
        isStageFailed && imageName == null -> PageView.OverlayState.Error
        else -> PageView.OverlayState.Idle
    }
    return PageView(
        imageName = imageName,
        renderRevision = renderRevision,
        lifecycle = lifecycle,
        overlay = overlay,
        errorMessage = errorMessage,
    )
}
