# Upstream state

Where the upstream work stands, what is open, and what is deliberately not coming. Resumable state, not
history: update it when the position or the outstanding item changes, and keep
[`cherrypick_log.md`](../../cherrypick_log.md) as the per-commit table.

## 1. Position

Verified against the remotes on 2026-10-28, before PR #80 was merged:

| Ref | Commit | Committer date |
|---|---|---|
| fork `master` | `45c51d699f` | 2026-10-25 |
| `mihon/main` | `e588e04359` | 2026-10-26 |
| `tachiyomiSY/master` | `9955747216` | 2026-10-27 |

The fork was about **one day** behind `mihon/main`, not the two-year backlog the log's blank rows
suggest. Those rows are blank because ports land as **squash merges** — no `cherry picked from commit`
trailer — and because `mihon/main` is not an ancestor of `master` (vendored history), so neither
detector fires. Adjudicate presence by content, per "Verify presence by content, not by the log" in the
`upstream-port` skill; never from the Status column.

## 2. PR #80 — `chore/upstream-port-2026-10`

The sync that closed the gap: the Mihon and SY commits since 2024-01-01 that a content audit found
genuinely missing, the Weblate translation commits in that window, and the fixes the port needed to stay
coherent (chapter-cache TTL rewrite, duplicate-download cleanup, updater and telemetry fixes, the merge
and backup repair). `ui/reader/**` is deliberately excluded and left to the reader stream below.

- Migrations: `59.sqm` (SY's `library_removal` table, renumbered from SY's `48.sqm`), `60.sqm` (KUN:
  the library/updates feed views dedupe a merged entry's chapters by its chapter-sort mode), `61.sqm`
  (KUN: split `merged` into `merge` + `merged_entry`).
- Backup: `BackupManga.mergeSettings` = `@ProtoNumber(10002)` (upstream's `604` is claimed by an open SY
  PR for a different wire type), see `AGENTS.md` → "Backup protobuf field numbers".
- Gate: `./gradlew testDebugUnitTest` green — 530 tests across 63 suites, 0 failures, 1 skipped — plus
  the CI `spotlessCheck` / `assembleNightly` steps.

## 3. Outstanding: the reader-through-Compose rewrite

Mihon's reader re-architecture — `00335b2a70` "Draw the reader through Compose" (#4191),
`9079ad8c9a` "Handle reader input and state in the activity" (#4192), `e588e04359` "Share the reader's
page chrome and page logic between viewers" (#4193), on top of `8124d324a1` (config listeners →
settings view model state) and `ca028cb657` (viewer out of the ViewModel) — is **not on `master`** and
cannot be cherry-picked as a block. It is a re-architecture of `ui/reader/**`, so it is being ported in
steps on its own branch.

- Branch: `feat/reader-compose`, based on `7d4be1b84c`.
- `docs/reader/feature-inventory.md`: the 55-capability `R1`–`R55` checklist every step must preserve.
  **The IDs are stable — never renumber, reorder or reuse one; add `R56` instead.**
- Landed:
  - `719365dc88` — the inventory itself.
  - Step 1, `ca028cb657` → `9bd2f451b7` (the viewer is owned by the activity, no longer by the
    ViewModel).
  - Step 2, `8124d324a1` → `86f62b3ebb` (phase A: the settings plane) + `db1e475143` (phase B: every
    consumer migrated, `ViewerConfig`, `PagerConfig`, `WebtoonConfig` and `WebGpuConfig` deleted), plus
    the fixes `0e1909e127` and `3e7ee19608`.
- `docs/reader/step2-settings-plane.md` records what happened to the fork's 28 fork-only reader knobs
  (18 homed in the new settings objects, 10 stay on `ReaderPreferences`) and what is deferred: R10 →
  step 4, R32 → step 5, R19/R20 → step 6.
- **Next: step 3 — `00335b2a70`** (Compose reader + `ViewerNavigation` value objects). After that:
  step 4, the dual-page engine on the new `PagerItems`; step 5, `9079ad8c9a` (input + autoscroll API);
  step 6, seed-colour theming, WebGPU settings and the zoom cluster.
- Rebase contract once PR #80 is merged, so that the reader branch sits on the new `master` without
  replaying the port commits underneath it:

  ```bash
  git rebase --onto master 7d4be1b84c feat/reader-compose
  ```

## 4. Rows judged inapplicable — do not re-litigate

Each already carries an `AI checked` note in `cherrypick_log.md`; the reasons are:

- `c67a33f3d4` (mihon, "Remove unused sync scaffold"): every hunk touches columns the fork's SyncYomi
  path reads, so the "unused" scaffold is live here and the commit is inseparable from SyncYomi.
- `9410a1b019`, `8ecbb0f311` (tachiyomiSY, marker-convention docs): SY's own `// SY` prose. This fork
  uses `// KUN` / `// KMK` / `// SY`, so porting it would document the wrong convention.
- `e7dc95dba1` (tachiyomiSY, "Fix possible foreign key errors in migration"): it patches SY's
  `merged_new` / `search_metadata_new` rebuild, tables this schema never had — `43.sqm` here is the
  unrelated `updatesView` change, and the delete-orphans intent already exists in `49.sqm`.

## 5. `library_removal` is redundant — the fork's removal sync came first

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
`OR id IN (SELECT manga_id FROM library_removal …)` terms (`manga.sq:223`, `:238`). Both triggers share
the same `is_syncing = 0` guard, and the tombstone's `old.user_favorite_at IS NOT NULL AND
new.user_favorite_at IS NULL` is a strict subset of `update_last_favorited_at_mangas`'s
`new.user_favorite_at IS NOT old.user_favorite_at`, so every row the tombstone names is already
stamped. The table ships unreleased in `59.sqm` and is kept for SY parity, not because anything
depends on it.

**Latent trap.** If the fork ever ports SY `c54092f4b3` / `570974114c`, which drop `favorite_modified_at`,
removals silently stop being uploaded on a full sync unless the tombstone is promoted to the primary
mechanism in the same change. Do not port those commits standalone.
