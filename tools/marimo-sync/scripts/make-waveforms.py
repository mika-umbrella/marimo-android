#!/usr/bin/env python3
"""Precompute marimo waveform envelopes for a music library.

Decoding a track on the phone costs ~19s (Android runs the audio decoder in a
separate process and charges ~300us per buffer hand-off, ~7850 of them per
track). Here it costs ~0.2s, so the phone never has to decode a track at all.

Writes one small sidecar per album folder -- `waves.marimo`, alongside the
`cover.jpg` that already lives there. It travels with the album, and importing
one album only regenerates that album.

    magic "MWAVS001" | uint32 count | (uint16 nameLen | name UTF-8 | 96 peaks)*

The 96 peaks are the same thing the app computes for itself: RMS per bucket,
sqrt curve, normalised by the 95th percentile, clamped to 4..100, from a mono
decode at RATE (see the note on RATE -- do not lower it).

usage: make-waveforms.py <library-root> [more roots...]
       make-waveforms.py <library-root> --outdir /tmp/mirror   # don't touch the library
"""
import argparse
import array
import os
import subprocess
import sys
import time
import unicodedata
from concurrent.futures import ThreadPoolExecutor

BUCKETS = 96
AUDIO_EXT = {".flac", ".mp3", ".m4a", ".opus", ".ogg", ".wav", ".aac"}
MAGIC = b"MWAVS001"
SIDECAR = "waves.marimo"

# A 96-bucket RMS envelope needs almost no bandwidth -- but do NOT go as low as
# 1 kHz: measured against a full 44.1 kHz decode, 1 kHz collapses the tail
# (buckets 90-95 read 84 53 9 6 5 5 4 where the truth is 82 62 60 66 69 71 33 --
# the steep resampler filter trims the end of the stream). Against 44.1 kHz:
#   1 kHz -> max bar error 66 (unusable)   4 kHz -> max 3
#   8 kHz -> max 1, mean 0.3   <- shipped
RATE = 8000
STRIDE = 4          # same decimation the app uses


def envelope(path):
    """96 peak values for one file, or None if it won't decode."""
    try:
        p = subprocess.run(
            ["ffmpeg", "-v", "error", "-nostdin", "-i", path,
             "-map", "0:a:0", "-ac", "1", "-ar", str(RATE), "-f", "s16le", "-"],
            capture_output=True)
    except Exception:
        return None
    if p.returncode != 0 or not p.stdout:
        return None
    a = array.array("h")
    a.frombytes(p.stdout[: len(p.stdout) // 2 * 2])
    n = len(a)
    if n == 0:
        return None

    sumsq = [0.0] * BUCKETS
    counts = [0] * BUCKETS
    for i in range(0, n, STRIDE):
        s = a[i]
        b = i * BUCKETS // n
        if b >= BUCKETS:
            b = BUCKETS - 1
        sumsq[b] += s * s
        counts[b] += 1

    rms = [(sumsq[i] / counts[i]) ** 0.5 if counts[i] else 0.0
           for i in range(BUCKETS)]
    ref = sorted(rms)[int(BUCKETS * 0.95)] or 1.0
    out = bytearray(BUCKETS)
    for i in range(BUCKETS):
        if counts[i] == 0:
            out[i] = 0
        else:
            v = int(100.0 * ((rms[i] / ref) ** 0.5))
            out[i] = max(4, min(100, v))
    return bytes(out)


def nfc(s):
    return unicodedata.normalize("NFC", s)


def read_sidecar(path):
    """{filename: 96 bytes} -- names normalised the same way write does."""
    if not os.path.exists(path):
        return {}
    try:
        with open(path, "rb") as f:
            blob = f.read()
    except OSError:
        return {}
    if not blob.startswith(MAGIC):
        return {}
    n = int.from_bytes(blob[8:12], "little")
    out, off = {}, 12
    for _ in range(n):
        if off + 2 > len(blob):
            break
        ln = int.from_bytes(blob[off:off + 2], "little")
        off += 2
        if off + ln + BUCKETS > len(blob):
            break
        name = blob[off:off + ln].decode("utf-8", "replace")
        off += ln
        out[name] = blob[off:off + BUCKETS]
        off += BUCKETS
    return out


def write_sidecar(path, entries):
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(MAGIC)
        f.write(len(entries).to_bytes(4, "little"))
        for name in sorted(entries):
            b = name.encode("utf-8")
            f.write(len(b).to_bytes(2, "little"))
            f.write(b)
            f.write(entries[name])
    os.replace(tmp, path)


def album_dirs(roots):
    """Every directory that directly contains audio files."""
    for root in roots:
        for dirpath, dirnames, filenames in os.walk(os.path.abspath(root)):
            dirnames.sort()
            if any(os.path.splitext(f)[1].lower() in AUDIO_EXT for f in filenames):
                yield dirpath, sorted(filenames)


def album_as_one(root):
    """Every audio file at or below `root`, as (basename, full_path).

    Basenames because that's what the library and the phone match on
    (`Library.sidecar_gaps` compares `Path(r).name`, and the app looks up by
    filename), and one sidecar per album because that's where they look for it.
    """
    entries = []
    for dirpath, dirnames, filenames in os.walk(os.path.abspath(root)):
        dirnames.sort()
        for f in sorted(filenames):
            if os.path.splitext(f)[1].lower() in AUDIO_EXT:
                entries.append((f, os.path.join(dirpath, f)))
    return entries


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("roots", nargs="+")
    ap.add_argument("--outdir", default=None,
                    help="write sidecars into a mirrored tree instead of in place")
    ap.add_argument("--jobs", type=int, default=min(8, os.cpu_count() or 4))
    ap.add_argument("--redraw", action="store_true",
                    help="recompute even where a sidecar already has an entry")
    ap.add_argument("--per-album", action="store_true",
                    help="treat each given root as ONE album -- subfolders included, "
                         "sidecar written at the root. Use for album folders: a disc "
                         "album keeps its tracks one level down, and shading each disc "
                         "separately writes sidecars nothing reads")
    args = ap.parse_args()

    roots = [os.path.abspath(r) for r in args.roots]
    base = roots[0]

    # plan first so the progress line is honest
    plan = []           # (album_dir, sidecar_path, [(name, full_path)])
    albums_seen = 0     # albums that have audio at all, whether or not they need work
    if args.per_album:
        for root in roots:
            entries = album_as_one(root)
            if not entries:
                continue
            albums_seen += 1
            out = (os.path.join(args.outdir, os.path.basename(root), SIDECAR)
                   if args.outdir else os.path.join(root, SIDECAR))
            have = {} if args.redraw else read_sidecar(out)
            todo = [(f, p) for f, p in entries if nfc(f) not in have]
            if todo:
                plan.append((out, have, todo))
    else:
        for adir, files in album_dirs(roots):
            audio = [f for f in files if os.path.splitext(f)[1].lower() in AUDIO_EXT]
            if not audio:
                continue
            albums_seen += 1
            rel = os.path.relpath(adir, base)
            out = (os.path.join(args.outdir, rel, SIDECAR) if args.outdir
                   else os.path.join(adir, SIDECAR))
            have = {} if args.redraw else read_sidecar(out)
            todo = [f for f in audio if nfc(f) not in have]
            if todo:
                plan.append((out, have, [(f, os.path.join(adir, f)) for f in todo]))

    total = sum(len(t) for _, _, t in plan)
    print(f"{len(roots)} root(s), {len(plan)} album(s) to update, "
          f"{total} track(s) to compute, {args.jobs} jobs", flush=True)
    if not total:
        # Nothing to do is the *healthy* case once everything is shaded -- and this is
        # where it used to crash on an undefined name, which the server faithfully
        # reported as "waveforms exited 1" while the library was in fact complete.
        if albums_seen:
            print("everything already has a sidecar")
        else:
            print("no albums containing audio found under the given root(s)")
        return

    t0 = time.time()
    done = 0
    failed = []
    for out, have, todo in plan:
        with ThreadPoolExecutor(max_workers=args.jobs) as pool:
            results = list(pool.map(lambda t: envelope(t[1]), todo))
        for (name, path), peaks in zip(todo, results):
            if peaks is None:
                failed.append(path)
            else:
                have[nfc(name)] = peaks
        os.makedirs(os.path.dirname(out), exist_ok=True)
        write_sidecar(out, have)
        done += len(todo)
        el = time.time() - t0
        rate = done / el if el else 0
        left = (total - done) / rate if rate else 0
        print(f"  {done}/{total}  {el:.0f}s elapsed, ~{left:.0f}s left", flush=True)

    el = time.time() - t0
    print(f"done: {done} peaks in {el:.0f}s ({done/el:.1f}/s)" if el else "")
    if failed:
        print(f"{len(failed)} failed to decode:", file=sys.stderr)
        for p in failed[:10]:
            print("  " + p, file=sys.stderr)


if __name__ == "__main__":
    main()
