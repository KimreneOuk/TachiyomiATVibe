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
    fun `experimental LaMa FP16 preference value is recognized case insensitively`() {
        NeuralInpaintModel.fromPrefOrNull("LAMA_MANGA_FP16") shouldBe NeuralInpaintModel.LAMA_MANGA_FP16
        NeuralInpaintModel.fromPrefOrNull("lama_manga_fp16") shouldBe NeuralInpaintModel.LAMA_MANGA_FP16
    }

    @Test
    fun `LaMa 512 variants are recognized case insensitively`() {
        NeuralInpaintModel.fromPrefOrNull("LAMA_512_INT8") shouldBe NeuralInpaintModel.LAMA_512_INT8
        NeuralInpaintModel.fromPrefOrNull("lama_512_fp16") shouldBe NeuralInpaintModel.LAMA_512_FP16
    }

    @Test
    fun `neural model preference defaults to LaMa Manga`() {
        val preferences = TranslationPreferences(InMemoryPreferenceStore())

        preferences.translationInpaintingNeuralModel().get() shouldBe NeuralInpaintModel.LAMA_MANGA
    }

    @Test
    fun `experimental LaMa FP16 model persists through the preference`() {
        val preferences = TranslationPreferences(InMemoryPreferenceStore())
        val modelPreference = preferences.translationInpaintingNeuralModel()

        modelPreference.set(NeuralInpaintModel.LAMA_MANGA_FP16)

        modelPreference.get() shouldBe NeuralInpaintModel.LAMA_MANGA_FP16
    }

    @Test
    fun `LaMa 512 FP16 model persists through the preference`() {
        val preferences = TranslationPreferences(InMemoryPreferenceStore())
        val modelPreference = preferences.translationInpaintingNeuralModel()

        modelPreference.set(NeuralInpaintModel.LAMA_512_FP16)

        modelPreference.get() shouldBe NeuralInpaintModel.LAMA_512_FP16
    }
}
