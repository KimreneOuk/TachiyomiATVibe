package eu.kanade.translation.engines.runtime.onnx

import android.content.Context
import android.content.res.AssetManager
import eu.kanade.translation.util.Sha256
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

class OnnxModelStoreTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `LaMa Manga model file resolves to installed model name`() {
        val context = mockk<Context>()
        every { context.noBackupFilesDir } returns File(tempDir, "no-backup")

        OnnxModelStore(context).getLamaMangaModelFile().name shouldBe "lama-manga.onnx"
    }

    @Test
    fun `validly structured cached model with wrong bytes is recopied`() {
        val noBackupDir = File(tempDir, "no-backup").apply { mkdirs() }
        val assetBytes = validOnnxBytes()
        val context = mockk<Context>()
        val assets = mockk<AssetManager>()
        every { context.noBackupFilesDir } returns noBackupDir
        every { context.assets } returns assets
        every { assets.open(ASSET_PATH) } answers { ByteArrayInputStream(assetBytes) }

        val modelStore = OnnxModelStore(context)

        val modelFile = modelStore.ensurePaddleOcrV6Det().detectionModel
        modelFile.readBytes().contentEquals(assetBytes) shouldBe true

        // Preserve the size and ModelProto header so the existing structural
        // check accepts this cache. Only the content hash can identify drift.
        val corruptedBytes = assetBytes.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }
        corruptedBytes.size shouldBe assetBytes.size
        corruptedBytes[0] shouldBe 0x08.toByte()
        (corruptedBytes[1].toInt() and 0x80) shouldBe 0
        modelFile.writeBytes(corruptedBytes)

        val resolvedFile = modelStore.ensurePaddleOcrV6Det().detectionModel

        resolvedFile.readBytes().contentEquals(assetBytes) shouldBe true
    }

    @Test
    fun `already verified warm cache is not hashed again`() {
        val noBackupDir = File(tempDir, "no-backup").apply { mkdirs() }
        val assetBytes = validOnnxBytes()
        val context = mockk<Context>()
        val assets = mockk<AssetManager>()
        every { context.noBackupFilesDir } returns noBackupDir
        every { context.assets } returns assets
        every { assets.open(ASSET_PATH) } answers { ByteArrayInputStream(assetBytes) }

        val modelStore = OnnxModelStore(context)
        val modelFile = modelStore.ensurePaddleOcrV6Det().detectionModel

        var fileHashCalls = 0
        mockkObject(Sha256)
        try {
            every { Sha256.digest(any<File>()) } answers {
                fileHashCalls++
                callOriginal()
            }

            modelStore.ensurePaddleOcrV6Det()
            fileHashCalls shouldBe 1

            modelStore.ensurePaddleOcrV6Det()
            fileHashCalls shouldBe 1

            // A changed file size invalidates the memoized verification key.
            modelFile.appendBytes(byteArrayOf(0x01))
            modelStore.ensurePaddleOcrV6Det()
            fileHashCalls shouldBe 2
            modelFile.readBytes().contentEquals(assetBytes) shouldBe true
        } finally {
            unmockkObject(Sha256)
        }
    }

    private fun validOnnxBytes(): ByteArray = ByteArray(70 * 1024) { index ->
        index.toByte()
    }.also { bytes ->
        // ModelProto field 1 (ir_version) and a one-byte varint version.
        bytes[0] = 0x08
        bytes[1] = 0x08
    }

    private companion object {
        const val ASSET_PATH = "models/ocr/paddle-v6-small/det/inference.onnx"
    }
}
