# komi-kun — personal fork of Komikku

This repository is a **personal vendoring fork of [Komikku](../AGENTS.md)**. It intends to stay a
superset of Komikku while also tracking two further upstreams. This file documents the upstreams,
the provenance markers, and the workflow that keeps future porting cheap. `AGENTS.md` is still the
mandatory rulebook for code style, markers and i18n — read it first; this file only adds the fork's
upstream topology.

## Upstreams

| Role | Remote | Branch | What we take |
|------|--------|--------|--------------|
| Base / upstream of this fork | `origin` (`komi-kun/komi-kun`) | `master` | Everything. This fork starts from a Komikku commit. |
| Additional upstream (primary) | `mihon` (`mihonapp/mihon`) | `main` | Bug fixes and features, cherry-picked individually. |
| Additional upstream (secondary) | `tachiyomiSY` (`jobobby04/TachiyomiSY`) | `master` | Sync and SY features the fork wants, cherry-picked individually. |

```bash
git fetch origin mihon tachiyomiSY
```

The upstreams are configured in `.claude/skills/cherrypick-log/config.json`; the
[`cherrypick-log`](.claude/skills/cherrypick-log/README.md) skill/script is the source of truth for
what has and has not been ported (`cherrypick_log.md`).

## Provenance markers

Markers are how we can still diff our tree against an upstream later. Rules (extending `AGENTS.md`):

| Code origin | Marker |
|-------------|--------|
| Komikku upstream and this fork's own code | `// KMK -->` … `// KMK <--` |
| Code ported from TachiyomiSY | `// SY -->` … `// SY <--` |
| Code ported from Mihon | no block marker — keep the commit's `(cherry picked from commit <sha>)` trailer |
| Changes *we* make inside a ported block | nest a `// KMK -->` … `// KMK <--` block inside it |

- Never add new strings to a non-`base` locale; new strings go to
  `i18n-kmk/.../base/strings.xml` and are referenced via `KMR` (`AGENTS.md`).
- Markers also apply inside `.sq` / `.sqm` files (use `-- KMK -->` / `-- KMK <--`).
- A pure cherry-pick needs no marker: the trailing `(cherry picked from commit <sha>)` is the
  provenance, and `update_cherrypick_log.py` detects it as `O`.

## Porting workflow (Mihon / TachiyomiSY → this fork)

1. `python3 .claude/skills/cherrypick-log/update_cherrypick_log.py` — refresh the log.
2. Review the `?` rows (and, when asked, the empty ones) with the `cherrypick-log` skill; mark
   verdicts through the script (`mark`), never by hand.
3. Port on a **feature branch**, one upstream sync per branch/PR:
   `git checkout -b chore/upstream-<what> master`.
4. Prefer `git cherry-pick -x <sha>` in **chronological order** — earlier commits in a series often
   unblock later ones. Resolve conflicts by adapting to this fork, never by reverting Komikku
   features. When a port needs local changes, keep the `(cherry picked from …)` trailer and wrap the
   local delta in `// KMK` markers.
5. **Prefer the TachiyomiSY copy of a Mihon commit** when one exists (the log shows them as a
   `↳ tachiyomiSY` row): Komikku descends from SY, so the SY-adapted version conflicts far less.
6. After each batch: `./gradlew spotlessApply spotlessCheck :app:compileDebugKotlin`, then the unit
   tests (`:app:testDebugUnitTest`, `:domain:testReleaseUnitTest`) where relevant.
7. For SQL changes append a **new** migration at the next number
   (`data/src/main/sqldelight/tachiyomi/migrations/<n+1>.sqm`); never renumber or edit an existing
   migration. Reconcile the incoming triggers/columns with what Komikku already has — some may
   already exist.
8. After the PR is merged into `master`, run the generator again: it finds the ported commits by
   their cherry-pick trailer and marks them `O`.
9. Push the feature branch and open a PR; never push to `master`.

## Followed Mihon re-architectures

The fork now follows Mihon on most of its internal re-architectures (adopted Oct 2026). When
porting, assume these are the current state, not divergences:

- **Jetpack AndroidX ViewModel** — screens use `ViewModel`/`StateViewModel`; Voyager is kept for
  navigation only.
- **`WebGPU` renderer (`ca.mpreg:webgpuviewer`)** plus the **mpreg `ca.mpreg:imagedecoder`** and
  progressive rendering; SSIV remains for the non-high-quality modes.
- **Material Symbols + Valkyrie** generated icons (`icons/material-symbols`, …);
  `material-icons-extended` is gone.
- **Apollo GraphQL** trackers (AniList, Kitsu, Shikimori, Suwayomi).
- **`com.eygraber:sqldelight-androidx-driver`** with SQLDelight `generateAsync`; the old
  `AndroidDatabaseHandler`/`DatabaseHandler` transaction helpers are gone — use
  `Database.transaction`/`transactionWithResult` + `await*`.
- **Mihon's extension-store model** (`Extension.Loaded`/`NotLoaded`, `signatures`, `store`).
- **`kotlinx-collections-immutable` is dropped** — use stdlib collections.
- compileSdk 37, aboutLibraries v15, Gradle configuration cache.

## Still deferred (do not port blindly)

- **Metro DI** — Komikku still uses Injekt. The migration is a multi-session redesign (it is
  entangled with the ViewModel layer, the KMP `:source-local` module and 250+ Komikku/SY/KMK
  bindings); it was attempted and reverted rather than left half-done.
- **DB table-rebuild schema rework** (`93cc075116`, `2d1d2e4cab`, `986f46c09a`, `a4abb6292b`,
  `f8fff318ba`, `5df78e305e`) — the migrations rebuild every table and must preserve the SyncYomi
  columns/triggers and Komikku's extra tables. Needs a dedicated PR **with an old-DB → new-DB
  upgrade test** before it can land safely.
- **`:source-api`/`:source-local` converted to Android library modules** — would change the
  extension ABI.
- **Gradle isolated projects** — blocked on reworking `buildSrc` for cross-project access.
- `MangaViewModel` "collect only while subscribed" (`7ff97761b9`) — needs a dedicated state-graph
  redesign for that screen.

When a Mihon commit exists only as part of one of these, mark it `X` (won't pick) with a note, or
leave it for the maintainer.

## Status of the first big upstream sync (Oct 2026)

The branch that introduced this file ports:

- **TachiyomiSY PR #1648** — SyncYomi protocol v2 with v1 fallback — plus its related fixes
  (PRs #1641/#1650 assessed) and the SY category-uid prerequisite (SY #1559).
- **Mihon backlog 2026-06 → 2026-10**: the portable fixes/features (TachiyomiSY's copies used where
  available), **and** the re-architectures listed under "Followed Mihon re-architectures" above
  (AndroidX ViewModel, WebGPU + mpreg decoder, Material Symbols, Apollo trackers, the SQLDelight
  driver swap, the extension-store model, dropping `kotlinx-collections-immutable`, build infra).

The items under "Still deferred" remain in `cherrypick_log.md` for dedicated follow-ups.
