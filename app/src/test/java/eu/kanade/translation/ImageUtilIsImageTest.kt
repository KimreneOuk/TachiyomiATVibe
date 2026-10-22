package eu.kanade.translation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.system.ImageUtil

class ImageUtilIsImageTest {

    @Test
    fun `isImage recognizes case-insensitive standard image extensions`() {
        assertTrue(ImageUtil.isImage("001.JPEG"))
        assertTrue(ImageUtil.isImage("002.jpeg"))
        assertTrue(ImageUtil.isImage("003.JPG"))
        assertTrue(ImageUtil.isImage("004.jpg"))
        assertTrue(ImageUtil.isImage("005.PNG"))
        assertTrue(ImageUtil.isImage("006.png"))
        assertTrue(ImageUtil.isImage("007.WEBP"))
        assertTrue(ImageUtil.isImage("008.webp"))
        assertTrue(ImageUtil.isImage("009.AVIF"))
        assertTrue(ImageUtil.isImage("010.avif"))

        assertFalse(ImageUtil.isImage("ComicInfo.xml"))
        assertFalse(ImageUtil.isImage("chapter.zip"))
        assertFalse(ImageUtil.isImage("info.txt"))
        assertFalse(ImageUtil.isImage(null))
    }
}
