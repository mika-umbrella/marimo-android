"""The library stages — convert, waves, covers — as functions, for anyone to drive.

These were three CLI commands you ran in order while watching the terminal. The work
is the same whoever asks for it, and increasingly the asker isn't a person at all:
when a phone reports what it has over the network, the albums it's missing are exactly
what needs converting and shading. So they live here instead of in cli.py, take a
Config and a Library, and report progress through a callback rather than printing at
each other.

`ensure()` is the whole of "make these albums ready to serve". It is deliberately
additive: it converts and generates, and it copies cover art that's missing, but it
never *removes* anything. Cleaning up superseded art is a decision for a person
looking at it (`marimo-sync covers`), not a side effect of a phone saying hello.
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

from .config import Config
from .core import Library
from .covers import sync_covers


def _say(msg: str, err: bool = False) -> None:
    """Default reporter: stdout, or stderr when it's a problem."""
    print(msg, file=sys.stderr if err else sys.stdout)


def source_name(album: str) -> str:
    """The uncompressed source folder that becomes this library album."""
    return album.replace("[OPUS]", "[FLAC]")


def library_name(name: str) -> str:
    """What a source folder is called in the library -- mirrors the converter's rule.

    "[FLAC]" becomes "[OPUS]"; a folder with *no* format tag at all ("... WEB FLAC")
    gets "[OPUS]" appended, so it lands as the album already in the library rather than
    a second copy beside it. Anything already carrying a tag -- including "[M4A]", which
    the converter copies verbatim -- is left alone. This is a shape test on purpose:
    enumerating the tags I expected turned `... [M4A]` into a bogus pending album.
    """
    if "[FLAC]" in name:
        return name.replace("[FLAC]", "[OPUS]")
    if "[" in name:
        return name
    return name + " [OPUS]"


def albums_needing_waves(lib: Library, names: list[str]) -> list[str]:
    """Which of these albums have no usable sidecar (or none at all)."""
    need = []
    for name in names:
        if not lib.audio_of(name):
            continue
        gaps = lib.sidecar_gaps(name)
        if gaps is None or gaps:
            need.append(name)
    return need


def convert_targets(cfg: Config, lib: Library) -> list[str]:
    """Albums in the uncompressed source with no counterpart in the library."""
    src = cfg.flac_source
    if src is None or not src.is_dir():
        return []
    have = set(lib.albums)
    out = []
    for p in sorted(src.iterdir()):
        if not p.is_dir() or p.name.startswith("."):
            continue
        dest = library_name(p.name)
        if dest not in have:
            out.append(p.name)
    return out


def run_convert(cfg: Config, names: list[str], dry: bool = False,
                say=_say) -> int:
    """FLAC (or whatever the source holds) -> the compressed library. Source names."""
    script = cfg.convert_script
    if script is None:
        say(f"convert: not done -- {cfg.script_problem('convert_script')}", err=True)
        say("  (set convert_script in your config, or --convert-script PATH; the "
            "bundled scripts/convert_music.py works)", err=True)
        return 1
    if cfg.flac_source is None:
        say("convert: not done -- no flac_source configured (where the originals live)",
            err=True)
        return 1
    if not names:
        say("convert: nothing to do")
        return 0
    verb = "would convert" if dry else "converting"
    say(f"convert: {verb} {len(names)} album(s) missing from the library")
    for name in names:
        say(f"  - {name}")

    help_text = _script_help(script)
    flags: list[str] = []
    if "--jobs" in help_text:
        # One ffmpeg per file, several at once: the wall for a rebuild is the share, so
        # what matters is having many reads in flight. Serial made a full library an
        # afternoon; this makes it the network's own floor.
        flags += ["--jobs", str(min(12, os.cpu_count() or 4))]
    if dry:
        flags.append("--dry")
    base = [str(script), "--src", str(cfg.flac_source), "--dst", str(cfg.source), *flags]

    if len(names) == 1 or "--one" not in help_text:
        rc = 0
        for name in names:
            r = subprocess.run(base + ["--one", name])
            rc = rc or r.returncode
        return rc
    # More than a couple of albums: one invocation converts everything missing in a
    # single pass -- the same set `convert_targets` just computed, but with one listing
    # of the source tree instead of one per album (629 of them for a full rebuild).
    return subprocess.run(base).returncode


def _script_help(script: Path) -> str:
    """A script's --help, cached: used to ask what flags it actually supports."""
    key = str(script)
    if key not in _HELP_CACHE:
        try:
            r = subprocess.run([key, "--help"], capture_output=True, text=True, timeout=30)
            _HELP_CACHE[key] = (r.stdout or "") + (r.stderr or "")
        except Exception:
            _HELP_CACHE[key] = ""
    return _HELP_CACHE[key]


_HELP_CACHE: dict[str, str] = {}


def run_waves(cfg: Config, source: Path, names: list[str], required: bool = False,
              say=_say) -> int:
    """Generate waveform sidecars for these albums, in the library at `source`."""
    script = cfg.wave_script
    if script is None:
        say(f"waveforms: not done -- {cfg.script_problem('wave_script')}", err=True)
        say("  (set wave_script in your config, or --waves-script PATH; the bundled "
            "scripts/make-waveforms.py works)", err=True)
        return 1 if required else 0
    if not names:
        say("waveforms: nothing to do")
        return 0
    say(f"waveforms: {len(names)} album(s) need a sidecar")
    flags = []
    if "--per-album" in _script_help(script):
        # Each argument is ONE album, subfolders included, so the sidecar lands at the
        # album root where the library and the phone look for it. Probed, not assumed:
        # wave_script may point at a compatible tool of someone else's.
        flags.append("--per-album")
    cmd = [str(script), *flags, *[str(source / n) for n in names]]
    return subprocess.run(cmd, cwd=str(source)).returncode


def ensure(cfg: Config, albums: list[str], *, reload, waves: bool = True,
           covers: bool = True, on_progress=None) -> dict:
    """Make `albums` ready to serve, and say what was done.

    `albums` are library album names ("ARTIST - (YEAR) ALBUM [OPUS]"). `reload` is a
    callable returning a *fresh* Library: converting changes what's on disk, so the
    scan has to be redone between stages or the next stage reasons about a library
    that no longer exists.

    `on_progress(stage, album, done, total)` is called as each stage starts and as
    each album finishes, so a caller can tell a phone (or a window) what's happening.
    """
    report: dict = {"converted": [], "waves": [], "covers": 0, "errors": []}

    def tell(stage: str, album: str = "", done: int = 0, total: int = 0) -> None:
        if on_progress:
            on_progress(stage, album, done, total)

    def log(msg: str, err: bool = False) -> None:
        # Deliberately *not* reported as progress. "log" is not a stage, and the phone
        # reads /api/health to decide whether to wait -- a log line must not look like
        # work in progress, or overwrite the stage that actually is.
        if err:
            report["errors"].append(msg.strip())
        else:
            report.setdefault("notes", []).append(msg.strip())

    src = cfg.flac_source
    lib = reload()

    # 1. albums we don't have at all -- convert them from the uncompressed source
    missing = [a for a in albums if a not in lib.albums]

    def find_source(album: str) -> str | None:
        """The source folder for a library album, whatever shape its name is in.

        A library name doesn't always invert back to its source: an untagged source
        ("... WEB FLAC") becomes "... WEB FLAC [OPUS]", and stripping the tag gives
        "... [FLAC]", which doesn't exist. Try the shapes we know rather than trusting
        one substitution.
        """
        if src is None or not src.is_dir():
            return None
        for candidate in (source_name(album), album, album.replace(" [OPUS]", "")):
            if candidate and (src / candidate).is_dir():
                return candidate
        return None

    pairs = [(a, find_source(a)) for a in missing]
    convertible = [(a, s) for a, s in pairs if s]
    if convertible:
        tell("converting", convertible[0][0], 0, len(convertible))
        rc = run_convert(cfg, [s for _, s in convertible], say=log)
        lib = reload()
        # Report what actually appeared, not what we asked for. An album whose tracks
        # live in a disc subfolder used to be "converted" on every prepare without ever
        # existing -- a lie the phone then acted on, and a loop with no end.
        report["converted"] = [a for a, _ in convertible if a in lib.albums]
        absent = [a for a, _ in convertible if a not in lib.albums]
        if absent:
            report["not_converted"] = absent
            log(f"convert: {len(absent)} album(s) produced nothing — no audio in the "
                f"source folder, or a shape the converter can't read")
        if rc:
            report["errors"].append(f"convert exited {rc}")
    if missing:
        have_src = {a for a, _ in convertible}
        report["unavailable"] = [a for a in missing if a not in have_src]

    # 2. sidecars for the albums that will travel
    here = [a for a in albums if a in lib.albums]
    if waves and here:
        need = albums_needing_waves(lib, here)
        if need:
            tell("waveforms", need[0], 0, len(need))
            rc = run_waves(cfg, Path(lib.root), need, say=log)
            # Ask the sidecars, not the script: the same lie the convert step had. A
            # disc album shaded per-subfolder looked done here and had nothing the
            # library or the phone could read.
            lib = reload()
            still = albums_needing_waves(lib, need)
            report["waves"] = [a for a in need if a not in still]
            if still:
                report["unshaded"] = still
                log(f"waveforms: {len(still)} album(s) still have no usable sidecar")
            if rc:
                report["errors"].append(f"waveforms exited {rc}")
        else:
            tell("waveforms", "", len(here), len(here))

    # 3. art the library is missing. Additive: tidy stays off, so nothing is removed
    #    as a side effect of a sync -- deleting superseded art is a person's call.
    if covers and here:
        tell("covers", "", 0, len(here))
        try:
            rep = sync_covers(cfg, lib, dry_run=False, tidy=False)
            report["covers"] = len(rep.get("copied", []))
            tell("covers", "", len(here), len(here))
        except Exception as e:                     # art must never break a sync
            report["errors"].append(f"covers: {e}")
    return report


def main(argv: list[str] | None = None) -> int:
    """Handy for testing a stage without the CLI's argument parsing."""
    argv = list(sys.argv[1:] if argv is None else argv)
    print(__doc__.splitlines()[0])
    print("  use `marimo-sync convert|waves|covers` — this module is the engine")
    return 0
