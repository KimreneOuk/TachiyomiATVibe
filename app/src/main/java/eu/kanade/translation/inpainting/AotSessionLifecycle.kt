package eu.kanade.translation.inpainting

/** Pure lifecycle helper for independently owned native AOT sessions. */
internal object AotSessionLifecycle {
    data class CloseFailure(val route: String, val error: Throwable)

    /**
     * Closes both sessions even when the first close fails. A null or aliased second
     * session is ignored so every distinct native handle is closed at most once.
     */
    fun closeIndependently(
        fixed: AutoCloseable?,
        dynamic: AutoCloseable?,
        nnapi: AutoCloseable? = null,
        qnn: AutoCloseable? = null,
        onFailure: (CloseFailure) -> Unit = {},
    ) {
        val closed = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<AutoCloseable, Boolean>(),
        )
        closeOneIfDistinct("qnn", qnn, closed, onFailure)
        closeOneIfDistinct("nnapi", nnapi, closed, onFailure)
        closeOneIfDistinct("fixed", fixed, closed, onFailure)
        closeOneIfDistinct("dynamic", dynamic, closed, onFailure)
    }

    private fun closeOneIfDistinct(
        route: String,
        session: AutoCloseable?,
        closed: MutableSet<AutoCloseable>,
        onFailure: (CloseFailure) -> Unit,
    ) {
        if (session != null && closed.add(session)) closeOne(route, session, onFailure)
    }

    private fun closeOne(
        route: String,
        session: AutoCloseable?,
        onFailure: (CloseFailure) -> Unit,
    ) {
        if (session == null) return
        try {
            session.close()
        } catch (error: Throwable) {
            onFailure(CloseFailure(route, error))
        }
    }
}
