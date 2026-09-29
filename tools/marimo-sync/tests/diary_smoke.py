#!/usr/bin/env python3
"""End-to-end test of `marimo-sync diary`, the phone->desktop diary merge.

Everything here happens in temp directories under ~/mika/tmp: the suite never
reads or writes the real diary, and it asserts that at the end by watching the
stat of ~/.config/marimo/history.jsonl. It also never runs the player.

The shape being checked is the whole point of the command: two append-only,
roughly-ordered files that share plays, merged into one diary without losing a
line, without rewriting a line it was only asked to carry, and without touching
anything when it cannot do that honestly.

    python3 tests/diary_smoke.py
"""

from __future__ import annotations

import datetime as dt
import gzip
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent
sys.path.insert(0, str(PROJECT))

CLI = PROJECT / "marimo-sync"
SCRATCH = Path.home() / "mika" / "tmp" / "diary-smoke"
ENV = {**os.environ,
       "XDG_CONFIG_HOME": str(SCRATCH / "xdg-config"),
       "XDG_CACHE_HOME": str(SCRATCH / "xdg-cache"),
       # diary_out's default lives under the tool's data dir, so pointing that at the
       # fixture is what keeps a forgotten --diary-out out of nova's real one
       "XDG_DATA_HOME": str(SCRATCH / "xdg-data")}

# the player's own patterns: prune() removes history.<Y>.jsonl.gz, the rollover
# removes a file named exactly history.jsonl. Ours must match neither.
PRUNE_PATTERN = re.compile(r"^history\.\d+\.jsonl\.gz$")

NOW = dt.datetime.now()
YEAR = NOW.year
LAST_YEAR = YEAR - 1          # the player keeps this year and the one before
PRUNED_YEAR = YEAR - 2        # ...and deletes anything at or below this

REAL_DIR = Path.home() / ".config" / "marimo"
REAL_DIARY = REAL_DIR / "history.jsonl"

# ---------------------------------------------------------------------------
# The cross-language contract, asserted in section 11. The merge rule lives twice --
# here and in the app's DiaryImport.java -- and these are the bytes both sides have to
# agree on. The archives are hashed *after decompression* on purpose: the .gz framing
# differs between zlib and java.util.zip, and that difference must never be mistaken
# for a difference in the rule.
CANON_LIVE = "bdb1519f5ac4a3d3f24d2b46d4bde44870fa0ce5315a00a779ead3cb6a67074f"
CANON_PREV_YEAR = "9bdf8a128ca670bb07a55a7be8c1a885caf9286cbe4544cb15738e04e7506f05"
CANON_PRUNED_YEAR = "30dd158bbd57761c79f72a38b4bf9a13aee545dac0a2ee1cce0c90f01e8e3531"
# The escaped line of the fixture, whole, so the app can build the same bytes without
# reading Python. Asserted against the fixture below, so a typo in *this* string fails
# here rather than turning into a mystery on the other side of the handoff.
#
# That is not hypothetical: on 2026-09-29 I published a hand-copied version of this hex
# with one transposed byte pair in `escaped` (6573637061706564 instead of 65736361706564),
# the Java side built its fixture from it, and its pinned test failed against
# bdb1519f... -- correctly, because at that point a *fixture* typo is indistinguishable
# from a rule divergence. The digests were right and the hex was not. Copy this from the
# machine; never retype it. If it ever disagrees with the fixture, the fixture moved.
CANON_ESCAPED_LINE_HEX = (
    "7b227473223a313737353034343830303030302c22617274697374223a22715c22625c5c5c6e63e9acb1222c22616c62756d"
    "223a2243616e6f6e222c227469746c65223a2265736361706564222c22736563223a34322c22647572223a3235393835337d")

FAILED: list[str] = []
N = 0


def check(name: str, ok: bool, detail: str = "") -> None:
    global N
    N += 1
    print(f"  {'PASS' if ok else 'FAIL'}  {name}"
          + (f"  -- {detail}" if detail and not ok else ""))
    if not ok:
        FAILED.append(name)


def skip(name: str, why: str) -> None:
    print(f"  skip  {name}  -- {why}")


# ------------------------------------------------------------------ helpers

def ms(year: int, month: int = 6, day: int = 15, hour: int = 13) -> int:
    """Epoch ms for a local wall-clock time -- the same frame the bucketing uses."""
    return int(dt.datetime(year, month, day, hour).timestamp() * 1000)


def J(ts: int, artist: str = "A", album: str = "Alb", title: str = "t",
      sec: int = 100, dur: int = 200000) -> str:
    """One diary line in exactly the player's format: key order, no spaces."""
    return json.dumps({"ts": ts, "artist": artist, "album": album, "title": title,
                       "sec": sec, "dur": dur}, ensure_ascii=False, separators=(",", ":"))


def write_lines(path: Path, lines: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"".join(l.encode() + b"\n" for l in lines))


def write_gz(path: Path, lines: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(gzip.compress(b"".join(l.encode() + b"\n" for l in lines), mtime=0))


def read_lines(path: Path) -> list[str]:
    """The file's lines as text, terminator gone -- [] if it isn't there."""
    if not path.is_file():
        return []
    return [l.decode() for l in path.read_bytes().split(b"\n") if l]


def read_gz_lines(path: Path) -> list[str]:
    return [l.decode() for l in gzip.decompress(path.read_bytes()).split(b"\n") if l]


def ts_of(line: str) -> int:
    return json.loads(line)["ts"]


def snap(root: Path) -> dict[str, str]:
    """Every file under `root`: name -> sha256. Two of these being equal is the
    only honest way to say "the directory is byte-identical"."""
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(root.rglob("*")) if p.is_file()}


def run(*args: str, env: dict | None = None) -> subprocess.CompletedProcess:
    return subprocess.run([str(CLI), "--plain", *args], capture_output=True, text=True,
                          env=env or ENV)


def merge(diary: Path, *imports: Path, extra: tuple[str, ...] = (),
          env: dict | None = None) -> subprocess.CompletedProcess:
    return run("diary", "--import", *[str(p) for p in imports], *extra,
               *(["--diary", str(diary)] if diary is not None else []), env=env)


def bak_files(diary: Path) -> list[Path]:
    return sorted(p for p in diary.iterdir() if ".bak-" in p.name) if diary.is_dir() else []


def main() -> int:
    if SCRATCH.exists():
        shutil.rmtree(SCRATCH)
    SCRATCH.mkdir(parents=True)
    real_before = REAL_DIARY.read_bytes() if REAL_DIARY.exists() else None
    real_names = sorted(p.name for p in REAL_DIR.iterdir()) if REAL_DIR.is_dir() else []
    print(f"fixture: {SCRATCH}\nyear {YEAR}, previous {LAST_YEAR}, pruned ≤{PRUNED_YEAR}\n")

    # ---------------------------------------------------------------- 1. the plan
    print("1. a fresh phone diary: the plan first, then the merge")
    desk, phone = SCRATCH / "a" / "desk", SCRATCH / "a" / "phone"
    # the desk's own file is deliberately out of ts order -- it is append-only
    desk_lines = [J(ms(YEAR, 3, 5), "鬱P", "DIARRHEA", "シューゲイズ・ライフ", 161, 259853),
                  J(ms(YEAR, 2, 1), "A", "Alb", "one", 0, 0)]
    phone_lines = [J(ms(YEAR, 4, 1), "B", "Alb2", "two", 200, 240000),
                   J(ms(YEAR, 1, 9), "C", "Alb3", "three", 240, 250000),
                   J(ms(LAST_YEAR, 11, 2), "D", "Alb4", "four"),
                   J(ms(PRUNED_YEAR, 6, 6), "E", "Alb5", "five"),
                   J(ms(YEAR, 3, 5), "鬱P", "DIARRHEA", "シューゲイズ・ライフ", 0, 259853)]
    write_lines(desk / "history.jsonl", desk_lines)
    write_lines(phone / "history.jsonl", phone_lines)
    os.utime(desk / "history.jsonl", (ms(2020, 3, 1) / 1000,) * 2)   # an old mtime on purpose
    before = (desk / "history.jsonl").read_bytes()

    r = merge(desk, phone / "history.jsonl", extra=("--dry-run",))
    out = r.stdout + r.stderr
    check("--dry-run exits 0", r.returncode == 0, f"{r.returncode} {out[-200:]}")
    check("and says nothing was written", "nothing was written" in out, out[-200:])
    check("it counts what each file brings",
          "5 lines, 5 unique, 4 added, 1 duplicate(s) skipped" in out, out)
    check("and names the file it would rewrite", "history.jsonl" in out and "would rewrite" in out,
          out)
    check("--dry-run really leaves the directory byte-identical",
          snap(desk) == {"history.jsonl": hashlib.sha256(before).hexdigest()}, str(snap(desk)))

    r = merge(desk, phone / "history.jsonl")
    out = r.stdout + r.stderr
    print(r.stdout)
    check("the merge exits 0", r.returncode == 0, f"{r.returncode} {out[-300:]}")
    current = read_lines(desk / "history.jsonl")
    check("every current-year line from both sides is in this year's log",
          sorted(current) == sorted(desk_lines + phone_lines[:2]), str(current))
    check("the lines are the bytes they arrived as -- no re-serialising",
          all(l in desk_lines + phone_lines for l in current), str(current))
    check("the file is sorted by ts", [ts_of(l) for l in current] == sorted(ts_of(l) for l in current),
          str([ts_of(l) for l in current]))
    check(f"{LAST_YEAR} landed in history.{LAST_YEAR}.jsonl.gz",
          read_gz_lines(desk / f"history.{LAST_YEAR}.jsonl.gz") == [phone_lines[2]], "")
    check(f"{PRUNED_YEAR} landed in history.{PRUNED_YEAR}.jsonl.gz",
          read_gz_lines(desk / f"history.{PRUNED_YEAR}.jsonl.gz") == [phone_lines[3]], "")
    check("a non-ASCII line survives as its own bytes",
          any("シューゲイズ・ライフ" in l and "鬱P" in l for l in current), str(current))
    check("the summary reports counts and what it wrote",
          "3 files written" in out and "backup .history.jsonl.bak-" in out
          and f"history.{LAST_YEAR}.jsonl.gz" in out, out[-400:])

    baks = [p for p in bak_files(desk) if "history.jsonl.bak-" in p.name]
    check("a .bak- copy exists beside the file it replaced", len(baks) == 1, str(baks))
    check("and it holds exactly the bytes that were replaced",
          baks and baks[0].read_bytes() == before, str(baks))
    check("the backup matches neither of the player's patterns",
          all(not PRUNE_PATTERN.match(p.name) and p.name != "history.jsonl" for p in bak_files(desk)),
          str(bak_files(desk)))
    check("no temp files are left behind",
          not [p for p in desk.iterdir() if ".tmp-" in p.name], str(list(desk.iterdir())))

    # the player archives-and-deletes history.jsonl when its *mtime* is another year
    mt = dt.datetime.fromtimestamp((desk / "history.jsonl").stat().st_mtime)
    check("the rewritten file carries this year's mtime, not the one it replaced",
          mt.year == YEAR, f"{mt}")

    # ------------------------------------------------------------- 2. idempotency
    print("\n2. running it twice adds nothing")
    steady = snap(desk)
    r = merge(desk, phone / "history.jsonl")
    out = r.stdout + r.stderr
    check("the second run exits 0", r.returncode == 0, out[-200:])
    check("the phone's lines are all duplicates now",
          "0 added, 5 duplicate(s) skipped" in out, out)
    check("and nothing is written at all", "0 files written" in out, out)
    check("the directory is byte-identical", snap(desk) == steady,
          str(set(snap(desk)) ^ set(steady)))
    check("no second backup was made for an untouched file",
          len([p for p in bak_files(desk) if "history.jsonl.bak-" in p.name]) == 1,
          str(bak_files(desk)))

    # ------------------------------------------------------------------- 3. dedup
    print("\n3. the same play twice: one line, on the triple (ts, artist, title)")
    dedup = SCRATCH / "c" / "desk"
    same = ms(YEAR, 5, 5)
    write_lines(dedup / "history.jsonl", [J(same, "A", "Alb", "one", sec=161)])
    write_lines(SCRATCH / "c" / "phone.jsonl", [J(same, "A", "Alb", "one", sec=0),
                                                J(same, "A", "Alb", "one", sec=0),
                                                J(same, "Other", "Alb", "one", sec=7)])
    r = merge(dedup, SCRATCH / "c" / "phone.jsonl")
    kept = read_lines(dedup / "history.jsonl")
    check("the same triple with a different sec stays one line",
          len([l for l in kept if json.loads(l)["artist"] == "A"]) == 1, str(kept))
    check("and the line kept is the one already in the diary",
          [l for l in kept if json.loads(l)["artist"] == "A"][0] == J(same, "A", "Alb", "one", sec=161),
          str(kept))
    check("a different artist with the same ts is a different play",
          len([l for l in kept if json.loads(l)["artist"] == "Other"]) == 1, str(kept))
    check("a file that repeats itself counts the repeats as duplicates",
          "3 lines, 2 unique, 1 added, 2 duplicate(s) skipped" in (r.stdout + r.stderr),
          r.stdout)

    # ------------------------------------------------------- 4. a gzipped import
    print("\n4. an archive comes in gzipped and goes back out gzipped, unchanged")
    gz_desk = SCRATCH / "d" / "desk"
    past = [J(ms(PRUNED_YEAR, 8, 8), "G", "AlbG", "gz one", 5, 0),
            J(ms(PRUNED_YEAR, 9, 9), "G", "AlbG", "gz two", 6, 0)]
    write_gz(SCRATCH / "d" / f"history.{PRUNED_YEAR}.jsonl.gz", list(reversed(past)))
    r = merge(gz_desk, SCRATCH / "d" / f"history.{PRUNED_YEAR}.jsonl.gz")
    back = read_gz_lines(gz_desk / f"history.{PRUNED_YEAR}.jsonl.gz")
    check("the gzipped archive is read", r.returncode == 0, (r.stdout + r.stderr)[-300:])
    check("its lines are written back byte-for-byte, sorted by ts", back == past, str(back))
    check("and it is readable as gzip by anyone else",
          gzip.decompress((gz_desk / f"history.{PRUNED_YEAR}.jsonl.gz").read_bytes()) != b"", "")

    # ------------------------------------------------- 5. gzip detected by magic
    print("\n5. gzip is detected by magic bytes, not just by the name")
    magic = SCRATCH / "e" / "desk"
    write_gz(SCRATCH / "e" / "phone.jsonl", [J(ms(LAST_YEAR, 4, 4), "H", "AlbH", "hidden", 3, 0)])
    r = merge(magic, SCRATCH / "e" / "phone.jsonl")
    check("a gzipped file called .jsonl is decompressed anyway",
          read_gz_lines(magic / f"history.{LAST_YEAR}.jsonl.gz")
          == [J(ms(LAST_YEAR, 4, 4), "H", "AlbH", "hidden", 3, 0)], r.stdout)

    # --------------------------------------------------------------- 6. refusals
    print("\n6. refusing beats guessing: a file it cannot read is not an empty file")

    def refuses(name: str, import_file: Path | None, *,
                extra: tuple[str, ...] = (), env: dict | None = None) -> subprocess.CompletedProcess:
        """Every refusal: non-zero, an explanation, and a directory nobody touched."""
        desk = SCRATCH / "f" / "desk"
        if desk.exists():
            shutil.rmtree(desk)
        desk.mkdir(parents=True)
        write_lines(desk / "history.jsonl", [J(ms(YEAR, 2, 2), "A", "Alb", "safe", 10, 0)])
        before_snap = snap(desk)
        r = run("diary", "--import", str(import_file), "--diary", str(desk), *extra, env=env) \
            if import_file is not None else run("diary", "--diary", str(desk))
        after = snap(desk)
        out = r.stdout + r.stderr
        check(f"{name}: exits non-zero", r.returncode != 0, f"{r.returncode} {out[-200:]}")
        check(f"{name}: says nothing was written", "nothing was written" in out, out[-300:])
        check(f"{name}: leaves the diary byte-identical", after == before_snap,
              f"{set(after) ^ set(before_snap)}")
        check(f"{name}: writes nothing to stdout it would have to retract",
              "wrote" not in r.stdout, r.stdout[-200:])
        return r

    bad = SCRATCH / "f" / "bad.jsonl"
    write_lines(bad, [J(ms(YEAR, 3, 3), "A", "Alb", "fine", 10, 0), '{"ts":123,'])
    r = refuses("a truncated line", bad)
    check("the message names the file and the line", "bad.jsonl: line 2" in (r.stdout + r.stderr),
          (r.stdout + r.stderr)[-300:])

    blank = SCRATCH / "f" / "blank.jsonl"
    write_lines(blank, [J(ms(YEAR, 3, 3)), "", J(ms(YEAR, 3, 4))])
    refuses("a blank line", blank)

    unjson = SCRATCH / "f" / "unjson.jsonl"
    write_lines(unjson, ["not json at all"])
    refuses("a line that is not JSON", unjson)

    notobject = SCRATCH / "f" / "notobject.jsonl"
    write_lines(notobject, ["[1,2,3]"])
    refuses("a JSON array instead of an object", notobject)

    notriple = SCRATCH / "f" / "notriple.jsonl"
    write_lines(notriple, ['{"ts":1234567890,"artist":"A","album":"Alb","sec":1,"dur":0}'])
    refuses("a line with no title", notriple)

    bogus_ts = SCRATCH / "f" / "bogusts.jsonl"
    write_lines(bogus_ts, ['{"ts":"yesterday","artist":"A","album":"Alb","title":"t","sec":1,"dur":0}'])
    refuses("a line whose ts is not a number", bogus_ts)

    refuses("a file that isn't there", SCRATCH / "f" / "no-such-file.jsonl")

    notgz = SCRATCH / "f" / "history.2020.jsonl.gz"
    write_lines(notgz, [J(ms(2020, 1, 1))])
    refuses("a .gz that isn't gzip", notgz)

    unreadable = SCRATCH / "f" / "unreadable.jsonl"
    write_lines(unreadable, [J(ms(YEAR, 3, 3))])
    unreadable.chmod(0o000)
    if os.geteuid() == 0:
        skip("an unreadable file", "running as root, where everything is readable")
    else:
        refuses("an unreadable file", unreadable)
    unreadable.chmod(0o644)

    # a corrupt line in the diary's *own* file is the same refusal, not a wipe
    desk = SCRATCH / "f" / "desk"
    shutil.rmtree(desk)
    desk.mkdir(parents=True)
    (desk / "history.jsonl").write_bytes(b'{"ts":12\n')
    good = SCRATCH / "f" / "good.jsonl"
    write_lines(good, [J(ms(YEAR, 3, 3), "A", "Alb", "fine", 10, 0)])
    before_snap = snap(desk)
    r = run("diary", "--import", str(good), "--diary", str(desk))
    check("a corrupt line in the diary itself refuses too", r.returncode != 0,
          f"{r.returncode} {r.stdout[-200:]}")
    check("and the diary is left alone", snap(desk) == before_snap, str(snap(desk)))

    # an unlistable diary folder is not an empty one either
    dark = SCRATCH / "f" / "dark"
    write_lines(dark / "history.jsonl", [J(ms(YEAR, 3, 3))])
    dark.chmod(0o000)
    if os.geteuid() == 0:
        skip("an unlistable diary folder", "running as root, where everything is readable")
    else:
        r = run("diary", "--import", str(good), "--diary", str(dark))
        check("an unlistable diary folder refuses instead of looking empty",
              r.returncode != 0 and "cannot list" in (r.stdout + r.stderr),
              f"{r.returncode} {(r.stdout + r.stderr)[-200:]}")
    dark.chmod(0o755)

    # importing the diary's own file would only risk the original
    self_import = SCRATCH / "f" / "self"
    write_lines(self_import / "history.jsonl", [J(ms(YEAR, 3, 3))])
    before_snap = snap(self_import)
    r = run("diary", "--import", str(self_import / "history.jsonl"), "--diary", str(self_import))
    check("importing the diary into itself is refused", r.returncode != 0 and
          "is the diary's own file" in (r.stdout + r.stderr), (r.stdout + r.stderr)[-200:])
    check("and it changed nothing", snap(self_import) == before_snap, str(snap(self_import)))

    # ---------------------------------------------------- 7. nothing is deleted
    print("\n7. it never deletes: old archives and other people's files stay put")
    keep = SCRATCH / "g" / "desk"
    old = [J(ms(1999, 1, 1), "Z", "Old", "ancient", 1, 0)]
    write_gz(keep / "history.1999.jsonl.gz", old)
    (keep / "notes.txt").write_text("not a diary file\n")
    write_lines(SCRATCH / "g" / "phone.jsonl", [J(ms(YEAR, 7, 7), "A", "Alb", "new", 1, 0)])
    r = merge(keep, SCRATCH / "g" / "phone.jsonl")
    check("an old archive is still there", (keep / "history.1999.jsonl.gz").is_file(), r.stdout)
    check("with its lines intact", old[0] in read_gz_lines(keep / "history.1999.jsonl.gz"), r.stdout)
    check("a file that isn't a diary file is not touched",
          (keep / "notes.txt").read_bytes() == b"not a diary file\n", "")
    check("and it says out loud that the player will prune that year",
          "prunes" in r.stdout and "history.1999.jsonl.gz (1)" in r.stdout
          and "1 line in years the player prunes" in r.stdout, r.stdout)

    # -------------------------------------------------------- 8. where it lives
    print("\n8. the diary folder resolves defaults -> file -> env -> flag")
    cfg = SCRATCH / "xdg-config" / "marimo-sync" / "config.json"
    cfg.parent.mkdir(parents=True, exist_ok=True)
    for target, kwargs, label in (
            (SCRATCH / "h" / "from-file", {"env": ENV}, "the config file"),
            (SCRATCH / "h" / "from-env", {"env": {**ENV, "MARIMO_SYNC_DIARY": str(SCRATCH / "h" / "from-env")}},
             "the environment"),
            (SCRATCH / "h" / "from-flag", {"env": {**ENV, "MARIMO_SYNC_DIARY": str(SCRATCH / "h" / "never")},
                                           "extra": ("--diary", str(SCRATCH / "h" / "from-flag"))},
             "the flag")):
        cfg.write_text(json.dumps({"diary": str(SCRATCH / "h" / "from-file")}))
        write_lines(SCRATCH / "h" / "phone.jsonl", [J(ms(YEAR, 8, 8), "A", "Alb", "where", 1, 0)])
        r = run("diary", "--import", str(SCRATCH / "h" / "phone.jsonl"),
                *kwargs.get("extra", ()), env=kwargs["env"])
        check(f"{label} decides where the merge lands", (target / "history.jsonl").is_file(),
              f"{r.returncode} {(r.stdout + r.stderr)[-200:]}")
        check(f"and nothing lands anywhere else ({label})",
              not (SCRATCH / "h" / "never").exists(), str(list((SCRATCH / "h").iterdir())))
    cfg.unlink()

    # a folder that doesn't exist yet: the plan says so, --dry-run makes nothing
    fresh = SCRATCH / "i" / "brand-new"
    write_lines(SCRATCH / "i" / "phone.jsonl", [J(ms(YEAR, 8, 9), "A", "Alb", "fresh", 1, 0)])
    r = run("diary", "--import", str(SCRATCH / "i" / "phone.jsonl"), "--diary", str(fresh),
            "--dry-run")
    check("--dry-run does not even create the folder",
          "does not exist yet" in r.stdout and not fresh.exists(), r.stdout[-200:])
    r = run("diary", "--import", str(SCRATCH / "i" / "phone.jsonl"), "--diary", str(fresh))
    check("a real run makes it and merges into it",
          (fresh / "history.jsonl").is_file() and r.returncode == 0,
          f"{r.returncode} {(r.stdout + r.stderr)[-200:]}")

    shown = run("config").stdout
    check("config reports the two new keys and where they came from",
          "diary_out" in shown and "diary_phone" in shown
          and "/sdcard/Download/marimo" in shown, shown[:400])
    check("and the staging default is inside this fixture, not a real data dir",
          str(SCRATCH / "xdg-data" / "marimo-sync" / "diary-out") in shown, shown[:400])

    # ------------------------------------------------- 9. the invariants, in code
    print("\n9. the guard that makes the promise checkable")
    from marimosync import diary as D  # noqa: E402

    check("archive names are read strictly", D.archive_year("history.2024.jsonl.gz") == 2024
          and D.archive_year("history.jsonl.bak-20260101-000000") is None
          and D.archive_year("history.jsonl") is None
          and D.archive_year("history.txt") is None, "")
    d = SCRATCH / "j"
    write_lines(d / "history.jsonl", [J(ms(YEAR, 1, 1), "A", "Alb", "one", 1, 0),
                                      J(ms(YEAR, 1, 2), "A", "Alb", "two", 1, 0)])
    m = D.Merge(d)
    m.add(d / "history.jsonl")
    m.guard()
    check("a merge that kept everything passes its own guard", m.total == 2, str(m.total))
    m.entries.pop(next(iter(m.entries)))
    try:
        m.guard()
        check("a merge that lost a line refuses to write", False, "no DiaryError raised")
    except D.DiaryError as e:
        check("a merge that lost a line refuses to write", "refusing to lose a line" in str(e), str(e))
    check("lines are carried as bytes, never re-serialised",
          D.render([D.Entry(b'{"ts":1}', 1, (1, "a", "t"))]) == b'{"ts":1}\n', "")
    check("an archive target is gzipped, a log target is not",
          gzip.decompress(D.target_bytes("history.2024.jsonl.gz", [D.Entry(b"{}", 1, ())])) == b"{}\n"
          and D.target_bytes("history.jsonl", [D.Entry(b"{}", 1, ())]) == b"{}\n", "")
    # a refusal *after* something has been written must not claim nothing happened
    d = SCRATCH / "k"
    write_lines(d / "history.jsonl", [J(ms(YEAR, 1, 1), "A", "Alb", "one", 1, 0)])
    try:
        D._replan(d, [d / "history.jsonl"], 2)
        with_corrupt = SCRATCH / "k" / "bad.jsonl"
        write_lines(with_corrupt, ['{"ts":12'])
        D._replan(d, [with_corrupt], 2)
        check("a mid-write refusal admits what was already written", False, "no DiaryWriteError")
    except D.DiaryWriteError as e:
        check("a mid-write refusal admits what was already written",
              "2 files had already been written" in str(e), str(e))
    except D.DiaryError as e:
        check("a mid-write refusal admits what was already written", False,
              f"raised the plain refusal instead: {e}")
    try:
        D._replan(d, [SCRATCH / "k" / "bad.jsonl"], 0)
        check("but with nothing written it is the plain refusal", False, "no DiaryError")
    except D.DiaryError as e:
        check("but with nothing written it is the plain refusal",
              not isinstance(e, D.DiaryWriteError) and "not JSON" in str(e), str(e))

    # ------------------------------- 10. staging it for the phone, and pushing it
    print("\n10. the exchange: staging the merged diary for the phone, and pushing it")
    home = SCRATCH / "p" / "desk"
    staged = SCRATCH / "p" / "out"
    phone_dir = SCRATCH / "p" / "phone"
    imp = SCRATCH / "p" / "phone.jsonl"
    write_lines(home / "history.jsonl", [J(ms(YEAR, 2, 2), "A", "Alb", "existing", 10, 0)])
    write_lines(imp, [J(ms(YEAR, 3, 3), "B", "Alb2", "new", 200, 0),
                      J(ms(LAST_YEAR, 4, 4), "C", "Alb3", "last", 1, 0)])

    before_home = snap(home)
    r = merge(home, imp, extra=("--publish", "--dry-run", "--diary-out", str(staged)))
    check("--dry-run --publish exits 0 and shows the staging plan",
          r.returncode == 0 and "would stage" in r.stdout and str(staged) in r.stdout,
          f"{r.returncode} {(r.stdout + r.stderr)[-300:]}")
    check("--dry-run --publish creates no staging directory", not staged.exists(), str(staged))
    check("--dry-run --publish leaves the diary byte-identical", snap(home) == before_home,
          str(set(snap(home)) ^ set(before_home)))

    r = merge(home, imp, extra=("--publish", "--diary-out", str(staged)))
    diary_names = sorted(p.name for p in home.iterdir() if not p.name.startswith("."))
    check("--publish exits 0", r.returncode == 0, (r.stdout + r.stderr)[-300:])
    check("the staging directory holds exactly the diary's files, and nothing else",
          sorted(p.name for p in staged.iterdir()) == diary_names,
          f"{sorted(p.name for p in staged.iterdir())} vs {diary_names}")
    check("every staged file is byte-identical to the diary's",
          all((staged / n).read_bytes() == (home / n).read_bytes() for n in diary_names),
          str(diary_names))
    check("archives are staged byte-identically too, not re-made",
          any(n.endswith(".jsonl.gz") for n in diary_names)
          and all(hashlib.sha256((staged / n).read_bytes()).digest()
                  == hashlib.sha256((home / n).read_bytes()).digest() for n in diary_names),
          str(diary_names))

    # the point of the whole feature: the phone exporting our own merge back to us
    settled, settled_staged = snap(home), snap(staged)
    r = run("diary", "--import", str(staged / "history.jsonl"),
            "--import", str(staged / f"history.{LAST_YEAR}.jsonl.gz"), "--diary", str(home))
    check("the phone's export of our own merge adds 0 lines", "0 added" in r.stdout,
          r.stdout[-300:])
    check("and rewrites nothing", "0 files written" in r.stdout, r.stdout[-300:])
    check("so the diary is byte-identical after the round trip", snap(home) == settled,
          str(set(snap(home)) ^ set(settled)))

    staged_mtimes = {p.name: p.stat().st_mtime_ns for p in staged.iterdir()}
    r = run("diary", "--publish", "--diary", str(home), "--diary-out", str(staged))
    check("re-publishing a settled diary stages nothing again",
          "0 files staged" in r.stdout and "already up to date" in r.stdout, r.stdout[-300:])
    check("and does not rewrite the staged files",
          snap(staged) == settled_staged
          and {p.name: p.stat().st_mtime_ns for p in staged.iterdir()} == staged_mtimes, "")
    check("--publish on its own stages the diary as it stands, whole",
          sorted(p.name for p in staged.iterdir()) == diary_names,
          str(sorted(p.name for p in staged.iterdir())))

    # a phone that cannot be reached: nothing written, nothing staged, and it says what it wanted
    before_diary, before_staged = snap(home), snap(staged)
    r = run("--device", "adb:NOSUCHDEVICE01", "diary", "--push", "--diary-out", str(staged))
    out = r.stdout + r.stderr
    check("--push with no reachable device exits non-zero", r.returncode != 0,
          f"{r.returncode} {out[-200:]}")
    check("and names the device it was looking for",
          "NOSUCHDEVICE01" in out and "isn't connected" in out, out[-300:])
    check("and writes nothing to the diary", snap(home) == before_diary, "")
    check("and stages nothing", snap(staged) == before_staged, "")

    phone_dir.mkdir(parents=True)
    r = run("--device", f"dir:{phone_dir}", "diary", "--push",
            "--diary-out", str(SCRATCH / "p" / "never-staged"))
    check("--push with nothing staged refuses and says where it looked",
          r.returncode != 0 and "nothing staged" in (r.stdout + r.stderr),
          (r.stdout + r.stderr)[-250:])

    r = run("--device", f"dir:{phone_dir}", "diary", "--push", "--diary-out", str(staged))
    check("--push copies the staged diary to the device", r.returncode == 0,
          (r.stdout + r.stderr)[-300:])
    check("and it is byte-identical there too",
          sorted(p.name for p in phone_dir.iterdir()) == diary_names
          and all((phone_dir / n).read_bytes() == (home / n).read_bytes() for n in diary_names),
          str(sorted(p.name for p in phone_dir.iterdir())))
    check("and pushing touched neither the diary nor the staging directory",
          snap(home) == before_diary and snap(staged) == before_staged, "")

    # the device-free promise: the merge and the staging never ask for a phone at all
    home2, out2 = SCRATCH / "p" / "desk2", SCRATCH / "p" / "out2"
    r = run("--device", "adb:NOSUCHDEVICE01", "diary", "--import", str(imp), "--publish",
            "--diary", str(home2), "--diary-out", str(out2))
    check("--import --publish works with a device that cannot resolve",
          r.returncode == 0 and (out2 / "history.jsonl").is_file()
          and (out2 / f"history.{LAST_YEAR}.jsonl.gz").is_file(),
          f"{r.returncode} {(r.stdout + r.stderr)[-300:]}")

    env_out = SCRATCH / "p" / "env-out"
    r = run("diary", "--publish", "--diary", str(home),
            env={**ENV, "MARIMO_SYNC_DIARY_OUT": str(env_out)})
    check("diary_out resolves from the environment",
          (env_out / "history.jsonl").is_file()
          and (env_out / f"history.{LAST_YEAR}.jsonl.gz").is_file(),
          f"{r.returncode} {(r.stdout + r.stderr)[-200:]}")

    r = run("diary", "--publish", "--diary", str(home), "--diary-out", str(home))
    check("publishing into the diary itself is refused, not attempted",
          r.returncode != 0 and "is the diary itself" in (r.stdout + r.stderr),
          (r.stdout + r.stderr)[-250:])
    check("and that refusal wrote nothing", snap(home) == before_diary, "")

    r = run("diary", "--diary", str(home))
    check("the command with no flags at all says what it wants",
          r.returncode == 2 and "nothing to do" in (r.stdout + r.stderr),
          f"{r.returncode} {(r.stdout + r.stderr)[-200:]}")

    # ------------------------------- 11. the case the app must agree with, byte for byte
    print("\n11. the canonical case: one rule, two languages, pinned by digests")
    # The merge rule exists twice -- here and in the app's DiaryImport.java -- and the
    # only thing that stops the two forking silently is both sides asserting the same
    # bytes. This is that contract: fixed input lines, a fixed clock, three digests.
    # **The app's DiaryImportTest is expected to assert these same three digests**
    # (CANON_LIVE / CANON_PREV_YEAR / CANON_PRUNED_YEAR above) against these same input
    # lines; if either side's rule drifts, that side's own suite fails here rather than
    # the two recaps quietly diverging.
    #
    # `now=` is why this section drives diary.merge() directly instead of the CLI: the
    # bucket years come from the *clock*, so a case described as "this year / last year /
    # the pruned year" would write different bytes every January. The CLI has no -- and
    # should have no -- way to pin the clock, so the seam is the module call.
    #
    # Every ts is mid-year on purpose: bucketing reads the *local* year, so a ts near
    # 31 December would put these digests at the mercy of the runner's timezone.
    canon = SCRATCH / "canon"
    own_dir, ph_dir = canon / "own", canon / "phone"
    own_dir.mkdir(parents=True, exist_ok=True)
    ph_dir.mkdir(parents=True, exist_ok=True)
    # ts values, as UTC: 2026-03-05, 2025-11-02, 2026-04-01, 2026-05-06, 2024-06-06,
    # 2025-08-08 -- written as integers so the bytes never depend on this box's TZ.
    OWN_1 = b'{"ts":1772712000000,"artist":"Alpha","album":"Canon","title":"first","sec":100,"dur":259853}'
    OWN_2 = b'{"ts":1772712000000,"artist":"Alpha","album":"Canon","title":"first","sec":0,"dur":259853}'
    OWN_3 = b'{"ts":1762084800000,"artist":"Bravo","album":"Canon","title":"previous year","sec":250,"dur":0}'
    # The escaping case. In the file this artist reads, literally:
    #     q\"b\\\nc鬱
    # that is: an escaped quote, an escaped backslash (two bytes \\), then an escaped
    # newline -- the two bytes backslash + 'n', NOT a raw 0x0A inside the JSON string --
    # then a literal 'c', then 鬱 as its own three UTF-8 bytes (never \u-escaped).
    OWN_4 = b'{"ts":1775044800000,"artist":"q\\"b\\\\\\nc\xe9\xac\xb1","album":"Canon","title":"escaped","sec":42,"dur":259853}'
    PHONE_1 = b'{"ts":1772712000000,"artist":"Alpha","album":"Canon","title":"first","sec":161,"dur":259853}'
    PHONE_2 = b'{"ts":1778068800000,"artist":"Charlie","album":"Canon","title":"fresh","sec":200,"dur":240000}'
    PHONE_3 = b'{"ts":1717675200000,"artist":"Delta","album":"Canon","title":"pruned","sec":100,"dur":200000}'
    ARCH_1 = b'{"ts":1754654400000,"artist":"Echo","album":"Canon","title":"archived","sec":30,"dur":0}'

    (own_dir / "history.jsonl").write_bytes(
        b"".join(l + b"\n" for l in (OWN_1, OWN_2, OWN_3, OWN_4)))
    (ph_dir / "history.jsonl").write_bytes(
        b"".join(l + b"\n" for l in (PHONE_1, PHONE_2, PHONE_3)))
    (ph_dir / "history.2025.jsonl.gz").write_bytes(gzip.compress(ARCH_1 + b"\n", mtime=0))

    def quiet(*_a, **_k):
        return None

    D.merge(own_dir, [ph_dir / "history.jsonl", ph_dir / "history.2025.jsonl.gz"],
            now=dt.datetime(2026, 6, 15, 12, 0), out=quiet)
    live = (own_dir / "history.jsonl").read_bytes()
    got_prev = gzip.decompress((own_dir / "history.2025.jsonl.gz").read_bytes())
    got_pruned = gzip.decompress((own_dir / "history.2024.jsonl.gz").read_bytes())

    check("the live log is exactly the three lines the rule gives, in ts order",
          live == b"".join(l + b"\n" for l in (OWN_1, OWN_4, PHONE_2)), live.decode("utf-8"))
    check("the file's own duplicate lost (first triple wins: sec=100 kept, sec=0 gone)",
          b'"sec":100' in live and b'"sec":0,' not in live, live.decode("utf-8"))
    check("the phone's overlap lost the same way (sec=161 is nowhere)",
          b'"sec":161' not in live, live.decode("utf-8"))
    check("the escaped artist came through as its own bytes, newline escape and all",
          OWN_4 in live and b"\\n" in OWN_4 and b"\n" not in OWN_4
          and b"\xe9\xac\xb1" in live, OWN_4.hex())
    check("the previous-year archive holds the phone's line and the diary's, ts-sorted",
          got_prev == b"".join(l + b"\n" for l in (ARCH_1, OWN_3)), got_prev.decode("utf-8"))
    check("the pruned-year archive holds its one line", got_pruned == PHONE_3 + b"\n",
          got_pruned.decode("utf-8"))
    check("the fixture line's bytes are exactly the hex published for the app",
          OWN_4.hex() == CANON_ESCAPED_LINE_HEX,
          f"fixture {OWN_4.hex()}\n  published {CANON_ESCAPED_LINE_HEX}")
    check("sha256(live log bytes) is the digest the app must assert",
          hashlib.sha256(live).hexdigest() == CANON_LIVE, hashlib.sha256(live).hexdigest())
    check("sha256(previous-year archive, decompressed) is the app's second digest",
          hashlib.sha256(got_prev).hexdigest() == CANON_PREV_YEAR,
          hashlib.sha256(got_prev).hexdigest())
    check("sha256(pruned-year archive, decompressed) is the app's third digest",
          hashlib.sha256(got_pruned).hexdigest() == CANON_PRUNED_YEAR,
          hashlib.sha256(got_pruned).hexdigest())
    settled_canon = snap(own_dir)
    D.merge(own_dir, [ph_dir / "history.jsonl", ph_dir / "history.2025.jsonl.gz"],
            now=dt.datetime(2026, 6, 15, 12, 0), out=quiet)
    check("re-running the same merge over the same bytes changes nothing at all",
          snap(own_dir) == settled_canon, str(set(snap(own_dir)) ^ set(settled_canon)))

    # ----------------------------- 12. the cable path: --pull, and the whole cycle in one
    print("\n12. the cable path: --pull fetches byte-exact, and one command does the cycle")
    # A `dir:` device IS its own tree, so this fixture stands in for the phone's
    # /sdcard/Download/marimo on both sides of the cycle: the export that comes down,
    # and the destination the merged file goes back to.
    dev = SCRATCH / "r" / "exchange"
    dev.mkdir(parents=True, exist_ok=True)
    inbox = SCRATCH / "r" / "in"
    home4 = SCRATCH / "r" / "desk"
    write_lines(home4 / "history.jsonl", [J(ms(YEAR, 2, 2), "A", "Alb", "hers", 10, 0)])
    write_lines(dev / "history.jsonl", [J(ms(YEAR, 3, 3), "B", "Alb2", "theirs", 200, 0),
                                        J(ms(YEAR, 2, 2), "A", "Alb", "hers", 99, 0)])
    exported = hashlib.sha256((dev / "history.jsonl").read_bytes()).hexdigest()
    before_home = snap(home4)

    r = run("--device", "adb:NOSUCHDEVICE01", "diary", "--pull", "--diary", str(home4),
            "--diary-in", str(inbox))
    out = r.stdout + r.stderr
    check("--pull with no reachable device exits non-zero", r.returncode != 0,
          f"{r.returncode} {out[-200:]}")
    check("and names the device it was looking for",
          "NOSUCHDEVICE01" in out and "isn't connected" in out, out[-300:])
    check("and writes nothing at all -- not even the inbox",
          snap(home4) == before_home and not inbox.exists(), "")

    empty = SCRATCH / "r" / "empty"
    empty.mkdir(parents=True, exist_ok=True)
    r = run("--device", f"dir:{empty}", "diary", "--pull", "--diary", str(home4),
            "--diary-in", str(SCRATCH / "r" / "in2"))
    out = r.stdout + r.stderr
    check("--pull on an empty exchange folder refuses and names the human step",
          r.returncode != 0 and "press export on the phone first" in out, out[-300:])
    check("and changes nothing there either",
          snap(home4) == before_home and not (SCRATCH / "r" / "in2").exists(), "")

    r = run("--device", f"dir:{dev}", "diary", "--pull", "--diary", str(home4),
            "--diary-in", str(inbox))
    fetched = inbox / "history.jsonl"
    check("--pull fetches the export and exits 0", r.returncode == 0,
          (r.stdout + r.stderr)[-300:])
    check("the fetched file's digest is the device's, byte for byte",
          fetched.is_file()
          and hashlib.sha256(fetched.read_bytes()).hexdigest() == exported,
          f"{hashlib.sha256(fetched.read_bytes()).hexdigest() if fetched.is_file() else 'no file'}"
          f" vs {exported}")
    check("and the tool says it checked, rather than just claiming success",
          "verified" in r.stdout and "sha256" in r.stdout, r.stdout[-300:])
    check("--pull with no --import merges what it fetched by itself",
          "1 added" in r.stdout and len(read_lines(home4 / "history.jsonl")) == 2,
          r.stdout[-400:])

    home5, out5 = SCRATCH / "r" / "desk5", SCRATCH / "r" / "out5"
    dev2 = SCRATCH / "r" / "exchange2"
    dev2.mkdir(parents=True, exist_ok=True)
    write_lines(home5 / "history.jsonl", [J(ms(YEAR, 2, 2), "A", "Alb", "hers", 10, 0)])
    write_lines(dev2 / "history.jsonl", [J(ms(YEAR, 3, 3), "B", "Alb2", "theirs", 200, 0)])
    cycle = ["--device", f"dir:{dev2}", "diary", "--pull", "--publish", "--push",
             "--diary", str(home5), "--diary-in", str(SCRATCH / "r" / "in5"),
             "--diary-out", str(out5)]
    r = run(*cycle)
    check("pull -> merge -> publish -> push in one command exits 0", r.returncode == 0,
          (r.stdout + r.stderr)[-400:])
    check("and it really did all four: merged, staged, and pushed the staged bytes",
          len(read_lines(home5 / "history.jsonl")) == 2
          and (out5 / "history.jsonl").is_file()
          and (dev2 / "history.jsonl").read_bytes() == (out5 / "history.jsonl").read_bytes(),
          r.stdout[-400:])
    settled = (snap(home5), snap(out5), hashlib.sha256((dev2 / "history.jsonl").read_bytes()).hexdigest())
    r = run(*cycle)
    check("a second identical cycle adds 0 lines", "0 added" in r.stdout, r.stdout[-300:])
    check("and stages nothing again", "0 files staged" in r.stdout, r.stdout[-300:])
    check("so the diary, the staging folder and the phone are all byte-identical",
          snap(home5) == settled[0] and snap(out5) == settled[1]
          and hashlib.sha256((dev2 / "history.jsonl").read_bytes()).hexdigest() == settled[2],
          r.stdout[-300:])

    r = run("--device", "adb:NOSUCHDEVICE01", "diary", "--import", str(dev2 / "history.jsonl"),
            "--publish", "--diary", str(SCRATCH / "r" / "desk6"),
            "--diary-out", str(SCRATCH / "r" / "out6"))
    check("plain --import --publish still never asks for a device",
          r.returncode == 0 and (SCRATCH / "r" / "out6" / "history.jsonl").is_file(),
          f"{r.returncode} {(r.stdout + r.stderr)[-300:]}")

    # -------------------------------------------------- 13. the real diary, safe
    print("\n13. the real diary was never touched")
    after = REAL_DIARY.read_bytes() if REAL_DIARY.exists() else None
    check("~/.config/marimo/history.jsonl only ever grew by appends, never rewritten",
          real_before is None or (after is not None and after.startswith(real_before)),
          f"{len(real_before or b'')} bytes before, {len(after or b'')} after")
    check("nothing of ours was left in the real diary folder",
          sorted(p.name for p in REAL_DIR.iterdir()) == real_names,
          str(sorted(p.name for p in REAL_DIR.iterdir())))

    print()
    if FAILED:
        print(f"{len(FAILED)}/{N} checks FAILED: " + ", ".join(FAILED))
        return 1
    print(f"all {N} checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
