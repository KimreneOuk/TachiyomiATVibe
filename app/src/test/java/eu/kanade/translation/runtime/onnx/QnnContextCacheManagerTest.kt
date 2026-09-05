package eu.kanade.translation.runtime.onnx

import android.content.Context
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class QnnContextCacheManagerTest {

    @TempDir
    lateinit var tempFolder: File

    private lateinit var context: Context
    private lateinit var dummyModel: File

    @BeforeEach
    fun setup() {
        context = mockk()
        val noBackupDir = File(tempFolder, "no_backup").apply { mkdirs() }
        every { context.noBackupFilesDir } returns noBackupDir

        dummyModel = File(tempFolder, "test-model.onnx").apply {
            writeText("DUMMY_MODEL_CONTENT")
        }
    }

    @AfterEach
    fun teardown() {
        tempFolder.deleteRecursively()
    }

    @Test
    fun `computeCacheKey produces deterministic 32 char hex key`() {
        val options = mapOf("backend_type" to "htp")
        val key1 = QnnContextCacheManager.computeCacheKey(dummyModel, options)
        val key2 = QnnContextCacheManager.computeCacheKey(dummyModel, options)

        key1.length shouldBe 32
        key1 shouldBe key2
    }

    @Test
    fun `computeCacheKey changes when options or model size changes`() {
        val optHtp = mapOf("backend_type" to "htp")
        val optGpu = mapOf("backend_type" to "gpu")

        val keyHtp = QnnContextCacheManager.computeCacheKey(dummyModel, optHtp)
        val keyGpu = QnnContextCacheManager.computeCacheKey(dummyModel, optGpu)

        (keyHtp != keyGpu) shouldBe true
    }

    @Test
    fun `prepareStaging creates staging directory and returns wrapper file handle`() {
        val options = mapOf("backend_type" to "htp")
        val stagingFile = QnnContextCacheManager.prepareStaging(context, dummyModel, options)

        stagingFile.parentFile.shouldNotBeNull()
        stagingFile.parentFile!!.name shouldStartWith "staging_"
        stagingFile.parentFile!!.exists() shouldBe true
        stagingFile.name shouldBe "test-model.onnx.qnnctx.bin"
    }

    @Test
    fun `commitStaging rejects incomplete files without companion binary`() {
        val options = mapOf("backend_type" to "htp")
        val stagingFile = QnnContextCacheManager.prepareStaging(context, dummyModel, options)

        // Only write wrapper, omit companion binary
        stagingFile.writeBytes(ByteArray(100) { 1 })

        val commitOk = QnnContextCacheManager.commitStaging(context, dummyModel, options)
        commitOk shouldBe false
        stagingFile.parentFile!!.exists() shouldBe false // Cleaned up
    }

    @Test
    fun `commitStaging promotes paired wrapper and companion binary atomically`() {
        val options = mapOf("backend_type" to "htp")
        val stagingFile = QnnContextCacheManager.prepareStaging(context, dummyModel, options)

        // Write both wrapper and companion binary
        stagingFile.writeBytes(ByteArray(100) { 1 })
        val companionBin = File(stagingFile.parentFile, "test-model.onnx.qnnctx_qnn.bin")
        companionBin.writeBytes(ByteArray(200) { 2 })

        val commitOk = QnnContextCacheManager.commitStaging(context, dummyModel, options)
        commitOk shouldBe true

        val cachedModel = QnnContextCacheManager.getValidCachedModel(context, dummyModel, options)
        cachedModel.shouldNotBeNull()
        cachedModel!!.exists() shouldBe true
        cachedModel.name shouldBe "test-model.onnx.qnnctx.bin"
        cachedModel.length() shouldBe 100L

        // Invalidate removes cached entry
        QnnContextCacheManager.invalidate(context, dummyModel, options)
        val afterInvalidation = QnnContextCacheManager.getValidCachedModel(context, dummyModel, options)
        afterInvalidation.shouldBeNull()
    }
}
