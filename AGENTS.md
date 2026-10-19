# komi-kun – AI Agent Guide

**komi-kun** is a personal vendoring fork of **Komikku** (app name "Komikun"; `applicationId`: `app.komikun`, debug `app.komikun.dev`). Komikku is itself an Android manga reader (min SDK 26, target SDK 36, JVM target 17 / Kotlin) forked from **Mihon** + **TachiyomiSY**. Komikku is this repo's direct upstream/base; Mihon and TachiyomiSY are Komikku's upstreams (and therefore also reachable from here).

Features: configurable reader, downloads/offline reading, trackers (MyAnimeList, AniList, Kitsu, MangaUpdates, Bangumi, Comick, Hikka, Kavita, Komga, MangaBaka, MangaDex, Shikimori, Suwayomi), recommendations, metadata editing, library categories/tags/filters, multi-source browsing and feed tabs.

### Upstreams

| Upstream | Code marker | Strings |
|----------|-------------|---------|
| Mihon | none (base code) | `MR` (`i18n/`) |
| TachiyomiSY | `// SY -->` … `// SY <--` (legacy `// EXH` blocks also come from SY) | `SYMR` (`i18n-sy/`) |
| Komikku (this fork's base upstream) | `// KMK -->` … `// KMK <--` | `KMR` (`i18n-kmk/`) |
| **komi-kun** (this repo) | `// KUN -->` … `// KUN <--` | `KUNMR` (`i18n-kun/`) |

Markers record **divergence from upstream**, never upstream code itself: Mihon is the base (unmarked); `// KMK` / `// SY` mark code inherited from the Komikku / TachiyomiSY upstreams; `// KUN` marks this fork's own code.

---

## Mandatory rules for AI agents

**Read this section before every change.** These rules override shortcuts (e.g. copying nearby `MR` imports or only running `compileDebugKotlin`).

### Keep this guide current

`AGENTS.md` documents the repo **as it is right now** (the tip of `master`). It is living documentation: whenever a change makes any statement here false — architecture, DI, module layout, markers, i18n, build flags, key files, workflows — update this file in the **same** change/PR. If you are on a feature branch that diverges from `master`, describe the branch's state (it is the future `master`). Prefer the current state over history, and delete guidance that no longer applies instead of leaving it stale.

### Git

| Rule | Required behavior |
|------|-------------------|
| Branch | Create a **feature branch** for the task (`git checkout -b <type>/<short-description>`, e.g. `feat/manga-recommendations`). |
| Commit | **OK** on a feature branch when work is ready. **Never** commit directly to `master` / `main` unless the user explicitly asks. |
| Push | **OK** to push the **current feature branch** when work is ready. **Never** push to `master` / `main` unless the user explicitly asks. |
| Merge | Into **`master` / `main`**: **squash-merge** with `gh pr merge --squash --delete-branch` (GitHub's tooling, via `gh`) — **never** a merge commit. Into a **feature branch** (rare): a merge commit is fine, and is how the per-commit signatures are preserved. |

Before `git push`, confirm the current branch is not `master` or `main` (`git branch --show-current`).

### Fork markers

Markers record **what diverges from upstream** — they never mark upstream code itself. Every komi-kun addition or modification to existing code **must** be wrapped:

```kotlin
// KUN -->
// your code here
// KUN <--
```

| Code origin | Marker |
|-------------|--------|
| komi-kun's own code (this repo) | `// KUN -->` … `// KUN <--` |
| Komikku upstream | `// KMK -->` … `// KMK <--` |
| TachiyomiSY upstream | `// SY -->` … `// SY <--` (legacy `// EXH` blocks also come from SY) |
| Mihon (base upstream) | none |

- Use `// KUN` for this fork's own new code. **Never** reuse `// KMK` for komi-kun code: `// KMK` means the code came from the **Komikku** upstream.
- Never label code that is not from TachiyomiSY as `// SY` (or its legacy `// EXH` form), and never label code that is not from Komikku as `// KMK`.
- Keep existing upstream `// KMK` / `// SY` / `// EXH` blocks intact; when changing code inside one, wrap your change in a nested `// KUN` block.
- Markers apply to `.sq` queries and mappers too (use `-- KUN -->` / `-- KUN <--`).

### Internationalization (strings)

| String kind | Module | Resource class | Base folder only |
|-------------|--------|----------------|------------------|
| **komi-kun** new features (Yomitan, dictionaries, OCR, Anki, etc.) | `i18n-kun/` | **`KUNMR`** | `i18n-kun/src/commonMain/moko-resources/base/` |
| Komikku upstream (library-update errors, WebDAV, Discord, etc.) | `i18n-kmk/` | `KMR` | `i18n-kmk/src/commonMain/moko-resources/base/` |
| Mihon upstream | `i18n/` | `MR` | `i18n/src/commonMain/moko-resources/base/` |
| TachiyomiSY upstream | `i18n-sy/` | `SYMR` | `i18n-sy/src/commonMain/moko-resources/base/` |

**Hard rules:**

- **All new komi-kun strings go to `i18n-kun/`** (`import tachiyomi.i18n.kun.KUNMR`). `KMR`, `MR`, and `SYMR` are upstream-owned; reuse existing entries, but add new strings to `i18n-kun/` so upstream merges never conflict.
- Translations in `i18n-kun/` can be added directly for supported locales. Upstream `i18n/`, `i18n-sy/`, and `i18n-kmk/` remain upstream-owned.
- Inside a `// KUN` block, default to **`KUNMR`** even if nearby code imports `KMR` or `MR`.

**Examples (→ `i18n-kun`, not `i18n-kmk` or `i18n`):** dictionary settings & management, OCR settings/actions, AnkiDroid integration, Yomitan backup import.

### Formatting & build verification

**“Build passes” is not enough.** After Kotlin/XML edits, run **in this order** before marking work complete:

```bash
./gradlew spotlessApply    # fix formatting
./gradlew spotlessCheck    # must pass (CI gate)
./gradlew assembleDebug    # or :app:compileDebugKotlin for a faster compile-only check
```

- **Do not** skip `spotlessCheck`. If it fails, run `spotlessApply` and re-run `spotlessCheck`.
- Use `compileDebugKotlin` instead of `assembleDebug` only if the user asked for a quick compile check — Spotless still applies.
- Spotless + ktlint config: `buildSrc/src/main/kotlin/mihon.code.lint.gradle.kts` (trim trailing whitespace, end with newline; non-base i18n locales and `**/build/**` excluded).

---

## Tech stack

- **UI:** Jetpack Compose + Material 3, [Voyager](https://voyager.adriel.cafe/) navigation, Coil 3 for images
- **DI:** [Metro](https://zacsweers.github.io/metro/) (`dev.zacsweers.metro`) — compile-time DI (`@Inject`, `@AssistedInject`, `@DependencyGraph`, `@ContributesIntoMap` / `@ContributesIntoSet`); an **Injekt** (`uy.kohesive.injekt`) interop bridge survives for extension-facing code (`mihon/app/di/injekt/`)
- **Database:** SQLDelight, SQLCipher encryption
- **Network:** OkHttp 5 with DNS-over-HTTPS
- **Serialization:** Kotlinx Serialization (JSON/Protobuf)
- **JS engine:** QuickJS (used by sources)
- **Concurrency:** Kotlin coroutines + Flow for new code; RxJava 1 remains in `source-api` for extension compatibility
- **Toolchain:** JDK 21 to build (matches `.github/.java-version`), bytecode targets Java 17, Gradle 9.8.1, compileSdk 37.2

Version catalogs in `gradle/`: `libs.versions.toml`, `kotlinx.versions.toml`, `androidx.versions.toml`, `compose.versions.toml`, `sy.versions.toml`.

---

## Module layout

| Module | Purpose |
|--------|---------|
| `app/` | UI (`eu.kanade.*`, `exh/`, `mihon/`), Metro ViewModels, the DI graph, workers, build variants |
| `domain/` | Use cases in `…/interactor/` (e.g. `GetManga`), models, repo interfaces |
| `data/` | SQLDelight DB, `*RepositoryImpl` (`tachiyomi.data.*`) |
| `core:common/` | Network (OkHttp), security, storage, shared utils |
| `core:metro/` | Shared Metro helpers (`mihon.core.metro`: `GraphProvider`, `AppCoroutineScope`, `IsDebugBuild`) |
| `core:archive/` | CBZ/archive reading with optional encryption |
| `core:dictionary/` | **GPL-3.0-or-later** — vendored `hoshidicts` engine (nested git submodule + NDK/CMake + JNI) and the Kotlin dictionary facade (`mihon.core.dictionary`) |
| `core:ocr/` | **GPL-3.0-or-later** — PaddleOCR models on an ONNX Runtime backend + `.mokuro` OCR pipeline, box merge and overlay math (`mihon.core.ocr`); its native `.so`s are fetched, not built (see [Licensing & native modules](#licensing--native-modules)) |
| `core-metadata/` | Comic-info metadata parsing |
| `source-api/` / `source-local/` | Extension `Source` API + local source |
| `presentation-core/` | Shared Compose components |
| `presentation-widget/` | Home-screen Glance widget |
| `i18n/` / `i18n-sy/` / `i18n-kmk/` / `i18n-kun/` | Strings → `MR` / `SYMR` / `KMR` / `KUNMR` (see [Internationalization](#internationalization-strings)) |
| `flagkit/` | Country-flag drawables |
| `icons:material-symbols/`, `icons:custom-material-symbols/`, `icons:simple-icons/` | Generated icon packs (`MaterialSymbols`, custom Material Symbols, Simple Icons) |
| `telemetry/` | Firebase/Crashlytics (noop unless `-Pinclude-telemetry`) |
| `baseline-profile/` | Baseline profile and startup benchmark generation (CI-only) |
| `buildSrc/` | Convention plugins and build logic |

Dependency flow: `app` → `domain` → `source-api`; `data` implements `domain` repos. `:app` also depends on the two **GPL-3.0** modules `:core:dictionary` and `:core:ocr`; those two are independent and must **never** depend on each other (see [Licensing & native modules](#licensing--native-modules)).

Package roots: `eu.kanade.tachiyomi.*` (legacy UI), `tachiyomi.*` (domain/data), `mihon.*` (Mihon's newer package root — also used by komi-kun code, e.g. `mihon.feature.*`, `mihon.core.dictionary.*`, `mihon.core.ocr.*`), `exh.*` (enhanced sources, SY).

---

## Architecture

**DI** – [Metro](https://zacsweers.github.io/metro/), not Hilt/Injekt. The graph is `mihon/app/di/AppGraph.kt` (`@DependencyGraph`) with providers/bindings in `mihon/app/di/AppBindings.kt`; it is built in `App.kt` (`createGraphFactory<AppGraph.Factory>().create(...)`) and reached as `context.appGraph` or by injecting `AppGraph`. Most classes need only `@Inject` on the constructor (interactors, repositories, services); use `@Provides`/`@Binds` in `AppBindings` for third-party or interface types, and `@ContributesIntoMap` / `@ContributesIntoSet` for maps/sets. Parameterised types use `@AssistedInject` + `@AssistedFactory`. Feature bindings live in per-feature Metro `@BindingContainer` objects registered once in `AppGraph.bindingContainers` (`DictionaryBindings`, `OcrBindings`, `DictionaryHostBindings`, `OcrHostBindings`), and feature impls are contributed with `@ContributesBinding(AppScope::class)` — this keeps the GPL modules' wiring out of the shared `AppBindings`. Extension-facing code still resolves through the Injekt interop bridge (`mihon/app/di/injekt/`).

**UI & navigation** – Voyager `Screen`s in `eu.kanade.tachiyomi.ui.*`, composables in `eu.kanade.presentation.*`. Base type: `eu.kanade.presentation.util.Screen` (in `Navigator.kt`). State lives in **Metro ViewModels** obtained with `metroViewModel<VM>()` or `assistedMetroViewModel<VM, Factory> { create(...) }`; they extend `androidx.lifecycle.ViewModel`, expose a `StateFlow` / `MutableStateFlow` (often a `State` data class) and run work in `viewModelScope`. Example: `NewUpdateScreen` + `NewUpdateViewModel` in `app/src/main/java/eu/kanade/tachiyomi/ui/more/`.

**Coroutines** – In ViewModels use `viewModelScope`. Helpers in `tachiyomi.core.common.util.lang`: `withIOContext`, `withUIContext`, `CoroutineScope.launchIO` (on a given scope). The top-level `launchIO` / `launchNow` run on **GlobalScope** — avoid them for lifecycle-bound work. `rememberCoroutineScope()` is fine in Compose; long-lived services may own their own scope (`AppCoroutineScope` in `mihon.core.metro`).

**Activities (not Voyager)** – `MainActivity` (shell / Voyager host), `ReaderActivity` + `ReaderViewModel`, `WebViewActivity`, `UnlockActivity`, OAuth login activities, `DeepLinkActivity`. Reader: `ReaderActivity.newIntent(context, mangaId, chapterId)`. Web: both `WebViewScreen` (Voyager) and `WebViewActivity.newIntent(...)`.

**Domain / data** – One class per operation under `domain/src/main/java/tachiyomi/domain/*/interactor/` (verb names like `GetTracksPerManga`, `HideCategory`; no `*Interactor` suffix), annotated `@Inject` so Metro resolves it directly — no module registration. App-specific cases go in `app/src/main/java/eu/kanade/domain/…/interactor/`. `Manga` / `Chapter` are domain models; `SManga` / `SChapter` are source-layer types — convert at the boundary (`SManga.toDomainManga()`, `Manga.toSManga()`, `Chapter.toSChapter()`).

**Database** – SQLDelight in `data/src/main/sqldelight/tachiyomi/`. For schema changes:
1. Add a new `migrations/*.sqm` file
2. Update the `.sq` queries
3. Update the `*RepositoryImpl` mapper
4. Regenerate: `./gradlew :data:generateSqlDelightInterface` (or any compile that touches `:data`)

**Preferences** – `eu.kanade.domain.*.service.*Preferences` (e.g. `SourcePreferences.relatedMangas()`). App preference migrations: `app/src/main/java/mihon/core/migration/migrations/` (`mihon.core.migration.Migration`).

**Images** – Coil 3 (`coil3.*`, `context.imageLoader`), configured in `App.kt`. No Glide/Picasso.

**Logging** – komi-kun / Komikku code: `xLogE()` / `xLog()` from `exh.log`. Mihon code: `logcat { }` from `tachiyomi.core.common.util.system`. Avoid raw `android.util.Log`.

---

## Licensing & native modules

komi-kun's own code is Apache-2.0, but the Yomitan/dictionary/OCR port adds two **GPL-3.0-or-later** modules, so the combined distribution is **GPL-3.0-or-later** while upstream-derived files keep their Apache-2.0 headers. See `NOTICE` and `LICENSES/GPL-3.0-or-later.txt`. The GPL-free seam files (listed in the implementation contract §1.3) stay Apache-2.0 so they can be contributed back to Komikku.

- **`:core:dictionary`** — the vendored `hoshidicts` C++ engine, added as a nested git submodule (`.gitmodules`; pin recorded in `core/dictionary/README.md`) and built with NDK/CMake behind a JNI bridge.
- **`:core:ocr`** — the OCR pipeline, running PaddleOCR **PP-OCRv6 small** models through an **ONNX Runtime** backend (MIT). `libpaddle_ocr.so` (our own Apache-2.0 JNI shim over ORT's C API) and its `libonnxruntime.so` / `libomp.so` runtime deps are **fetched, not built**: `./gradlew :core:ocr:fetchPaddleNativeLibs` reads `core/ocr/paddle-manifest.json`, verifies each file's SHA-256 and writes it into `core/ocr/src/main/jniLibs/<abi>/` (git-ignored). Models (`.onnx` + dict) download at runtime, opt-in and checksum-verified. Because ORT publishes `x86_64` and the ONNX graphs are architecture-independent, **OCR now works on `arm64-v8a`, `armeabi-v7a` and `x86_64`** — the old "OCR unavailable on x86_64" limitation is gone.
- **ABIs** — one set for every native artifact: `arm64-v8a`, `armeabi-v7a`, `x86_64`; 32-bit `x86` is dropped. Set identically in both modules' `ndk.abiFilters` and in `app/build.gradle.kts` `splits.abi`; the universal APK still carries all three.
- **`maven/`** — an in-repo Maven repository declared in `settings.gradle.kts` and holding the vendored `komi-kun/webgpuviewer` fork AAR (tag `ocr-accessors-1`, MIT). It exists because JitPack cannot build that AGP 9.x fork; delete `maven/` and its repository line once the accessors land upstream.
- **GPL boundary** — `./gradlew verifyGplFree` (wrapping `scripts/verify-gpl-free.sh`) fails if any GPL-free seam file imports a GPL package. Keep it green whenever you touch a seam file.
- **Maintenance skills** — the recurring upkeep for this port is scripted under `.agents/skills/`: `immersion-deps-sync` (bump the `hoshidicts` submodule, rebuild the `webgpuviewer` fork AAR in `maven/`, track Chimahon), `ocr-model-release` (build/publish the OCR native artifact), `yomitan-corpus-sync` (refresh the conformance corpus), and `gpl-boundary` (this boundary + the licensing artifacts).

---

## Extensions & sources

- Catalog sources: installable APK extensions (not in this repo).
- In-repo: delegated sources and metadata in `exh/` (E-Hentai, NHentai, MangaDex, `exh/recs/`).
- `source-api`: `eu.kanade.tachiyomi.source.*` — **avoid breaking extension ABI**.

---

## Adding a komi-kun feature

1. **Interactor:** `domain/src/main/java/tachiyomi/domain/<area>/interactor/MyFeature.kt`, annotated `@Inject` (Metro resolves it; add a `@Provides` / `@Binds` to `mihon/app/di/AppBindings.kt` — or a feature `@BindingContainer`, as the GPL dictionary/OCR modules do — only if it needs manual wiring)
2. **UI:** Metro ViewModel + `Screen` under `app/src/main/java/eu/kanade/tachiyomi/ui/` (see [Architecture](#architecture))
3. **Strings:** `i18n-kun/src/commonMain/moko-resources/base/strings.xml`, referenced via `KUNMR`
4. **Markers:** wrap edits to existing files in `// KUN -->` / `// KUN <--`

Reference: `domain/src/main/java/tachiyomi/domain/category/interactor/HideCategory.kt` (an `@Inject` interactor) and `app/src/main/java/eu/kanade/tachiyomi/ui/more/NewUpdateScreen.kt` + `NewUpdateViewModel.kt` (ViewModel + Screen).

---

## Build & CI

Build types (`applicationIdSuffix`): `debug` (`.dev`), `release`, `releaseTest` (`.rt`), `foss` (`.foss`), `nightly` (`.beta`, CI default), `benchmark` (`.benchmark`).

Gradle `-P` flags (`buildSrc/src/main/kotlin/mihon/buildlogic/BuildConfig.kt`):

| Flag | Effect |
|------|--------|
| `include-telemetry` | Firebase Analytics + Crashlytics |
| `enable-updater` | In-app update checker |
| `disable-code-shrink` | Skip R8 minification |
| `include-dependency-info` | Dependency metadata in APK |

```bash
./gradlew spotlessApply                    # format
./gradlew spotlessCheck                    # CI gate
./gradlew :app:compileDebugKotlin          # compile-only check
./gradlew assembleDebug                    # debug APK
./gradlew assembleNightly                  # nightly (beta) APK, CI equivalent
./gradlew assembleNightly -Pinclude-telemetry -Penable-updater  # full CI build
./gradlew assembleRelease                  # release APK
./gradlew testDebugUnitTest              # CI unit tests
./gradlew test                             # all unit tests
./gradlew installDebug                     # install to device/emulator
./gradlew :data:generateSqlDelightInterface  # after .sq / .sqm changes
./gradlew verifyGplFree                    # GPL-free boundary check (scripts/verify-gpl-free.sh)
./gradlew :core:ocr:fetchPaddleNativeLibs  # fetch + verify the OCR native libs (auto-invoked by assemble*)
```

**Parallel builds.** When more than one build/agent runs in the same project directory, serialise Gradle with a shared lock so the daemon and the git index never race: `flock /tmp/opencode/gradle.lock ./gradlew <tasks>`. The Yomitan/OCR port follows this convention (`docs/design/implementation/AGENT-RUNBOOK.md`).

**Workflows** (`.github/workflows/`):
- `build_pull_request.yml` – PR validation: dependency review → wrapper validation → `spotlessCheck` → `assembleNightly` → `testDebugUnitTest` → APK signing (authorized forks)
- `build_push.yml` – push to `master`: full build + signing
- `build_nightly.yml` – **the release path**: manual dispatch on `master` → signed `assembleNightly -Pdist=ci` → a prerelease tagged `r<commit-count>` (4 APKs). See the `nightly-release` skill.
- `build_release.yml` – optional `v*`-tag stable build; **dormant** (this fork ships nightlies only)
- `build_benchmark.yml` – manual benchmark builder

**Releases are nightlies.** There is no stable channel and the version numbers carry no meaning: the nightly `versionName` is `1.14.1-<commit-count>` (the commit count is the version), `versionCode` stays `83`, and the in-app updater matches the `r<count>` tags and offers an update when the release count exceeds the installed build's `COMMIT_COUNT`. `CI_SETUP.md` documents the setup and the `nightly-release` skill documents cutting one.

---

## Tests

- **Framework:** JUnit (Jupiter) + Kotest assertions + MockK
- **Locations:** `domain/src/test/`, `app/src/test/` (e.g. `app/src/test/java/mihon/core/migration/MigratorTest.kt`). No broad UI test suite — focus on domain and critical logic.
- **Single class:** `./gradlew :domain:testDebugUnitTest --tests "*.ClassName"`

---

## Key files

- `App.kt` – Metro graph bootstrap, logging setup, Coil initialization
- `MainActivity.kt` – Voyager navigation host
- `app/src/main/java/mihon/app/di/AppGraph.kt` – Metro `@DependencyGraph` (the DI graph)
- `app/src/main/java/mihon/app/di/AppBindings.kt` – `@Provides` / `@Binds` / contributed bindings
- `buildSrc/src/main/kotlin/mihon/buildlogic/BuildConfig.kt`, `AndroidConfig.kt` – flags, SDK versions
- `app/build.gradle.kts`, `settings.gradle.kts`
- `NOTICE`, `LICENSES/GPL-3.0-or-later.txt` – licensing artifacts for the GPL dictionary/OCR port
- `scripts/verify-gpl-free.sh` – `verifyGplFree` GPL-free boundary check
- `core/dictionary/README.md`, `core/ocr/README.md` – pins, layout and provenance for the GPL native modules
- `CONTRIBUTING.md` – prerequisites and contribution process

---

## Upstream cherry-pick log

`cherrypick_log.md` (repo root) lists upstream commits since 2024-01-01 with their cherry-pick status on `master`. The main upstream is `mihon/main`, the additional upstream is `tachiyomiSY/master`, and Renovate commits are excluded. All of these are set in `.agents/skills/cherrypick-log/config.json`, and `--since` overrides the start date.

- The list is generated by `.agents/skills/cherrypick-log/update_cherrypick_log.py`.
- `?` rows are reviewed with the `cherrypick-log` skill.
- `X` means "won't pick". The maintainer sets it by hand, and the skill sets it for version-bump-only commits.
- Commits that cancel each other out (revert chains the fork has none of) are flagged `?` by the script with a `Cancels out (script):` note, for review.
- Pre-2024 rows at the end come from a one-off pass and may be deleted by hand.
- Never hand-edit columns other than Status and Notes; use the script's `mark` command.

Rules, commands and the porting guide: `.agents/skills/cherrypick-log/README.md`.

---

## Common issues

- **Gradle OOM** – the daemon uses `-Xmx4g` (`gradle.properties`); run `./gradlew --stop` and retry.
- **Spotless failures** – run `spotlessApply`, then `spotlessCheck`.
- **SQLDelight errors** – run `./gradlew :data:generateSqlDelightInterface` after schema changes (see [Database](#architecture)).
- **Missing `google-services.json` / `client_secrets.json`** – these are CI secrets; builds without `-Pinclude-telemetry` don't need them.
- **First build is slow** – it downloads ~1 GB of dependencies; later builds use the Gradle cache.
