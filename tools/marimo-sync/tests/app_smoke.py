#!/usr/bin/env python3
"""Check the server window: convert, shade, serve — and never touch a device.

    python3 tests/app_smoke.py

Three rules this file exists to enforce:

1. **No device, ever.** `autodetect_device` and `open_device` are replaced with
   something that raises, so if the window (or anything it reaches) tries to resolve
   adb or MTP, this suite fails loudly instead of quietly working on a box that
   happens to have a phone plugged in. The phone is a client that reports in.
2. **Fixtures on both sides.** The library *and* the originals are under ~/mika/tmp
   and the scripts are stubs. Its sibling suite once left `flac_source` unset, so it
   fell back to ~/Music and converted one of the real albums into a scratch folder.
3. **The phone's report comes off disk.** Status here must work with no phone in
   existence, which is the whole point of doing this over the network.
"""

from __future__ import annotations

import json
import os
import shutil
import socket
import sys
import time
import urllib.request
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent
sys.path.insert(0, str(PROJECT))
sys.path.insert(0, str(HERE))

import fixture  # noqa: E402

SCRATCH = Path.home() / "mika" / "tmp" / "app-smoke"
LIB = SCRATCH / "lib"
SRC = SCRATCH / "flac"
CONFIG = SCRATCH / "config.json"
PORT = 8478                    # fixed, so the test can look for a listener
TOKEN = "app-smoke-token"
FAILED: list[str] = []
N = 0


def check(name: str, ok: bool, detail: str = "") -> None:
    global N
    N += 1
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"  -- {detail}" if detail and not ok else ""))
    if not ok:
        FAILED.append(name)


def health(token: str | None = TOKEN) -> dict:
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}/api/health")
    if token:
        req.add_header("X-Marimo-Token", token)
    with urllib.request.urlopen(req, timeout=5) as r:
        return json.loads(r.read())


def wait_for_port(app, want_open: bool, timeout: float = 30.0) -> bool:
    """Wait for a listener while pumping Qt's event loop, or nothing ever fires."""
    t0 = time.monotonic()
    while time.monotonic() - t0 < timeout:
        app.processEvents()
        s = socket.socket()
        s.settimeout(0.3)
        now = s.connect_ex(("127.0.0.1", PORT)) == 0
        s.close()
        if now == want_open:
            return True
        time.sleep(0.2)
    return False


STUB_CONVERT = '''#!/usr/bin/env python3
import argparse, shutil
from pathlib import Path
p = argparse.ArgumentParser()
p.add_argument("--src"); p.add_argument("--dst")
p.add_argument("--one"); p.add_argument("--dry", action="store_true")
a = p.parse_args()
if a.dry:
    raise SystemExit(0)
dst = Path(a.dst) / a.one.replace("[FLAC]", "[OPUS]")
dst.mkdir(parents=True, exist_ok=True)
for f in sorted((Path(a.src) / a.one).iterdir()):
    shutil.copy2(f, dst / f.name)
print("converted", a.one)
'''

STUB_WAVE = '''#!/usr/bin/env python3
import struct, sys, unicodedata
from pathlib import Path
for d in sys.argv[1:]:
    album = Path(d)
    names = sorted(p.name for p in album.iterdir()
                   if p.suffix.lower() in (".opus", ".mp3", ".m4a", ".flac"))
    out = bytearray(b"MWAVS001") + struct.pack("<I", len(names))
    for n in names:
        raw = unicodedata.normalize("NFC", n).encode("utf-8")
        out += struct.pack("<H", len(raw)) + raw + bytes([60] * 96)
    (album / "waves.marimo").write_bytes(bytes(out))
    print("shaded", album.name)
'''


def main() -> int:
    if SCRATCH.exists():
        shutil.rmtree(SCRATCH)
    SCRATCH.mkdir(parents=True)
    albums = fixture.build_library(LIB, fixture.ALBUMS[:2])
    fresh = "New Artist - (2026) Fresh [OPUS]"
    orig = SRC / "New Artist - (2026) Fresh [FLAC]"
    fixture.tone(orig / "01. brand new.flac", 440)
    fixture.cover(orig / "cover.jpg")
    print(f"fixture: {len(albums)} albums in the library, 1 album of originals only\n")

    (SCRATCH / "stub-convert.py").write_text(STUB_CONVERT)
    (SCRATCH / "stub-convert.py").chmod(0o755)
    (SCRATCH / "stub-wave.py").write_text(STUB_WAVE)
    (SCRATCH / "stub-wave.py").chmod(0o755)

    CONFIG.write_text(json.dumps({
        "source": str(LIB),
        "flac_source": str(SRC),                 # fixtures, never ~/Music
        "convert_script": str(SCRATCH / "stub-convert.py"),
        "wave_script": str(SCRATCH / "stub-wave.py"),
        "scratch": str(SCRATCH),
        "serve": {"port": PORT, "token": TOKEN, "auto": True,
                  "state": str(SCRATCH / "inventory.json")},
    }))
    os.environ["XDG_CONFIG_HOME"] = str(SCRATCH / "xdg-config")

    # rule 1: the window is not allowed to be able to find a device
    from marimosync import transports

    def forbidden(*a, **k):
        raise AssertionError("the server window tried to resolve a device")

    transports.autodetect_device = forbidden
    transports.open_device = forbidden

    from PyQt6.QtWidgets import QApplication
    from marimosync.app import ServeWindow
    from marimosync.config import Config

    app = QApplication([])
    print("window: opening it with no device reachable at all")
    try:
        w = ServeWindow(["--config", str(CONFIG)])
        opened = True
    except AssertionError as e:
        w = None
        opened = False
        check("opening the window never asks for a device", False, str(e))
    if opened:
        check("opening the window never asks for a device", True)
        w.show()

        served = wait_for_port(app, True, timeout=30)
        check(f"and it serves on port {PORT} by itself", served)
        check("showing the address to type into the phone",
              bool(w.url) and w.url in w.lbl_addr.text(), w.lbl_addr.text())

        # --prepare-now: the library is made ready before a byte is served
        deadline = time.monotonic() + 90
        state = ""
        while time.monotonic() < deadline:
            app.processEvents()
            try:
                state = (health().get("preparing") or {}).get("state", "")
            except Exception:
                state = ""
            if state in ("ready", "failed"):
                break
            w.refresh()
            time.sleep(0.3)
        check("it prepares the library before serving", state == "ready", state)
        check("so an album that existed only as originals got converted",
              (LIB / fresh).is_dir(), str(sorted(p.name for p in LIB.iterdir()))[:140])
        check("and given a waveform sidecar", (LIB / fresh / "waves.marimo").is_file(), "")

        w.refresh()
        check("the window shows the library it is serving",
              str(len(albums) + 1) in w.lbl_lib.text() and "albums" in w.lbl_lib.text(),
              w.lbl_lib.text())
        check("and says the phone hasn't reported, rather than hunting for it",
              "nothing reported yet" in w.lbl_phone.text(), w.lbl_phone.text())

        # the phone reports in over the network; the window reads that report off disk
        inv = Path(json.loads(CONFIG.read_text())["serve"]["state"])
        inv.parent.mkdir(parents=True, exist_ok=True)
        inv.write_text(json.dumps({
            "version": 1, "reported": "2026-09-29T06:40:03+01:00",
            "albums": {albums[0]: {"files": {"01. a.opus": 10}},
                       albums[1]: {"files": {}}},
        }))
        w.refresh()
        check("so its status comes from the phone's own report, with no cable",
              "2 albums" in w.lbl_phone.text() and "06:40" in w.lbl_phone.text(),
              w.lbl_phone.text())

        # -- the phone row: only when a phone is on the cable, and never resolved here
        # (a stub adb keeps this hermetic: the machine running the suite may well have
        #  a real phone attached, and the test must not depend on that either way)
        from marimosync import app as appmod
        empty = SCRATCH / "adb-empty"
        empty.write_text("#!/bin/sh\necho 'List of devices attached'\n")
        empty.chmod(0o755)
        one = SCRATCH / "adb-one"
        one.write_text("#!/bin/sh\nprintf 'List of devices attached\\nTITAN99\\tdevice\\n'\n")
        one.chmod(0o755)
        os.environ["MARIMO_SYNC_ADB"] = str(empty)

        check("with nothing on adb, no phone is listed", appmod.adb_serials(empty) == [], "")
        check("a listed device is parsed out", appmod.adb_serials(one) == ["TITAN99"],
              str(appmod.adb_serials(one)))
        w.check_for_phone()
        check("so the phone row stays hidden", not w.device_row.isVisible(), "")
        check("and the window itself still resolves no transport",
              transports.autodetect_device is forbidden
              and transports.open_device is forbidden, "")
        check("the device flag is placed before the subcommand",
              appmod.device_argv([], ["status"], "adb:X", json_out=True)
              == ["--plain", "--json", "--device", "adb:X", "status"],
              str(appmod.device_argv([], ["status"], "adb:X", json_out=True)))

        os.environ["MARIMO_SYNC_ADB"] = str(one)
        w.check_for_phone()
        check("with a phone on the cable, the row appears", w.device_row.isVisible(), "")
        # The row's numbers come from `status --json`, which refresh_device starts; with
        # a fake serial that fails fast and read-only, which is the point of the check.
        os.environ["MARIMO_SYNC_ADB"] = str(empty)

        # and the destructive button refuses the mis-rooted case outright
        w.device_files, w.device_orphans, w.adb_serial = 8053, 8053, "X"
        w.clean_phone()
        log = w.log.toPlainText()
        check("it refuses to clean when every file on the phone looks orphaned",
              "refusing to clean" in log and "sync --prune" not in log,
              log[-140:])
        w.adb_serial = None

        shot = SCRATCH / "window.png"
        w.grab().save(str(shot))
        print(f"  rendered the window to {shot}")

        w.close()
        gone = wait_for_port(app, False, timeout=15)
        check("closing the window stops the server", gone)

    print()
    if FAILED:
        print(f"{len(FAILED)}/{N} checks FAILED: " + ", ".join(FAILED))
        return 1
    print(f"all {N} checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
