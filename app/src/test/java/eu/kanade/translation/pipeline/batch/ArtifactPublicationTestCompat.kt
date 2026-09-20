package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.artifact.AnalysisChunkResult
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.ChapterArtifactManifest
import eu.kanade.translation.artifact.ChapterTranslationProfile
import eu.kanade.translation.artifact.EnvelopePlan
import kotlinx.coroutines.runBlocking

/**
 * Test-only adapters for the pre-facade artifact fixtures.
 *
 * Production publication APIs intentionally accept [ChapterTranslationStore]
 * only; these extensions keep the artifact-focused contract tests concise
 * while they continue to exercise the same facade-owned implementation.
 */
internal fun AnalysisChunkPublication.publish(
    artifact: ChapterArtifactEngine,
    manifest: ChapterArtifactManifest,
    result: AnalysisChunkResult,
    nowEpochMs: Long,
): ChapterArtifactEngine.TransactionOutcome = runBlocking {
    AnalysisChunkPublication.publish(
        store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            artifactStore = artifact,
        ),
        manifest = manifest,
        result = result,
        nowEpochMs = nowEpochMs,
    )
}

internal fun EnvelopePlanPublication.publish(
    artifact: ChapterArtifactEngine,
    manifest: ChapterArtifactManifest,
    plan: EnvelopePlan,
    nowEpochMs: Long,
): ChapterArtifactEngine.TransactionOutcome = runBlocking {
    EnvelopePlanPublication.publish(
        store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
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
            translationFile = null,
            fileCreator = null,
            artifactStore = artifact,
        ),
        manifest = manifest,
    )
}

internal fun ProfileFreezePublication.publish(
    artifact: ChapterArtifactEngine,
    manifest: ChapterArtifactManifest,
    profile: ChapterTranslationProfile,
    nowEpochMs: Long,
): ChapterArtifactEngine.TransactionOutcome = runBlocking {
    ProfileFreezePublication.publish(
        store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            artifactStore = artifact,
        ),
        manifest = manifest,
        profile = profile,
        nowEpochMs = nowEpochMs,
    )
}

internal fun ProfileFreezePublication.readReusableFrozenProfile(
    artifact: ChapterArtifactEngine,
    manifest: ChapterArtifactManifest,
    expectedInputFingerprint: String,
): ProfileFreezePublication.FrozenProfileRead = runBlocking {
    ProfileFreezePublication.readReusableFrozenProfile(
        store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            artifactStore = artifact,
        ),
        manifest = manifest,
        expectedInputFingerprint = expectedInputFingerprint,
    )
}
