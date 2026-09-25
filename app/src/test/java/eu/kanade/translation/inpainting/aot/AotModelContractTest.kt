package eu.kanade.translation.inpainting.aot

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class AotModelContractTest {

    @Test
    fun `fixed contract requires exact 512 NCHW tensors`() {
        assertDoesNotThrow {
            AotModelContract.validate(AotModelContract.Kind.FIXED_512, fixedContract())
        }
        assertThrows<IllegalArgumentException> {
            AotModelContract.validate(
                AotModelContract.Kind.FIXED_512,
                fixedContract(imageShape = longArrayOf(1, 3, -1, -1)),
            )
        }
        assertThrows<IllegalArgumentException> {
            AotModelContract.validate(
                AotModelContract.Kind.FIXED_512,
                fixedContract(maskShape = longArrayOf(1, 1, 512, 511)),
            )
        }
    }

    @Test
    fun `fixed contract accepts Qualcomm AI Hub tensor names`() {
        assertDoesNotThrow {
            AotModelContract.validate(
                AotModelContract.Kind.FIXED_512,
                fixedContract(inputNames = setOf("input_image", "input_mask")),
            )
        }
    }

    @Test
    fun `dynamic contract rejects fixed spatial tensors`() {
        assertDoesNotThrow {
            AotModelContract.validate(AotModelContract.Kind.DYNAMIC, dynamicContract())
        }
        assertThrows<IllegalArgumentException> {
            AotModelContract.validate(AotModelContract.Kind.DYNAMIC, fixedContract())
        }
    }

    @Test
    fun `contracts require exact input names channels and one output`() {
        assertThrows<IllegalArgumentException> {
            AotModelContract.validate(
                AotModelContract.Kind.FIXED_512,
                fixedContract(inputNames = setOf("image", "mask", "extra")),
            )
        }
        assertThrows<IllegalArgumentException> {
            AotModelContract.validate(
                AotModelContract.Kind.FIXED_512,
                fixedContract(imageShape = longArrayOf(1, 1, 512, 512)),
            )
        }
        assertThrows<IllegalArgumentException> {
            AotModelContract.validate(
                AotModelContract.Kind.FIXED_512,
                fixedContract(outputCount = 2),
            )
        }
    }

    private fun fixedContract(
        inputNames: Set<String> = setOf("image", "mask"),
        outputCount: Int = 1,
        imageShape: LongArray = longArrayOf(1, 3, 512, 512),
        maskShape: LongArray = longArrayOf(1, 1, 512, 512),
        outputShape: LongArray = longArrayOf(1, 3, 512, 512),
    ) = AotModelContract.Contract(inputNames, outputCount, imageShape, maskShape, outputShape)

    private fun dynamicContract() = AotModelContract.Contract(
        inputNames = setOf("image", "mask"),
        outputCount = 1,
        imageShape = longArrayOf(-1, 3, -1, -1),
        maskShape = longArrayOf(-1, 1, -1, -1),
        outputShape = longArrayOf(-1, 3, -1, -1),
    )
}
