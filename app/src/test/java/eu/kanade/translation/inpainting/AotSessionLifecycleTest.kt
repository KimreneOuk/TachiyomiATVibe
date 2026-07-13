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
