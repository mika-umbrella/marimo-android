#!/usr/bin/env python3
"""Build a synthetic music library and device, for the test suites.

Nothing here reads the real library. Audio is generated with ffmpeg, covers with
PIL, and waveform sidecars are written by hand in the documented format. So the
suites are portable, fast, and structurally incapable of touching real data —
which matters, because one of these files once copied 27 GB of it.

The albums are deliberately awkward: one has decomposed (NFD) Japanese filenames
on disk against an NFC sidecar, one has its tracks in `[Disc N]` subfolders, one
has spaces and brackets in the names.
"""

from __future__ import annotations

import shutil
import struct
import subprocess
import unicodedata
from pathlib import Path

AUDIO = {".opus", ".mp3", ".m4a", ".flac"}

# name, tracks, filename flavour
ALBUMS: list[tuple[str, int, str]] = [
    ("Synthetic Band - (2021) First Light [OPUS]", 3, "ascii"),
    ("合成バンド - (2022) 光のうた [OPUS]", 2, "nfd"),
    ("Another Artist - (2023) Slow Bloom [MP3]", 4, "spaces"),
]

DISC_ALBUM = "Various - (2020) Compilation [OPUS]"
DISC_TRACKS = {"[Disc 1]": 2, "[Disc 2]": 2}


CODECS = {
    ".opus": ["-c:a", "libopus", "-b:a", "32k"],
    ".mp3": ["-c:a", "libmp3lame", "-b:a", "64k"],
    ".m4a": ["-c:a", "aac", "-b:a", "64k"],
    ".flac": ["-c:a", "flac", "-compression_level", "8"],
}


def tone(path: Path, freq: int, seconds: float = 0.3) -> None:
    """A short, small, *distinct* audio file (distinct so hashes differ)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    codec = CODECS.get(path.suffix.lower(), CODECS[".opus"])
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi",
         "-i", f"sine=frequency={freq}:duration={seconds}",
         *codec, str(path)],
        check=True,
    )


def cover(path: Path, colour=(88, 138, 88)) -> None:
    from PIL import Image
    Image.new("RGB", (48, 48), colour).save(path, quality=75)


def write_sidecar(album_dir: Path, names: list[str]) -> Path:
    """A waves.marimo in the real format: magic, count, then name + 96 peaks each."""
    out = bytearray(b"MWAVS001")
    out += struct.pack("<I", len(names))
    for name in names:
        raw = unicodedata.normalize("NFC", name).encode("utf-8")
        out += struct.pack("<H", len(raw)) + raw + bytes([60] * 96)
    path = album_dir / "waves.marimo"
    path.write_bytes(bytes(out))
    return path


def _name(flavour: str, index: int) -> str:
    if flavour == "nfd":
        # decomposed on disk, exactly like the Korean/Japanese titles in the real
        # library; the sidecar stays NFC, which is the mismatch the tests want
        return unicodedata.normalize("NFD", f"0{index}. ひかりのうた.opus")
    if flavour == "spaces":
        return f"0{index} - A Song With Spaces.mp3"
    return f"0{index}. First Light.opus"


def build_library(root: Path, albums: list[tuple[str, int, str]] | None = None) -> list[str]:
    """Create the albums under `root`. Returns their folder names."""
    if root.exists():
        shutil.rmtree(root)
    root.mkdir(parents=True)
    names: list[str] = []
    freq = 300
    for name, tracks, flavour in (albums if albums is not None else ALBUMS):
        album = root / name
        album.mkdir(parents=True)
        filenames = []
        for i in range(1, tracks + 1):
            filename = _name(flavour, i)
            tone(album / filename, freq)
            freq += 40
            filenames.append(filename)
        cover(album / "cover.jpg")
        write_sidecar(album, filenames)
        names.append(name)

    discs = root / DISC_ALBUM
    disc_tracks: list[str] = []
    for disc, count in DISC_TRACKS.items():
        (discs / disc).mkdir(parents=True)
        for i in range(1, count + 1):
            filename = f"0{i}. Disc Track {disc[-2]}{i}.opus"
            tone(discs / disc / filename, freq)
            freq += 40
            disc_tracks.append(filename)
    cover(discs / "cover.jpg")
    write_sidecar(discs, disc_tracks)
    names.append(DISC_ALBUM)
    return names


def copy_album(src: Path, dst: Path, skip: set[str] | None = None) -> None:
    """Copy an album folder into a device tree, optionally omitting files."""
    skip = skip or set()
    shutil.copytree(src, dst)
    for name in skip:
        for hit in dst.rglob(name):
            hit.unlink()


def ghost_album(device_root: Path, name: str = "Ghost Artist - (1999) Lost [OPUS]") -> Path:
    """Something on the device that the library doesn't have."""
    d = device_root / name
    d.mkdir(parents=True, exist_ok=True)
    (d / "01. gone.opus").write_bytes(b"not real audio")
    return d


def standard_pair(scratch: Path) -> tuple[Path, Path, list[str]]:
    """A library and a half-populated device: the shape both CLI suites want."""
    lib = scratch / "lib"
    dev = scratch / "device"
    names = build_library(lib)
    dev.mkdir(parents=True, exist_ok=True)
    copy_album(lib / names[0], dev / names[0])                       # already in sync
    copy_album(lib / names[1], dev / names[1], skip={"waves.marimo"})  # missing its sidecar
    ghost_album(dev)
    return lib, dev, names
