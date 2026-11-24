# Upstream state

Where the upstream work stands, what is open, and what is deliberately not coming. Resumable state, not
history: update it when the position or the outstanding item changes, and keep
[`cherrypick_log.md`](../../cherrypick_log.md) as the per-commit table.

## 1. Position

Verified against the remotes on 2026-11-18, before the window in §2 is merged:

| Ref | Commit | Committer date |
|---|---|---|
| fork `master` | `85d7d1fade` (PR #80) | 2026-10-28 |
| `mihon/main` | `4823ba69ca` | 2026-11-14 |
| `tachiyomiSY/master` | `5c77cad303` | 2026-11-13 |
| `komikku/master` | `290267dd4c` | 2026-11-16 |

The window itself is the branch `chore/upstream-window-and-deps`, based on `9624dc3187`. Its rows are
**hand-marked** in the log (the window is not in `master` yet, so the script cannot see the picks): a port's
`(cherry picked from commit <sha>)` trailer does reach `master` when the port message carried it — 67 of the 77
commits above the Komikku base have one, so `hash of` fires — and what cannot fire is `in history as mihon`,
because `mihon/main` is not an ancestor of `master` (vendored history). A blank row is therefore never
evidence that a change is missing — adjudicate presence by content, per "Verify presence by content, not by
the log" in the `upstream-port` skill, and never from the Status column alone.

## 2. The 2026-11-18 window — `chore/upstream-window-and-deps`

Upstream commits since 2026-10-24 (`mihon` `e588e04359`→`4823ba69ca`, `tachiyomiSY`
`9955747216`→`5c77cad303`) plus Komikku since its config `since` 2026-10-08 (`1656575e8f`→`290267dd4c`),
adjudicated row by row: **13 ported, 5 already-present rows closed, 13 won't-pick (§5), 3 deferred, all
three since landed on the stacked branch (last paragraph of this section)**.

- Mihon: `629033c804` → `c98f9b3cac` (lint: read the locale observably, `AppCompatButton`; its splash
  resource hunk is omitted deliberately — the fork keeps its own themed splash) and `002a5fba27` →
  `b95508035f` (scrollbars on Compose's scroll-indicator APIs, with the KUN drag label re-applied).
- TachiyomiSY: `9926c3131a` → `d1c8355dce` (Weblate #1684), `90f9bfcb64` → `bfd842b602` (page preview keyed
  by a stable identity), `c97ffb6025` → `5396eb148e` (synced category positions), `24ac64049a` →
  `f1a6c41c58` (E-Hentai tag lists), `4ba3a6af5b` → `3db898e5e4` (MangaDex logout headers; the login half
  was already in the tree), `e073ef6d84` → `e5e6abf58e` (empty ckb locale files end with a newline) and
  `5c77cad303` → `1d2287a75b` (split/double-pages exclusivity decided at toggle time).
- Komikku: `a1166f3c23` → `375bf1da65` (custom smart-update intervals in every build type), `07f6b434e8` →
  `cac8a27724` (global search keeps its sources, recommendations stop spinning), `9c955b5b1e` →
  `4bb1137fdb` (stop attributing other stores' updates to installed extensions; the `ExtensionMatching` half
  followed on the stacked branch, below) and `383ffaa816` → `b5087846d2` (partial-pick detection and the newer
  `mark` options in `.agents/skills/cherrypick-log/`).
- Already present, now recorded: `c1eb1e3cbf`, `290267dd4c`, `6e218618d2`, `8d091eaebc` and the reader series'
  `ca028cb657`, `8124d324a1`, `00335b2a70` (PR #81).
- Dependencies: the window's catalog work landed as one series — Gradle 9.9.1, `compose-bom` 2026.11.00,
  `metro` 1.5.2, `okhttp` 5.5.1, `sqlcipher` 4.20.0, `kotlinx-serialization` 1.13.0, `libarchive` 1.1.7,
  `mockk` 1.14.12, `kotest-assertions` 6.2.7, `uiautomator` 2.4.1. What is still behind is in §4.

**The three deferred rows landed on the stacked branch `agent/stacked-deferrals`** — three commits on
`eea356c883`, the tip of `chore/upstream-window-and-deps`, so it merges after that PR:

- `9c955b5b1e` → `161196d446`: the `ExtensionMatching` half, on the fork's model — `isSameExtensionAs` is
  `pkgName == other.pkgName && other.store.signingKey in signatures`, because the fork has neither
  `Extension.signatureHash` nor `Installed.storeName`; its test comes with it, so every applicable file of
  that commit is in and the row is `O`.
- `9079ad8c9a` → `14046736ef`: key dispatch and tap handling move into `ReaderActivity`, and the four
  navigation directions collapse into `moveForward()`/`moveBackward()`. The KUN `VolumeKeyPagingPolicy`
  OCR-overlay exemption and the KMK pan-before-turn are re-applied on the new structure, the latter as
  `Action.PAN_UP`/`PAN_DOWN` emitted only from the vertical keys and the mouse wheel.
- `e588e04359` → `219fcf5bbf`: `PagerItems`/`PagerPageTracker` replace the holders' own page lists and the
  page chrome leaves the holders for `ReaderError`/`ReaderPageError`/`ReaderTransitionPage`/
  `ReaderThemedContent`/`TransitionDownloadState`/`PageImageProcessing`, so `ReaderButton` and
  `res/layout/reader_error.xml` go. The KUN OCR display-transform bookkeeping survives as a hook on
  `processPagedImage`/`processStripImage`, and the SY dual-page engine as a fork-only `PagerJoinedItems`
  layered over the flat `PagerItems`.

## 3. PR #80 — `chore/upstream-port-2026-10` (landed)

The sync that closed the two-year gap into `85d7d1fade`: the Mihon and SY commits since 2024-01-01 that a
content audit found genuinely missing, the Weblate translation commits in that window, and the fixes the port
needed to stay coherent (chapter-cache TTL rewrite, duplicate-download cleanup, updater and telemetry fixes,
the merge and backup repair). `ui/reader/**` was deliberately excluded and left to the reader stream in §2.

- Migrations: `59.sqm` (SY's `library_removal` table, renumbered from SY's `48.sqm`), `60.sqm` (KUN:
  the library/updates feed views dedupe a merged entry's chapters by its chapter-sort mode), `61.sqm`
  (KUN: split `merged` into `merge` + `merged_entry`).
- Backup: `BackupManga.mergeSettings` = `@ProtoNumber(10002)` (upstream's `604` is claimed by an open SY
  PR for a different wire type), see `AGENTS.md` → "Backup protobuf field numbers".
- Gate: `./gradlew testDebugUnitTest` green — 530 tests across 63 suites, 0 failures, 1 skipped — plus
  the CI `spotlessCheck` / `assembleNightly` steps.

## 4. Open work

- **Injekt removal** — `524fa53043` (Mihon #4179) is **deferred 2026-11-18**: 50 `.kt` files outside
  `source-api/` still import `uy.kohesive.injekt` through the interop bridge in `mihon/app/di/injekt/`, so
  the commit's premise does not hold in this tree yet.
- **Dependency rows still behind `mihon/main`.** Every one of them is Renovate-authored, so `config.json`'s
  identity filter keeps them out of the log (26 such commits on `mihon/main` and 7 on `tachiyomiSY/master`
  in this window; they have to be read from the upstream catalogs, not from `cherrypick_log.md`):
  `spotless` 8.8.0 → 8.11.0, `firebase-bom` 34.15.0 → 35.2.0, `google-services` 4.5.0 → 4.6.0,
  `conscrypt-android` 2.5.3 → 2.9.0, `jsoup` 1.23.2 → 1.24.1, `androidx-webkit` 1.16.0 → 1.18.0 and AGP
  9.4.1 → 9.5.1. `metro` is 1.5.2 here, level with `mihon/main`; the 1.6.0 bump this window expected is not
  on any upstream branch yet, so there is nothing to pick for it.
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
- history-only change detection in `needsRestore` (`SyncDelta.kt:287`, `remote.history.any { … }`).

What it added that is redundant today: the `library_removal` table and the two
`OR id IN (SELECT manga_id FROM library_removal …)` terms (`manga.sq:223`, `:238`). Both triggers share the
same `is_syncing = 0` guard, and the tombstone's `old.user_favorite_at IS NOT NULL AND
new.user_favorite_at IS NULL` is a strict subset of `update_last_favorited_at_mangas`'s
`new.user_favorite_at IS NOT old.user_favorite_at`, so every row the tombstone names is already
stamped. The table ships unreleased in `59.sqm` and is kept for SY parity, not because anything
depends on it.

**Latent trap.** If the fork ever ports SY `c54092f4b3` / `570974114c`, which drop `favorite_modified_at`,
removals silently stop being uploaded on a full sync unless the tombstone is promoted to the primary
mechanism in the same change. Do not port those commits standalone.
