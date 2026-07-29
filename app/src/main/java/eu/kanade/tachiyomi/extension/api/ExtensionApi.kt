package eu.kanade.tachiyomi.extension.api

import android.content.Context
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
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

    suspend fun checkForUpdates(context: Context) {
        updateExtensionStores()

        val extensions = findExtensions()

        // SY -->
        val blacklistEnabled = sourcePreferences.enableSourceBlacklist().get()
        // SY <--

        val installedExtensions = ExtensionLoader.loadExtensions(context)
            .filterIsInstance<LoadResult.Success>()
            .map { it.extension }
            // SY -->
            .filterNot { it.isBlacklisted(blacklistEnabled) }
        // SY <--

        val extensionsWithUpdate = mutableListOf<Extension.Installed>()
        for (installedExt in installedExtensions) {
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
