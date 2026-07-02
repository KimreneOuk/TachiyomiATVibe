package eu.kanade.translation.remote

class RemotePageTranslationException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        const val DESKTOP_UNREACHABLE = "Desktop Server Unreachable"
    }
}
