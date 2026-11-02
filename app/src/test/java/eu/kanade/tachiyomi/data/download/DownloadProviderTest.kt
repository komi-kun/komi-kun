package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.util.lang.Hash.md5
import eu.kanade.tachiyomi.util.storage.DiskUtil
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences

class DownloadProviderTest {

    private fun provider(disallowNonAscii: Boolean, includeChapterUrlHash: Boolean = true): DownloadProvider {
        val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
        libraryPreferences.disallowNonAsciiFilenames.set(disallowNonAscii)
        val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore())
        downloadPreferences.includeChapterUrlHash.set(includeChapterUrlHash)
        return DownloadProvider(mockk(relaxed = true), mockk(relaxed = true), libraryPreferences, downloadPreferences)
    }

    private fun dirNameBefore0210(name: String, scanlator: String?, url: String, disallowNonAscii: Boolean): String {
        val dirName = if (scanlator.isNullOrBlank()) name else "${scanlator}_$name"
        return DiskUtil.buildValidFilename(dirName, DiskUtil.MAX_FILE_NAME_BYTES - 11, disallowNonAscii) +
            "_" + md5(url).take(6)
    }

    private fun dirName0210(name: String, scanlator: String?, url: String, disallowNonAscii: Boolean): String {
        return DiskUtil.buildValidFilename(scanlator!!) + "_" +
            DiskUtil.buildValidFilename(name, DiskUtil.MAX_FILE_NAME_BYTES - 11, disallowNonAscii) +
            "_" + md5(url).take(6)
    }

    @ParameterizedTest
    @MethodSource("chapters")
    fun `finds chapters downloaded before 0_21_0 and names new ones the same`(
        name: String,
        scanlator: String?,
        disallowNonAscii: Boolean,
    ) {
        val provider = provider(disallowNonAscii)
        val expected = dirNameBefore0210(name, scanlator, URL, disallowNonAscii)

        provider.getValidChapterDirNames(name, scanlator, URL) shouldContain expected
        provider.getChapterDirName(name, scanlator, URL) shouldBe expected
    }

    @ParameterizedTest
    @MethodSource("chapters")
    fun `finds chapters downloaded by 0_21_0 and 0_21_1`(name: String, scanlator: String?, disallowNonAscii: Boolean) {
        if (scanlator.isNullOrBlank()) return

        provider(disallowNonAscii).getValidChapterDirNames(name, scanlator, URL) shouldContain
            dirName0210(name, scanlator, URL, disallowNonAscii)
    }

    // The orphaned-download cleanup decides what to delete by comparing the on-disk names with these
    // lists, so every form a download can have has to stay listed here.

    @Test
    fun `a scanlator-prefixed name is listed`() {
        val names = provider(disallowNonAscii = false, includeChapterUrlHash = false)
            .getValidChapterDirNames("Chapter 1", "Group", URL)

        names.first() shouldBe "Group_Chapter 1"
        names shouldContain "Group_Chapter 1.cbz"
        names shouldNotContain "Chapter 1"
    }

    @Test
    fun `the url hash is appended when enabled and the unhashed legacy name is still listed`() {
        val hash = md5(URL).take(6)
        val names = provider(disallowNonAscii = false, includeChapterUrlHash = true)
            .getValidChapterDirNames("Chapter 1", null, URL)

        names.first() shouldBe "Chapter 1_$hash"
        names shouldContain "Chapter 1_$hash.cbz"
        // A chapter downloaded before the hash setting was enabled must still be found.
        names shouldContain "Chapter 1"
        names shouldContain "Chapter 1.cbz"
    }

    @Test
    fun `both the folder and the archived cbz form are listed`() {
        val names = provider(disallowNonAscii = false, includeChapterUrlHash = false)
            .getValidChapterDirNames("Chapter 1", null, URL)

        names shouldContain "Chapter 1"
        names shouldContain "Chapter 1.cbz"
    }

    @Test
    fun `a non-ascii name is escaped when non-ascii filenames are disallowed`() {
        val escaped = DiskUtil.buildValidFilename("翻訳グループ_第1話", DiskUtil.MAX_FILE_NAME_BYTES - 11, true)
        val names = provider(disallowNonAscii = true, includeChapterUrlHash = false)
            .getValidChapterDirNames("第1話", "翻訳グループ", URL)

        escaped shouldNotBe "翻訳グループ_第1話"
        escaped.all { it.code < 0x80 } shouldBe true
        names.first() shouldBe escaped
        names shouldContain "$escaped.cbz"
    }

    companion object {
        private const val URL = "/chapter/1"

        @JvmStatic
        fun chapters() = listOf(
            Arguments.of("Chapter 1", "Group", false),
            Arguments.of(" Chapter 1", "Group", false),
            Arguments.of(".Chapter 1", "Group", false),
            Arguments.of("Chapter 1", "Group ", false),
            Arguments.of("Chapter 1", "A/B ", false),
            Arguments.of("Chapter ".repeat(40), "Group", false),
            Arguments.of("Chapter 1", "翻訳グループ", true),
            Arguments.of(" Chapter 1", null, false),
        )
    }
}
