// KUN -->
package tachiyomi.domain.history.service

/**
 * Whether a local history removal can currently reach a server.
 *
 * Only the SyncYomi v2 deletions request consumes the queue (`history.pending_deletion`), so a
 * removal on a device whose sync is off, belongs to another service, or points at a host that does
 * not speak v2 has to reset the row *without* queueing it. A queued row nothing will ever send is
 * not merely useless: a restore payload skips it, "Clear database" keeps it, and a remote chapter
 * tombstone is held back because the chapter still carries a history entry.
 *
 * The answer is the same one the retired `rememberDeletedHistory` used: SyncYomi selected, its host
 * confirmed to speak v2, and the history section enabled.
 */
fun interface HistoryTombstoneDelivery {
    fun canDeliver(): Boolean
}
// KUN <--
