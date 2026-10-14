# Review — PR komi-kun/komi-kun#1 (`chore/syncyomi-v2-mihon-backlog`)

**Reviewed:** `chore/syncyomi-v2-mihon-backlog` @ `4906c9d4e8` vs `master` @ `43e92b6294`
(383 commits, 1421 changed files, +62 856 / −26 086).

**Scope of the PR:** port TachiyomiSY #1648 (SyncYomi protocol v2 + v1 fallback) plus related SY fixes;
port the Mihon `main` backlog 2026‑06 → 2026‑10; and follow Mihon's internal re‑architectures
(AndroidX ViewModel, WebGPU renderer + mpreg, Material Symbols, Apollo GraphQL trackers,
SQLDelight AndroidX driver, extension‑store model, Metro DI, DB schema rework,
`:source-api`/`:source-local` as Android libraries, Gradle isolated projects).

**How this was produced:** 16 specialist sub‑agents audited disjoint areas in parallel
(sync protocol, DB/migrations, Metro DI, trackers/Apollo, reader/rendering/downloads, extension stores,
build/CI, i18n/provenance, library/search/categories, backup/restore, source API/exh, domain/data/repos,
downloads/storage/updater, upstream port completeness, UI/navigation, concurrency/lifecycle).
Every finding below was then **re‑checked against the tree by the reviewer**; items are marked
`[verified]` (I read the exact code/commit) or `[reported]` (sub‑agent evidence, not independently
re‑confirmed). Where two sub‑agents disagreed, the disagreement is called out and resolved.

**Verification runs actually performed (read‑only):** `./gradlew :app:spotlessKotlinCheck :data:spotlessKotlinCheck --offline`
→ **EXIT 0**; `:data:verifyDebugDatabaseMigration` → **passes**; `:app:testDebugUnitTest`,
`:domain:testDebugUnitTest`, `:data:testDebugUnitTest` (MigrationTest) → **green**;
lines/commits shown below were read directly.

> **Read the "Origin" column carefully.** *PR-work* = introduced by this PR's porting/adaptation and
> this fork owns the fix. *Upstream* = the defect exists identically in Mihon/SY; this PR inherited it
> faithfully. The second class still matters (the fork ships it), but it is not a porting mistake.

---

## 0. Severity summary

| # | Sev | Origin | Kind | Area | Finding |
|---|-----|--------|------|------|---------|
| P1 | **High** | PR-work | data-corruption | DB / sources | `StubSourceRepositoryImpl` passes `(id, lang, name)` to a `(id, name, language)` API → stub‑source name/language swapped |
| P2 | **High** | PR-work | feature-loss / wrong-results | Library search | SY search syntax (`id:`, `src:<n>`, `artist:`/`group:`/… metadata, `*`/`?`/`%`, `$`) replaced by the AST parser; some tokens silently match the wrong thing |
| P3 | **High** | PR-work | provenance | Port process | 5 upstream ports use prose instead of the required `(cherry picked from commit <sha>)` trailer and are invisible to `update_cherrypick_log.py`; several more have none |
| P4 | **High** | PR-work | markers | Fork hygiene | Mistyped/unbalanced marker blocks added in new code (`// KMk -->`, `// KMK ->`, double‑open inline, unclosed blocks) |
| P5 | **High** | PR-work | ANR / main-thread IO | Reader | Chapter completion now runs a `runBlocking` DB read on `Dispatchers.Main.immediate` |
| P6 | Med-High | PR-work | extension ABI | source-api | `HttpSource.getImage(page)` → `getImage(page, existingSize)`; old overrides are silently bypassed |
| P7 | **Med** | PR-work | regression | Reader | `retryPage` no longer forces a re‑download (`force` is never `true`) |
| P8 | **Med** | PR-work | downgrade-incompat | Backup | proto3 defaults change wire encoding; backups written by this build can fail to restore in older builds |
| P9 | **Med** | PR-work | regression | Backup | Non‑sync restore now rewrites existing categories, reorders them to the end, and rebases sync counters |
| P10 | **Med** | PR-work | data-loss | Backup | `restoreHistory` lost the fork's "write 0, not NULL" fix; removed‑history tombstones are dropped |
| P11 | **Med** | PR-work | regression | Backup | `MangaBackupCreator` uses `checkNotNull` where Mihon uses `mapNotNull`; one orphan history row aborts the whole backup |
| P12 | **Med** | PR-work | regression | Sync | `SyncManager` hard‑codes `RestoreOptions(all true)`; user "choose what to sync" flags are ignored on restore |
| P13 | **Med** | PR-work | latent data-loss | Sync | `isSyncYomiService()` cannot distinguish v1/v2 (same enum value); tombstones recorded on a v1 server flush on upgrade |
| P14 | **Med** | PR-work | regression | Downloads | `DownloadCache.getDownloadCounts` keys on `it.title` instead of `ogTitle` → badge shows 0 downloads after a rename |
| P15 | **Med** | PR-work | data-quality | Backup/DB | `BackupManga` still uses `favoriteModifiedAt` as a `dateAdded` fallback, which `49.sqm` explicitly removed |
| P16 | **Med** | PR-work | incomplete-port | UI | `MangaCoverDialog` picks WebGPU from the pref with no `isAvailable` fallback (reader has one); also loses tap‑to‑dismiss |
| P17 | **Med** | PR-work | ANR risk | UI sources | `runBlocking` on `SourceManager` at synchronous surfaces; on cold start these block the main thread for the whole package scan |
| P18 | **Med** | PR-work | regression | Crash logs | `CrashLogUtil.getExtensionsInfo` now suspends on `isInitialized.first {}` → the share sheet can hang forever |
| P19 | **Med** | PR-work | process | Port process | 57 ported Mihon commits have an SY copy that FORK.md says to prefer (systemic deviation) |
| P20 | **Med** | PR-work | missing-port | Port process | Applicable 2026‑06→10 Mihon commits left unported and not marked `X` with a reason |
| P21 | Low | PR-work | main-thread block | Sync | `SyncDataJob.stop()` still uses a blocking `ListenableFuture.get()` on the broadcast thread |
| P22 | Low | PR-work | regression risk | Extensions | Fork keeps async `LocalBroadcastManager` install cancellation instead of upstream's synchronous cancel |
| P23 | Low | PR-work | behaviour change | Extensions | `Source.installedExtension` reads an asynchronously‑filled snapshot → null window before extensions load |
| P24 | Low | PR-work | regression | Library search | `Source.query()` emits `lang:` for a blank `lang`, producing a query that matches nothing |
| P25 | Low | PR-work | main-thread IO | Downloads | `Downloader` restores its queue on `Dispatchers.Main` |
| P26 | Low | PR-work | style/docs | Updater | `GetApplicationRelease` comment/param drift |
| P27 | Low | PR-work | dead code | misc | `ExtensionManager.iconMap`, unused imports (`Download.kt`, `RestoreRepositoryImpl`, `PreferenceRestorer`, `MangaBakaApi`), `LOCAL_SOURCE_ID_ALIAS`, unused `GetSearchTags/Titles`, `networkPreferences` |
| P28 | Low | PR-work | markers | Extensions | Fork edits in `ExtensionLoader` lack `// KMK` markers |
| P29 | Low | PR-work | docs | misc | Stale `AGENTS.md` (compileSdk 37 vs 37.2, Gradle 9.3 vs 9.8.1), `.gitignore` `preview`→`nightly`, duplicate `### Fixed` + duplicated CHANGELOG entry, stale commit message, stale cherrypick‑log Details |
| P30 | Low | PR-work | CI | CI | Test‑report upload path still points at `testReleaseUnitTest` |
| P31 | Med | PR-work | docs/rules | i18n/markers | Fork brand strings edited inside upstream‑owned `i18n`/`i18n-sy`; AGENTS.md "never add new `// SY` blocks" contradicts FORK.md and the tree |
| P32 | Low | PR-work | robustness | app scope | App‑scope handler swallows failures in release (no crash, no user signal) |
| P33 | Low | PR-work | leak | Concurrency | Top-level `GlobalScope`-backed `launchIO`/`launchUI` still used in lifecycle-bound classes |
| P34 | Low | PR-work | race | KMK | `MangaViewModel.relatedMangaCollection` non‑atomic read‑modify‑write |
| P35 | Low | PR-work | provenance | Port process | `c67a33f3d4` skipped but log empty; `6d69903a56` cited twice; squashed SY #1648 trailers leave the log at `?` |
| U1 | **Med** | upstream | logic | Kitsu | Benign‑500 delete handler is dead: `dataOrElse` consumes the body before `onException` re‑reads it |
| U2 | **Med** | upstream | deadlock risk | Networking | Cloudflare interceptor does a read→write lock "upgrade" that `ReentrantReadWriteLock` forbids (unverified reachability) |
| U3 | Low | upstream | crash risk | DB | `chapterQueries.insert(...).awaitAsOne()` on `ON CONFLICT DO NOTHING RETURNING id` can throw on a race |
| U4 | Low | upstream | regression | Suwayomi | `thumbnailUrl` null → cover URL `…/null` |
| U5 | Low | upstream | resource leak | MangaUpdates | Unclosed OkHttp responses (`addSeriesToList`, `getSeriesDetails` 404) |
| U6 | Low | upstream (SY) | cosmetic | ImageUtil | `addHorizontalCenterMargin` always paints white (double‑decodes a consumed source) |
| U7 | Low | upstream (SY) | crash risk | Pager R2L | `PagerViewerAdapter.onPageSplit` can index `-1` |
| U8 | Low | upstream | regression | Downloads | `DeleteDownload.awaitAll` lost its non‑cancellable guard |
| U9 | Low | upstream | race | Notifier | `DownloadNotifier.isDownloading` mixed synchronization |
| U10 | Low | upstream | crash risk | Updater | `isNewVersion` can index out of bounds on a short release tag |
| U11 | Low | upstream | ABI | source-api | `consumer-proguard.pro` omits `ConfigurableSource$DefaultImpls` |
| U12–U17 | Info | upstream | misc | — | `dataOrElse` doc contract, `BackupManga.favorite=true` proto3 default, `MURecord` "null", `ExtensionManager` log format, `telemetry` manifest typo, `MangaUpdatesApi` unclosed 404 |

---

## A. Issues owned by this PR (porting / adaptation / fork integration)

### P1 — `StubSourceRepositoryImpl` swaps name and language `[verified]` — **High**
`data/src/main/java/tachiyomi/data/source/StubSourceRepositoryImpl.kt:34,38-42`

```kotlin
database.sourceQueries.upsert(id, lang, name)              // :34
private fun mapStubSource(id: Long, lang: String, name: String) = StubSource(id, lang, name)  // :38
```

The PR renamed the table to `source(id, name, language)` (`data/src/main/sqldelight/tachiyomi/data/source.sq`).
The generated API is positional and both arguments are `String`:
`SourceQueries.upsert(id: Long?, name: String, language: String)` and `findAll(mapper: (id, name, language))`.

So every `upsertStubSource` writes the **language code into `name`** and the **source title into `language`**,
and the read mapper is inverted the same way. App‑written rows round‑trip, but:
- rows written by `49.sqm` (`INSERT INTO source(id,name,language) SELECT _id, name, lang FROM sources` — correct order)
  are read back **swapped**, so the source list shows a source's language as its name for anything uninstalled before the upgrade;
- the table is wrong at rest for all new rows (visible to backups/debug inspection).

Fix: named args — `upsert(id = id, name = name, language = lang)` and `mapStubSource(id, name, lang)` (adopt the Mihon copy).

### P2 — Library search syntax regression `[verified]` — **High**
`domain/src/main/java/mihon/domain/library/model/search/LibrarySearch{Ast,Lexer,Parser}.kt`,
`app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt`, deleted `app/src/main/java/exh/search/**`

`cd0cb1ac9f` replaces TachiyomiSY's search engine with Mihon's AST parser. The commit message says
"drop `id:`/`src:` prefixes and the metadata source tag/title search", but the practical effect is worse
than "fewer features" because some tokens are **silently reinterpreted**:

| User types (worked in SY) | New behaviour |
|---|---|
| `id:123` | lexes as `Field(id, 123)`; `MangaField.fromString("id")` is null → `GeneralQueryNode("id:123")` → matches nothing. Must now write `id=123`. |
| `src:123` (numeric source id) | `MangaField.SOURCE` matches → matches entries whose **source name contains "123"** → silently wrong results. New form is `srcid:123`. |
| `artist:`, `group:`, `parody:`, `character:`, `uploader:`, `female:`, `male:`, `reclass:`, `a:`, `c:`, `f:`, `g:`, `l:`, `m:`, `p:` | EH/NHentai metadata search gone; `artist:`/`language:` now match unrelated manga fields, the rest become literal terms → nothing. |
| `foo*`, `f?o`, `foo%` | `*`/`?`/`%` are literal → never matches. |
| `$exact` | literal. |

Verified in `LibrarySearchLexer.kt` (the `CompField` branch needs `=`/`>`/`<`) and
`LibrarySearchParser.kt:66-70`. The `4906c9d4e8` fix for `lang:` is correct for non‑blank langs.
There is no on‑screen help text for the syntax, so users get silent wrong/empty results.
Fix: add a compatibility shim/aliases for `id:`/`src:`, or at minimum document the new grammar and
the metadata loss. See also P24.

### P3 — Missing/incorrect cherry‑pick trailers `[verified]` — **High**
Requirement: `FORK.md:51-52` — a port must keep `(cherry picked from commit <sha>)`, because
`update_cherrypick_log.py` only detects that exact form.

Prose instead of the trailer (all appear as `?` in `cherrypick_log.md`):
- `b6635d3f66` → mihon `771ba33a33` (`cherrypick_log.md:22`)
- `fdc811e834` → mihon `ea2b73ad78` (`:32`)
- `1c0fd84a7b` → mihon `db45dda525` (`:117`)
- `da20f781dc` → mihon `a4abb6292b` (`:149`)
- `8ee215710e` → mihon `f8fff318ba` (`:147`)

No trailer at all:
- `d1ab687c8d` (`5fadea61e7` #3869, "suspend the source‑manager surface") — FORK.md says it is ported, the log row is empty.
- `387ea9a5b5`, `359ee0cf8f` (SY #1559 / #1658 ports) — no trailer.
- `cd3987a228` — no trailer; `3b88dd7ca9`'s trailer points at the **non‑existent object** `4496c9db1c` (real SY source `41574e8235`, documented in FORK.md).
- `15c4b5beef` → `4e8e01d4f4`, which exists only on the unmerged `tachiyomiSY-pr/1641`.

Consequence: the log claims 383 commits but only ~269 are attributable; the five prose ports plus
`d1ab687c8d`/`387ea9a5b5`/`359ee0cf8f` will never be marked `O`, so future syncs will re‑consider and
possibly re‑port them. Fix: convert the prose to the exact trailer form (keep the prose as well),
and fix `3b88dd7ca9`'s SHA.

### P4 — Marker defects `[verified]` — **High**
Added lines: 304 `// KMK -->` vs 302 `// KMK <--` (net +2, plus pre‑existing debt).
Confirmed in the tree:

- `app/src/main/java/eu/kanade/tachiyomi/ui/history/HistoryViewModel.kt:451` — `// KMk -->` (lowercase `k`), and the block is never closed.
- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt:742` — `// KMK -->` unclosed.
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt:1406` and `:1531` — `// KMK --> using injected context` unclosed (two separate blocks).
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonRecyclerView.kt:204` — `// KMK ->` (one dash).
- `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadCache.kt:371` and
  `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadManager.kt:467` — double opening tag:
  `/* KMK --> */ manga.ogTitle /* KMK --> */` (second should be `/* KMK <-- */`).

These defeat marker tooling and the fork's ability to diff against upstream. Fix: normalise and re‑balance.

### P5 — Reader: blocking DB read on the main thread at chapter completion `[verified]` — **High**
`app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt:322-333, 997-1000, 1012-1016, ~893`

```kotlin
private val unfilteredChapterList by lazy {
    val manga = manga!!
    runBlocking { getMergedChaptersByMangaId.await(...) /* or */ getChaptersByMangaId.await(...) }
}
...
private fun writeChapterProgress(readerChapter: ReaderChapter, onComplete: Boolean) {
    appScope.launch(Dispatchers.Main.immediate) {      // :997
        progressWrites.withLock {
            if (onComplete) updateChapterProgressOnComplete(readerChapter)   // touches the lazy at :1016
            ...
```
`updateChapterProgressOnComplete` also calls `deleteChapterIfNeeded` and reads `unfilteredChapterList`
on the same main‑immediate coroutine. At `master` this path ran on IO (`launchNonCancellable`).
On the first completion after opening a chapter (and on any EH‑based manga), this is a synchronous
SQL query on the UI thread → jank/ANR on slow storage. The `Main.immediate` choice is upstream #4068;
the `runBlocking` lazy is the SY layer this PR carried. Fix: prime `unfilteredChapterList` off the main
thread in `init()`.

### P6 — `HttpSource.getImage` overload breaks old extension overrides `[verified]` — **Med-High**
`source-api/src/main/kotlin/eu/kanade/tachiyomi/source/online/HttpSource.kt:505`

```kotlin
open suspend fun getImage(page: Page, existingSize: Long = 0L): Response
```
(`mihon/main` was `getImage(page: Page)` before #3167; upstream now has this two‑arg form, non‑open.)

`DelegatedHttpSource.kt`/`EnhancedHttpSource.kt` were updated, but an installed extension compiled
against the old `source-api` that overrides `getImage(page: Page)`:
- has a method that no longer overrides anything → its custom image fetch (auth/decrypt/headers) is **silently skipped**; and
- calling `super.getImage(page)` links to a removed method → `NoSuchMethodError`.

This fork hosts third‑party extension repos, so extensions must be recompiled and version‑gated.
Prevalence of `getImage` overrides is unverified (no extension sources in this repo).

### P7 — Reader retry no longer re‑downloads `[verified]` — **Med**
`app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt:132-152, 281-298, 329-338`

Upstream `mihon/main` (`:103`):
```kotlin
scope.launch { internalLoadPage(page, force = true) }.trackAsDownloadJob(pageIndex)
```
The fork replaced both retry paths with `internalLoadPage(page)` (default `force = false`), and
`internalLoadPageImpl` only downloads when `!chapterCache.isImageInCache(imageUrl)`. So a page that
fails to **decode** while its bytes are still cached cannot be re‑fetched by tapping retry (it just
flips back to `Ready` from the same bad bytes). EXH sources mask it because `retryPage` nulls `imageUrl`.
Fix: pass `force = true` through `boostPage`/the rolling path.

### P8 — proto3 defaults make backups downgrade‑incompatible `[reported, high confidence]` — **Med**
`app/src/main/java/eu/kanade/tachiyomi/data/backup/models/Backup*.kt`

`3b88dd7ca9` adds defaults to fields that previously had none. The injected `ProtoBuf` uses
`encodeDefaults = false` by default, so values equal to the new defaults (e.g.
`BackupTracking.libraryId = 0`, `BackupHistory.lastRead = 0`, `BackupMergedMangaReference.* = false`,
`BackupManga.source/url = ""`) are now **omitted** from the payload. A backup written by this build
restored by `master` (or any client whose model still marks those fields required) throws
`MissingFieldException`; `BackupStream`/`BackupDecoder` wrap it as `invalid_backup_file_unknown` and the
**whole restore aborts**. The reverse direction (old backup → this build) is fine.
Fix/decision: either accept downgrade breakage explicitly, or keep those fields default‑less and only
default genuinely optional ones on decode.

### P9 — Non‑sync restore rewrites and reorders existing categories `[verified]` — **Med**
`data/src/main/java/tachiyomi/data/category/CategoryRepositoryImpl.kt:133-163`

For an existing `dbCategory` the code now always runs an `update` with
`order = if (isSync && category.order !in usedOrders) category.order else freeOrder()`. For a
**user‑initiated** (non‑sync) restore, `freeOrder()` appends after `max(order)+1`, so existing
categories A(0), B(1) become 3, 4 — result order C, A, B. It also overwrites `version`/`last_modified_at`
with the backup's values (0 for old backups), resetting SyncYomi counters. `master`'s
`CategoriesRestorer` only inserted *new* categories. Whether routing the non‑sync path through this
update is deliberate convergence work is **unverified** — but the user restore path does hit it.

### P10 — `restoreHistory` lost the "0, not NULL" fix `[verified code, master diff unconfirmed]` — **Med**
`data/src/main/java/tachiyomi/data/backup/RestoreRepositoryImpl.kt:389, 394`

```kotlin
val readAt = copies.mapNotNull { it.readAt }.maxOrNull()
...
?: return@map Triple(chapterId, readAt, readDuration)   // readAt may be null
```
A history entry with `lastRead == 0` (removed/unread) maps to `BackupHistory.toInstantOrNull() = null`,
so restore upserts `read_at = NULL`. `history.sq` treats NULL as "reset"
(`removeResettedHistory`), so a user‑removed history entry no longer survives a restore — which the
fork had previously fixed. Fix: write epoch‑0 (non‑null) as before.

### P11 — `MangaBackupCreator` aborts on one orphan history row `[verified]` — **Med**
`app/src/main/java/eu/kanade/tachiyomi/data/backup/create/creators/MangaBackupCreator.kt:124-128`

```kotlin
val url = checkNotNull(chapterUrlsById[history.chapterId])
```
Upstream `771ba33a33` uses `mapNotNull` with a comment that a chapter removed since its history was
read takes that history with it. `checkNotNull` throws and fails the **whole backup**.
Reachability is unverified (`history.chapter_id` has `ON DELETE CASCADE`), hence Med not High.

### P12 — Sync restore ignores "choose what to sync" `[verified]` — **Med**
`app/src/main/java/eu/kanade/tachiyomi/data/sync/SyncManager.kt:313-325`

```kotlin
options = RestoreOptions(appSettings = true, sourceSettings = true, libraryEntries = true, extensionStores = true)
```
SY `master` passes `syncOptions.*`. Because the flags are forced true, `BackupRestorer` applies app/source
settings and extension stores from the server even when the user unchecked them. (User‑initiated restore
does honour its options — this is sync‑specific.)

### P13 — Tombstones recorded on a v1 SyncYomi server flush after an upgrade `[verified]` — **Med**
`app/src/main/java/eu/kanade/domain/sync/SyncPreferences.kt:98-100`

```kotlin
private fun isSyncYomiService() = syncService.get() == SyncManager.SyncService.SYNCYOMI.value
```
`SyncManager.SyncService` (`SyncManager.kt:64-70`) has a single `SYNCYOMI(1)` for both protocol
versions. The comment claims v1 is excluded, but it is not. Tombstones are only ever *sent* by the v2
path, so categories deleted while the server was v1 stay pending and are then flushed against a v2
server, potentially deleting live categories. Fix: gate on runtime `serverSupportsV2()`, and forget a
uid when it is seen live again.

### P14 — Download‑count badge uses the wrong title field `[verified]` — **Med**
`app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadCache.kt:243`

```kotlin
mangaDirs[provider.getMangaDirName(it.title)]
```
Every other download path keys on `ogTitle` (`:219`, `:268`, `:294`, `:309`, `:332`, `:352`, `:371`), and
`Manga.title = customMangaInfo?.title ?: ogTitle`. After a rename, `mangaDirs[getMangaDirName(title)]`
is null, so the library shows **0 downloaded chapters** for that entry. Ported verbatim from Mihon
(which has no `ogTitle`) and not adapted. Fix: `it.ogTitle`.

### P15 — `BackupManga` still falls back to `favoriteModifiedAt` `[verified]` — **Med**
`app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupManga.kt:87-93`

```kotlin
dateAdded = ... dateAdded.toInstantOrNull()
    ?: favoriteModifiedAt?.let { Instant.fromEpochSeconds(it) }
    ?: Manga.UNKNOWN_FAVORITE_AT
```
`49.sqm:142-147` removed exactly this fallback ("`favorite_modified_at` is no fallback for a missing
`date_added`: every manga update rewrote it"), so the restore path and the migration now disagree.
Restoring a pre‑PR backup can set `dateAdded` to the last‑update time, which then feeds the
`DateAdded` sort and the `added:` AST search. Fix: drop the middle branch.

### P16 — Cover dialog has no WebGPU fallback and loses tap‑to‑dismiss `[verified]` — **Med**
`app/src/main/java/eu/kanade/presentation/manga/components/MangaCoverDialog.kt:92, 207-252`

```kotlin
val useNewRenderer = LocalContext.current.appGraph.basePreferences.highQualityRenderer.get()
...
if (useNewRenderer) { ...; ImageViewer(state = state); return@Scaffold }
```
The reader gates on device capability (`ReadingMode.kt:114` probes `WebGpuRenderer.isAvailable`), but the
dialog only reads the preference. With the pref on and WebGPU unavailable, the full‑screen cover renders
a no‑op surface instead of falling back to SSIV; the `else` branch (which wires
`clickableNoIndication(onClick = onDismissRequest)`) is unreachable, so dismissing by tapping also
differs by renderer. (Upstream‑derived; the fork's fallback guarantee does not propagate.)

### P17 — `runBlocking` on `SourceManager` at synchronous UI surfaces `[verified code]` — **Med**
`SourceManager.get/getOrStub/...` are `suspend` and gate on `sourcesMapFlow.filterNotNull().first()`,
which stays null until extensions finish loading. These call it under `runBlocking`:
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMangadexScreen.kt:75` (during settings composition)
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/merged/EditMergedMangaHolder.kt:53` (ViewHolder bind)
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/merged/EditMergedSettingsHeaderAdapter.kt:117` (ArrayAdapter)
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt:838`
- `app/src/main/java/exh/recs/sources/RecommendationPagingSource.kt:164` (lazy, read from Compose)

No permanent deadlock was found (the producer is on `AppCoroutineScope`/IO), but on a cold start /
before the first load completes these block the main thread for the entire package scan → ANR.
FORK.md acknowledges the pattern; the justification ("sources are already loaded") is timing‑dependent.

### P18 — Crash‑log dump can hang on extension init `[verified]` — **Med**
`app/src/main/java/eu/kanade/tachiyomi/util/CrashLogUtil.kt:37, 69-72`

`getExtensionsInfo()` is now `suspend` and calls `extensionManager.getLoadedExtensions()`, which awaits
`isInitialized.first { it }`. `master` read `installedExtensionsFlow.value` directly. If extensions never
finish loading (a plausible crash antecedent — e.g. a locked DB), `dumpLogs` suspends forever and the
share sheet never opens. Fix: `withTimeoutOrNull` or fall back to the current snapshot.

### P19 — SY copies not preferred (process) `[reported]` — **Med**
FORK.md:53-54 and the PR description say to prefer the TachiyomiSY copy of a Mihon commit when one
exists. The audit found ~57 window commits (60 over all history) ported from the Mihon copy although an
SY copy exists — e.g. `face02f100` picked `5c3ae37d52` instead of SY `b7f19ea053`; `c98354ea3f` picked
`55be95dd5d`; the source‑module conversion picked `9c3cc1ca5f` rather than SY `e03a949efe`. This is a
systemic process deviation that increases future conflict surface. Not a functional break by itself.

### P20 — Applicable Mihon commits left unported `[reported]` — **Med**
Of ~45 empty Mihon window rows, these are applicable and not marked `X` with a reason:
- `7aacaa3490` "Keep the paused download notification after the worker stops" (also the parent of ported `f027de8c2e` → `b631eeab4d`)
- `0c1be369b0` "Ask to add an entry to the library before editing its cover (#2265)" (parent of ported `36ec043a5c`)
- `d58130a5fc` "Check for app updates every 6 hours while the app is open" and `aa25b3a14c` "Download app update without WorkManager (#3707)"
- `0787678c12` "Don't use GMS to detect if GMS is available (#3525)"

### Lower‑severity PR‑work items
- **P21** `SyncDataJob.stop()` uses blocking `wm.getWorkInfos(...).get()` (`SyncDataJob.kt:179`) on the broadcast main thread, while the rest of the file was made suspend/non‑blocking.
- **P22** `Installer.kt` keeps `LocalBroadcastManager` cancellation (a cancel can be dropped if no installer is alive); upstream replaced it with a synchronous listener set.
- **P23** `Source.installedExtension` (`app/src/main/java/eu/kanade/domain/source/model/Source.kt:17-22`) reads `loadedExtensionMapFlow.value`, populated asynchronously; it was synchronous on `master`.
- **P24** `MangaInfoHeader.kt:904` `Source.query()` guards only `name`, not `lang`; a blank lang yields `lang:` → empty results (see P2).
- **P25** `Downloader.kt:131` `scope.launch(Dispatchers.Main, UNDISPATCHED) { addAllToQueue(withContext(IO) { store.restore() }) }` runs queue insertion (SharedPreferences writes) on Main.
- **P26** `GetApplicationRelease.kt:83,86` comment names `komikku-app/komikku` while the repo is `komi-kun/komi-kun`; `peekIntoPreview` is ignored.
- **P27** Dead code: `ExtensionManager.kt:84` `iconMap`/`ConcurrentHashMap:54`; `Download.kt:10,12,14`; `RestoreRepositoryImpl.kt:14,15,33`; `PreferenceRestorer.kt:4`; `MangaBakaApi.kt:36` (`MR`); `LibraryItem.kt` `LOCAL_SOURCE_ID_ALIAS`; unused `GetSearchTags`/`GetSearchTitles`; `CrashLogUtil.kt:29`. **Note:** `spotlessKotlinCheck` passes (`EXIT 0`) — these do **not** fail CI, contrary to one sub‑agent's claim.
- **P28** `ExtensionLoader.kt:116,203` uses fork interactors (`appGraph.getExtensionStores.get()`) without `// KMK` markers.
- **P29** Docs/drift: `AGENTS.md:88` says compileSdk 37 / Gradle 9.3+ (actual 37.2 / 9.8.1); `.gitignore:26` still `app/standard/preview/*`; `CHANGELOG.md` has two `### Fixed` sections and a duplicated Shikimori entry; `977458a0de` claims two commits are "left for follow‑ups" but both are ported; `cherrypick_log.md` Details cite local commits that are not ancestors of HEAD.
- **P30** `.github/workflows/build_pull_request.yml:91` (and `build_push.yml:75`) upload `app/build/reports/tests/testReleaseUnitTest` while the task is now `testDebugUnitTest`.
- **P31 (docs/rules)** `i18n/src/.../base/strings.xml:3-5` and `i18n-sy/.../base/strings.xml:456-458` brand strings are edited inside upstream‑owned modules (KMK‑wrapped, so detectable) — record them in FORK.md as permanent divergence. Also `AGENTS.md:41` ("Never add new `// SY` … blocks") directly contradicts `FORK.md:32` and the tree (this PR adds many new SY blocks). Reconcile.
- **P32** `AppBindings.kt:152-165` app‑scope handler logs in release; a failing app‑scope DB write now silently no‑ops (debug still crashes).
- **P33** `launchIO`/`launchUI` (GlobalScope) still used in `LibraryUpdateErrorViewModel`, `LibraryViewModel`, `MangaScreen`, `ConfigureExhDialog`, `AppUpdateDownloadJob`, `MainActivity`, `DiscordLoginScreen`.
- **P34** `MangaViewModel.kt:1272` `relatedMangaCollection.value = relatedMangaCollection.value.orEmpty() + relatedManga` is a non‑atomic RMW from a source callback.
- **P35** Provenance bookkeeping: `c67a33f3d4` deliberately skipped but the log row is empty (not `X`); `6d69903a56` is cited by two local commits; `380496a5be` (SY #1648) is squashed and stuck at `?`.

---

## B. Issues inherited from upstream (ported faithfully; the fork ships them)

### U1 — Kitsu benign‑500 delete handler is dead `[verified]` — **Med**
`core/common/src/main/kotlin/eu/kanade/tachiyomi/network/ApolloExtensions.kt:36-39`
and `app/src/main/java/eu/kanade/tachiyomi/data/track/kitsu/KitsuApi.kt:141-145`

`dataOrElse` does `val body = e.body?.use { it.readUtf8() }` and then calls `onException(e)`.
The Kitsu handler re‑reads `e.body?.use { it.readUtf8() }`, which is now exhausted → `""`, so the
`statusCode == 500 && body.contains("Couldn't find LibraryEntry…")` check can never be true and the
intended silent success throws `HttpException(500)`. Both files are byte‑identical to `mihon/main`.
Fix: read the body once and pass the string to the handler.

### U2 — Cloudflare read→write lock "upgrade" `[reported]` — **Med (unverified)**
`core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt:67`

```kotlin
val (response, clearance) = lock.read { val response = chain.proceed(request); ... }
lock.write { ... }
```
`ReentrantReadWriteLock` does not permit upgrading read→write. If a nested same‑host challenged request
is issued from inside `chain.proceed` (the token‑refresh case the comment names), the nested `lock.write`
waits on the outer read hold while the outer call waits on the nested one → deadlock. Reachability is
unverified; the code comment's premise looks wrong.

### U3 — `chapterQueries.insert(...).awaitAsOne()` on `DO NOTHING RETURNING id` `[reported]` — **Low**
`data/src/main/sqldelight/tachiyomi/data/chapter.sq:191-194`,
`data/src/main/java/tachiyomi/data/chapter/ChapterRepositoryImpl.kt:54,133`
On an `ON CONFLICT DO NOTHING` conflict there is no row, so `awaitAsOne()` throws; `updateFromRemote`
is not guarded, so a race can abort the batch. Identical to `mihon/main`.

### U4–U11 — Lower severity upstream items
- **U4** `SuwayomiMangaFragmentExtensions.kt:11` `"$baseUrl/${manga.thumbnailUrl}"` → `…/null` when the (nullable) thumbnail is absent.
- **U5** `MangaUpdatesApi.kt:79-85, 192-196` unclosed responses (`addSeriesToList`, `getSeriesDetails` 404).
- **U6** `ImageUtil.addHorizontalCenterMargin` (`core/common/.../ImageUtil.kt:202-235`) double‑decodes an exhausted source → background is always white (SY‑carried).
- **U7** `PagerViewerAdapter.kt:211` `indexOfFirst` can return `-1` for the second element of a joined pair → `joinedItems[-1]` in R2L (SY).
- **U8** `DeleteDownload.kt:16-20` lost the `withNonCancellableContext` guard (upstream #4068).
- **U9** `DownloadNotifier` writes `isDownloading` without the monitor in some callbacks.
- **U10** `GetApplicationRelease.kt:86-99` indexes `newSemVer` with `oldSemVer.indices` → `IndexOutOfBoundsException` on a shorter release tag.
- **U11** `source-api/consumer-proguard.pro` keeps `ConfigurableSource` but not `ConfigurableSource$DefaultImpls`, which extensions link against.

### U12–U17 — Informational upstream items
- `dataOrElse` KDoc says the caller *must* close `body`, but it already drains/closes it (root cause of U1).
- `BackupManga.favorite = true` is a non‑zero default; a proto3 payload that omits `favorite` decodes to `true`.
- `MURecord.kt:35-36` stringifies nulls as `"null"`.
- `ExtensionManager.kt:410` log format has one `%s` but two args (SY).
- `telemetry/build.gradle.kts:16` typo `AndroidManifext.xml` → the no‑telemetry manifest is not used.
- `MangaBackupCreator`/restore constants and `Source.query()` are shared with the sync path.

---

## C. Verified clean (positive evidence)

- **Build/CI infrastructure:** `spotlessCheck` and `:app`/`:data` `spotlessKotlinCheck` pass
  (`--offline`, EXIT 0); `:data:verifyDebugDatabaseMigration` is a real task and passes;
  unit tests (`app` 9 suites/47 tests, `domain` 6 suites, `data` MigrationTest) are green.
  No stale references to removed things (`macrobenchmark`, `material-icons-extended`,
  `kotlinx-collections-immutable`, `DatabaseHandler`, `assemblePreview`, `preview` build type,
  `ifSourcesLoaded`, `ExtensionApi`, `ScreenModel`/`rememberScreenModel`). `release.keystore` is
  **not** tracked (`git ls-files` empty; PR adds it to `.gitignore`). Isolated projects config is clean
  (no `allprojects`/`subprojects`/cross‑project configuration). Icons codegen inputs all exist and the
  `MaterialSymbolsR` alias forbid‑rules don't false‑positive. `:source-api`/`:source-local` contain only
  `src/main` (KMP leftovers gone).
- **DB schema/migrations:** files 1–53 are contiguous, `Database.Schema.version == 54`; `49.db`…`53.db`
  are the correct snapshots; SyncYomi `version`/`is_syncing`/`last_modified_at` columns **and** all
  triggers survive; Komikku/SY extra tables (`merged`, `search_*`, `libraryUpdateError*`, `saved_search`,
  `feed_saved_search`, `eh_favorites*`, `extension_store`) survive and `MigrationTest` asserts rows +
  `foreign_key_check == 0`; the duplicate `(source,url)` merge is deterministic
  (`favorite_at IS NOT NULL DESC, _id ASC`) with `coalesce(min(nullif(...,0)), min(...))` preserving a
  missing date; `favorite_modified_at` is dropped in `49.sqm` and re‑added+backfilled in `52.sqm` with a
  NULL‑safe trigger. Rebuilt views match the `.sq` definitions and their generated models.
- **SyncYomi v2:** capability probe (200/404/throw, per‑host cache), v1 fallback, `/api/sync/v2/merge`
  request/response and `X-Sync-*` headers, gzip applied exactly once, streaming request body format
  matches `BackupWriter`/`BackupReader`, restore awaited + `fullSyncRequested` self‑heal, category
  tombstone wired from both Injekt and Metro `DeleteCategory`, proto3 defaults match final SY,
  migrations preserve sync columns. (`BackupRequestBody` streaming verified.)
- **Metro DI / ViewModel:** the Injekt bridge covers every residual consumer; `AppGraph.inject`
  declarations match all 16 call sites; ViewModels stay **screen‑scoped** via Voyager's
  `AndroidScreenLifecycleOwner` (`viewModelStore.clear()` on dispose), so the `screenModelScope`→
  `viewModelScope` move did not make them activity‑scoped; no private/`open` ViewModel remains;
  preference `fun`→`val` and `enabled`→`visible` migrations are complete; `GlobalScope` in
  `LibraryViewModel:1005`/`LibraryUpdateErrorViewModel` is pre‑existing and deliberate.
- **Trackers:** all `material-icons`/`GraphQL` operations match `mihon/main`; `CountryCode`/`ISO8601*`
  scalar mappers present; token expiry uses `kotlin.time`; removed OAuth attributes have zero references
  and old tokens still decode (`ignoreUnknownKeys`); fork‑only trackers (Komga, Kavita, MdList, Suwayomi,
  Bangumi, MangaUpdates, MAL, Shikimori) intact; tracker IDs stable with Hikka=10/MangaBaka=11/Comick=12;
  new trackers fully wired in `TrackerManager`, settings, manifest and drawables.
- **Extensions:** the security‑sensitive gating is sound — updates only come from a key‑matching listing
  (`Extension.Installed.findListing`), the downloaded APK is re‑verified against the store key before
  install, and shared installs are OS‑enforced. `Loaded`/`NotLoaded` propagation is exhaustive; the
  `ExtensionApi` wrapper is gone; install cancellation cancels only the matching `downloadId`; download
  IDs are non‑negative and live only in in‑memory maps (no collision with chapter downloads); Shizuku
  receivers use `RECEIVER_NOT_EXPORTED`; extension‑store backup round‑trips `signing_key` unchanged.
- **Backup streaming:** `BackupStream` is byte‑identical to upstream; old non‑streamed `.tachibk` remains
  restorable; gzip/JSON handling correct; no `@ProtoNumber` reuse/renumbering (only additions); download
  cache invalidation after restore present; user restore honours options; batch restore uses one
  transaction per chunk with `ensureActive()` and per‑entry fallback.
- **Revert integrity:** `dd6c724445` is the exact inverse of `7a8566708f` (the path difference is the
  `LibraryUpdateJob`→`LibraryUpdateWorker` rename), `fd524fde86` is byte‑exact inverse of `7acdf5c303`;
  no revert without its original, no duplicate port (`git log --format=%s | sort | uniq -d` empty), and
  all 117 deletions trace to an upstream or local replacement.
- **Prerequisites present:** preference properties `dd31a86836`, source‑api Android modules
  `513b1615ec`, Metro `53dcf223ba`, Metro‑Injekt resolution `0f0c72f0ef`, upstream Injekt `aa374360b7`,
  schema rework `977458a0de`, isolated projects `a637993549`; `webgpuviewer 52`, Apollo `5.2.0`,
  `sqldelight-androidx-driver 0.2.2`, compileSdk `37.2` all in the catalogs.
- **Icons/UI:** 0 dangling `material-icons-extended`/`Icons.*`/`ic_*`/`anim_*_enter` references; all
  `MaterialSymbols`/`CustomMaterialSymbols` usages resolve; all `metroViewModel`/`assistedMetroViewModel`
  targets keyed; settings `when` exhaustive; Tokyo Night/Catppuccin/Nord colors all defined; shortcuts
  drawables resolve.

---

## D. Unverified / needs a device or a clean CI run

These could not be settled statically and should be exercised before/after merge:

1. **R8/minified release build.** `assembleRelease`/`assembleNightly` has never run here; `41ffde83f1`
   removed broad app keeps. CI only tests `debug`. Run a minified build and smoke‑test.
2. **WebGPU rendering** and `WebGpuRenderer.isAvailable` bring‑up cost (the static initializer does GPU
   `runBlocking`); the SSIV fallback path; mpreg decoding of very tall long strips (P16, P5).
3. **Metro runtime graph construction**, re‑entrancy during `createGraphFactory`, the
   `KMKMetroInteropModule` vs upstream's removed module in the extension‑loading path, and
   `Set<Migration>` Type identity (FORK.md's own admitted gap).
4. **`ON CONFLICT DO NOTHING RETURNING id` / `SELECT last_insert_rowid()` blocks** under the async
   (androidx) driver on both plain and SQLCipher paths (U3, and the `saved_search`/`feed_saved_search`/
   `merged` block form vs the `category.sq` `RETURNING` rewrite).
5. **Partial category response on `v2/merge`** — `SyncManager` deletes local categories absent from the
   response; whether the server always returns the full set is a contract question.
6. **`instanceExtension` null window**, `getRelatedMangaList` callback concurrency, and the cold‑start
   ANR probability of P17.
7. **`BackupPreference.value`** (only field without a default) when a proto3 server omits the message.
8. **`verifyDebugDatabaseMigration` coverage**: `verifyMigrations=false`, so migrations 1–48 are exercised
   only through the `.db` snapshot chain; whether `verifyDefinitions` compares exact CREATE text needs a
   build to confirm.
9. **`androidx.navigationevent:1.2.0` pin** (`4d0139c01d`) vs transitive activity/lifecycle versions.
10. **Updater rotation**: `MIHON_GITHUB_RELEASE` signing path and the `isNewVersion` tag‑length edge (U10).
11. **Extension recompile/version‑gate** for the `HttpSource.getImage` ABi change (P6) and the
    `RxExtension.kt`/`PreferenceScreen.kt` removals.

---

## E. Suggested pre‑merge checklist

1. Fix P1 (named args) — a one‑line data‑corruption bug that ships otherwise.
2. Fix P4 markers and P3 trailers (they are cheap and unblock the fork's whole porting workflow).
3. Decide P2: at minimum document the new library‑search grammar and add `id:`/`src:` aliases; the
   metadata search loss is the largest user‑visible regression for EH/NHentai users.
4. Fix P7 (pass `force = true`) and P5 (prime the chapter list off‑main).
5. Re‑check the restore path: P9, P10, P11, P15 and the P8 downgrade decision.
6. Fix P12 (pass the real sync options) and P13 (gate on `serverSupportsV2`).
7. Fix P14 (`ogTitle`) and P16 (reuse `isHighQualityRendererAvailable()`).
8. Run `./gradlew assembleRelease` (or `assembleNightly`) once and smoke‑test; Run
   `spotlessCheck`/tests again after fixes.
9. Fix the CI report path (P30) and refresh the cherry‑pick log after P3 so `?` rows become `O`.
10. Consider cherry‑picking/adapting the upstream fixes for U1 (Kitsu) and reviewing U2 (Cloudflare).

---

## F. Post-review findings (minified-release build + emulator smoke test)

### F1 — BLOCKER (fixed): the minified release crashed the reader on the SSIV path
`FATAL EXCEPTION … java.lang.NoClassDefFoundError: tachiyomi.decoder.ImageDecoder`
`at com.davemorrissey.labs.subscaleview.decoder.Decoder.init(...)`

The PR ported Mihon `face02f100` ("Add high quality WebGPU renderer"), which swapped
`libs.image.decoder` from the legacy `com.github.tachiyomiorg:image-decoder` to `ca.mpreg:imagedecoder`.
Upstream Mihon could do that because it removed SubsamplingScaleImageView; **this fork keeps SSIV**
(FORK.md), and the SSIV fork it depended on (`com.github.tachiyomiorg:subsampling-scale-image-view:66e0db195d`)
has a `Decoder` that hard‑links `tachiyomi.decoder.ImageDecoder`, which only the legacy decoder provides.
Result: the default reader (high‑quality renderer off) crashed for every user. The R8 run only
* warned* (`Missing class tachiyomi.decoder.ImageDecoder`), which is why a debug/compile check missed it.

Fix: switch to Mihon's SSIV fork `com.github.mihonapp:subsampling-scale-image-view:1b283e2bc4`, whose
`Decoder` uses `ca.mpreg.imagedecoder` — matching `mihon/main` (same fork hash + `image-decoder = 16` +
`exclude(module = "image-decoder")`). The two forks cannot coexist (both ship `libimagedecoder.so` with
different JNI symbols), so the SSIV fork must be the mpreg one.

Verified on an emulator (API 37, software GPU): the minified release launches, and **both** reader paths
render — SSIV (default) and WebGPU/mpreg (with "Use high quality renderer" enabled).

### F2 — Extension ABI: our `source-api` is a strict superset of `mihon/main`; proguard widened
Only real gap was `consumer-proguard.pro` (missing `*$DefaultImpls` keeps). Fixed.

### F3 — `versionCode` / `ContentWarningMigration`
`master` = 82, the branch = **83**, `ContentWarningMigration.version = 83f` → the migration already runs
on upgrade. No bump needed (an earlier note based on the PR description's stale "82" was wrong).

### F4 — Device test coverage
Emulator smoke test covered: minified R8 build + launch, Metro graph resolution, onboarding, DB creation
(migration ran; "Updated to v1.14.1"), local source, and both reader renderers. Not covered on a device:
real‑GPU WebGPU, the DB *upgrade* path (no prior installs exist), and SyncYomi against a live server.

