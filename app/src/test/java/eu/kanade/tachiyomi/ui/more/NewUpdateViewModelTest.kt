// SPDX-License-Identifier: Apache-2.0
// KUN -->
package eu.kanade.tachiyomi.ui.more

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class NewUpdateViewModelTest {
    private val context: Context = mockk(relaxed = true)
    private val preferences: BasePreferences = mockk(relaxed = true)
    private val network: NetworkHelper = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var tempDir: File
    private lateinit var apkFile: File

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempDir = File.createTempFile("update_test", "").apply {
            delete()
            mkdirs()
        }
        apkFile = File(tempDir, "update.apk")
        every { context.externalCacheDir } returns tempDir
        every { context.packageName } returns "app.komikun.beta"
        every { context.packageManager } returns packageManager
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    @Test
    fun `when apk file does not exist stage is Available`() {
        val viewModel = NewUpdateViewModel(
            versionName = "r10695",
            changelogInfo = "Changelog",
            downloadLink = "https://example.com/update.apk",
            context = context,
            preferences = preferences,
            network = network,
        )
        viewModel.checkCachedApk()

        viewModel.state.value.stage shouldBe NewUpdateViewModel.Stage.Available
        viewModel.state.value.downloadProgress shouldBe 0
    }

    @Test
    fun `when cached apk is from older version it is deleted and stage is Available`() {
        apkFile.writeText("fake apk content")

        val stalePackageInfo = PackageInfo().apply {
            packageName = "app.komikun.beta"
            versionName = "1.14.1-10694"
        }
        @Suppress("DEPRECATION")
        every { packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0) } returns stalePackageInfo

        val viewModel = NewUpdateViewModel(
            versionName = "r10695",
            changelogInfo = "Changelog",
            downloadLink = "https://example.com/update.apk",
            context = context,
            preferences = preferences,
            network = network,
        )
        viewModel.checkCachedApk()

        viewModel.state.value.stage shouldBe NewUpdateViewModel.Stage.Available
        apkFile.exists() shouldBe false
    }

    @Test
    fun `when cached apk matches target version stage is Downloaded`() {
        apkFile.writeText("fake apk content")

        val matchingPackageInfo = PackageInfo().apply {
            packageName = "app.komikun.beta"
            versionName = "1.14.1-10695"
        }
        @Suppress("DEPRECATION")
        every { packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0) } returns matchingPackageInfo

        val viewModel = NewUpdateViewModel(
            versionName = "r10695",
            changelogInfo = "Changelog",
            downloadLink = "https://example.com/update.apk",
            context = context,
            preferences = preferences,
            network = network,
        )
        viewModel.checkCachedApk()

        viewModel.state.value.stage shouldBe NewUpdateViewModel.Stage.Downloaded
        viewModel.state.value.downloadProgress shouldBe 100
        apkFile.exists() shouldBe true
    }

    @Test
    fun `when apk is partially downloaded or corrupt checkCachedApk does not delete file`() {
        apkFile.writeText("partial download")

        // Incomplete APK: zip central directory cannot be read, package manager returns null
        @Suppress("DEPRECATION")
        every { packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0) } returns null

        val viewModel = NewUpdateViewModel(
            versionName = "r10695",
            changelogInfo = "Changelog",
            downloadLink = "https://example.com/update.apk",
            context = context,
            preferences = preferences,
            network = network,
        )
        viewModel.checkCachedApk()

        viewModel.state.value.stage shouldBe NewUpdateViewModel.Stage.Available
        // Must NOT delete the file because an active AppUpdateDownloadJob may be writing it
        apkFile.exists() shouldBe true
    }

    @Test
    fun `isMatchingUpdate does not match substrings of version count`() {
        val viewModel = NewUpdateViewModel(
            versionName = "r1069",
            changelogInfo = "Changelog",
            downloadLink = "https://example.com/update.apk",
            context = context,
            preferences = preferences,
            network = network,
        )

        val info = PackageInfo().apply {
            packageName = "app.komikun.beta"
            versionName = "1.14.1-10695"
        }

        viewModel.isMatchingUpdate(info, "r1069") shouldBe false
        viewModel.isMatchingUpdate(info, "r10695") shouldBe true
        viewModel.isMatchingUpdate(info, "10695") shouldBe true

        val releaseInfo = PackageInfo().apply {
            packageName = "app.komikun.beta"
            versionName = "1.14.1"
        }
        viewModel.isMatchingUpdate(releaseInfo, "v1.14.1") shouldBe true
        viewModel.isMatchingUpdate(releaseInfo, "1.14.1") shouldBe true
        viewModel.isMatchingUpdate(releaseInfo, "v1.14.0") shouldBe false

        val wrongPkgInfo = PackageInfo().apply {
            packageName = "other.app"
            versionName = "1.14.1"
        }
        viewModel.isMatchingUpdate(wrongPkgInfo, "v1.14.1") shouldBe false
    }

    @Test
    fun `when cached apk matches target version and matching digest stage is Downloaded`() {
        apkFile.writeText("valid apk content")
        val expectedSha256 = eu.kanade.tachiyomi.util.lang.Hash.sha256("valid apk content")

        val matchingPackageInfo = PackageInfo().apply {
            packageName = "app.komikun.beta"
            versionName = "1.14.1-10695"
        }
        @Suppress("DEPRECATION")
        every { packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0) } returns matchingPackageInfo

        val viewModel = NewUpdateViewModel(
            versionName = "r10695",
            changelogInfo = "Changelog",
            downloadLink = "https://example.com/update.apk",
            digest = "sha256:$expectedSha256",
            context = context,
            preferences = preferences,
            network = network,
        )
        viewModel.checkCachedApk()

        viewModel.state.value.stage shouldBe NewUpdateViewModel.Stage.Downloaded
        viewModel.state.value.downloadProgress shouldBe 100
        apkFile.exists() shouldBe true
    }

    @Test
    fun `when cached apk matches target version but digest mismatches it is deleted and stage is Available`() {
        apkFile.writeText("corrupted apk content")

        val matchingPackageInfo = PackageInfo().apply {
            packageName = "app.komikun.beta"
            versionName = "1.14.1-10695"
        }
        @Suppress("DEPRECATION")
        every { packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0) } returns matchingPackageInfo

        val viewModel = NewUpdateViewModel(
            versionName = "r10695",
            changelogInfo = "Changelog",
            downloadLink = "https://example.com/update.apk",
            digest = "sha256:0000000000000000000000000000000000000000000000000000000000000000",
            context = context,
            preferences = preferences,
            network = network,
        )
        viewModel.checkCachedApk()

        viewModel.state.value.stage shouldBe NewUpdateViewModel.Stage.Available
        apkFile.exists() shouldBe false
    }

    @Test
    fun `verifyDownloadedApk validates both checksum and package info`() {
        apkFile.writeText("sample apk")
        val validHash = eu.kanade.tachiyomi.util.lang.Hash.sha256("sample apk")

        val matchingPackageInfo = PackageInfo().apply {
            packageName = "app.komikun.beta"
            versionName = "1.14.1-10695"
        }
        @Suppress("DEPRECATION")
        every { packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0) } returns matchingPackageInfo

        val viewModelWithMatchingDigest = NewUpdateViewModel(
            versionName = "r10695",
            changelogInfo = "Changelog",
            downloadLink = "https://example.com/update.apk",
            digest = "sha256:$validHash",
            context = context,
            preferences = preferences,
            network = network,
        )
        viewModelWithMatchingDigest.verifyDownloadedApk(apkFile) shouldBe true

        val viewModelWithMismatchingDigest = NewUpdateViewModel(
            versionName = "r10695",
            changelogInfo = "Changelog",
            downloadLink = "https://example.com/update.apk",
            digest = "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            context = context,
            preferences = preferences,
            network = network,
        )
        viewModelWithMismatchingDigest.verifyDownloadedApk(apkFile) shouldBe false
    }
}
// KUN <--
