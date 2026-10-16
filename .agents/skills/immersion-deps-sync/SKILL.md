---
name: immersion-deps-sync
description: Keep komi-kun's vendored immersion dependencies current — the `hoshidicts` C++ dictionary-engine submodule (fork `komi-kun/hoshidicts`), the `webgpuviewer` fork vendored under `maven/`, and the port source Chimahon. Use when asked to update/bump hoshidicts or webgpuviewer, rebuild the dictionary engine, refresh the vendored webgpu AAR, or pull in Chimahon immersion changes.
---

# Keeping the immersion dependencies current

The Yomitan/OCR port vendors three upstreams. This skill is the upkeep guide; the design rationale is
in `docs/design/yomitan-manga-ocr-port.md` and `docs/design/implementation/00-implementation-contract.md`.

| Dependency | Where it lives | License | Pin |
|---|---|---|---|
| `hoshidicts` (dictionary engine, C++) | git submodule `core/dictionary/src/main/cpp/hoshidicts` → `komi-kun/hoshidicts` | GPL-3.0 | recorded in `core/dictionary/README.md` |
| `webgpuviewer` fork (reader coordinate accessors) | in-repo Maven repo `maven/`, pin in `gradle/libs.versions.toml` | MIT | tag `ocr-accessors-1` |
| Chimahon (port source: dictionary/OCR/Anki) | not vendored — reference only | GPL-3.0 | `Chimahon/chimahon` |

For the base/fork upstreams (Komikku/Mihon/TachiyomiSY) use `komikku-sync` / `upstream-port` instead.

## A. Update the `hoshidicts` submodule

Our fork carries a few commits on top of the base — keep them, or the Android/CI build breaks:

- `.gitmodules`: `external/utf8proc` URL is **HTTPS** (upstream uses `git@github.com:…`, which fails CI).
- `CMakeLists.txt`: `HOSHIDICTS_BUILD_CLI` / `HOSHIDICTS_BUILD_BENCHMARKS` options gate the optional
  targets (default `OFF`).
- Host-portability includes (`<cstdint>` in `include/hoshidicts/query.hpp`, `<climits>` in
  `src/lookup.cpp`) so the device-free host build works.

Procedure:

```bash
# in a clone of komi-kun/hoshidicts (remotes: origin = our fork, upstream = Manhhao/hoshidicts)
git fetch upstream && git log --oneline HEAD..upstream/main     # what's new
git rebase upstream/main                                        # replay our 3 guard commits
cmake -S . -B build -DHOSHIDICTS_BUILD_CLI=OFF -DHOSHIDICTS_BUILD_BENCHMARKS=OFF \
  && cmake --build build --target hoshidicts -j                 # host build must be green
git push origin HEAD
```

Then bump the app's submodule pointer and record the new pin:

```bash
cd core/dictionary/src/main/cpp/hoshidicts && git fetch origin && git checkout <new-sha> && cd -
git add core/dictionary/src/main/cpp/hoshidicts
# update the pin line in core/dictionary/README.md
./gradlew :core:dictionary:compileDebugKotlin :core:dictionary:externalNativeBuildDebug
```

- Never track a branch — the submodule must point at a commit.
- Do **not** use the `main-mit` hoshidicts branch to claim an Apache-clean engine (see `gpl-boundary`).
- If the JNI surface changed, update `core/dictionary/src/main/cpp/hoshidicts_jni.cpp` +
  `HoshiDictsNative` to match (contract §2.1/§2.2).

## B. Rebuild the vendored `webgpuviewer` fork

The fork adds a few **additive, upstreamable** accessors so the OCR overlay can map coordinates without
touching `internal` members or a `GPUTexture`:
`ImageViewerState.visiblePages` / `rectOf(page)` / `transformVersion`;
`Image.placement(surfaceWidth, surfaceHeight, …)` + `Int` overloads for `ImagePage`/`ImageSpread`
rects; `ImageViewerContinuousState` scroll-offset getter.

It is consumed from the **in-repo Maven repo** `maven/com/github/komi-kun/webgpuviewer/library/<version>/`
(declared in `settings.gradle.kts`) because **JitPack cannot build the AGP 9.x project** — do not switch
it to JitPack.

When the fork needs a new pin (rebase on upstream, or new accessors):

```bash
# in a clone of komi-kun/webgpuviewer
git fetch origin && git rebase origin/master        # or apply the new accessor patch
./gradlew :library:assembleRelease                   # must build
# publish to a local Maven repo with the fork's coordinates, then copy into the app repo:
#   maven/com/github/komi-kun/webgpuviewer/library/<newtag>/{*.aar,*.pom,*.module,-sources.jar,-javadoc.jar}
git tag <newtag> && git push origin HEAD --tags
```

Then in the app repo update `[versions] webgpuviewer = "<newtag>"` in `gradle/libs.versions.toml`,
replace the `maven/…/ocr-accessors-1/` directory, and verify:

```bash
./gradlew :app:dependencyInsight --configuration debugRuntimeClasspath --dependency webgpuviewer
./gradlew :app:compileDebugKotlin
```

The AAR is ~15 MB and bundles `jni/<abi>/libwebgpu_c_bundled.so` + `libresize.so` (it is not
ABI-neutral, despite what an old contract note said).

**Eventually drop the fork:** open a PR to `mpreg-ca/webgpuviewer` with the accessors; once merged and
released, repoint `webgpuviewer` to the upstream coordinate, delete `maven/` and the
`maven { url = uri("$rootDir/maven") }` line in `settings.gradle.kts`.

## C. Track the port source (Chimahon)

Chimahon is the GPL-3.0 app we ported the immersion features from (dictionary engine, OCR glue, Anki,
reader overlay). It is **not** vendored, so its updates do not flow automatically. Watch
`Chimahon/chimahon` for changes under `chimahon/`, `chimahon-local-ocr/`, `app/…/ui/dictionary`,
`app/…/data/ocr`, and the reader OCR bits, and port only the **manga-relevant** parts (drop
novel/anime/video/screen-OCR/player). Use the Yomitan behavior spec
`docs/design/yomitan-spec/` and the design doc as the rulebook; keep markers/i18n per `AGENTS.md`.

## Verify

```bash
flock /tmp/opencode/gradle.lock ./gradlew spotlessCheck
flock /tmp/opencode/gradle.lock ./gradlew :core:dictionary:compileDebugKotlin :core:dictionary:externalNativeBuildDebug
flock /tmp/opencode/gradle.lock ./gradlew :core:ocr:compileDebugKotlin
flock /tmp/opencode/gradle.lock ./gradlew :app:assembleDebug
```
