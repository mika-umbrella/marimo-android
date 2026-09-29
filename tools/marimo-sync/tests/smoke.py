#!/usr/bin/env python3
"""End-to-end test of the engine, against a synthetic library and a directory "device".

Walks the situations that actually happen: a fresh device, an album missing a
sidecar, a file corrupted in place without changing length, a renamed album, a
deleted track, an album with decomposed (NFD) filenames, and how settings resolve.

Both sides of every sync are under ~/mika/tmp, and the environment points
XDG_CONFIG_HOME at the fixture too, so the suite can't read the real library or
behave differently because of a real config file.

    python3 tests/smoke.py
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent
sys.path.insert(0, str(HERE))

import fixture  # noqa: E402

CLI = PROJECT / "marimo-sync"
SCRATCH = Path.home() / "mika" / "tmp" / "sync-smoke"
LIB = SCRATCH / "lib"
DEV = SCRATCH / "device"
SRC = SCRATCH / "flac"
MANIFEST = SCRATCH / ".marimo-sync" / "manifest.json"
ENV = {**os.environ,
       "XDG_CONFIG_HOME": str(SCRATCH / "xdg-config"),
       "XDG_CACHE_HOME": str(SCRATCH / "xdg-cache"),
       "MARIMO_SYNC_FLAC_SOURCE": str(SRC),
       "MARIMO_SYNC_WAVE_SCRIPT": str(SCRATCH / "stub-wave.py"),
       "MARIMO_SYNC_CONVERT_SCRIPT": str(SCRATCH / "stub-convert.py")}

STUB_WAVE = '''#!/usr/bin/env python3
"""Stand-in for make-waveforms.py: writes a valid sidecar for each album given."""
import struct, sys, unicodedata
from pathlib import Path
AUDIO = {".opus", ".mp3", ".m4a", ".flac"}
for root in sys.argv[1:]:
    d = Path(root)
    names = sorted(p.name for p in d.iterdir() if p.suffix.lower() in AUDIO)
    out = bytearray(b"MWAVS001") + struct.pack("<I", len(names))
    for n in names:
        b = unicodedata.normalize("NFC", n).encode()
        out += struct.pack("<H", len(b)) + b + bytes([70] * 96)
    (d / "waves.marimo").write_bytes(bytes(out))
    print("stub sidecar for", d.name)
'''
STUB_CONVERT = '''#!/usr/bin/env python3
import sys
print("stub converter called with:", " ".join(sys.argv[1:]))
'''


def sha256(data: bytes) -> str:
    import hashlib
    return hashlib.sha256(data).hexdigest()

FAILED: list[str] = []
N = 0


def check(name: str, ok: bool, detail: str = "") -> None:
    global N
    N += 1
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"  -- {detail}" if detail and not ok else ""))
    if not ok:
        FAILED.append(name)


def run(*args: str, expect_ok: bool = True, env: dict | None = None) -> str:
    r = subprocess.run([str(CLI), "--plain", "--source", str(LIB), "--device", f"dir:{DEV}", *args],
                       capture_output=True, text=True, env=env or ENV)
    if expect_ok and r.returncode != 0:
        print(r.stdout)
        print(r.stderr, file=sys.stderr)
        raise SystemExit(f"`marimo-sync {' '.join(args)}` failed with {r.returncode}")
    return r.stdout + r.stderr        # warnings matter to the assertions too


def plan() -> dict:
    return json.loads(subprocess.run(
        [str(CLI), "--plain", "--json", "--source", str(LIB), "--device", f"dir:{DEV}", "status"],
        capture_output=True, text=True, env=ENV).stdout)


def device_files() -> set[str]:
    """Every device file the tool considers library content (dotfiles are not)."""
    return {str(p.relative_to(DEV)) for p in DEV.rglob("*")
            if p.is_file() and not any(part.startswith(".") for part in p.relative_to(DEV).parts)}


def library_files() -> set[str]:
    return {str(p.relative_to(LIB)) for p in LIB.rglob("*") if p.is_file()}


def manifest_matches() -> dict[str, int]:
    from collections import Counter
    data = json.loads(MANIFEST.read_text())
    return dict(Counter(f["match"] for a in data["albums"].values() for f in a["files"].values()))


def main() -> int:
    if SCRATCH.exists():
        shutil.rmtree(SCRATCH)
    SCRATCH.mkdir(parents=True)
    albums = fixture.build_library(LIB)
    for name, src in ((STUB_WAVE, "stub-wave.py"), (STUB_CONVERT, "stub-convert.py")):
        p = SCRATCH / src
        p.write_text(name)
        p.chmod(0o755)
    (DEV).mkdir(parents=True, exist_ok=True)
    fixture.copy_album(LIB / albums[0], DEV / albums[0])                       # in sync already
    fixture.copy_album(LIB / albums[1], DEV / albums[1], skip={"waves.marimo"})  # missing a sidecar
    fixture.ghost_album(DEV)
    print(f"fixture: {len(albums)} albums, {len(library_files())} files\n")

    print("0. settings resolve the way they should")
    cfg_path = SCRATCH / "config.json"
    cfg_path.write_text(json.dumps({"source": str(LIB), "device": f"dir:{DEV}"}))
    from_file = subprocess.run([str(CLI), "--plain", "--config", str(cfg_path), "config"],
                               capture_output=True, text=True, env=ENV).stdout
    check("a config file is honoured", str(LIB) in from_file, from_file[:120])
    check("and it says where each value came from", str(cfg_path) in from_file, from_file[:120])
    env_wins = subprocess.run([str(CLI), "--plain", "--config", str(cfg_path), "config"],
                              capture_output=True, text=True,
                              env={**ENV, "MARIMO_SYNC_ROOT": "/from/env"}).stdout
    check("the environment beats the file",
          "/from/env" in env_wins and "[env MARIMO_SYNC_ROOT]" in env_wins, env_wins[:200])
    flag_wins = subprocess.run([str(CLI), "--plain", "--config", str(cfg_path), "--root", "/from/flag",
                                "config"], capture_output=True, text=True,
                               env={**ENV, "MARIMO_SYNC_ROOT": "/from/env"}).stdout
    check("a flag beats the environment",
          "/from/flag" in flag_wins and "[--flag]" in flag_wins, flag_wins[:200])
    asked = subprocess.run([str(CLI), "--plain", "--source", str(LIB), "--device", f"dir:{DEV}",
                            "waves"], capture_output=True, text=True,
                           env={**ENV, "MARIMO_SYNC_WAVE_SCRIPT": "none"})
    check("asking for a stage that isn't configured fails, and says why",
          "not done" in (asked.stdout + asked.stderr) and asked.returncode == 1,
          f"{asked.returncode} {asked.stdout}{asked.stderr}")

    print("\n1. first look at a half-populated device")
    out = run("status")
    check("new albums detected", "NEW ON DEVICE (2)" in out, out)
    check("the sidecar gap is spotted", "1 to push" in out, out)
    check("the orphan album is spotted", "ON DEVICE ONLY (1)" in out, out)
    check("nothing has been read back yet", "BELIEVED BUT UNVERIFIED" in out, out)
    check("the NFD album is NOT reported as having a sidecar gap",
          "NO USABLE WAVEFORM SIDECAR" not in out, out)

    print("\n2. sync pushes exactly the difference")
    before = plan()
    expected = before["push_files"]
    out = run("sync")
    check(f"pushed {expected} file(s) and no more", f"pushed {expected} file(s)" in out, out)
    check("the device now has every library file plus the ghost",
          device_files() == library_files() | {"Ghost Artist - (1999) Lost [OPUS]/01. gone.opus"},
          str(device_files() - (library_files() | {"Ghost Artist - (1999) Lost [OPUS]/01. gone.opus"})))
    check("provenance was recorded", "pushed" in manifest_matches(), str(manifest_matches()))

    print("\n3. a second look is clean")
    out = run("status")
    check("no albums out of step", "OUT OF STEP" not in out, out)
    check("all local albums in sync", f'library  {len(albums)} albums' in out, out)

    print("\n4. a file corrupted in place, same length")
    victim = DEV / albums[2] / next(p.name for p in (LIB / albums[2]).iterdir()
                                    if p.suffix in (".opus", ".mp3"))
    before_bytes = victim.read_bytes()
    flipped = bytearray(before_bytes)
    flipped[200:205] = b"XXXXX"
    victim.write_bytes(bytes(flipped))
    check("status stays quiet, because the length is unchanged",
          "OUT OF STEP" not in run("status"))

    print("\n5. verify reads it back and catches it")
    out = run("verify")
    check("verify reports a content mismatch", "differ from the library in content" in out, out)
    check("marked as mismatch", manifest_matches().get("mismatch") == 1, str(manifest_matches()))
    check("everything else got upgraded", manifest_matches().get("size", 0) == 0,
          str(manifest_matches()))

    print("\n6. a plain sync repairs it without re-reading anything")
    out = run("sync")
    check("one file pushed", "pushed 1 file(s)" in out, out)
    check("the file is back", victim.read_bytes() == before_bytes)
    check("clean again", "OUT OF STEP" not in run("status"))

    print("\n6b. a file verify cannot read is UNKNOWN, not wrong")
    unreadable = DEV / albums[3] / "cover.jpg"
    unreadable.chmod(0o000)
    out = run("verify")
    check("verify says it could not read it", "could NOT be read back" in out, out)
    check("and does not claim it differs", "differ from the library in content" not in out, out)
    check("and offers nothing as a re-upload", plan()["push_files"] == 0,
          str(plan()["push_files"]))
    unreadable.chmod(0o644)
    check("once readable again it verifies clean", "could NOT be read back" not in run("verify"))

    print("\n7. prune removes what the library doesn't have")
    (DEV / ".nomedia").write_bytes(b"a control file that isn't ours to delete\n")
    out = run("status")
    check("a dotfile at the device root is not library content", ".nomedia" not in out, out)
    check("and it isn't counted as an orphan", plan()["orphan_files"] == 1,
          str(plan()["orphan_files"]))
    out = run("sync", "--prune")
    check("the orphan album went", not (DEV / "Ghost Artist - (1999) Lost [OPUS]").exists(), out)
    check("and so did its folder", device_files() == library_files(), "")
    check("but .nomedia survived the prune", (DEV / ".nomedia").exists(), "")

    print("\n8. an album renamed locally")
    old, new = albums[0], "Renamed Artist - (2024) Renamed [OPUS]"
    shutil.move(LIB / old, LIB / new)
    out = run("status")
    check("the rename is recognised rather than shown as delete + upload",
          "SAME ALBUM, OLD NAME" in out and "ON DEVICE ONLY" not in out, out)
    check("nothing needs uploading for it", plan()["push_files"] == 0, str(plan()["push_files"]))
    out = run("sync", "--adopt-renames")
    check("the move happened", "moved" in out and old in out, out)
    check("the old folder is gone from the device", not (DEV / old).exists(), out)
    check("the new one is there with everything", (DEV / new).is_dir(), out)
    check("nothing left to push", plan()["push_files"] == 0, str(plan()["push_files"]))

    print("\n9. a track deleted locally")
    gone = next(p.name for p in (LIB / new).iterdir() if p.suffix in (".opus", ".mp3"))
    (LIB / new / gone).unlink()
    out = run("status")
    check("the stray device file is noticed", "orphaned" in out, out)
    out = run("sync", "--prune")
    check("and removed", not (DEV / new / gone).exists(), out)
    check("back in sync afterwards", "OUT OF STEP" not in run("status"), "")

    print("\n10. a dry run reports the wave stage and writes nothing")
    sidecar = LIB / new / "waves.marimo"
    sidecar.unlink()
    out = run("sync", "--waves", "--dry-run")
    check("the dry run says a sidecar would be needed", "would need a sidecar" in out, out)
    check("and it did not make one", not sidecar.exists(), "")
    out = run("sync", "--waves", "--dry-run", env={**ENV, "MARIMO_SYNC_WAVE_SCRIPT": "none"})
    check("with no script configured the dry run says so, and still shows the plan",
          "no wave script configured" in out and ("in sync" in out or "to push" in out), out)

    print("\n11. an album renamed in the library (the phone still has the old name)")
    old_name = albums[3]
    dashed = "Various — (2020) Compilation [OPUS]"       # em dash, like the real library
    shutil.move(LIB / old_name, LIB / dashed)
    out = run("status")
    check("the rename is recognised", "SAME ALBUM, OLD NAME" in out, out)
    check("it isn't offered as an upload", plan()["push_files"] == 0, str(plan()["push_files"]))
    before = {str(p.relative_to(DEV / old_name)): p.stat().st_size
              for p in (DEV / old_name).rglob("*") if p.is_file()}
    out = run("sync", "--adopt-renames")
    check("the files were moved, not uploaded", "-> " + dashed in out.replace("  ", " "), out)
    check("the old folder is gone", not (DEV / old_name).exists(), out)
    check("the new folder holds the same bytes",
          {str(p.relative_to(DEV / dashed)): p.stat().st_size for p in (DEV / dashed).rglob("*")
           if p.is_file()} == before,
          f"{sorted(str(p.relative_to(DEV / dashed)) for p in (DEV / dashed).rglob('*'))} vs {sorted(before)}")
    check("and it's now in sync", "OUT OF STEP" not in run("status"), "")
    check("the moved files are only believed by size, not claimed as verified",
          "UNVERIFIED" in run("status"), "")

    print("\n12. a leftover duplicate: the same album on the device twice, under both names")
    shutil.copytree(DEV / dashed, DEV / old_name)      # exactly what her phone had
    out = run("status")
    check("it is NOT called a pending rename", "SAME ALBUM, OLD NAME" not in out, out)
    check("it is called what it is: device-only", "ON DEVICE ONLY" in out, out)
    check("and nothing is offered as a move", plan()["rename_files"] == 0,
          str(plan()["rename_files"]))
    out = run("sync", "--prune")
    check("prune clears it", not (DEV / old_name).exists(), out)

    print("\n13. album art: content decides, and the source's silence means nothing")
    SRC.mkdir(parents=True, exist_ok=True)

    # (a) an edit under a different name -- the Shitmat shape: library holds the old
    #     art as folder.jpg, the source has the new art as cover.jpg
    changed = albums[1]
    src_changed = SRC / changed.replace("[OPUS]", "[FLAC]")
    src_changed.mkdir(parents=True)
    (LIB / changed / "cover.jpg").rename(LIB / changed / "folder.jpg")
    fixture.cover(src_changed / "cover.jpg", (10, 200, 30))

    # (b) the same picture, different name -- copying on a name mismatch would duplicate
    ident = albums[2]
    src_ident = SRC / ident
    src_ident.mkdir(parents=True)
    (src_ident / "front.jpg").write_bytes((LIB / ident / "cover.jpg").read_bytes())

    # (c) the source has no art at all, while the library does -- leave it alone
    noart = "Renamed Artist - (2024) Renamed [OPUS]"
    src_noart = SRC / noart.replace("[OPUS]", "[FLAC]")
    src_noart.mkdir(parents=True)
    (src_noart / "notes.txt").write_text("no art here\n")

    out = run("covers", "--dry-run")
    check("dry run reports the copy", f"would copy: {changed}/cover.jpg" in out, out)
    check("dry run changes nothing on disk", not (LIB / changed / "cover.jpg").exists(), out)
    check("it notices the identical picture under another name",
          f"{ident}/front.jpg" not in out and "already identical in the library" in out, out)

    out = run("covers", "--keep-others")
    check("it copies the new art in", (LIB / changed / "cover.jpg").exists(), out)
    check("the copied bytes are the source's",
          sha256((LIB / changed / "cover.jpg").read_bytes())
          == sha256((src_changed / "cover.jpg").read_bytes()), "")
    check("--keep-others leaves the superseded folder.jpg alone",
          (LIB / changed / "folder.jpg").exists(), out)
    check("it leaves a cover the source also has, under any name, alone",
          (LIB / ident / "cover.jpg").exists(), "")
    check("it did not duplicate the identical picture",
          sorted(p.name for p in (LIB / ident).iterdir() if p.suffix.lower() == ".jpg")
          == ["cover.jpg"],
          str(sorted(p.name for p in (LIB / ident).iterdir())))
    check("the source with no art changed nothing",
          (LIB / noart / "cover.jpg").exists(), "")

    out = run("covers")
    check("by default it clears the superseded folder.jpg",
          not (LIB / changed / "folder.jpg").exists(), out)
    check("and says so", "superseded" in out, out)

    print("\n14. the local stages never need a phone — which is what makes the wireless flow work")
    bogus = ["--device", "adb:NOSUCHDEVICE01"]
    (LIB / albums[2] / "waves.marimo").unlink()          # give the wave stage something to do

    r = subprocess.run([str(CLI), "--plain", "--source", str(LIB), *bogus, "waves"],
                       capture_output=True, text=True, env=ENV)
    check("marimo-sync waves runs with no device reachable",
          r.returncode == 0 and "stub sidecar" in r.stdout,
          f"{r.returncode} {r.stdout[-160:]} {r.stderr[-160:]}")
    check("and it really made one", (LIB / albums[2] / "waves.marimo").is_file(), "")
    (LIB / albums[2] / "waves.marimo").unlink()

    r = subprocess.run([str(CLI), "--plain", "--source", str(LIB), *bogus,
                        "sync", "--convert", "--waves", "--prepare"],
                       capture_output=True, text=True, env=ENV)
    check("and so does sync --convert --waves --prepare",
          r.returncode == 0 and "the library is ready" in r.stdout,
          f"{r.returncode} {r.stdout[-200:]} {r.stderr[-200:]}")
    check("which leaves the library ready for the phone to fetch",
          (LIB / albums[2] / "waves.marimo").is_file(), "")

    r = subprocess.run([str(CLI), "--plain", "--source", str(LIB), *bogus,
                        "sync", "--convert", "--waves"],
                       capture_output=True, text=True, env=ENV)
    check("but a plain sync still insists on a device, and says which",
          r.returncode == 2 and "isn't connected" in (r.stdout + r.stderr),
          f"{r.returncode} {(r.stdout + r.stderr)[-160:]}")

    print("\n15. a phone that's plugged in but held shut by another program")
    sys.path.insert(0, str(PROJECT))
    from marimosync import transports as T

    # gvfs lists an MTP volume without its activation_root exactly when it can't
    # open the device to read the descriptor — which is what a held phone looks
    # like. That's a state, and it must not be mistaken for "no phone".
    free = ("Volume(0): Titan 2\n"
            "  Type: GProxyVolume (GProxyVolumeMonitorMTP)\n"
            "  activation_root=mtp://Unihertz_Titan_2_TITAN20000045721/\n")
    held = ("Volume(0): Titan 2\n"
            "  Type: GProxyVolume (GProxyVolumeMonitorMTP)\n")
    check("a free phone parses to its activation root",
          T._parse_gio_volumes(free)
          == [("Titan 2", "mtp://Unihertz_Titan_2_TITAN20000045721/")],
          str(T._parse_gio_volumes(free)))
    check("a held phone parses as present, with no root — which is the tell",
          T._parse_gio_volumes(held) == [("Titan 2", "")],
          str(T._parse_gio_volumes(held)))

    real = (T.gvfs_mtp_mounts, T.mtp_volumes, T.mount_mtp, T.AdbDevice)
    try:
        class NoAdb:
            def __init__(self, *a, **k):
                raise T.TransportError("no adb device connected")

        def never(*a, **k):
            raise AssertionError("a held phone must not be mounted")

        T.AdbDevice = NoAdb
        T.gvfs_mtp_mounts = lambda: []
        T.mount_mtp = never

        def trouble(vols, mount=None):
            T.mtp_volumes = lambda: vols
            if mount is not None:
                T.mount_mtp = mount
            try:
                return T.autodetect_device("/sdcard/Music"), ""
            except T.TransportError as e:
                return "", str(e)

        _, msg = trouble([("Titan 2", "")])
        check("a held phone is reported as held, not as missing",
              "can't open it" in msg and "another program" in msg, msg)
        check("and the message names what to close, so it's actionable",
              "Dolphin" in msg and "kiod6" in msg, msg)

        mounted = SCRATCH / "fake-mount"
        (mounted / "sdcard" / "Music" / "Chunes").mkdir(parents=True, exist_ok=True)
        got, msg = trouble([("Titan 2", "mtp://X/")],
                           lambda act, timeout=30: (mounted, ""))
        check("a free phone is mounted for you, with no adb and no settings",
              got == f"dir:{mounted}", got or msg)

        shutil.rmtree(mounted / "sdcard")
        _, msg = trouble([("Titan 2", "mtp://X/")],
                         lambda act, timeout=30: (mounted, ""))
        check("a mount whose music folder is missing explains the locked-phone case",
              "isn't there" in msg and "unlock" in msg, msg)
    finally:
        T.gvfs_mtp_mounts, T.mtp_volumes, T.mount_mtp, T.AdbDevice = real

    print("\n16. the shader's quiet case is not an error")
    # This crashed on an undefined name *when there was nothing to do* -- the healthy
    # case -- and the server faithfully reported it as "waveforms exited 1" while the
    # library was complete. Its own album on purpose: the fixture library's are moved
    # and emptied by the sections above, so borrowing one makes the premise a lie.
    shader = str(PROJECT / "scripts" / "make-waveforms.py")
    quiet = SCRATCH / "quiet-album"
    quiet.mkdir(parents=True, exist_ok=True)
    fixture.tone(quiet / "01. quiet.opus", 700)
    fixture.write_sidecar(quiet, ["01. quiet.opus"])
    r = subprocess.run([shader, "--per-album", str(quiet)], capture_output=True, text=True)
    check("shading a fully shaded album exits 0 and says so",
          r.returncode == 0 and "already has a sidecar" in r.stdout,
          f"exit={r.returncode} out={r.stdout[-80:]!r} err={r.stderr[-80:]!r}")
    r = subprocess.run([shader, "--per-album", str(SCRATCH / "nothing-here")],
                       capture_output=True, text=True)
    check("and a root with no audio in it exits 0 too", r.returncode == 0,
          f"{r.returncode} {r.stderr[-90:]}")

    print()
    if FAILED:
        print(f"{len(FAILED)}/{N} checks FAILED: " + ", ".join(FAILED))
        return 1
    print(f"all {N} checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
