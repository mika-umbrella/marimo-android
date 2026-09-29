"""The library, the book-keeping, and the diff between them.

Three ideas and nothing else:

  Library   what this computer has, plus a lazy content-hash cache
  Manifest  what we last *put* on the device, and how sure we are of each file
  Plan      the diff: per album, what to push, what's stale, what's orphaned

The manifest is the trick that makes this cheap. Walking 6000 files on the phone
is one shell command and a few seconds; hashing them is minutes of phone CPU. So
we record, per file, the size, the source mtime and (when we've paid for it) the
sha256 -- and the *provenance* of that knowledge: "pushed" (we sent it, we know
its bytes), "hash" (we read it back and confirmed), "size" (it was already there
and agreed on length, nobody has looked inside), "reported" (a phone that pulls
told us what it holds, and we can't check), and "mismatch" (we read it back and
it differed). `verify` upgrades provenance for real; `plan` never lies about
which files it merely believes.
"""

from __future__ import annotations

import hashlib
import json
import os
import struct
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Sequence

from .transports import Device

AUDIO_EXT = {".opus", ".mp3", ".m4a", ".aac", ".ogg", ".flac", ".wav"}
SIDECAR = "waves.marimo"
MANIFEST_VERSION = 1

# Kept out of the music tree on purpose: marimo-android's SAF grant is rooted at
# the music folder and it walks that tree during a scan. Our book-keeping has no
# business looking like an album to it.
MANIFEST_REL = "../.marimo-sync/manifest.json"


# ---------------------------------------------------------------------------
# local library
# ---------------------------------------------------------------------------

@dataclass
class FileInfo:
    rel: str
    size: int
    mtime_ns: int


class HashCache:
    """Content hashes for local files, keyed on (size, mtime) so it self-invalidates."""

    def __init__(self, path: Path):
        self.path = path
        self._data: dict[str, list] = {}
        try:
            raw = json.loads(path.read_text())
            if isinstance(raw, dict):
                self._data = raw
        except (OSError, ValueError):
            pass
        self._dirty = False

    def get(self, info: FileInfo) -> str | None:
        ent = self._data.get(info.rel)
        if ent and ent[0] == info.size and ent[1] == info.mtime_ns:
            return ent[2]
        return None

    def put(self, info: FileInfo, sha: str) -> None:
        self._data[info.rel] = [info.size, info.mtime_ns, sha]
        self._dirty = True

    def forget_prefix(self, prefix: str) -> int:
        gone = [k for k in self._data if k == prefix or k.startswith(prefix + "/")]
        for k in gone:
            del self._data[k]
        self._dirty = bool(gone)
        return len(gone)

    def save(self) -> None:
        if not self._dirty:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self._data))
        os.replace(tmp, self.path)
        self._dirty = False


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


class Library:
    """Every file under the local library root, grouped into albums."""

    def __init__(self, root: Path, cache: HashCache | None = None):
        self.root = Path(root).expanduser().resolve()
        self.files: dict[str, FileInfo] = {}
        self.albums: dict[str, list[str]] = {}
        self.loose: list[str] = []
        self.cache = cache
        self._scan()

    def _scan(self) -> None:
        for dirpath, dirnames, filenames in os.walk(self.root):
            dirnames[:] = [d for d in dirnames if not d.startswith(".")]
            for name in filenames:
                if name.startswith("."):
                    continue
                p = Path(dirpath) / name
                try:
                    st = p.stat()
                except OSError:
                    continue
                rel = str(p.relative_to(self.root))
                self.files[rel] = FileInfo(rel, st.st_size, st.st_mtime_ns)
                album = rel.split("/", 1)
                if len(album) == 2:
                    self.albums.setdefault(album[0], []).append(rel)
                else:
                    self.loose.append(rel)
        for rels in self.albums.values():
            rels.sort()
        self.loose.sort()

    # -- queries ---------------------------------------------------------
    def album_files(self, album: str) -> list[str]:
        return self.albums.get(album, [])

    def audio_of(self, album: str) -> list[str]:
        return [r for r in self.album_files(album) if Path(r).suffix.lower() in AUDIO_EXT]

    def sha(self, rel: str) -> str:
        """Content hash of a local file, using the cache when it's still valid."""
        info = self.files[rel]
        if self.cache is not None:
            hit = self.cache.get(info)
            if hit:
                return hit
        sha = sha256_file(self.root / rel)
        if self.cache is not None:
            self.cache.put(info, sha)
        return sha

    # -- sidecars --------------------------------------------------------
    def sidecar_names(self, album: str) -> set[str] | None:
        """Track filenames a sidecar claims to cover, or None if unreadable."""
        path = self.root / album / SIDECAR
        try:
            with open(path, "rb") as fh:
                if fh.read(8) != b"MWAVS001":
                    return None
                (count,) = struct.unpack("<I", fh.read(4))
                names = set()
                for _ in range(count):
                    (n,) = struct.unpack("<H", fh.read(2))
                    names.add(fh.read(n).decode("utf-8", errors="replace"))
                    if len(fh.read(96)) != 96:      # 96 peak bytes follow every name
                        return None
                return names
        except (OSError, struct.error):
            return None

    def sidecar_gaps(self, album: str) -> list[str] | None:
        """Audio files the sidecar doesn't cover. None if there's no usable sidecar.

        Both sides get NFC-normalised: the sidecar generator writes NFC, but the
        filenames on disk can be NFD (anything that came through a Mac, and the
        Korean/Japanese titles largely are), so a raw string comparison invents
        gaps that aren't there. The android app normalises the same way, which is
        why the sidecars work at all.
        """
        have = self.sidecar_names(album)
        if have is None:
            return None
        have = {unicodedata.normalize("NFC", n) for n in have}
        want = {unicodedata.normalize("NFC", Path(r).name) for r in self.audio_of(album)}
        return sorted(want - have)


# ---------------------------------------------------------------------------
# device manifest
# ---------------------------------------------------------------------------

@dataclass
class Manifest:
    rel: str = MANIFEST_REL
    albums: dict[str, dict] = field(default_factory=dict)
    updated: str | None = None
    device_root: str | None = None

    @classmethod
    def load(cls, device: Device, rel: str = MANIFEST_REL) -> "Manifest":
        raw = device.read_text(rel)
        if not raw:
            return cls(rel=rel)
        try:
            data = json.loads(raw)
        except ValueError:
            return cls(rel=rel)
        if data.get("version") != MANIFEST_VERSION:
            return cls(rel=rel)
        return cls(rel=rel, albums=data.get("albums") or {},
                   updated=data.get("updated"), device_root=data.get("root"))

    def save(self, device: Device) -> None:
        import datetime

        self.updated = datetime.datetime.now().astimezone().isoformat(timespec="seconds")
        self.device_root = device.root
        payload = {
            "version": MANIFEST_VERSION,
            "root": device.root,
            "updated": self.updated,
            "albums": self.albums,
        }
        device.write_text(self.rel, json.dumps(payload, indent=1, sort_keys=True))

    def album(self, name: str) -> dict:
        return self.albums.setdefault(name, {"files": {}})

    def record(self, album: str, rel_inside: str, info: FileInfo, sha: str | None, match: str,
               pushed_at: str | None = None) -> None:
        import datetime

        entry = self.album(album)
        entry.setdefault("files", {})[rel_inside] = {
            "size": info.size,
            "mtime_ns": info.mtime_ns,
            "sha256": sha,
            "match": match,
        }
        entry["pushed_at"] = pushed_at or datetime.datetime.now().astimezone().isoformat(timespec="seconds")

    def forget(self, album: str) -> None:
        self.albums.pop(album, None)

    def refresh(self, lib: "Library", name: str, *,
                pushed: set[str] | frozenset[str] = frozenset(),
                device_hashes: dict[str, str] | None = None) -> None:
        """Bring the manifest's view of one album in line with the local library.

        `pushed` are root-relative paths whose bytes we just sent -- certain.
        `device_hashes` are root-relative paths we read back from the device;
        they become "hash" where they match the library and "mismatch" where they
        don't, so the next plain `sync` knows to replace them without re-reading
        anything. Everything else keeps whatever provenance it had, with its
        mtime refreshed, so a touched-but-identical file doesn't cost a re-hash
        on every single run.
        """
        device_hashes = device_hashes or {}
        files = lib.album_files(name)
        if not files:
            self.albums.pop(name, None)
            return
        entry = self.album(name)
        known = entry.setdefault("files", {})

        for rel in files:
            info = lib.files[rel]
            inside = _rel_inside(rel)
            old = known.get(inside)
            if rel in pushed:
                self.record(name, inside, info, lib.sha(rel), "pushed")
            elif rel in device_hashes:
                same = device_hashes[rel] == lib.sha(rel)
                self.record(name, inside, info, lib.sha(rel), "hash" if same else "mismatch")
            elif old is None:
                self.record(name, inside, info, lib.sha(rel), "size")
            elif old.get("size") == info.size and old.get("sha256") == lib.sha(rel):
                old["mtime_ns"] = info.mtime_ns          # content unmoved; just touched
            else:
                # the local file changed and we haven't sent it -- the device copy
                # is stale now, so claim no more than a length match
                self.record(name, inside, info, lib.sha(rel), "size")

        live = {_rel_inside(r) for r in files}
        for inside in [k for k in known if k not in live]:
            del known[inside]
        if not known:
            self.albums.pop(name, None)


# ---------------------------------------------------------------------------
# the diff
# ---------------------------------------------------------------------------

STATUS_ORDER = {"update": 0, "new": 1, "orphan": 2, "ok": 3}


@dataclass
class AlbumPlan:
    name: str
    status: str                       # new | update | ok | orphan
    push: list[str] = field(default_factory=list)
    overwrite: list[str] = field(default_factory=list)
    orphans: list[str] = field(default_factory=list)
    unverified: int = 0               # device files we only believe by size
    missing_sidecar: bool = False
    stale_sidecar: list[str] = field(default_factory=list)
    on_device: int = 0
    push_bytes: int = 0
    notes: list[str] = field(default_factory=list)
    verified: list[str] = field(default_factory=list)   # read back, matched
    mismatch: list[str] = field(default_factory=list)   # read back, differed
    unreadable: list[str] = field(default_factory=list)  # couldn't be read; unknown, not wrong
    # set when this device-only album is really the same album under an old name:
    # the files can be moved on the device instead of uploaded again
    move_to: str | None = None
    move_files: list[str] = field(default_factory=list)
    move_bytes: int = 0

    def short(self) -> str:
        bits = []
        if self.push:
            bits.append(f"{len(self.push)} to push")
        if self.orphans:
            bits.append(f"{len(self.orphans)} orphaned")
        if self.missing_sidecar:
            bits.append("no sidecar")
        elif self.stale_sidecar:
            bits.append(f"sidecar missing {len(self.stale_sidecar)} track(s)")
        if self.unverified and not self.push:
            bits.append(f"{self.unverified} unverified")
        bits.extend(self.notes)
        return ", ".join(bits) or "in sync"


@dataclass
class Plan:
    albums: dict[str, AlbumPlan] = field(default_factory=dict)
    loose_orphans: list[str] = field(default_factory=list)
    device_files: int = 0
    local_files: int = 0
    device_hashes: dict[str, str] = field(default_factory=dict)   # rel -> sha256 we read back

    def of(self, status: str) -> list[AlbumPlan]:
        return [a for a in self.albums.values() if a.status == status]

    @property
    def push_total(self) -> int:
        return sum(len(a.push) for a in self.albums.values())

    @property
    def push_bytes(self) -> int:
        return sum(a.push_bytes for a in self.albums.values())

    @property
    def orphans(self) -> list[tuple[str, list[str]]]:
        return [(a.name, a.orphans) for a in self.albums.values() if a.orphans]

    @property
    def orphan_files(self) -> int:
        """Things that would actually be *deleted*.

        An album on its way to a new name is not an orphan -- its files are being
        moved, and counting them here would overstate what `--prune` removes.
        """
        return (sum(len(a.orphans) for a in self.albums.values() if a.status != "rename")
                + len(self.loose_orphans))

    @property
    def unreadable_files(self) -> int:
        return sum(len(a.unreadable) for a in self.albums.values())

    @property
    def renames(self) -> list[AlbumPlan]:
        return [a for a in self.albums.values() if a.status == "rename"]

    @property
    def rename_files(self) -> int:
        return sum(len(a.move_files) for a in self.renames)

    @property
    def rename_bytes(self) -> int:
        return sum(a.move_bytes for a in self.renames)


def _rel_inside(rel: str) -> str:
    """Path inside its album folder ("Album/x.opus" -> "x.opus")."""
    parts = rel.split("/", 1)
    return parts[1] if len(parts) == 2 else parts[0]


def _hidden(rel: str) -> bool:
    """A path with any dot-prefixed component.

    The device's own book-keeping lives in these, and they are not ours to tidy:
    `.nomedia` at the music root tells Android's media scanner to skip the folder.
    Deleting it because "the library doesn't have it" would be a bug with teeth.
    """
    return any(part.startswith(".") for part in rel.split("/"))


def build_plan(lib: Library, device: Device, manifest: Manifest, *,
               verify: bool = False, wave_check: bool = True,
               limit: int | None = None) -> Plan:
    raw = device.walk()
    dev = {rel: size for rel, size in raw.items() if not _hidden(rel)}
    plan = Plan(device_files=len(dev), local_files=len(lib.files))
    dev_albums: dict[str, list[str]] = {}
    for rel in dev:
        album = Device.album_of(rel)
        if album:
            dev_albums.setdefault(album, []).append(rel)
        else:
            plan.loose_orphans.append(rel)

    names = set(lib.albums) | set(dev_albums)
    if limit:
        # keep the always-interesting albums, then trim
        names = set(sorted(names, key=lambda n: (n not in dev_albums or n not in lib.albums, n))[:limit])

    # --- pass 1: sizes and book-keeping, no device hashing
    want_hash: list[str] = []          # rels to read back from the device this run
    for name in sorted(names):
        ap = AlbumPlan(name=name, status="ok")
        local = lib.album_files(name)
        on_dev = dev_albums.get(name, [])
        ap.on_device = len(on_dev)
        man = manifest.albums.get(name, {})
        man_files = man.get("files", {})

        for rel in local:
            info = lib.files[rel]
            inside = _rel_inside(rel)
            dsize = dev.get(rel)
            if dsize is None:
                ap.push.append(rel)
                continue
            if dsize != info.size:
                ap.push.append(rel)
                ap.overwrite.append(rel)
                continue
            # The lengths agree -- now decide what that's worth. Everything
            # below is book-keeping the manifest already paid for; the device is
            # only read when we have no provenance at all (or when asked).
            if verify:
                # `verify` promises to read every file back, so it doesn't get to
                # skip the ones we think we're sure about -- a file can be
                # corrupted without changing length, and that's the only case
                # verify exists to catch.
                want_hash.append(rel)
                continue
            mf = man_files.get(inside)
            if mf and mf.get("match") == "mismatch":
                ap.push.append(rel)                     # read back wrong once; replace it
                ap.overwrite.append(rel)
                continue
            if mf and mf.get("size") == info.size and mf.get("sha256"):
                confirmed = mf.get("match") in ("pushed", "hash", "reported")
                if confirmed and mf.get("mtime_ns") == info.mtime_ns:
                    continue                            # nothing moved on either side
                if lib.sha(rel) != mf["sha256"]:
                    ap.push.append(rel)                 # the library itself changed
                    ap.overwrite.append(rel)
                elif confirmed:
                    continue                            # touched, identical, already confirmed
                else:
                    ap.unverified += 1
            else:
                ap.unverified += 1

        ap.orphans = sorted(set(on_dev) - set(local))

        if wave_check and local:
            gaps = lib.sidecar_gaps(name)
            if gaps is None:
                ap.missing_sidecar = True
            elif gaps:
                ap.stale_sidecar = gaps

        if not on_dev and local:
            ap.status = "new"
        elif on_dev and not local:
            ap.status = "orphan"
        elif ap.push:
            ap.status = "update"
        else:
            ap.status = "ok"
        plan.albums[name] = ap

    # --- pass 2: one device hash sweep for the files we actually need
    if want_hash:
        unreadable: list[str] = []
        got = device.hash_walk(want_hash, failures=unreadable)
        plan.device_hashes.update({k: v for k, v in got.items() if k in set(want_hash)})
        for rel in want_hash:
            ap = plan.albums[Device.album_of(rel)]
            if rel not in got:
                # We could not look at it. That is never "it differs" -- on this
                # device the adb link drops longer commands, and treating a
                # dropped batch as a mismatch would re-upload gigabytes over a
                # hiccup. Record it as unknown and let `verify` say so.
                ap.unreadable.append(rel)
                continue
            if got[rel] == lib.sha(rel):
                ap.verified.append(rel)
                ap.unverified = max(0, ap.unverified - 1)
            else:
                ap.mismatch.append(rel)
                if rel not in ap.push:
                    ap.push.append(rel)
                    ap.overwrite.append(rel)
                ap.status = "update"

    # --- pass 3: are any of these "device only" albums really the album that
    # just went missing under a new name? A rename naively reads as delete +
    # upload, which on a real library is hundreds of megabytes of needless
    # transfer for files that are already sitting there.
    _detect_renames(plan, lib, dev, dev_albums)

    # re-sort push lists into album order, and count the bytes we'd move
    for ap in plan.albums.values():
        order = {rel: i for i, rel in enumerate(lib.album_files(ap.name))}
        ap.push.sort(key=lambda r: order.get(r, 1 << 30))
        ap.push_bytes = sum(lib.files[r].size for r in ap.push if r in lib.files)
    return plan


def _detect_renames(plan: Plan, lib: Library, dev: dict[str, int],
                    dev_albums: dict[str, list[str]]) -> None:
    """Mark device-only albums that are a stale copy of a library album.

    The evidence has to be strong, because the action is "move these files".
    Every file in the orphan must appear in the candidate album under the same
    relative name *and* the same size; at least one of them must be audio, so a
    lone cover.jpg can't accidentally match half the library; and where several
    albums qualify, the tightest fit wins.
    """
    local_maps = {name: {_rel_inside(rel): lib.files[rel].size
                         for rel in lib.album_files(name)}
                  for name in lib.albums}

    for name, ap in plan.albums.items():
        if ap.status != "orphan":
            continue
        dev_inside = {_rel_inside(rel): dev[rel]
                      for rel in dev_albums.get(name, []) if rel in dev}
        if not dev_inside:
            continue
        if not any(Path(k).suffix.lower() in AUDIO_EXT for k in dev_inside):
            continue

        best: tuple[int, str] | None = None
        for cand, local in local_maps.items():
            if cand == name or not local:
                continue
            if not all(k in local and local[k] == size for k, size in dev_inside.items()):
                continue
            # A rename is only worth declaring if there is something to move. If
            # the destination already holds these files, this is a leftover
            # duplicate -- `--prune` should clear it, not a move that would do
            # nothing and leave the folder sitting there.
            at_dest = {_rel_inside(rel): dev[rel]
                       for rel in dev_albums.get(cand, []) if rel in dev}
            to_move = [k for k in dev_inside if k not in at_dest]
            conflicting = [k for k in dev_inside if k in at_dest
                           and at_dest[k] != dev_inside[k]]
            if not to_move or conflicting:
                continue
            slack = len(local) - len(dev_inside)
            if best is None or slack < best[0]:
                best = (slack, cand)
        if not best:
            continue

        ap.move_to = best[1]
        ap.move_files = sorted(dev_albums[name])
        ap.move_bytes = sum(dev_inside.values())
        ap.status = "rename"

        # those files no longer need sending
        target = plan.albums.get(ap.move_to)
        if target is not None:
            moved = set(dev_inside)
            target.push = [rel for rel in target.push if _rel_inside(rel) not in moved]
            target.overwrite = [rel for rel in target.overwrite if _rel_inside(rel) not in moved]
            target.push_bytes = sum(lib.files[r].size for r in target.push if r in lib.files)
            if not target.push and target.status == "update":
                target.status = "ok"
            target.notes.append(f"{len(moved)} file(s) arrive by move, not upload")


def attach_sizes(plan: Plan, lib: Library) -> None:
    """Give each album plan its byte count (for batching/reporting)."""
    for ap in plan.albums.values():
        ap.push_bytes = sum(lib.files[r].size for r in ap.push if r in lib.files)
