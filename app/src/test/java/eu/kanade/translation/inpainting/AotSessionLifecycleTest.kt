package eu.kanade.translation.inpainting

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AotSessionLifecycleTest {

    @Test
    fun `both distinct sessions close independently`() {
        val closed = mutableListOf<String>()

        AotSessionLifecycle.closeIndependently(
            fixed = AutoCloseable { closed += "fixed" },
            dynamic = AutoCloseable { closed += "dynamic" },
        )

        closed shouldContainExactly listOf("fixed", "dynamic")
    }

    @Test
    fun `dynamic session still closes when fixed close fails`() {
        val closed = mutableListOf<String>()
        val failures = mutableListOf<AotSessionLifecycle.CloseFailure>()
        val fixedError = IllegalStateException("fixed close failed")

        AotSessionLifecycle.closeIndependently(
            fixed = AutoCloseable {
                closed += "fixed"
                throw fixedError
            },
            dynamic = AutoCloseable { closed += "dynamic" },
            onFailure = failures::add,
        )

        closed shouldContainExactly listOf("fixed", "dynamic")
        failures.size shouldBe 1
        failures.single().route shouldBe "fixed"
        failures.single().error shouldBe fixedError
    }

    @Test
    fun `NNAPI closes independently first and siblings survive its failure`() {
        val closed = mutableListOf<String>()
        val failures = mutableListOf<AotSessionLifecycle.CloseFailure>()
        AotSessionLifecycle.closeIndependently(
            fixed = AutoCloseable { closed += "fixed" },
            dynamic = AutoCloseable { closed += "dynamic" },
            nnapi = AutoCloseable {
                closed += "nnapi"
                error("close")
            },
            onFailure = failures::add,
        )
        closed shouldContainExactly listOf("nnapi", "fixed", "dynamic")
        failures.map { it.route } shouldContainExactly listOf("nnapi")
    }

    @Test
    fun `QNN closes independently first and siblings survive its failure`() {
        val closed = mutableListOf<String>()
        val failures = mutableListOf<AotSessionLifecycle.CloseFailure>()
        AotSessionLifecycle.closeIndependently(
            fixed = AutoCloseable { closed += "fixed" },
            dynamic = AutoCloseable { closed += "dynamic" },
            nnapi = AutoCloseable { closed += "nnapi" },
            qnn = AutoCloseable {
                closed += "qnn"
                error("qnn close")
            },
            onFailure = failures::add,
        )
        closed shouldContainExactly listOf("qnn", "nnapi", "fixed", "dynamic")
        failures.map { it.route } shouldContainExactly listOf("qnn")
    }

    @Test
    fun `aliased NNAPI handle closes only once`() {
        var closes = 0
        val aliased = AutoCloseable { closes++ }
        AotSessionLifecycle.closeIndependently(aliased, aliased, aliased, aliased)
        closes shouldBe 1
    }

    @Test
    fun `fixed session still closes when dynamic close fails`() {
        val closed = mutableListOf<String>()
        val failures = mutableListOf<AotSessionLifecycle.CloseFailure>()

        AotSessionLifecycle.closeIndependently(
            fixed = AutoCloseable { closed += "fixed" },
            dynamic = AutoCloseable {
                closed += "dynamic"
                throw IllegalStateException("dynamic close failed")
            },
            onFailure = failures::add,
        )

        closed shouldContainExactly listOf("fixed", "dynamic")
        failures.map { it.route } shouldContainExactly listOf("dynamic")
    }
}
