"""Where a library lives when it isn't this computer.

Two backends behind one interface:

    dir:PATH        an ordinary directory -- an SD card, a mounted share, or a
                    mirror you're building before the phone is even plugged in
    adb[:SERIAL]    an Android device over adb (the Titan 2 Elite, the Tab A9)

A device needs six verbs and nothing else: what files do you have (with sizes),
what are their hashes if I really insist, take these files, drop those files,
and hand me a small text file back. Everything else is diffing, which happens
on this side where it's cheap.

`dir:` is not a test stub. It's how you inspect a sync without a phone, how you
stage a card, and how you point the same tool at a NAS folder.
"""

from __future__ import annotations

import json
import os
import shlex
import shutil
import subprocess
import tarfile
import tempfile
import time
from pathlib import Path
from typing import Iterable, Sequence

DEFAULT_ADB = Path.home() / "android-sdk" / "platform-tools" / "adb"
SCRATCH = Path.home() / "mika" / "tmp"

# One tar per push batch. 1.5G keeps the scratch file modest and still makes the
# per-batch adb round trip negligible against the transfer itself.
PUSH_BATCH_BYTES = 1500 * 1024 * 1024

# One sha256sum invocation covering this many files, then a smaller retry. Sized
# so that a dropped connection costs one small batch rather than the whole run.
HASH_BATCH_FILES = 400
HASH_RETRY_FILES = 50

# `adb shell` needs a real shell; Android's is mksh (POSIX). Quote everything.
def _q(s: str) -> str:
    return shlex.quote(s)


def human_bytes(n: int | None) -> str:
    if n is None:
        return "unknown"
    for unit, div in (("GB", 1 << 30), ("MB", 1 << 20), ("kB", 1 << 10)):
        if n >= div:
            return f"{n / div:.1f} {unit}"
    return f"{n} B"


class TransportError(RuntimeError):
    pass


class Device:
    """A place files can be read from and written to, rooted at `root`."""

    label: str
    root: str

    def __init__(self, root: str):
        self.root = root.rstrip("/") or "/"

    # -- reading ---------------------------------------------------------
    def walk(self) -> dict[str, int]:
        """{relative path: size in bytes} for every regular file under root."""
        raise NotImplementedError

    def hash_walk(self, rels: Sequence[str] | None = None,
                  failures: list[str] | None = None) -> dict[str, str]:
        """{relative path: sha256}. `rels` limits the work; None means all.

        Anything asked about but not returned is *unknown*, never "differs" --
        put those in `failures` if a list is passed.
        """
        raise NotImplementedError

    # -- writing ---------------------------------------------------------
    def push(self, libroot: Path, rels: Sequence[str], sizes: dict[str, int] | None = None) -> int:
        """Copy `rels` (relative to `libroot`) to the same relative path under root."""
        raise NotImplementedError

    def remove(self, rels: Sequence[str]) -> None:
        """Delete these relative paths (files or whole directories)."""
        raise NotImplementedError

    def prune_empty_albums(self, albums: Sequence[str]) -> list[str]:
        """Remove album folders that no longer hold anything. Returns what went."""
        raise NotImplementedError

    def adopt_rename(self, src_album: str, dst_album: str, files: Sequence[str]) -> int:
        """Move files from one album folder to another, on the device.

        Used when an album turns out to be the same album under an old name: the
        bytes are already there, so moving them is instant where re-uploading is
        hundreds of megabytes. Only files that aren't already present at the
        destination are moved, and the source folder goes if it empties.
        """
        raise NotImplementedError

    def read_text(self, rel: str) -> str | None:
        raise NotImplementedError

    def write_text(self, rel: str, text: str) -> None:
        raise NotImplementedError

    def exists(self, rel: str) -> bool:
        raise NotImplementedError

    def free_bytes(self) -> int | None:
        """Room left where we'd be writing, or None if we can't tell."""
        raise NotImplementedError

    def probe(self) -> list[tuple[str, bool, str]]:
        """(name, ok, detail) for the things this transport needs to be able to do."""
        return []

    # -- helpers ---------------------------------------------------------
    @staticmethod
    def album_of(rel: str) -> str:
        """Top-level folder a root-relative path belongs to ("" for loose files)."""
        head = rel.split("/", 1)
        return head[0] if len(head) == 2 else ""


# ---------------------------------------------------------------------------
# plain directories
# ---------------------------------------------------------------------------

class DirDevice(Device):
    def __init__(self, path: str | Path):
        self.path = Path(path).expanduser()
        if not self.path.is_dir():
            # A typo'd path would otherwise read as "an empty device, push everything".
            # An empty directory is a fine device (a fresh card); a missing one is a mistake.
            raise TransportError(
                f"{self.path} doesn't exist -- a directory device has to already be there "
                f"(an empty one is fine). Check the path, or the card is unmounted.")
        self.path = self.path.resolve()
        super().__init__(str(self.path))
        self.label = f"dir:{self.path}"

    def _p(self, rel: str) -> Path:
        return self.path / rel

    def walk(self) -> dict[str, int]:
        out: dict[str, int] = {}
        if not self.path.is_dir():
            return out
        for dirpath, _dirnames, filenames in os.walk(self.path):
            for name in filenames:
                p = Path(dirpath) / name
                try:
                    if not p.is_file() or p.is_symlink() and not p.exists():
                        continue
                    out[str(p.relative_to(self.path))] = p.stat().st_size
                except OSError:
                    continue
        return out

    def hash_walk(self, rels: Sequence[str] | None = None,
                  failures: list[str] | None = None) -> dict[str, str]:
        import hashlib

        want = None if rels is None else set(rels)
        out: dict[str, str] = {}
        for rel, size in self.walk().items():
            if want is not None and rel not in want:
                continue
            h = hashlib.sha256()
            try:
                with open(self._p(rel), "rb") as fh:
                    for chunk in iter(lambda: fh.read(1 << 20), b""):
                        h.update(chunk)
            except OSError:
                if failures is not None:
                    failures.append(rel)
                continue
            out[rel] = h.hexdigest()
        return out

    def push(self, libroot: Path, rels: Sequence[str], sizes: dict[str, int] | None = None) -> int:
        n = 0
        for rel in rels:
            src = Path(libroot) / rel
            dst = self._p(rel)
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
            n += 1
        return n

    def remove(self, rels: Sequence[str]) -> None:
        for rel in rels:
            p = self._p(rel)
            if p.is_dir():
                shutil.rmtree(p, ignore_errors=True)
            else:
                try:
                    p.unlink()
                except FileNotFoundError:
                    pass

    def prune_empty_albums(self, albums: Sequence[str]) -> list[str]:
        gone = []
        for album in albums:
            p = self._p(album)
            # deepest first: an album that held [Disc N] folders keeps them after
            # the files go, and rmdir on the album fails while they're there
            for d in sorted((q for q in p.rglob("*") if q.is_dir()),
                            key=lambda q: -len(q.parts)):
                try:
                    d.rmdir()
                except OSError:
                    pass
            try:
                p.rmdir()           # only succeeds when it really is empty
                gone.append(album)
            except OSError:
                pass
        return gone

    def adopt_rename(self, src_album: str, dst_album: str, files: Sequence[str]) -> int:
        src, dst = self._p(src_album), self._p(dst_album)
        moved = 0
        for rel in files:
            inside = rel.split("/", 1)[1] if "/" in rel else rel
            s, d = src / inside, dst / inside
            if not s.is_file() or d.exists():
                continue
            d.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(s), str(d))
            moved += 1
        # deepest first: moving files out can leave empty [Disc N] folders behind,
        # and the album itself won't go while they're there
        for d in sorted((p for p in src.rglob("*") if p.is_dir()),
                        key=lambda p: -len(p.parts)):
            try:
                d.rmdir()
            except OSError:
                pass
        try:
            src.rmdir()
        except OSError:
            pass
        return moved

    def read_text(self, rel: str) -> str | None:
        try:
            return self._p(rel).read_text()
        except (OSError, UnicodeDecodeError):
            return None

    def write_text(self, rel: str, text: str) -> None:
        p = self._p(rel)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)

    def exists(self, rel: str) -> bool:
        return self._p(rel).exists()

    def free_bytes(self) -> int | None:
        try:
            return shutil.disk_usage(self.path).free
        except OSError:
            return None

    def probe(self) -> list[tuple[str, bool, str]]:
        return [
            ("root exists", self.path.is_dir(), str(self.path)),
            ("root writable", os.access(self.path, os.W_OK), ""),
        ]


# ---------------------------------------------------------------------------
# android over adb
# ---------------------------------------------------------------------------

class AdbDevice(Device):
    def __init__(self, serial: str | None, root: str, adb: str | Path = DEFAULT_ADB):
        self.adb = str(adb)
        self.serial = serial or self._only_device()
        self._check_state()
        super().__init__(root)
        self.label = f"adb:{self.serial}"

    # -- plumbing --------------------------------------------------------
    def _run(self, argv: Sequence[str], **kw) -> subprocess.CompletedProcess:
        return subprocess.run(argv, capture_output=True, **kw)

    def _base(self) -> list[str]:
        return [self.adb, "-s", self.serial]

    def _check_state(self) -> None:
        """Fail with something a human can act on, rather than a filesystem error.

        With an explicit serial we never ask adb who's connected, so an unplugged
        phone would otherwise surface as "find failed" -- which reads like a
        broken device rather than an absent one.
        """
        r = self._run(self._base() + ["get-state"])
        state = r.stdout.decode(errors="replace").strip()
        if state == "device":
            return
        err = r.stderr.decode(errors="replace").strip()
        if state == "unauthorized":
            raise TransportError(
                f"{self.serial} is connected but not authorised -- accept the USB "
                f"prompt on the phone")
        if state == "offline":
            raise TransportError(f"{self.serial} is offline (adb can see it but not talk to it)")
        raise TransportError(
            f"{self.serial} isn't connected. Plug the phone in, or aim at a directory "
            f"with --device dir:PATH (or use --device adb to take whichever device is there)"
            + (f" [{err}]" if err else ""))

    def _only_device(self) -> str:
        r = self._run([self.adb, "devices"])
        if r.returncode != 0:
            raise TransportError(f"adb failed: {r.stderr.decode(errors='replace').strip()}")
        serials = []
        for line in r.stdout.decode(errors="replace").splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                serials.append(parts[0])
            elif len(parts) >= 2 and parts[1] == "unauthorized":
                raise TransportError(f"{parts[0]} is connected but not authorised -- accept the USB prompt on the phone")
        if not serials:
            raise TransportError("no adb device connected (plug the phone in, or pass --device dir:PATH)")
        if len(serials) > 1:
            raise TransportError("more than one adb device: " + ", ".join(serials) + " -- pass --serial")
        return serials[0]

    def sh(self, script: str) -> subprocess.CompletedProcess:
        """Run a shell script on the device."""
        return self._run(self._base() + ["shell", script])

    def sh_out(self, script: str) -> bytes:
        """Run a script and get clean stdout (no pty line-ending mangling)."""
        return self._run(self._base() + ["exec-out", script]).stdout

    def _must(self, r: subprocess.CompletedProcess, what: str) -> None:
        if r.returncode != 0:
            err = r.stderr.decode(errors="replace").strip()
            raise TransportError(f"{what}: {err or f'exit {r.returncode}'}")

    # -- reading ---------------------------------------------------------
    def walk(self) -> dict[str, int]:
        # stat -c is exact and cheap; fall back to ls -l parsing if toybox is
        # older than -c support.
        script = (
            f"cd {_q(self.root)} 2>/dev/null || exit 3; "
            "find . -type f -exec stat -c '%s %n' {} +"
        )
        r = self.sh(script)
        if r.returncode == 3:
            return {}
        if r.returncode != 0 or not r.stdout.strip():
            return self._walk_via_ls()

        out: dict[str, int] = {}
        for line in r.stdout.decode(errors="replace").splitlines():
            if " " not in line:
                continue
            size, _, name = line.partition(" ")
            if not name.startswith("./"):
                continue
            try:
                out[name[2:]] = int(size)
            except ValueError:
                continue
        return out

    def _walk_via_ls(self) -> dict[str, int]:
        r = self.sh(f"cd {_q(self.root)} 2>/dev/null || exit 3; find . -type f -exec ls -l {{}} +")
        if r.returncode == 3:
            return {}
        if r.returncode != 0:
            err = r.stderr.decode(errors="replace").strip()
            raise TransportError(
                f"could not list {self.root} (neither `stat -c` nor `ls -l` worked)"
                + (f": {err}" if err else ""))
        import re

        pat = re.compile(r"^\S+\s+\d+\s+\S+\s+\S+\s+(\d+)\s+\S+\s+\S+\s+\S+\s+(\./.*)$")
        out: dict[str, int] = {}
        for line in r.stdout.decode(errors="replace").splitlines():
            m = pat.match(line)
            if m:
                out[m.group(2)[2:]] = int(m.group(1))
        return out

    def hash_walk(self, rels: Sequence[str] | None = None,
                  failures: list[str] | None = None) -> dict[str, str]:
        """{rel: sha256}.

        `failures` collects anything we asked about but could not read. That
        distinction matters: on this device the adb link intermittently drops
        longer commands (`error: closed`), and a dropped batch that is silently
        treated as "no hash" would look like a content mismatch to the caller --
        which would turn a hiccup into gigabytes of needless re-upload.
        """
        if rels is None:
            r = self.sh(f"cd {_q(self.root)} && find . -type f -exec sha256sum {{}} +")
            if r.returncode != 0:
                err = r.stderr.decode(errors="replace").strip()
                raise TransportError(f"could not hash the device tree: {err or 'sha256sum failed'}")
            out: dict[str, str] = {}
            for line in r.stdout.decode(errors="replace").splitlines():
                h, sep, name = line.partition("  ")
                if not sep or not name.startswith("./"):
                    continue
                out[name[2:]] = h
            return out

        rels = list(rels)
        out: dict[str, str] = {}
        for batch in _chunks(rels, HASH_BATCH_FILES):
            got = self._hash_batch(batch)
            out.update(got)
            missing = [r for r in batch if r not in got]
            if not missing:
                continue
            # retry the batch smaller, then one at a time; only then give up
            for sub in _chunks(missing, HASH_RETRY_FILES):
                got = self._hash_batch(sub)
                out.update(got)
                for rel in [r for r in sub if r not in got]:
                    out.update(self._hash_batch([rel]))
            if failures is not None:
                failures.extend(r for r in missing if r not in out)
        return out

    def _hash_batch(self, rels: Sequence[str]) -> dict[str, str]:
        args = " ".join(_q(rel) for rel in rels)
        script = f"cd {_q(self.root)} && sha256sum -- {args}"
        r = self.sh(script)
        out: dict[str, str] = {}
        for line in r.stdout.decode(errors="replace").splitlines():
            h, sep, name = line.partition("  ")
            if not sep:
                continue
            name = name.strip()
            if name.startswith("./"):
                name = name[2:]
            out[name] = h
        return out

    # -- writing ---------------------------------------------------------
    def push(self, libroot: Path, rels: Sequence[str], sizes: dict[str, int] | None = None) -> int:
        rels = list(rels)
        if not rels:
            return 0
        libroot = Path(libroot)
        sizes = sizes or {r: (libroot / r).stat().st_size for r in rels}

        SCRATCH.mkdir(parents=True, exist_ok=True)
        batches = _batch_by_size(rels, sizes, PUSH_BATCH_BYTES)
        pushed = 0
        with tempfile.TemporaryDirectory(dir=SCRATCH, prefix="marimo-sync-") as tmp:
            local_tar = Path(tmp) / "batch.tar"
            remote_tar = "/data/local/tmp/marimo-sync-batch.tar"
            for i, batch in enumerate(batches, 1):
                names = b"".join(r.encode("utf-8") + b"\0" for r in batch)
                # deterministic order keeps the tar reproducible-ish
                with open(local_tar, "wb") as fh:
                    subprocess.run(
                        ["tar", "-C", str(libroot), "--null", "-T", "-", "-cf", "-"],
                        input=names, stdout=fh, check=True,
                    )
                r = self._run(self._base() + ["push", "-q", str(local_tar), remote_tar])
                self._must(r, f"pushing batch {i}/{len(batches)}")
                # extract as the shell user into /sdcard (FUSE, writable by shell)
                r = self.sh(f"cd {_q(self.root)} && tar xf {_q(remote_tar)} && rm -f {_q(remote_tar)}")
                self._must(r, f"extracting batch {i}/{len(batches)}")
                pushed += len(batch)
        return pushed

    def remove(self, rels: Sequence[str]) -> None:
        rels = list(rels)
        batch: list[str] = []
        size = 0
        for rel in rels:
            batch.append(rel)
            size += len(rel) + 1
            if size > 60_000:
                self._remove_batch(batch)
                batch, size = [], 0
        if batch:
            self._remove_batch(batch)

    def _remove_batch(self, rels: Sequence[str]) -> None:
        args = " ".join(_q(r) for r in rels)
        r = self.sh(f"cd {_q(self.root)} && rm -rf -- {args}")
        self._must(r, "deleting from the device")

    def prune_empty_albums(self, albums: Sequence[str]) -> list[str]:
        gone = []
        for album in albums:
            # -depth walks children first, so emptied subfolders go before their
            # parent; rmdir only succeeds on an empty directory, which is exactly
            # the test we want
            self.sh(f"cd {_q(self.root)} && find {_q(album)} -depth -type d "
                    f"-exec rmdir {{}} \\; 2>/dev/null")
            still_there = self.sh(f"[ -d {_q(self.root + '/' + album)} ] && echo y || echo n")
            if still_there.stdout.strip() == b"n":
                gone.append(album)
        return gone

    def adopt_rename(self, src_album: str, dst_album: str, files: Sequence[str]) -> int:
        files = list(files)
        if not files:
            return 0
        q_src = _q(f"{self.root}/{src_album}")
        q_dst = _q(f"{self.root}/{dst_album}")
        moved = 0
        # One script per album: the names are quoted individually, and a file only
        # moves if it isn't already at the destination (so this can never clobber
        # something newer with an older copy).
        for batch in _chunks(files, 200):
            names = " ".join(_q(f.split("/", 1)[1] if "/" in f else f) for f in batch)
            script = "\n".join([
                f"cd {q_src} || exit 4",
                f"mkdir -p {q_dst}",
                f"for f in {names}; do",
                f'  if [ ! -e {q_dst}/"$f" ]; then',
                f'    mkdir -p {q_dst}/"$(dirname "$f")" 2>/dev/null || true',
                f'    mv "$f" {q_dst}/"$f" && echo moved || true',
                "  fi",
                "done",
            ])
            r = self.sh(script)
            if r.returncode == 4:
                raise TransportError(f"{src_album} is no longer on the device")
            self._must(r, f"moving files into {dst_album}")
            moved += r.stdout.count(b"moved")
        # deepest first: emptied [Disc N] folders would keep the album itself alive
        self.sh(f"cd {_q(self.root)} && find {_q(src_album)} -depth -type d "
                f"-exec rmdir {{}} \\; 2>/dev/null || true")
        return moved

    def read_text(self, rel: str) -> str | None:
        r = self._run(self._base() + ["exec-out", f"cat {_q(self.root + '/' + rel)}"])
        if r.returncode != 0 or not r.stdout:
            return None
        try:
            return r.stdout.decode("utf-8")
        except UnicodeDecodeError:
            return None

    def write_text(self, rel: str, text: str) -> None:
        SCRATCH.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=SCRATCH, prefix="marimo-sync-") as tmp:
            p = Path(tmp) / "manifest.json"
            p.write_text(text)
            parent = str(Path(self.root) / rel).rsplit("/", 1)[0]
            self.sh(f"mkdir -p {_q(parent)}")
            r = self._run(self._base() + ["push", "-q", str(p), f"{self.root}/{rel}"])
            self._must(r, "writing the device manifest")

    def exists(self, rel: str) -> bool:
        r = self.sh(f"[ -e {_q(self.root + '/' + rel)} ] && echo yes || echo no")
        return r.stdout.strip() == b"yes"

    def free_bytes(self) -> int | None:
        r = self.sh(f"df -k {_q(self.root)} 2>/dev/null || df -k {_q(str(Path(self.root).parent))}")
        for line in r.stdout.decode(errors="replace").splitlines():
            fields = line.split()
            if len(fields) >= 4 and fields[0].startswith("/"):
                try:
                    return int(fields[3]) * 1024          # Available, in 1K blocks
                except ValueError:
                    continue
        return None

    def probe(self) -> list[tuple[str, bool, str]]:
        """Every assumption this transport makes about the device, checked."""
        out: list[tuple[str, bool, str]] = []

        def check(name: str, script: str, want: str = "ok") -> None:
            r = self.sh(script)
            got = r.stdout.decode(errors="replace").strip()
            out.append((name, got == want, got or r.stderr.decode(errors="replace").strip()[:80]))

        check("device reachable", "echo ok")
        for prop, label in (("ro.product.model", "model"),
                            ("ro.build.version.release", "android")):
            r = self.sh(f"getprop {prop}")
            out.append((label, bool(r.stdout.strip()), r.stdout.decode(errors="replace").strip()))

        out.append(("music root present", Path(self.root).name != "" and self.exists("."),
                    self.root))

        # the two things our listing doesn't actually need unless they're missing
        check("find -exec ... + works", "find . -maxdepth 0 -exec true {} + >/dev/null 2>&1 && echo ok")
        check("stat -c works", "stat -c '%s %n' . >/dev/null 2>&1 && echo ok")
        check("sha256sum works", "echo -n ok | sha256sum >/dev/null 2>&1 && echo ok")
        check("tar present", "command -v tar >/dev/null && echo ok")
        check("rmdir present", "command -v rmdir >/dev/null && echo ok")

        # can we write where it matters?
        probe_file = f"{self.root}/.marimo-sync-write-probe"
        r = self.sh(f"echo ok > {_q(probe_file)} && cat {_q(probe_file)} && rm -f {_q(probe_file)}")
        out.append(("music root writable", r.stdout.decode(errors="replace").strip() == "ok",
                    r.stderr.decode(errors="replace").strip()[:80]))

        manifest_dir = str(Path(self.root).parent / ".marimo-sync")
        r = self.sh(f"mkdir -p {_q(manifest_dir)} && echo ok > {_q(manifest_dir + '/.probe')} "
                    f"&& rm -f {_q(manifest_dir + '/.probe')} && echo ok")
        out.append(("manifest dir writable (and outside the app's music tree)",
                    r.stdout.decode(errors="replace").strip() == "ok", manifest_dir))

        try:
            n = len(self.walk())
            out.append(("tree walk", True, f"{n} files"))
        except TransportError as e:
            out.append(("tree walk", False, str(e)[:80]))

        free = self.free_bytes()
        out.append(("free space", free is not None, human_bytes(free) if free else "unknown"))
        return out


# ---------------------------------------------------------------------------
# a device that pulls instead of being pushed to
# ---------------------------------------------------------------------------

class HttpDevice(Device):
    """A phone fetching from a `serve`, and telling us what it ended up with.

    There is nothing here to connect to. The phone downloads over the LAN and
    reports its inventory back, and that report *is* this device's state — so
    `walk` answers from the report, pushing is refused with an explanation of
    what to do instead, and the "manifest" this device reads is the inventory
    translated into the manifest's shape with `match: "reported"`.

    The honest caveat: this view is exactly as fresh as the phone's last report.
    A file deleted on the phone since then still looks present.
    """

    def __init__(self, url: str, root: str, inventory: Path):
        self.url = url.rstrip("/")
        self.inventory = Path(inventory)
        super().__init__(root or "/")
        from urllib.parse import urlparse
        self.label = f"http:{urlparse(self.url).netloc}"

    # -- the report ------------------------------------------------------
    def _data(self) -> dict:
        try:
            data = json.loads(self.inventory.read_text())
        except (OSError, ValueError):
            return {}
        return data if isinstance(data, dict) else {}

    def _albums(self) -> dict:
        return self._data().get("albums") or {}

    def as_manifest_text(self) -> str:
        """The inventory rewritten in the manifest's schema."""
        albums: dict[str, dict] = {}
        for name, entry in self._albums().items():
            files: dict[str, dict] = {}
            for fname, meta in (entry.get("files") or {}).items():
                if isinstance(meta, dict):
                    files[fname] = {"size": int(meta.get("size") or 0),
                                    "sha256": meta.get("sha256"),
                                    "match": meta.get("match") or "reported"}
                else:
                    files[fname] = {"size": int(meta or 0), "sha256": None,
                                    "match": "reported"}
            albums[name] = {"files": files,
                            "reported": entry.get("reported") or self._data().get("reported")}
        return json.dumps({"version": 1, "albums": albums,
                           "root": self._data().get("root") or self.root,
                           "updated": self._data().get("reported")})

    def reported_at(self) -> str | None:
        return self._data().get("reported")

    # -- reading ---------------------------------------------------------
    def walk(self) -> dict[str, int]:
        out: dict[str, int] = {}
        for album, entry in self._albums().items():
            for fname, meta in (entry.get("files") or {}).items():
                size = meta.get("size") if isinstance(meta, dict) else meta
                out[f"{album}/{fname}"] = int(size or 0)
        return out

    def hash_walk(self, rels: Sequence[str] | None = None,
                  failures: list[str] | None = None) -> dict[str, str]:
        want = None if rels is None else set(rels)
        out: dict[str, str] = {}
        for album, entry in self._albums().items():
            for fname, meta in (entry.get("files") or {}).items():
                rel = f"{album}/{fname}"
                if want is not None and rel not in want:
                    continue
                sha = meta.get("sha256") if isinstance(meta, dict) else None
                if sha:
                    out[rel] = sha
                elif failures is not None:
                    failures.append(rel)      # the phone didn't tell us; we can't ask
        return out

    def read_text(self, rel: str) -> str | None:
        if not self.inventory.is_file():
            return None
        return self.as_manifest_text()

    # -- writing: refused, with the thing to do instead ------------------
    def push(self, libroot: Path, rels: Sequence[str], sizes: dict[str, int] | None = None) -> int:
        raise TransportError(
            "this device pulls rather than being pushed to. Run `marimo-sync serve` "
            "on this computer and use Sync-from-desktop in the app; then "
            "`marimo-sync status` will show you what the phone reported.")

    def remove(self, rels: Sequence[str]) -> None:
        raise TransportError("this device pulls -- the phone deletes its own files")

    def prune_empty_albums(self, albums: Sequence[str]) -> list[str]:
        return []

    def exists(self, rel: str) -> bool:
        return rel in self.walk()

    def free_bytes(self) -> int | None:
        return None

    def write_text(self, rel: str, text: str) -> None:
        # The write is local on purpose: this is our record of what the phone
        # reported, kept on this side so `status` works without the phone around.
        try:
            data = json.loads(text)
        except ValueError:
            return
        self.inventory.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.inventory.with_suffix(".tmp")
        merged = self._data()
        merged["albums"] = data.get("albums", {})
        tmp.write_text(json.dumps(merged, indent=1, sort_keys=True))
        os.replace(tmp, self.inventory)

    def probe(self) -> list[tuple[str, bool, str]]:
        have_report = self.inventory.is_file()
        albums = len(self._albums())
        files = len(self.walk())
        return [
            ("url", True, self.url),
            ("inventory on file", have_report, str(self.inventory)),
            ("what the phone last reported", albums > 0,
             f"{albums} albums, {files} files" if albums else
             "nothing yet -- the phone hasn't reported in"),
            ("as of", have_report, str(self.reported_at() or "unknown")),
        ]


# ---------------------------------------------------------------------------

def _chunks(items: Sequence[str], size: int) -> list[list[str]]:
    return [list(items[i:i + size]) for i in range(0, len(items), size)]


def _batch_by_size(rels: Iterable[str], sizes: dict[str, int], limit: int) -> list[list[str]]:
    batches: list[list[str]] = []
    cur: list[str] = []
    total = 0
    for rel in rels:
        s = sizes.get(rel, 0)
        if cur and total + s > limit:
            batches.append(cur)
            cur, total = [], 0
        cur.append(rel)
        total += s
    if cur:
        batches.append(cur)
    return batches


def gvfs_mtp_mounts() -> list[Path]:
    """MTP filesystems gvfs has actually mounted (so they have a real path)."""
    gvfs = Path(f"/run/user/{os.getuid()}/gvfs")
    return sorted(p for p in gvfs.glob("mtp:host=*") if p.is_dir()) if gvfs.is_dir() else []


def _parse_gio_volumes(text: str) -> list[tuple[str, str]]:
    """(name, activation_root) for every MTP volume in `gio mount -l` output.

    `activation_root` is empty when gvfs couldn't open the device to read its
    descriptor — which is what happens while another program holds it. That's a
    different situation from "no phone plugged in", and callers need to tell them
    apart: gio's output is blocks starting at column 0, so indented lines belong
    to the entry above.
    """
    vols: list[tuple[str, str]] = []
    cur: dict[str, object] | None = None
    for line in text.splitlines():
        if line[:1] not in ("", " ", "\t"):        # a new top-level entry
            if cur and cur["mtp"]:
                vols.append((str(cur["name"]), str(cur["root"])))
            cur = ({"name": line.split(":", 1)[1].strip(), "mtp": False, "root": ""}
                   if line.startswith("Volume(") else None)
            continue
        if cur is None:
            continue
        s = line.strip()
        if s.startswith("Type:") and "GProxyVolumeMonitorMTP" in s:
            cur["mtp"] = True
        elif s.startswith("activation_root="):
            cur["root"] = s.split("=", 1)[1].strip()
    if cur and cur["mtp"]:
        vols.append((str(cur["name"]), str(cur["root"])))
    return vols


def mtp_volumes() -> list[tuple[str, str]]:
    try:
        p = subprocess.run(["gio", "mount", "-l"], capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return []                       # no gio: nothing to discover, not an error
    return _parse_gio_volumes(p.stdout)


def mount_mtp(activation_root: str, timeout: int = 30) -> tuple[Path | None, str]:
    """Ask gvfs to mount a phone. Returns (path, ''), or (None, why not)."""
    before = set(gvfs_mtp_mounts())
    try:
        p = subprocess.run(["gio", "mount", activation_root],
                           capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError) as e:
        return None, str(e)
    if p.returncode != 0:
        return None, (p.stderr or p.stdout).strip()
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        now = gvfs_mtp_mounts()
        fresh = [m for m in now if m not in before] or now
        if fresh:
            return fresh[0], ""
        time.sleep(0.4)
    return None, "gvfs reported success but no mount path ever appeared"


def autodetect_device(root: str, adb: str | Path = DEFAULT_ADB) -> str:
    """The phone, if exactly one is reachable — over adb, or mounted by the OS.

    adb first: it's much faster, and if adb is installed with a device authorised
    then that's plainly the one being used. Otherwise an MTP phone: already mounted
    by gvfs, or visible to gvfs as an unmounted volume (in which case we mount it
    ourselves, which is what makes "just plug it in" work).

    Nothing here needs a serial number or a config file, which is the point.
    """
    adb_trouble: str | None = None
    try:
        return f"adb:{AdbDevice(None, root, adb).serial}"
    except TransportError as e:
        adb_trouble = str(e)

    mounts = gvfs_mtp_mounts()
    if len(mounts) == 1:
        return f"dir:{mounts[0]}"
    if len(mounts) > 1:
        raise TransportError(
            "more than one phone is mounted: " + ", ".join(str(m) for m in mounts)
            + " — pick one with --device dir:PATH")

    vols = mtp_volumes()
    mountable = [(n, r) for n, r in vols if r]
    busy = [n for n, r in vols if not r]
    if len(mountable) > 1:
        raise TransportError("more than one phone is on USB: "
                             + ", ".join(n for n, _ in mountable))
    if len(mountable) == 1:
        name, act = mountable[0]
        path, why = mount_mtp(act)
        if path is None:
            raise TransportError(
                f"your phone ({name}) is plugged in, but it wouldn't mount: {why}\n"
                "  MTP allows one program at a time. If your file manager has the "
                "phone open (Dolphin holds it through kiod6), close that window and "
                "try again." + (f"  [{adb_trouble}]" if adb_trouble else ""))
        if not (path / root.lstrip("/")).is_dir():
            raise TransportError(
                f"your phone ({name}) mounted at {path}, but {root} isn't there.\n"
                "  If the phone is locked, or still set to charging-only, unlock it "
                "and choose 'File transfer' (MTP), then try again.")
        return f"dir:{path}"
    if busy:
        # gvfs lists the volume but couldn't read its descriptor, which is what it
        # does when the device is already open somewhere else. Saying "no phone"
        # here would be a lie, and the unhelpful kind.
        raise TransportError(
            f"your phone ({busy[0]}) is plugged in, but the system can't open it — "
            "another program already has it. MTP allows one at a time, and your "
            "file manager holds it while the phone is open there (Dolphin keeps it "
            "through kiod6). Close that window, or eject the phone in Dolphin, then "
            "try again." + (f"  [{adb_trouble}]" if adb_trouble else ""))

    raise TransportError(
        "couldn't find a phone. Plug one in and unlock it, choose 'File transfer' "
        "(MTP) on the phone, and it will be mounted for you (needs gvfs-mtp and "
        "gvfs-fuse — installed on this box). Or point --device at its music folder, "
        "or use --device http://host:port to have the phone fetch over the network "
        "instead." + (f"  [{adb_trouble}]" if adb_trouble else ""))


def open_device(spec: str, root: str | None = None, inventory: Path | None = None) -> Device:
    """`spec` is 'auto', 'dir:PATH', 'adb[:SERIAL]', or 'http(s)://host:port'."""
    if spec in (None, "", "auto"):
        spec = autodetect_device(root or "/sdcard/Music")
    if spec.startswith("dir:"):
        return DirDevice(spec[4:])
    if spec.startswith(("http://", "https://")):
        if inventory is None:
            raise TransportError("an http device needs somewhere to keep the phone's report")
        return HttpDevice(spec, root or "/", inventory)
    if spec.startswith("adb"):
        _, _, serial = spec.partition(":")
        if root is None:
            raise TransportError("adb device needs a root (--root)")
        return AdbDevice(serial or None, root)
    raise TransportError(
        f"don't understand device {spec!r} -- try adb[:SERIAL], dir:PATH or http://host:port")
