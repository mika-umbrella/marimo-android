#!/usr/bin/env python3
"""Check the serve side actually works: the desktop half of "the phone pulls".

Starts the real server against a synthetic library and talks to it exactly the way
the Android app will — index, ranged download, whole-album tar, and reporting an
inventory back — then checks that a plain `marimo-sync status --device http://…`
reads that report and gets the right answer.

    python3 tests/serve_smoke.py
"""

from __future__ import annotations

import base64
import hashlib
import io
import json
import shutil
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent
sys.path.insert(0, str(PROJECT))
sys.path.insert(0, str(HERE))

import fixture  # noqa: E402
from marimosync.config import Config  # noqa: E402
from marimosync.serve import serve  # noqa: E402

SCRATCH = Path.home() / "mika" / "tmp" / "serve-smoke"
TOKEN = "test-token-please"
FAILED: list[str] = []
N = 0


def check(name: str, ok: bool, detail: str = "") -> None:
    global N
    N += 1
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"  -- {detail}" if detail and not ok else ""))
    if not ok:
        FAILED.append(name)


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def get(url: str, token: str | None = TOKEN, headers: dict | None = None):
    req = urllib.request.Request(url, headers=headers or {})
    if token:
        req.add_header("X-Marimo-Token", token)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def post(url: str, payload: dict, token: str | None = TOKEN):
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("X-Marimo-Token", token)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def library_snapshot(root: Path) -> dict:
    """Every file with its size and mtime — so 'nothing was written' is checkable."""
    return {str(p.relative_to(root)): (p.stat().st_size, p.stat().st_mtime_ns)
            for p in root.rglob("*") if p.is_file()}


def main() -> int:
    if SCRATCH.exists():
        shutil.rmtree(SCRATCH)
    SCRATCH.mkdir(parents=True)
    lib = SCRATCH / "lib"
    albums = fixture.build_library(lib)
    before = library_snapshot(lib)          # the library as it stands right now
    print(f"synthetic library: {len(albums)} albums, "
          f"{sum(1 for _ in lib.rglob('*') if _.is_file())} files\n")

    cfg_file = SCRATCH / "config.json"
    cfg_file.write_text(json.dumps({
        "source": str(lib),
        "device": "http://127.0.0.1:0",          # replaced below with the real port
        "root": "/sdcard/Music",
        # Pinned to nothing on purpose. Left unset, flac_source falls back to ~/Music
        # and convert_script to the bundled converter -- so when the server started
        # preparing on a phone's report, this suite converted *real* albums into its
        # fixture library. A test that can write must not be able to reach her music.
        "flac_source": str(SCRATCH / "no-such-source"),
        "convert_script": "none",
        "wave_script": "none",
        # The diary endpoints write to a *real* live diary if this is left unset --
        # it would default to ~/.config/marimo, i.e. Nova's own listening history.
        # Named as a fixture, explicitly, in the code that writes the config.
        "diary": str(SCRATCH / "diary"),
        "diary_in": str(SCRATCH / "diary-in"),
        "diary_out": str(SCRATCH / "diary-out"),
        "serve": {"state": str(SCRATCH / "inventory.json"), "prepare": False, "diary": True},
    }))
    cfg = Config.load(path=cfg_file)

    httpd = serve(cfg, host="127.0.0.1", port=0, token=TOKEN, quiet=True)
    port = httpd.server_address[1]
    base = f"http://127.0.0.1:{port}"
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    print(f"server up on {base}\n")

    try:
        print("1. auth and health")
        code, _, _ = get(f"{base}/api/health", token=None)
        check("a request with no token is refused", code == 401, str(code))
        code, _, _ = get(f"{base}/api/health", token="wrong")
        check("a wrong token is refused", code == 401, str(code))
        code, _, body = get(f"{base}/api/health")
        check("the right token works", code == 200 and json.loads(body)["name"] == "marimo-sync",
              f"{code} {body[:60]}")
        code, _, body = get(f"{base}/api/health?token={TOKEN}", token=None)
        check("the token can also come in the query string", code == 200, str(code))

        print("\n2. the index is the contract the app codes against")
        code, _, body = get(f"{base}/api/index.json")
        index = json.loads(body)
        check("every album is listed", len(index["albums"]) == len(albums),
              f"{len(index['albums'])} vs {len(albums)}")
        check("hashes are off by default", "sha256" not in index["albums"][0]["files"][0])
        listed = {a["name"]: a for a in index["albums"]}
        real = {a: sorted(str(p.relative_to(lib / a)) for p in (lib / a).rglob("*") if p.is_file())
                for a in albums}
        ok_sizes = True
        for name, entry in listed.items():
            for f in entry["files"]:
                local = lib / name / f["name"]
                if not local.is_file() or local.stat().st_size != f["size"]:
                    ok_sizes = False
        check("every listed file exists locally at the listed size", ok_sizes)
        check("the index names match the library's own relative paths",
              sorted(f["name"] for f in listed[fixture.DISC_ALBUM]["files"])
              == real[fixture.DISC_ALBUM],
              f"{sorted(f['name'] for f in listed[fixture.DISC_ALBUM]['files'])}"
              f" vs {real[fixture.DISC_ALBUM]}")

        code, _, body = get(f"{base}/api/index.json?hashes=1")
        hashed = json.loads(body)
        sample_album = hashed["albums"][0]
        sample = sample_album["files"][0]
        check("hashes on request match the local bytes",
              sample["sha256"] == sha(lib / sample_album["name"] / sample["name"]),
              sample["sha256"][:12])

        print("\n3. one file, and resuming it")
        album, fname = sample_album["name"], sample["name"]
        code, headers, body = get(f"{base}/api/file/{urllib.parse.quote(album)}/{urllib.parse.quote(fname)}")
        check("a whole file comes back byte-identical",
              code == 200 and hashlib.sha256(body).hexdigest() == sha(lib / album / fname), str(code))
        check("it advertises range support", headers.get("Accept-Ranges") == "bytes",
              str(headers.get("Accept-Ranges")))

        whole = body
        code, headers, first = get(f"{base}/api/file/{urllib.parse.quote(album)}/{urllib.parse.quote(fname)}",
                                  headers={"Range": "bytes=0-9"})
        check("a range request is a 206", code == 206, str(code))
        check("the range is the right length", len(first) == 10, str(len(first)))
        check("Content-Range is correct", headers.get("Content-Range") == f"bytes 0-9/{len(whole)}",
              str(headers.get("Content-Range")))
        check("the ranged bytes are the right ones", first == whole[:10],
              f"{first[:10]!r} vs {whole[:10]!r}")

        cut = len(whole) // 2
        _, _, a = get(f"{base}/api/file/{urllib.parse.quote(album)}/{urllib.parse.quote(fname)}",
                      headers={"Range": f"bytes=0-{cut - 1}"})
        _, _, b = get(f"{base}/api/file/{urllib.parse.quote(album)}/{urllib.parse.quote(fname)}",
                      headers={"Range": f"bytes={cut}-"})
        check("two halves resume into the whole file", a + b == whole,
              f"{len(a)}+{len(b)} vs {len(whole)}")
        code, _, _ = get(f"{base}/api/file/{urllib.parse.quote(album)}/{urllib.parse.quote(fname)}",
                         headers={"Range": f"bytes={len(whole) + 5}-"})
        check("a range past the end is refused, not faked", code == 416, str(code))

        print("\n4. a whole album as one tar")
        code, headers, body = get(f"{base}/api/album/{urllib.parse.quote(fixture.DISC_ALBUM)}.tar")
        check("the tar comes back", code == 200 and headers.get("Content-Type") == "application/x-tar",
              str(code))
        with tempfile.TemporaryDirectory() as tmp:
            tarpath = Path(tmp) / "a.tar"
            tarpath.write_bytes(body)
            with tarfile.open(tarpath) as tf:
                tf.extractall(Path(tmp) / "out", filter="data")
            got = sorted(str(p.relative_to(Path(tmp) / "out")) for p in (Path(tmp) / "out").rglob("*")
                         if p.is_file())
            want = sorted(str(p.relative_to(lib / fixture.DISC_ALBUM))
                          for p in (lib / fixture.DISC_ALBUM).rglob("*") if p.is_file())
            check("the tar holds exactly the album's files", got == want, f"{got} vs {want}")
            check("the tar names files exactly as the index does",
                  set(got) == {f["name"] for f in listed[fixture.DISC_ALBUM]["files"]},
                  f"{sorted(got)}")
            same = all(
                (Path(tmp) / "out" / rel).read_bytes()
                == (lib / fixture.DISC_ALBUM / rel).read_bytes() for rel in want)
            check("and their bytes are intact", same)
        code, _, body = get(f"{base}/api/album/{urllib.parse.quote(fixture.DISC_ALBUM)}.tar",
                            headers={"Range": "bytes=0-511"})
        check("album tars are resumable too", code == 206 and len(body) == 512, str(code))

        print("\n5. it doesn't serve anything outside the library")
        for attempt in ("/api/file/..%2f..%2fetc/passwd",
                        "/api/file/%2e%2e/%2e%2e/etc/passwd",
                        "/api/file/" + urllib.parse.quote(albums[0]) + "/" + urllib.parse.quote("../../../etc/passwd"),
                        "/api/album/..%2f..%2fetc.tar"):
            code, _, _ = get(base + attempt)
            check(f"refused: {attempt[:46]}", code in (400, 404), str(code))

        print("\n6. an unknown album is a 404, not an empty tar")
        code, _, _ = get(f"{base}/api/album/Nope%20-%20(1999)%20Missing.tar")
        check("unknown album 404s", code == 404, str(code))
        code, _, _ = get(f"{base}/api/file/{urllib.parse.quote(albums[0])}/not-in-this-album.opus")
        check("unknown file 404s", code == 404, str(code))

        print("\n7. the phone reports what it has, and the desktop believes it")
        # the phone says it has the first album (with the hashes it computed itself),
        # plus one thing the library has never heard of
        hashed_album = next(a for a in hashed["albums"] if a["name"] == albums[0])
        reported = {"root": "/sdcard/Music/Chunes", "albums": {
            albums[0]: {"files": {f["name"]: {"size": f["size"], "sha256": f["sha256"]}
                                  for f in hashed_album["files"]}},
            "Ghost Artist - (1999) Lost [OPUS]": {"files": {"01. gone.opus": {"size": 15}}},
        }}
        code, body = post(f"{base}/api/manifest", reported)
        check("the report is accepted", code == 200, f"{code} {body[:80]}")
        code, _, body = get(f"{base}/api/manifest.json")
        check("it's kept and served back", json.loads(body)["albums"].keys().__len__() == 2,
              body[:80])

        cfg_file.write_text(json.dumps({
            "source": str(lib), "device": base, "root": "/sdcard/Music/Chunes",
            "serve": {"state": str(SCRATCH / "inventory.json")},
        }))
        r = subprocess.run([str(PROJECT / "marimo-sync"), "--config", str(cfg_file), "--plain",
                            "--json", "status"], capture_output=True, text=True)
        check("status against an http device works", r.returncode == 0, r.stderr[:200])
        if r.returncode == 0:
            plan = json.loads(r.stdout)
            by_name = {a["name"]: a for a in plan["albums"]}
            check("the album the phone reported is in sync",
                  by_name[albums[0]]["status"] == "ok", by_name[albums[0]]["status"])
            check("an album the phone didn't report is one to fetch",
                  by_name[albums[2]]["status"] == "new", by_name[albums[2]]["status"])
            check("something on the phone but not in the library is an orphan",
                  by_name["Ghost Artist - (1999) Lost [OPUS]"]["status"] == "orphan")

        r = subprocess.run([str(PROJECT / "marimo-sync"), "--config", str(cfg_file), "--plain",
                            "sync"], capture_output=True, text=True)
        check("sync refuses to push at an http device, and says what to do",
              r.returncode == 2 and "serve" in (r.stdout + r.stderr),
              f"{r.returncode} {r.stdout[-120:]}{r.stderr[-120:]}")
    finally:
        httpd.shutdown()
        httpd.server_close()

    print("\n8. and it never writes into the library")
    after = library_snapshot(lib)
    changed = {k for k in set(before) | set(after) if before.get(k) != after.get(k)}
    check("no file in the library was added, removed or touched", not changed,
          str(sorted(changed)[:4]))
    inv = Path(json.loads(cfg_file.read_text())["serve"]["state"])
    check("the phone's inventory goes to the cache dir, not the library",
          not str(inv.resolve()).startswith(str(lib.resolve())), str(inv))
    check("and the inventory really is there", inv.is_file(), str(inv))

    print("\n9. the exchange does the work: the phone reports, the desktop prepares")
    # Its own fixture library on purpose: this section *writes* (it converts an album
    # in), and section 8's promise is that serving never touches the library unasked.
    ex = SCRATCH / "exchange"
    lib2, src2 = ex / "lib", ex / "flac"
    already = fixture.build_library(lib2, [fixture.ALBUMS[0]])[0]
    fresh = "New Artist - (2026) Fresh [OPUS]"
    orig = src2 / "New Artist - (2026) Fresh [FLAC]"
    fixture.tone(orig / "01. brand new.flac", 440)
    fixture.tone(orig / "02. also new.flac", 520)
    fixture.cover(orig / "cover.jpg")

    # stubs with the real scripts' interface: --src/--dst/--one for convert,
    # a list of album folders for make-waveforms
    stub_convert = ex / "stub-convert.py"
    stub_convert.write_text(
        "#!/usr/bin/env python3\n"
        "import argparse, shutil\n"
        "from pathlib import Path\n"
        "p = argparse.ArgumentParser()\n"
        "p.add_argument('--src'); p.add_argument('--dst')\n"
        "p.add_argument('--one'); p.add_argument('--dry', action='store_true')\n"
        "a = p.parse_args()\n"
        "if a.dry:\n    raise SystemExit(0)\n"
        "dst = Path(a.dst) / a.one.replace('[FLAC]', '[OPUS]')\n"
        "dst.mkdir(parents=True, exist_ok=True)\n"
        "for f in sorted((Path(a.src) / a.one).iterdir()):\n"
        "    shutil.copy2(f, dst / f.name)\n"
        "print('converted', a.one)\n")
    stub_convert.chmod(0o755)

    stub_wave = ex / "stub-wave.py"
    stub_wave.write_text(
        "#!/usr/bin/env python3\n"
        "import struct, sys, unicodedata\n"
        "from pathlib import Path\n"
        "for d in sys.argv[1:]:\n"
        "    album = Path(d)\n"
        "    names = sorted(p.name for p in album.iterdir()\n"
        "                   if p.suffix.lower() in ('.opus', '.mp3', '.m4a', '.flac'))\n"
        "    out = bytearray(b'MWAVS001') + struct.pack('<I', len(names))\n"
        "    for n in names:\n"
        "        raw = unicodedata.normalize('NFC', n).encode('utf-8')\n"
        "        out += struct.pack('<H', len(raw)) + raw + bytes([60] * 96)\n"
        "    (album / 'waves.marimo').write_bytes(bytes(out))\n"
        "    print('shaded', album.name)\n")
    stub_wave.chmod(0o755)

    cfg2_file = ex / "config.json"
    cfg2_file.write_text(json.dumps({
        "source": str(lib2), "device": "http://127.0.0.1:0", "root": "/sdcard/Music/Chunes",
        "flac_source": str(src2), "convert_script": str(stub_convert),
        "wave_script": str(stub_wave),
        "serve": {"state": str(ex / "inventory.json"), "prepare": True},
    }))
    httpd2 = serve(Config.load(path=cfg2_file), host="127.0.0.1", port=0, token=TOKEN,
                   quiet=True)
    base2 = f"http://127.0.0.1:{httpd2.server_address[1]}"
    threading.Thread(target=httpd2.serve_forever, daemon=True).start()
    print(f"  server up on {base2}")

    try:
        check("the album the phone is missing isn't in the library yet",
              not (lib2 / fresh).exists(), str(sorted(p.name for p in lib2.iterdir()))[:120])

        code, body = post(f"{base2}/api/manifest", {"albums": {already: {"files": {}}}})
        reply = json.loads(body) if code == 200 else {}
        check("the phone's report is taken as the instruction", code == 200, f"{code} {body[:90]}")
        check("and the desktop answers with what it will prepare",
              reply.get("preparing", 0) >= 1, body[:140])

        # wait for it the way the phone will: poll health, don't guess
        deadline = time.monotonic() + 90
        state = ""
        while time.monotonic() < deadline:
            _, _, hb = get(f"{base2}/api/health")
            state = (json.loads(hb).get("preparing") or {}).get("state", "")
            if state in ("ready", "failed"):
                break
            time.sleep(0.5)
        check("the preparation finishes", state == "ready", state)
        check("the album that existed only as originals is now in the library",
              (lib2 / fresh).is_dir(), str(sorted(p.name for p in lib2.iterdir()))[:160])
        check("with the waveform sidecar it never had",
              (lib2 / fresh / "waves.marimo").is_file(), "")
        check("and its art", (lib2 / fresh / "cover.jpg").is_file(), "")

        code, _, body = get(f"{base2}/api/index.json")
        names_now = {a["name"] for a in json.loads(body)["albums"]}
        check("so the phone can see it in the index now", fresh in names_now,
              str(sorted(names_now))[:160])

        code, _, body = get(f"{base2}/api/album/{urllib.parse.quote(fresh)}.tar")
        members: list[str] = []
        if code == 200:
            with tarfile.open(fileobj=io.BytesIO(body)) as tf:
                members = sorted(tf.getnames())
        check("and fetching it gets the audio and the sidecar together",
              code == 200 and "waves.marimo" in members
              and any(m.endswith((".flac", ".opus")) for m in members),
              f"{code} {members[:6]}")
    finally:
        httpd2.shutdown()
        httpd2.server_close()

    print("\n10. asking for an album that isn't built yet — what the phone's Fetch does")
    # A source-only album, so we can ask for one that exists nowhere in the library yet.
    later = "Later Artist - (2027) Not Yet [OPUS]"
    later_src = src2 / "Later Artist - (2027) Not Yet [FLAC]"
    fixture.tone(later_src / "01. later on.flac", 610)
    fixture.cover(later_src / "cover.jpg")

    httpd3 = serve(Config.load(path=cfg2_file), host="127.0.0.1", port=0, token=TOKEN,
                   quiet=True)
    base3 = f"http://127.0.0.1:{httpd3.server_address[1]}"
    threading.Thread(target=httpd3.serve_forever, daemon=True).start()
    try:
        check("the album isn't in the library to begin with", not (lib2 / later).exists())

        code, _, body = get(f"{base3}/api/album/{urllib.parse.quote(later)}.tar")
        check("fetching it says 'converting, ask again' rather than 404",
              code == 202, f"{code} {body[:90]}")

        deadline = time.monotonic() + 90
        state = ""
        while time.monotonic() < deadline:
            _, _, hb = get(f"{base3}/api/health")
            state = (json.loads(hb).get("preparing") or {}).get("state", "")
            if state in ("ready", "failed"):
                break
            time.sleep(0.5)
        check("the desktop builds it after being asked", (lib2 / later).is_dir(), state)

        code, _, body = get(f"{base3}/api/album/{urllib.parse.quote(later)}.tar")
        members = []
        if code == 200:
            with tarfile.open(fileobj=io.BytesIO(body)) as tf:
                members = sorted(tf.getnames())
        check("and the same fetch now returns it, sidecar included",
              code == 200 and "waves.marimo" in members
              and any(m.endswith((".flac", ".opus")) for m in members),
              f"{code} {members[:5]}")

        code, body = post(f"{base3}/api/prepare", {"albums": []})
        check("an empty prepare request is a no-op, not an error", code == 200, f"{code} {body[:80]}")

        code, body = post(f"{base3}/api/prepare", {"albums": "not-a-list"})
        check("and a malformed one is refused clearly", code == 400, f"{code} {body[:80]}")
    finally:
        httpd3.shutdown()
        httpd3.server_close()

    print("\n11. the diary over the same link: POST it in, GET the union back")
    # The config at the top names `diary`/`diary_in`/`diary_out` inside SCRATCH on
    # purpose: this section writes a *diary*, and an unset `diary` would mean Nova's
    # own listening history.
    def dline(ts: int, artist: str, title: str, sec: int = 100) -> bytes:
        return json.dumps({"ts": ts, "artist": artist, "album": "Serve", "title": title,
                           "sec": sec, "dur": 1000}, ensure_ascii=False,
                          separators=(",", ":")).encode()

    def wire(name: str, blob: bytes) -> dict:
        """One entry of the app's `files` array: base64, inline."""
        return {"name": name, "size": len(blob), "b64": base64.b64encode(blob).decode()}

    dfile = SCRATCH / "diary" / "history.jsonl"
    dfile.parent.mkdir(parents=True, exist_ok=True)
    now_ms = int(time.time() * 1000)
    mine = dline(now_ms - 7200_000, "Desk", "ours")
    theirs = dline(now_ms - 3600_000, "Phone", "theirs")
    dfile.write_bytes(mine + b"\n")
    before_diary = dfile.read_bytes()

    httpd4 = serve(cfg, host="127.0.0.1", port=0, token=TOKEN, quiet=True)
    base4 = f"http://127.0.0.1:{httpd4.server_address[1]}"
    threading.Thread(target=httpd4.serve_forever, daemon=True).start()
    try:
        code, body = post(f"{base4}/api/diary",
                          {"version": 1, "files": [wire("history.jsonl", theirs + b"\n")]},
                          token=None)
        check("posting a diary without a token is refused", code == 401, f"{code} {body[:80]}")

        code, _, body = get(f"{base4}/api/diary")
        check("the merged diary is fetchable and is this file's bytes",
              code == 200 and body == before_diary, f"{code} {body[:80]}")

        code, body = post(f"{base4}/api/diary",
                          {"version": 1, "files": [wire("history.jsonl", b'{"ts":123,\n')]})
        check("a diary the merge refuses comes back as an error, not a 200",
              code == 422, f"{code} {body[:120]}")
        check("and it changed nothing", dfile.read_bytes() == before_diary, "")

        code, body = post(f"{base4}/api/diary",
                          {"version": 1, "files": [wire("history.jsonl", theirs + b"\n")]})
        reply = json.loads(body) if code == 200 else {}
        check("a real diary is merged and the reply says what it did",
              code == 200 and reply.get("ok") is True, f"{code} {body[:160]}")
        check("the reply's numbers are the merge's own",
              reply.get("before") == 1 and reply.get("after") == 2
              and reply.get("added") == 1 and reply.get("duplicates") == 0,
              json.dumps(reply)[:200])
        check("the merged file really holds both lines, ts-ordered",
              dfile.read_bytes() == mine + b"\n" + theirs + b"\n",
              dfile.read_bytes()[:160].decode(errors="replace"))
        check("the reply's digest is the merged file's",
              reply.get("digest") == sha(dfile), f"{reply.get('digest')} vs {sha(dfile)}")

        back = {f["name"]: f for f in reply.get("files", [])}
        check("and it hands the merged bytes straight back, base64, byte for byte",
              "history.jsonl" in back
              and base64.b64decode(back["history.jsonl"]["b64"]) == dfile.read_bytes()
              and back["history.jsonl"]["size"] == dfile.stat().st_size,
              str(sorted(back)))
        check("only diary names come back in `files`",
              all(n == "history.jsonl"
                  or (n.startswith("history.") and n.endswith(".jsonl.gz")) for n in back),
              str(sorted(back)))

        record = json.loads((SCRATCH / "diary-last-merge.json").read_text())
        check("what the merge did is written where the window can read it",
              record.get("added") == 1 and record.get("lines_after") == 2
              and record.get("sha256") == sha(dfile) and record.get("posted_lines") == 1,
              json.dumps(record)[:200])

        code, _, body = get(f"{base4}/api/diary")
        check("GET hands back the merged union", body == dfile.read_bytes(), body[:80])

        code, body = post(f"{base4}/api/diary", {"version": 1, "files": []})
        fresh = json.loads(body) if code == 200 else {}
        check("an empty files list is 'nothing from me', not a bad request",
              code == 200 and fresh.get("added") == 0, f"{code} {body[:160]}")
        fresh_back = {f["name"]: f for f in fresh.get("files", [])}
        check("and a fresh phone still gets the merged diary back",
              base64.b64decode(fresh_back["history.jsonl"]["b64"]) == dfile.read_bytes(), "")

        code, body = post(f"{base4}/api/diary",
                          {"version": 1,
                           "files": [wire("notes.txt", b"hello"),
                                     wire("history.jsonl", theirs + b"\n")]})
        mixed = json.loads(body) if code == 200 else {}
        check("a name that is not a diary file is ignored, not merged",
              code == 200 and mixed.get("ignored") == ["notes.txt"], json.dumps(mixed)[:160])

        code, body = post(f"{base4}/api/diary",
                          {"version": 1, "files": [wire("history.jsonl", theirs + b"\n")]})
        second = json.loads(body) if code == 200 else {}
        check("posting the same diary again adds nothing (the fixed point, over the wire)",
              code == 200 and second.get("added") == 0, json.dumps(second)[:200])
        check("and the file is byte-identical after the second post",
              dfile.read_bytes() == mine + b"\n" + theirs + b"\n", "")

        code, body = post(f"{base4}/api/nonesuch", {"files": []})
        check("an endpoint this desktop hasn't got is a 404, so the app can read version skew",
              code == 404, f"{code} {body[:80]}")
    finally:
        httpd4.shutdown()
        httpd4.server_close()

    off_cfg = SCRATCH / "config-off.json"
    off_cfg.write_text(json.dumps({
        "source": str(lib), "device": "http://127.0.0.1:0", "root": "/sdcard/Music",
        "flac_source": str(SCRATCH / "no-such-source"), "convert_script": "none",
        "wave_script": "none", "diary": str(SCRATCH / "diary"),
        "diary_in": str(SCRATCH / "diary-in"), "diary_out": str(SCRATCH / "diary-out"),
        "serve": {"state": str(SCRATCH / "inventory-off.json"), "diary": False},
    }))
    httpd5 = serve(Config.load(path=off_cfg), host="127.0.0.1", port=0, token=TOKEN,
                   quiet=True)
    base5 = f"http://127.0.0.1:{httpd5.server_address[1]}"
    threading.Thread(target=httpd5.serve_forever, daemon=True).start()
    try:
        settled = dfile.read_bytes()
        code, body = post(f"{base5}/api/diary",
                          {"version": 1,
                           "files": [wire("history.jsonl", dline(now_ms, "Off", "nope") + b"\n")]})
        check("serve.diary = false refuses the POST, with a status that is not the app's "
              "token message", code == 409, f"{code} {body[:120]}")
        check("and it wrote nothing at all", dfile.read_bytes() == settled, "")
        code, _, body = get(f"{base5}/api/diary")
        check("while reading the diary stays allowed", code == 200 and body == settled, str(code))
    finally:
        httpd5.shutdown()
        httpd5.server_close()

    print()
    if FAILED:
        print(f"{len(FAILED)}/{N} checks FAILED: " + ", ".join(FAILED))
        return 1
    print(f"all {N} checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
