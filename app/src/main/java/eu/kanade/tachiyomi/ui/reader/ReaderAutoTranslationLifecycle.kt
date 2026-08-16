package eu.kanade.tachiyomi.ui.reader

import kotlinx.coroutines.Deferred

/**
 * Registers resource cleanup on the manager-owned reader stop boundary. The Deferred is backed by
 * applicationScope, so the cleanup is not cancelled when ReaderViewModel.onCleared() cancels its
 * own scope.
 */
internal fun registerReaderCleanupAfterStop(
    readerStop: Deferred<Unit>,
    cleanup: () -> Unit,
) {
    readerStop.invokeOnCompletion { cause ->
        if (cause == null) cleanup()
    }
}

/** Keeps delete/replacement actions ordered after the Reader auto resolver has been invalidated. */
internal fun runAfterReaderAutoReset(
    reset: () -> Unit,
    action: () -> Unit,
) {
    reset()
    action()
}
