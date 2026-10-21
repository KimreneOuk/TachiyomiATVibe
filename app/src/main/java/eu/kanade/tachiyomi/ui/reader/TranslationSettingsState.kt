package eu.kanade.tachiyomi.ui.reader

import androidx.compose.runtime.Immutable
import eu.kanade.presentation.more.settings.widget.AiModelListState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory

@Immutable
data class TranslationSettingsState(
    val enabled: Boolean = false,
    val autoTranslate: Boolean = false,
    val translateFromLanguage: String = "",
    val translateButtonWithControls: Boolean = false,
    val translateToLanguage: String = "",
    val translationRecentLanguagesFrom: ImmutableList<String> = persistentListOf(),
    val translationRecentLanguagesTo: ImmutableList<String> = persistentListOf(),
    val ocrModel: OcrModel = OcrModel.MLKIT,
    val ocrModelEntries: ImmutableMap<OcrModel, String> = persistentMapOf(),
    val inpaintingMode: String = "",
    val inpaintingNeuralModel: NeuralInpaintModel = NeuralInpaintModel.DEFAULT,
    val engineCategory: TranslationEngineCategory = TranslationEngineCategory.STANDARD,
    val standardEngine: StandardEngine = StandardEngine.GOOGLE,
    val deeplApiKey: String = "",
    val aiEngine: AiEngine = AiEngine.GEMINI,
    val aiApiKey: String = "",
    val aiBaseUrl: String = "",
    val aiModel: String = "",
    val aiRecentModels: ImmutableList<String> = persistentListOf(),
    val aiModelFetchState: AiModelListState = AiModelListState.Idle,
)
