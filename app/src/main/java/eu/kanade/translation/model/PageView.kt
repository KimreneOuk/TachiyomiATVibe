package eu.kanade.translation.model

data class PageView(
    val imageName: String?,
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
            lifecycle = PageLifecycle.Pending,
            overlay = PageView.OverlayState.Idle,
            errorMessage = null,
        )
    }
    val imageName = displayImageName
    val overlay = when {
        isStageRunning -> PageView.OverlayState.Running
        isStageFailed && imageName == null -> PageView.OverlayState.Error
        else -> PageView.OverlayState.Idle
    }
    return PageView(
        imageName = imageName,
        lifecycle = lifecycle,
        overlay = overlay,
        errorMessage = errorMessage,
    )
}
