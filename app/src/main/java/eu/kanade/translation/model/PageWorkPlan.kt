package eu.kanade.translation.model

import kotlinx.serialization.Serializable

@Serializable
data class PageWorkPlan(
    val runOcr: Boolean,
    val runTranslation: Boolean,
    val runInpaint: Boolean,
    val runRender: Boolean,
)
