# marimo-sync

Keeps a music library in step with an Android phone: what's out of step, what to
send, what to move, and what the phone actually holds.

## Getting started

```bash
marimo-sync doctor                    # is this device set up? (run this first)
marimo-sync status                    # what's out of step
marimo-sync config --init             # write a config file to edit
marimo-sync-gui                       # or: the window, with a Settings panel
```

**Everything path-shaped is configurable, and nothing here carries anyone's paths.**
Settings resolve in this order — later wins:

| | |
|---|---|
| built-in defaults | ordinary places (`~/Music`, `adb`, `/sdcard/Music`) |
| config file | `$XDG_CONFIG_HOME/marimo-sync/config.json` (i.e. `~/.config/marimo-sync/config.json`) |
| environment | `MARIMO_SYNC_SOURCE`, `MARIMO_SYNC_DEVICE`, … one per key |
| flags | `--source`, `--device`, `--root`, `--waves-script`, … |

`marimo-sync config` prints every value **and where it came from**, which is the
command to reach for when something resolves surprisingly:

```
  source          /music/compressed   [/home/you/.config/marimo-sync/config.json]
  device          adb:ABC123         [env MARIMO_SYNC_DEVICE]
  root            /sdcard/Music      [--flag]
  wave_script     (unset)
```

| key | what it is |
|---|---|
| `source` | the library to copy from — a directory of album folders |
| `device` | `adb`, `adb:SERIAL`, `dir:/path`, or `http://host:port` |
| `root` | the music directory on the device |
| `flac_source` | where uncompressed originals live, for `sync --convert` |
| `wave_script` / `convert_script` | your equivalents of the two helper scripts, if you have them |
| `adb` | the adb binary, if it isn't on `PATH` |
| `manifest` | where the device-side manifest lives, relative to the device root |
| `hashcache`, `scratch` | where hashes are cached, where push tars are built |
| `diary` | the player's diary folder (`history.jsonl` + its gzipped years), for `diary` |
| `diary_out` | where `diary --publish` stages the merged diary for the phone |
| `diary_phone` | where the app imports it from on the phone (`/sdcard/Download/marimo`) |
| `serve` | `{port, bind, token}` for the LAN mode |

If a helper script isn't configured, the stage that needs it says so and the rest
carries on — nothing here insists on a particular transcoder.

## What it does

| stage | what | cost |
|---|---|---|
| **convert** | originals → compressed library | minutes, only for new albums |
| **waves** | a `waves.marimo` sidecar per album, so the player never decodes | ~1s per album |
| **sync** | push the difference | seconds when little changed |

```bash
marimo-sync status                 # what's out of step
marimo-sync sync                   # push the diff
marimo-sync sync --convert --waves --prepare
                                   # convert + make waveforms, then stop — no device
                                   # needed, for when the phone fetches over the network
marimo-sync sync --adopt-renames   # move files that are already there under an old name
marimo-sync sync --waves --convert # the whole pipeline, including the push
marimo-sync sync --prune           # ...and delete what the library no longer has
marimo-sync verify                 # read every file back and hash it (slow, once)
```

`convert` and `waves` (and `Prepare` in the window) work with **no phone anywhere near
the machine** — they're local work, and needing a device to do them was a bug. `sync`
itself still needs one, which is the point: it's the step that moves bytes.

`status` is cheap: sizes and a manifest, no device reads. `verify` is the expensive
truth, and it reads **every** file back — including ones already believed correct,
because a file can be corrupted without changing length and that's the only case
verify exists to catch.

## The manifest

Per device, at `<root>/../.marimo-sync/manifest.json` — deliberately *outside* the
music tree, so a player's own scan never sees it. Per file it records size, source
mtime, sha256, and the **provenance** of that knowledge:

| match | means |
|---|---|
| `pushed` | we sent it; the bytes are certain |
| `hash` | we read the device copy back and it matched |
| `reported` | a phone that pulls told us what it holds; we can't check |
| `size` | it was already there and agreed on length; nobody has looked inside |
| `mismatch` | we read it back and it differed — the next `sync` replaces it |

So a run costs what it needs to and no more, and the tool never claims more
certainty than it has.

## Renames

Renaming an album folder in the library looks, from the outside, like "delete
these files, upload those". When every file on the device also exists in a library
album under the same relative name and the same size, it's the same album under an
old name — and `sync --adopt-renames` **moves** the files on the device instead.
Instant, where uploading the same bytes again is hundreds of megabytes.

That isn't hypothetical: 19 albums on the developer's phone were sitting there
twice — audio under the old name, a bare sidecar under the new one, because the
sidecar pipeline re-pushed after the rename and created the new folders while the
audio stayed behind. Detection found all 19: 543.9 MB that never needed to move.

The moved files are recorded as believed-by-size, not verified, so the provenance
stays honest.

## The phone pulls over the LAN (no cable, no developer options)

```bash
marimo-sync serve
```

Serves the library's contents and its bytes so an app on the phone can work out
what it's missing and fetch it — no USB debugging, no MTP, and the phone writes
with its own storage access.

| endpoint | what |
|---|---|
| `GET /api/health` | is this a marimo-sync |
| `GET /api/index.json` | every album and file, with sizes (`?hashes=1` for sha256) |
| `GET /api/file/<album>/<file>` | one file's bytes, resumable via `Range` |
| `GET /api/album/<name>.tar` | a whole album as one tar, also resumable |
| `GET /api/manifest.json` · `POST /api/manifest` | the last inventory the phone reported |

A token is required by default (fresh per run, printed; `X-Marimo-Token` or
`?token=`), because this publishes a music library to the network. `--no-auth`
turns that off. Filenames in the index and the tars are album-relative paths, so
`[Disc N]` subfolders survive and can't collide on extraction.

With `--device http://host:port`, `status` reads the phone's reported inventory
instead of walking anything, so the desktop keeps its view of the phone.

### The phone pulls — and the desktop window serves while it's open

**Open the window (`marimo-sync-gui`) and it serves.** There's nothing to start: the
library is published on your LAN for as long as the window is open, and stopped when
you close it. The header says where and with which token:

```
serving  on · http://192.168.0.10:8422 · token 1a2b3c4d5e6f
```

The **token is made once and saved in your config** rather than fresh per run — a
per-run token would break the phone's saved one every time you opened the window.
It's generated from the OS's randomness, per machine, so **every desktop gets its own
different one** (there is no shared or built-in token anywhere). Change it in Settings
(or `marimo-sync config --set serve.token=…`) and use the same one on the phone.
Untick *serve to phone* to turn it off (remembered).

**Point it at a second computer and the phone remembers that machine's token too.**
Tokens are kept per address in the app, so switching between desktops needs no
retyping — type the address and its token fills itself in.

**Music only ever flows desktop → phone.** `serve` reads the library and never writes
into it; the phone pulls and writes into its own music folder. The only thing the phone
sends back is a list of filenames and sizes, which the desktop keeps in its cache
directory. So a second computer is a *source* you can push from, never somewhere music
lands. `serve_smoke` asserts this: it snapshots every file's size and mtime around a
full serve-and-fetch cycle and fails if a single one moved.

`marimo-sync serve` still works on its own if you'd rather run it headless — and if
you do, **redirect its output to a file rather than piping it into something that
exits** (`serve | head` closes the pipe, and logging used to be able to kill requests;
that's fixed, but the habit is still right).

The app side lives in `marimo-android`: Settings → **sync with desktop**, where you
put that address and token, then *check what's missing* and *fetch*. It fetches only
what differs, file by file, resuming if the wifi drops, and writes into the same music
folder the player already scans — no cable, no USB debugging, no new permissions. It
tells the desktop what it ended up holding, so `status --device http://host:port` keeps
working without the phone attached.

## The phone's listening diary, merged in

The phone and this computer keep the *same* diary — one JSON object per line in
`~/.config/marimo/history.jsonl`, finished years gzipped beside it — so bringing
one side's plays into the other is a merge, not a conversion:

```bash
marimo-sync diary --import ~/phone-history.jsonl        # or history.2025.jsonl.gz
marimo-sync diary --import a.jsonl b.jsonl --dry-run    # the plan, and nothing written
```

Lines are deduped on `(ts, artist, title)`, sorted by `ts`, and bucketed by
**local** year: this year into `history.jsonl`, earlier years into
`history.YYYY.jsonl.gz`. A kept line is written back as **the exact bytes it
arrived as** — nothing is re-serialised, so key order, escaping and number
formatting survive untouched — and every file it replaces is first copied to a
hidden `.history.jsonl.bak-YYYYMMDD-HHMMSS` beside it.

It refuses, writing nothing at all, if any input cannot be read or parsed, or if
the result would hold fewer distinct plays than one of its inputs did: an
unreadable file is not an empty file, and a corrupt line is not a line to skip.
It also never deletes anything — no pruning, no tidying of old archives.

Two details come straight out of the player's own code
(`marimo-desktop/src/history.c`), and both are handled rather than documented away:

| the player does | so the merge |
|---|---|
| appends with one `fopen("ab")` per line, holding no lock | re-reads the live files immediately before each replace, so a track logged meanwhile is not dropped |
| archives **and removes** a `history.jsonl` whose *mtime* is another year | writes a brand-new file every time, never carrying the replaced file's timestamps across |
| prunes `history.<Y>.jsonl.gz` at `current_year - 2` and older on each logged track | says so when lines land in those years ("N lines in years the player prunes (≤2024)") instead of dropping them quietly |

**The one window that is not sealed, stated plainly.** There is no lock on the
player's side, so between the merge's last re-read of a file and the rename that
replaces it, a line the player appends lands in the inode the rename unlinks — that
append is lost, and the `.bak-` copy holds the bytes that were compared, so it will
not have it either. Re-reading immediately before each replace makes that window one
temp write wide (microseconds), not the length of the run, but it is not zero. If it
ever bites, the tell is a play missing from the phone's diary *and* absent from the
backup; the remedy is to run the merge again with the player idle — nothing needs
repairing, because the phone's copy still has the line.

### Giving the merged diary back to the phone

Both recaps should hold the same history, so the merge goes back the other way too —
over the link the window already serves. **There is nothing to do on the desktop:** the
app posts its diary when it syncs, the desktop merges it and hands the merged bytes
straight back, and the app imports them.

Two taps on the phone, in the app: **Settings → sync with desktop**, then *check what's
missing* and *fetch*. That one sync carries the diary as well as the music.

On the wire that is `POST /api/diary` — the phone's diary files in, the merged diary out,
the same token as everything else — and the numbers it answers with (before, after,
added, duplicates, digest) are what the window's **Diary…** panel shows. The merge is the
same code the command line uses, so the rule cannot drift between the two paths.

It is a write to the player's live diary, so it is switchable off: `serve.diary` (see
`marimo-sync config`). Switched off, the endpoint answers 409 and writes nothing — 409
rather than 403, because the app reads 401/403 as "the token does not match". Switched
on, a diary the merge cannot accept comes back as an error with the reason rather than a
200 that quietly changed nothing. Nothing is ever deleted, and every replaced file is
backed up beside it.

**A merged diary cannot be split back apart.** Nothing in a line records which
device wrote it, so once the two histories are one, "what I played on the phone" is
no longer a question that can be asked — that is the price of both recaps holding
the same history, and it is the reason the merge is byte-preserving: what came from
the phone is still, byte for byte, what came from the phone.

<details>
<summary>The cable fallback — not the normal path</summary>

For a phone on USB with the desktop beside it, the same merge runs from the shell.
`--device` is a **global** flag: it goes *before* the subcommand, or it is not parsed
as the device at all.

```bash
marimo-sync diary --pull                                           # fetch the export, then merge it
marimo-sync diary --pull --publish --push --device adb:TITAN20000045721   # the whole round trip
marimo-sync diary --import FILE --publish                          # no phone: merge a file you have
```

| flag | what it does | needs a phone? |
|---|---|---|
| `--pull` | fetch the phone's export into `diary_in`, and merge it | yes |
| `--import FILE …` | merge those files into the diary | no |
| `--publish` | copy the merged diary into `diary_out`, byte for byte | **no** |
| `--push` | copy the staged files to the phone (its `Download/marimo`) | **yes** |

`--pull` and `--push` are the only parts that want a device, and they ask for one
*first*, so a phone that cannot be reached leaves the diary and the staging folders
exactly as they were. `--pull` checks what it fetched against the digest the device
computes for the same file, and refuses when the phone has not exported anything yet —
"press export on the phone first" — instead of merging nothing and reporting success.
`--publish` stages the *whole* diary, not just what the merge touched, as copies of the
diary's own bytes, and leaves alone any staged file whose bytes already match. With a
`dir:` device the files land in that directory itself: a `dir:` device has no notion of
`/sdcard`, so aim `--device dir:…` at the folder the app reads.
</details>

### The rule exists twice, so the bytes are pinned

The app merges a diary back in with the same rule (`DiaryImport.java`), and two
implementations agreeing in prose is not agreement. So `tests/diary_smoke.py`
section 11 builds one fixed case — fixed lines, a fixed clock — and asserts three
digests, and **the app's `DiaryImportTest` is expected to assert the same three**:

| what | sha256 |
|---|---|
| `history.jsonl` bytes as written | `bdb1519f5ac4a3d3f24d2b46d4bde44870fa0ce5315a00a779ead3cb6a67074f` |
| `history.<last year>.jsonl.gz`, after decompression | `9bdf8a128ca670bb07a55a7be8c1a885caf9286cbe4544cb15738e04e7506f05` |
| `history.<current−2>.jsonl.gz`, after decompression | `30dd158bbd57761c79f72a38b4bf9a13aee545dac0a2ee1cce0c90f01e8e3531` |

The archives are compared **decompressed** on purpose: the gzip framing differs
between zlib and `java.util.zip`, and that difference must not read as a difference
in the rule. If either side's rule drifts, that side's own suite fails — which is the
whole point, since a drifted rule is how the two recaps silently fork.

The one line whose bytes need care is the escaped one. Build it literally — an escaped
quote, an escaped backslash, a newline written as the two characters `\` `n` (never a
raw 0x0A inside the JSON string), then `c`, then `鬱` as its own three UTF-8 bytes:

```
{"ts":1775044800000,"artist":"q\"b\\\nc鬱","album":"Canon","title":"escaped","sec":42,"dur":259853}
```

and in hex (200 characters, the whole line):

```
7b227473223a313737353034343830303030302c22617274697374223a22715c22625c5c5c6e63e9acb1222c22616c62756d223a2243616e6f6e222c227469746c65223a2265736361706564222c22736563223a34322c22647572223a3235393835337d
```

All eight input lines live in `tests/diary_smoke.py` section 11, and the desktop suite
asserts both the hex and the three digests; the app's `DiaryImportTest` asserts the
same three. A mismatch means one side's rule moved — or that someone retyped a fixture
by hand, which is what happened here on 2026-09-29 and cost a round trip.

## Devices

```bash
--device adb:SERIAL          # a phone over adb (fastest, needs USB debugging on)
--device adb                 # whichever single device is plugged in
--device dir:/mnt/sdcard     # any directory: an SD card, a NAS folder, a mounted MTP phone
--device http://host:port    # a phone that pulls from `serve`
```

`dir:` is a real backend, not a test stub — it's how you watch a sync happen
without a phone, and how every test runs.

## Tests

```bash
python3 tests/smoke.py        # the engine      — 78 checks, ~3s
python3 tests/serve_smoke.py  # the serve side  — 53 checks, ~4s
python3 tests/gui_smoke.py    # the window      — 43 checks, ~3s, writes a screenshot
python3 tests/diary_smoke.py  # the diary merge — 132 checks, ~2s
```

All four are hermetic: `tests/fixture.py` builds the library synthetically
(ffmpeg tone files, PIL covers, hand-written sidecars), and each suite points
`XDG_CONFIG_HOME` at its own scratch so it can't read a real config or touch a real
library. The fixture albums are deliberately awkward — decomposed (NFD) Japanese
filenames against an NFC sidecar, `[Disc N]` subfolders, spaces and brackets.
`diary_smoke.py` needs no fixture at all — its subject is one text file of JSON
lines — but it does check, at the end, that `~/.config/marimo/history.jsonl` only
ever grew by appends while it ran: the one file in this repo whose loss would
actually hurt.
