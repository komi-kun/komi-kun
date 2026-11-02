package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.lang.Hash.md5
import eu.kanade.tachiyomi.util.storage.DiskUtil
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import mihon.core.ocr.cache.OcrManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.isLocal

/**
 * The corner cases of the orphaned-download decision, driven by the production name producer.
 *
 * [OrphanedDownloadsTest] hands the decision a hand-written name producer, so it can only say what
 * the *decision* does with a name list. Every name here instead comes out of the real
 * [DownloadProvider] (`getValidChapterDirNames`), so the suite pins the naming production actually
 * ships — the 229-byte budget, the escaping, the url-hash suffix and the legacy list — and a change
 * to any of them shows up as a failing case instead of silently moving the delete boundary.
 *
 * Every case has the same shape: the chapters that survive, the on-disk entries those chapters still
 * reach (kept) and the entries nothing reaches any more (orphaned). Which chapters count as
 * survivors — the folder-scoping rule — is [OrphanedDownloadCleanerImplTest]'s subject, not this one.
 */
class OrphanedDownloadsCornerCasesTest {

    // region fixture

    /** A chapter as the download naming sees it: exactly the three fields the provider takes. */
    data class ChapterSpec(
        val name: String,
        val scanlator: String? = null,
        val url: String = URL_1,
    ) {
        fun toChapter() = Chapter.create().copy(name = name, scanlator = scanlator, url = url)
    }

    /** One on-disk entry a case talks about, resolved against the provider when the case runs. */
    sealed interface Entry {
        /**
         * A literal name. [producedBy] pins it to the provider's own output for that chapter: the
         * fixture fails loudly when the premise is gone, rather than keeping an expectation for a
         * name nothing produces any more (which would turn a keep case into a tautology).
         */
        data class Text(val name: String, val producedBy: ChapterSpec? = null) : Entry

        /** The names of [spec] with their case flipped: what a case-insensitive volume hands over. */
        data class CaseFlipped(val spec: ChapterSpec) : Entry

        /** Every name the provider gives [spec] that the [other] chapters do not also give. */
        data class Names(val spec: ChapterSpec, val other: List<ChapterSpec> = emptyList()) : Entry
    }

    data class Case(
        val description: String,
        val survivors: List<ChapterSpec>,
        /** Entries a surviving chapter still reaches: they must not be orphaned. */
        val kept: List<Entry> = emptyList(),
        /** Entries nothing produces any more: they must be orphaned. */
        val orphaned: List<Entry> = emptyList(),
        val disallowNonAscii: Boolean = false,
        val includeUrlHash: Boolean = false,
    ) {
        // The parameterized test's display name: the case reads as one sentence in the report.
        override fun toString() = description
    }

    private fun provider(disallowNonAscii: Boolean = false, includeUrlHash: Boolean = false): DownloadProvider {
        val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
        libraryPreferences.disallowNonAsciiFilenames.set(disallowNonAscii)
        val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore())
        downloadPreferences.includeChapterUrlHash.set(includeUrlHash)
        return DownloadProvider(mockk(relaxed = true), mockk(relaxed = true), libraryPreferences, downloadPreferences)
    }

    /** The production name producer, called exactly as `DownloadManager` calls it. */
    private fun DownloadProvider.namesOf(spec: ChapterSpec): List<String> =
        getValidChapterDirNames(spec.name, spec.scanlator, spec.url)

    private fun reachableBy(provider: DownloadProvider, survivors: List<ChapterSpec>): Set<String> =
        OrphanedDownloads.reachableFileNames(
            survivors.map(ChapterSpec::toChapter),
            provider::getValidChapterDirNames,
        )

    private fun resolve(provider: DownloadProvider, entry: Entry): List<String> = when (entry) {
        is Entry.Text -> {
            entry.producedBy?.let { spec ->
                check(entry.name in provider.namesOf(spec)) {
                    "fixture premise gone: the provider no longer gives '${spec.name}' the name '${entry.name}'"
                }
            }
            listOf(entry.name)
        }
        is Entry.CaseFlipped -> provider.namesOf(entry.spec).map { it.uppercase() }
        is Entry.Names -> {
            val excluded = entry.other.flatMap { provider.namesOf(it) }.toSet()
            provider.namesOf(entry.spec).filterNot { it in excluded }.also {
                check(it.isNotEmpty()) { "fixture premise gone: '${entry.spec.name}' has no name of its own" }
            }
        }
    }

    // endregion

    // region the name matrix

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `the entries a surviving chapter reaches are kept and the rest are orphaned`(case: Case) {
        val provider = provider(case.disallowNonAscii, case.includeUrlHash)
        val reachable = reachableBy(provider, case.survivors)

        case.kept.flatMap { resolve(provider, it) }.forEach { name ->
            withClue("${case.description}: '$name' is reachable, so it must be kept") {
                OrphanedDownloads.isOrphaned(name, reachable) shouldBe false
            }
        }
        case.orphaned.flatMap { resolve(provider, it) }.forEach { name ->
            withClue("${case.description}: '$name' is unreachable, so it must be deleted") {
                OrphanedDownloads.isOrphaned(name, reachable) shouldBe true
            }
        }
    }

    /**
     * The other flag combination: `disallowNonAscii` and `includeChapterUrlHash` compose, and the
     * escaped-plus-hashed name still fits the same byte budget.
     */
    @Test
    fun `the escaped name with a url hash is kept`() {
        val provider = provider(disallowNonAscii = true, includeUrlHash = true)
        val reachable = reachableBy(provider, listOf(nonAscii))
        val escapedAndHashed = escapedName("翻訳グループ_第1話") + "_" + hashOf(URL_1)

        // The premise: this really is the first name the provider produces for that chapter.
        provider.namesOf(nonAscii).first() shouldBe escapedAndHashed
        OrphanedDownloads.isOrphaned(escapedAndHashed, reachable) shouldBe false
        OrphanedDownloads.isOrphaned("$escapedAndHashed.cbz", reachable) shouldBe false
    }

    /**
     * Two long chapter names that differ only past the truncation point are *one* file on disk, so
     * the survivor keeps it — which is only true while the provider truncates them together.
     */
    @Test
    fun `two different long names truncate to one file name`() {
        val provider = provider()

        val survivorNames = provider.namesOf(longX)
        val removedNames = provider.namesOf(longY)

        survivorNames shouldBe removedNames
        OrphanedDownloads.isOrphaned(removedNames.first(), reachableBy(provider, listOf(longX))) shouldBe false
    }

    /**
     * A name that only the *other* `disallowNonAsciiFilenames` setting would produce is never
     * orphaned: `getLegacyChapterDirNames` puts all four `(disallowNonAscii, includeHash)`
     * combinations in every chapter's valid-name list, so a download written before the setting was
     * flipped stays reachable. The brief's "must be orphaned under the other setting" case therefore
     * cannot exist — this is its opposite, asserted from both directions.
     */
    @Test
    fun `a name only valid under the other non-ascii setting is still reachable`() {
        val asciiOnly = provider(disallowNonAscii = true)
        val raw = provider(disallowNonAscii = false)

        val escaped = asciiOnly.namesOf(nonAscii).first()
        val rawName = raw.namesOf(nonAscii).first()

        // The fixture premise: the two settings really do name this chapter differently.
        escaped shouldNotBe rawName
        escaped.none { it.code >= 0x80 } shouldBe true
        rawName.any { it.code >= 0x80 } shouldBe true

        // A run with the setting on wrote the escaped name; a run with it off must still find it.
        raw.namesOf(nonAscii) shouldContain escaped
        OrphanedDownloads.isOrphaned(escaped, reachableBy(raw, listOf(nonAscii))) shouldBe false
        // And the other way round: the raw name survives the setting being turned on.
        asciiOnly.namesOf(nonAscii) shouldContain rawName
        OrphanedDownloads.isOrphaned(rawName, reachableBy(asciiOnly, listOf(nonAscii))) shouldBe false
    }

    // endregion

    // region the decision itself

    @Test
    fun `an empty survivor list orphans every name of the removed chapter`() {
        val provider = provider(includeUrlHash = true)

        val reachable = reachableBy(provider, emptyList())

        reachable shouldBe emptySet()
        val names = provider.namesOf(ch1).distinct()
        names.forEach { name ->
            withClue(name) { OrphanedDownloads.isOrphaned(name, reachable) shouldBe true }
        }
        // The delete side sees the same decision: everything readable goes, the unreadable entry stays.
        OrphanedDownloads.orphaned(names + listOf(null), { it }, reachable) shouldBe names
    }

    @Test
    fun `an entry whose name cannot be read is never orphaned`() {
        val provider = provider()
        val reachable = reachableBy(provider, listOf(ch1))

        // The premise: this is not the empty-set shortcut, the set really holds names.
        reachable.isEmpty() shouldBe false
        OrphanedDownloads.isOrphaned(null, reachable) shouldBe false
        OrphanedDownloads.isOrphaned(null, emptySet()) shouldBe false
    }

    @Test
    fun `the delete filter is name membership only`() {
        val provider = provider()
        val reachable = reachableBy(provider, listOf(ch1))
        // Mixed input: names a survivor reaches, names nothing reaches, and one that cannot be read.
        val entries = provider.namesOf(ch1) + listOf("Chapter 2", "Chapter 2.cbz", null)

        OrphanedDownloads.orphaned(entries, { it }, reachable) shouldBe listOf("Chapter 2", "Chapter 2.cbz")
        OrphanedDownloads.orphaned(entries, { it }, emptySet()) shouldBe
            entries.filterNotNull()
    }

    // endregion

    // region what the cleaner hands to DownloadManager

    /**
     * The cleaner hands the removed chapters to [DownloadManager], which is where the guard for the
     * user's own local-source storage lives. `OrphanedDownloadCleanerImplTest` covers the other two
     * "delete nothing" branches (preference off, unresolved source); this pair covers the third.
     *
     * The two tests are one instrument: the local one asserts the file lookup is *not* reached, the
     * network one proves the assertion can see it being reached.
     */
    @Test
    fun `a local source is never pruned`() = runBlocking {
        val provider = mockk<DownloadProvider>(relaxed = true)
        coEvery { provider.findChapterDirs(any(), any(), any()) } returns (null to emptyList())
        val downloader = mockk<Downloader>(relaxed = true)
        val ocrManager = mockk<OcrManager>(relaxed = true)
        val source = mockk<Source>(relaxed = true)
        every { source.id } returns LocalSource.ID
        // The fixture premise, not the assertion: this really is the local source.
        source.isLocal() shouldBe true

        managerOf(provider, downloader, ocrManager).deleteOrphanedChapters(
            removedChapters = listOf(ch1.toChapter()),
            remainingChapters = emptyList(),
            manga = manga(),
            source = source,
        )

        coVerify(exactly = 0) { provider.findChapterDirs(any(), any(), any()) }
        coVerify(exactly = 0) { downloader.dequeue(any()) }
        coVerify(exactly = 0) { ocrManager.deleteOcrForChapters(any()) }
    }

    @Test
    fun `a network source reaches the file lookup`() = runBlocking {
        val provider = mockk<DownloadProvider>(relaxed = true)
        coEvery { provider.findChapterDirs(any(), any(), any()) } returns (null to emptyList())
        val downloader = mockk<Downloader>(relaxed = true)
        val ocrManager = mockk<OcrManager>(relaxed = true)
        val source = mockk<Source>(relaxed = true)
        every { source.id } returns 1L
        source.isLocal() shouldBe false

        managerOf(provider, downloader, ocrManager).deleteOrphanedChapters(
            removedChapters = listOf(ch1.toChapter()),
            remainingChapters = emptyList(),
            manga = manga(),
            source = source,
        )

        coVerify(exactly = 1) { provider.findChapterDirs(any(), any(), any()) }
    }

    private fun managerOf(
        provider: DownloadProvider,
        downloader: Downloader,
        ocrManager: OcrManager,
    ) = DownloadManager(
        context = mockk(relaxed = true),
        provider = provider,
        cache = mockk(relaxed = true),
        getCategories = mockk(relaxed = true),
        getManga = mockk(relaxed = true),
        getChapter = mockk(relaxed = true),
        sourceManager = mockk(relaxed = true),
        downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
        downloader = downloader,
        pendingDeleter = mockk(relaxed = true),
        ocrManager = ocrManager,
    )

    private fun manga() = Manga.create().copy(id = 1, source = 1, ogTitle = "Title")

    // endregion

    companion object {
        private const val URL_1 = "/chapter/1"
        private const val URL_2 = "/chapter/2"

        // The chapter name the long-name cases share; `X` and `Y` fall past the truncation point.
        private val LONG_NAME = "Chapter ".repeat(40)

        private val plain = ChapterSpec(name = "Vol.01 Ch.001")
        // A second chapter row (another id, another url) naming the same file, as a re-added chapter is.
        private val replacement = ChapterSpec(name = "Vol.01 Ch.001", url = URL_2)
        private val ch1 = ChapterSpec(name = "Chapter 1")
        private val ch1OtherUrl = ChapterSpec(name = "Chapter 1", url = URL_2)
        private val ch2 = ChapterSpec(name = "Chapter 2")
        private val ch10 = ChapterSpec(name = "Chapter 10")
        private val grouped = ChapterSpec(name = "Chapter 1", scanlator = "Group")
        private val otherGroup = ChapterSpec(name = "Chapter 1", scanlator = "GroupB")
        private val nonAscii = ChapterSpec(name = "第1話", scanlator = "翻訳グループ")
        private val leadingSpace = ChapterSpec(name = " Chapter 1", scanlator = "Group")
        private val longX = ChapterSpec(name = LONG_NAME + "X")
        private val longY = ChapterSpec(name = LONG_NAME + "Y")
        private val longLegacy = ChapterSpec(name = "A".repeat(250), scanlator = "Group")

        /** The 6 hex characters `includeChapterUrlHash` appends. */
        private fun hashOf(url: String) = md5(url).take(6)

        /** The chapter name budget, exactly as `ChapterDirNameParts` applies it. */
        private fun chapterNameOf(value: String) = DiskUtil.buildValidFilename(value, DiskUtil.MAX_FILE_NAME_BYTES - 11)

        /** The escaped form of a name, as the provider builds it with non-ASCII filenames disallowed. */
        private fun escapedName(value: String) =
            DiskUtil.buildValidFilename(value, DiskUtil.MAX_FILE_NAME_BYTES - 11, disallowNonAscii = true)

        /** The name at the old 240-byte budget, which `getLegacyChapterDirNames` still lists. */
        private fun legacyName(value: String) = DiskUtil.buildValidFilename(value)

        @JvmStatic
        fun cases(): List<Case> = listOf(
            Case(
                description = "a plain chapter name and its cbz file are kept",
                survivors = listOf(plain),
                kept = listOf(Entry.Text("Vol.01 Ch.001", plain), Entry.Text("Vol.01 Ch.001.cbz", plain)),
                orphaned = listOf(Entry.Text("Vol.01 Ch.002")),
            ),
            Case(
                description = "a scanlator-prefixed name and its cbz file are kept",
                survivors = listOf(grouped),
                kept = listOf(Entry.Text("Group_Chapter 1", grouped), Entry.Text("Group_Chapter 1.cbz", grouped)),
                // The scanlator is part of the file name, so the bare chapter name owns nothing.
                orphaned = listOf(Entry.Text("Chapter 1")),
            ),
            Case(
                description = "with the url hash on, the hashed name and the unhashed legacy name are kept",
                survivors = listOf(ch1),
                includeUrlHash = true,
                kept = listOf(
                    Entry.Text("Chapter 1_${hashOf(URL_1)}", ch1),
                    Entry.Text("Chapter 1_${hashOf(URL_1)}.cbz", ch1),
                    // A download written before the hash setting was enabled must survive.
                    Entry.Text("Chapter 1", ch1),
                    Entry.Text("Chapter 1.cbz", ch1),
                ),
                orphaned = listOf(Entry.Text("Chapter 1_${hashOf(URL_2)}", ch1OtherUrl)),
            ),
            Case(
                description = "an escaped non-ascii name and its cbz file are kept while non-ascii is disallowed",
                survivors = listOf(nonAscii),
                disallowNonAscii = true,
                kept = listOf(
                    Entry.Text(escapedName("翻訳グループ_第1話"), nonAscii),
                    Entry.Text("${escapedName("翻訳グループ_第1話")}.cbz", nonAscii),
                ),
                orphaned = listOf(Entry.Text("翻訳グループ_第2話")),
            ),
            Case(
                description = "the raw non-ascii name is kept while non-ascii is allowed, and its escaped form too",
                survivors = listOf(nonAscii),
                kept = listOf(
                    Entry.Text("翻訳グループ_第1話", nonAscii),
                    Entry.Text("翻訳グループ_第1話.cbz", nonAscii),
                    // The legacy list keeps the other setting's form reachable, so it is never deleted
                    // (see `a name only valid under the other non-ascii setting is still reachable`).
                    Entry.Text(escapedName("翻訳グループ_第1話"), nonAscii),
                ),
                orphaned = listOf(Entry.Text("翻訳グループ_第2話")),
            ),
            Case(
                description = "an on-disk entry differing from a survivor only in case is kept",
                survivors = listOf(plain),
                kept = listOf(Entry.CaseFlipped(plain)),
                orphaned = listOf(Entry.Text("Vol.01 Ch.002")),
            ),
            Case(
                description = "two chapters resolving to one directory share its file",
                survivors = listOf(plain, replacement),
                kept = listOf(
                    Entry.Text("Vol.01 Ch.001", replacement),
                    Entry.Text("Vol.01 Ch.001.cbz", replacement),
                ),
                orphaned = listOf(Entry.Text("Vol.01 Ch.002")),
            ),
            Case(
                description = "a removed long name the survivor truncates to the same file is kept",
                survivors = listOf(longX),
                kept = listOf(
                    Entry.Text(chapterNameOf(longY.name), longY),
                    Entry.Text("${chapterNameOf(longY.name)}.cbz", longY),
                ),
                orphaned = listOf(Entry.Text("Chapter 2")),
            ),
            Case(
                description = "the 0.21 legacy name, which sanitised the scanlator and the name apart, is kept",
                survivors = listOf(leadingSpace),
                kept = listOf(
                    Entry.Text("Group_Chapter 1", leadingSpace),
                    Entry.Text("Group_Chapter 1.cbz", leadingSpace),
                ),
                orphaned = listOf(Entry.Text("Group_Chapter 2")),
            ),
            Case(
                description = "the pre-0.21 name at the old 240-byte budget is kept",
                survivors = listOf(longLegacy),
                kept = listOf(
                    Entry.Text(legacyName("Group_" + "A".repeat(250)), longLegacy),
                    Entry.Text("${legacyName("Group_" + "A".repeat(250))}.cbz", longLegacy),
                ),
                // One byte past the old budget: no name of that length is produced any more.
                orphaned = listOf(
                    Entry.Text("Group_" + "A".repeat(DiskUtil.MAX_FILE_NAME_BYTES - "Group_".length + 1)),
                ),
            ),
            Case(
                description = "a removed chapter's unique name and its cbz file are orphaned",
                survivors = listOf(ch2),
                kept = listOf(Entry.Text("Chapter 2", ch2)),
                orphaned = listOf(Entry.Text("Chapter 1", ch1), Entry.Text("Chapter 1.cbz", ch1)),
            ),
            Case(
                description = "a survivor differing by a leading character does not match",
                survivors = listOf(ch10),
                kept = listOf(Entry.Text("Chapter 10", ch10)),
                orphaned = listOf(Entry.Text("Chapter 1", ch1)),
            ),
            Case(
                description = "a survivor differing by a trailing character does not match",
                survivors = listOf(ch1),
                kept = listOf(Entry.Text("Chapter 1", ch1)),
                orphaned = listOf(Entry.Text("Chapter 1x")),
            ),
            Case(
                description = "a survivor differing by a character in the middle does not match",
                survivors = listOf(ch1),
                kept = listOf(Entry.Text("Chapter 1", ch1)),
                orphaned = listOf(Entry.Text("Chapte 1")),
            ),
            Case(
                description = "a survivor with another scanlator does not match",
                survivors = listOf(otherGroup),
                kept = listOf(Entry.Text("GroupB_Chapter 1", otherGroup)),
                orphaned = listOf(
                    Entry.Text("Group_Chapter 1", grouped),
                    Entry.Text("Group_Chapter 1.cbz", grouped),
                ),
            ),
            Case(
                description = "with the url hash on, another url's hash is orphaned while the survivor's is kept",
                survivors = listOf(ch1),
                includeUrlHash = true,
                kept = listOf(
                    Entry.Text("Chapter 1_${hashOf(URL_1)}.cbz", ch1),
                    // The unhashed legacy name is shared, so it belongs to both chapters.
                    Entry.Text("Chapter 1", ch1),
                ),
                // Only the names the other url adds: `Names` drops everything the survivor also gives.
                orphaned = listOf(Entry.Names(ch1OtherUrl, other = listOf(ch1))),
            ),
        )
    }
}
