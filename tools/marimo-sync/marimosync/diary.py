"""Merging a listening diary in from the phone.

The desktop keeps its diary as one JSON object per line in
`~/.config/marimo/history.jsonl`, with finished years gzipped beside it, and
marimo-android writes byte-compatible lines. Both files are append-only and only
roughly ordered, so concatenating them duplicates every line that was already
synced -- the job here is a *merge*: union, dedup on `(ts, artist, title)`, sort
by `ts`, and bucket back into year files by **local** calendar year.

Three things about this file are the whole of its design:

* **Lines are bytes, not JSON.** A kept line is written back exactly as it arrived
  -- same key order, same escaping, same number formatting. Nothing is ever
  re-serialised, so a merge cannot quietly rewrite a diary it was only asked to
  add to. That also means an empty (`sec: 0`) line and a full play are equally
  safe to carry.

* **Refusing is a feature.** An unreadable file is not an empty file and a corrupt
  line is not a line to skip: if anything cannot be read or parsed, or if the
  merged result would hold fewer distinct triples than some single input could
  see, nothing at all is written and the reason is printed. The same goes for a
  blank line -- that means a file was truncated or edited, and guessing past it is
  how a diary loses a year.

* **Nothing is ever deleted.** No pruning, no tidying, no "that archive is stale".
  Old files stay exactly where they are; the only files this tool writes are the
  year files the merge itself produces, and it backs each one up first.

marimo-desktop's player shapes the rest (`src/history.c`, read 2026-09-29):

* it appends with one `fopen(path, "ab")` per line and holds no lock, so replacing
  a file atomically is safe -- the next append resolves the path fresh. Because
  there is no lock, we re-read everything immediately before each replace, so a
  track logged while we were working is not dropped by the file we write.
* it archives *and removes* any `history.jsonl` whose **mtime** belongs to another
  year (`history_log`, :181-193). So the file we write must be brand new: we write
  a fresh temp and `os.replace()` it, and never copy the replaced file's
  timestamps onto it -- an old mtime would hand the player a diary to delete.
* it prunes `history.<Y>.jsonl.gz` for every Y at or below `current_year - 2` on
  each logged line (`prune`, :158-168) -- it keeps this year and the previous one,
  nothing older. We still write those years (dropping the lines would be worse)
  and say so out loud.
* its own temp and backup files must not look like either pattern, so ours are
  hidden and suffixed: `.history.jsonl.tmp-<rand>`, `.history.jsonl.bak-<stamp>`.
"""

from __future__ import annotations

import datetime as dt
import gzip
import hashlib
import json
import os
import stat
import tempfile
import zlib
from pathlib import Path
from typing import Iterable, NamedTuple

CURRENT_NAME = "history.jsonl"
ARCHIVE_PREFIX = "history."
ARCHIVE_SUFFIX = ".jsonl.gz"
GZIP_MAGIC = b"\x1f\x8b"


class DiaryError(RuntimeError):
    """A refusal. Nothing has been written when this is raised."""


class DiaryWriteError(RuntimeError):
    """Something failed once writing had started. Whatever landed is complete."""


class Entry(NamedTuple):
    raw: bytes            # exactly the bytes it arrived as, terminator removed
    ts: int               # epoch ms
    key: tuple            # (ts, artist, title) -- what makes two lines the same line


class FileStat(NamedTuple):
    path: Path
    lines: int
    unique: int
    added: int

    @property
    def duplicates(self) -> int:
        return self.lines - self.added


class Written(NamedTuple):
    """What a merge put where: the diary files that now hold the merged set."""
    names: list[str]
    wrote: int
    kept: int


class MergeSummary(NamedTuple):
    """What a merge did, in the numbers worth putting on a wire or in a window.

    Everything here is *observed* by the merge itself (the plan it read and the write
    it made), so a caller reporting these can never disagree with what happened.
    """
    lines_before: int      # lines in the diary's own files when the plan was made
    lines_after: int       # lines in the merged set
    added: int             # triples the imports brought that the diary did not have
    duplicates: int        # lines dropped because the triple was already there
    years: list[int]       # the local years the merged set now covers
    sha256: str            # of the live log as it stands (after the write)
    size: int              # ...and its size in bytes


# ------------------------------------------------------------------ reading

def archive_year(name: str) -> int | None:
    """`history.2024.jsonl.gz` -> 2024; anything else -> None.

    Deliberately strict: the player's `prune()` removes exactly that shape of name,
    so a file this does not recognise is one the player does not own either -- and
    our own temp and backup files must never look like one.
    """
    if not name.startswith(ARCHIVE_PREFIX) or not name.endswith(ARCHIVE_SUFFIX):
        return None
    middle = name[len(ARCHIVE_PREFIX):len(name) - len(ARCHIVE_SUFFIX)]
    return int(middle) if middle.isdigit() else None


def is_diary_file(name: str) -> bool:
    """The two names that are a diary, in every direction (disk, wire, device)."""
    return name == CURRENT_NAME or archive_year(name) is not None


def diary_files(diary: Path) -> list[Path]:
    """The diary's own files: archives oldest first, then this year's log.

    Anything else in the directory (our backups, a stray note, another program's
    file) is left alone and never read as diary.
    """
    if not diary.is_dir():
        return []
    try:
        listing = sorted(diary.iterdir())
    except OSError as e:
        # An unlistable folder is not an empty one: an empty diary would silently
        # mean "nothing to carry over".
        raise DiaryError(f"cannot list {diary} ({e.strerror or e}) -- refusing to merge "
                         f"past a diary folder it cannot see")
    found = [p for p in listing if p.is_file()
             and (p.name == CURRENT_NAME or archive_year(p.name) is not None)]
    archives = sorted((p for p in found if p.name != CURRENT_NAME),
                      key=lambda p: archive_year(p.name) or 0)
    return archives + [p for p in found if p.name == CURRENT_NAME]


def year_of(ts_ms: int, where: str = "") -> int:
    """The **local** calendar year a line belongs to -- both sides' rule."""
    try:
        return dt.datetime.fromtimestamp(ts_ms / 1000).year
    except (OSError, OverflowError, ValueError) as e:
        raise DiaryError(f"{where + ': ' if where else ''}ts {ts_ms} is not a time this "
                         f"machine can place in a year ({e})")


def read_file(path: Path) -> list[Entry]:
    """Every line of one diary file, as the bytes it arrived as."""
    try:
        data = path.read_bytes()
    except OSError as e:
        # A file we could not look at is not a file we found to be empty.
        raise DiaryError(f"cannot read {path} ({e.strerror or e}) -- refusing to merge "
                         f"past a file it cannot see")
    if data[:2] == GZIP_MAGIC or path.name.endswith(".gz"):
        try:
            data = gzip.decompress(data)
        except (OSError, EOFError, zlib.error) as e:
            raise DiaryError(f"{path} says it is gzip but is not readable as gzip ({e})")
    return [_entry(raw, path, n) for n, raw in enumerate(_lines(data), start=1)]


def _lines(data: bytes) -> Iterable[bytes]:
    """The lines of a diary file, terminators (and a CR before them) removed."""
    if data.endswith(b"\n"):
        data = data[:-1]
    if not data:
        return []
    return [raw[:-1] if raw.endswith(b"\r") else raw for raw in data.split(b"\n")]


def _entry(raw: bytes, path: Path, lineno: int) -> Entry:
    where = f"{path}: line {lineno}"
    if not raw.strip():
        raise DiaryError(f"{where} is blank -- a file with a hole in it is not a file "
                         f"to merge")
    try:
        obj = json.loads(raw.decode("utf-8"))
    except UnicodeDecodeError as e:
        raise DiaryError(f"{where} is not UTF-8 ({e})")
    except ValueError as e:
        raise DiaryError(f"{where} is not JSON ({e})")
    if not isinstance(obj, dict):
        raise DiaryError(f"{where} is not a JSON object")
    ts = obj.get("ts")
    if isinstance(ts, bool) or not isinstance(ts, (int, float)) \
            or (isinstance(ts, float) and not ts.is_integer()):
        raise DiaryError(f'{where} has no whole-number "ts" (found {ts!r})')
    ts = int(ts)
    for field in ("artist", "title"):
        if not isinstance(obj.get(field), str):
            raise DiaryError(f'{where} has no "{field}" string (found {obj.get(field)!r})')
    year_of(ts, where)               # an unplaceable ts is a fault, not a year
    return Entry(raw, ts, (ts, obj["artist"], obj["title"]))


# ------------------------------------------------------------------ merging

class Merge:
    """The union of everything read, before anything is written."""

    def __init__(self, diary: Path):
        self.diary = diary
        self.entries: dict[tuple, Entry] = {}
        self.stats: list[FileStat] = []

    def add(self, path: Path) -> None:
        """Fold one file in. The first copy of a triple wins; later ones are the
        duplicates a naive re-merge would pile up."""
        entries = read_file(path)
        added = 0
        for e in entries:
            if e.key not in self.entries:
                self.entries[e.key] = e
                added += 1
        self.stats.append(FileStat(path, len(entries), len({e.key for e in entries}), added))

    @property
    def total(self) -> int:
        return len(self.entries)

    def guard(self) -> None:
        """A merge must hold at least as many distinct triples as any single input
        it could see. This can only fail if something above stops keeping what it
        read -- which is exactly the bug worth stopping for."""
        for st in self.stats:
            if st.unique > len(self.entries):
                raise DiaryError(
                    f"{st.path} holds {st.unique} distinct line(s) but the merge holds "
                    f"only {len(self.entries)} -- refusing to lose a line it could see")

    def targets(self, current_year: int) -> dict[Path, list[Entry]]:
        """Which file each line belongs in, sorted by `ts`.

        A year later than the current one (a clock that ran ahead) goes in this
        year's log rather than minting an archive in the future.
        """
        by_year: dict[int, list[Entry]] = {}
        for e in self.entries.values():
            by_year.setdefault(year_of(e.ts), []).append(e)
        out: dict[Path, list[Entry]] = {}
        for year, entries in by_year.items():
            # ties keep the order the lines arrived in, so a merge is deterministic
            entries.sort(key=lambda e: e.ts)
            name = CURRENT_NAME if year >= current_year else f"history.{year}.jsonl.gz"
            out[self.diary / name] = entries
        return out


def render(entries: Iterable[Entry]) -> bytes:
    """A file's contents: every kept line exactly as it arrived, plus the trailing
    newline the format ends with. Nothing is re-serialised."""
    return b"".join(e.raw + b"\n" for e in entries)


def target_bytes(name: str, entries: Iterable[Entry]) -> bytes:
    """What the *file* named `name` should hold: the lines themselves, gzipped when
    the name says it is an archive.

    The gzip header's mtime is zeroed so the same merge twice over makes the same
    bytes -- the file's own mtime is what the player reads, and that is set by
    writing the file (never carried over from the one being replaced).
    """
    data = render(entries)
    if name.endswith(ARCHIVE_SUFFIX):
        return gzip.compress(data, compresslevel=6, mtime=0)
    return data


def ordered_names(targets: dict[Path, list[Entry]]) -> list[str]:
    """Archives oldest first, then this year's log -- the order they are written."""
    names = [p.name for p in targets]
    return sorted(names, key=lambda n: (n == CURRENT_NAME, archive_year(n) or 0, n))


# ------------------------------------------------------------------ writing

def _backup(path: Path, slug: str, data: bytes) -> Path:
    """A hidden sibling holding exactly the bytes being replaced.

    Hidden and suffixed on purpose: the player's rollover removes a file named
    exactly `history.jsonl` and its `prune()` removes `history.<Y>.jsonl.gz`, so a
    backup must match neither. The bytes are the ones we compared against, not a
    second read -- the file could have grown in between.
    """
    candidate = path.with_name(f".{path.name}.bak-{slug}")
    n = 1
    while candidate.exists():
        candidate = path.with_name(f".{path.name}.bak-{slug}-{n}")
        n += 1
    candidate.write_bytes(data)
    return candidate


def _write_atomic(path: Path, data: bytes) -> None:
    """Temp file in the same directory, fsynced, then `os.replace()`.

    The temp is deliberately fresh and the replaced file's timestamps are never
    copied on: the player archives and deletes a `history.jsonl` whose mtime
    belongs to another year, so a merged file has to carry today's mtime.
    """
    mode = stat.S_IMODE(path.stat().st_mode) if path.is_file() else None
    fd, tmp = tempfile.mkstemp(prefix=f".{path.name}.tmp-", dir=str(path.parent))
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(data)
            fh.flush()
            os.fsync(fh.fileno())
        if mode is not None:
            os.chmod(tmp, mode)
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise
    _fsync_dir(path.parent)


def _fsync_dir(path: Path) -> None:
    """Make the rename itself durable. Best-effort: not every filesystem allows it."""
    try:
        fd = os.open(str(path), os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(fd)
    except OSError:
        pass
    finally:
        os.close(fd)


# ------------------------------------------------------------------ the job

def publish(diary: Path, names: list[str] | None, out_dir: Path, *,
            dry_run: bool = False, out=print) -> int:
    """Stage the diary's files where the phone can import them.

    The phone cannot be written into -- a release build keeps its diary in a private
    filesDir, and there is no root -- so the exchange runs the other way: the desktop
    writes these files into a directory the app reads (`diary_phone` on the device,
    its Downloads folder). What lands there is a *copy of the diary's own bytes*,
    archives included, so what the phone imports is exactly what the desktop holds.

    Three deliberate properties: the whole diary is staged, not just what a merge
    touched (the app imports a file, not a delta); a file whose bytes already match
    is left alone, so re-publishing a settled diary churns nothing; and nothing is
    ever deleted here, so a stale file in the staging directory stays put -- it can
    only ever hold lines the diary still has, and re-importing those changes nothing.
    """
    diary = Path(diary).expanduser()
    out_dir = Path(out_dir).expanduser()
    if names is None:
        names = [p.name for p in diary_files(diary)]
    names = [n for n in names if n == CURRENT_NAME or archive_year(n) is not None]
    if not names:
        raise DiaryError(f"nothing to publish -- {diary} holds no diary files"
                         + ("" if diary.is_dir() else " (there is no diary there yet)"))
    if out_dir.resolve() == diary.resolve():
        raise DiaryError(f"{out_dir} is the diary itself -- staging needs a directory "
                         f"of its own, or the phone would be handed the live file")
    out(f"  publish -> {out_dir}")
    if dry_run:
        for name in names:
            out(f"    {name:<28}  would stage")
        return 0

    # read everything before writing anything, so a diary that cannot be staged
    # leaves the staging directory exactly as it was
    plan: list[tuple[str, bytes]] = []
    for name in names:
        src = diary / name
        try:
            plan.append((name, src.read_bytes()))
        except OSError as e:
            raise DiaryError(f"cannot read {src} to stage it ({e.strerror or e}) -- "
                             f"nothing was staged")
    try:
        out_dir.mkdir(parents=True, exist_ok=True)
    except OSError as e:
        raise DiaryError(f"cannot make {out_dir} ({e.strerror or e})")

    staged, kept = 0, 0
    for name, data in plan:
        dst = out_dir / name
        try:
            if dst.is_file() and dst.read_bytes() == data:
                kept += 1
                continue
        except OSError:
            pass
        try:
            _write_atomic(dst, data)
        except OSError as e:
            raise DiaryWriteError(f"cannot stage {dst} ({e.strerror or e})")
        staged += 1
        out(f"    {name:<28}  staged")
    out(f"  {_files(staged)} staged, {_files(kept)} already up to date")
    return 0


def merge(diary: Path, imports: list[Path], *, dry_run: bool = False,
          publish_to: Path | None = None, out=print,
          now: dt.datetime | None = None) -> int:
    """Merge these files into the diary at `diary`, and optionally stage the result.

    Returns a process exit code. Raises DiaryError before writing anything if it
    cannot do the job honestly.
    """
    merge_summary(diary, imports, dry_run=dry_run, publish_to=publish_to, out=out, now=now)
    return 0


def merge_summary(diary: Path, imports: list[Path], *, dry_run: bool = False,
                  publish_to: Path | None = None, out=print,
                  now: dt.datetime | None = None) -> MergeSummary:
    """The same work, reporting what it did -- for the reply over the wire, and the window.

    Every number comes from the plan the merge read and the write it made, so a summary
    cannot disagree with the merge that produced it. Raises DiaryError before writing
    anything when it cannot do the job honestly; on a dry run the digest describes the
    live log as it stands, because nothing was written.
    """
    diary = Path(diary).expanduser()
    imports = [Path(p).expanduser() for p in imports]
    if not imports:
        raise DiaryError("nothing to merge in -- give --import FILE")
    for p in imports:
        if p.is_dir():
            raise DiaryError(f"{p} is a directory -- --import wants diary files, "
                             f"not a folder of them")
        if p.resolve() in {q.resolve() for q in diary_files(diary)}:
            raise DiaryError(f"{p} is the diary's own file -- importing a file into "
                             f"itself has nothing to add and would only risk the original")
    now = now or dt.datetime.now()

    plan = _read_everything(diary, imports)
    _report(plan, diary, now.year, out)
    if publish_to is not None:
        # part of the plan, and like the rest of it: --dry-run copies nothing
        publish(diary, ordered_names(plan.targets(now.year)), publish_to,
                dry_run=True, out=out)
    if dry_run:
        out("")
        out("--dry-run: nothing was written")
        return _summary(plan, diary, now.year)

    out("")
    written = _apply(diary, imports, now.year, plan.total, out)
    if publish_to is not None:
        try:
            publish(diary, written.names, publish_to, out=out)
        except (DiaryError, DiaryWriteError) as e:
            # the merge is done and correct; only the copy for the phone is missing,
            # and saying "nothing was written" here would be a lie about the diary
            raise DiaryWriteError(f"{e} -- the merge itself is complete and correct; "
                                  f"only the staged copy for the phone is missing")
    return _summary(plan, diary, now.year)


def diary_summary(diary: Path) -> MergeSummary:
    """The diary as it stands, for a caller that wants to report without merging.

    The phone may have nothing to send -- a fresh install still wants the merged diary
    back -- and the reply has to describe the file either way.
    """
    live = diary / CURRENT_NAME
    entries = read_file(live) if live.is_file() else []
    data = live.read_bytes() if live.is_file() else b""
    return MergeSummary(
        lines_before=len(entries),
        lines_after=len(entries),
        added=0,
        duplicates=0,
        years=sorted({year_of(e.ts) for e in entries}),
        sha256=hashlib.sha256(data).hexdigest() if data else "",
        size=len(data),
    )


def _summary(plan: Merge, diary: Path, current_year: int) -> MergeSummary:
    """What a caller can report: what was there, what it became, what arrived."""
    targets = plan.targets(current_year)
    mine = [st for st in plan.stats if st.path.parent == diary]
    fresh = [st for st in plan.stats if st.path.parent != diary]
    live = diary / CURRENT_NAME
    data = live.read_bytes() if live.is_file() else b""
    return MergeSummary(
        lines_before=sum(st.lines for st in mine),
        lines_after=sum(len(v) for v in targets.values()),
        added=sum(st.added for st in fresh),
        duplicates=sum(st.duplicates for st in fresh),
        years=sorted({year_of(e.ts) for entries in targets.values() for e in entries}),
        sha256=hashlib.sha256(data).hexdigest() if data else "",
        size=len(data),
    )


def _read_everything(diary: Path, imports: list[Path]) -> Merge:
    """The diary's own files first (so its bytes survive a duplicate), then the
    imports in the order they were given."""
    m = Merge(diary)
    for path in diary_files(diary):
        m.add(path)
    for path in imports:
        m.add(path)
    m.guard()
    return m


def _replan(diary: Path, imports: list[Path], wrote: int) -> Merge:
    """Read everything again, just before a replace.

    A refusal *after* something has already been written is not the same promise as
    one before, so it says which it is rather than claiming nothing happened.
    """
    try:
        return _read_everything(diary, imports)
    except DiaryError as e:
        if not wrote:
            raise
        raise DiaryWriteError(f"{e} -- and {_files(wrote)} had already been written")


def _apply(diary: Path, imports: list[Path], current_year: int, plan_total: int,
           out) -> Written:
    """Write the merged set out, re-reading everything immediately before each replace.

    The player appends with a fresh `fopen("ab")` per line and no lock, so every file
    is planned against the diary as it is *at that moment*: a track logged while we
    work is in the union rather than something the next replace drops, and the window
    a line could still slip through is one temp write wide instead of a whole run.

    The loop terminates without a retry budget because it is not a retry loop: each
    name is handled exactly once, and `names` only ever grows by a year file that was
    not there before -- a handful of them at most. What it deliberately does *not* do
    is refuse when the diary changes underneath it: re-reading is cheap, and a merge
    that gave up because nova was listening to music would be a worse tool.
    """
    try:
        diary.mkdir(parents=True, exist_ok=True)
    except OSError as e:
        raise DiaryError(f"cannot make {diary} ({e.strerror or e})")

    slug = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    seed = _replan(diary, imports, 0)
    if seed.total != plan_total:
        out(f"  re-read before writing: {_n_lines(abs(seed.total - plan_total))} "
            f"{'arrived' if seed.total > plan_total else 'went'} while merging")
    names = ordered_names(seed.targets(current_year))
    width = max([len(n) for n in names] or [0])
    wrote, kept, backups = 0, 0, []
    named: list[str] = []

    i = 0
    while i < len(names):
        name = names[i]
        i += 1
        merge_now = _replan(diary, imports, wrote)
        targets = merge_now.targets(current_year)
        for extra in ordered_names(targets):
            if extra not in names:                # a year file arrived while we worked
                names.append(extra)
        entries = targets.get(diary / name)
        if entries is None:
            out(f"  left      {name:<{width}}  nothing belongs to it now -- not touched")
            continue
        named.append(name)
        data = target_bytes(name, entries)
        path = diary / name
        try:
            current = path.read_bytes() if path.is_file() else None
        except OSError as e:
            raise DiaryWriteError(f"cannot read {path} to compare ({e.strerror or e})")
        if current == data:
            out(f"  kept      {name:<{width}}  {_n_lines(len(entries))}, already exactly this")
            kept += 1
            continue
        if current is None and not entries:
            continue                             # nothing to say and no file to make
        backup = None
        if current is not None:
            try:
                backup = _backup(path, slug, current)
            except OSError as e:
                raise DiaryWriteError(
                    f"cannot back {path} up before replacing it ({e.strerror or e}) -- "
                    f"refusing to overwrite a file it could not save first")
        try:
            _write_atomic(path, data)
        except OSError as e:
            raise DiaryWriteError(f"cannot write {path} ({e.strerror or e})")
        wrote += 1
        backups.append(backup)
        shown = f"  wrote     {name:<{width}}  {_n_lines(len(entries))}"
        if len(entries) == 0:
            shown += "  (emptied: every line in it belongs to another year)"
        if backup is not None:
            shown += f"   backup {backup.name}"
        out(shown)

    out("")
    parts = [f"{_files(wrote)} written"]
    if kept:
        parts.append(f"{_files(kept)} left exactly as they were")
    if backups:
        parts.append(f"{len(backups)} backup(s) beside the originals")
    out(", ".join(parts))
    out("nothing was removed: this tool only ever adds, and the player's own "
        "rollover and pruning are the only things that delete diary files.")
    return Written(named, wrote, kept)


def _report(plan: Merge, diary: Path, current_year: int, out) -> None:
    targets = plan.targets(current_year)
    out(f"diary  {diary}" + ("" if diary.is_dir() else "   (does not exist yet)"))
    width = max([len(str(s.path)) for s in plan.stats] or [0])
    for st in plan.stats:
        out(f"  in  {str(st.path):<{width}}  {_n_lines(st.lines)}, {st.unique} unique, "
            f"{st.added} added, {st.duplicates} duplicate(s) skipped")
    total = sum(len(v) for v in targets.values())
    years = sorted({current_year if p.name == CURRENT_NAME else archive_year(p.name)
                    for p in targets} - {None})
    out(f"  merged: {_n_lines(total)} in {len(targets)} file(s)"
        + (f"; years {', '.join(str(y) for y in years)}" if years else ""))
    names = ordered_names(targets)
    nwidth = max([len(n) for n in names] or [0])
    for name in names:
        entries = targets[diary / name]
        note = ""
        if diary.joinpath(name).is_file():
            try:
                note = "unchanged" if diary.joinpath(name).read_bytes() == target_bytes(name, entries) \
                    else "would rewrite"
            except OSError:
                note = ""
        else:
            note = "new file"
        out(f"    {name:<{nwidth}}  {_n_lines(len(entries))}" + (f"  ({note})" if note else ""))
    _prune_note(targets, current_year, out)


def _prune_note(targets: dict[Path, list[Entry]], current_year: int, out) -> None:
    """Say out loud when lines land in years the player deletes.

    `prune()` keeps this year and the one before it; everything older goes the next
    time a track is logged. Writing those files is still right -- dropping the lines
    silently is what "a merge must never lose a line" forbids -- but nova is told,
    with the count, so a disappearing archive is never a surprise.
    """
    cut = current_year - 2
    hits = sorted((archive_year(p.name), len(v)) for p, v in targets.items()
                  if archive_year(p.name) is not None and archive_year(p.name) <= cut and v)
    if not hits:
        return
    total = sum(n for _, n in hits)
    detail = ", ".join(f"history.{y}.jsonl.gz ({n})" for y, n in hits)
    out(f"  note: {_n_lines(total)} in years the player prunes (≤{max(hits)[0]}): {detail}")
    out("        they are written anyway -- dropping them would be worse -- but the "
        "player removes those files on its next logged track")


def _n_lines(n: int) -> str:
    return f"{n} line" + ("" if n == 1 else "s")


def _files(n: int) -> str:
    return f"{n} file" + ("" if n == 1 else "s")
