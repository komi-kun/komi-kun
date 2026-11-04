# `tools/upscale-models` — the upscale model release tool

Builds and publishes the model release `:core:upscale` downloads at runtime:
[`komi-kun/komi-kun-upscale-models`](https://github.com/komi-kun/komi-kun-upscale-models),
tag **`upscale-models-v1`** — the exact release `ModelDownloader.DEFAULT_MANIFEST_URL` pins.

It takes the upstream MangaJaNai 3.x **ONNX** release (which ships a few zips) and turns it into
**one release asset per model**, plus a `manifest.json` that carries each file's SHA-256 and size, so
the app can fetch exactly the model it needs and verify every byte.

The weights are **CC BY-NC 4.0** (non-commercial only), re-hosted unmodified. See
[Licence boundary](#licence-boundary).

## What is here

| file | purpose |
| --- | --- |
| `models.json` | the declarative model list — the one source of truth. Each entry maps a catalog `id` to an upstream zip + the exact member inside it |
| `fetch_and_publish.py` | the publisher (Python 3, stdlib only): download → verify → extract → verify → manifest + attribution |
| `README.md` | this file |

`models.json` also carries the upstream coordinates (`release`, `baseUrl`, `sumsFile`, `licenseFile`)
and the release this fork publishes (`repo`, `tag`, `title`, `baseUrl`). Changing the tag in one place
and re-running is all a new release needs — plus copying the new manifest into the app (below).

Every entry's `memberName` **is** the release asset name **is** the catalog's
`UpscaleModel.fileName`: nothing is renamed between upstream and the app, so the file the app looks
for is the file that ships. `memberName` is the zip member name verbatim (the MangaJaNai V1 fp16 set
carries no `_fp16` suffix, even though it comes from the fp16 pack).

## The catalogue policy these models serve

The app never picks a model by this tool's ordering; the policy lives in
`core/upscale/.../model/UpscaleRouter.kt`. What is published exists to satisfy it:

* **MangaJaNai, routed by page height.** Line art gets the MangaJaNai V1 ESRGAN set — one model per
  target page height ("bucket": 1200, 1300, 1400, 1500, 1600, 1920, 2048 px) at 2x **and** 4x, so a
  page is served by a model trained for pages that size. The router prefers the largest installed
  bucket at or below the page height, then the smallest one above it.
* **IllustrationJaNai for colour.** A colour page never gets a MangaJaNai model (line-art training
  destroys colour work). It gets the IllustrationJaNai model the user selected, or nothing.
* **V3 preferred.** The IllustrationJaNai defaults and the recommended picks are the **V3** models
  (denoise/detail), which is why V3 is downloaded and published first-class rather than V1/V2: the
  catalogue keeps the older variants selectable, but the default
  (`illustrationjanai_2x_v3detail_fdat_m`) is V3.
* **Every catalogue entry is published.** If the app can select it, the release must carry it — that
  is what the drift test below enforces. That means all 14 MangaJaNai bucket models and all 8
  IllustrationJaNai variants: 22 files, ≈530 MiB.

## Re-running it

```sh
# from the app repository root
python3 tools/upscale-models/fetch_and_publish.py                # default cache/dist under /tmp/opencode
python3 tools/upscale-models/fetch_and_publish.py --print-only   # the table from models.json, no network
python3 tools/upscale-models/fetch_and_publish.py --only mangajanai_2x_1600p,illustrationjanai_2x_v3detail_fdat_m
python3 tools/upscale-models/fetch_and_publish.py --cache /tmp/opencode/upscale-models/cache \
                                                  --dist  /tmp/opencode/upscale-models/dist
```

The run is **idempotent** and **resumable**:

* upstream zips are cached (`/tmp/opencode/upscale-models/cache/`) and skipped when their SHA-256
  matches `sha256sums_fp16.txt`; a truncated download leaves a `.part` that continues with an HTTP
  `Range` request on the next run;
* an extracted member is skipped when the file on disk already hashes to the expected value;
* `manifest.json`, `ATTRIBUTION.md`, `RELEASE_NOTES.md` and the licence are rewritten identically
  every run (no timestamps), so re-running over an intact `dist/` produces byte-identical output —
  which is what lets the app's checked-in manifest copy stay honest.
* `--only` is for fetching a subset: it suffixes **every** artifact it writes with `.partial` — the
  `.onnx` files as well as `manifest.json.partial`, `ATTRIBUTION.md.partial` and
  `RELEASE_NOTES.md.partial` — so a partial set can never be published under the names the release
  command uploads (`<dist>/*.onnx`, `manifest.json`, …). A manifest missing catalog models is exactly
  the drift the app's manifest test rejects. Re-run without `--only` before publishing.

It ends with the table `id / file / scale / family / bucket / bytes / sha256(short)`.

Output (`dist/`, deliberately **outside** the app tree — a model byte is never committed to the app
repository):

```
manifest.json                          the release manifest the app parses
<22 .onnx files>                        the weights, byte-identical to the upstream zip members
ATTRIBUTION.md                          the CC BY-NC 4.0 attribution the licence requires
LICENSE-CC-BY-NC-4.0.txt                the full licence text
RELEASE_NOTES.md                        the release body (not itself a release asset)
```

## Publishing (and keeping the app in lockstep)

`gh` must be authenticated as an account with admin on the `komi-kun` org.

```sh
DIST=/tmp/opencode/upscale-models/dist
python3 tools/upscale-models/fetch_and_publish.py

# once, if the repository does not exist yet (a throwaway dir, so the app repo's remotes are untouched)
mkdir -p /tmp/opencode/upscale-models/repo && cd /tmp/opencode/upscale-models/repo
git init -b main && cp $DIST/ATTRIBUTION.md $DIST/LICENSE-CC-BY-NC-4.0.txt .   # + README.md
git add -A && git commit -m "Upscale models: README, attribution, CC BY-NC 4.0 text"
gh repo create komi-kun/komi-kun-upscale-models --public --source=. --push

gh release create upscale-models-v1 -R komi-kun/komi-kun-upscale-models \
    --title "Upscale models v1" --notes-file $DIST/RELEASE_NOTES.md \
    $DIST/*.onnx $DIST/manifest.json $DIST/ATTRIBUTION.md $DIST/LICENSE-CC-BY-NC-4.0.txt
```

Do **not** pass `$DIST/*`: that would upload `RELEASE_NOTES.md` as an asset too (the body already
carries it).

Publish only after a **full** run. A `--only` run leaves `.onnx.partial` files instead of `.onnx`
ones (see above), so `$DIST/*.onnx` matches nothing and the `gh release create` fails loudly rather
than publishing a release that disagrees with `manifest.json` — but the safe path is simply to re-run
`fetch_and_publish.py` without `--only` first.

Then, in the app repository, in the **same** change as the model list update:

1. Copy the published manifest to the test resource that guards against drift:

   ```sh
   curl -sSL -o core/upscale/src/test/resources/upscale-manifest.json \
       https://github.com/komi-kun/komi-kun-upscale-models/releases/download/upscale-models-v1/manifest.json
   ```

   `UpscaleModelManifestTest` parses that copy with the production DTOs and fails when the catalog and
   the release disagree: a catalog model with no asset, an asset the catalog cannot route to, a hash
   that is not 64 hex, a non-positive size, or a `baseUrl` that does not match the pinned
   `ModelDownloader.DEFAULT_MANIFEST_URL`.

2. Update `ModelDownloader.DEFAULT_MANIFEST_URL` when the release tag changes (it names
   `.../releases/download/upscale-models-v1/manifest.json` today).

Verify the upload before trusting it:

```sh
gh release view upscale-models-v1 -R komi-kun/komi-kun-upscale-models --json assets \
    --jq '.assets[] | "\(.size)\t\(.name)"'
curl -sSI https://github.com/komi-kun/komi-kun-upscale-models/releases/download/upscale-models-v1/manifest.json
```

## Benchmark staging (phase 3b)

The on-device upscaling benchmark reads its inputs from the app's **external** files directory, never
from the repository (`core/upscale/src/androidTest/.../bench/BenchmarkAssets.kt` owns the contract and
skips the test when the files are absent). Publishing leaves the staged tree in place:

```
/tmp/opencode/upscale-models/
  dist/                 the release assets, exactly as uploaded (manifest + 22 .onnx + docs)
  bench/models/         the same 22 .onnx, ready to push
  bench/pages/          two synthetic pages
    page-manga-1200x1600.png    grayscale line art  (routes to mangajanai_*_1600p)
    page-colour-1125x1600.png   RGB                 (routes to the selected IllustrationJaNai model)
```

Push (nothing is installed on the device by this tool):

```sh
PKG=app.komikun.dev   # the installed variant's applicationId
adb shell mkdir -p /sdcard/Android/data/$PKG/files/upscale-bench/models \
                   /sdcard/Android/data/$PKG/files/upscale-bench/pages
adb push /tmp/opencode/upscale-models/bench/models/. /sdcard/Android/data/$PKG/files/upscale-bench/models/
adb push /tmp/opencode/upscale-models/bench/pages/.  /sdcard/Android/data/$PKG/files/upscale-bench/pages/
```

The pages are synthetic on purpose: deterministic (no licence, no personal content, fixed size), one
per routing branch — grayscale for the MangaJaNai bucket path, colour for the IllustrationJaNai path.

## Licence boundary

The weights are **not GPL** and **not part of the app**:

* They are licensed **CC BY-NC 4.0** — Creative Commons Attribution-NonCommercial 4.0 International.
  **Non-commercial use only.** The full text ships with every release as `LICENSE-CC-BY-NC-4.0.txt`.
* Original weights by [the-database](https://github.com/the-database/MangaJaNai) (MangaJaNai); ONNX
  conversion by [Lolle2000la](https://github.com/Lolle2000la/MangaJaNai) (`v3.0.0-onnx`). Both
  attributions are required and are reproduced in `ATTRIBUTION.md` and in the release notes.
* The files here are **byte-identical** to their upstream zip members (same SHA-256 as upstream's
  `sha256sums_fp16.txt`) — only the container changed, zip → one asset per file. Nothing is
  re-exported, re-quantised, renamed or re-signed.
* No model byte is ever committed to the komi-kun repository (a GPL-3.0 tree) or bundled in an APK.
  The app downloads them on device, at the user's request, only while reader upscaling is enabled.
* `dist/` therefore defaults to `/tmp/opencode/upscale-models/dist`, never to a path inside the app
  checkout.
