# Yomitan / OCR port — implementation docs

Design, plan and contract for the dictionary (Yomitan) + OCR port on branch
**`feat/introduce-ocr-and-yomitan`**. `AGENTS.md` remains the mandatory rulebook; these docs are the
port's plan and record.

## Reading order

1. `docs/design/yomitan-manga-ocr-port.md` — the design and the licensing decision (combined work is
   GPL-3.0-or-later). Behavior specs live in `docs/design/yomitan-spec/`.
2. **`00-implementation-contract.md`** — the **frozen source of truth** (v1.1). Where a plan below
   contradicts it, the contract wins; implementation codes against §2 only.
3. `01-architecture-build.md` … `05-testing-and-anki.md` — the per-workstream plans (01 build,
   02 dictionary UI, 03 OCR, 04 reader, 05 tests + Anki).
4. `AGENT-RUNBOOK.md` — the rules for agents working the branch (ownership, signing, Gradle serialisation).
5. `reviews/` — review/verification reports, starting with `00-contract-review.md` (the fresh-eyes
   review that produced v1.1).

## Waves

Waves run as defined in the contract §4; a wave never runs two editors against one file.

| Wave | Name | Exit artifact |
|---|---|---|
| W0 | Forks / prep | `hoshidicts` pin recorded; `webgpuviewer` tag `ocr-accessors-1` |
| W1 | Build scaffold | both GPL modules compile with stubs; `verifyGplFree` runs |
| W2 | Libraries | phase-1 headless dictionary lookup test passes |
| W3 | App integration | app compiles; settings/dictionary/OCR wire up |
| W4 | Wiring + Anki | full OCR overlay across all four readers; Anki stubs |
| W5 | Verify / docs | all gates green (contract §5) |

## Commits

Oldest → newest on the branch (`git log --oneline master..HEAD`). Track = the workstream that owns
the change.

| # | Commit | Track | Subject |
|---|---|---|---|
| 1 | `22e74981bf` | docs | add Yomitan/OCR port design, behavior specs, and implementation contract |
| 2 | `d4d99f750e` | build | vendor `hoshidicts`, add `:core:dictionary` / `:core:ocr` modules |
| 3 | `5c261bb868` | build | point `webgpuviewer` at the fork, add `anki-api`, wire `:app` |
| 4 | `3e75323cce` | ci | check out submodules recursively, provision NDK/CMake |
| 5 | `a5801eca3d` | build | consume the `webgpuviewer` fork via the in-repo `maven/` repo |
| 6 | `3e3f5258d5` | ocr | Apache `ReaderOcrSource` enum in `:core:common` |
| 7 | `7e656b2329` | reader | Apache reader OCR/dictionary seam interfaces |
| 8 | `6bc599f5fa` | di | create and register the OCR/dictionary `@BindingContainer`s |
| 9 | `0fbc9bf4d1` | build | add the `verifyGplFree` GPL-free boundary check |
| 10 | `eb26ee2ad2` | dictionary | harden the `hoshidicts` JNI, add engine unit tests |
| 11 | `928ecd79df` | ocr | implement the `:core:ocr` pipeline, merger seam and Paddle bridge |
| 12 | `0b3e00a0ac` | tests | pinned Yomitan conformance corpus and tooling |
| 13 | `47d81fcc7e` | i18n | dictionary, Anki and OCR string keys (`KMR`) |
| 14 | `796d05608b` | ocr | add the chapter `is_ocr_ready` database flag |
| 15 | `737609ce0f` | ocr | OCR services, model downloader and settings screen |
| 16 | `93f297639b` | dictionary | Yomitan renderer assets |
| 17 | `c747e82e86` | dictionary | core repository, profiles and WebView shell |
| 18 | `d2fa5cf04a` | dictionary | reader dictionary host impl and bindings |
| 19 | `510e95319f` | dictionary | popup, entry WebView, warm-up and tab |
| 20 | `a49d6a59dd` | dictionary | settings screens, tab and notification channels |
| 21 | `e99de94b4d` | ocr | write chapter `is_ocr_ready` through insert/update |
| 22 | `23d75ac634` | ocr | wire the reader OCR host, queue worker and download hooks |
| 23 | `2062902ffc` | ocr | clean-room `.mokuro` sidecar HTTP cache |
| 24 | `66d5195a58` | reader | OCR viewports, overlay hook and long-press routing |

## Verification

Gate checklist: contract §5. Run the shared-lock Gradle form while other agents share this project
directory:

```bash
flock /tmp/opencode/gradle.lock ./gradlew spotlessApply spotlessCheck
flock /tmp/opencode/gradle.lock ./gradlew :core:dictionary:compileDebugKotlin :core:ocr:compileDebugKotlin
flock /tmp/opencode/gradle.lock ./gradlew testDebugUnitTest
bash scripts/verify-gpl-free.sh          # or: ./gradlew verifyGplFree
```

Per-wave results and review write-ups are recorded in **`reviews/`** (see
`reviews/00-contract-review.md` for the format). The GPL-free boundary check is
`scripts/verify-gpl-free.sh`, surfaced as `./gradlew verifyGplFree` (contract §1.9).

## Maintenance skills

The recurring upkeep for this port is documented as agent skills under `.agents/skills/` — start there
before doing any of these by hand:

| Skill | Use it for |
|---|---|
| `immersion-deps-sync` | Bumping the `hoshidicts` submodule pin (rebuild + re-pin), rebuilding the `webgpuviewer` fork AAR in `maven/`, tracking Chimahon. |
| `ocr-model-release` | Rebuilding/publishing the Paddle OCR artifact (`komi-kun/komi-kun-ocr-models`) and updating `core/ocr/paddle-manifest.json`. |
| `yomitan-corpus-sync` | Refreshing the pinned Yomitan conformance corpus when upstream tests change. |
| `gpl-boundary` | Keeping the GPL-free seam set + `verifyGplFree` green and the licensing artifacts correct. |
