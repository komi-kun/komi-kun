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
| `webgpuviewer` fork (reader coordinate accessors) | in-repo Maven repo `maven/`, pin in `gradle/libs.versions.toml` | MIT | tag `ocr-accessors-2` (base upstream `53`) |
| Chimahon (port source: dictionary/OCR/Anki) | not vendored — reference only | GPL-3.0 | `Chimahon/chimahon` |

For other dependency forks (e.g. `komi-kun/UniFile`) use `unifile-sync`; for the base/fork upstreams (Komikku/Mihon/TachiyomiSY) use `komikku-sync` / `upstream-port`.

---

## A. Update the `hoshidicts` submodule

Our fork carries a few commits on top of the base (`Manhhao/hoshidicts`) — keep them, or the Android/CI build breaks:

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
env -u SSH_AUTH_SOCK git push origin HEAD
```

Then bump the app's submodule pointer and record the new pin:

```bash
cd core/dictionary/src/main/cpp/hoshidicts && git fetch origin && git checkout <new-sha> && cd -
git add core/dictionary/src/main/cpp/hoshidicts
# update the pin line in core/dictionary/README.md
flock /tmp/opencode/gradle.lock ./gradlew :core:dictionary:compileDebugKotlin :core:dictionary:externalNativeBuildDebug
```

- Never track a branch — the submodule must point at a commit.
- Do **not** use the `main-mit` hoshidicts branch to claim an Apache-clean engine (see `gpl-boundary`).
- If the JNI surface changed, update `core/dictionary/src/main/cpp/hoshidicts_jni.cpp` +
  `HoshiDictsNative` to match (contract §2.1/§2.2).

---

## B. Rebuild the vendored `webgpuviewer` fork

Upstream `mpreg-ca/webgpuviewer` publishes numeric release tags (`52`, `53`, ...). When upstream Mihon
or `origin/master` bumps `webgpuviewer = "<version>"`, the fork must rebase its coordinate accessors
onto that new upstream version.

The fork adds a few **additive, upstreamable** accessors so the OCR overlay can map coordinates without
touching `internal` members or a `GPUTexture`:
- `ImageViewerState.visiblePages`
- `ImageViewerState.rectOf(page)`
- `ImageViewerState.transformVersion`
- `ImageViewerContinuousState.scrollOffsetY`
- `Image.placement(surfaceWidth, surfaceHeight, …)` + `Int` overloads for `ImagePage`/`ImageSpread` rects.

It is consumed from the **in-repo Maven repo** `maven/com/github/komi-kun/webgpuviewer/library/<version>/`
(declared in `settings.gradle.kts`) because **JitPack cannot build the AGP 9.x project** — do not switch
it to JitPack.

### Sync & Rebuild Procedure

In a clone of `webgpuviewer` (e.g. `/home/luca/development/webgpuviewer`):

```bash
# 1. Ensure remotes are configured
git remote add origin https://github.com/mpreg-ca/webgpuviewer.git 2>/dev/null || true
git remote add fork git@github.com:komi-kun/webgpuviewer.git 2>/dev/null || true

# 2. Fetch new upstream tags and our fork branches
git fetch origin --tags
git fetch fork

# 3. Create a branch based on the new upstream tag (e.g., 53)
UPSTREAM_TAG="53"
NEW_TAG="ocr-accessors-2"
git checkout -b feat/ocr-transform-accessors-${UPSTREAM_TAG} ${UPSTREAM_TAG}

# 4. Cherry-pick the accessor commit from the previous accessor branch/tag
# (commit "Add GPUTexture-free coordinate mapping accessors", tag ocr-accessors-1: 792aef3)
git cherry-pick 792aef3b6886343ee38a9140eefdc98114869dfd

# 5. Build release AAR
./gradlew :library:assembleRelease

# 6. Tag the release and push to komi-kun/webgpuviewer
git tag -m "${NEW_TAG}: upstream ${UPSTREAM_TAG} + ocr transform accessors" ${NEW_TAG}
env -u SSH_AUTH_SOCK git push fork feat/ocr-transform-accessors-${UPSTREAM_TAG} ${NEW_TAG}

# 7. Publish to a temporary Maven local repo
PUBLISH_VERSION=${NEW_TAG} ./gradlew :library:publishMavenPublicationToMavenLocal -Dmaven.repo.local=/tmp/webgpu-m2
```

### Update the komi-kun App Repository

1. Replace the vendored artifacts under `maven/`:
   ```bash
   rm -rf maven/com/github/komi-kun/webgpuviewer/library/ocr-accessors-*
   mkdir -p maven/com/github/komi-kun/webgpuviewer/library/${NEW_TAG}
   cp -r /tmp/webgpu-m2/com/github/komi-kun/webgpuviewer/library/${NEW_TAG}/* maven/com/github/komi-kun/webgpuviewer/library/${NEW_TAG}/
   ```

2. Update `gradle/libs.versions.toml`:
   ```toml
   # KUN -->
   # komi-kun/webgpuviewer fork (contract §2.6): tag `ocr-accessors-2`
   webgpuviewer = "ocr-accessors-2"
   # KUN <--
   ```

3. Verify dependency resolution and app compilation:
   ```bash
   flock /tmp/opencode/gradle.lock ./gradlew :app:dependencyInsight --configuration debugRuntimeClasspath --dependency webgpuviewer
   flock /tmp/opencode/gradle.lock ./gradlew :app:compileDebugKotlin
   flock /tmp/opencode/gradle.lock ./gradlew spotlessCheck
   ```

**Eventually drop the fork:** once the accessors land in `mpreg-ca/webgpuviewer` upstream and upstream
ships a release, repoint `webgpuviewer` to `ca.mpreg:webgpuviewer`, delete `maven/` and the
`maven { url = uri("$rootDir/maven") }` line in `settings.gradle.kts`.

---

## C. Track the port source (Chimahon)

Chimahon is the GPL-3.0 app we ported the immersion features from (dictionary engine, OCR glue, Anki,
reader overlay). It is **not** vendored, so its updates do not flow automatically. Watch
`Chimahon/chimahon` for changes under `chimahon/`, `chimahon-local-ocr/`, `app/…/ui/dictionary`,
`app/…/data/ocr`, and the reader OCR bits, and port only the **manga-relevant** parts (drop
novel/anime/video/screen-OCR/player). Use the Yomitan behavior spec
`docs/design/yomitan-spec/` and the design doc as the rulebook; keep markers/i18n per `AGENTS.md`.

---

## Verify

```bash
flock /tmp/opencode/gradle.lock ./gradlew spotlessCheck
flock /tmp/opencode/gradle.lock ./gradlew :core:dictionary:compileDebugKotlin :core:dictionary:externalNativeBuildDebug
flock /tmp/opencode/gradle.lock ./gradlew :core:ocr:compileDebugKotlin
flock /tmp/opencode/gradle.lock ./gradlew :app:compileDebugKotlin
```
