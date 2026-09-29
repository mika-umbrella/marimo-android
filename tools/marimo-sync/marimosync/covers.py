"""Bring album art across from the uncompressed source into the library.

Why this is needed at all: `convert_music.py` decides an album is "complete" by
**name** -- an existing `.opus`, and every image file present *by name*. So a cover
edited in the source doesn't replace anything; it either gets copied in beside the
old one, or is ignored because some other image file already satisfies the check.

Two things this does differently, both learned from the real library:

  * it compares **content**, not names. The source's `cover.jpg` and the library's
    `folder.jpg` are frequently the same picture (`Portishead - Dummy`), and copying
    on a name mismatch would leave a pointless duplicate.
  * it never treats **absent** art in the source as a reason to remove anything.
    Eight albums in this library have covers only in the library -- the source has
    none at all -- so the source is not authoritative about absence. It is
    authoritative about what it *has*.

So the copy is additive and safe. `--tidy` (off by default) additionally removes a
cover-named file whose content the source doesn't have at all, which is how a
superseded `folder.jpg` beside a fresh `cover.jpg` gets cleaned up. It only ever
considers the conventional cover families, never booklets or back covers.

`marimo-sync sync` afterwards pushes whatever changed, and the superseded file (if
cleaned) becomes an orphan for `--prune` to clear off the device.
"""

from __future__ import annotations

import re
import shutil
from pathlib import Path

from .config import Config
from .core import Library, sha256_file

IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}

# the same conventional families CoverName.java ranks, so this agrees with what
# the player will actually display (cover beats folder beats front beats album)
COVER_STEMS = ("cover", "folder", "front", "album")


def _images(folder: Path) -> dict[str, str]:
    """{filename: sha256} for the images in a folder."""
    out: dict[str, str] = {}
    if not folder.is_dir():
        return out
    for p in sorted(folder.iterdir()):
        if p.is_file() and p.suffix.lower() in IMAGE_EXT:
            try:
                out[p.name] = sha256_file(p)
            except OSError:
                continue
    return out


def _key(s: str) -> str:
    """Folder-name / cover-name fold, matching CoverName.key() closely enough."""
    t = re.sub(r"\([^)]*\)", " ", s)
    t = re.sub(r"\[[^\]]*\]", " ", t)
    t = re.sub(r"[\u2010-\u2015\u2212]", "-", t)
    t = re.sub(r"\s*-\s*", " - ", t)
    return re.sub(r"\s+", " ", t).strip().lower()


def is_cover_name(name: str, album: str) -> bool:
    stem = name.rsplit(".", 1)[0].lower()
    if stem in COVER_STEMS or stem.startswith("cover"):
        return True
    return _key(stem) == _key(album) != ""


def source_folder_for(cfg: Config, album: str) -> Path | None:
    """Where this library album's originals live, if they're there to be found."""
    src = cfg.flac_source
    if src is None or not src.is_dir():
        return None
    for candidate in (album.replace("[OPUS]", "[FLAC]"), album):
        d = src / candidate
        if d.is_dir():
            return d
    return None


def sync_covers(cfg: Config, lib: Library | None = None, *, dry_run: bool = False,
                tidy: bool = False) -> dict:
    """Copy art the library is missing. Returns a report; changes nothing if dry."""
    lib = lib or Library(cfg.source)
    report: dict = {"copied": [], "removed": [], "unmatched": [], "identical": [],
                    "source_missing_art": 0}
    library_root = Path(lib.root)

    for album in sorted(lib.albums):
        src_album = source_folder_for(cfg, album)
        if src_album is None:
            report["unmatched"].append(album)
            continue
        src_art = _images(src_album)
        if not src_art:
            # the source has no art file at all -- not a reason to touch anything
            report["source_missing_art"] += 1
            continue
        lib_art = _images(library_root / album)
        have = set(lib_art.values())

        for name, digest in sorted(src_art.items()):
            if digest in have:
                # the same picture is already here, whatever it's called
                report["identical"].append(f"{album}/{name}")
                continue
            report["copied"].append(f"{album}/{name}")
            if not dry_run:
                shutil.copy2(src_album / name, library_root / album / name)
            have.add(digest)

        if tidy:
            # If the source has art at all, then a library cover whose content isn't
            # in the source is a superseded copy -- that's the whole rule. Albums
            # whose source has no art were skipped above, so nothing is removed on
            # the strength of the source's silence.
            src_digests = set(src_art.values())
            for name, digest in sorted(lib_art.items()):
                if digest in src_digests or not is_cover_name(name, album):
                    continue
                report["removed"].append(f"{album}/{name}")
                if not dry_run:
                    try:
                        (library_root / album / name).unlink()
                    except OSError:
                        pass
    return report
