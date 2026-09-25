package eu.kanade.translation.artifact

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class ModelIdentityCacheTest {

    @TempDir
    lateinit var tempDir: File

    private fun model(role: String = "detector", content: String = "model-bytes-$role"): File =
        File(tempDir, "$role.onnx").apply { writeText(content) }

    @Test
    fun `computes sha256 identity for an installed asset`() {
        val file = model()
        val cache = ModelIdentityCache(File(tempDir, "identities.json"))
        val identity = cache.identityFor(ModelAsset("detector", "v1", file)).shouldNotBeNull()
        identity.role shouldBe "detector"
        identity.versionMarker shouldBe "v1"
        identity.sha256 shouldBe eu.kanade.translation.util.ModelDeployment.hashOfFile(file)
        identity.lengthBytes shouldBe file.length()
    }

    @Test
    fun `repeated reads reuse the cached identity`() {
        val file = model()
        val cache = ModelIdentityCache(File(tempDir, "identities.json"))
        val first = cache.identityFor(ModelAsset("detector", "v1", file))
        val second = cache.identityFor(ModelAsset("detector", "v1", file))
        first shouldBe second
        cache.cachedRoleCount() shouldBe 1
    }

    @Test
    fun `byte drift invalidates the cached identity`() {
        val file = model()
        val cache = ModelIdentityCache(File(tempDir, "identities.json"))
        val first = cache.identityFor(ModelAsset("detector", "v1", file)).shouldNotBeNull()
        file.writeText("model-bytes-changed")
        val second = cache.identityFor(ModelAsset("detector", "v1", file)).shouldNotBeNull()
        second.sha256 shouldNotBe first.sha256
        second.sha256 shouldBe eu.kanade.translation.util.ModelDeployment.hashOfFile(file)
    }

    @Test
    fun `identities survive a cache reopening without the asset changing`() {
        val file = model()
        val cacheFile = File(tempDir, "identities.json")
        val first = ModelIdentityCache(cacheFile).identityFor(ModelAsset("detector", "v1", file)).shouldNotBeNull()
        val reopened = ModelIdentityCache(cacheFile).identityFor(ModelAsset("detector", "v1", file))
        reopened shouldBe first
    }

    @Test
    fun `missing asset yields no identity`() {
        val cache = ModelIdentityCache(File(tempDir, "identities.json"))
        cache.identityFor(ModelAsset("detector", "v1", File(tempDir, "absent.onnx"))).shouldBeNull()
    }

    @Test
    fun `in-memory mode works without a persistence file`() {
        val file = model()
        val cache = ModelIdentityCache(null)
        val identity = cache.identityFor(ModelAsset("detector", "v1", file)).shouldNotBeNull()
        cache.flush()
        identity.sha256 shouldNotBe ""
    }

    @Test
    fun `temp persistence file never remains after flush`() {
        val file = model()
        val cacheFile = File(tempDir, "identities.json")
        val cache = ModelIdentityCache(cacheFile)
        cache.identityFor(ModelAsset("detector", "v1", file))
        cache.flush()
        Files.exists(File(tempDir, "identities.json.tmp").toPath()) shouldBe false
    }
}
