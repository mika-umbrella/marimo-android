"""The phone-pulls-over-the-LAN half.

A small HTTP server that publishes the library's contents and its bytes, so the
Android app can work out what it's missing and fetch it — no cable, no USB
debugging, no MTP, and the phone writes with its own storage access.

    marimo-sync serve

Endpoints (all under /api, all needing the token unless --no-auth):

    GET  /api/health              is this a marimo-sync, and can I talk to it
    GET  /api/index.json          every album and file, with sizes (+ hashes on request)
    GET  /api/file/<album>/<file> one file's bytes, resumable via Range
    GET  /api/album/<name>.tar    a whole album as one tar (many small files, one request)
    GET  /api/manifest.json       the last inventory the phone reported
    POST /api/manifest            the phone tells us what it has, so `status` stays true

The index is the whole contract: the app compares it against its own scan and asks
for the difference. Nothing on this side needs to know what the phone holds — but if
the phone *does* report in, a later plain `marimo-sync status --device http://…`
can show the desktop's view of it without a cable.
"""

from __future__ import annotations

import datetime
import hashlib
import json
import os
import socket
import sys
import tarfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

from .config import Config, cache_dir
from .core import HashCache, Library
from . import prep

VERSION = 1
INDEX_TTL = 30.0        # rescan the library if the cached scan is older than this
TOKEN_HEADER = "X-Marimo-Token"

# Logging must not be able to break the server. Found the hard way: piping `serve`
# into `head` closes the pipe once head has had its lines, and every later print
# raised BrokenPipeError *inside a handler thread* -- which killed that connection
# before it had written a response (the phone saw "unexpected end of stream") and
# left the server broken for good, with no clue in what little output survived.
_logging = True


def _say(*parts, **kw) -> None:
    """print(), but a closed stdout can only cost us the log, never a request."""
    global _logging
    if not _logging:
        return
    try:
        print(*parts, **kw)
    except (BrokenPipeError, ValueError, OSError):
        _logging = False


def inventory_path(cfg: Config) -> Path:
    """Where the phone's last report is kept, for a later `status` to read."""
    return cfg.inventory


def lan_address() -> str:
    """This machine's address on the LAN, without sending anything."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("192.0.2.1", 9))          # TEST-NET: no packets actually leave
            return s.getsockname()[0]
        finally:
            s.close()
    except OSError:
        try:
            return socket.gethostbyname(socket.gethostname())
        except OSError:
            return "127.0.0.1"


class State:
    """Everything the handlers share, behind one lock."""

    def __init__(self, cfg: Config, token: str | None, allow_hashes: bool):
        self.cfg = cfg
        self.token = token
        self.allow_hashes = allow_hashes
        self.lock = threading.Lock()
        self.lib: Library | None = None
        self.scanned_at = 0.0
        self.tars: dict[str, tuple[Path, int, str]] = {}   # album -> (path, size, fingerprint)
        self.requests = 0
        self.bytes_sent = 0
        # what the phone last said it lacked, and what we're doing about it. Read by
        # /api/health so the phone can show "the desktop is converting X" rather than
        # sitting there wondering why the bytes haven't started.
        self.prepare_ready = True
        self.preparing: dict = {"state": "idle"}
        self.prepare_lock = threading.Lock()
        self.prepare_thread: threading.Thread | None = None

    def library(self, force: bool = False) -> Library:
        with self.lock:
            now = time.monotonic()
            if force or self.lib is None or now - self.scanned_at > INDEX_TTL:
                self.lib = Library(self.cfg.source, HashCache(self.cfg.hashcache))
                self.scanned_at = now
            return self.lib

    def fingerprint(self, album: str, lib: Library) -> str:
        h = hashlib.sha256()
        for rel in lib.album_files(album):
            info = lib.files[rel]
            h.update(f"{rel}\0{info.size}\0{info.mtime_ns}\n".encode())
        return h.hexdigest()

    def album_tar(self, album: str, lib: Library) -> tuple[Path, int]:
        """One tar per album, cached until the album's files change."""
        fp = self.fingerprint(album, lib)
        with self.lock:
            hit = self.tars.get(album)
            if hit and hit[2] == fp and hit[0].is_file():
                return hit[0], hit[1]
        scratch = cfg_scratch(self.cfg)
        scratch.mkdir(parents=True, exist_ok=True)
        tmp = scratch / f".marimo-sync-{hashlib.sha256(album.encode()).hexdigest()[:12]}.tar"
        with tarfile.open(tmp, "w", format=tarfile.PAX_FORMAT) as tf:
            for rel in lib.album_files(album):
                inside = rel.split("/", 1)[1] if "/" in rel else rel
                tf.add(lib.root / rel, arcname=inside)
        size = tmp.stat().st_size
        with self.lock:
            self.tars[album] = (tmp, size, fp)
        return tmp, size

    # -- preparing, on the phone's say-so ------------------------------------

    def prepare(self, albums: list[str], *, waves: bool = True,
                covers: bool = True) -> bool:
        """Get these albums ready to serve, in the background. True if it started.

        Started by a phone reporting what it has, so it must never block that
        request — and never run twice over the library at once, because two
        conversions writing the same album is a way to corrupt one.
        """
        if not albums or not self.prepare_ready:
            return False
        with self.prepare_lock:
            if self.prepare_thread is not None and self.prepare_thread.is_alive():
                return False
            self.preparing = {"state": "queued", "albums": list(albums),
                              "done": 0, "total": len(albums)}
            self.prepare_thread = threading.Thread(
                target=self._prepare_worker, args=(albums, waves, covers), daemon=True)
            self.prepare_thread.start()
        return True

    def _prepare_worker(self, albums: list[str], waves: bool, covers: bool) -> None:
        def progress(stage: str, album: str = "", done: int = 0, total: int = 0) -> None:
            if stage == "log":
                return                     # not a state; the phone waits on real ones
            with self.prepare_lock:
                self.preparing = {"state": stage, "album": album, "done": done,
                                  "total": total, "albums": list(albums)}

        try:
            report = prep.ensure(self.cfg, albums, waves=waves, covers=covers,
                                 reload=lambda: self.library(force=True),
                                 on_progress=progress)
            final = {"state": "ready", "albums": list(albums), "report": report,
                     "done": len(albums), "total": len(albums)}
            _say(f"  prepared {len(albums)} album(s) for the phone", flush=True)
        except Exception as e:                     # a stage must never kill the server
            final = {"state": "failed", "error": f"{type(e).__name__}: {e}",
                     "albums": list(albums)}
            _say(f"  preparing failed: {final['error']}", flush=True)
        with self.prepare_lock:
            self.preparing = final
        with self.lock:
            self.lib = None            # the library changed on disk; rescan next ask


    def prepare_everything(self) -> bool:
        """Bring the library up to date on our own account: convert, shade, art.

        The point of serving is that the library is ready *before* anything asks for
        it, so this runs when the server starts — and the same `prepare` runs again
        whenever a phone reports in. One owner of the writing, which matters: two
        conversions of the same album at once is a way to corrupt one.
        """
        lib = self.library(force=True)
        todo = sorted({a.replace("[FLAC]", "[OPUS]")
                       for a in prep.convert_targets(self.cfg, lib)}
                      | set(prep.albums_needing_waves(lib, sorted(lib.albums))))
        return self.prepare(todo)


def albums_they_lack(lib: Library, their_albums) -> list[str]:
    """Which of our albums the phone hasn't got everything of, from its own report.

    Deliberately lenient about the shape of the phone's file keys: this decides what
    we *prepare*, and erring towards doing the work is far cheaper than erring towards
    offering an album that turns out to be half-missing mid-transfer.
    """
    out: list[str] = []
    if not isinstance(their_albums, dict):
        return sorted(lib.albums)
    for name in sorted(lib.albums):
        theirs = their_albums.get(name)
        keys = None
        if isinstance(theirs, dict):
            files = theirs.get("files")
            if isinstance(files, dict):
                keys = set(files)
            elif isinstance(files, list):
                keys = {f.get("name") for f in files if isinstance(f, dict)}
        if keys is None:
            out.append(name)
            continue
        for rel in lib.album_files(name):
            inside = rel.split("/", 1)[1] if "/" in rel else rel
            if inside not in keys:
                out.append(name)
                break
    return out


def cfg_scratch(cfg: Config) -> Path:
    return cfg.scratch or Path(os.environ.get("TMPDIR", "/tmp")) / "marimo-sync"


class Handler(BaseHTTPRequestHandler):
    server_version = "marimo-sync"
    protocol_version = "HTTP/1.1"

    # ------------------------------------------------------------ plumbing
    @property
    def state(self) -> State:
        return self.server.state            # type: ignore[attr-defined]

    def log_message(self, fmt: str, *args) -> None:
        if self.server.quiet:               # type: ignore[attr-defined]
            return
        _say(f"  {self.address_string()}  {fmt % args}", flush=True)

    def _authorised(self, query: dict) -> bool:
        token = self.state.token
        if token is None:
            return True
        given = self.headers.get(TOKEN_HEADER) or (query.get("token") or [None])[0]
        return given == token

    def _send(self, code: int, body: bytes, ctype: str, extra: dict | None = None) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, code: int, payload: dict) -> None:
        self._send(code, json.dumps(payload, indent=1).encode(), "application/json; charset=utf-8")

    def _error(self, code: int, message: str) -> None:
        self._json(code, {"error": message})

    # --------------------------------------------------------------- paths
    def _album_and_file(self, rest: str) -> tuple[str, str] | None:
        """Split and validate an /api/file/<album>/<name> path.

        Both halves are percent-decoded, then checked: no absolute paths, no
        `..`, and the resolved path must still be inside the library. Serving
        arbitrary files off someone's disk because they typed `..` is the one
        mistake here that would actually matter.
        """
        parts = rest.split("/", 1)
        if len(parts) != 2 or not parts[0] or not parts[1]:
            return None
        album, name = unquote(parts[0]), unquote(parts[1])
        for piece in (album, name):
            if piece.startswith("/") or piece in ("..", ".") or "/../" in f"/{piece}/" \
                    or piece.startswith("../") or piece.endswith("/.."):
                return None
            if "\0" in piece:
                return None
        target = (self.state.cfg.source / album / name).resolve()
        root = self.state.cfg.source.resolve()
        if not str(target).startswith(str(root) + os.sep):
            return None
        return album, name

    # ----------------------------------------------------------------- get
    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        path = parsed.path

        if path in ("/", "/index.html"):
            return self._landing()

        if not path.startswith("/api/"):
            return self._error(404, "not found")
        if not self._authorised(query):
            return self._error(401, f"missing or wrong {TOKEN_HEADER}")

        state = self.state
        state.requests += 1

        if path == "/api/health":
            with state.prepare_lock:
                prep_now = dict(state.preparing)
            return self._json(200, {"ok": True, "name": "marimo-sync", "version": VERSION,
                                    "preparing": prep_now})

        if path == "/api/index.json":
            return self._index(query)

        if path == "/api/manifest.json":
            p = inventory_path(state.cfg)
            if not p.is_file():
                return self._json(200, {"version": VERSION, "albums": {},
                                        "note": "no inventory reported yet"})
            return self._send(200, p.read_bytes(), "application/json; charset=utf-8")

        if path == "/api/album/" and parsed.path.endswith(".tar"):
            return self._error(404, "not found")

        if path.startswith("/api/album/"):
            name = unquote(path[len("/api/album/"):])
            if name.endswith(".tar"):
                name = name[:-4]
            return self._album_tar(name)

        if path.startswith("/api/file/"):
            got = self._album_and_file(path[len("/api/file/"):])
            if not got:
                return self._error(400, "bad path")
            return self._file(*got)

        return self._error(404, "not found")

    do_HEAD = do_GET

    # ------------------------------------------------------------ responses
    def _landing(self) -> None:
        lib = self.state.library()
        albums = len(lib.albums)
        total = sum(f.size for f in lib.files.values())
        url = f"http://{self.headers.get('Host', '')}"
        html = f"""<!doctype html><meta charset=utf-8>
<title>marimo sync</title>
<style>
 body {{ background:#14171a; color:#d6d9d6; font:14px/1.5 system-ui,sans-serif;
        max-width:44em; margin:6vh auto; padding:0 1.5em; }}
 code {{ background:#20262b; padding:.15em .4em; border-radius:3px; color:#8fbf6a; }}
 a {{ color:#7fa8c9; }} .dim {{ color:#8b9490; }}
</style>
<h2>marimo sync</h2>
<p>Serving <b>{albums}</b> albums &middot; {total / 2**30:.1f} GB to your phone.</p>
<p class="dim">Open <b>marimo</b> on the phone and use <b>Sync from desktop</b>,
pointing it at <code>{url}</code>.</p>
<p class="dim">If it asks for a token, it's in the terminal where this is running.</p>
<p class="dim">Endpoints: <code>/api/index.json</code>,
<code>/api/album/&lt;name&gt;.tar</code>, <code>/api/file/&lt;album&gt;/&lt;file&gt;</code></p>
"""
        self._send(200, html.encode(), "text/html; charset=utf-8")

    def _index(self, query: dict) -> None:
        want_hashes = (query.get("hashes") or ["0"])[0] in ("1", "true", "yes")
        lib = self.state.library(force=(query.get("refresh") or ["0"])[0] in ("1", "true"))
        albums = []
        for name in sorted(lib.albums):
            entries = []
            for rel in lib.album_files(name):
                info = lib.files[rel]
                inside = rel.split("/", 1)[1] if "/" in rel else rel
                entry = {"name": inside, "size": info.size}
                if want_hashes:
                    entry["sha256"] = lib.sha(rel)
                entries.append(entry)
            albums.append({"name": name, "files": entries})
        self._json(200, {
            "version": VERSION,
            "generated": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
            "hashes": want_hashes,
            "library": {"albums": len(lib.albums), "files": len(lib.files),
                        "bytes": sum(f.size for f in lib.files.values())},
            "albums": albums,
        })

    def _album_tar(self, album: str) -> None:
        lib = self.state.library()
        if album not in lib.albums:
            # Not built yet -- but if the originals are there, build it and tell the
            # phone to come back rather than 404ing at it. This is the per-album half
            # of "tell the desktop to convert, wait, then pull".
            if self.state.prepare([album]):
                return self._json(202, {"preparing": True, "album": album,
                                        "note": "converting from the originals; ask again"
                                                " when /api/health says ready"})
            return self._error(404, f"no album named {album!r}")
        path, size = self.state.album_tar(album, lib)
        self._serve_path(path, size, "application/x-tar",
                         extra={"X-Marimo-Album": album})

    def _file(self, album: str, name: str) -> None:
        lib = self.state.library()
        path = lib.root / album / name
        if not path.is_file() or name not in {r.split("/", 1)[-1] for r in lib.album_files(album)}:
            return self._error(404, "no such file in the library")
        self._serve_path(path, path.stat().st_size, "application/octet-stream")

    def _serve_path(self, path: Path, size: int, ctype: str, extra: dict | None = None) -> None:
        """Send a file, honouring a single Range header so downloads resume."""
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        partial = False
        if rng and rng.startswith("bytes="):
            spec = rng[len("bytes="):].split(",")[0].strip()
            head, _, tail = spec.partition("-")
            try:
                if head:
                    start = int(head)
                    end = int(tail) if tail else size - 1
                elif tail:                       # "-N" = the last N bytes
                    start, end = max(0, size - int(tail)), size - 1
                partial = True
            except ValueError:
                start, end = 0, size - 1
                partial = False
        if start >= size or start < 0:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{size}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        end = min(end, size - 1)
        length = end - start + 1

        self.send_response(206 if partial else 200)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if partial:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command == "HEAD":
            return
        remaining = length
        with open(path, "rb") as fh:
            fh.seek(start)
            while remaining > 0:
                chunk = fh.read(min(1 << 16, remaining))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    return
                remaining -= len(chunk)
        with self.state.lock:
            self.state.bytes_sent += length

    # ---------------------------------------------------------------- post
    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        if not self._authorised(query):
            return self._error(401, f"missing or wrong {TOKEN_HEADER}")
        if parsed.path not in ("/api/manifest", "/api/prepare"):
            return self._error(404, "not found")

        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > 64 * 1024 * 1024:
            return self._error(400, "no or oversized body")
        raw = self.rfile.read(length)
        try:
            data = json.loads(raw)
        except ValueError as e:
            return self._error(400, f"not JSON: {e}")
        if not isinstance(data, dict):
            return self._error(400, "expected a JSON object")

        # "convert these for me" -- what the phone sends when it wants an album the
        # library hasn't got yet. The work starts in the background and the caller
        # watches /api/health until it says ready; nothing here blocks on a convert.
        if parsed.path == "/api/prepare":
            wanted = data.get("albums")
            if not isinstance(wanted, list) or not all(isinstance(a, str) for a in wanted):
                return self._error(400, 'expected {"albums": ["album name", ...]}')
            started = self.state.prepare(list(wanted))
            state = (self.state.preparing or {}).get("state")
            if not self.state.prepare_ready:
                return self._json(200, {"preparing": 0, "state": "off",
                                        "note": "preparing is switched off here"})
            _say(f"  asked to prepare {len(wanted)} album(s): {'yes' if started else 'already busy'}",
                 flush=True)
            return self._json(202 if started else 200,
                              {"preparing": started, "albums": wanted, "state": state})

        if not isinstance(data.get("albums"), dict):
            return self._error(400, "expected an object with an 'albums' map")

        p = inventory_path(self.state.cfg)
        p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.with_suffix(".tmp")
        data.setdefault("version", VERSION)
        data["reported"] = datetime.datetime.now().astimezone().isoformat(timespec="seconds")
        tmp.write_text(json.dumps(data, indent=1, sort_keys=True))
        os.replace(tmp, p)
        albums = len(data["albums"])
        files = sum(len(a.get("files", {})) for a in data["albums"].values())
        _say(f"  inventory received: {albums} albums, {files} files -> {p}", flush=True)

        # The phone has just said what it has. That IS the instruction, and it means
        # two things: refresh whatever it's missing from albums we already hold, and
        # bring in anything sitting in the originals that we've never converted --
        # otherwise a phone can only ever fetch music the library already had, and
        # "the desktop prepares what the phone needs" would be a lie for new albums.
        started = 0
        try:
            lib = self.state.library(force=True)
            wanted = albums_they_lack(lib, data["albums"])
            pending = [a.replace("[FLAC]", "[OPUS]")
                       for a in prep.convert_targets(self.state.cfg, lib)]
            todo = sorted(set(wanted) | set(pending))
            if todo:
                _say(f"  phone is missing {len(wanted)} album(s), "
                     f"{len(pending)} waiting to be converted; preparing", flush=True)
            if self.state.prepare(todo):
                started = len(todo)
        except Exception as e:
            _say(f"  (couldn't work out what to prepare: {e})", flush=True)

        self._json(200, {"ok": True, "albums": albums, "files": files,
                         "preparing": started})


def serve(cfg: Config, host: str | None = None, port: int | None = None,
          token: str | None = None, allow_hashes: bool = False, quiet: bool = False,
          prepare: bool | None = None, prepare_now: bool = False,
          ready: callable | None = None) -> ThreadingHTTPServer:
    """Start the server and return it (the caller decides when to stop)."""
    settings = cfg.serve
    host = host or settings.get("bind") or "0.0.0.0"
    if port is None:
        port = int(settings.get("port") or 8422)      # 0 means "whatever's free"
    if token == "":
        token = None                      # --no-auth, or an explicit empty token
    elif token is None:
        configured = settings.get("token", None)
        if configured == "":
            token = None                  # config says no auth
        elif configured:
            token = configured
        else:
            token = hashlib.sha256(os.urandom(32)).hexdigest()[:12]   # a fresh one per run

    state = State(cfg, token, allow_hashes)
    # preparing is the point when a phone is pulling, but it writes (converts, shades)
    # and so must be switchable off -- for a test, or a library you'd rather keep still
    state.prepare_ready = bool(prepare) if prepare is not None else bool(
        settings.get("prepare", True))
    httpd = ThreadingHTTPServer((host, port), Handler)
    httpd.state = state            # type: ignore[attr-defined]
    httpd.quiet = quiet            # type: ignore[attr-defined]
    httpd.daemon_threads = True

    address = lan_address() if host in ("0.0.0.0", "::") else host
    if prepare_now and state.prepare_ready:
        # before a byte is served: the library should already be what the phone wants
        if not quiet:
            _say("  getting the library ready first (convert, waveforms, art)")
        state.prepare_everything()
    if not quiet:
        _say(f"serving {cfg.source}")
        _say(f"  library   {len(state.library().albums)} albums")
        _say(f"  url       http://{address}:{httpd.server_address[1]}")
        if token:
            _say(f"  token     {token}")
        _say(f"  local     http://127.0.0.1:{httpd.server_address[1]}")
        _say("  open marimo on the phone -> Sync from desktop.  ^C to stop.")
    elif token:
        # a caller that asked for quiet still needs the token if it wants to talk
        _say(f"token {token}", file=sys.stderr)
    if ready:
        ready(httpd)
    return httpd
