// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.ocr

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * The sampled OCR decode: the factor the decoder is asked for, the format guard that decides how far
 * a decimating format may be pre-shrunk, and the directory-level guarantee that no OCR class still
 * materialises a page's full native frame.
 */
class OcrBitmapDecoderTest {

    // ------------------------------------------------------------------ sample arithmetic

    @Test
    fun `a 4500x6400 page samples at 3 when the format scales natively`() {
        OcrBitmapDecoder.sampleSizeFor(4500, 6400, OcrImageFormat.JPEG) shouldBe 3
        OcrBitmapDecoder.sampleSizeFor(4500, 6400, OcrImageFormat.WEBP) shouldBe 3
        OcrBitmapDecoder.sampleSizeFor(4500, 6400, OcrImageFormat.JP2) shouldBe 3
    }

    @Test
    fun `a 6000x9000 page samples at 4 when the format scales natively`() {
        OcrBitmapDecoder.sampleSizeFor(6000, 9000, OcrImageFormat.JPEG) shouldBe 4
        OcrBitmapDecoder.sampleSizeFor(6000, 9000, OcrImageFormat.WEBP) shouldBe 4
        OcrBitmapDecoder.sampleSizeFor(6000, 9000, OcrImageFormat.JP2) shouldBe 4
    }

    @Test
    fun `a page inside the 4 MP budget is not sampled`() {
        OcrBitmapDecoder.sampleSizeFor(1200, 1600, OcrImageFormat.JPEG) shouldBe 1
        OcrBitmapDecoder.sampleSizeFor(1200, 1600, OcrImageFormat.PNG) shouldBe 1
        // Exactly on the budget, so still untouched.
        OcrBitmapDecoder.sampleSizeFor(4000, 1000, OcrImageFormat.JPEG) shouldBe 1
        OcrBitmapDecoder.sampleSizeFor(4000, 1000, OcrImageFormat.PNG) shouldBe 1
    }

    @Test
    fun `a 12000x18000 page is capped by the format guard`() {
        // Native scaling: the plain budget arithmetic, ceil(sqrt(216 MP / 4 MP)) = 8.
        OcrBitmapDecoder.sampleSizeFor(12000, 18000, OcrImageFormat.JPEG) shouldBe 8
        // Decimating formats stop at the 8 MP pre-shrink: ceil(sqrt(216 MP / 8 MP)) = 6, not 8.
        OcrBitmapDecoder.sampleSizeFor(12000, 18000, OcrImageFormat.PNG) shouldBe 6
        OcrBitmapDecoder.sampleSizeFor(12000, 18000, OcrImageFormat.AVIF) shouldBe 6
        OcrBitmapDecoder.sampleSizeFor(12000, 18000, OcrImageFormat.JXL) shouldBe 6
        OcrBitmapDecoder.sampleSizeFor(12000, 18000, OcrImageFormat.HEIF) shouldBe 6
        OcrBitmapDecoder.sampleSizeFor(12000, 18000, OcrImageFormat.TIFF) shouldBe 6
    }

    @Test
    fun `a decimating format at the 8 MP cap is not sampled`() {
        // 4000x2000 is exactly 8 MP: the guard allows sample 1, the 4 MP budget alone would say 2.
        OcrBitmapDecoder.sampleSizeFor(4000, 2000, OcrImageFormat.PNG) shouldBe 1
        OcrBitmapDecoder.sampleSizeFor(4000, 2000, OcrImageFormat.JPEG) shouldBe 2
    }

    @Test
    fun `an inconclusive format is treated as one that does not scale natively`() {
        OcrImageFormat.UNKNOWN.scalesNatively shouldBe false
        OcrBitmapDecoder.sampleSizeFor(12000, 18000, OcrImageFormat.UNKNOWN) shouldBe 6
    }

    // ------------------------------------------------------------------ webtoon strips

    @Test
    fun `a tall webtoon strip keeps native resolution whatever the format`() {
        // The strip path tiles at native resolution, so a 4 MP pre-shrink would halve the text
        // before the tiles are cut. Budget arithmetic alone would sample 800x20000 at 2 and
        // 2000x30000 at 4, which is exactly the regression this guards.
        OcrBitmapDecoder.sampleSizeFor(800, 20000, OcrImageFormat.JPEG) shouldBe 1
        OcrBitmapDecoder.sampleSizeFor(800, 20000, OcrImageFormat.PNG) shouldBe 1
        OcrBitmapDecoder.sampleSizeFor(800, 20000, OcrImageFormat.UNKNOWN) shouldBe 1
        OcrBitmapDecoder.sampleSizeFor(2000, 30000, OcrImageFormat.JPEG) shouldBe 1
        OcrBitmapDecoder.sampleSizeFor(2000, 30000, OcrImageFormat.PNG) shouldBe 1
    }

    @Test
    fun `the strip rule is strictly greater than the ratio`() {
        // Both pages are over the 4 MP budget, so only the ratio decides between them: 4.44 MP just
        // over 3.0 is a strip, 4.32 MP at exactly 3.0 is an ordinary page and samples at 2.
        OcrBitmapDecoder.sampleSizeFor(1200, 3700, OcrImageFormat.JPEG) shouldBe 1
        OcrBitmapDecoder.sampleSizeFor(1200, 3600, OcrImageFormat.JPEG) shouldBe 2
        // The brief's example at exactly 3.0: 48 MP samples at 4 on the budget path.
        OcrBitmapDecoder.sampleSizeFor(4000, 12000, OcrImageFormat.JPEG) shouldBe 4
        // 4000/3 = 1334 px wide and 12000/3 = 4000 px tall, i.e. 5.3 MP, inside the 8 MP guard.
        OcrBitmapDecoder.sampleSizeFor(4000, 12000, OcrImageFormat.PNG) shouldBe 3
    }

    @Test
    fun `an ordinary page still takes the budget path`() {
        // Portrait pages below the strip ratio are unchanged by the rule.
        OcrBitmapDecoder.sampleSizeFor(6000, 9000, OcrImageFormat.JPEG) shouldBe 4
        OcrBitmapDecoder.sampleSizeFor(4500, 6400, OcrImageFormat.JPEG) shouldBe 3
    }

    // KUN -->
    @Test
    fun `an exif-rotated strip is measured by the decoder header, not the stored bounds`() {
        // A landscape 12000x2000 strip tagged "rotate 90": the decoder applies the orientation, so the
        // page decodes as a 2000x12000 strip. Measured on BitmapFactory's stored orientation it is an
        // ordinary 24 MP page and samples at 3, which halves the strip's text; measured on the header
        // the tall-strip bypass fires and it stays at native resolution.
        val header = 2000 to 12000
        val stored = 12000 to 2000
        OcrBitmapDecoder.sampleSizeFor(header.first, header.second, OcrImageFormat.JPEG) shouldBe 1
        OcrBitmapDecoder.sampleSizeFor(stored.first, stored.second, OcrImageFormat.JPEG) shouldBe 3

        // The precedence the manager uses: the header wins, so a revert to bounds-first fails here.
        samplingDimensions(header, stored, fallback = 1000) shouldBe header
        // Bounds are the fallback for formats the decoder cannot open...
        samplingDimensions(null, stored, fallback = 1000) shouldBe stored
        // ...and only then the default, which the old code also used for unusable measurements.
        samplingDimensions(null, null, fallback = 1000) shouldBe (1000 to 1000)
        samplingDimensions(0 to 0, 0 to 0, fallback = 1000) shouldBe (1000 to 1000)
    }
    // KUN <--

    // ------------------------------------------------------------------ decoded size

    @Test
    fun `native-scaling formats decode within the 4 MP budget`() {
        val pages = listOf(
            4500 to 6400,
            6000 to 9000,
            12000 to 18000,
            16384 to 16384,
            3000 to 5000,
        )
        for (format in listOf(OcrImageFormat.JPEG, OcrImageFormat.WEBP, OcrImageFormat.JP2)) {
            for ((width, height) in pages) {
                val sample = OcrBitmapDecoder.sampleSizeFor(width, height, format)
                val (outWidth, outHeight) = OcrBitmapDecoder.sampledDimensions(width, height, sample)
                withClue(format, width, height, sample) {
                    (outWidth.toLong() * outHeight).shouldBeLessThanOrEqual(MAX_OCR_PIXELS.toLong())
                }
            }
        }
    }

    @Test
    fun `decimating formats decode within the 8 MP pre-shrink`() {
        val pages = listOf(
            4500 to 6400,
            6000 to 9000,
            12000 to 18000,
            16384 to 16384,
            3000 to 5000,
        )
        val formats = listOf(
            OcrImageFormat.PNG,
            OcrImageFormat.AVIF,
            OcrImageFormat.JXL,
            OcrImageFormat.HEIF,
            OcrImageFormat.TIFF,
            OcrImageFormat.UNKNOWN,
        )
        for (format in formats) {
            for ((width, height) in pages) {
                val sample = OcrBitmapDecoder.sampleSizeFor(width, height, format)
                val (outWidth, outHeight) = OcrBitmapDecoder.sampledDimensions(width, height, sample)
                withClue(format, width, height, sample) {
                    (outWidth.toLong() * outHeight)
                        .shouldBeLessThanOrEqual(OcrBitmapDecoder.NON_NATIVE_MAX_PIXELS.toLong())
                }
            }
        }
    }

    @Test
    fun `sampled dimensions replicate the decoder's ceil division`() {
        // decodeToBitmap allocates ceil(w / sample) x ceil(h / sample).
        OcrBitmapDecoder.sampledDimensions(4500, 6400, 3) shouldBe (1500 to 2134)
        OcrBitmapDecoder.sampledDimensions(6000, 9000, 4) shouldBe (1500 to 2250)
        OcrBitmapDecoder.sampledDimensions(12000, 18000, 6) shouldBe (2000 to 3000)
        OcrBitmapDecoder.sampledDimensions(1200, 1600, 1) shouldBe (1200 to 1600)
        // Never degenerates to zero for a page smaller than its sample factor.
        OcrBitmapDecoder.sampledDimensions(3, 5, 4) shouldBe (1 to 2)
    }

    // ------------------------------------------------------------------ format detection

    @Test
    fun `detects JPEG WebP and JPEG-2000 as natively scaled`() {
        OcrBitmapDecoder.formatOf(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10)) shouldBe OcrImageFormat.JPEG
        OcrBitmapDecoder.formatOf(riff()).shouldBeNativeAs(OcrImageFormat.WEBP)
        OcrBitmapDecoder.formatOf(jp2Signature()).shouldBeNativeAs(OcrImageFormat.JP2)
        OcrBitmapDecoder.formatOf(bytes(0xFF, 0x4F, 0xFF, 0x51, 0x00)).shouldBeNativeAs(OcrImageFormat.JP2)
    }

    @Test
    fun `detects PNG as a decimating format`() {
        OcrBitmapDecoder.formatOf(pngHeader()).shouldBeDecimating(OcrImageFormat.PNG)
    }

    @Test
    fun `detects AVIF JXL and HEIF as decimating formats`() {
        OcrBitmapDecoder.formatOf(isoBmff("avif")).shouldBeDecimating(OcrImageFormat.AVIF)
        OcrBitmapDecoder.formatOf(isoBmff("avis")).shouldBeDecimating(OcrImageFormat.AVIF)
        OcrBitmapDecoder.formatOf(isoBmff("heic")).shouldBeDecimating(OcrImageFormat.HEIF)
        OcrBitmapDecoder.formatOf(isoBmff("mif1")).shouldBeDecimating(OcrImageFormat.HEIF)
        OcrBitmapDecoder.formatOf(jxlSignature()).shouldBeDecimating(OcrImageFormat.JXL)
        OcrBitmapDecoder.formatOf(bytes(0xFF, 0x0A, 0x00)).shouldBeDecimating(OcrImageFormat.JXL)
    }

    @Test
    fun `detects TIFF and GIF as decimating formats`() {
        OcrBitmapDecoder.formatOf(bytes(0x49, 0x49, 0x2A, 0x00, 0x08)).shouldBeDecimating(OcrImageFormat.TIFF)
        OcrBitmapDecoder.formatOf(bytes(0x4D, 0x4D, 0x00, 0x2A, 0x00)).shouldBeDecimating(OcrImageFormat.TIFF)
        OcrBitmapDecoder.formatOf(ascii("GIF89a") + bytes(0x01)).shouldBeDecimating(OcrImageFormat.GIF)
    }

    @Test
    fun `an unknown or truncated header is inconclusive`() {
        OcrBitmapDecoder.formatOf(bytes(0x00, 0x01, 0x02, 0x03)).shouldBeDecimating(OcrImageFormat.UNKNOWN)
        OcrBitmapDecoder.formatOf(bytes(0xFF)).shouldBeDecimating(OcrImageFormat.UNKNOWN)
        OcrBitmapDecoder.formatOf(ByteArray(0)).shouldBeDecimating(OcrImageFormat.UNKNOWN)
        // An ISO-BMFF box whose brand this does not recognise.
        OcrBitmapDecoder.formatOf(isoBmff("mp42")) shouldBe OcrImageFormat.UNKNOWN
        // RIFF carrying something other than WebP.
        OcrBitmapDecoder.formatOf(ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WAVE")).shouldBeDecimating(
            OcrImageFormat.UNKNOWN,
        )
    }

    // ------------------------------------------------------------------ call sites

    @Test
    fun `no ocr class decodes a page's full native frame`() {
        val classes = ocrClassBytes()
        // The scan must really see this module, or the assertion below is vacuous.
        classes.any { it.first.endsWith("mihon/core/ocr/OcrBitmapDecoder.class") } shouldBe true

        val markers = listOf("copyPixelsFromBuffer", "decodeNext")
        val offenders = classes.filter { (_, bytes) -> markers.any { bytes.containsAscii(it) } }.map { it.first }
        offenders.shouldBeEmpty()
    }

    // ------------------------------------------------------------------ helpers

    private fun withClue(format: OcrImageFormat, width: Int, height: Int, sample: Int, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            throw AssertionError("$format ${width}x$height sampled at $sample", t)
        }
    }

    private fun OcrImageFormat.shouldBeNativeAs(format: OcrImageFormat) {
        this shouldBe format
        this.scalesNatively.shouldBeTrue()
    }

    private fun OcrImageFormat.shouldBeDecimating(format: OcrImageFormat) {
        this shouldBe format
        this.scalesNatively shouldBe false
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private fun pngHeader() = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + bytes(0x00, 0x00)

    private fun riff() = ascii("RIFF") + bytes(0x24, 0x00, 0x00, 0x00) + ascii("WEBPVP8 ")

    private fun jp2Signature() =
        bytes(0x00, 0x00, 0x00, 0x0C) + bytes(0x6A, 0x50, 0x20, 0x20) + bytes(0x0D, 0x0A, 0x87, 0x0A)

    private fun jxlSignature() =
        bytes(0x00, 0x00, 0x00, 0x0C) + bytes(0x4A, 0x58, 0x4C, 0x20) + bytes(0x0D, 0x0A, 0x87, 0x0A)

    private fun isoBmff(brand: String) =
        bytes(0x00, 0x00, 0x00, 0x1C) + ascii("ftyp") + ascii(brand) + bytes(0x00, 0x00, 0x00, 0x00)

    /** Every class the module's main source set compiled, so a full-frame decode cannot hide. */
    private fun ocrClassBytes(): List<Pair<String, ByteArray>> {
        val location = OcrBitmapDecoder::class.java.protectionDomain.codeSource?.location
            ?: error("Cannot locate the compiled :core:ocr classes")
        val root = File(location.toURI())
        val classes = mutableListOf<Pair<String, ByteArray>>()
        if (root.isDirectory) {
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .forEach { classes += it.relativeTo(root).path to it.readBytes() }
        } else {
            ZipFile(root).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.endsWith(".class") }
                    .forEach { entry ->
                        classes += entry.name to zip.getInputStream(entry).use { it.readBytes() }
                    }
            }
        }
        return classes
    }

    /** A class file's constant pool holds the name of every method it references. */
    private fun ByteArray.containsAscii(needle: String): Boolean {
        val target = needle.toByteArray(Charsets.US_ASCII)
        if (target.isEmpty() || size < target.size) return false
        outer@ for (i in 0..size - target.size) {
            for (j in target.indices) {
                if (this[i + j] != target[j]) continue@outer
            }
            return true
        }
        return false
    }
}
