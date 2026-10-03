package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.EnvelopePlan
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.pipeline.batch.envelope.EnvelopePlanPublication
import kotlinx.coroutines.runBlocking

/** Test adapter for artifact fixtures using the production store facade. */
internal fun EnvelopePlanPublication.publish(
    artifact: ChapterArtifactEngine,
    manifest: ChapterArtifactManifest,
    plan: EnvelopePlan,
    nowEpochMs: Long,
): ChapterArtifactEngine.TransactionOutcome = runBlocking {
    EnvelopePlanPublication.publish(
        store = ChapterTranslationStore(
            artifactParentResolver = null,
            artifactStore = artifact,
        ),
        manifest = manifest,
        plan = plan,
        nowEpochMs = nowEpochMs,
    )
}

internal fun EnvelopePlanPublication.readValidatedPlan(
    artifact: ChapterArtifactEngine,
    manifest: ChapterArtifactManifest,
): EnvelopePlanPublication.EnvelopePlanRead = runBlocking {
    EnvelopePlanPublication.readValidatedPlan(
        store = ChapterTranslationStore(
            artifactParentResolver = null,
            artifactStore = artifact,
        ),
        manifest = manifest,
    )
}
