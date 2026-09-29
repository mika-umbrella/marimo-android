"""The server window — the desktop half of a wifi sync, and nothing else.

Open it and it does the whole job, in order: convert what's new from your originals,
make the waveform sidecars, start serving, and wait for the phone to pull. Nothing
here resolves a *device* — no adb, no MTP, no cable — so "is the phone plugged in?"
is not a question this window is capable of asking. The phone is a client that
reports in over the network, and the only thing it ever needs typed is the address.

What it reads, and where from:

    the library     its own server's index (/api/index.json), so one truth, not two
    the phone       the report the phone POSTs (/api/manifest), straight off disk
    progress        its own server's health (/api/health), which is what the phone
                    polls too -- so if this window says "converting X", so does the phone

The push-style console (device, plan table, sync/verify/doctor) still exists as
`marimo-sync-gui`, for when you deliberately want to put a cable in.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

from PyQt6.QtCore import QProcess, Qt, QTimer
from PyQt6.QtGui import QFont
from PyQt6.QtWidgets import (QApplication, QHBoxLayout, QLabel, QMainWindow, QMenu,
                             QMessageBox, QPlainTextEdit, QPushButton, QVBoxLayout,
                             QWidget)

from .config import Config
from .serve import inventory_path, lan_address

HERE = Path(__file__).resolve().parent
CLI = HERE.parent / "marimo-sync"
OLD_WINDOW = HERE.parent / "marimo-sync-gui"


def adb_path() -> Path:
    """Which adb to use. Overridable so a test can point it at a stub and stay hermetic."""
    env = os.environ.get("MARIMO_SYNC_ADB")
    return Path(env) if env else Path.home() / "android-sdk" / "platform-tools" / "adb"


def adb_serials(adb: Path | None = None) -> list[str]:
    """Serials of phones adb can see right now -- listing, not using.

    This window still resolves no transport of its own: serving, the library summary
    and the phone's report all work with nothing plugged in, and a test enforces it.
    All this decides is whether to *offer* the cable-only actions, which the CLI then
    performs in its own process.
    """
    try:
        r = subprocess.run([str(adb or adb_path()), "devices"],
                           capture_output=True, text=True, timeout=10)
    except Exception:
        return []
    serials = []
    for line in (r.stdout or "").splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials

def device_argv(window_args: list[str], args: list[str], device: str | None = None,
                json_out: bool = False) -> list[str]:
    """The CLI argv for a device run: globals -- and `--device` -- BEFORE the subcommand.

    argparse only takes global flags ahead of the subcommand; `status --device X` is
    "unrecognized arguments", which cost me a launch test earlier today. Keeping this
    in one place makes it checkable rather than remembered.
    """
    head = [*window_args, "--plain"]
    if json_out:
        head.append("--json")
    if device:
        head += ["--device", device]
    return [*head, *args]


MOSS = "#8ab48a"
DIM = "#8a8a8a"
CLAY = "#c08a6a"


def human(n: int) -> str:
    for unit, size in (("G", 1 << 30), ("M", 1 << 20), ("k", 1 << 10)):
        if n >= size:
            return f"{n / size:.1f} {unit}B"
    return f"{n} B"


class ServeWindow(QMainWindow):
    """Serve the library to the phone, and show what's happening while it does."""

    def __init__(self, argv: list[str]):
        super().__init__()
        self.argv = list(argv)
        self.cfg_path: Path | None = None
        self.cfg: Config | None = None
        self.proc: QProcess | None = None
        self.url: str | None = None
        self.token: str | None = None
        self._last_line = ""

        self.setWindowTitle("marimo sync")
        self.resize(880, 560)
        self._build()
        self._apply_style()

        self.timer = QTimer(self)
        self.timer.setInterval(1500)
        self.timer.timeout.connect(self.poll)
        self.timer.start()
        QTimer.singleShot(200, self.start)

    # ------------------------------------------------------------------ ui
    def _build(self) -> None:
        root = QWidget()
        self.setCentralWidget(root)
        outer = QVBoxLayout(root)
        outer.setContentsMargins(16, 14, 16, 12)
        outer.setSpacing(10)

        # -- the one thing you do: serve -- the phone gets the address, nothing else
        top = QHBoxLayout()
        top.setSpacing(10)
        self.btn_serve = QPushButton("Serve")
        self.btn_serve.setMinimumWidth(96)
        self.btn_serve.clicked.connect(self.toggle_serve)
        top.addWidget(self.btn_serve)

        self.lbl_addr = QLabel("starting…")
        self.lbl_addr.setFont(QFont("monospace", 12))
        self.lbl_addr.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        top.addWidget(self.lbl_addr, 1)

        self.btn_copy = QPushButton("Copy address")
        self.btn_copy.clicked.connect(self.copy_address)
        self.btn_copy.setToolTip("the only thing the phone needs typed into it")
        top.addWidget(self.btn_copy)
        outer.addLayout(top)

        # -- what there is, and what the phone has said for itself
        self.lbl_lib = QLabel("library    --")
        self.lbl_phone = QLabel("phone      nothing reported yet")
        self.lbl_stage = QLabel("")
        for w in (self.lbl_lib, self.lbl_phone, self.lbl_stage):
            w.setFont(QFont("monospace", 10))
            outer.addWidget(w)
        self.lbl_stage.setStyleSheet(f"color: {MOSS};")

        # -- the phone, but only when one is actually plugged in. This window is
        # wifi-first, so it advertises the cable only when the cable is there.
        self.device_row = QWidget()
        drow = QHBoxLayout(self.device_row)
        drow.setContentsMargins(0, 0, 0, 0)
        drow.setSpacing(10)
        self.lbl_dev = QLabel("")
        self.lbl_dev.setFont(QFont("monospace", 10))
        drow.addWidget(self.lbl_dev, 1)
        self.btn_clean = QPushButton("Clean up the phone…")
        self.btn_clean.setToolTip("delete what the phone holds that the library no "
                                  "longer has (marimo-sync sync --prune, over adb)")
        self.btn_clean.clicked.connect(self.clean_phone)
        drow.addWidget(self.btn_clean)
        outer.addWidget(self.device_row)
        self.device_row.setVisible(False)
        self.adb_serial: str | None = None
        self.device_files = self.device_orphans = 0
        self.busy: QProcess | None = None
        self.output = ""
        self.quiet = False
        self._ticks = 0

        # -- what happened
        self.log = QPlainTextEdit()
        self.log.setReadOnly(True)
        self.log.setFont(QFont("monospace", 9))
        self.log.setMaximumBlockCount(500)
        outer.addWidget(self.log, 1)

        # -- settings, and everything else out of sight
        bottom = QHBoxLayout()
        bottom.addStretch(1)
        self.btn_more = QPushButton("⋯")
        self.btn_more.setFixedWidth(40)
        self.btn_more.setToolTip("the rest, for when you want it")
        menu = QMenu(self)
        menu.addAction("Refresh", lambda: (self.refresh(), self.refresh_device()))
        menu.addAction("Open the library folder", self.open_library)
        menu.addAction("Copy the token", self.copy_token)
        menu.addSeparator()
        menu.addAction("Push console… (cable, adb)", self.open_console)
        self.btn_more.setMenu(menu)
        self.btn_settings = QPushButton("Settings…")
        self.btn_settings.clicked.connect(self.open_settings)
        for b in (self.btn_settings, self.btn_more):
            bottom.addWidget(b)
        outer.addLayout(bottom)

    def _apply_style(self) -> None:
        self.setStyleSheet("""
            QWidget { background: #1f1f1f; }
            QLabel { color: #d8d8d8; background: transparent; }
            QPlainTextEdit { background: #161616; color: #cfcfcf; border: 1px solid #333;
                             selection-background-color: #3a5a3a; }
            QPushButton { background: #2c2c2c; color: #d8d8d8; border: 1px solid #3c3c3c;
                          border-radius: 4px; padding: 6px 12px; }
            QPushButton:hover { background: #383838; }
            QPushButton::menu-indicator { image: none; }
            QMenu { background: #262626; color: #d8d8d8; border: 1px solid #3c3c3c; }
            QMenu::item:selected { background: #3a5a3a; }
        """)
        self.lbl_addr.setStyleSheet(f"color: {MOSS};")

    # -------------------------------------------------------------- saying
    def say(self, text: str, colour: str = "") -> None:
        self.log.appendPlainText(text)
        if colour:
            self.lbl_stage.setText(text)
            self.lbl_stage.setStyleSheet(f"color: {colour};")

    # -------------------------------------------------------------- doing
    def start(self) -> None:
        self.cfg_path = _cfg_path(self.argv)
        self.cfg = Config.load(path=self.cfg_path)
        settings = self.cfg.serve or {}
        port = int(settings.get("port") or 8422)
        token = settings.get("token")
        if not token:
            import hashlib
            token = hashlib.sha256(os.urandom(32)).hexdigest()[:12]
            self.cfg.save({"serve": {"token": token}})
            self.say(f"made a token for the phone and saved it in {self.cfg_path}")
        self.token = token
        self.url = f"http://{lan_address()}:{port}"

        if not settings.get("auto", True):
            self.say("serve.auto is off in your config — press Serve when you want it")
            self._show_off()
            return

        self.proc = QProcess(self)
        self.proc.setProcessChannelMode(QProcess.ProcessChannelMode.SeparateChannels)
        self.proc.readyReadStandardOutput.connect(self._on_out)
        self.proc.readyReadStandardError.connect(self._on_err)
        self.proc.finished.connect(self._on_done)
        # --prepare-now: the server gets the library ready (convert, waveforms, art)
        # *before* it serves a byte, and it is the only thing that writes -- so the
        # two of us can never be converting the same album at once.
        self.proc.start(sys.executable, [str(CLI), *self.argv, "--plain", "serve",
                                         "--quiet", "--prepare-now",
                                         "--port", str(port), "--token", token])
        self.btn_serve.setText("Stop")
        self.lbl_addr.setText(f"{self.url}   ·   token {token}")
        self.say(f"serving at {self.url} — type that into marimo on the phone")
        self.log.appendPlainText("")

    def toggle_serve(self) -> None:
        if self.proc is None:
            self.start()
            return
        self.say("stopping the server")
        self.proc.terminate()
        self.proc.waitForFinished(3000)
        self.proc = None
        self._show_off()

    def _show_off(self) -> None:
        self.btn_serve.setText("Serve")
        self.lbl_addr.setText("not serving")

    def _on_out(self) -> None:
        if self.proc is None:
            return
        text = bytes(self.proc.readAllStandardOutput()).decode(errors="replace")
        for line in text.splitlines():
            if line.strip():
                self.say(line.strip("\n"))

    def _on_err(self) -> None:
        if self.proc is None:
            return
        text = bytes(self.proc.readAllStandardError()).decode(errors="replace")
        for line in text.splitlines():
            if line.strip() and not line.startswith("token "):
                self.say(line.strip("\n"), CLAY)

    def _on_done(self, _code: int, _status) -> None:
        self.proc = None
        self._show_off()

    # ------------------------------------------------------------- looking
    def _get(self, path: str):
        req = urllib.request.Request(self.url + path)
        if self.token:
            req.add_header("X-Marimo-Token", self.token)
        with urllib.request.urlopen(req, timeout=3) as r:
            return json.loads(r.read().decode("utf-8", "replace"))

    def poll(self) -> None:
        """Refresh from the server and the phone's own report. No device involved."""
        self.refresh()
        self._ticks += 1
        if self._ticks % 4 == 0:           # every ~6s: is a phone on the cable?
            self.check_for_phone()

    def refresh(self) -> None:
        if self.proc is not None and self.url:
            try:
                health = self._get("/api/health")
                prep = health.get("preparing") or {}
                state = prep.get("state", "idle")
                album = prep.get("album") or ""
                total = prep.get("total") or 0
                if state in ("queued", "converting", "waveforms", "covers"):
                    line, colour = (f"{state}  {album}"
                                    + (f"   ({total} to do)" if total else "")), MOSS
                elif state == "ready":
                    n = len(prep.get("albums") or [])
                    line, colour = f"ready  {n} album(s) prepared for the phone", MOSS
                elif state == "failed":
                    line, colour = f"prepare failed  {prep.get('error', '')}", CLAY
                else:
                    line, colour = "", DIM
                if line != self._last_line:        # a poll every 1.5s must not chant
                    self._last_line = line
                    if line:
                        self.say(line, colour)
                    else:
                        self.lbl_stage.setText("")
                        self.lbl_stage.setStyleSheet(f"color: {DIM};")
            except Exception as e:
                self.lbl_stage.setText(f"server not answering yet ({e})")
                self.lbl_stage.setStyleSheet(f"color: {CLAY};")

            try:
                index = self._get("/api/index.json")
                lib = index.get("library") or {}
                self.lbl_lib.setText(
                    f"library    {lib.get('albums', 0)} albums · {lib.get('files', 0)} files"
                    f" · {human(lib.get('bytes', 0))}")
            except Exception:
                pass

        self._read_report()

    def _read_report(self) -> None:
        """What the phone last said it has — read off disk, so no phone is needed."""
        if self.cfg is None:
            return
        p = inventory_path(self.cfg)
        if not p.is_file():
            self.lbl_phone.setText("phone      nothing reported yet"
                                   " — open marimo on it and press Sync")
            return
        try:
            data = json.loads(p.read_text())
        except Exception:
            self.lbl_phone.setText("phone      report unreadable")
            return
        albums = data.get("albums") or {}
        files = sum(len(a.get("files", {}) or {}) for a in albums.values())
        when = (data.get("reported") or "")[11:19]
        self.lbl_phone.setText(f"phone      {len(albums)} albums · {files} files"
                              + (f" · reported {when}" if when else ""))

    # ------------------------------------------------------- a phone on the cable
    def check_for_phone(self) -> None:
        """Look for a phone on adb, and offer the cable-only actions if there is one."""
        serials = adb_serials()
        found = serials[0] if serials else None
        if found == self.adb_serial:
            return
        self.adb_serial = found
        if found is None:
            self.device_row.setVisible(False)
            self.lbl_dev.setText("")
            return
        self.device_row.setVisible(True)
        self.lbl_dev.setText(f"phone      {found} · reading what's on it…")
        self.say(f"phone on adb: {found} (the wifi side needs nothing plugged in)")
        self.refresh_device()

    def refresh_device(self) -> None:
        """Ask the CLI what the phone holds, and what it holds that we don't."""
        if not self.adb_serial:
            return
        self.spawn_cli(["status"], device=f"adb:{self.adb_serial}", json_out=True,
                       quiet=True, on_done=self._apply_device)

    def _apply_device(self, _out: str) -> None:
        try:
            plan = json.loads(self.output)
        except Exception:
            self.lbl_dev.setText("phone      (couldn't read the phone's state)")
            return
        dev = plan.get("device") or {}
        self.device_files = dev.get("files", 0)
        self.device_orphans = plan.get("orphan_files", 0)
        label = dev.get("label") or self.adb_serial or "?"
        self.lbl_dev.setText(
            f"phone      {label} · {self.device_files} files · "
            f"{human(dev.get('free_bytes') or 0)} free · "
            f"{self.device_orphans} not in the library")

    def clean_phone(self) -> None:
        """Delete what the phone holds that the library doesn't -- over adb."""
        if not self.adb_serial:
            return
        if not self.device_orphans:
            self.say("nothing to clean: the phone has no files the library lacks")
            return
        # A mis-rooted device makes *everything* look orphaned, and this button deletes.
        # Refuse rather than trust the count when the count is the whole phone.
        if self.device_files and self.device_orphans >= self.device_files:
            self.say(
                f"refusing to clean: all {self.device_files} file(s) on the phone look "
                f"like orphans, which usually means the device root is wrong — check "
                f"that 'root' in settings points at the folder marimo reads on the "
                f"phone (it should be /sdcard/Music/Chunes, not /sdcard/Music)", CLAY)
            return
        answer = QMessageBox.question(
            self, "Clean up the phone",
            f"Delete {self.device_orphans} file(s) from the phone that the library no "
            f"longer has?\n\n"
            "Everything on the phone that the library doesn't know about goes, "
            "including music you put there yourself. marimo's own scan won't notice "
            "until you rescan on the phone.",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.Cancel)
        if answer != QMessageBox.StandardButton.Yes:
            self.say("cancelled")
            return
        self.spawn_cli(["sync", "--prune"], device=f"adb:{self.adb_serial}",
                       on_done=lambda _o: self.refresh_device())

    # ------------------------------------------------------------ running the cli
    def spawn_cli(self, args: list[str], device: str | None = None,
                  json_out: bool = False, quiet: bool = False, on_done=None) -> None:
        """Run one CLI command in the background, streaming it into the log.

        The window resolves no transport itself -- the CLI does, in its own process,
        which is the same rule that keeps serving device-free.
        """
        if self.busy is not None:
            self.say("still busy with the last command", CLAY)
            return
        argv = device_argv(self.argv, args, device=device, json_out=json_out)
        self.say("$ marimo-sync " + " ".join(args) + (f"   ({device})" if device else ""))
        self.output = ""
        self.quiet = quiet
        self.busy = QProcess(self)
        self.busy.setProcessChannelMode(QProcess.ProcessChannelMode.MergedChannels)
        self.busy.readyReadStandardOutput.connect(self._on_busy_out)
        self.busy.finished.connect(lambda *_: self._on_busy_done(on_done))
        self.busy.start(sys.executable, [str(CLI), *argv])

    def _on_busy_out(self) -> None:
        if self.busy is None:
            return
        chunk = bytes(self.busy.readAllStandardOutput()).decode(errors="replace")
        self.output += chunk
        if not self.quiet:
            for line in chunk.splitlines():
                if line.strip():
                    self.say(line.rstrip())

    def _on_busy_done(self, on_done) -> None:
        self.busy = None
        if on_done:
            on_done(self.output)

    # ------------------------------------------------------------- actions
    def copy_address(self) -> None:
        if not self.url:
            return
        QApplication.clipboard().setText(self.url)
        self.say(f"copied {self.url}")

    def copy_token(self) -> None:
        if self.token:
            QApplication.clipboard().setText(self.token)
            self.say("copied the token")

    def open_library(self) -> None:
        if self.cfg is not None:
            QDesktopServices_open(self.cfg.source)

    def open_settings(self) -> None:
        from .gui import SettingsDialog            # the editor, already written
        if self.cfg_path is None:
            return
        dlg = SettingsDialog(self, self.cfg_path, self.argv)
        if dlg.exec():
            self.cfg = Config.load(path=self.cfg_path)
            self.say("settings saved — restarting the server to pick them up")
            if self.proc is not None:
                self.proc.terminate()
                self.proc.waitForFinished(3000)
                self.proc = None
            self.start()
            self.refresh()

    def open_console(self) -> None:
        """The old device-based window, deliberately, on purpose."""
        if not OLD_WINDOW.is_file():
            self.say(f"no push console at {OLD_WINDOW}", CLAY)
            return
        QProcess.startDetached(sys.executable, [str(OLD_WINDOW), *self.argv])
        self.say("started the push console (it has its own window)")

    def closeEvent(self, event) -> None:
        if self.busy is not None:
            self.busy.terminate()
            self.busy.waitForFinished(3000)
        if self.proc is not None:
            self.proc.terminate()
            self.proc.waitForFinished(3000)
        self.timer.stop()
        super().closeEvent(event)


def QDesktopServices_open(path: Path) -> None:
    """Open a folder in the desktop's file manager, without importing a whole module."""
    from PyQt6.QtCore import QUrl
    from PyQt6.QtGui import QDesktopServices
    QDesktopServices.openUrl(QUrl.fromLocalFile(str(path)))


def _cfg_path(argv: list[str]) -> Path | None:
    for i, a in enumerate(argv):
        if a == "--config" and i + 1 < len(argv):
            return Path(argv[i + 1])
        if a.startswith("--config="):
            return Path(a.split("=", 1)[1])
    from .config import config_path
    return config_path()


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    app = QApplication([sys.argv[0], *argv])
    win = ServeWindow(argv)
    win.show()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
