# Upstream state

Where the upstream work stands, what is open, and what is deliberately not coming. Resumable state, not
history: update it when the position or the outstanding item changes, and keep
[`cherrypick_log.md`](../../cherrypick_log.md) as the per-commit table.

## 1. Position

Verified against the remotes on 2026-11-28:

| Ref | Commit | Committer date |
|---|---|---|
| fork `master` | `74ab61d574` (PR #115) | 2026-11-24 |
| `mihon/main` | `f8e30a8608` | 2026-11-22 |
| `tachiyomiSY/master` | `5c77cad303` | 2026-11-13 |
| `komikku/master` | `15610e67e4` | 2026-11-27 |

The work in §2 is the branch `chore/upstream-sync-2026-11`, based on `master` `74ab61d574`. Its Mihon rows
are **hand-marked** in the log (the window is not in `master` yet, so the script cannot see the picks), and
`in history as mihon` cannot fire either way, because `mihon/main` is not an ancestor of `master` (vendored
history). What does fire for a merged port is `hash of`: its `(cherry picked from commit <sha>)` trailer
reaches `master` when the port message carried it. The base-forward Komikku rows are not in the log at all
— Komikku's `since` moved to the new base commit's date when this sync was prepared (§2). A blank row is
therefore never evidence that a change is missing — adjudicate presence by content, per "Verify presence by
content, not by the log" in the `upstream-port` skill, and never from the Status column alone.

## 2. The 2026-11-27 window — `chore/upstream-sync-2026-11`

Against `master` `74ab61d574`: the Komikku base forward to `15610e67e4`, the Mihon picks the fork
wanted, and the dependency landing. **70 commits of sync work, no merge commit** — 51 base-forward
picks, 3 Mihon picks (4 commits), 7 dependency commits and 8 fork-only commits that adapt the result —
plus the documentation change that records it, all on `chore/upstream-sync-2026-11`, which is not in
`master` yet.

- Komikku base forward: **cherry-picked, not merged** (`15610e67e4` is not an ancestor of the branch), so
  each of the 51 keeps its `(cherry picked from commit <sha>)` trailer. 36 name a Komikku commit first, 3
  a TachiyomiSY one, and 12 a Mihon one because the Komikku commit they came from was itself a pick of
  that Mihon commit — the Weblate translation series, `mihonapp/mihon#3497`/`#325`, the `navigationevent`
  fix and the WebView spoofing rework among them.
- Mihon picks: `994a095397` → `992ba736d5` (the private extension uninstall prompt, matched to Android
  17's), `f64d82b942` → `a56f39e8e0` (the shared `WebViewScreen` hosted by `WebViewActivity`) and
  `c0d3aeaeb5` — the **Navigation 3 port** — → `b71eda1d3a` (the `:core:navigation` module and the
  `androidx.navigation3` catalog) plus `7c99da2b04` (upstream's Voyager removal, applied by hand: the
  intermediate series `mihon-pr/3984` was not used). `androidx.navigation3` is pinned at 1.2.2
  (`c12c31031d`).
- Navigation 3 in the fork: `presentation/util/Navigator.kt` (`LocalBackPress`, `interface Tab`,
  `abstract class Screen`, `AssistContentScreen`), `ui/setting/SettingsScreen.kt` and
  `ScreenSerializationTest` are deleted and the Voyager dependency is gone from the catalog.
  `:core:navigation` (`mihon.core.navigation`) holds the wrapper, upstream's routes (`AppRoutes.kt`) and
  the two scene strategies; `app/src/main/java/mihon/core/navigation/` holds the fork's routes
  (`KunRoutes.kt`) and the single entry registry (`AppNavGraph.appEntries()`). The dictionary tab is
  `TopLevelRoute.Dictionary`, decided by `DictionaryTabNavigationPolicy`, and is no longer a `Tab` — as
  `AGENTS.md` now documents.
- Dependencies: one commit each for Gradle 9.9.2 (`885aa72dc5`), spotless 8.12.0 (`2be219bee6`), AGP
  9.5.2 (`f8e30a8608`), metro 1.6.0 (`f42d08a23e`), lifecycle 2.11.1 (`a608ed2dde`),
  `compose-bom` switched off the beta train to the stable 2026.11.01 (`1ed58f5e27`) and
  `actions/dependency-review-action@v5.0.1` (`845f662a3e`); `navigation3` 1.2.2 (`c12c31031d`) rides in
  the Nav3 port. All eight are Renovate rows on `mihon/main`, so `config.json`'s identity filter keeps
  them out of `cherrypick_log.md`.
- Fork-only commits (no trailer): `f17af5321c` and `eb7996d436` (adapt the ported code and tests to the
  fork's APIs), `755e504fd7` and `b06a38ab30` (spotless passes), `680d954dca` (drop the duplicate
  `navigationevent` catalog entry), `0e3226f0aa` and `0408eafd7c` (make the fork's remaining settings
  routes serializable, keep the updates/history nav-button preferences), and `0da59f17d4` (document the
  new architecture in `AGENTS.md`).

**The earlier window has landed.** `chore/upstream-window-and-deps` (based on `9624dc3187`) and the
stacked `agent/stacked-deferrals` (`eea356c883`) exist only as squashes in `master`: the 2026-11-18
window is `fd572f012a` (PR #102), and the three rows it deferred landed in `29d651b880` (PR #105) —
`9c955b5b1e`'s `ExtensionMatching` half (`domain/.../extension/model/ExtensionMatching.kt` and its test),
`9079ad8c9a`'s activity-owned reader input (`ReaderActivity.moveForward()`/`moveBackward()`) and
`e588e04359`'s `PagerItems` page logic. The stacked branch's own commits `161196d446`, `14046736ef` and
`219fcf5bbf` are **not** ancestors of `master`, so those rows are `O` in `cherrypick_log.md` by content,
not by ancestry, and nothing in them is reopened here.

## 3. PR #80 — `chore/upstream-port-2026-10` (landed)

The sync that closed the two-year gap into `85d7d1fade`: the Mihon and SY commits since 2024-01-01 that a
content audit found genuinely missing, the Weblate translation commits in that window, and the fixes the port
needed to stay coherent (chapter-cache TTL rewrite, duplicate-download cleanup, updater and telemetry fixes,
the merge and backup repair). `ui/reader/**` was deliberately excluded and left to the reader stream, which
landed later as `29d651b880` (PR #105, §2).

- Migrations: `59.sqm` (SY's `library_removal` table, renumbered from SY's `48.sqm`), `60.sqm` (KUN:
  the library/updates feed views dedupe a merged entry's chapters by its chapter-sort mode), `61.sqm`
  (KUN: split `merged` into `merge` + `merged_entry`).
- Backup: `BackupManga.mergeSettings` = `@ProtoNumber(10002)` (upstream's `604` is claimed by an open SY
  PR for a different wire type), see `AGENTS.md` → "Backup protobuf field numbers".
- Gate: `./gradlew testDebugUnitTest` green — 530 tests across 63 suites, 0 failures, 1 skipped — plus
  the CI `spotlessCheck` / `assembleNightly` steps.

## 4. Open work

- **Injekt removal** — `524fa53043` (Mihon #4179) is **deferred 2026-11-18**: 53 `.kt` files outside
  `source-api/` still import `uy.kohesive.injekt` through the interop bridge in `mihon/app/di/injekt/` (5
  more under `source-api/` itself), so the commit's premise does not hold in this tree yet.
- **Dependency rows still behind `mihon/main`.** Every one of them is Renovate-authored, so `config.json`'s
  identity filter keeps them out of the log — they have to be read from the upstream catalogs, not from
  `cherrypick_log.md`: `firebase-bom` 34.15.0 → 35.2.0, `google-services` 4.5.0 → 4.6.0,
  `conscrypt-android` 2.5.3 → 2.9.0, `jsoup` 1.23.2 → 1.24.1 and `androidx-webkit` 1.16.0 → 1.18.0.
  Everything the previous window listed as behind has since landed: Gradle 9.9.2, spotless 8.12.0, AGP
  9.5.2, metro 1.6.0, lifecycle 2.11.1, `compose-bom` 2026.11.01 and `navigation3` 1.2.2 are all level
  with `mihon/main` `f8e30a8608`.
- **webgpuviewer fork AAR.** `32eb14c763`, `ede052af17`, `70c73c728b` and the two `imagedecoder` bumps are not
  picks: the fork vendors its own `komi-kun/webgpuviewer` AAR in `maven/` and tracks it through the
  `immersion-deps-sync` skill, so Maven bumps upstream do not apply.

## 5. Rows judged inapplicable — do not re-litigate

Each already carries a note in `cherrypick_log.md`; the reasons are:

- `c67a33f3d4` (mihon, "Remove unused sync scaffold"): every hunk touches columns the fork's SyncYomi
  path reads, so the "unused" scaffold is live here and the commit is inseparable from SyncYomi.
- `9410a1b019`, `8ecbb0f311` (tachiyomiSY, marker-convention docs): SY's own `// SY` prose. This fork
  uses `// KUN` / `// KMK` / `// SY`, so porting it would document the wrong convention.
- `e7dc95dba1` (tachiyomiSY, "Fix possible foreign key errors in migration"): it patches SY's
  `merged_new` / `search_metadata_new` rebuild, tables this schema never had — `43.sqm` here is the
  unrelated `updatesView` change, and the delete-orphans intent already exists in `49.sqm`.
- `cfeccaf08b` (mihon, "Remove unused code, resources and strings"): the symbols it deletes are live fork
  code — `NotificationReceiver` (13 Kotlin files), `WebGpuViewer`, `AppUpdateNotifier`, `DotSeparatorText`,
  `TachiyomiTextInputEditText` — so the removal does not apply. The 2026-10-28 "already present" verdict on
  this row was a false negative.
- `f8334d644e`, `a8ed0a7194` (tachiyomiSY crash guards): the fork has no `runBlocking` path and resolves
  `appGraph` lazily, so both crash paths are absent here.
- `df84ab89ee` ("Fix build"), `df041f0a04` ("Lint"): superseded — the fork already streams `decodeEntries`
  three times and carries the equivalent lint cleanup.
- `4df41cef63` (mihon, "Add agent instructions"): upstream's own `AGENTS.md`, not this repo's guide.
- `4b879d1ceb`, `007ec9a868`: release bookkeeping (`versionCode` bump, `v0.21.3`). The fork pins
  `versionCode` 83 and ships nightlies, so neither is a pick.
- `7a5632c013` and `e8977b4a66` ("Keep tab badges visible…" and its revert): the pair cancels out, and the
  fork keeps the re-applied version that landed as `b19c8abeba` / `2e3ea2a3f5`.

## 6. `library_removal` is redundant — the fork's removal sync came first

The fork already synced library removals and un-read / lower-progress chapters before SY's
`78e6a65248` was ported: the owner built it in `5d0a67227d` (2026-10-14), `3991bd8537` (#43),
`c96b78491f` (#51, `data/src/test/java/tachiyomi/data/SyncRepositoryFavoriteTimestampTest.kt` —
`a removed favorite is still uploaded`) and `a52730100f` (#53). The mechanism is `favorite_modified_at`,
stamped on unfavorite and **never reset**, so a removed entry stays visible to the upload queries
(`getMangasToSync`, `getMangasModifiedSince`) unconditionally.

What `78e6a65248` genuinely added, and what to keep:

- the NULL-safe `IS NOT` comparisons for `remote_url` / `remote_description` in the
  `update_manga_version` trigger (`59.sqm:32-33`);
- history-only change detection in `needsRestore` (`app/src/main/java/eu/kanade/tachiyomi/data/sync/SyncDelta.kt:350`,
  `remote.history.any { … }`).

What it added that is redundant today: the `library_removal` table and the two
`OR id IN (SELECT manga_id FROM library_removal …)` terms
(`data/src/main/sqldelight/tachiyomi/data/manga.sq:235` in `getMangasToSync`, `:250` in
`getMangasModifiedSince`). Both triggers share the same `is_syncing = 0` guard, and the tombstone's
`old.user_favorite_at IS NOT NULL AND new.user_favorite_at IS NULL` is a strict subset of
`update_last_favorited_at_mangas`'s `new.user_favorite_at IS NOT old.user_favorite_at`, so every row the
tombstone names is already stamped. The table ships unreleased in `59.sqm` and is kept for SY parity, not
because anything depends on it.

**Latent trap.** If the fork ever ports SY `c54092f4b3` / `570974114c`, which drop `favorite_modified_at`,
removals silently stop being uploaded on a full sync unless the tombstone is promoted to the primary
mechanism in the same change. Do not port those commits standalone.
