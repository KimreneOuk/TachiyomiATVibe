package eu.kanade.translation.artifact

import android.graphics.BitmapFactory
import java.io.InputStream

/** Result of a bounded decode probe over cleaned-image bytes. */
data class ProbedImage(
    val width: Int,
    val height: Int,
)

/**
 * Bounded cleaned-image validation probe. Implementations must not decode
 * full bitmaps: they answer only whether the bytes are a decodable image and
 * what dimensions it has.
 */
fun interface CleanedImageProbe {
    /** Returns decoded dimensions, or null when the stream is not a decodable image. */
    fun probe(input: InputStream): ProbedImage?
}

/**
 * Production probe over Android [BitmapFactory] using `inJustDecodeBounds`,
 * so validation reads only image metadata — no bitmap allocation, no full
 * decode — keeping migration inside the bounded-memory contract.
 */
object BitmapFactoryCleanedImageProbe : CleanedImageProbe {
    override fun probe(input: InputStream): ProbedImage? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(input, null, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) return null
        return ProbedImage(width, height)
    }
}
