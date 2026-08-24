package eu.kanade.tachiyomi.data.translation

import eu.kanade.translation.model.Translation

internal object BatchTranslationForegroundPolicy {

    fun shouldKeepServiceRunning(states: Collection<Translation.State>): Boolean {
        return states.any { it == Translation.State.QUEUE || it == Translation.State.TRANSLATING }
    }
}
