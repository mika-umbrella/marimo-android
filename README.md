# marimo-android

the android port of [marimo](https://github.com/mika-umbrella/marimo) — a
tiny retro pixel music player (C11, SDL2, libmpv). **local repo, not
published.** the port strategy: keep the C core, rebuild the UI layer.

## status

- [x] **M0 — portable core extracted + proven** (`core/`): tags.c (FLAC/MP3
      ID3v2, now with fd variants for SAF), queue.c, md5.c, cJSON.c, fs.c.
      Zero SDL/mpv/UI deps; builds with `gcc -std=c11 -Wall -Wextra -pedantic`.
      `core/core_selftest.c` passes on the real library (md5 vectors, queue
      logic, japanese-path fs, tags via path AND fd agreeing).
- [x] **JNI contract sketched** (`android/core_api.h`): flat buffers + opaque
      handles only — no structs across the boundary.
- [x] **App skeleton sketched** (`android/MainActivity.kt`): SAF tree picker,
      fd-based tag indexing, MediaSession callback, foreground service.
- [x] **M1 — toolchain** — SDK + NDK r29 installed (~/android-sdk), licenses accepted
- [x] **M2 — on-device core selftest** — NDK-built x86_64 binary ran on waydroid: md5/queue/fs/tags all green, japanese-named flac parsed via bionic (fd + path variants)
- [x] **M3 — app with JNI core** — gradle app (AGP 8.7.3, gradle 8.14.3, JDK 21
      via temurin tarball): SAF tree walk → fd tags, app-private path tags
      (tagReadPath), C queue exposed, on-screen JNI selftest
- [x] **M4 — playback** — MediaPlayer service + MediaSession (lockscreen/BT
      keys) + foreground notification. mpv-android stays the future upgrade
      for true gapless
- [x] **M5 — touch UI** — bottom tabs (player/library), player screen with
      big art + title/artist/album + 64dp transport, 64dp track rows with
      cover thumbs. embedded art extraction is now a CORE feature (FLAC
      picture blocks + ID3 APIC) since MediaPlayer doesn't hand over art.
      the pixel marimo face (unifont UI) is the remaining dream
- [ ] M6 — scrobbling: core/scrobble.c + libcurl for android, or the app does
      HTTP in kotlin

## what survives, what doesn't

survives as-is: tags, queue, md5, cJSON, fs, config format (rewritten to
SharedPreferences), scrobble payload logic, the winamp soul.

dies or gets rebuilt: the pixel UI (touch targets), path-based browsing
(SAF/MediaStore instead), dirent shim (obsolete — no paths at all),
MPRIS/global hotkeys (→ MediaSession + bluetooth keys, strictly better),
fixed 480x640 landscape layout (phones want portrait).

## building

core (works here):

```sh
cd core
gcc -std=c11 -Wall -Wextra -pedantic -O2 -o core_selftest *.c
./core_selftest            # or: ./core_selftest /path/to/music
ar rcs libmarimo_core.a *.o   # static lib for the JNI shim
```

android (needs the SDK/NDK — big download, nova's call when she wants M1):

- Android Studio (SDK + NDK + gradle + kotlin)
- mpv for android: build audio-only libmpv via the
  [mpv-android](https://github.com/mpv-android/mpv-android) build tree
  (FFmpeg + opensl/aaudio out; marimo already runs mpv with `vo=null`)
- curl for android (scrobbling), or plain kotlin HTTP

## license

GPL-3.0, same as marimo (this is derived code; see LICENSE).
