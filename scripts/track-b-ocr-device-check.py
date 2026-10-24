#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Track B OCR device check: stock-variant download/switch + on-device OCR latency.

This is a *verification* helper, not production code. It automates the two PR #7 gaps that can only
be closed on hardware:

1. "Stock variant download not exercised on device" — switch Settings -> OCR -> Recogniser model to
   Stock, let the runtime downloader fetch `ocr-models-v3`, confirm the file landed with the size the
   release manifest publishes, then switch back to the default fine-tune.
2. "On-device OCR latency unmeasured" — collect the engine's own timings for normal and oversized
   pages. `libpaddle_ocr.so` emits `TIMING|total|prep|det|post|crop|rec|rawBoxCount`; the Kotlin side
   parses it and logs `recognize: Total: <n>ms (Prep: ..., Det: ..., Post: ..., Crop: ..., Rec: ...)
   | Boxes: <n>, Lines: <n>` (`core/ocr/.../paddle/PaddleOcrEngine.kt:22,376,345`). This script reads
   that log line, so no debug build flag and no Kotlin change are needed.

Every adb-touching command runs under an exclusive `flock` on
`/tmp/opencode/komi-kun-tablet.lock`, because several agent tracks share the tablet. The lock is taken
before the first adb call and held until the process exits.

Usage
-----
    scripts/track-b-ocr-device-check.py preflight
    scripts/track-b-ocr-device-check.py capture --label normal    --seconds 60 --clear-cache
    scripts/track-b-ocr-device-check.py capture --label oversized --seconds 60
    scripts/track-b-ocr-device-check.py variant stock
    scripts/track-b-ocr-device-check.py variant --restore
    scripts/track-b-ocr-device-check.py selftest [--online]

Device selection: `--serial`/`$TB_SERIAL`, else `$TB_SERIAL_DEFAULT` (the OPD2403 tablet,
`4df231a3`), else the only attached device.

Safety: only the debug package (`app.komikun.dev`) is ever touched. `variant` rewrites one key in the
app's default SharedPreferences (via `run-as`, so it needs a debuggable build) and prints the previous
value; `--restore` puts it back. `--clear-cache` deletes `files/ocr_cache` only.
"""

from __future__ import annotations

import argparse
import fcntl
import json
import os
import re
import statistics
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass

LOCK_PATH = "/tmp/opencode/komi-kun-tablet.lock"
LOCK_WAIT_S = float(os.environ.get("TB_LOCK_WAIT", "1800"))
PKG = "app.komikun.dev"
DEFAULT_SERIAL = os.environ.get("TB_SERIAL_DEFAULT", "4df231a3")
MODELS_DIR = f"files/paddle_ocr"  # relative to the app's filesDir
PREFS_REL = f"shared_prefs/{PKG}_preferences.xml"

# RecModelVariant (core/ocr/src/main/java/mihon/core/ocr/RecModelVariant.kt).
FINE_TUNED = {"rec": "ppocrv6_small_rec_manga_real.onnx", "release": "ocr-manga-v2"}
STOCK = {"rec": "ppocrv6_small_rec.onnx", "release": "ocr-models-v3"}
SHARED_MODELS = ("ppocrv6_small_det.onnx", "ppocrv6_small_dict.txt")
RELEASE_ROOT = "https://github.com/komi-kun/komi-kun-ocr-models/releases/download"

# `recognize: Total: 662ms (Prep: 7ms, Det: 283ms, Post: 6ms, Crop: 34ms, Rec: 330ms) | Boxes: 21, Lines: 20`
RECOGNIZE_RE = re.compile(
    r"recognize: Total: (?P<total>\d+)ms \(Prep: (?P<prep>\d+)ms, Det: (?P<det>\d+)ms, "
    r"Post: (?P<post>\d+)ms, Crop: (?P<crop>\d+)ms, Rec: (?P<rec>\d+)ms\) "
    r"\| Boxes: (?P<boxes>\d+), Lines: (?P<lines>\d+)"
)
TIMING_RE = re.compile(
    r"TIMING\|(?P<total>\d+)\|(?P<prep>\d+)\|(?P<det>\d+)\|(?P<post>\d+)\|"
    r"(?P<crop>\d+)\|(?P<rec>\d+)\|(?P<boxes>\d+)"
)

FIELDS = ("total", "prep", "det", "post", "crop", "rec", "boxes", "lines")


# --------------------------------------------------------------------------------------- adb plumbing
class Adb:
    def __init__(self, serial: str | None) -> None:
        self.serial = serial or self._resolve_serial()
        if not self.serial:
            die(
                "no adb device attached (checked `adb devices`). Expected the OPD2403 tablet "
                f"(serial {DEFAULT_SERIAL}, `adb -s {DEFAULT_SERIAL} devices`). Connect it, accept the "
                "USB-debugging prompt and re-run."
            )

    @staticmethod
    def _resolve_serial() -> str | None:
        out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
        online = [line.split()[0] for line in out.splitlines()[1:] if line.strip().endswith("device")]
        if DEFAULT_SERIAL in online:
            return DEFAULT_SERIAL
        if len(online) == 1:
            return online[0]
        return None

    def sh(self, *args: str, check: bool = True) -> str:
        cmd = ["adb", "-s", self.serial, *args]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if check and r.returncode != 0:
            die(f"{' '.join(cmd)} failed: {r.stderr.strip() or r.stdout.strip()}")
        return r.stdout

    def shell(self, *args: str, check: bool = True) -> str:
        return self.sh("shell", *args, check=check)

    def run_as(self, *args: str, check: bool = True) -> str:
        return self.sh("shell", "run-as", PKG, *args, check=check)

    def app_stdout(self, *args: str) -> str:
        """`exec-out run-as` (binary-safe; used to read files as the app uid)."""
        r = subprocess.run(["adb", "-s", self.serial, "exec-out", "run-as", PKG, *args],
                           capture_output=True, text=True)
        if r.returncode != 0:
            die(f"run-as failed (is {PKG} a debug build?): {r.stderr.strip()}")
        return r.stdout


def die(msg: str, code: int = 2) -> None:
    print(f"ERROR: {msg}", file=sys.stderr)
    raise SystemExit(code)


def acquire_lock() -> None:
    os.makedirs(os.path.dirname(LOCK_PATH), exist_ok=True)
    handle = open(LOCK_PATH, "w")
    deadline = time.time() + LOCK_WAIT_S
    while True:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            break
        except BlockingIOError:
            if time.time() > deadline:
                die(f"tablet lock {LOCK_PATH} still held after {LOCK_WAIT_S:.0f}s", 3)
            time.sleep(2)
    # Keep `handle` alive for the life of the process; the kernel drops the lock on exit.
    globals()["_lock_handle"] = handle


# ----------------------------------------------------------------------------------------- prefs access
def prefs_read(adb: Adb) -> str:
    return adb.app_stdout("cat", PREFS_REL)


def prefs_get(adb: Adb, key: str) -> tuple[str | None, str]:
    root = ET.fromstring(prefs_read(adb))
    for entry in root:
        if entry.get("name") == key:
            # `<string>` keeps its value in the text; `<boolean>/<int>/<long>/<float>` in `value`.
            return (entry.text if entry.text is not None else entry.get("value")), entry.tag
    return None, ""


def prefs_set(adb: Adb, key: str, value: str | None) -> None:
    """Writes one key of the default SharedPreferences XML (app must be force-stopped first)."""
    adb.shell("am", "force-stop", PKG)
    root = ET.fromstring(prefs_read(adb))
    entry = next((e for e in root if e.get("name") == key), None)
    if value is None:
        if entry is not None:
            root.remove(entry)
    elif entry is None:
        ET.SubElement(root, "string", {"name": key}).text = value
    elif entry.tag == "string":
        entry.text = value
    else:
        # Keep the XML type SharedPreferences expects (boolean/int/long/float live in `value`).
        entry.set("value", value)
    text = ET.tostring(root, encoding="unicode")
    if not text.startswith("<?xml"):
        text = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" + text
    with open("/tmp/opencode/_tb_prefs.xml", "w") as f:
        f.write(text)
    adb.sh("push", "/tmp/opencode/_tb_prefs.xml", "/data/local/tmp/tb_prefs.xml")
    adb.shell("chmod", "644", "/data/local/tmp/tb_prefs.xml")
    adb.run_as("cp", "/data/local/tmp/tb_prefs.xml", PREFS_REL)
    adb.shell("rm", "-f", "/data/local/tmp/tb_prefs.xml")


# ------------------------------------------------------------------------------- uiautomator (UI drive)
def ui_dump(adb: Adb) -> ET.Element:
    adb.shell("uiautomator", "dump", "/sdcard/tb_ui.xml")
    return ET.fromstring(adb.shell("cat", "/sdcard/tb_ui.xml"))


def ui_nodes(xml: ET.Element):
    for n in xml.iter("node"):
        text, desc = n.get("text", ""), n.get("content-desc", "")
        bounds = re.findall(r"\d+", n.get("bounds", ""))
        if (not text and not desc) or len(bounds) != 4:
            continue
        x1, y1, x2, y2 = map(int, bounds)
        yield text, desc, (x1 + x2) // 2, (y1 + y2) // 2


def ui_tap(adb: Adb, needle: str, *, contains: bool = False, wait_s: float = 6.0) -> bool:
    """Taps the first node whose text/content-desc matches `needle`, retrying while the UI settles."""
    deadline = time.time() + wait_s
    while time.time() < deadline:
        for text, desc, x, y in ui_nodes(ui_dump(adb)):
            hit = (needle in text or needle in desc) if contains else (needle in (text, desc))
            if hit:
                adb.shell("input", "tap", str(x), str(y))
                return True
        time.sleep(1)
    return False


def open_ocr_settings(adb: Adb) -> None:
    """More tab -> Settings -> toolbar search "OCR" -> the OCR settings screen."""
    adb.shell("am", "start", "-n", f"{PKG}/eu.kanade.tachiyomi.ui.main.MainActivity")
    time.sleep(5)
    if not ui_tap(adb, "More"):
        die("could not find the 'More' tab")
    time.sleep(2)
    if not ui_tap(adb, "Settings"):
        die("could not find 'Settings' on the More tab")
    time.sleep(3)
    # The settings toolbar search button is icon-only (`content-desc=Search`).
    if not ui_tap(adb, "Search", wait_s=4):
        die("could not find the settings search button")
    time.sleep(2)
    adb.shell("input", "text", "OCR")
    time.sleep(3)
    if not ui_tap(adb, "OCR models", wait_s=8):
        die("search results did not show the 'OCR models' preference")
    time.sleep(3)


# ---------------------------------------------------------------------------------------------- commands
def normalise_variant(value: str) -> str | None:
    """Maps CLI aliases onto the exact `RecModelVariant` enum names stored in the preference."""
    v = value.strip()
    if v.upper() in ("STOCK", "S"):
        return "STOCK"
    if v.upper() in ("FINE", "FINE_TUNED", "FINE-TUNED", "F"):
        return "FINE_TUNED"
    return None


def cmd_preflight(adb: Adb, _args) -> int:
    print(f"serial:       {adb.serial}")
    model = adb.shell("getprop", "ro.product.model").strip()
    release = adb.shell("getprop", "ro.build.version.release").strip()
    sdk = adb.shell("getprop", "ro.build.version.sdk").strip()
    abis = adb.shell("getprop", "ro.product.cpu.abilist").strip()
    print(f"device:       {model} (Android {release}, API {sdk}, {abis})")
    if model != "OPD2403":
        print(f"WARNING: expected the OPD2403 tablet, got {model!r}; latency numbers are device-specific")

    dump = adb.shell("dumpsys", "package", PKG, check=False)
    version = re.search(r"versionName=(\S+)", dump)
    code = re.search(r"versionCode=(\d+)", dump)
    if not version:
        die(f"{PKG} is not installed; install the debug APK first (update install keeps data)")
    print(f"package:      {PKG} versionName={version.group(1)} versionCode={code.group(1) if code else '?'}")

    variant, _ = prefs_get(adb, "pref_ocr_rec_model")
    source, _ = prefs_get(adb, "pref_ocr_source")
    overlay, _ = prefs_get(adb, "pref_reader_ocr_overlay_enabled")
    storage, _ = prefs_get(adb, "__APP_STATE_storage_dir")
    print(f"prefs:        pref_ocr_rec_model={variant or '<unset -> FINE_TUNED>'}"
          f" pref_ocr_source={source} pref_reader_ocr_overlay_enabled={overlay}")
    print(f"storage:      {storage}")

    listing = adb.run_as("ls", "-l", MODELS_DIR).strip().splitlines()
    print(f"models dir:   {MODELS_DIR}")
    sizes = {}
    for line in listing:
        parts = line.split()
        if len(parts) >= 7 and parts[-1].endswith((".onnx", ".txt", ".json")):
            sizes[parts[-1]] = int(parts[-4])
            print(f"  {parts[-1]:40} {int(parts[-4]):>10} B")
    selected = FINE_TUNED if (variant or "").upper() != "STOCK" else STOCK
    missing = [m for m in (*SHARED_MODELS, selected["rec"]) if m not in sizes]
    print(f"selected:     {selected['release']} -> {selected['rec']}")
    if missing:
        print(f"MISSING:      {', '.join(missing)}  (OCR will silently no-op until downloaded)")
    if "manifest.json" not in sizes:
        print("MISSING:      manifest.json  (first run will fetch it)")
    return 0 if not missing else 4


def _line_timestamp(line: str) -> float | None:
    m = re.match(r"\d\d-\d\d (\d\d):(\d\d):(\d\d)\.(\d{3})", line)
    if not m:
        return None
    h, mi, s, ms = (int(g) for g in m.groups())
    return h * 3600 + mi * 60 + s + ms / 1000


def parse_rows(text: str, state: dict[tuple[str, str], float | None] | None = None) -> list[dict]:
    """Extracts one row per OCR run.

    The app currently emits each `recognize:` record twice, 1-2 ms apart (same pid/tid/message); the
    logcat buffers can duplicate again. Collapse records with the same payload from the same thread
    inside a short window instead of trusting line identity. `state` carries the seen-and-when map
    across successive snapshots when collecting live.
    """
    last: dict[tuple[str, str], float | None] = state if state is not None else {}
    rows: list[dict] = []
    for line in text.splitlines():
        m = RECOGNIZE_RE.search(line) or TIMING_RE.search(line)
        if not m:
            continue
        parts = line.split()
        tid = parts[3] if len(parts) > 3 else ""
        ts = _line_timestamp(line)
        key = (tid, m.group(0))
        previous = last.get(key)
        if key in last and (ts is None or previous is None or ts - previous < 0.5):
            continue
        last[key] = ts
        rows.append({k: int(m.group(k)) for k in FIELDS if m.groupdict().get(k) is not None})
    return rows


def cmd_capture(adb: Adb, args) -> int:
    if args.clear_cache:
        adb.shell("am", "force-stop", PKG)
        adb.run_as("rm", "-rf", "files/ocr_cache", check=False)
        print("cleared files/ocr_cache (forces a real engine run instead of a cache hit)")

    if args.logcat_file:
        rows = parse_rows(open(args.logcat_file, errors="replace").read())
    else:
        adb.shell("logcat", "-c")
        print(f"collecting for {args.seconds}s — drive the reader now "
              f"(open a '{args.label}' page/oversized chapter; each new page OCRs once)", flush=True)
        rows: list[dict] = []
        state: dict[tuple[str, str], float | None] = {}
        deadline = time.time() + args.seconds
        while time.time() < deadline:
            time.sleep(3)
            rows.extend(parse_rows(adb.shell("logcat", "-d", check=False), state))

    if not rows:
        print("no TIMING/recognize lines captured.")
        print("Checklist: OCR overlay enabled? (Settings -> OCR -> Enable OCR overlay) | selected models")
        print("present? (run `preflight`) | page already cached? (re-run with --clear-cache) | engine")
        print("init failure? (`adb logcat -d | grep -E 'PaddleOcrEngine|ReaderOcr'`).")
        return 5

    print(f"\n{'#':>2} {'total':>7} {'prep':>5} {'det':>5} {'post':>5} {'crop':>5} {'rec':>5} "
          f"{'boxes':>5} {'lines':>5}")
    for i, r in enumerate(rows, 1):
        print(f"{i:>2} {r.get('total', 0):>7} {r.get('prep', 0):>5} {r.get('det', 0):>5} "
              f"{r.get('post', 0):>5} {r.get('crop', 0):>5} {r.get('rec', 0):>5} "
              f"{r.get('boxes', 0):>5} {r.get('lines', 0):>5}")
    totals = [r.get("total", 0) for r in rows]
    print(f"\nn={len(totals)}  median={statistics.median(totals):.0f}ms  "
          f"min={min(totals)}ms  max={max(totals)}ms  label={args.label}  device={adb.serial}")

    if args.out:
        payload = {"label": args.label, "serial": adb.serial, "rows": rows}
        with open(args.out, "w") as f:
            json.dump(payload, f, indent=2)
        print(f"wrote {args.out}")
    return 0


def _model_listing(adb: Adb) -> dict[str, int]:
    """name -> size for the model files actually on the device."""
    listing = adb.run_as("ls", "-l", MODELS_DIR, check=False).splitlines()
    sizes: dict[str, int] = {}
    for line in listing:
        parts = line.split()
        if len(parts) >= 7 and parts[-1].endswith((".onnx", ".txt", ".json")):
            try:
                sizes[parts[-1]] = int(parts[-4])
            except ValueError:
                continue
    return sizes


def _device_sha256(adb: Adb, name: str) -> str | None:
    """SHA-256 of one model file as the app uid, or None when `sha256sum` is unavailable."""
    out = adb.shell("run-as", PKG, "sha256sum", f"{MODELS_DIR}/{name}", check=False)
    m = re.search(r"\b([0-9a-fA-F]{64})\b", out)
    return m.group(1).lower() if m else None


def _manifest(adb: Adb, release: str) -> dict | None:
    """The manifest cached beside the models, else the published one (network); None if neither."""
    raw = adb.sh("exec-out", "run-as", PKG, "cat", f"{MODELS_DIR}/manifest.json", check=False)
    try:
        cached = json.loads(raw)
    except json.JSONDecodeError:
        cached = None
    if cached and cached.get("release") == release:
        return cached
    try:
        with urllib.request.urlopen(f"{RELEASE_ROOT}/{release}/manifest.json", timeout=30) as resp:
            published = json.load(resp)
    except Exception:  # noqa: BLE001 - offline is a legitimate case, reported as "unverified"
        return cached
    return published if published.get("release") == release else cached


def assess_model(
    name: str,
    present_size: int | None,
    expected_size: int | None,
    expected_sha256: str | None,
    computed_sha256: str | None,
) -> tuple[bool, str]:
    """Returns (verified, message). `verified` is only True when size *and* SHA-256 both match.

    A mere `ls` is never reported as verification: without a manifest entry (or without
    `sha256sum`/a published hash) the message says so explicitly.
    """
    if present_size is None:
        return False, f"{name}: missing"
    if expected_size is None:
        return False, f"{name}: present ({present_size} B); no manifest size — NOT verified"
    if present_size != expected_size:
        return False, f"{name}: SIZE MISMATCH {present_size} != {expected_size} B"
    if expected_sha256 is None:
        return False, f"{name}: size ok ({present_size} B); manifest has no SHA-256 — hash NOT verified"
    if computed_sha256 is None:
        return False, f"{name}: size ok ({present_size} B); SHA-256 not computed — hash NOT verified"
    if computed_sha256.lower() != expected_sha256.lower():
        return False, f"{name}: SHA-256 MISMATCH ({computed_sha256[:12]}… != {expected_sha256[:12]}…)"
    return True, f"{name}: size + SHA-256 match manifest ({present_size} B)"


def verify_models(adb: Adb, wanted: dict) -> tuple[bool, list[str]]:
    """Checks the variant's required files against the release manifest (size + SHA-256)."""
    manifest = _manifest(adb, wanted["release"])
    entries = {f["name"]: f for f in (manifest or {}).get("files", [])}
    sizes = _model_listing(adb)
    all_ok = True
    messages: list[str] = []
    for name in (*SHARED_MODELS, wanted["rec"]):
        entry = entries.get(name, {})
        computed = _device_sha256(adb, name) if sizes.get(name) else None
        ok, message = assess_model(
            name,
            sizes.get(name),
            entry.get("size") if entry else None,
            entry.get("sha256") if entry else None,
            computed,
        )
        all_ok = all_ok and ok
        messages.append(("OK  " if ok else "FAIL") + " " + message)
    return all_ok, messages


# Snapshot of the value the last `variant` switch replaced, so `--restore` is not guesswork: by the
# time a separate `--restore` invocation runs, the pref already holds the *new* value.
VARIANT_STATE_PATH = "/tmp/opencode/track-b-ocr-variant-state.json"


def load_variant_state() -> dict | None:
    try:
        with open(VARIANT_STATE_PATH) as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError):
        return None


def save_variant_state(serial: str, previous: str | None, target: str) -> None:
    with open(VARIANT_STATE_PATH, "w") as f:
        json.dump(
            {"serial": serial, "previous": previous, "target": target, "at": time.strftime("%Y-%m-%dT%H:%M:%S")},
            f,
            indent=2,
        )


def resolve_restore(state: dict | None, serial: str, cli_value: str | None) -> tuple[str | None, str | None]:
    """Decides what `variant --restore` must write. Returns (value_or_None_to_unset, error).

    `None` as the value means "remove the key" (the pref was unset before the switch, i.e. default).
    """
    if cli_value:
        return None, (
            "--restore takes no variant: it rewrites the value recorded before the last switch. "
            "Run `variant <stock|fine>` to switch instead."
        )
    if not state:
        return None, (
            f"no saved variant snapshot at {VARIANT_STATE_PATH}; nothing to restore. Run "
            "`variant stock` (or `fine`) first, or write the preference by hand."
        )
    if state.get("serial") != serial:
        return None, (
            f"the saved snapshot belongs to {state.get('serial')!r}, not {serial!r}; refusing to "
            "restore another device's value"
        )
    return state.get("previous"), None


def variant_result(verified: bool, ui_ready: bool | None, release: str) -> tuple[int, str | None]:
    """Exit code + message for a finished `variant` run.

    Unverifiable files outrank the UI state (they explain it); a verified download whose settings row
    still says "OCR not ready" is **not** a success — the engine would silently no-op on every page.
    `ui_ready is None` means `--verify-ui` was not requested.
    """
    if not verified:
        return 4, f"NOT VERIFIED: files exist but do not match the {release} manifest (see above)"
    if ui_ready is False:
        return 9, (
            "ERROR: the settings row reports 'OCR not ready' although the model files verify; the "
            "engine will not run OCR — check `adb logcat -d | grep -E 'PaddleOcrEngine|ModelDownloader'`"
        )
    return 0, f"OK: {release} models verified against the manifest (size + SHA-256)"


def cmd_variant(adb: Adb, args) -> int:
    key = "pref_ocr_rec_model"
    previous, _ = prefs_get(adb, key)

    if args.restore:
        target, error = resolve_restore(load_variant_state(), adb.serial, args.value)
        if error:
            die(error)
        shown = "<unset (removes the key; default FINE_TUNED)>" if target is None else repr(target)
        print(f"restoring {key} -> {shown}")
        prefs_set(adb, key, target)
        try:
            os.remove(VARIANT_STATE_PATH)
        except OSError:
            pass
        print("pref written only; the model files on disk were not touched, so run `preflight` to see "
              "whether the restored variant's weights are present")
        return 0

    target = normalise_variant(args.value or "")
    if target is None:
        die(f"unknown variant {args.value!r}; use 'stock' or 'fine'")
    wanted = STOCK if target == "STOCK" else FINE_TUNED
    print(f"{key}: {previous or '<unset -> FINE_TUNED>'} -> {target} "
          f"(release {wanted['release']}, rec {wanted['rec']})")
    save_variant_state(adb.serial, previous, target)
    print(f"undo: scripts/track-b-ocr-device-check.py --serial {adb.serial} variant --restore "
          f"(snapshot in {VARIANT_STATE_PATH})")
    prefs_set(adb, key, target)

    if args.no_download:
        print("pref written; skipping the downloader trigger (--no-download)")
        return 0

    open_ocr_settings(adb)
    print("tapping the model-status row to run the downloader (it re-verifies size + SHA-256)")
    adb.shell("logcat", "-c")
    if not ui_tap(adb, "OCR models"):
        die("could not find the 'OCR models' status row")

    required = (*SHARED_MODELS, wanted["rec"])
    downloaded: set[str] = set()
    failed = False
    waited = 0.0
    while waited < args.timeout:
        time.sleep(3)
        waited += 3
        log = adb.shell("logcat", "-d", check=False)
        for line in log.splitlines():
            m = re.search(r"ModelDownloader: downloading (\S+)$", line)
            if m:
                downloaded.add(m.group(1))
        if "model download failed:" in log:
            for line in log.splitlines():
                if "model download failed:" in line:
                    print(f"DOWNLOAD FAILED: {line.split('model download failed: ', 1)[-1]}")
            failed = True
            break
        present = _model_listing(adb)
        if all(m in present for m in required):
            break

    if failed:
        return 6
    if not all(m in _model_listing(adb) for m in required):
        print(f"timeout after {args.timeout:.0f}s: model file not present and no failure logged")
        return 7

    if downloaded:
        print(f"downloaded during this run: {', '.join(sorted(downloaded))}")
    else:
        print("no download logged: every required file was already present (manifest fast path)")
    verified, messages = verify_models(adb, wanted)
    for line in messages:
        print("  " + line)
    ui_ready: bool | None = None
    if args.verify_ui:
        ui_ready = any("OCR ready" == t for t, *_ in ui_nodes(ui_dump(adb)))
        print(f"settings status row: {'OCR ready' if ui_ready else 'NOT ready'}")
    code, message = variant_result(verified, ui_ready, wanted["release"])
    if message:
        print(message)
    return code


def cmd_selftest(_adb, args) -> int:
    """Offline checks: parser fixtures plus (optionally) the published release manifests."""
    fixture = """
10-24 18:09:44.929  6970  7024 I PaddleOcrEngine: recognize: Total: 662ms (Prep: 7ms, Det: 283ms, Post: 6ms, Crop: 34ms, Rec: 330ms) | Boxes: 21, Lines: 20
10-24 18:11:47.120  6970  7480 I PaddleOcrEngine: recognize: Total: 873ms (Prep: 10ms, Det: 266ms, Post: 7ms, Crop: 218ms, Rec: 370ms) | Boxes: 41, Lines: 37
TIMING|526|6|251|9|24|234|21
"""
    parsed = []
    doubles = """
10-24 18:11:47.120  6970  7480 I PaddleOcrEngine: recognize: Total: 873ms (Prep: 10ms, Det: 266ms, Post: 7ms, Crop: 218ms, Rec: 370ms) | Boxes: 41, Lines: 37
10-24 18:11:47.122  6970  7480 I PaddleOcrEngine: recognize: Total: 873ms (Prep: 10ms, Det: 266ms, Post: 7ms, Crop: 218ms, Rec: 370ms) | Boxes: 41, Lines: 37
10-24 18:11:48.900  6970  7480 I PaddleOcrEngine: recognize: Total: 873ms (Prep: 10ms, Det: 266ms, Post: 7ms, Crop: 218ms, Rec: 370ms) | Boxes: 41, Lines: 37
"""
    # Two records 2 ms apart are the same OCR run; the third (1.8 s later, same numbers) is a new one.
    assert len(parse_rows(doubles)) == 2, parse_rows(doubles)
    print("dedupe: OK (duplicate records collapsed, a later identical run kept)")
    for line in fixture.splitlines():
        m = RECOGNIZE_RE.search(line) or TIMING_RE.search(line)
        if m:
            parsed.append({k: int(m.group(k)) for k in FIELDS if m.groupdict().get(k) is not None})
    assert parsed[0]["total"] == 662 and parsed[0]["rec"] == 330 and parsed[0]["lines"] == 20, parsed
    assert parsed[2] == {"total": 526, "prep": 6, "det": 251, "post": 9, "crop": 24, "rec": 234, "boxes": 21}, parsed[2]
    assert statistics.median([p["total"] for p in parsed]) == 662
    print(f"parser: OK ({len(parsed)} fixture lines)")

    # `--restore` writes the value recorded before the last switch; a CLI value can never override it,
    # an unset previous value restores to unset, and a missing/foreign snapshot is a hard error.
    assert resolve_restore({"serial": "dev", "previous": "STOCK"}, "dev", None) == ("STOCK", None)
    assert resolve_restore({"serial": "dev", "previous": None}, "dev", None) == (None, None)
    target, error = resolve_restore(None, "dev", None)
    assert target is None and error and "nothing to restore" in error, (target, error)
    target, error = resolve_restore({"serial": "dev", "previous": "STOCK"}, "dev", "fine")
    assert error and "takes no variant" in error, (target, error)
    target, error = resolve_restore({"serial": "other", "previous": "STOCK"}, "dev", None)
    assert error and "another device" in error, (target, error)
    print("restore: OK (recorded value wins; unset stays unset; missing/foreign snapshot errors)")

    # A file-presence check must never be reported as verification (review finding: the old code said
    # "downloader verified size/SHA-256" from a bare `ls`).
    ok, msg = assess_model("m.onnx", 100, 100, "a" * 64, "a" * 64)
    assert ok and "size + SHA-256 match" in msg, msg
    ok, msg = assess_model("m.onnx", 100, 100, None, None)
    assert not ok and "NOT verified" in msg and "SHA-256 match" not in msg, msg
    ok, msg = assess_model("m.onnx", 100, None, None, None)
    assert not ok and "no manifest size" in msg, msg
    ok, msg = assess_model("m.onnx", 99, 100, "a" * 64, "a" * 64)
    assert not ok and "SIZE MISMATCH" in msg, msg
    ok, msg = assess_model("m.onnx", None, 100, "a" * 64, None)
    assert not ok and "missing" in msg, msg
    ok, msg = assess_model("m.onnx", 100, 100, "b" * 64, "a" * 64)
    assert not ok and "SHA-256 MISMATCH" in msg, msg
    ok, msg = assess_model("m.onnx", 100, 100, "a" * 64, None)
    assert not ok and "hash NOT verified" in msg, msg
    print("verification: OK (only size+SHA-256 counts as verified; presence alone says NOT verified)")

    # `--verify-ui` gates success: verified files with a settings row that says "OCR not ready" is a
    # failure (the engine would silently no-op), and an unverifiable file outranks the UI state.
    assert variant_result(True, None, "ocr-models-v3")[0] == 0
    assert variant_result(True, True, "ocr-models-v3")[0] == 0
    code, message = variant_result(True, False, "ocr-models-v3")
    assert code == 9 and message and "OCR not ready" in message, (code, message)
    code, message = variant_result(False, False, "ocr-manga-v2")
    assert code == 4 and message and "NOT VERIFIED" in message, (code, message)
    print("variant exit: OK (verified+ready=0, verified+not-ready=9, unverified files=4)")

    if not args.online:
        print("release manifests: skipped (pass --online to check the published releases)")
        return 0
    failures = 0
    for variant in (STOCK, FINE_TUNED):
        url = f"{RELEASE_ROOT}/{variant['release']}/manifest.json"
        try:
            manifest = json.load(urllib.request.urlopen(url, timeout=30))
        except Exception as e:  # noqa: BLE001 - report, do not raise
            print(f"manifest {variant['release']}: FETCH FAILED ({e})")
            failures += 1
            continue
        files = {f["name"]: f for f in manifest.get("files", [])}
        missing = [m for m in (*SHARED_MODELS, variant["rec"]) if m not in files]
        print(f"manifest {variant['release']}: release={manifest.get('release')!r} "
              f"files={len(files)} missing={missing or 'none'}")
        if missing:
            failures += 1
        for name in (*SHARED_MODELS, variant["rec"]):
            entry = files.get(name)
            if not entry:
                continue
            # ModelDownloader builds `$baseUrl/${entry.path ?: "models/$name"}` (ModelDownloader.kt:204-243);
            # GitHub release assets are flat, so anything relying on the `models/` default must 404.
            rel = entry.get("path") or f"models/{name}"
            req = urllib.request.Request(f"{RELEASE_ROOT}/{variant['release']}/{rel}", method="HEAD")
            try:
                with urllib.request.urlopen(req, timeout=30) as resp:
                    state = f"HTTP {resp.status}"
            except Exception as e:  # noqa: BLE001
                state = f"UNREACHABLE ({getattr(e, 'code', e)})"
            print(f"  {name:42} path={rel:46} {state}")
            if not state.startswith("HTTP 20"):
                failures += 1
    print("release downloads: OK" if failures == 0 else f"release downloads: {failures} problem(s)")
    return 0 if failures == 0 else 8


# -------------------------------------------------------------------------------------------------- main
def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--serial")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("preflight", help="device, package, models and OCR prefs (read-only)")

    cap = sub.add_parser("capture", help="collect TIMING/recognize lines into a table")
    cap.add_argument("--label", default="normal", help="normal | oversized (documentation only)")
    cap.add_argument("--seconds", type=float, default=60)
    cap.add_argument("--clear-cache", action="store_true", help="delete files/ocr_cache first")
    cap.add_argument("--logcat-file", help="parse a saved `adb logcat -d` dump instead of the device")
    cap.add_argument("--out", help="write the parsed rows as JSON here")

    var = sub.add_parser("variant", help="switch the recogniser variant and exercise the download")
    var.add_argument("value", nargs="?", choices=["stock", "fine", "fine-tuned", "STOCK", "FINE_TUNED"],
                     help="variant to switch to; omit with --restore")
    var.add_argument("--restore", action="store_true",
                     help="rewrite the value recorded before the last switch (pref only, no download)")
    var.add_argument("--no-download", action="store_true")
    var.add_argument("--timeout", type=float, default=120)
    var.add_argument("--verify-ui", action="store_true",
                     help="also read the settings status row and require it to say 'OCR ready' (exit 9 otherwise)")

    st = sub.add_parser("selftest", help="offline parser + release-manifest checks")
    st.add_argument("--online", action="store_true")

    args = parser.parse_args()
    if args.command == "variant" and not args.restore and not args.value:
        parser.error("variant needs a value (stock|fine) or --restore")

    acquire_lock()
    if args.command == "selftest":
        return cmd_selftest(None, args)
    adb = Adb(args.serial)
    return {
        "preflight": cmd_preflight,
        "capture": cmd_capture,
        "variant": cmd_variant,
    }[args.command](adb, args)


if __name__ == "__main__":
    sys.exit(main())
