#!/usr/bin/env python3
"""Convert nova's music library to opus (128k), preserving tags + art.

- FLAC -> opus 128k (tags preserved, art extracted to cover.jpg if embedded)
- mp3/m4a -> copied verbatim (lossy stays lossy)
- cover/folder/front image files copied alongside
- junk skipped (samba ~ dirs, *.zip, non-audio)

Usage:
  convert.py --dry            # dry run, show what it WOULD do
  convert.py --one "NAME"     # just one folder (for testing)
  convert.py                   # full run
"""
import argparse
import os
import shutil
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

def _default_library():
    base = os.environ.get("XDG_DATA_HOME") or os.path.join(
        os.path.expanduser("~"), ".local", "share")
    return os.path.join(base, "marimo-sync", "library")


SRC = os.path.join(os.path.expanduser("~"), "Music")
DST = _default_library()

AUDIO_FLAC = {".flac"}
AUDIO_LOSSY = {".mp3", ".m4a", ".aac", ".ogg", ".opus"}
IMAGE = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
JUNK_PREFIX = ("~",)
JUNK_NAMES = {"marimo-windows.zip"}


def is_junk_dir(name):
    if name in JUNK_NAMES:
        return True
    if any(name.startswith(p) for p in JUNK_PREFIX):
        return True
    # samba temp junk: short name ending in ~ with a digit, e.g. MG34FM~A
    if "~" in name and (name.endswith("~A") or name.endswith("~B") or name.endswith("~C")):
        return True
    if name.lower().endswith(".zip"):
        return True
    return False


def files_of(folder_path):
    try:
        return os.listdir(folder_path)
    except Exception:
        return []


def ext_of(path):
    return os.path.splitext(path)[1].lower()


def walk_audio(folder, prefix=""):
    """(flacs, lossy, images) below `folder`, as paths relative to it.

    Descends into subfolders on purpose: disc albums keep their tracks one level
    down (`TRAIL [Disc 1 2009]/...`), and looking only at the top level made the
    converter call two of Nova's albums "no audio" and skip them -- while
    `marimo-sync convert --dry-run` listed them as missing, so the two ends
    disagreed and the server retried them on every prepare, each time claiming
    success. One level of nesting was invisible; now it isn't.
    """
    flacs = []
    lossy = []
    images = []
    for name in files_of(folder):
        path = os.path.join(folder, name)
        rel = os.path.join(prefix, name) if prefix else name
        if os.path.isdir(path):
            if is_junk_dir(name):
                continue
            f, l, i = walk_audio(path, rel)
            flacs += f
            lossy += l
            images += i
            continue
        e = ext_of(name)
        if e in AUDIO_FLAC:
            flacs.append(rel)
        elif e in AUDIO_LOSSY:
            lossy.append(rel)
        elif e in IMAGE:
            images.append(rel)
    return flacs, lossy, images


def folder_has(src_album):
    """Everything convertible in an album, disc subfolders included."""
    flacs, lossy, _ = walk_audio(src_album)
    return flacs, lossy


def folder_is_complete(src_album, dst_album):
    """True if every flac in src has a corresponding .opus in dst, and every
    lossy + image file is present. Used to skip finished work and re-run
    folders cut off mid-conversion."""
    src_flacs, src_rest, src_images = walk_audio(src_album)
    dst_flacs, dst_rest, dst_images = walk_audio(dst_album)
    dst_all = set(dst_flacs + dst_rest + dst_images)
    for f in src_flacs:
        if os.path.splitext(f)[0] + ".opus" not in dst_all:
            return False
    for f in src_rest + src_images:
        if f not in dst_all:
            return False
    return True


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True)


def library_name(name):
    """What a source folder is called in the library.

    "[FLAC]" becomes "[OPUS]", and a folder with *no* format tag at all ("... WEB FLAC")
    gets "[OPUS]" appended -- naming the output after the source instead copied a second
    copy of an album the library already had as "... [OPUS]". Anything already tagged
    (`[M4A]`, `[OPUS]`) is left alone: a shape test, not a list of the tags I happened to
    expect. Keep in step with `library_name` in marimosync/prep.py.
    """
    if "[FLAC]" in name:
        return name.replace("[FLAC]", "[OPUS]")
    if "[" in name:
        return name
    return name + " [OPUS]"


def plan_album(src_album, dst_album):
    """Every job one album needs: (verb, src, dst), plus (nflac, nlossy).

    Audio, then the verbatim copies, then art -- and separately, one cover pulled from
    the first track's embedded art when the source carries no image at all, because a
    disc subfolder's cover is not the album's cover and the player looks at the root.
    """
    flacs, lossy, images = walk_audio(src_album)
    jobs = [("opus", os.path.join(src_album, f),
             os.path.join(dst_album, os.path.splitext(f)[0] + ".opus")) for f in flacs]
    jobs += [("copy", os.path.join(src_album, f), os.path.join(dst_album, f)) for f in lossy]
    jobs += [("art", os.path.join(src_album, f), os.path.join(dst_album, f)) for f in images]
    if not images and (flacs or lossy):
        jobs.append(("cover", os.path.join(src_album, (flacs + lossy)[0]),
                     os.path.join(dst_album, "cover.jpg")))
    return jobs, len(flacs), len(lossy)


def run_job(verb, src, dst):
    """One file's worth of work. Returns an error string, or None.

    Runs in a pool. ffmpeg is a separate process, so threads are enough -- and the wall
    for a library rebuild is the share rather than the CPU, so what matters is having
    several reads in flight at once instead of one ffmpeg after another.
    """
    parent = os.path.dirname(dst)
    if parent and not os.path.isdir(parent):
        os.makedirs(parent, exist_ok=True)      # a disc subfolder, mirrored
    if verb == "opus":
        if os.path.exists(dst) and os.path.getsize(dst) > 0:
            return None                          # resumed: already converted
        r = run(["ffmpeg", "-y", "-i", src, "-c:a", "libopus", "-b:a", "128k",
                 "-map_metadata", "0", dst])
        if r.returncode != 0 or not os.path.exists(dst):
            return f"convert {os.path.basename(src)}: {r.stderr[-200:]}"
        return None
    if verb == "cover":
        extract_art(src, dst)
        return None
    try:
        shutil.copy2(src, dst)
        return None
    except Exception as e:
        return f"copy {os.path.basename(src)}: {e}"


def extract_art(src_flac, out_opus):
    """If the flac has embedded art and the folder has no cover image, write cover.jpg
    next to the opus. Uses ffmpeg (no metaflac dependency). Cleans up if there's no art."""
    img = os.path.join(os.path.dirname(out_opus), "cover.jpg")
    if os.path.exists(img):
        return
    r = run(["ffmpeg", "-y", "-i", src_flac, "-map", "0:v", "-frames:v", "1", img])
    if r.returncode != 0 or not os.path.exists(img) or os.path.getsize(img) == 0:
        # no embedded picture — remove the placeholder so the folder stays clean
        if os.path.exists(img):
            try:
                os.remove(img)
            except Exception:
                pass


def main():
    global SRC, DST
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry", action="store_true")
    ap.add_argument("--one", metavar="NAME")
    ap.add_argument("--jobs", type=int, default=min(12, os.cpu_count() or 4),
                    help="how many files to convert at once (default 12)")
    ap.add_argument("--src", metavar="DIR", default=SRC,
                    help="where the originals live (default ~/Music)")
    ap.add_argument("--dst", metavar="DIR", default=DST,
                    help="the compressed library to build (default <data>/marimo-sync/library)")
    args = ap.parse_args()
    SRC = os.path.abspath(os.path.expanduser(args.src))
    DST = os.path.abspath(os.path.expanduser(args.dst))

    if not os.path.isdir(SRC):
        print(f"source missing: {SRC}")
        return

    os.makedirs(DST, exist_ok=True)

    entries = sorted(os.listdir(SRC))
    if args.one:
        entries = [e for e in entries if args.one.lower() in e.lower()]

    tot_flac = tot_copy = 0
    big_errs = []
    jobs = []                    # (album, verb, src, dst): the whole run, planned first
    for name in entries:
        src_album = os.path.join(SRC, name)
        if not os.path.isdir(src_album):
            continue
        if name.startswith("."):
            print(f"SKIP hidden: {name}")        # .Trash-1000 lives on the share
            continue
        if is_junk_dir(name):
            print(f"SKIP junk: {name}")
            continue
        flacs, lossy = folder_has(src_album)
        if not flacs and not lossy:
            print(f"SKIP no-audio: {name}")
            continue

        # The library's own naming: an untagged source ("... WEB FLAC") lands as the
        # album already there rather than a second copy beside it.
        dst_name = library_name(name)
        dst_album = os.path.join(DST, dst_name)
        # Re-run incomplete folders: if the dest exists but is missing opus
        # outputs for source flacs, (re)convert it instead of skipping.
        # A dry run consults this too -- skipping it made `--dry` report every
        # album as "would convert", which is a lie about a library that's done.
        if os.path.exists(dst_album):
            if folder_is_complete(src_album, dst_album):
                print(f"{'WOULD SKIP, exists' if args.dry else 'EXISTS, skip'}: {dst_name}")
                continue
            print(f"{'WOULD RE-RUN, incomplete' if args.dry else 'INCOMPLETE, re-run'}: {dst_name}")

        mode = "FLAC->opus" if flacs else "copy"
        print(f"=== {mode}: {name} ({len(flacs)} flac / {len(lossy)} lossy)")
        album_jobs, nf, nc = plan_album(src_album, dst_album)
        tot_flac += nf
        tot_copy += nc
        jobs += [(name, v, s, d) for v, s, d in album_jobs]

    if args.dry:
        for _name, verb, s, _d in jobs:
            what = {"opus": "convert", "copy": "copy", "art": "copy art",
                    "cover": "extract art"}[verb]
            print(f"  WOULD {what} {os.path.relpath(s, SRC)}")
    elif jobs:
        done = 0
        t0 = time.time()
        with ThreadPoolExecutor(max_workers=max(1, args.jobs)) as pool:
            pending = {pool.submit(run_job, v, s, d): (n, v, s) for n, v, s, d in jobs}
            for fut in as_completed(pending):
                album, _verb, src_path = pending[fut]
                done += 1
                try:
                    err = fut.result()
                except Exception as e:              # one bad file must not stop the run
                    err = f"{type(e).__name__}: {e}"
                if err:
                    big_errs.append(f"[{album}] {err}")
                    print("  ERR", err, flush=True)
                if done % 50 == 0 or done == len(jobs):
                    el = time.time() - t0
                    rate = f"{done / el:.1f}/s" if el else "-"
                    print(f"  {done}/{len(jobs)} files, {el:.0f}s ({rate})", flush=True)

    print(f"\nDONE. {tot_flac} flac->opus, {tot_copy} lossy copied.")
    if big_errs:
        print(f"{len(big_errs)} ERRORS:")
        for e in big_errs[:25]:
            print(" ", e)


if __name__ == "__main__":
    main()
