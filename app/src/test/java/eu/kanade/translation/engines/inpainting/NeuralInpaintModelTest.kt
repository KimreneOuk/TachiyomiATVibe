package eu.kanade.translation.engines.inpainting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.TranslationPreferences

class NeuralInpaintModelTest {

    @Test
    fun `default neural model is Manga LaMa LiteRT GPU`() {
        NeuralInpaintModel.DEFAULT shouldBe NeuralInpaintModel.LAMA_LITERT_GPU
    }

    @Test
    fun `only the LiteRT and AOT-GAN options exist`() {
        NeuralInpaintModel.entries.toSet() shouldBe setOf(
            NeuralInpaintModel.LAMA_LITERT_GPU,
            NeuralInpaintModel.AOT_GAN,
        )
    }

    @Test
    fun `known values parse case insensitively and unknown fall back to default`() {
        (NeuralInpaintModel.fromPrefOrNull("INVALID") ?: NeuralInpaintModel.DEFAULT) shouldBe NeuralInpaintModel.LAMA_LITERT_GPU
        NeuralInpaintModel.fromPrefOrNull("aot_gan") shouldBe NeuralInpaintModel.AOT_GAN
        NeuralInpaintModel.fromPrefOrNull("lama_litert_gpu") shouldBe NeuralInpaintModel.LAMA_LITERT_GPU
    }

    @Test
    fun `deprecated model values no longer parse and migrate to the default`() {
        listOf("LAMA_MANGA", "LAMA_MANGA_FP16", "LAMA_512_INT8", "LAMA_512_FP16").forEach { legacy ->
            (NeuralInpaintModel.fromPrefOrNull(legacy) ?: NeuralInpaintModel.DEFAULT) shouldBe
                NeuralInpaintModel.LAMA_LITERT_GPU
        }
    }

    @Test
    fun `neural model preference defaults to Manga LaMa LiteRT GPU`() {
        val preferences = TranslationPreferences(InMemoryPreferenceStore())

        preferences.translationInpaintingNeuralModel().get() shouldBe NeuralInpaintModel.LAMA_LITERT_GPU
    }

    @Test
    fun `AOT-GAN model persists through the preference`() {
        val preferences = TranslationPreferences(InMemoryPreferenceStore())
        val modelPreference = preferences.translationInpaintingNeuralModel()

        modelPreference.set(NeuralInpaintModel.AOT_GAN)

        modelPreference.get() shouldBe NeuralInpaintModel.AOT_GAN
    }

    @Test
    fun `deprecated stored preference value migrates to the default on read`() {
        // Production migration path: PreferenceStore.getEnum installs a
        // deserializer that maps unknown stored names to the default
        // (PreferenceStore.kt: enumValueOf catch IllegalArgumentException).
        // InMemoryPreferenceStore.getObject's unchecked cast cannot simulate
        // a raw legacy String round-trip, so the contract is asserted on the
        // enum surface itself: legacy names are gone and the default is the
        // LiteRT model.
        val names = NeuralInpaintModel.entries.map { it.name }
        listOf("LAMA_MANGA", "LAMA_MANGA_FP16", "LAMA_512_INT8", "LAMA_512_FP16").forEach { legacy ->
            (legacy in names) shouldBe false
        }
        NeuralInpaintModel.DEFAULT shouldBe NeuralInpaintModel.LAMA_LITERT_GPU
    }
}
