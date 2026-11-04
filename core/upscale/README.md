# `:core:upscale` — reader page upscaling (GPL-3.0-or-later)

On-device upscaling of reader pages with the MangaJaNai / IllustrationJaNai ONNX models, on an ONNX
Runtime backend.

**Licence:** this module is **GPL-3.0-or-later**. The model weights it downloads are **not** GPL and
are **not** part of the app: they are **CC BY-NC 4.0** (non-commercial only), fetched at runtime from
`komi-kun/komi-kun-upscale-models` release `upscale-models-v1`, verified by SHA-256, and never bundled
in an APK. See `NOTICE` and `tools/upscale-models/README.md`.

Design, measurements and known limitations: [`docs/design/upscaling.md`](../../docs/design/upscaling.md).

---

## Module layout

| Package | What it is |
|---|---|
| `tile/` | pure geometry and pixel math: `TileGeometry` (grid, per-tile rect + reflection pads, uniform tensor shape), `ReflectPad`, `TileBlender` (half-sine seam blend), `BandPlan` (vertical splitting for pages over the cap), `TileAssembler` (row-major blend into a surface) |
| `model/` | `UpscaleModel` (one catalog entry), `UpscaleModelCatalog` (every model, by family/scale/bucket), `UpscaleRouter` (`route(...)` — the one routing rule), `GrayscaleDetector` (`isGrayscale(...)`), `ModelDownloader` (manifest, download, verify, prune, installed marker; enforces the `unmeteredOnly` preference at the start of a run and before every file, and rejects a manifest whose `name`/`path` is not a single path segment) |
| `engine/` | `UpscaleEngine` (single `upscale-inference` thread; tile pipeline; band loop; memory-pressure tile fallback), `OrtTileInference` (one ONNX Runtime session per model file + tensor shape; WebGPU → XNNPACK → CPU chain), `PageDecoder`, `UpscaleSession` |
| `cache/` | `UpscaleCache` — synchronous lookup, `.part` + rename writes, `cacheMb` budget, 30-day TTL |
| `di/` | `UpscaleBindings` — the module's Metro `@BindingContainer` (`UpscaleCache`, `ModelDownloader`, `UpscaleEngine`, `UpscaleService`, all `@SingleIn(AppScope::class)`), registered in `AppGraph.bindingContainers` |
| — | `UpscaleService` — the reader-facing facade: cache lookup, request queue, `focus`, cancellation |

The Apache gate (`UpscalePreferences`, `UpscaleMode`) lives in `:core:common`, never here, so the
GPL-free reader can read it without importing GPL code. The reader talks to this module through the
Apache seam `eu.kanade.tachiyomi.ui.reader.upscale.ReaderUpscaleHost`; the GPL implementation is
`mihon.feature.upscale.*` in `:app`.

**Hard rule:** this module must never depend on `:core:dictionary` or `:core:ocr`.

---

## The engine and the cache in one paragraph each

**Engine.** `UpscaleService` is the only caller of `UpscaleEngine`, and everything it starts runs on
the engine's single `upscale-inference` thread — one session, one inference at a time, no locks. A
page is decoded once, routed (`GrayscaleDetector` + `UpscaleRouter.route`), split into bands when its
output would exceed `UpscalePreferences.maxOutputMegapixels`, and each band is cut into tiles whose
crops carry their neighbours' real pixels (16 px overlap) and are reflect-padded to the page's uniform
tensor shape. Finished tile blocks are blended horizontally, row buffers vertically, and band seams
with the same half-sine band. Rows leave for the output surface as soon as no later tile row can
touch them, so nothing page-sized is ever allocated besides the source and the output.

**Cache.** `UpscaleCache.get(key)` is one `File.isFile` — it runs on the page-decode path, which
cannot suspend. The key carries the page identity (`sourceLength`/`sourceLastModified` included, so a
re-downloaded chapter misses), the model, the scale, `UpscalePreferences.cacheSignature()` and
`ENGINE_GENERATION`. Writes go through a `.part` file and a rename, then prune to `cacheMb`
(32–4096 MB, 30-day TTL, oldest first). Bump `ENGINE_GENERATION` whenever the pipeline's output for
the same key changes.

---

## Model release pin

| What | Where |
|---|---|
| Release | `komi-kun/komi-kun-upscale-models`, tag `upscale-models-v1` |
| Manifest URL | `ModelDownloader.DEFAULT_MANIFEST_URL` |
| Installed marker | `filesDir/upscale/models/installed-release.txt` (written only after every selected file verified) |
| Drift guard | `src/test/resources/upscale-manifest.json` — a byte-identical copy of the published manifest, parsed by `UpscaleModelManifestTest` with the production DTOs |

Re-publishing the release means, in the **same** change: build/publish with
`tools/upscale-models/fetch_and_publish.py`, copy the new `manifest.json` over
`src/test/resources/upscale-manifest.json`, and update `DEFAULT_MANIFEST_URL` if the tag changed.
`./gradlew :core:upscale:testDebugUnitTest` then fails if the catalog and the release disagree (a
catalog model with no asset, an asset the catalog cannot route to, a non-64-hex hash, a non-positive
size, a `baseUrl` that does not match the pin). Procedure:
[`tools/upscale-models/README.md`](../../tools/upscale-models/README.md).

The measured provider starts are pinned in code: `UpscaleEngine.providerFor` starts ESRGAN/SPAN_S on
WebGPU and FDAT_M on the CPU kernels (WebGPU returns NaN for the fp16 FDAT_M exports at a 512/384 px
tile).

**The ONNX Runtime AAR is a second version axis and is gated.** `libs.versions.toml`'s `onnxruntime`
pins the AAR this module compiles against; `core/ocr/paddle-manifest.json` pins the
`libonnxruntime.so` the Paddle shim `dlopen`s, and `app`'s `pickFirsts` collapses the two identical
paths. `./gradlew :app:verifyOrtLockstep` (wired into `preBuild`) hashes the AAR's
`jni/<abi>/libonnxruntime.so` per ABI against the manifest and fails naming the ABI and both
hashes/sizes — so bumping one without the other is a build failure, not a `dlopen` failure on a
device.

---

## Running the device benchmark

`src/androidTest/.../bench/EngineBenchmarkTest.kt` runs the real engine, the real `OrtTileInference`
and real pages over published weights, and writes a JSON report. It is **not** part of CI's `test`
task; a missing asset skips the test rather than failing it.

Assets are never in the repository (CC BY-NC 4.0 weights, generated pages). Push them into the *test*
package's external files dir:

```sh
PKG=mihon.core.upscale.test   # the instrumented test package (`BenchmarkAssets` shows how it resolves)
adb shell mkdir -p /sdcard/Android/data/$PKG/files/upscale-bench/models \
                   /sdcard/Android/data/$PKG/files/upscale-bench/pages
adb push /tmp/opencode/upscale-models/bench/models/. /sdcard/Android/data/$PKG/files/upscale-bench/models/
adb push /tmp/opencode/upscale-models/bench/pages/.  /sdcard/Android/data/$PKG/files/upscale-bench/pages/

ANDROID_HOME=/home/luca/Android/Sdk ./gradlew :core:upscale:connectedDebugAndroidTest
```

`tools/upscale-models/fetch_and_publish.py` leaves `bench/models/` (the 22 `.onnx`) and `bench/pages/`
(one grayscale 1200×1600 page that routes to a MangaJaNai bucket, one RGB 1125×1600 page that routes
to the selected IllustrationJaNai model) staged for that push.

The matrix is pruned on the fly — a provider that cannot open a session is skipped for the rest of
that model, a run over `RUN_TIMEOUT_MS` records and skips the remaining tile sizes for that
configuration, and a global deadline bounds the whole test — so it can run unattended.

---

## Tests

| Test | Guards |
|---|---|
| `tile/TileGeometryTest`, `TileBlenderTest`, `BandPlanTest` | the grid, the pads, the half-sine weights, band splitting/rebalancing |
| `model/UpscaleRouterTest`, `GrayscaleDetectorTest` | the routing rule (a colour page never gets MangaJaNai) and the sampler thresholds |
| `model/UpscaleModelCatalogTest` | catalog ids/file names are unique, buckets and scales complete, the Apache preference defaults still name real catalog models |
| `model/UpscaleModelManifestTest` | catalog ↔ release lockstep (see above) |
| `model/ModelDownloaderTest` | SHA-256/size verification (a short body with a matching hash is still rejected), `.part` cleanup, stale pruning via the allow-list, the installed-release marker, the unmetered-only refusal (including a metered handover mid-run), and manifest names that would escape the models directory |
| `engine/UpscalePipelineTest` | the tile pipeline end to end with a fake `TileInference` |
| `cache/UpscaleCacheTest` | key identity, budget/TTL pruning, `.part` writes |
| `UpscaleServiceTest` | queue coalescing, `focus` prioritisation, cancelling, cache writes |
| `androidTest/bench/EngineBenchmarkTest` | the on-device numbers this module's constants come from |
