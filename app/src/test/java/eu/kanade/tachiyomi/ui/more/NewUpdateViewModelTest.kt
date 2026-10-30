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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class NewUpdateViewModelTest {
    private val context: Context = mockk(relaxed = true)
    private val preferences: BasePreferences = mockk(relaxed = true)
    private val network: NetworkHelper = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)

    private lateinit var tempDir: File
    private lateinit var apkFile: File

    @BeforeEach
    fun setUp() {
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

        viewModel.state.value.stage shouldBe NewUpdateViewModel.Stage.Downloaded
        viewModel.state.value.downloadProgress shouldBe 100
        apkFile.exists() shouldBe true
    }
}
// KUN <--
