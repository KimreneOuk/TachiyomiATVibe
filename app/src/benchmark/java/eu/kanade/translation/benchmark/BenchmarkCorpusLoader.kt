package eu.kanade.translation.benchmark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.floor

class BenchmarkCorpusLoader(private val context: Context) {

    data class Discovery(
        val pages: List<BenchmarkPage>,
        val externalStatus: String,
    )

    fun discover(config: PaddleBenchmarkConfig): Discovery {
        val fixturePages = if (config.includeFixtures) loadFixturePages() else emptyList()
        if (!config.includeExternalCorpus) {
            return Discovery(fixturePages, "disabled")
        }
        val root = config.corpusRoot?.let(::File)
        if (root == null) return Discovery(fixturePages, "not_configured")
        if (!root.isDirectory) return Discovery(fixturePages, "missing:${root.absolutePath}")

        val corpusRoot = root.resolveNestedCorpusDirectory()
        val externalPages = corpusRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .mapNotNull { pageDirectory -> loadExternalPage(pageDirectory) }
        return Discovery(
            pages = fixturePages + externalPages,
            externalStatus = if (externalPages.isEmpty()) "empty:${corpusRoot.absolutePath}" else "loaded:${corpusRoot.absolutePath}",
        )
    }

    fun forEachSample(page: BenchmarkPage, block: (BenchmarkSample) -> Unit) {
        val fixture = page.fixture
        if (fixture != null) {
            val bitmap = createFixture(fixture)
            try {
                block(BenchmarkSample(fixture.id, page.id, bitmap))
            } finally {
                bitmap.recycleIfNeeded()
            }
            return
        }

        val decoded = page.openBitmap() ?: return
        try {
            val boxes = page.cropBoxes.ifEmpty {
                listOf(CropBox(0, 0, decoded.width, decoded.height))
            }
            boxes.forEachIndexed { index, box ->
                val safeBox = box.clampTo(decoded.width, decoded.height)
                if (safeBox.right <= safeBox.left || safeBox.bottom <= safeBox.top) return@forEachIndexed
                val crop = Bitmap.createBitmap(
                    decoded,
                    safeBox.left,
                    safeBox.top,
                    safeBox.right - safeBox.left,
                    safeBox.bottom - safeBox.top,
                )
                try {
                    block(BenchmarkSample("${page.id}_crop_${index + 1}", page.id, crop))
                } finally {
                    crop.recycleIfNeeded()
                }
            }
        } finally {
            decoded.recycleIfNeeded()
        }
    }

    private fun loadFixturePages(): List<BenchmarkPage> {
        val root = JSONObject(
            context.assets.open(FIXTURE_ASSET).bufferedReader().use { it.readText() },
        )
        val fixtures = root.optJSONArray("fixtures") ?: JSONArray()
        return buildList(fixtures.length()) {
            for (index in 0 until fixtures.length()) {
                val fixture = fixtures.getJSONObject(index)
                add(
                    BenchmarkPage(
                        id = fixture.getString("id"),
                        fixture = FixtureSpec(
                            id = fixture.getString("id"),
                            width = fixture.getInt("width"),
                            height = fixture.getInt("height"),
                            background = android.graphics.Color.parseColor(fixture.getString("background")),
                            text = fixture.optString("text"),
                        ),
                        source = "fixture",
                    ),
                )
            }
        }
    }

    private fun loadExternalPage(directory: File): BenchmarkPage? {
        val image = IMAGE_NAMES.asSequence()
            .map { File(directory, it) }
            .firstOrNull { it.isFile }
            ?: directory.listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
                .sortedBy { it.name }
                .firstOrNull()
            ?: return null

        val boxesFile = File(directory, "boxes.json")
        val boxes = if (boxesFile.isFile) parseSourceBoxes(boxesFile) else emptyList()
        return BenchmarkPage(
            id = directory.name,
            imageFile = image,
            cropBoxes = boxes,
            source = "external",
        )
    }

    private fun BenchmarkPage.openBitmap(): Bitmap? {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return imageStreamFactory?.invoke()?.use { input: InputStream ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: imageFile?.let { BitmapFactory.decodeFile(it.absolutePath, options) }
    }

    private fun File.resolveNestedCorpusDirectory(): File {
        val nested = resolve("real_corpus")
        val hasPageDirectory = nested.isDirectory &&
            nested.listFiles()
                .orEmpty()
                .any { it.isDirectory && IMAGE_NAMES.any { name -> File(it, name).isFile } }
        return if (hasPageDirectory) nested else this
    }

    private fun parseSourceBoxes(file: File): List<CropBox> {
        return runCatching {
            val json = JSONObject(file.readText())
            val values = json.optJSONArray("source_boxes") ?: return@runCatching emptyList()
            buildList(values.length()) {
                for (index in 0 until values.length()) {
                    val box = values.optJSONArray(index) ?: continue
                    if (box.length() < 4) continue
                    add(
                        CropBox(
                            left = floor(box.getDouble(0)).toInt(),
                            top = floor(box.getDouble(1)).toInt(),
                            right = ceil(box.getDouble(2)).toInt(),
                            bottom = ceil(box.getDouble(3)).toInt(),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun createFixture(spec: FixtureSpec): Bitmap {
        val bitmap = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(spec.background)
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = (spec.height * 0.45f).coerceAtLeast(12f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText(spec.text, spec.width * 0.02f, spec.height * 0.62f, this)
        }
        return bitmap
    }

    private fun CropBox.clampTo(width: Int, height: Int): CropBox = CropBox(
        left = left.coerceIn(0, width),
        top = top.coerceIn(0, height),
        right = right.coerceIn(0, width),
        bottom = bottom.coerceIn(0, height),
    )

    private fun Bitmap.recycleIfNeeded() {
        if (!isRecycled) recycle()
    }

    private companion object {
        const val FIXTURE_ASSET = "benchmark/fixtures.json"
        val IMAGE_NAMES = listOf("page.jpg", "page.jpeg", "page.png", "page.webp")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}
