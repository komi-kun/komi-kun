# Track B — OCR device verification (stock variant + latency)

Status: **the emulator run is done; the tablet run is still open** (no adb device was attached when
this was written). Everything below is reproducible in one command per step.

Two PR #7 gaps can only be closed on hardware:

| Gap | What "done" means |
|---|---|
| Stock variant download not exercised on device | Settings → OCR → Recogniser model → *Stock PP-OCRv6* downloads `ocr-models-v4`, the files verify against the manifest (size + SHA-256), the app reports `OCR ready`, OCR runs on a page, and switching back to the default fine-tune (`ocr-manga-v3`) works — the `ocr-manga-v2` missing-`path` 404 defect that used to block switching back is **fixed and verified offline** (see below) |
| On-device OCR latency unmeasured | `recognize:` timings (parsed from the shim's `TIMING|` line) for a normal page and for an oversized (≥ 4× upscaled, > `MAX_OCR_PIXELS = 4 MP`) page |

Driver: `scripts/track-b-ocr-device-check.py` (Python 3 stdlib only, new file, no production code
touched). Every command takes an exclusive `flock` on `/tmp/opencode/komi-kun-tablet.lock` before the
first adb call, because several tracks share the tablet. Only `app.komikun.dev` is ever touched.

## Where the numbers come from

`libpaddle_ocr.so` prints one `TIMING|total|prep|det|post|crop|rec|rawBoxCount` line per page over the
JNI text protocol. `PaddleOcrEngine` parses it (`core/ocr/src/main/java/mihon/core/ocr/paddle/PaddleOcrEngine.kt:376`)
and logs the same numbers as one `logcat` line (`PaddleOcrEngine.kt:345`):

```
PaddleOcrEngine: recognize: Total: 662ms (Prep: 7ms, Det: 283ms, Post: 6ms, Crop: 34ms, Rec: 330ms) | Boxes: 21, Lines: 20
```

The script reads that line, so no debug flag, no benchmark build and no Kotlin change are needed. The
raw `TIMING|` line is also accepted in case the shim ever logs it directly.

The cache interferes: a page that was already OCR'd is served from `files/ocr_cache` (keyed by engine
generation, thresholds and — since the variant selector — the recogniser variant), so use
`capture --clear-cache` before a latency pass, or the run reports zero timings.

One quirk when reading raw logs: the app currently writes each `recognize:` record twice, 1–2 ms apart
(same pid/tid/message), and `logcat -d` can repeat it again. The script collapses records with the same
payload from the same thread within 500 ms, so its row count is the number of OCR runs; `grep | sort -u`
on timestamps alone will over-count.

## Commands

```bash
# read-only: device, package, variant pref, and which model files are actually on disk
scripts/track-b-ocr-device-check.py preflight

# stock switch + real download (drives Settings → OCR through uiautomator, waits for the downloader,
# then verifies the landed files against the release manifest with size + SHA-256; --verify-ui also
# requires the settings row to report "OCR ready", otherwise the command exits 9)
scripts/track-b-ocr-device-check.py variant stock --verify-ui

# undo: rewrites the value the last switch replaced (recorded in
# /tmp/opencode/track-b-ocr-variant-state.json), pref only, no download. Takes no variant argument;
# errors if there is no snapshot or it belongs to another serial.
scripts/track-b-ocr-device-check.py variant --restore

# latency: drive the reader while it collects; then repeat for the oversized chapter
scripts/track-b-ocr-device-check.py capture --label normal    --seconds 90 --clear-cache
scripts/track-b-ocr-device-check.py capture --label oversized --seconds 90 --clear-cache

# offline: parser fixtures, restore/verification unit checks, published release manifests (no device)
scripts/track-b-ocr-device-check.py selftest --online
```

`capture` prints a table plus `n`, median, min and max, and can write the rows as JSON (`--out`).

What `variant` does and does not claim: it reports `downloaded during this run: <file>` only when the
app logged `ModelDownloader: downloading …`, `no download logged: … already present` on the manifest
fast path, and then one line per required file from `verify_models`. A file is only called verified
when its **size and SHA-256** both match the manifest entry (`sha256sum` runs on the device as the app
uid; the manifest is the device-cached one for the selected release, else the published one). A bare
`ls` can therefore never produce a success line: without a manifest entry, without a published hash,
or without `sha256sum`, the line says `NOT verified` and the command exits non-zero. With
`--verify-ui` the settings row is part of the verdict: verified files are not enough if the row says
`OCR not ready` (exit `9`).

Exit codes: `0` ok/verified (and, with `--verify-ui`, the settings row says `OCR ready`),
`2` bad invocation/no device/restore refused, `3` tablet lock busy,
`4` models missing **or not verifiable against the manifest**, `5` no timings captured,
`6` download failure, `7` download timeout, `8` release-artifact problem,
`9` models verified but the settings row still reports `OCR not ready` (with `--verify-ui`) — treated as
a failure, because the engine would silently no-op on every page.

### Tablet procedure (one pass)

1. `adb devices` → the OPD2403 (`4df231a3`) must be listed; accept the debugging prompt on the tablet.
2. `scripts/track-b-ocr-device-check.py preflight` — record model state and the current variant.
3. `variant stock --verify-ui` — expect `downloaded during this run: ppocrv6_small_rec.onnx`, three
   `OK … size + SHA-256 match manifest` lines, `OK: ocr-models-v4 models verified…`, `OCR ready`, and
   exit `0`. A verified download whose row still says `NOT ready` exits `9` and must be reported as a
   failure, not a pass. `variant --restore` afterwards puts back whatever the switch replaced (also
   when it had been unset).
4. Open a local-source chapter (`Browse → Local source → <series> → <chapter>`); `capture` for the
   normal pages, then for an oversized chapter.
5. Oversized asset (do not commit it): take a chapter from
   `/home/luca/development/MangaIngestWithUpscaling/test_data/library/original/`, upscale 4× and put it
   next to the original:
   ```bash
   python3 - <<'EOF'
   import io, zipfile
   from PIL import Image
   src = zipfile.ZipFile("Chapter 01.cbz")
   with zipfile.ZipFile("Chapter 02 (4x).cbz", "w", zipfile.ZIP_DEFLATED) as out:
       for n in sorted(src.namelist()):
           im = Image.open(io.BytesIO(src.read(n))).convert("RGB")
           buf = io.BytesIO()
           im.resize((im.width * 4, im.height * 4), Image.LANCZOS).save(buf, "JPEG", quality=92)
           out.writestr(n, buf.getvalue())
   EOF
   adb push "Chapter 02 (4x).cbz" "<base>/local/<series>/Chapter 02 (4x).cbz"   # <base>/local = local source
   ```
   Then `Refresh` from the manga screen's overflow menu (pull-to-refresh does not rescan the folder).
6. `screencap` for evidence (`screenrecord` is blocked on this device).

Reference, exactly how the oversized path behaves (`core/ocr/src/main/java/mihon/core/ocr/ImageChunking.kt:142-146`):
pages whose aspect ratio is ≤ 3 are resized to `MAX_OCR_PIXELS = 4_000_000` **before** the 2400 px
tiler is considered, so a 15.9 MP 4× page becomes ~1.678 × 2.383 k px and is a single tile. Webtoon
strips (aspect > 3) keep the old path.

## What has been measured — x86_64 emulator, **not the tablet**

`Pixel_9_Pro_XL` AVD (Android 17/API 37, `sdk_gphone16k_x86_64`), debug build `app.komikun.dev`
versionName `1.14.1-10800`, stock recogniser, `PADDLE` source, overlay enabled, cache cleared before
each pass. These are correctness/plumbing numbers, not latency targets: an emulator says nothing about
the OPD2403.

| Page set | Pages | Total (ms) | Det / Rec (ms) | Boxes → Lines | Retained artifact |
|---|---|---|---|---|---|
| normal (836×1187, 1.0 MP), cold cache | 3 | 544, 760, 784 | 260/244, 250/447, 248/468 | 21→20, 36→31, 39→38 | `tb-capture-normal.json`, `tb-live-fixture.txt` |
| oversized (3344×4748, 15.9 MP, 4× LANCZOS), cold cache | 2 | 593, 709 | 250/213, 146/360 | 20→18, 41→37 | `tb-oversized-live.json` |
| oversized, earlier capture | 2 | 647, 856 | 301/209, 277/353 | 20→18, 41→37 | `tb-capture-oversized.json` |
| oversized, single page dump | 1 | 684 | 320/229 | 12→10 | `tb-oversized.json`, `tb-logcat-fixture.txt` |

Medians over the retained rows: normal 760 ms (n=3), oversized 684 ms (n=5) — emulator, so treat the
absolute values as noise; what matters is that both paths complete in one engine run, the 4× page costs
no extra detector pass, and nothing falls back to per-tile OCR (a tiled page would emit several
`recognize:` lines for one page and take seconds).

All `/tmp/opencode/*` paths are scratch: they are what was on disk when this note was written, and
`/tmp` has been wiped before. The two `tb-capture-*.json` files predate the duplicate-record fix, so
they list each row twice (see the log-duplication note above); the table lists the deduplicated values.
No measurement is included here without a retained artifact behind it.

Engine init (native libraries from `jniLibs`, model load) was `214 ms` for the whole session; the first
page of a session therefore includes it once. Screenshot evidence of the stock run on an oversized page
(text overlay + boxes, `1/3`): `/tmp/opencode/tb_oversized.png`.

## Defect found while exercising the switch — **RESOLVED** in `ocr-manga-v3` / `ocr-models-v4`

> **Status: fixed.** The two releases that superseded `ocr-manga-v2` (`ocr-manga-v3` for the default
> fine-tune, `ocr-models-v4` for the stock recogniser) publish an explicit flat `"path"` on **every**
> manifest entry, so the `$baseUrl/models/<name>` fallback below is never taken. `selftest --online`
> (which downloads each variant's manifest and `HEAD`s every required file exactly the way
> `ModelDownloader` resolves it) exits `0` with `release downloads: OK`. The text below is the
> original finding, kept because it is the reason the manifests are now checked this way.

Switching **to the stock variant** works, but switching **back to the default fine-tune** (and any
first-time install of the default) cannot download its models: `ocr-manga-v2` publishes its manifest
without the optional `path` field, and `ModelDownloader` then resolves
`$baseUrl/models/<name>` (`core/ocr/src/main/java/mihon/core/ocr/paddle/ModelDownloader.kt:234`,
`entry.path ?: "models/${entry.name}"`). GitHub release assets are flat, so every such URL 404s.

Evidence (emulator, `variant fine`):

```
pref_ocr_rec_model: STOCK -> FINE_TUNED (release ocr-manga-v2, rec ppocrv6_small_rec_manga_real.onnx)
tapping the model-status row to run the downloader (it re-verifies size + SHA-256)
DOWNLOAD FAILED: Download failed for ppocrv6_small_rec_manga_real.onnx: HTTP 404
```

With the model directory emptied (simulating a fresh install) the first file fails too, and the UI
shows `Error: Download failed for ppocrv6_small_det.onnx: HTTP 404`. `selftest --online` reports the
same three 404s without a device:

```
manifest ocr-manga-v2: release='ocr-manga-v2' files=3 missing=none
  ppocrv6_small_det.onnx        path=models/ppocrv6_small_det.onnx        UNREACHABLE (404)
  ppocrv6_small_dict.txt        path=models/ppocrv6_small_dict.txt        UNREACHABLE (404)
  ppocrv6_small_rec_manga_real.onnx path=models/ppocrv6_small_rec_manga_real.onnx UNREACHABLE (404)
```

`ocr-models-v3` (stock) is fine: it lists explicit flat `path`s and all three required files return
HTTP 200. **Resolution (shipped):** rather than patch `ocr-manga-v2`, the fixes went into the two
successor releases — `ocr-manga-v3` (default fine-tune) and `ocr-models-v4` (stock recogniser) — every
manifest entry of which carries `"path": "<name>"`. `scripts/track-b-ocr-device-check.py selftest
--online` now reports, for both pinned releases, `missing=none` and `HTTP 200` for
`ppocrv6_small_det.onnx`, `ppocrv6_small_dict.txt` and the variant's recogniser, and exits `0`.
`ocr-manga-v2` stays published (nothing was deleted) but no build pins it any more.
A device that already has the fine-tuned weights is unaffected until the files are removed.

### The stock switch, re-checked with the stricter verification

The same run, after the verification claim was tightened (size + device-side `sha256sum` against the
manifest instead of a bare `ls`). The release tag below (`ocr-models-v3`) is what that build pinned at
the time; the current pin is `ocr-models-v4`, which ships the **same** stock recogniser
(`ppocrv6_small_rec.onnx`, 21,159,378 B, sha256 `5435fd74…`) and the same `ppocrv6_small_dict.txt`
(74,947 B) but a **new detector** (`ppocrv6_small_det.onnx`, 9,929,569 B, sha256 `bf5ed74a…`, the
curved-SFX fine-tune), so a `variant stock` run today prints a different size for that one line:

```
pref_ocr_rec_model: STOCK -> STOCK (release ocr-models-v3, rec ppocrv6_small_rec.onnx)
undo: scripts/track-b-ocr-device-check.py --serial emulator-5556 variant --restore (snapshot in /tmp/opencode/track-b-ocr-variant-state.json)
tapping the model-status row to run the downloader (it re-verifies size + SHA-256)
no download logged: every required file was already present (manifest fast path)
  OK   ppocrv6_small_det.onnx: size + SHA-256 match manifest (9880512 B)
  OK   ppocrv6_small_dict.txt: size + SHA-256 match manifest (74947 B)
  OK   ppocrv6_small_rec.onnx: size + SHA-256 match manifest (21159378 B)
settings status row: OCR ready
OK: ocr-models-v3 models verified against the manifest (size + SHA-256)
```

Replacing the stock recogniser with a 1000-byte junk file made the app re-download it on the next
trigger, and the script reported the difference explicitly:
`downloaded during this run: ppocrv6_small_rec.onnx`, then the same three `OK … size + SHA-256 match`
lines — i.e. "downloaded" and "verified" are separate, separately-evidenced statements.

## Limitations of this check

- Latency is measured end-to-end per page as the engine reports it (prep + det + post + crop + rec);
  image decode, cache write and Compose overlay drawing are outside it.
- The script cannot prove from logs that the oversized page took the 4 MP normalisation branch; that
  path is covered by `ImageChunkingTest` and by the code above, and the timings are consistent with it.
- `variant` uses `uiautomator` text matching against the English UI. A different locale breaks the
  taps (the pref is written first, so the download can still be triggered by hand).
- `--restore` reads the snapshot written by the last switch (`/tmp/opencode/track-b-ocr-variant-state.json`).
  No snapshot, a snapshot for another serial, or passing a variant alongside `--restore` are hard
  errors; the snapshot is deleted once restored.
- Model verification needs a manifest entry and `sha256sum` on the device. Without either, the file is
  reported as `NOT verified` and the command exits `4` — that is not a failure of the download, it is
  the absence of evidence.
- `--clear-cache` and the pref write need `run-as`, i.e. a debuggable (`app.komikun.dev`) build.
- Nothing here exercises the detector, the dictionary or Anki paths.
