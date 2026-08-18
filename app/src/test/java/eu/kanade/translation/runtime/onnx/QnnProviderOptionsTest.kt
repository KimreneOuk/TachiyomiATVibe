package eu.kanade.translation.runtime.onnx

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class QnnProviderOptionsTest {

    @Test
    fun `provider options carry soc and arch when both are known`() {
        val opts = OnnxRuntimeProvider.buildQnnProviderOptions(socModel = "57", htpArch = "75")
        opts["backend_type"] shouldBe "htp"
        opts["htp_performance_mode"] shouldBe "burst"
        opts["soc_model"] shouldBe "57"
        opts["htp_arch"] shouldBe "75"
    }

    @Test
    fun `provider options omit unknown soc and arch instead of guessing`() {
        val opts = OnnxRuntimeProvider.buildQnnProviderOptions(socModel = null, htpArch = null)
        opts.containsKey("soc_model") shouldBe false
        opts.containsKey("htp_arch") shouldBe false
        opts["backend_type"] shouldBe "htp"
    }

    @Test
    fun `provider options never contain the invalid context-cache pseudo options`() {
        // ORT 1.27 has no qnn_context_cache_enable/qnn_context_cache_path provider
        // options; silently ignored, they once masked that caching never worked.
        // Real caching uses the ep.context_enable/ep.context_file_path session
        // config entries applied in createQnnHtpSessionOptions.
        val opts = OnnxRuntimeProvider.buildQnnProviderOptions(socModel = "57", htpArch = "75")
        opts.containsKey("qnn_context_cache_enable") shouldBe false
        opts.containsKey("qnn_context_cache_path") shouldBe false
    }
}
