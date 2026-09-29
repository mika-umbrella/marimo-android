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

The app side lives in `marimo-android`: Settings → **sync from desktop**, where you
put that address and token, then *check what's missing* and *fetch*. It fetches only
what differs, file by file, resuming if the wifi drops, and writes into the same music
folder the player already scans — no cable, no USB debugging, no new permissions. It
tells the desktop what it ended up holding, so `status --device http://host:port` keeps
working without the phone attached.

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
python3 tests/smoke.py        # the engine      — 42 checks, ~2s
python3 tests/serve_smoke.py  # the serve side  — 35 checks, ~2s
python3 tests/gui_smoke.py    # the window      — 32 checks, ~8s, writes a screenshot
```

All three are hermetic: `tests/fixture.py` builds the library synthetically
(ffmpeg tone files, PIL covers, hand-written sidecars), and each suite points
`XDG_CONFIG_HOME` at its own scratch so it can't read a real config or touch a real
library. The fixture albums are deliberately awkward — decomposed (NFD) Japanese
filenames against an NFC sidecar, `[Disc N]` subfolders, spaces and brackets.
