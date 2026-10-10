package eu.kanade.translation.engines.inpainting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.TranslationPreferences

class NeuralInpaintModelTest {

    @Test
    fun `default neural model is LaMa Manga`() {
        NeuralInpaintModel.DEFAULT shouldBe NeuralInpaintModel.LAMA_MANGA
    }

    @Test
    fun `unknown model values fall back to default and known values parse case insensitively`() {
        (NeuralInpaintModel.fromPrefOrNull("INVALID") ?: NeuralInpaintModel.DEFAULT) shouldBe NeuralInpaintModel.LAMA_MANGA
        NeuralInpaintModel.fromPrefOrNull("aot_gan") shouldBe NeuralInpaintModel.AOT_GAN
    }

    @Test
    fun `neural model preference defaults to LaMa Manga`() {
        val preferences = TranslationPreferences(InMemoryPreferenceStore())

        preferences.translationInpaintingNeuralModel().get() shouldBe NeuralInpaintModel.LAMA_MANGA
    }
}
