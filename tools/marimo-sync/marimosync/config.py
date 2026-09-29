"""Configuration: where the library is, where the device is, which helpers exist.

Precedence, lowest to highest:

    built-in defaults  →  the config file  →  environment  →  command-line flags

`marimo-sync config` prints the winner for every key *and where it came from*,
because "which file is it actually reading" is the question that costs an hour
when the answer is wrong.

**Nothing in this package carries anyone's paths.** The defaults are ordinary
places and the notes are about marimo's conventions, but your own library, your
own phone and your own helper scripts live in the config file:

    $XDG_CONFIG_HOME/marimo-sync/config.json      (~/.config/marimo-sync/config.json)

`marimo-sync config --init` writes a starter file to edit, and the window has a
Settings panel that does the same thing with file pickers.
"""

from __future__ import annotations

import copy
import json
import os
from pathlib import Path
from typing import Any

ENV_PREFIX = "MARIMO_SYNC_"

# Ordinary defaults, so the tool runs for anyone without being told anything.
def config_path() -> Path:
    base = os.environ.get("XDG_CONFIG_HOME")
    root = Path(base) if base else Path.home() / ".config"
    return root / "marimo-sync" / "config.json"


def cache_dir() -> Path:
    base = os.environ.get("XDG_CACHE_HOME")
    root = Path(base) if base else Path.home() / ".cache"
    return root / "marimo-sync"


def data_dir() -> Path:
    """Where marimo-sync keeps the things it owns — the compressed library above all."""
    base = os.environ.get("XDG_DATA_HOME")
    root = Path(base) if base else Path.home() / ".local" / "share"
    return root / "marimo-sync"


def default_library() -> Path:
    return data_dir() / "library"


def bundled_script(name: str) -> Path:
    """A script that ships with marimo-sync, next to this package."""
    return Path(__file__).resolve().parent.parent / "scripts" / name


def _disabled(value: Any) -> bool:
    """`none`/`off` is how you say "don't run this stage", as against unset."""
    return isinstance(value, str) and value.strip().lower() in ("none", "off", "no", "false")


def _expand(value: Any) -> Any:
    if isinstance(value, str) and value.startswith("~"):
        return str(Path(value).expanduser())
    return value


DEFAULTS: dict[str, Any] = {
    # the library to sync from — a directory of album folders. Defaults to the
    # conventional place under the data dir, so a new install needs no config at
    # all: `marimo-sync convert` builds it, `sync` sends it, and that's the lot.
    "source": str(default_library()),
    # how to reach the device. "auto" finds it: a single adb device, or a single
    # phone the OS has mounted (gvfs MTP), or a card — so nothing needs configuring.
    # Or say it outright: "dir:/path", "adb:SERIAL", "http://host:port".
    "device": "auto",
    # the music directory on the device (marimo-android's tree is "<root>/Chunes",
    # so set that if that's your player)
    "root": "/sdcard/Music",
    # where the uncompressed originals live, for `sync --convert`.
    "flac_source": "~/Music",
    # the two helper stages ship with the tool; set either to "none" to turn it off
    "wave_script": None,      # null = the bundled make-waveforms.py
    "convert_script": None,   # null = the bundled convert_music.py
    # adb binary; null = "adb" from PATH
    "adb": None,
    # where the device-side manifest lives, relative to the device root. The
    # default keeps it just outside the music tree so a player's scan never sees it
    "manifest": "../.marimo-sync/manifest.json",
    # null = $XDG_CACHE_HOME/marimo-sync/hashcache.json
    "hashcache": None,
    # where push tars are built; null = the system temp dir
    "scratch": None,
    # the desktop player's diary folder: history.jsonl and its gzipped years. Fixed
    # at the player's own place -- marimo-desktop ignores XDG_CONFIG_HOME and reads
    # ~/.config/marimo, so this default names that directory outright.
    "diary": str(Path.home() / ".config" / "marimo"),
    # where the merged diary is staged so the phone can import it. The phone cannot
    # be written into (private filesDir, release build), so the exchange is a folder
    # the desktop writes and the phone reads -- under our own data dir, because this
    # one really is ours (unlike `diary`, it follows XDG_DATA_HOME).
    "diary_out": str(data_dir() / "diary-out"),
    # where `diary --pull` fetches the phone's export TO. Deliberately not the same
    # folder as diary_out: the outbox is what gets published and pushed, so a fetched
    # copy landing there would be overwritten by --publish (or pushed straight back).
    "diary_in": str(data_dir() / "diary-in"),
    # where on the phone that folder lands: the app imports the diary from its
    # Downloads. Set this to where your app looks if it isn't that.
    "diary_phone": "/sdcard/Download/marimo",
    # `serve` settings for the phone-pulls-over-the-LAN mode.
    #   auto  - the desktop app serves for as long as its window is open
    #   token - the shared secret the phone sends; generated and saved on first
    #           auto-serve, because a fresh token per launch would break the
    #           phone's saved one every time
    #   diary - whether the phone may hand its listening diary over this link. On by
    #           default, because that is the point of the feature -- but it is the one
    #           endpoint that writes to the *player's* live diary, so it can be turned
    #           off without turning the music off.
    "serve": {"port": 8422, "bind": None, "token": None, "auto": True, "diary": True},
}

# keys whose value is a path and should have ~ expanded
PATH_KEYS = {"source", "flac_source", "wave_script", "convert_script", "adb",
             "hashcache", "scratch", "diary", "diary_out", "diary_in"}

# one line per key: shown by `marimo-sync config` and used as tooltips in the window
HELP: dict[str, str] = {
    "source": "the library to sync from — normally <data>/marimo-sync/library",
    "device": "auto (finds a plugged-in phone), dir:/path, adb[:SERIAL], or http://host:port",
    "root": "the music directory on the device (marimo-android's tree is <root>/Chunes)",
    "flac_source": "where the uncompressed originals live, for `sync --convert`",
    "wave_script": "the waveform generator — bundled; `none` turns the stage off",
    "convert_script": "the converter — bundled; `none` turns the stage off",
    "adb": "the adb binary, if it isn't on PATH",
    "manifest": "where the device-side manifest lives, relative to the device root",
    "hashcache": "where content hashes are cached locally",
    "scratch": "where push tars are built (defaults to the system temp dir)",
    "diary": "the player's diary folder (history.jsonl + its gzipped years), for `diary`",
    "diary_out": "where `diary --publish` stages the merged diary for the phone",
    "diary_in": "where `diary --pull` fetches the phone's export to",
    "diary_phone": "where the phone's app imports the diary from (its Downloads folder)",
    "serve": "settings for `serve`: {port, bind, token, auto, diary}",
}


class ConfigError(RuntimeError):
    pass


class Config:
    """Resolved settings, with each value's origin remembered."""

    def __init__(self, values: dict[str, Any], origins: dict[str, str], path: Path):
        self.values = values
        self.origins = origins
        self.path = path

    # ------------------------------------------------------------------ load
    @classmethod
    def load(cls, path: Path | None = None, flags: dict[str, Any] | None = None,
             env: dict[str, str] | None = None) -> "Config":
        path = Path(path).expanduser() if path else config_path()
        env = dict(os.environ if env is None else env)
        values = copy.deepcopy(DEFAULTS)
        origins = {k: "default" for k in DEFAULTS}

        # 1. the file
        raw: dict[str, Any] = {}
        if path.is_file():
            try:
                raw = json.loads(path.read_text()) or {}
            except ValueError as e:
                raise ConfigError(f"{path} is not valid JSON: {e}")
            if not isinstance(raw, dict):
                raise ConfigError(f"{path} should contain a JSON object")
            for key, value in raw.items():
                if key not in DEFAULTS:
                    continue                      # tolerate unknown keys, ignore them
                if key == "serve" and isinstance(value, dict):
                    values["serve"].update(value)
                else:
                    values[key] = value
                origins[key] = str(path)

        # 2. environment
        for key in DEFAULTS:
            name = ENV_PREFIX + key.upper()
            if name in env and env[name] != "":
                values[key] = _expand(env[name])
                origins[key] = f"env {name}"

        # 3. flags -- only the ones actually given
        for key, value in (flags or {}).items():
            if value is None or key not in DEFAULTS:
                continue
            if key == "serve" and isinstance(value, dict):
                values["serve"].update({k: v for k, v in value.items() if v is not None})
            else:
                values[key] = _expand(value)
            origins[key] = "--flag"

        return cls(values, origins, path)

    # --------------------------------------------------------------- access
    def __getitem__(self, key: str) -> Any:
        return self.values[key]

    def get(self, key: str, default: Any = None) -> Any:
        return self.values.get(key, default)

    def origin(self, key: str) -> str:
        return self.origins.get(key, "default")

    def path_value(self, key: str) -> Path | None:
        v = self.values.get(key)
        if v in (None, ""):
            return None
        return Path(_expand(v))

    @property
    def source(self) -> Path:
        return self.path_value("source") or Path.home() / "Music"

    @property
    def flac_source(self) -> Path | None:
        return self.path_value("flac_source")

    @property
    def diary(self) -> Path:
        """The player's diary folder. Its default is spelled absolutely, so this is
        the one place XDG does *not* get a say (the player ignores it)."""
        return self.path_value("diary") or Path.home() / ".config" / "marimo"

    @property
    def diary_out(self) -> Path:
        """Where the merged diary is staged for the phone to import."""
        return self.path_value("diary_out") or data_dir() / "diary-out"

    @property
    def diary_in(self) -> Path:
        """Where `diary --pull` fetches the phone's export to."""
        return self.path_value("diary_in") or data_dir() / "diary-in"

    @property
    def diary_phone(self) -> str:
        """Where on the device those staged files belong. A device path, not a local
        one -- so no `~` expansion, and it is passed to the transport as the root."""
        return self.values.get("diary_phone") or "/sdcard/Download/marimo"

    @property
    def diary_record(self) -> Path:
        """Where the server notes what the last diary merge did, so the window can show
        it without asking the server anything (it sits beside the phone's inventory)."""
        return self.inventory.parent / "diary-last-merge.json"

    @property
    def wave_script(self) -> Path | None:
        return self._script("wave_script", "make-waveforms.py")

    @property
    def convert_script(self) -> Path | None:
        return self._script("convert_script", "convert_music.py")

    def _script(self, key: str, bundled: str) -> Path | None:
        """The script for a stage: whatever is configured, else the bundled one.

        The stages ship with the tool, so this is normally the bundled script and
        there's nothing to set up. Setting the key to `none` turns a stage off.
        """
        raw = self.values.get(key)
        if _disabled(raw):
            return None
        configured = self.path_value(key)
        if configured is not None:
            return configured if configured.is_file() else None
        shipped = bundled_script(bundled)
        return shipped if shipped.is_file() else None

    def script_problem(self, key: str) -> str | None:
        """Why a stage can't run, for a clear message."""
        raw = self.values.get(key)
        if raw:
            p = Path(_expand(raw)) if not _disabled(raw) else None
            if p is not None and not p.is_file():
                return f"{p} does not exist"
        return None if self._script(key, "make-waveforms.py" if "wave" in key else "convert_music.py") \
            else f"no {key.replace('_', ' ')} configured"

    @property
    def manifest_rel(self) -> str:
        return self.values.get("manifest") or DEFAULTS["manifest"]

    @property
    def hashcache(self) -> Path:
        return self.path_value("hashcache") or cache_dir() / "hashcache.json"

    @property
    def scratch(self) -> Path | None:
        return self.path_value("scratch")

    @property
    def adb(self) -> str:
        return str(self.path_value("adb") or "adb")

    @property
    def serve(self) -> dict:
        return self.values.get("serve") or DEFAULTS["serve"]

    @property
    def inventory(self) -> Path:
        """Where a phone that pulls reports what it holds."""
        configured = (self.values.get("serve") or {}).get("state")
        if configured:
            return Path(os.path.expanduser(configured))
        return cache_dir() / "phone-inventory.json"

    # ---------------------------------------------------------------- write
    def overlay(self, changes: dict[str, Any]) -> dict[str, Any]:
        """The config file as it should be, with `changes` applied.

        Existing keys are preserved so the file stays the user's, not ours.
        """
        current: dict[str, Any] = {}
        if self.path.is_file():
            try:
                current = json.loads(self.path.read_text()) or {}
            except ValueError:
                current = {}
        for key, value in changes.items():
            if key not in DEFAULTS:
                continue
            if key == "serve" and isinstance(value, dict):
                current.setdefault("serve", {}).update(value)
            elif value is None:
                current.pop(key, None)
            else:
                current[key] = value
        return current

    def save(self, changes: dict[str, Any]) -> Path:
        merged = self.overlay(changes)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(merged, indent=2, sort_keys=True) + "\n")
        os.replace(tmp, self.path)
        return self.path

    # --------------------------------------------------------------- report
    def describe(self) -> list[tuple[str, str, str]]:
        """(key, value, where it came from) for every key, for `config` output."""
        rows = []
        for key in DEFAULTS:
            value = self.values[key]
            if key == "serve":
                value = json.dumps(value)
            elif value is None:
                value = "(unset)"
            rows.append((key, str(value), self.origin(key)))
        return rows
