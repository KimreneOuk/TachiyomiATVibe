package eu.kanade.translation.engines.translator

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.TranslationEngineCategory

/**
 * Guards [TranslatorComputeClass] — the REMOTE_IO vs LOCAL_COMPUTE
 * classification driving batch lane routing (Checkpoint 2 §2).
 */
class TranslatorComputeClassTest {

    @Test
    fun `MLKit is LOCAL_COMPUTE, all others REMOTE_IO`() {
        TranslatorComputeClass.forConfiguration(
            TranslationEngineCategory.STANDARD,
            StandardTranslatorKind.MLKIT.name,
            null,
        ) shouldBe TranslatorComputeClass.LOCAL_COMPUTE

        TranslatorComputeClass.forConfiguration(
            TranslationEngineCategory.STANDARD,
            StandardTranslatorKind.GOOGLE.name,
            null,
        ) shouldBe TranslatorComputeClass.REMOTE_IO

        TranslatorComputeClass.forConfiguration(
            TranslationEngineCategory.STANDARD,
            StandardTranslatorKind.DEEPL.name,
            null,
        ) shouldBe TranslatorComputeClass.REMOTE_IO
    }

    @Test
    fun `every AI engine is REMOTE_IO`() {
        AiTranslatorKind.entries.forEach { kind ->
            TranslatorComputeClass.forConfiguration(
                TranslationEngineCategory.AI_MODEL,
                null,
                kind.engine.name,
            ) shouldBe TranslatorComputeClass.REMOTE_IO
        }
    }

    @Test
    fun `only REMOTE_IO may overlap native`() {
        TranslatorComputeClass.REMOTE_IO.mayOverlapNative shouldBe true
        TranslatorComputeClass.LOCAL_COMPUTE.mayOverlapNative shouldBe false
    }
}
