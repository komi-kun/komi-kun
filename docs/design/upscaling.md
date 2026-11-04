# Design: reader page upscaling

Status: **shipped (nightly), device-measured, settings UI landed in phase 4b** · Branch target:
`master` · Models: [komi-kun/komi-kun-upscale-models](https://github.com/komi-kun/komi-kun-upscale-models)

On-device upscaling of reader pages with the MangaJaNai / IllustrationJaNai ONNX models, on an ONNX
Runtime backend. Everything runs on the device, in the background, as you read: no upload, no cloud,
no network at inference time.

This document covers the architecture, the tile/band/seam algorithm and why it is shaped that way,
the measured performance, the model catalogue and its release process, the licence boundary, the
default-off gate, and the known limitations.

---

## 1. Shape of the feature

| Piece | Where | Licence |
|---|---|---|
| Preferences (`UpscalePreferences`, `UpscaleMode`) | `core/common/.../preference/UpscalePreferences.kt` | Apache-2.0 |
| Reader seam (`ReaderUpscaleHost` / `ReaderUpscaleSession`) | `app/.../ui/reader/upscale/` | Apache-2.0 |
| Seam implementation (`ReaderUpscaleHostImpl` / `ReaderUpscaleSessionImpl`) | `app/.../mihon/feature/upscale/` | GPL-3.0-or-later |
| Settings screen (`SettingsUpscaleScreen`) | `app/.../presentation/more/settings/screen/` | GPL-3.0-or-later |
| Engine, cache, models, service | `:core:upscale` (`mihon.core.upscale.*`) | GPL-3.0-or-later |
| Weights | runtime download, never bundled | CC BY-NC 4.0 |

**The preference lives in `:core:common`, not in `:core:upscale`.** The reader is an
upstream-contributable (Apache) file; if the gate lived in the GPL module the reader would have to
import GPL code, which would end the GPL-free boundary (`scripts/verify-gpl-free.sh`
`verifyGplFree`). So the key, the `UpscaleMode` enum and the cache signature are Apache, and the GPL
side reads them.

**One session per reader.** `ReaderActivity.onCreate` asks the DI-injected `ReaderUpscaleHost` for a
`ReaderUpscaleSession`; `onNewIntent` calls `reset()` (drops the pages queued for the chapter being
left) and `onDestroy` closes it with the same `recreating` rule the OCR session uses. Nothing a
session holds outlives its reader; the engine, cache, model set and queue are app-scoped singletons
(`UpscaleBindings`, `@SingleIn(AppScope::class)`).

**Three decode paths** consume the session — `PagerPageHolder`, `WebtoonPageHolder` and
`WebGpuViewer.decodeReaderPage` — and each decodes `session.open(page) ?: page.stream()`.
`ReaderActivity.onPageSelected` is the one "page is visible" hook and calls `focus(page)`.

### Cache-first background pipeline

```
decode path            engine thread (single)                  disk
-----------            ----------------------                  ----
open(page)  ──► UpscaleCache.get(key) ──hit──► upscaled page
      │miss
      └──────► requestAuto ──► queue ──► decode once ──► route ──► tiles ──► JPEG ──► cache.put
```

* `open(page)` is **synchronous** and costs one `File.isFile`. The decode path cannot wait for a
  coroutine, so there is no index, no metadata file and no directory scan on a hit.
* **The decode path asks before it requests.** `open(page) ?: page.stream()` means a page whose
  upscale is already cached is served from the cache and is *never* queued: no request, no decode, no
  inference. Only a miss reaches `request(...)`/`focus(...)`, so a second read of a chapter does no
  model work at all.
* **A page the loader has not filled in yet is skipped.** `open`/`request`/`focus` refuse a page with
  `sourceLength < 0` (the loader sets it, with `sourceLastModified`, once it has the bytes), because
  an entry written under the unknown identity `-1` is one no later `open` can ever match. The next
  page turn sees the page loaded and queues it then.
* `requestAuto(...)` takes the routing decision as a lambda and only runs it **after** decoding,
  because a page's height (the MangaJaNai bucket unit) and its colour are not known before that. The
  decode it needs is the one it just paid for.
* `request(...)` — the explicit-model variant — is the benchmark/test path.
* The queue is re-prioritised by `focus(chapterId, pageIndex)`: the visible page, then the next three,
  then everything else; anything queued for another chapter is dropped. A request for another
  chapter arriving *after* a focus is still accepted, just served last, so a reader that prefetches
  the next chapter keeps working.
* A page whose upscale is not ready keeps its **original** resolution. It is replaced on a later
  open, when the cache entry exists. Nothing blocks the reader waiting for an upscale.
* **A page that cannot upscale is not retried on every page turn.** The request's outcome is
  recorded in a bounded negative memo (`UpscaleNegativeMemo`): *no route*, *no installed model*,
  *undecodable* and *failed* are remembered and skip the page until the chapter changes (`reset()`,
  which also clears it) or the cache signature changes — a mode, scale, bucket or model change is a
  new attempt. A cancellation and a switched-off feature are not verdicts about the page and are
  never memoised, and a page that does produce an entry is forgotten instead.

The cache (`UpscaleCache`) is one image file per `(page × model × signature)` key: source id, manga
id, chapter id, page index, the chapter's `sourceLength`/`sourceLastModified` (a re-downloaded
chapter misses instead of serving a stale upscale), the model id, the scale,
`UpscalePreferences.cacheSignature()` and `ENGINE_GENERATION`. It is bounded by `cacheMb` (32–4096,
default 512) and a 30-day TTL, oldest-first, pruned on write, written through a `.part` + rename so a
crash never leaves a partial image.

**Where the byte identity comes from.** A downloaded page takes it from the stored file in the
chapter cache (`length()` and `lastModified()`). An **archive** page takes it from the entry header
libarchive already parsed while enumerating (`ArchivePageEntry`, read once per entry under the same
read guard as the name and never a second mapping read), so a re-zipped chapter misses even though
the page index is unchanged. When an archive records neither size nor mtime the page keeps `-1` and
is skipped by the upscaler rather than keyed on an identity that is not its own.

### Routing

`UpscaleRouter.route(...)` is the single rule:

* **Auto** — a grayscale page goes to a MangaJaNai bucket; a colour page goes to the selected
  IllustrationJaNai model.
* **MangaJaNai only** — every page goes to a MangaJaNai bucket.
* **IllustrationJaNai only** — every page goes to the selected IllustrationJaNai model.

**A colour page never resolves to MangaJaNai.** The family is trained on line art; running it on
colour destroys the colour work. Under Auto a colour page therefore gets the selected
IllustrationJaNai model when it is installed, and otherwise `null` — "leave the page alone", never a
silent fallback to a manga model.

The grayscale verdict (`GrayscaleDetector`) is a cheap sampler, not an analyser: it reads a grid every
4 px and says grayscale when every sample is within 2 levels of neutral, or within 25 levels *and* the
page is dark overall (bright near-neutral pages are almost always colour with a light palette).

The manga side is a **bucket match**: MangaJaNai publishes one model per target page height
(1200/1300/1400/1500/1600/1920/2048 px), so a page is served by the largest installed bucket at or
below its height, else the smallest one above it.

---

## 2. Tiles, bands and seams

### Why tiles

A whole page does not fit a model session in one pass: the ONNX graphs are fixed-shape, and a
2400×3200 output as a single tensor is hundreds of MB of activations. So the page is cut into a grid
(`TileGeometry`, `planGrid`) and the model is run per tile.

* `columns = ceil(width / tileSize)`, `tileWidth = ceil(width / columns)` — the reference
  implementation's arithmetic, kept as-is.
* Every tile of a page is fed the **same tensor shape** (the nominal tile size rounded up to a
  multiple of 64), so there is one session, one set of allocations and no per-tile reshape. The last
  tile of a row/column is simply narrower in the page and padded up.

### Why the tiles overlap, and how the seam is hidden

Neighbouring tiles disagree slightly along their shared edge. Two things hide that:

1. **Real-neighbour context.** Each tile's inference crop is extended by 16 input px on the sides it
   shares with a neighbour, taken from the neighbour's *own pixels* (`Tile.padLeft`/`padTop`), and
   reflect-padded only on the far right/bottom to reach the tensor shape (`ReflectPad`, whole-sample
   symmetric — `abcde` pads to `abcde dcba`, the edge sample is not duplicated).
2. **Half-sine blending** (`TileBlender`). The finished per-tile block is `extendedWidth × scale` by
   `extendedHeight × scale`, i.e. the rect **plus its real-neighbour pads** (the reflected ring is
   discarded). Inside a tile row, tiles are blended horizontally; row buffers are then blended
   vertically into the page. The weight is
   `b(t) = (sin(clamp(2t − 0.5, 0, 1) · π − π/2) + 1) / 2`, which is 0 up to `t = 0.25`, 0.5 at
   `t = 0.5` and 1 from `t = 0.75` — the outer quarters of the band belong outright to one side and
   only the middle half is a real cross-fade.

Using the pads (not just the rect) is what makes the blend sound: a band straddles the seam, so both
sides of it must be the same page area upscaled twice. A rect-only block would blend the far half
against unwritten memory.

### Why bands (large pages)

`maxOutputMegapixels` (default 16) bounds the one page-sized allocation — an ARGB output bitmap is
4 B/px, so 16 MP is 64 MB. A page whose output would exceed the cap is **not refused**: `planBands`
splits it into horizontal input bands that are upscaled one at a time and blended into the page with
the same half-sine band (so a band seam is invisible for the same reason a tile seam is). The cap
counts the page's *unique* output, not the 16 px of context a band borrows and blends away.

### Why the heap stays flat

Nothing page-sized is allocated except the decoded source (`PageDecoder`'s budget) and the output
surface (`PageSurface`: a native `Bitmap` in the app). The pipeline holds **one tile block at a
time**, writes rows to the output as soon as no later tile row can touch them, and reads back only
`2 · overlap · scale` rows at a band boundary. A 32 MP output as one `IntArray` would be 128 MB of
Java heap — the exact allocation this design exists to avoid.

### Why one thread

All inference, session creation and teardown happen on the engine's single `upscale-inference`
thread. One ONNX session per `(model file, tensor shape)` is created, reused for every tile of every
page sharing that shape, and rebuilt only when the shape (memory-pressure fallback) or the file
changes. `UpscaleService` is the only caller. Execution providers are tried in a chain — WebGPU →
XNNPACK → CPU — and a provider that is missing, refuses the graph or dies at creation falls through
to the next instead of failing the page (see §4 for where each model starts).

---

## 3. Measured performance

**Device: OnePlus Pad 2**, 2x, tile 512, page 1125×1600 (colour) / 1200×1600 (manga), session-reusing
run, `EngineBenchmarkTest` (phase 3b).

| Model | Provider | Time per page |
|---|---|---|
| MangaJaNai 2x 1600p (V1 ESRGAN) | WebGPU | **~56 s** |
| IllustrationJaNai 2x V3 detail SPAN_S (default, "Fast") | WebGPU | **~14 s** |
| IllustrationJaNai 2x V3 detail FDAT_M ("Balanced") | CPU | **~103 s** |

(WebGPU returns NaN for the fp16 FDAT_M exports at a 512 or 384 px tile — see
`UpscaleEngine.providerFor`; 256 px works at 73 s but is slower overall.)

Tile size dominates everything else, and 512 is the largest size measured — a boundary of the
benchmark matrix, not a peak:

| tile | MangaJaNai 2x 1600p (WebGPU) | Ill. 2x V3 detail FDAT (CPU) | Ill. 2x V3 detail SPAN (WebGPU) |
|---|---|---|---|
| 512 | **56 s** (12 tiles) | **103 s** (12 tiles) | **14 s** (12 tiles) |
| 384 | 69 s (20 tiles) | 126–130 s (20 tiles) | 17 s (20 tiles) |
| 256 | 82 s (35 tiles) | 163–172 s (35 tiles) | 21 s (35 tiles) |

This is why `DEFAULT_TILE_SIZE = 512` and why the Fast SPAN_S model — not the prettier FDAT one — is
the shipped default: 14 s against 103 s is the difference between "the next page is ready when you
get there" and "it never is".

**What the user sees.** Pages are upscaled in the background *as they read*; a page keeps its
original resolution until its upscaled copy is ready, which in practice means the first pass through a
chapter is original-resolution and a later open is upscaled.

---

## 4. Model catalogue and release process

`UpscaleModelCatalog` (`core/upscale/.../model/UpscaleModelCatalog.kt`) is the single source of
truth for what the app can select:

* **MangaJaNai** — V1 ESRGAN, one model per bucket (7) at 2x and 4x → 14 files. Ids
  `mangajanai_<scale>x_<bucket>p`.
* **IllustrationJaNai** — 8 files: six 2x (V1 ESRGAN, V2 standard FDAT, V3 denoise SPAN/FDAT, V3
  detail SPAN/FDAT) and two 4x (V2 standard FDAT, V3 detail FDAT).

Every entry is published; nothing the app can select is missing from the release, and nothing in the
release is unreachable (`UpscaleModelManifestTest`).

`UpscaleModel.fileName` **is** the release asset name **is** the upstream zip member name — nothing is
renamed between upstream and the app.

**Release process.** `tools/upscale-models/` builds `upscale-models-v1` from the upstream MangaJaNai
3.x ONNX zips: `models.json` (the declarative list) → `fetch_and_publish.py` (download, verify against
upstream's `sha256sums_fp16.txt`, extract, verify, write `manifest.json` + `ATTRIBUTION.md` +
`LICENSE-CC-BY-NC-4.0.txt`) → `gh release create`. The full procedure, including the lockstep steps
in the app repository, is in `tools/upscale-models/README.md`.

**The drift guard.** `core/upscale/src/test/resources/upscale-manifest.json` is a byte-identical copy
of the published `manifest.json`; `UpscaleModelManifestTest` parses it with the production DTOs and
fails when a catalog model has no asset, an asset is not in the catalog, a hash is not 64 hex, a size
is not positive, or the manifest's `baseUrl` does not match the pinned
`ModelDownloader.DEFAULT_MANIFEST_URL`. Re-publishing the release therefore means copying the new
manifest into the test resource in the same change.

**Install state is not "the file exists".** `ModelDownloader` records the installed release tag in
`upscale/models/installed-release.txt` after **every** selected file verified (size and SHA-256, in
its `.part` form, before the rename). `installedIds()` is a directory listing plus the marker plus the
cached manifest, so it never hashes a model.

**The unmetered preference is enforced by the downloader, not by the screen.** `unmeteredOnly`
(default on) is re-read at the start of a run **and before every file**: `triggerDownload` refuses
immediately with a visible failure, and `downloadModels` fails before the manifest request, before the
`.part` cleanup and before each subsequent file — so a Wi-Fi → cellular handover stops a ~530 MB run
instead of finishing it on the user's data plan. An unknown network counts as metered (fail closed).

**Manifest file names are one path segment.** Every `name`/`path` is validated when the manifest is
parsed (fetched and cached alike): blank, `.`/`..` and anything whose `File(name).name` differs from
`name` (`../x`, `/abs`, `a/b`) rejects the whole manifest before a single file is derived from it, so
nothing the release says can read or write outside `filesDir/upscale/models`.

---

## 5. Licence boundary

* **Weights: CC BY-NC 4.0, non-commercial only.** Original MangaJaNai weights by
  [the-database](https://github.com/the-database/MangaJaNai); ONNX conversion by
  [Lolle2000la](https://github.com/Lolle2000la/MangaJaNai). Both attributions are required and are
  reproduced in the release's `ATTRIBUTION.md`.
* **Never bundled.** No model byte is in this repository or in an APK. The app downloads them at the
  user's request from the release (`ModelDownloader.DEFAULT_MANIFEST_URL`), checksum-verified, only
  while the feature is enabled.
* **Code: GPL-3.0-or-later.** `:core:upscale` and every app-level helper/test for it carry the GPL
  header, so the combined distribution is GPL-3.0-or-later (`NOTICE`, `LICENSES/`). The GPL-free seam
  files stay Apache-2.0 so they remain contributable upstream.
* **ONNX Runtime** is MIT (`com.microsoft.onnxruntime:onnxruntime-android`). `:core:upscale` links
  the AAR directly and `:core:ocr` fetches its own `libonnxruntime.so` per ABI, so `app`'s packaging
  picks one copy (`pickFirsts += "**/libonnxruntime.so"`). **The two are kept in lockstep by a build
  gate, not by trust:** `./gradlew :app:verifyOrtLockstep` (wired into `preBuild`, so every
  `assemble*` runs it) hashes the AAR's `jni/<abi>/libonnxruntime.so` for each ABI
  `core/ocr/paddle-manifest.json` pins and fails, naming the ABI and both hashes/sizes, when a
  Renovate bump of `libs.versions.toml`'s `onnxruntime` outruns the OCR artifact. Without it the APK
  would carry one release's Java/JNI pair against another release's `libonnxruntime.so` and only
  `dlopen` on a device would notice.
* **No ORT startup component.** The AAR injects a `ContentProvider`
  (`ai.onnxruntime.TelemetryInitializer`, `initOrder=100`) that loads `libonnxruntime.so` and builds a
  native telemetry client on every cold start. `app/src/main/AndroidManifest.xml` removes it with
  `tools:node="remove"`, so nothing native is loaded until a feature that needs it is actually used;
  the app never sends OTEL telemetry.

The settings screen shows this licence line **whether the feature is on or off** — a user must be
able to read what enabling it would download before enabling it.

---

## 6. Default-off gate

`pref_reader_upscale_enabled` is `false` until the user turns it on, and it is inert rather than
merely invisible while off:

| Layer | Gate |
|---|---|
| Reader | `ReaderUpscaleSession.open`/`request` return nothing; `UpscaleRuntimePolicy.isActive` needs an opt-in **and** an installed model |
| Queue | `UpscaleService.request`/`requestAuto`/`runItem` drop work while off |
| Models | `ModelDownloader.triggerDownload`/`downloadModels`/`fetchManifest` refuse while off — no request, no `.part`, no prune. The `unmeteredOnly` preference is enforced here too (start of a run and before every file), not only in the settings screen |
| Settings | the master switch and the licence block stay visible; every actionable row (`visible = featureEnabled`) and the I/O behind it (installed listing, cache size) is skipped |

The screen itself is always reachable (`isEnabled() = true`): it owns the master switch, so hiding it
would strand re-enable.

---

## 7. Known limitations

* **Slow on CPU.** MangaJaNai is 56 s/page on WebGPU but ~120 s on CPU kernels; the FDAT colour models
  are CPU-only at the shipped tile size and take ~103 s/page. A page is a *background* product, not a
  loading screen.
* **WebGPU NaN for the fp16 FDAT_M exports** at a 512 or 384 px tile. They start on the CPU kernels
  (`providerFor`); if a NaN still reaches the engine it is reported as
  `UpscaleNonFiniteException` and the page is kept as it is.
* **4x is impractical** on this hardware. It is exposed (both scales are published) but it is far
  slower and needs a much bigger output cap, so it is a deliberate choice for a few pages, not a
  sensible default.
* **Pages over the output cap** are upscaled in vertical bands rather than refused. Only a cap so
  small that it cannot hold a single row of output yields no band plan at all (`planBands` returns
  `null`), and that page is then skipped and stays as it is.
* **The reader hooks are device-untested at this point.** The engine, tile math, cache, downloader and
  the routing rules have unit/instrumented coverage and the benchmark ran on a device, and the wiring
  itself is now covered off-device: `ReaderUpscaleWiringTest` pins "every viewer decodes
  `open(page) ?: page.stream()` and requests **only** on a miss" plus "one session per activity, closed
  on every destroy", and `ReaderUpscaleSessionImplTest` pins the session's lifecycle (reset, close
  recreating vs finishing, the negative memo, the `-1` page identity). What has not been exercised is
  the three decode-path integrations (`PagerPageHolder`, `WebtoonPageHolder`, `WebGpuViewer`) and the
  session lifecycle *on hardware*.
* **Model-per-family selection is per scale.** A 2x colour model cannot serve a 4x request; the
  settings screen repairs a cross-scale selection when the scale changes
  (`UpscaleModelOptions.resolve`) rather than leaving colour pages silently untouched.
