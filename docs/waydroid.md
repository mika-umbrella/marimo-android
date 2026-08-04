# waydroid notes (2026-08-04)

## install (fedora)

- `sudo dnf install -y waydroid waydroid-selinux java-21-openjdk-devel`
- `waydroid-extra` is NOT in fedora repos — it provides `/usr/share/waydroid-extra/channels.cfg`
  (the OTA channel URLs). without it `waydroid init` fails with
  "You must provide 'System OTA' and 'Vendor OTA' URLs."
  fix (channels.cfg content):
  ```
  [channels]
  system_channel = https://ota.waydro.id/system
  vendor_channel = https://ota.waydro.id/vendor
  rom_type = lineage
  system_type = VANILLA
  ```
  install with `sudo mkdir -p /usr/share/waydroid-extra && sudo cp <file> /usr/share/waydroid-extra/channels.cfg`
- `sudo waydroid init` — downloads system.img (1.7G) + vendor.img (536M) to /var/lib/waydroid/images/
- binder is built into the fedora kernel (CONFIG_ANDROID_BINDER_IPC=y) — no modules needed

## run

- `waydroid session start` (user) then `sudo waydroid container start`
- check: `waydroid status` → Session RUNNING / Container RUNNING, IP 192.168.240.112
- UI: `waydroid show-full-ui` (may be a black screen while android settles; first boot is slow)

## adb

- `adb connect 192.168.240.112:5555` — will be UNAUTHORIZED
- the on-screen "Allow USB debugging?" dialog may never appear (black UI) and
  synthetic X events don't reach the container
- **working auth method:** `sudo waydroid shell -u 0 -g 0 < /tmp/script.sh` —
  waydroid shell with NO command args attaches `/system/bin/sh` reading STDIN
  (stdin is inherited through subprocess.run). script content:
  ```
  mkdir -p /data/misc/adb
  echo "<adbkey.pub content>" >> /data/misc/adb/adb_keys
  chmod 644 /data/misc/adb/adb_keys
  setprop ctl.restart adbd
  ```
  then reconnect adb. **gotchas:** waydroid shell EXECS argv directly (no shell
  wrapping) — `sh -c '...'` payloads need quoting that breaks in copy/paste
  (smart quotes); host-side /var/lib/waydroid/rootfs/data is NOT the container's
  /data (mounted inside the container's namespace).
- push/run: `adb push bin /data/local/tmp/ && adb shell "cd /data/local/tmp && HOME=/data/local/tmp ./bin <musicdir>"`
  (container $HOME isn't writable — set HOME to /data/local/tmp for tests that write)

## on-device test

- NDK x86_64 core_selftest: **PASSED** (md5/queue/fs/tags, japanese flac via
  tag_read_meta_fd on bionic). binary still at /data/local/tmp/core_selftest_android.
