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

## Known intentional divergences (do **not** port these blindly)

These Mihon changes are deliberately not followed, because this fork inherits Komikku's choices.
Porting them would mean re-architecting the app:

- **Jetpack AndroidX ViewModel** — the fork uses Voyager `ScreenModel`/`StateScreenModel`.
- **Metro DI** — the fork uses Injekt (`AppModule`, `DomainModule`, `KMKDomainModule`, …).
- **`WebGPU` renderer / `webgpuviewer`** — the fork uses Subsampling Scale Image View (SSIV).
- **Material Symbols + Valkyrie icon generation** — the fork uses `material-icons-extended`.
- **Apollo GraphQL tracker stack** and its per-tracker rewrites.
- **`:source-api`/`:source-local` converted to Android library modules** (extension ABI).
- **libvips based image decoder** — dropped upstream, not present here.
- **Dropping `kotlinx-collections-immutable`** — Komikku still uses it.
- **`sqldelight-androidx-driver` / Injekt / FlexibleAdapter "upstream" swaps** — the fork pins the
  versions Komikku pins.
- **SQLDelight `generateAsync` / `RETURNING`** — Komikku uses `AndroidDatabaseHandler`.
- **Mihon's extension-store model rework** (`Extension.Loaded`/`NotLoaded`, dropped
  `signatureHash`/`storeName`) — Komikku has its own store model.

When a Mihon commit exists only as part of one of those migrations, mark it `X` (won't pick) with a
note, or leave it for the maintainer.

## Status of the first big upstream sync (Oct 2026)

The branch that introduced this file ports:

- **TachiyomiSY PR #1648** — SyncYomi protocol v2 with v1 fallback — plus its related fixes
  (PRs #1641/#1650 assessed) and the SY category-uid prerequisite (SY #1559).
- **Mihon backlog 2026-06 → 2026-10**: the portable fixes/features. TachiyomiSY's copies were used
  where available (MangaBaka + Hikka trackers, resumable downloads, reader chapter navigator,
  backup-restore performance, Shikimori GraphQL, …).

Deliberately **not** ported: the divergences listed above (Voyager/Metro/webgpu/Apollo/Material
Symbols, the DB-schema and extension-store reworks). Those remain in `cherrypick_log.md` for a
future, dedicated effort.
