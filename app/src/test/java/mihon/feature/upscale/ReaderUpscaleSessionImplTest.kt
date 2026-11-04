// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The reader session over the upscaling service: what `open()` may serve before a page is decoded,
// which model `request()` routes a decoded page to, what `focus()`/`reset()`/`close()` do to the
// queue, and what the session refuses to ask for a second time.
//
// The engine and the result cache are the real ones (a JVM cache directory, the real tile pipeline
// with a fake inference), so the cache keys, the routing rule and the written entries are exercised
// end to end — only the model weights and the image decoder are fake. The fake decoder takes the
// page's index from the page's own bytes, so "which pages were decoded" is what the assertions read.
// KUN <--
package mihon.feature.upscale

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import mihon.core.upscale.PageEncoder
import mihon.core.upscale.PageKey
import mihon.core.upscale.UpscaleService
import mihon.core.upscale.cache.UpscaleCache
import mihon.core.upscale.engine.ArgbSurfaces
import mihon.core.upscale.engine.DecodedPage
import mihon.core.upscale.engine.TileInference
import mihon.core.upscale.engine.TileInput
import mihon.core.upscale.engine.UpscaleEngine
import mihon.core.upscale.engine.UpscaleResult
import mihon.core.upscale.model.InstalledModels
import mihon.core.upscale.model.UpscaleModel
import mihon.core.upscale.model.UpscaleModelCatalog
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.UpscaleMode
import tachiyomi.core.common.preference.UpscalePreferences
import tachiyomi.domain.chapter.model.Chapter
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReaderUpscaleSessionImplTest {

    private val root: File = Files.createTempDirectory("upscale-session").toFile()
    private val cache = UpscaleCache(File(root, "cache"), maxBytes = { Long.MAX_VALUE })
    private val modelFile = File(root, "fake.onnx").apply { writeText("stub weights") }
    private val preferences = UpscalePreferences(InMemoryPreferenceStore())

    private val installed = mutableSetOf<String>()

    /**
     * How often the session asked for the installed model list. `InstalledModels.installedIds()` is
     * the reader's most expensive call (an install-marker read, a manifest parse and a stat per
     * model), and it must not run per page.
     */
    private val installedIdReads = AtomicInteger()

    /** Model ids the engine ran, in order. */
    private val encoded = ConcurrentLinkedQueue<String>()

    /** Page indices the fake decoder decoded, in order. */
    private val decoded = ConcurrentLinkedQueue<Int>()

    /** Page indices the encoder wrote, in order — taken from the decode that produced them. */
    private val encodedPages = ConcurrentLinkedQueue<Int>()

    /** Page indices the fake decoder refuses (as a decode of unreadable bytes would). */
    private var undecodable: Set<Int> = emptySet()

    /** Runs inside the fake decoder before it returns: lets a test hold a page in the decode. */
    private var decoding: () -> Unit = {}

    /** Runs for every tile the fake model infers: lets a test hold the engine inside inference. */
    private var inferring: () -> Unit = {}

    private val installedModels = object : InstalledModels {
        override fun installedIds(): Set<String> {
            installedIdReads.incrementAndGet()
            return installed.toSet()
        }

        override fun fileFor(model: UpscaleModel): File? = if (model.id in installed) modelFile else null
    }

    /**
     * Catches a cache identity that ignores the source bytes: a re-downloaded page (same chapter,
     * same index, new last-modified) would be served an upscale of the *previous* image.
     */
    @Test
    fun `a page's cache identity includes the source bytes`() {
        preferences.enabled.set(true)
        preferences.mode.set(UpscaleMode.ILLUSTRATION_ONLY)
        preferences.illustrationModel.set(ILLUSTRATION_ID)
        installed += ILLUSTRATION_ID

        runBlocking {
            cache.put(keyOf(page(modified = 20L), ILLUSTRATION_ID)) { it.writeText("upscaled") }
        }
        val session = session()

        session.open(page(modified = 20L))!!.use { it.readBytes().decodeToString() } shouldBe "upscaled"
        session.open(page(modified = 21L)) shouldBe null
    }

    /**
     * Catches an `open()` that returns a cached stream the feature cannot actually produce: with no
     * model installed there is no entry the reader may serve, whatever is on disk.
     */
    @Test
    fun `open returns nothing while no eligible model is installed`() {
        preferences.enabled.set(true)
        preferences.illustrationModel.set(ILLUSTRATION_ID)
        runBlocking {
            cache.put(keyOf(page(), ILLUSTRATION_ID)) { it.writeText("upscaled") }
        }

        session().open(page()) shouldBe null

        installed += ILLUSTRATION_ID
        session().open(page())!!.use { it.readBytes().decodeToString() } shouldBe "upscaled"

        installed.clear()
        session().open(page()) shouldBe null
    }

    /** Catches `open()` serving pages while the feature is off, when the cache may be stale. */
    @Test
    fun `open returns nothing while upscaling is disabled`() {
        preferences.enabled.set(false)
        preferences.mode.set(UpscaleMode.ILLUSTRATION_ONLY)
        preferences.illustrationModel.set(ILLUSTRATION_ID)
        installed += ILLUSTRATION_ID
        runBlocking {
            cache.put(keyOf(page(), ILLUSTRATION_ID)) { it.writeText("upscaled") }
        }

        session().open(page()) shouldBe null
    }

    /**
     * The router rule, end to end: a colour page under AUTO is upscaled by the IllustrationJaNai model
     * even though a MangaJaNai bucket is installed, and no MangaJaNai entry is ever written for it —
     * MangaJaNai is trained on line art, so serving it a colour page would destroy the colour work.
     */
    @Test
    fun `a colour page is routed to the illustration model and no manga entry is written`() {
        preferences.enabled.set(true)
        preferences.mode.set(UpscaleMode.AUTO)
        preferences.scale.set(2)
        preferences.illustrationModel.set(ILLUSTRATION_ID)
        installed += ILLUSTRATION_ID
        installed += MANGA_ID

        val encodedLatch = CountDownLatch(1)
        val session = session(colourPage = true, onEncoded = { encodedLatch.countDown() })
        val page = page()

        session.request(page)
        encodedLatch.await(10, TimeUnit.SECONDS) shouldBe true

        encoded.toList() shouldBe listOf(ILLUSTRATION_ID)
        cache.get(keyOf(page, MANGA_ID)) shouldBe null
        cache.get(keyOf(page, ILLUSTRATION_ID)) shouldNotBe null
        // And the finished page is what the reader is then served from cache.
        session.open(page)!!.use { it.readBytes().decodeToString() } shouldBe ILLUSTRATION_ID
    }

    /** The other half of the rule: a grayscale page under AUTO still gets a MangaJaNai bucket. */
    @Test
    fun `a grayscale page is routed to a manga bucket`() {
        preferences.enabled.set(true)
        preferences.mode.set(UpscaleMode.AUTO)
        preferences.scale.set(2)
        preferences.illustrationModel.set(ILLUSTRATION_ID)
        installed += ILLUSTRATION_ID
        installed += MANGA_ID

        val encodedLatch = CountDownLatch(1)
        val session = session(colourPage = false, onEncoded = { encodedLatch.countDown() })

        session.request(page())
        encodedLatch.await(10, TimeUnit.SECONDS) shouldBe true

        encoded.toList() shouldBe listOf(MANGA_ID)
    }

    /** Catches a request that runs with no model installed (nothing could ever be written). */
    @Test
    fun `a request is dropped while no model is installed`() {
        preferences.enabled.set(true)
        preferences.mode.set(UpscaleMode.AUTO)
        val encodedLatch = CountDownLatch(1)
        val session = session(colourPage = true, onEncoded = { encodedLatch.countDown() })

        session.request(page())

        encodedLatch.await(1, TimeUnit.SECONDS) shouldBe false
        encoded.toList() shouldBe emptyList()
    }

    /**
     * The orphan-entry defect. A page the loader has not filled in yet has `sourceLength = -1`, and
     * the loader sets the real value when the bytes arrive, so a request made before that decodes the
     * *real* page and writes it under the `-1` key: an entry `open` can never match for the page it
     * was made from, which also evicts entries that were usable. So such a page is not queued, and its
     * `-1` entry (should one exist) is never served — and once the loader has filled the identity in,
     * the very same page is queued normally.
     */
    @Test
    fun `an unloaded page is neither queued nor served from the cache`() {
        preferences.enabled.set(true)
        preferences.mode.set(UpscaleMode.ILLUSTRATION_ONLY)
        preferences.illustrationModel.set(ILLUSTRATION_ID)
        installed += ILLUSTRATION_ID

        val page = page(index = 3, length = -1L)
        val encodedLatch = CountDownLatch(1)
        val session = session(onEncoded = { encodedLatch.countDown() })

        // Not queued: nothing is read, decoded or written.
        session.request(page)
        encodedLatch.await(1, TimeUnit.SECONDS) shouldBe false
        decoded.shouldBeEmpty()
        encoded.shouldBeEmpty()

        // Not served either, even when an entry for the unusable identity is on disk (what the old
        // prefetch left behind).
        runBlocking { cache.put(keyOf(page, ILLUSTRATION_ID)) { it.writeText("orphan") } }
        session.open(page) shouldBe null

        // The loader fills the page's identity in; the same page is now queued and written under it.
        page.sourceLength = 10L
        page.sourceLastModified = 20L
        session.request(page)
        encodedLatch.await(10, TimeUnit.SECONDS) shouldBe true

        decoded.toList() shouldBe listOf(3)
        cache.get(keyOf(page, ILLUSTRATION_ID))!!.readText() shouldBe ILLUSTRATION_ID
        session.open(page)!!.use { it.readBytes().decodeToString() } shouldBe ILLUSTRATION_ID
    }

    /**
     * The page-turn cost. `installedIds()` re-reads the install marker, parses the release manifest and
     * stats every model; the old session called it once per request *and* once per route, so a single
     * focus (the visible page + 3) paid for it five times on the frame thread. One resolution per
     * validity window is what makes the page turn cheap.
     */
    @Test
    fun `one page turn reads the installed model list once, not once per page`() {
        preferences.enabled.set(true)
        preferences.mode.set(UpscaleMode.ILLUSTRATION_ONLY)
        preferences.illustrationModel.set(ILLUSTRATION_ID)
        installed += ILLUSTRATION_ID
        val chapter = loadedChapter(indices = 0..3)
        val done = CountDownLatch(4)
        val session = session(onEncoded = { done.countDown() })

        session.focus(chapter.pages!!.first())

        done.await(20, TimeUnit.SECONDS) shouldBe true
        decoded.toList() shouldBe listOf(0, 1, 2, 3)
        installedIdReads.get() shouldBe 1
    }

    /**
     * ...and the other side of that memo: a model installed while the window is open is not seen until
     * the window elapses (or the chapter changes), which is what keeps a finished install from being
     * invisible for the rest of the chapter.
     */
    @Test
    fun `the installed model list is re-read once the validity window elapses`() {
        preferences.enabled.set(true)
        preferences.mode.set(UpscaleMode.ILLUSTRATION_ONLY)
        preferences.illustrationModel.set(ILLUSTRATION_ID)
        var clock = 1_000L
        val session = session(clock = { clock })
        val page = page()

        session.open(page) shouldBe null
        session.open(page) shouldBe null
        installedIdReads.get() shouldBe 1

        clock += ReaderUpscaleSessionImpl.INSTALLED_VALIDITY_MILLIS - 1
        session.open(page) shouldBe null
        installedIdReads.get() shouldBe 1

        clock += 1
        session.open(page) shouldBe null
        installedIdReads.get() shouldBe 2
    }

    /**
     * A chapter change is the explicit refresh: a model that finished downloading while the previous
     * chapter was open is used by the chapter that follows it.
     */
    @Test
    fun `reset picks up a model that finished installing`() {
        preferences.enabled.set(true)
        preferences.mode.set(UpscaleMode.ILLUSTRATION_ONLY)
        preferences.illustrationModel.set(ILLUSTRATION_ID)
        val chapter = loadedChapter(indices = 0..1)
        val page = chapter.pages!!.first()
        val done = CountDownLatch(1)
        val session = session(onEncoded = { done.countDown() })

        session.request(page)
        // Nothing installed: the request is dropped, and the empty set is what the memo holds.
        decoded.shouldBeEmpty()
        installedIdReads.get() shouldBe 1

        installed += ILLUSTRATION_ID
        session.request(page)
        decoded.shouldBeEmpty() // still the memoised answer
        installedIdReads.get() shouldBe 1

        session.reset()
        session.request(page)
        done.await(10, TimeUnit.SECONDS) shouldBe true
        installedIdReads.get() shouldBe 2
        cache.get(keyOf(page, ILLUSTRATION_ID)) shouldNotBe null
    }

    /**
     * The retry defect: a page that can never be upscaled (here a colour page under AUTO with no
     * illustration model installed) used to be re-requested on every page turn — bytes read, decoded,
     * routed, refused — for the whole chapter. It is asked once, remembered, and only asked again
     * after `reset()` (a chapter change) or a preference-signature change makes it a new question.
     */
    @Test
    fun `a page that can never be routed is not requested again until reset`() {
        preferences.enabled.set(true)
        preferences.mode.set(UpscaleMode.AUTO)
        preferences.illustrationModel.set(ILLUSTRATION_ID) // selected, but not installed
        installed += MANGA_ID
        val chapter = loadedChapter(indices = 0..1)
        val page = chapter.pages!!.first()
        val session = session(colourPage = true)

        session.request(page)
        awaitTrue("the page to be memoised as unroutable") {
            session.negative.contains(pageKeyOf(page), preferences.cacheSignature())
        }
        decoded.toList() shouldBe listOf(0)
        encoded.shouldBeEmpty()

        // The next page turn does no work at all for it.
        session.request(page)
        decoded.toList() shouldBe listOf(0)
        cache.get(keyOf(page, ILLUSTRATION_ID)) shouldBe null

        // A chapter change asks the question again.
        session.reset()
        session.negative.contains(pageKeyOf(page), preferences.cacheSignature()) shouldBe false
        session.request(page)
        awaitTrue("the page to be decoded again after the reset") { decoded.size == 2 }
        decoded.toList() shouldBe listOf(0, 0)
    }

    /**
     * The same rule for a page the decoder refuses (a truncated or corrupt image): a retry on every
     * page turn would re-read and re-fail it forever. It is remembered like an unroutable page.
     */
    @Test
    fun `a page that cannot be decoded is not requested again`() {
        preferences.enabled.set(true)
        preferences.mode.set(UpscaleMode.ILLUSTRATION_ONLY)
        preferences.illustrationModel.set(ILLUSTRATION_ID)
        installed += ILLUSTRATION_ID
        undecodable = setOf(4)
        val chapter = loadedChapter(indices = 0..5)
        val page = chapter.pages!![4]
        val session = session()

        session.request(page)
        awaitTrue("the page to be memoised as undecodable") {
            session.negative.contains(pageKeyOf(page), preferences.cacheSignature())
        }
        decoded.toList() shouldBe listOf(4)

        session.request(page)
        decoded.toList() shouldBe listOf(4)
        encoded.shouldBeEmpty()
    }

    /**
     * `focus` is the page the reader is showing plus the next three — no more — and a page the loader
     * has not filled in yet is skipped rather than keyed on `-1` (see the orphan test).
     *
     * The last page the test requests belongs to another chapter, so the queue serves it *last*
     * (`UpscaleService`'s FOCUS_FAR). That makes "exactly these pages ran" provable: any page this
     * focus queued by mistake sits inside the focused chapter and would be served before it.
     */
    @Test
    fun `focus queues the visible page and the next three, skipping unloaded pages`() {
        preferences.enabled.set(true)
        preferences.mode.set(UpscaleMode.ILLUSTRATION_ONLY)
        preferences.illustrationModel.set(ILLUSTRATION_ID)
        installed += ILLUSTRATION_ID
        val chapter = loadedChapter(indices = 0..9, unloaded = setOf(7))
        val elsewhere = page(index = 42, chapter = chapter(OTHER_CHAPTER_ID))
        val done = CountDownLatch(4)
        val session = session(onEncoded = { done.countDown() })

        session.focus(chapter.pages!![5])
        session.request(elsewhere)

        done.await(20, TimeUnit.SECONDS) shouldBe true
        encodedPages.toSet() shouldBe setOf(5, 6, 8, 42)
        decoded.contains(7) shouldBe false
        decoded.toSet() shouldBe setOf(5, 6, 8, 42)
    }

    /**
     * `reset` is the chapter switch: the page in flight and everything queued for the chapter being
     * left are cancelled (nothing of theirs may reach the cache), and the same session is then usable
     * for the chapter that replaces it.
     */
    @Test
    fun `reset cancels the chapter being left and leaves the session usable`() {
        preferences.enabled.set(true)
        preferences.mode.set(UpscaleMode.ILLUSTRATION_ONLY)
        preferences.illustrationModel.set(ILLUSTRATION_ID)
        installed += ILLUSTRATION_ID
        val chapter = loadedChapter(indices = 0..2)
        val (first, second, third) = chapter.pages!!.take(3)

        val insideInference = CountDownLatch(1)
        val release = CountDownLatch(1)
        inferring = {
            if (insideInference.count == 1L) {
                insideInference.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val done = CountDownLatch(1)
        val session = session(onEncoded = { done.countDown() })

        session.request(first)
        insideInference.await(10, TimeUnit.SECONDS) shouldBe true
        session.request(second)

        session.reset()
        release.countDown()

        done.await(1, TimeUnit.SECONDS) shouldBe false
        cache.get(keyOf(first, ILLUSTRATION_ID)) shouldBe null
        cache.get(keyOf(second, ILLUSTRATION_ID)) shouldBe null

        session.request(third)
        done.await(10, TimeUnit.SECONDS) shouldBe true
        cache.get(keyOf(third, ILLUSTRATION_ID)) shouldNotBe null
    }

    /**
     * A recreation is not the end of the reader: its results are app-scoped cache entries, so the work
     * it queued keeps running and the recreated reader reads them back. The closed handle itself is
     * inert, so nothing new is queued from it.
     */
    @Test
    fun `close recreating keeps the queued work running`() {
        preferences.enabled.set(true)
        preferences.mode.set(UpscaleMode.ILLUSTRATION_ONLY)
        preferences.illustrationModel.set(ILLUSTRATION_ID)
        installed += ILLUSTRATION_ID
        val chapter = loadedChapter(indices = 0..2)
        val (first, second, third) = chapter.pages!!.take(3)

        val insideInference = CountDownLatch(1)
        val release = CountDownLatch(1)
        inferring = {
            if (insideInference.count == 1L) {
                insideInference.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val done = CountDownLatch(2)
        val session = session(onEncoded = { done.countDown() })

        session.request(first)
        insideInference.await(10, TimeUnit.SECONDS) shouldBe true
        session.request(second)

        session.close(recreating = true)
        release.countDown()

        done.await(20, TimeUnit.SECONDS) shouldBe true
        cache.get(keyOf(first, ILLUSTRATION_ID)) shouldNotBe null
        cache.get(keyOf(second, ILLUSTRATION_ID)) shouldNotBe null

        // The handle is closed: every later call is a no-op.
        session.open(first) shouldBe null
        session.request(third)
        session.focus(third)
        awaitQuiet()
        decoded.toList() shouldBe listOf(0, 1)
    }

    /**
     * Leaving the reader is what stops it: the queued work is cancelled, nothing reaches the cache,
     * and the closed handle is inert for `open`/`request`/`focus`.
     */
    @Test
    fun `close finishing cancels the queued work and later calls are no-ops`() {
        preferences.enabled.set(true)
        preferences.mode.set(UpscaleMode.ILLUSTRATION_ONLY)
        preferences.illustrationModel.set(ILLUSTRATION_ID)
        installed += ILLUSTRATION_ID
        val chapter = loadedChapter(indices = 0..2)
        val (first, second, third) = chapter.pages!!.take(3)

        val insideInference = CountDownLatch(1)
        val release = CountDownLatch(1)
        inferring = {
            if (insideInference.count == 1L) {
                insideInference.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val done = CountDownLatch(1)
        val session = session(onEncoded = { done.countDown() })

        session.request(first)
        insideInference.await(10, TimeUnit.SECONDS) shouldBe true
        session.request(second)

        session.close(recreating = false)
        release.countDown()

        done.await(1, TimeUnit.SECONDS) shouldBe false
        cache.get(keyOf(first, ILLUSTRATION_ID)) shouldBe null
        cache.get(keyOf(second, ILLUSTRATION_ID)) shouldBe null

        session.open(first) shouldBe null
        session.request(third)
        session.focus(third)
        awaitQuiet()
        decoded.toList() shouldBe listOf(0)
    }

    // ------------------------------------------------------------------ helpers

    private fun session(
        colourPage: Boolean = false,
        clock: () -> Long = System::currentTimeMillis,
        onEncoded: () -> Unit = {},
    ): ReaderUpscaleSessionImpl {
        val engine = UpscaleEngine(
            modelsDir = root,
            surfaces = ArgbSurfaces,
            inferenceFactory = { _, scale, _ -> PassThroughInference(scale) },
            threadPriority = 0,
        )
        val service = UpscaleService(
            cache = cache,
            models = installedModels,
            engine = engine,
            preferences = preferences,
            pageDecoder = { bytes ->
                val index = bytes.decodeToString().toInt()
                decoded += index
                decoding()
                if (index in undecodable) {
                    null
                } else {
                    // The decoder is the only fake image input: a flat colour or a flat grey page.
                    val pixel = if (colourPage) OPAQUE_RED else OPAQUE_GREY
                    DecodedPage(IntArray(PAGE_WIDTH * PAGE_HEIGHT) { pixel }, PAGE_WIDTH, PAGE_HEIGHT)
                }
            },
            tileSize = { 32 },
            encoder = PageEncoder { result: UpscaleResult, target: File ->
                // The entry's content names the model, which is what the assertions read back.
                encoded += result.modelId
                encodedPages += decoded.lastOrNull() ?: -1
                target.writeText(result.modelId)
                onEncoded()
            },
        )
        return ReaderUpscaleSessionImpl(
            service = service,
            cache = cache,
            models = installedModels,
            preferences = preferences,
            now = clock,
        )
    }

    private fun chapter(id: Long = CHAPTER_ID): ReaderChapter =
        ReaderChapter(Chapter.create().copy(id = id, mangaId = MANGA_ID_DB))

    /** A chapter whose page list is loaded, so `focus` can walk the pages after the visible one. */
    private fun loadedChapter(indices: IntRange, unloaded: Set<Int> = emptySet()): ReaderChapter {
        val chapter = chapter()
        val pages = indices.map { index ->
            page(index = index, chapter = chapter, length = if (index in unloaded) -1L else 10L)
        }
        chapter.state = ReaderChapter.State.Loaded(pages)
        return chapter
    }

    /**
     * A page whose `stream` carries its own index, so the fake decoder can tell them apart. `length =
     * -1` is what a page looks like before its loader has the bytes.
     */
    private fun page(
        index: Int = 0,
        chapter: ReaderChapter = chapter(),
        length: Long = 10L,
        modified: Long = 20L,
    ): ReaderPage = ReaderPage(index, "url", "image").apply {
        this.chapter = chapter
        this.stream = { ByteArrayInputStream(index.toString().toByteArray()) }
        this.sourceLength = length
        this.sourceLastModified = modified
    }

    private fun pageKeyOf(page: ReaderPage): PageKey = PageKey(
        sourceId = -1L,
        mangaId = page.chapter.chapter.mangaId,
        chapterId = page.chapter.chapter.id,
        pageIndex = page.index,
        sourceLength = page.sourceLength,
        sourceLastModified = page.sourceLastModified,
    )

    private fun keyOf(page: ReaderPage, modelId: String): String = cache.key(
        sourceId = -1L,
        mangaId = page.chapter.chapter.mangaId,
        chapterId = page.chapter.chapter.id,
        pageIndex = page.index,
        sourceLength = page.sourceLength,
        sourceLastModified = page.sourceLastModified,
        modelId = modelId,
        scale = 2,
        signature = preferences.cacheSignature(),
    )

    /** Waits for [condition], failing loudly (rather than hanging) when it never holds. */
    private fun awaitTrue(what: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        fail("timed out after ${timeoutMillis}ms waiting for $what")
    }

    /**
     * A short settle for "the queue must stay empty": the engine thread is a single thread and every
     * request of these tests was enqueued before it, so a page that would ever run has started by now.
     */
    private fun awaitQuiet() = Thread.sleep(200)

    /** A pass-through "model": every tile comes back scaled by nearest-neighbour. */
    private inner class PassThroughInference(private val scale: Int) : TileInference {
        override val isFloat16: Boolean = false

        override fun infer(input: TileInput): FloatArray {
            inferring()
            val rgb = (input as TileInput.Float32).rgb
            val width = input.width
            val height = input.height
            val output = FloatArray(width * scale * height * scale * 3)
            for (y in 0 until height * scale) {
                for (x in 0 until width * scale) {
                    val from = ((y / scale) * width + x / scale) * 3
                    val to = (y * width * scale + x) * 3
                    output[to] = rgb[from]
                    output[to + 1] = rgb[from + 1]
                    output[to + 2] = rgb[from + 2]
                }
            }
            return output
        }
    }

    private companion object {
        const val CHAPTER_ID = 7L
        const val OTHER_CHAPTER_ID = 9L
        const val MANGA_ID_DB = 2L

        /** Catalog ids, so the session resolves them through the same catalog the app uses. */
        val ILLUSTRATION_ID: String = UpscalePreferences.DEFAULT_ILLUSTRATION_MODEL_ID
        val MANGA_ID: String = UpscaleModelCatalog.models
            .first { it.bucketHeight == 1600 && it.scale == 2 }
            .id

        const val PAGE_WIDTH = 64
        const val PAGE_HEIGHT = 64
        const val OPAQUE_RED = 0xFFFF0000.toInt()
        const val OPAQUE_GREY = 0xFF808080.toInt()
    }
}
