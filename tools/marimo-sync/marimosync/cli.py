"""marimo-sync -- keep an android music library in step with this computer.

    marimo-sync status              what's out of step (cheap: sizes + book-keeping)
    marimo-sync status --verify     ...and actually read the files on the device back
    marimo-sync sync                push the diff
    marimo-sync sync --waves        generate any missing waveform sidecars first
    marimo-sync sync --convert      bring the compressed library up to date from ~/Music
    marimo-sync verify              hash the device, report real mismatches, fix the manifest
    marimo-sync waves               just the sidecars
    marimo-sync convert             just the FLAC -> OPUS step
"""

from __future__ import annotations

import argparse
import datetime
import json
import shutil
import subprocess
import sys
from pathlib import Path

from .core import (AUDIO_EXT, MANIFEST_REL, HashCache, Library, Manifest,
                   build_plan, _rel_inside)
from .config import Config, ConfigError, ENV_PREFIX, config_path
from .transports import Device, TransportError, human_bytes, open_device
from .prep import albums_needing_waves, convert_targets, run_convert, run_waves

C_OK, C_WARN, C_BAD, C_DIM, C_OFF = "\033[32m", "\033[33m", "\033[31m", "\033[2m", "\033[0m"


def files(n: int) -> str:
    return f"{n} file" + ("" if n == 1 else "s")


def human(n: int) -> str:
    for unit, div in (("GB", 1 << 30), ("MB", 1 << 20), ("kB", 1 << 10)):
        if n >= div:
            return f"{n / div:.1f} {unit}"
    return f"{n} B"


def yn(colour: str, text: str, plain: bool) -> str:
    return text if plain else f"{colour}{text}{C_OFF}"


# ---------------------------------------------------------------------------

# every key a command-line flag can override
FLAG_KEYS = ("source", "device", "root", "flac_source", "wave_script",
             "convert_script", "adb", "hashcache", "scratch", "manifest",
             "diary", "diary_out", "diary_in", "diary_phone")


def config_flags(args) -> dict:
    """The config keys this invocation was given a flag for. One place, so `config`
    reports the same resolution every other command uses."""
    return {k: getattr(args, k) for k in FLAG_KEYS if hasattr(args, k)}


def resolve(args) -> tuple[Config, Device]:
    """Settings (defaults → file → env → flags) and the device they point at."""
    cfg = Config.load(path=getattr(args, "config", None), flags=config_flags(args))
    device = open_device(cfg["device"], cfg["root"], inventory=cfg.inventory)
    return cfg, device


def open_library(cfg: Config, source=None) -> Library:
    return Library(source or cfg.source, HashCache(cfg.hashcache))


# ---------------------------------------------------------------------------
# sidecars and conversion moved to prep.py, so the server and the window drive
# exactly the same stages the CLI does


# ---------------------------------------------------------------------------
# reporting

def report(lib: Library, device: Device, plan, *, plain: bool = False, verify: bool = False) -> None:
    src_bytes = sum(f.size for f in lib.files.values())
    print(f"library  {len(lib.albums)} albums / {len(lib.files)} files / {human(src_bytes)}   {lib.root}")
    print(f"device   {device.label}  {device.root}   {plan.device_files} files"
          + (f" / {human(sum(device.walk().values()))}" if False else ""))
    print()

    new = [a for a in plan.albums.values() if a.status == "new"]
    upd = [a for a in plan.albums.values() if a.status == "update"]
    orp = [a for a in plan.albums.values() if a.status == "orphan"]
    ok = [a for a in plan.albums.values() if a.status == "ok"]
    unverified = sum(a.unverified for a in plan.albums.values())
    nowaves = [a for a in plan.albums.values() if a.missing_sidecar or a.stale_sidecar]
    orphan_files = plan.orphan_files

    if new:
        print(yn(C_OK, f"NEW ON DEVICE ({len(new)})", plain))
        for a in new:
            note = f"  ({'; '.join(a.notes)})" if a.notes else ""
            print(f"  {a.name}  {C_DIM if not plain else ''}{len(a.push)} files, "
                  f"{human(a.push_bytes)}{note}{C_OFF if not plain else ''}")
        print()
    if upd:
        print(yn(C_WARN, f"OUT OF STEP ({len(upd)})", plain))
        for a in upd:
            print(f"  {a.name}  {C_DIM if not plain else ''}{a.short()}, {human(a.push_bytes)}{C_OFF if not plain else ''}")
        print()
    if orp:
        print(yn(C_BAD, f"ON DEVICE ONLY ({len(orp)})", plain))
        for a in orp:
            extra = f", {len(a.orphans)} file(s) not in the library" if (a.on_device and a.orphans and len(a.orphans) != a.on_device) else ""
            print(f"  {a.name}  {C_DIM if not plain else ''}{a.on_device} files{extra}{C_OFF if not plain else ''}")
        print()

    if plan.loose_orphans:
        print(yn(C_BAD, f"LOOSE FILES AT THE ROOT ({len(plan.loose_orphans)})", plain))
        for rel in plan.loose_orphans[:10]:
            print(f"  {rel}")
        if len(plan.loose_orphans) > 10:
            print(f"  ... {len(plan.loose_orphans) - 10} more")
        print()
    if unverified and not verify:
        print(yn(C_DIM, f"BELIEVED BUT UNVERIFIED  {unverified} file(s) match by size only -- `marimo-sync verify` to look inside", plain))
        print()
    if nowaves:
        print(yn(C_WARN, f"NO USABLE WAVEFORM SIDECAR ({len(nowaves)})", plain))
        for a in nowaves[:10]:
            why = "missing" if a.missing_sidecar else f"missing {len(a.stale_sidecar)} track(s)"
            print(f"  {a.name}  {C_DIM if not plain else ''}{why}{C_OFF if not plain else ''}")
        if len(nowaves) > 10:
            print(f"  ... {len(nowaves) - 10} more")
        print(f"  {C_DIM if not plain else ''}`marimo-sync waves` generates them (~1s per new album){C_OFF if not plain else ''}")
        print()

    if plan.renames:
        print(yn(C_OK, f"SAME ALBUM, OLD NAME ({len(plan.renames)})", plain))
        for a in plan.renames[:10]:
            print(f"  {a.name}")
            print(f"     {C_DIM if not plain else ''}-> {a.move_to}   "
                  f"{files(len(a.move_files))}, {human(a.move_bytes)}{C_OFF if not plain else ''}")
        if len(plan.renames) > 10:
            print(f"  ... {len(plan.renames) - 10} more")
        print(f"  {C_DIM if not plain else ''}these files are already on the device, so "
              f"`sync --adopt-renames` moves them there instead of uploading "
              f"({human(plan.rename_bytes)} saved){C_OFF if not plain else ''}")
        print()

    print(f"{yn(C_OK, str(len(ok)), plain)} album{'' if len(ok) == 1 else 's'} in sync")
    todo = plan.push_total
    if todo or orphan_files or plan.renames:
        line = f"-> {files(todo)}, {human(plan.push_bytes)} to push"
        if plan.renames:
            line += f"; {files(plan.rename_files)} to move ({human(plan.rename_bytes)} not uploaded)"
        if orphan_files:
            line += f"; {files(orphan_files)} orphaned (--prune)"
        print(line)


# ---------------------------------------------------------------------------
# commands

def cmd_status(args) -> int:
    cfg, device = resolve(args)
    lib = open_library(cfg)
    manifest = Manifest.load(device, cfg.manifest_rel)
    plan = build_plan(lib, device, manifest, verify=args.verify,
                      wave_check=not args.no_waves_check, limit=args.limit)
    if args.json:
        print(json.dumps(plan_to_json(lib, device, plan), indent=1))
        return 0
    report(lib, device, plan, plain=args.plain, verify=args.verify)
    if args.exit_code and (plan.push_total or plan.orphans):
        return 1
    return 0


def cmd_sync(args) -> int:
    if args.prepare:
        return sync_local_only(args)

    cfg, device = resolve(args)

    if args.convert:
        lib = open_library(cfg)
        rc = run_convert(cfg, convert_targets(cfg, lib), dry=args.dry_run)
        if rc:
            print("convert failed -- stopping before the device is touched", file=sys.stderr)
            return rc
        if args.dry_run:
            print("(--dry-run: no conversion done; re-run without it to convert)")
            return 0

    lib = open_library(cfg)
    if args.waves:
        names = albums_needing_waves(lib, list(lib.albums))
        if args.dry_run:
            # A dry run must not write. It used to generate them here, before the
            # dry-run check further down ever ran -- which made "show me what you'd
            # do" a mutation of the library.
            if names:
                problem = cfg.script_problem("wave_script")
                print(f"waveforms: {files(len(names))} would need a sidecar"
                      + (f", but {problem}" if problem else " (none made — this is a dry run)"))
            else:
                print("waveforms: nothing to do")
        elif names:
            rc = run_waves(cfg, lib.root, names)
            if rc:
                print("waveform generation failed -- stopping", file=sys.stderr)
                return rc
            lib = open_library(cfg)          # pick up the new sidecars

    manifest = Manifest.load(device, cfg.manifest_rel)
    plan = build_plan(lib, device, manifest, verify=args.verify,
                      wave_check=not args.no_waves_check, limit=args.limit)
    plan.albums = {k: v for k, v in plan.albums.items()
                   if (not args.album or args.album.lower() in k.lower())}
    for ap in plan.albums.values():
        ap.push_bytes = sum(lib.files[r].size for r in ap.push if r in lib.files)

    report(lib, device, plan, plain=args.plain, verify=args.verify)
    want_rename = bool(args.adopt_renames and plan.renames)
    if not plan.push_total and not want_rename and not (
            args.prune and (plan.orphans or plan.loose_orphans)):
        print("\nnothing to do")
        return 0

    if args.dry_run:
        print("\n--dry-run: stopping here")
        return 0

    free = device.free_bytes()
    if free is not None and plan.push_bytes > free:
        print(yn(C_WARN, f"note: {human(plan.push_bytes)} to send but only {human(free)} free "
                         f"on the device -- replacing files frees room as it goes", args.plain))

    if args.adopt_renames and plan.renames:
        print()
        for ap in plan.renames:
            moved = device.adopt_rename(ap.name, ap.move_to, ap.move_files)
            print(f"moved {files(moved)}: {ap.name} -> {ap.move_to}")
            manifest.forget(ap.name)
    elif plan.renames:
        print()
        print(yn(C_WARN, f"{files(plan.rename_files)} in {len(plan.renames)} album(s) are already "
                         f"on the device under an old name -- `sync --adopt-renames` would move "
                         f"them and save {human(plan.rename_bytes)}", args.plain))

    print()
    pushed_total = 0
    for ap in sorted(plan.albums.values(), key=lambda a: a.name):
        if not ap.push:
            continue
        device.push(lib.root, ap.push, {r: lib.files[r].size for r in ap.push})
        pushed_total += len(ap.push)
        # record what we sent, and what we merely believe
        for rel in ap.push:
            inside = _rel_inside(rel)
            manifest.record(ap.name, inside, lib.files[rel], lib.sha(rel), "pushed")
    print(f"pushed {pushed_total} file(s)")

    if args.prune:
        whole: set[str] = set()
        touched: list[str] = []
        for ap in plan.albums.values():
            if not ap.orphans:
                continue
            if ap.status == "rename" and args.adopt_renames:
                continue                 # its files were moved, not left behind
            device.remove(ap.orphans)
            touched.append(ap.name)
            if ap.on_device == len(ap.orphans) and not lib.album_files(ap.name):
                whole.add(ap.name)
                print(f"removed {ap.name} ({len(ap.orphans)} files)")
            else:
                print(f"removed {len(ap.orphans)} stray file(s) from {ap.name}")
        if plan.loose_orphans:
            device.remove(plan.loose_orphans)
            print(f"removed {len(plan.loose_orphans)} loose file(s) from the root")
        for name in device.prune_empty_albums(touched):
            if name not in whole:
                print(f"removed the now-empty folder {name}")

    # Record what we know: pushed files are certain, files we read back keep
    # their verdict, everything else stays exactly as trusted as it was.
    for ap in plan.albums.values():
        if lib.album_files(ap.name):
            manifest.refresh(lib, ap.name, pushed=set(ap.push),
                             device_hashes=plan.device_hashes)
    for name in list(manifest.albums):
        if not lib.album_files(name) and name not in plan.albums:
            manifest.forget(name)
    manifest.save(device)

    if args.waves:
        print()
        print("waveform sidecars are on the device now -- the app only reads them during a scan,")
        print("so tap rescan (settings) once, and every new album stops decoding on the phone.")
    return 0


def sync_local_only(args) -> int:
    """Convert and make waveforms, then stop — no device anywhere in sight.

    This is the first half of "the phone fetches over the network rather than being
    plugged in": get the library into the state the phone should end up with, and
    let it pull. It needs no cable, so it works on a machine the phone never meets.
    """
    cfg = Config.load(path=getattr(args, "config", None), flags=config_flags(args))

    if args.convert:
        lib = open_library(cfg)
        rc = run_convert(cfg, convert_targets(cfg, lib), dry=args.dry_run)
        if rc:
            print("convert failed", file=sys.stderr)
            return rc

    lib = open_library(cfg)
    if args.waves:
        names = albums_needing_waves(lib, list(lib.albums))
        if args.dry_run:
            if names:
                problem = cfg.script_problem("wave_script")
                print(f"waveforms: {files(len(names))} would need a sidecar"
                      + (f", but {problem}" if problem else " (none made — this is a dry run)"))
            else:
                print("waveforms: nothing to do")
        elif names:
            rc = run_waves(cfg, lib.root, names)
            if rc:
                print("waveform generation failed", file=sys.stderr)
                return rc
            lib = open_library(cfg)

    if args.dry_run:
        print("\n--dry-run: nothing was changed")
        return 0
    total = sum(f.size for f in lib.files.values())
    print(f"\nthe library is ready: {len(lib.albums)} albums, {files(len(lib.files))}, "
          f"{human(total)}")
    print("the phone can fetch it now — with the window open (or `marimo-sync serve` "
          "running), use Sync from desktop on the phone.")
    return 0


def cmd_verify(args) -> int:
    cfg, device = resolve(args)
    lib = open_library(cfg)
    manifest = Manifest.load(device, cfg.manifest_rel)
    print("reading the device back and hashing it -- this is the slow one, and the only")
    print("way to catch a file that changed without changing length")
    plan = build_plan(lib, device, manifest, verify=True, wave_check=not args.no_waves_check,
                      limit=args.limit)
    for a in plan.albums.values():
        a.unverified = 0
    report(lib, device, plan, plain=args.plain, verify=True)

    # Persist the verdicts: a file we read back and matched is now "hash" (trusted
    # for good), and one we read back and didn't match is "mismatch". That second
    # one is the point -- it means a plain `sync` replaces it without re-reading.
    for name in plan.albums:
        if lib.album_files(name):
            manifest.refresh(lib, name, device_hashes=plan.device_hashes)
    manifest.save(device)

    bad = [a for a in plan.albums.values() if a.mismatch]
    unread = plan.unreadable_files
    print(f"\n{len(plan.device_hashes)} file(s) read back; manifest refreshed ({manifest.updated})")
    if unread:
        print(yn(C_WARN, f"{files(unread)} could NOT be read back -- the device dropped the "
                         f"connection mid-hash. That is not a claim that they are wrong; "
                         f"run verify again to pick them up.", args.plain))
    if bad:
        print(f"{len(bad)} album(s) differ from the library in content, not just size --"
              "\n`marimo-sync sync` will replace them (they're marked now).")
    if args.exit_code and (bad or unread):
        return 1
    return 0


def cmd_doctor(args) -> int:
    cfg, device = resolve(args)
    source = cfg.source
    print(f"config   {cfg.path}" + ("" if cfg.path.is_file() else "   (does not exist yet — using defaults)"))
    print(f"source   {source}")
    print(f"         {'ok, directory' if source.is_dir() else 'MISSING -- no library here'}")
    for key in ("flac_source", "wave_script", "convert_script"):
        value = cfg[key]
        if value:
            problem = cfg.script_problem(key) if key.endswith("script") else None
            state = "ok" if (problem is None and (cfg.path_value(key) or Path()).exists()) else (problem or "missing")
            print(f"{key:<8} {value}   ({state})")
    print(f"device   {device.label}")
    print()

    rows = device.probe()
    if rows:
        width = max(len(n) for n, _, _ in rows)
        for name, ok, detail in rows:
            mark = yn(C_OK, "ok", args.plain) if ok else yn(C_BAD, "FAIL", args.plain)
            print(f"  {name:<{width}}  {mark}  {detail}")
    else:
        print("  (nothing to probe for this kind of device)")

    if source.is_dir():
        lib_bytes = sum(f.size for f in Library(source).files.values())
        free = device.free_bytes()
        print()
        print(f"library is {human(lib_bytes)}; device has {human_bytes(free)} free")
        if free is not None and lib_bytes > free:
            print(yn(C_BAD, "the whole library would not fit -- sync will still work, it only sends what's missing", args.plain))

    bad = [n for n, ok, _ in rows if not ok]
    if bad:
        print()
        print(yn(C_BAD, f"{len(bad)} check(s) failed: " + ", ".join(bad), args.plain))
        return 1
    return 0


def cmd_waves(args) -> int:
    # deliberately not resolve(): making waveforms reads the library and runs a
    # script. It has no business needing a phone to be reachable.
    cfg = Config.load(path=getattr(args, "config", None), flags=config_flags(args))
    lib = open_library(cfg)
    names = args.album_names or albums_needing_waves(lib, list(lib.albums))
    return run_waves(cfg, lib.root, names, required=True)


def cmd_convert(args) -> int:
    cfg = Config.load(path=getattr(args, "config", None), flags=config_flags(args))
    lib = open_library(cfg)
    return run_convert(cfg, convert_targets(cfg, lib), dry=args.dry_run)


def cmd_covers(args) -> int:
    from .covers import sync_covers

    # deliberately not resolve(): this is a local library operation (source ->
    # library), so it must work with no phone anywhere near the machine
    cfg = Config.load(path=getattr(args, "config", None), flags=config_flags(args))
    if cfg.flac_source is None:
        print("marimo-sync: no flac_source configured -- that's where the originals live",
              file=sys.stderr)
        return 2
    if not cfg.flac_source.is_dir():
        print(f"marimo-sync: {cfg.flac_source} isn't there (is the share mounted?)", file=sys.stderr)
        return 2
    lib = open_library(cfg)
    report = sync_covers(cfg, lib, dry_run=args.dry_run, tidy=not args.keep_others)

    what = "would copy" if args.dry_run else "copied"
    for rel in report["copied"]:
        print(f"  {what}: {rel}")
    for rel in report["removed"]:
        print(f"  {'would remove' if args.dry_run else 'removed'}: {rel}   (superseded)")
    print(f"\n{files(len(report['copied']))} {what}"
          + (f", {files(len(report['removed']))} {'would be ' if args.dry_run else ''}removed"
             if report["removed"] else "")
          + f"; {len(report['identical'])} already identical in the library"
          + f"; {report['source_missing_art']} album(s) with no art in the source (left alone)"
          + f"; {len(report['unmatched'])} with no source folder")
    if args.dry_run and (report["copied"] or report["removed"]):
        print("re-run without --dry-run to do it, then `marimo-sync sync` to send the change on")
    return 0


def _sha256(path: Path) -> str:
    import hashlib

    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _diary_shaped(name: str) -> bool:
    """The two names the app itself enumerates -- nothing else travels."""
    from .diary import CURRENT_NAME, archive_year

    return name == CURRENT_NAME or archive_year(name) is not None


def pull_diary(cfg: Config, device: Device) -> tuple[int, list[Path]]:
    """Fetch the phone's exported diary into `diary_in`, byte-checked.

    Byte-exactness is the point: what arrived is hashed and compared against the
    digest the *device* computes for the same file, so "it came across" is a
    measurement rather than a hope. Refuses when the phone has not exported anything
    yet, because that silence is a missing human step and saying so beats merging
    nothing and reporting success.
    """
    dest: Path = cfg.diary_in
    listing = device.walk()
    names = sorted(n for n in listing if n == Path(n).name and _diary_shaped(n))
    if not names:
        print(f"marimo-sync: diary: nothing exported yet in {device.root} -- press "
              f"export on the phone first", file=sys.stderr)
        return 2, []
    print(f"pulling {files(len(names))} ({human(sum(listing[n] for n in names))}) from "
          f"{device.label}  {device.root}  ->  {dest}")
    pulled = device.pull(dest, names)
    device_digests = device.hash_walk(names) or {}
    mismatched = []
    for name in pulled:
        local = _sha256(dest / name)
        want = device_digests.get(name)
        state = ("verified" if want == local else
                 "no digest from the device" if want is None else "MISMATCH")
        print(f"  {name}  {listing.get(name, '?')} bytes  sha256 {local[:16]}  {state}")
        if want is not None and want != local:
            mismatched.append(name)
    if mismatched:
        raise DiaryError(f"{len(mismatched)} file(s) did not match the device's own "
                         f"digest ({', '.join(mismatched)}) -- refusing to merge a copy "
                         f"that is not the original")
    return 0, [dest / n for n in pulled]


def push_diary(out_dir: Path, device: Device) -> int:
    """Copy the staged diary to the phone, where the app imports it from.

    Only the names the app itself enumerates go across (`history.jsonl` and
    `history.YYYY.jsonl.gz`), so our own `.bak-`/`.tmp-` leftovers next to them can
    never be offered to it as diary files.
    """
    from .diary import diary_files

    names = [p.name for p in diary_files(out_dir)]
    if not names:
        print(f"marimo-sync: diary: nothing staged in {out_dir} -- run "
              f"`marimo-sync diary --import FILE --publish` first, or point --diary-out "
              f"at where it was staged", file=sys.stderr)
        return 2
    print(f"pushing {files(len(names))} to {device.label}  {device.root}")
    pushed = device.push(out_dir, names)
    for name in names:
        print(f"  {name}")
    print(f"{files(pushed)} pushed -- the phone imports them from its Downloads folder")
    return 0


def cmd_diary(args) -> int:
    """Fetch, merge, stage and/or send the diary -- on a cable.

    Deliberately not resolve(): merging and staging touch no library and no device, which
    is why they work with the phone unplugged. --pull and --push are the two that want a
    device, and they ask for one *first*, so a phone that cannot be reached leaves the
    diary and the staging folder exactly as they were. (The normal path is over wifi:
    the app POSTs its diary to `serve`, and fetches the union back.)
    """
    from .diary import DiaryError, DiaryWriteError, merge, publish

    cfg = Config.load(path=getattr(args, "config", None), flags=config_flags(args))
    if not (args.sources or args.publish or args.push or args.pull):
        print("marimo-sync: diary: nothing to do -- give --pull, --import FILE, "
              "--publish or --push", file=sys.stderr)
        return 2
    device = None
    if args.pull or args.push:
        device = open_device(cfg["device"], cfg.diary_phone, inventory=cfg.inventory)
    sources = [Path(p) for p in (args.sources or [])]
    try:
        if args.pull:
            if args.dry_run:
                listing = device.walk()
                names = sorted(n for n in listing
                               if n == Path(n).name and _diary_shaped(n))
                print(f"{device.label}  {device.root} holds "
                      + (f"{files(len(names))}: " + ", ".join(names) if names else
                         "nothing -- press export on the phone first"))
                print("--dry-run: nothing was fetched, so there is nothing to merge; "
                      "run it again without --dry-run to pull and merge")
                return 0
            rc, pulled = pull_diary(cfg, device)
            if rc:
                return rc
            if not sources:                    # --pull supplies the sources by itself
                sources = pulled
        if sources:
            rc = merge(cfg.diary, sources, dry_run=args.dry_run,
                       publish_to=cfg.diary_out if args.publish else None)
            if rc:
                return rc
        elif args.publish:
            publish(cfg.diary, None, cfg.diary_out, dry_run=args.dry_run)
        if args.push:
            return push_diary(cfg.diary_out, device)
        return 0
    except DiaryError as e:
        print(f"marimo-sync: diary: {e}", file=sys.stderr)
        print("nothing was written -- the diary is exactly as it was.", file=sys.stderr)
        return 1
    except DiaryWriteError as e:
        print(f"marimo-sync: diary: {e}", file=sys.stderr)
        print("the files already written are complete; re-run to finish the rest.",
              file=sys.stderr)
        return 1


def cmd_app(args) -> int:
    """The server window. No device is resolved here, on purpose."""
    from .app import main as app_main
    passthrough = [a for a in sys.argv[1:] if a != "app"]
    return app_main(passthrough)


def cmd_serve(args) -> int:
    from .serve import serve as start_server

    # deliberately not resolve(): this is the thing the phone connects *to*, so it
    # must start happily with no phone anywhere near the machine.
    cfg = Config.load(path=getattr(args, "config", None), flags=config_flags(args))
    if not cfg.source.is_dir():
        print(f"marimo-sync: no library at {cfg.source} (set source in your config)",
              file=sys.stderr)
        return 2
    token = "" if args.no_auth else args.token
    httpd = start_server(cfg, host=args.bind, port=args.port, token=token,
                         allow_hashes=not args.no_hashes, quiet=args.quiet,
                         prepare_now=args.prepare_now)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nstopping")
    finally:
        httpd.server_close()
    return 0


def plan_to_json(lib: Library, device: Device, plan) -> dict:
    free = None
    try:
        free = device.free_bytes()
    except Exception:
        pass
    needs_sidecar = sum(1 for a in plan.albums.values() if a.missing_sidecar or a.stale_sidecar)
    return {
        "library": {"root": str(lib.root), "albums": len(lib.albums), "files": len(lib.files),
                    "bytes": sum(f.size for f in lib.files.values())},
        "device": {"label": device.label, "root": device.root, "files": plan.device_files,
                   "free_bytes": free},
        "albums": [
            {
                "name": a.name,
                "status": a.status,
                "push": len(a.push),
                "overwrite": len(a.overwrite),
                "orphans": len(a.orphans),
                "unverified": a.unverified,
                "missing_sidecar": a.missing_sidecar,
                "stale_sidecar": len(a.stale_sidecar),
                "on_device": a.on_device,
                "in_library": len(lib.album_files(a.name)),
                "bytes": a.push_bytes,
                "move_to": a.move_to,
                "move_files": len(a.move_files),
                "move_bytes": a.move_bytes,
            }
            for a in sorted(plan.albums.values(), key=lambda a: a.name)
        ],
        "push_files": plan.push_total,
        "push_bytes": plan.push_bytes,
        "albums_in_sync": sum(1 for a in plan.albums.values() if a.status == "ok"),
        "albums_total": len(plan.albums),
        "needs_sidecar": needs_sidecar,
        "unverified": sum(a.unverified for a in plan.albums.values()),
        "unreadable": plan.unreadable_files,
        "orphan_files": plan.orphan_files,
        "loose_orphans": plan.loose_orphans,
        "rename_files": plan.rename_files,
        "rename_bytes": plan.rename_bytes,
        "renames": len(plan.renames),
    }


# ---------------------------------------------------------------------------

NUMPY_KEYS = {"serve.port"}


def _coerce_set(key: str, value: str):
    """Strings from the command line, but some keys aren't strings."""
    if value == "":
        return None
    if key in NUMPY_KEYS:
        try:
            return int(value)
        except ValueError:
            return value
    return value


def cmd_config(args) -> int:
    from .config import DEFAULTS, HELP

    if args.path_only:
        print(config_path())
        return 0

    cfg = Config.load(path=args.config, flags=config_flags(args))

    if args.init:
        if cfg.path.is_file() and not args.force:
            print(f"{cfg.path} already exists -- --force to rewrite it", file=sys.stderr)
            return 1
        path = cfg.save({k: v for k, v in DEFAULTS.items()})
        print(f"wrote {path}")
        print("every key is at its default; edit the ones you care about, then run")
        print("`marimo-sync config` to see what it resolved to.")
        return 0

    if args.set:
        changes = {}
        for item in args.set:
            key, sep, value = item.partition("=")
            key = key.strip()
            if not sep:
                print(f"--set wants key=value, got {item!r}", file=sys.stderr)
                return 2
            if key.startswith("serve."):
                changes.setdefault("serve", {})[key[6:]] = _coerce_set(key, value)
            elif key in DEFAULTS:
                changes[key] = _coerce_set(key, value)
            else:
                print(f"unknown key {key!r} -- try one of: "
                      + ", ".join(sorted(DEFAULTS)), file=sys.stderr)
                return 2
        path = cfg.save(changes)
        print(f"wrote {path}")
        return 0

    if args.json:
        print(json.dumps({
            "path": str(cfg.path),
            "exists": cfg.path.is_file(),
            "values": {k: (None if v is None else v) for k, v in cfg.values.items()},
            "origins": cfg.origins,
            "help": HELP,
        }, indent=1))
        return 0

    rows = cfg.describe()
    width = max(len(k) for k, _, _ in rows)
    print(f"{cfg.path}" + ("" if cfg.path.is_file() else "   (doesn't exist yet — everything below is defaults)"))
    print()
    for key, value, origin in rows:
        note = "" if origin == "default" else f"   [{origin}]"
        print(f"  {key:<{width}}  {value}{note}")
    if args.long:
        print()
        for key in DEFAULTS:
            print(f"  {key:<{width}}  {HELP.get(key, '')}")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="marimo-sync", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config", metavar="PATH", help=f"config file (default {config_path()})")
    ap.add_argument("--source", help="the library to copy from (overrides the config)")
    ap.add_argument("--device", help="adb[:SERIAL], dir:/PATH or http://host:port")
    ap.add_argument("--root", help="music directory on the device")
    ap.add_argument("--flac-source", help="where the uncompressed originals live")
    ap.add_argument("--waves-script", help="a make-waveforms.py-compatible script")
    ap.add_argument("--convert-script", help="a convert_music.py-compatible script")
    ap.add_argument("--adb", help="the adb binary, if it isn't on PATH")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    ap.add_argument("--plain", action="store_true", help="no colour")
    ap.add_argument("--limit", type=int, help="only look at the first N albums (safe first run)")
    ap.add_argument("--exit-code", action="store_true", help="exit 1 if anything is out of step")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("config", help="show, create or change the settings")
    p.add_argument("--init", action="store_true", help="write a config file with every key at its default")
    p.add_argument("--force", action="store_true", help="with --init, overwrite an existing file")
    p.add_argument("--set", nargs="+", metavar="KEY=VALUE", help="change keys (empty value unsets)")
    p.add_argument("--path", dest="path_only", action="store_true", help="just print the config file path")
    p.add_argument("--long", action="store_true", help="explain what each key is for")
    p.set_defaults(func=cmd_config)

    p = sub.add_parser("status", help="what's out of step (cheap)")
    p.add_argument("--verify", action="store_true", help="hash the device instead of trusting sizes")
    p.add_argument("--no-waves-check", action="store_true")
    p.set_defaults(func=cmd_status)

    p = sub.add_parser("plan", help="alias for status")
    p.add_argument("--verify", action="store_true")
    p.add_argument("--no-waves-check", action="store_true")
    p.set_defaults(func=cmd_status)

    p = sub.add_parser("sync", help="push the diff to the device")
    p.add_argument("--waves", action="store_true", help="generate missing sidecars first")
    p.add_argument("--convert", action="store_true", help="update the compressed set from ~/Music first")
    p.add_argument("--prune", action="store_true", help="delete things on the device that aren't in the library")
    p.add_argument("--adopt-renames", action="store_true",
                   help="move files on the device when an album is the same album under an old name")
    p.add_argument("--verify", action="store_true", help="hash the device before deciding")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--no-waves-check", action="store_true")
    p.add_argument("--album", help="only this album (substring match)")
    p.add_argument("--prepare", action="store_true",
                   help="convert and make waveforms, then stop — no device needed, for "
                        "when the phone fetches over the network instead of being plugged in")
    p.set_defaults(func=cmd_sync)

    p = sub.add_parser("verify", help="hash the device and report real mismatches")
    p.add_argument("--no-waves-check", action="store_true")
    p.set_defaults(func=cmd_verify)

    p = sub.add_parser("doctor", help="check the device actually does what this tool needs")
    p.set_defaults(func=cmd_doctor)

    p = sub.add_parser("covers", help="bring album art across from the originals into the library")
    p.add_argument("--dry-run", action="store_true", help="show what it would do")
    p.add_argument("--keep-others", action="store_true",
                   help="only add art; don't remove a superseded cover the source no longer has")
    p.set_defaults(func=cmd_covers)

    p = sub.add_parser("app", help="the server window: convert, shade, serve, wait for the phone")
    p.set_defaults(func=cmd_app)

    p = sub.add_parser("serve", help="serve the library over the LAN so the phone can pull it")
    p.add_argument("--port", type=int, help="port (default from the config, else 8422)")
    p.add_argument("--bind", help="address to bind (default 0.0.0.0, all interfaces)")
    p.add_argument("--token", help="shared secret the phone must send (default: a fresh one per run)")
    p.add_argument("--no-auth", action="store_true", help="no token at all — trust the LAN")
    p.add_argument("--no-hashes", action="store_true", help="refuse to compute content hashes")
    p.add_argument("--quiet", action="store_true", help="don't log every request")
    p.add_argument("--prepare-now", action="store_true",
                   help="convert/shade/fetch art first, then serve (what the window does)")
    p.set_defaults(func=cmd_serve)

    p = sub.add_parser("waves", help="generate missing waveform sidecars locally")
    p.add_argument("album_names", nargs="*", help="specific album folders")
    p.set_defaults(func=cmd_waves)

    p = sub.add_parser("convert", help="bring the compressed library up to date from ~/Music")
    p.add_argument("--dry-run", action="store_true", help="show what it would convert, do nothing")
    p.set_defaults(func=cmd_convert)

    p = sub.add_parser("diary", help="merge a listening diary from the phone into the "
                                     "desktop's history.jsonl, and give it back")
    p.add_argument("--import", dest="sources", nargs="+", metavar="FILE",
                   help="diary files to merge in: history.jsonl or history.YYYY.jsonl.gz "
                        "(the app's export, or a copy of its diary)")
    p.add_argument("--diary", metavar="DIR",
                   help="the diary folder to merge into (default ~/.config/marimo)")
    p.add_argument("--pull", action="store_true",
                   help="fetch the phone's exported diary over the cable first; it is "
                        "then merged, and becomes the import source unless you named "
                        "--import files yourself (the normal path is wifi: the app POSTs "
                        "to `serve`)")
    p.add_argument("--diary-in", metavar="DIR",
                   help="where --pull fetches the phone's export to")
    p.add_argument("--publish", action="store_true",
                   help="also stage the merged diary in the folder the phone imports "
                        "from (diary_out) — no device needed")
    p.add_argument("--push", action="store_true",
                   help="copy the staged diary to the phone's Downloads — the only part "
                        "of this command that wants a device")
    p.add_argument("--diary-out", metavar="DIR",
                   help="where to stage the merged diary (default: the tool's data dir)")
    p.add_argument("--diary-phone", metavar="PATH",
                   help="where on the phone it belongs (default /sdcard/Download/marimo)")
    p.add_argument("--dry-run", action="store_true", help="print the plan, write nothing")
    p.set_defaults(func=cmd_diary)

    args = ap.parse_args(argv)
    try:
        return args.func(args)
    except TransportError as e:
        print(f"marimo-sync: {e}", file=sys.stderr)
        return 2
    except ConfigError as e:
        print(f"marimo-sync: {e}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("\ninterrupted", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
