package eu.kanade.tachiyomi.extension.model

import io.kotest.matchers.shouldBe
import mihon.domain.extension.model.ContentWarning
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class ExtensionMatchingTest {

    private val pkg = "eu.kanade.tachiyomi.extension.all.mangadex"

    @Test
    fun `Identity key combines package name and signing key`() {
        available(versionCode = 1, store = yuzono).identityKey shouldBe "${pkg}_$YUZONO_KEY"
    }

    @Test
    fun `Same package signed by another store's key is not an update`() {
        val installed = installed(versionCode = 211, signatures = listOf(YUZONO_KEY))
        val listings = listOf(available(versionCode = 106001, store = keiyoushi))

        installed.findAvailable(listings) shouldBe null
        installed.findUpdate(listings) shouldBe null
    }

    @Test
    fun `Same package and signing key is matched among other stores`() {
        val installed = installed(versionCode = 211, signatures = listOf(YUZONO_KEY))
        val fromYuzono = available(versionCode = 212, store = yuzono)
        val listings = listOf(
            available(versionCode = 106001, store = keiyoushi),
            fromYuzono,
        )

        installed.findAvailable(listings) shouldBe fromYuzono
    }

    @Test
    fun `Another package with the same signing key is not matched`() {
        val installed = installed(versionCode = 1, signatures = listOf(YUZONO_KEY))
        val listings = listOf(available(versionCode = 2, store = yuzono, pkgName = "other"))

        installed.findAvailable(listings) shouldBe null
    }

    @Test
    fun `Newest of several stores sharing a signing key is picked`() {
        val installed = installed(versionCode = 1, signatures = listOf(YUZONO_KEY))
        val newest = available(versionCode = 3, store = komikku)
        val listings = listOf(
            available(versionCode = 2, store = yuzono),
            newest,
        )

        installed.findAvailable(listings) shouldBe newest
    }

    @Test
    fun `Outdated when the version code or extension lib is newer`() {
        val installed = installed(versionCode = 5, signatures = listOf(YUZONO_KEY), libVersion = 1.4)

        installed.isOutdatedComparedTo(available(versionCode = 5, store = yuzono)) shouldBe false
        installed.isOutdatedComparedTo(available(versionCode = 4, store = yuzono)) shouldBe false
        installed.isOutdatedComparedTo(available(versionCode = 6, store = yuzono)) shouldBe true
        installed.isOutdatedComparedTo(available(versionCode = 5, store = yuzono, libVersion = 1.6)) shouldBe true
    }

    @Test
    fun `Extension signed with several keys is updated from a store of any of them`() {
        val installed = installed(versionCode = 1, signatures = listOf(YUZONO_KEY, KEIYOUSHI_KEY))
        val fromKeiyoushi = available(versionCode = 2, store = keiyoushi)
        val listings = listOf(fromKeiyoushi)

        installed.findAvailable(listings) shouldBe fromKeiyoushi
    }

    @Test
    fun `A listing that is not newer is not an update`() {
        val installed = installed(versionCode = 5, signatures = listOf(YUZONO_KEY), libVersion = 1.6)

        installed.findUpdate(listOf(available(versionCode = 5, store = yuzono))) shouldBe null
    }

    @Test
    fun `An extension signed with several keys is matched by any of them, not just the last`() {
        // The case above matches the *second* signature, so a `first()` implementation fails there; this one
        // matches the *first*, which a `signatures.last()` implementation misses. Only a membership test passes
        // both.
        val installed = installed(versionCode = 1, signatures = listOf(YUZONO_KEY, KEIYOUSHI_KEY))
        val fromYuzono = available(versionCode = 2, store = yuzono)

        installed.findAvailable(listOf(fromYuzono)) shouldBe fromYuzono
        installed.findUpdate(listOf(fromYuzono)) shouldBe fromYuzono
    }

    @Test
    fun `A listing with a newer version code is an update`() {
        val installed = installed(versionCode = 5, signatures = listOf(YUZONO_KEY), libVersion = 1.4)
        val newer = available(versionCode = 6, store = yuzono)

        installed.findUpdate(listOf(newer)) shouldBe newer
        // ...while the same version code, at the same extension lib, is still no update.
        installed.findUpdate(listOf(available(versionCode = 5, store = yuzono))) shouldBe null
    }

    @Test
    fun `A listing at the same version code with a newer extension lib is an update`() {
        val installed = installed(versionCode = 5, signatures = listOf(YUZONO_KEY), libVersion = 1.6)
        val newerLib = available(versionCode = 5, store = yuzono, libVersion = 1.8)

        installed.findUpdate(listOf(newerLib)) shouldBe newerLib
        // The lib version is compared, not merely present: the same or an older one is no update.
        installed.findUpdate(listOf(available(versionCode = 5, store = yuzono, libVersion = 1.6))) shouldBe null
        installed.findUpdate(listOf(available(versionCode = 5, store = yuzono, libVersion = 1.5))) shouldBe null
    }

    @Test
    fun `An extension that was never loaded is updated only by a newer version code`() {
        val installed = notLoaded(versionCode = 5, signatures = listOf(YUZONO_KEY))
        val sameVersionNewerLib = available(versionCode = 5, store = yuzono, libVersion = 9.9)

        // No lib version was ever read for it, so there is nothing to be outdated by: only the version code counts.
        installed.isOutdatedComparedTo(sameVersionNewerLib) shouldBe false
        installed.findUpdate(listOf(sameVersionNewerLib)) shouldBe null

        val newer = available(versionCode = 6, store = yuzono)
        installed.isOutdatedComparedTo(newer) shouldBe true
        installed.findUpdate(listOf(newer)) shouldBe newer
    }

    private fun installed(
        versionCode: Long,
        signatures: List<String>,
        libVersion: Double = 1.4,
    ) = Extension.Loaded(
        name = "MangaDex",
        pkgName = pkg,
        versionName = "$libVersion.$versionCode",
        versionCode = versionCode,
        libVersion = libVersion,
        lang = "all",
        contentWarning = ContentWarning.SAFE,
        isShared = true,
        signatures = signatures,
        pkgFactory = null,
        sources = emptyList(),
    )

    private fun notLoaded(
        versionCode: Long,
        signatures: List<String>,
    ) = Extension.NotLoaded(
        name = "MangaDex",
        pkgName = pkg,
        versionName = "1.4.$versionCode",
        versionCode = versionCode,
        isShared = true,
        contentWarning = ContentWarning.SAFE,
        signatures = signatures,
        libVersion = null,
        lang = null,
        reason = Extension.NotLoaded.Reason.Filtered,
    )

    private fun available(
        versionCode: Long,
        store: ExtensionStore,
        libVersion: Double = 1.4,
        pkgName: String = pkg,
    ) = Extension.Available(
        name = "MangaDex",
        pkgName = pkgName,
        versionName = "$libVersion.$versionCode",
        versionCode = versionCode,
        libVersion = libVersion,
        lang = "all",
        contentWarning = ContentWarning.SAFE,
        sources = emptyList(),
        apkUrl = "${store.indexUrl}/$pkgName.apk",
        iconUrl = "",
        store = store,
    )

    private fun store(name: String, signingKey: String) = ExtensionStore(
        indexUrl = "https://$name.example/repo.json",
        name = name,
        badgeLabel = name,
        signingKey = signingKey,
        contact = ExtensionStore.Contact(website = "", discord = null),
        isLegacy = true,
        extensionListUrl = null,
    )

    private val yuzono = store("yuzono", YUZONO_KEY)
    private val komikku = store("komikku", YUZONO_KEY)
    private val keiyoushi = store("keiyoushi", KEIYOUSHI_KEY)

    companion object {
        private const val YUZONO_KEY = "cbec121a"
        private const val KEIYOUSHI_KEY = "9add655a"
    }
}
