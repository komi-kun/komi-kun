package eu.kanade.tachiyomi.extension.model

/**
 * Identifies one extension line: a package signed by one key.
 *
 * Several stores can publish the same package name (e.g. a fork and the store it builds from), so the
 * package name alone does not say which store a listing belongs to.
 *
 * Only a listing has one key: an installed extension carries every signature its apk has, and each of them is
 * a line of its own, so it's compared with a listing through [isSameExtensionAs] instead.
 */
val Extension.Available.identityKey: String
    get() = "${pkgName}_${store.signingKey}"

/**
 * Whether [other] is the same package, signed by a key this installed extension is signed with, so its apk can
 * replace this one.
 */
fun Extension.Installed.isSameExtensionAs(other: Extension.Available): Boolean =
    pkgName == other.pkgName && other.store.signingKey in signatures

/** Orders available extensions by version code, then by extension lib version. */
val AvailableExtensionVersionComparator: Comparator<Extension.Available> =
    compareBy<Extension.Available> { it.versionCode }.thenBy { it.libVersion }

/**
 * Returns the newest of [available] that can update this installed extension (same package name, signed by a
 * key it is signed with), or null if no store lists it.
 */
fun Extension.Installed.findAvailable(available: Iterable<Extension.Available>): Extension.Available? =
    available
        .filter { isSameExtensionAs(it) }
        .maxWithOrNull(AvailableExtensionVersionComparator)

/** Whether [available] has a newer version or a newer extension lib than this installed extension. */
fun Extension.Installed.isOutdatedComparedTo(available: Extension.Available): Boolean {
    // An extension that was never loaded has no lib version to compare against
    val installedLibVersion = libVersion
    return available.versionCode > versionCode ||
        (installedLibVersion != null && available.libVersion > installedLibVersion)
}
