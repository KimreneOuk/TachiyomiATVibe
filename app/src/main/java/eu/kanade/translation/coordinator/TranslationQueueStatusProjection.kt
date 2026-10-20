package eu.kanade.translation.coordinator

import eu.kanade.translation.model.Translation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

/** Replays each queued translation's current status, then follows its live state flow. */
internal fun translationQueueStatusFlow(queue: StateFlow<List<Translation>>): Flow<Translation> =
    queue.flatMapLatest { translations ->
        translations
            .map { translation ->
                translation.statusFlow
                    .drop(1)
                    .map { translation }
                    .onStart { emit(translation) }
            }
            .merge()
    }
