package eu.kanade.tachiyomi.extension.api

import android.content.Context
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.model.Extension
import exh.source.BlacklistedSources
import exh.source.ExhPreferences
import mihon.domain.extension.interactor.UpdateExtensionStores
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

internal class ExtensionApi {

    private val repository: ExtensionStoreRepository by injectLazy()

    private val updateExtensionStores: UpdateExtensionStores by injectLazy()

    // SY -->
    private val sourcePreferences: SourcePreferences by injectLazy()
    // SY <--

    suspend fun findExtensions(): List<Extension.Available> {
        // KMK -->
        val disabledRepos = sourcePreferences.disabledRepos().get()
        // KMK <--
        return withIOContext {
            repository.fetchExtensions(
                // KMK -->
                disabledRepos,
                // KMK <--
            )
        }
    }

    /**
     * @param loadedExtensions Extensions already loaded by [eu.kanade.tachiyomi.extension.ExtensionManager].
     * Only their versions are read, so there's nothing to gain from loading them a second time.
     */
    suspend fun checkForUpdates(context: Context, loadedExtensions: List<Extension.Loaded>) {
        updateExtensionStores()

        val extensions = findExtensions()

        // SY -->
        val blacklistEnabled = sourcePreferences.enableSourceBlacklist().get()
        val filteredExtensions = loadedExtensions.filterNot { it.isBlacklisted(blacklistEnabled) }
        // SY <--

        val extensionsWithUpdate = mutableListOf<Extension.Loaded>()
        for (installedExt in filteredExtensions) {
            val pkgName = installedExt.pkgName
            val availableExt = extensions.find { it.pkgName == pkgName } ?: continue
            val hasUpdatedVer = availableExt.versionCode > installedExt.versionCode
            val hasUpdatedLib = availableExt.libVersion > installedExt.libVersion
            val hasUpdate = hasUpdatedVer || hasUpdatedLib
            if (hasUpdate) {
                extensionsWithUpdate.add(installedExt)
            }
        }

        if (extensionsWithUpdate.isNotEmpty()) {
            ExtensionUpdateNotifier(context).promptUpdates(extensionsWithUpdate.map { it.name })
        }
    }

    // SY -->
    private fun Extension.isBlacklisted(
        blacklistEnabled: Boolean = sourcePreferences.enableSourceBlacklist().get(),
        // KMK -->
        isHentaiEnabled: Boolean = Injekt.get<ExhPreferences>().isHentaiEnabled().get(),
        // KMK <--
    ): Boolean {
        return pkgName in BlacklistedSources.BLACKLISTED_EXTENSIONS &&
            blacklistEnabled &&
            // KMK -->
            isHentaiEnabled
        // KMK <--
    }
    // SY <--
}
