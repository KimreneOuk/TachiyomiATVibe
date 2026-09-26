package eu.kanade.translation.engines.translator.providers

/**
 * DeepL v2 `translate` endpoint contract.
 *
 * TachiyomiAT: implemented with OkHttp rather than Retrofit to match the other
 * translators in this package ([GoogleTranslator], [DeepSeekTranslator], etc.),
 * which the project builds without a Retrofit dependency.
 *
 * DeepL exposes two hostnames keyed off the account type, and the account type
 * is encoded in the API key itself: Free-account keys end in `:fx` and must use
 * `api-free.deepl.com`, Pro keys use `api.deepl.com`. [translateUrl] picks the
 * host from the key suffix so the user pastes a single key with no separate
 * Free/Pro toggle (DeepL's documented convention).
 */
internal object DeepLApi {
    private const val PATH = "/v2/translate"
    private const val FREE_HOST = "https://api-free.deepl.com"
    private const val PRO_HOST = "https://api.deepl.com"

    /**
     * DeepL Free (Auth Key) account suffix. Case-insensitive check: DeepL
     * issues these keys lowercase, but tolerate uppercase pastes.
     */
    private const val FREE_KEY_SUFFIX = ":fx"

    fun isFreeKey(apiKey: String): Boolean = apiKey.endsWith(FREE_KEY_SUFFIX, ignoreCase = true)

    fun translateUrl(apiKey: String): String =
        (if (isFreeKey(apiKey)) FREE_HOST else PRO_HOST) + PATH

    fun authHeader(apiKey: String): String = "DeepL-Auth-Key $apiKey"
}
