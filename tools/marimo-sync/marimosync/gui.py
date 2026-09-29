"""The window.

Deliberately thin: every button runs the CLI in `cli.py` as a subprocess and shows
what it said. There is one implementation of the sync logic and it is not here, so
the window cannot drift out of step with the tool.

No QThread anywhere -- QProcess runs subprocesses on the event loop, which sidesteps
the whole class of Qt abort-on-teardown bugs that comes from destroying a worker
thread mid-syscall (see the qt-qthread-network-sigabrt skill).
"""

from __future__ import annotations

import hashlib
import json
import os
import sys
import urllib.request
from pathlib import Path

from PyQt6.QtCore import QProcess, Qt, QTimer
from PyQt6.QtGui import QColor, QFont
from PyQt6.QtWidgets import (QAbstractItemView, QApplication, QCheckBox, QDialog,
                             QFileDialog, QFrame, QHBoxLayout, QHeaderView, QLabel,
                             QLineEdit, QMainWindow, QMessageBox, QPlainTextEdit,
                             QProgressBar, QPushButton, QTableWidget, QTableWidgetItem,
                             QVBoxLayout, QWidget)

from .diary_panel import DiaryDialog
from .serve import lan_address
from .transports import gvfs_mtp_mounts, mount_mtp, mtp_volumes


def device_start_dir(current: str) -> str:
    """Where the device picker should open: the phone, if we can get hold of it.

    An MTP phone has no path until something mounts it, and Qt's file dialog only
    browses real paths — so "plug it in and pick it" needs us to ask gvfs for the
    mount first, and to fall back somewhere harmless when the phone is busy.
    """
    if current and Path(current).is_dir():
        return current
    mounts = gvfs_mtp_mounts()
    if len(mounts) == 1:
        return str(mounts[0])
    mountable = [(n, r) for n, r in mtp_volumes() if r]
    if len(mountable) == 1:
        path, _why = mount_mtp(mountable[0][1])
        if path is not None:
            return str(path)
    gvfs = Path(f"/run/user/{os.getuid()}/gvfs")
    return str(gvfs) if gvfs.is_dir() else str(Path.home())

# Above this, a sync asks first. Below it, the normal handful-of-files case, it doesn't.
CONFIRM_FILES = 100
CONFIRM_BYTES = 1 << 30

PROJECT = Path(__file__).resolve().parent.parent
CLI = PROJECT / "marimo-sync"

# flags that name a location — these override the config file, so the Settings
# panel has to be honest about them and clear them once it saves
PATH_FLAGS = ("--source", "--device", "--root", "--flac-source", "--waves-script",
              "--convert-script", "--adb")


def config_path_from(argv: list[str]) -> Path:
    from .config import config_path
    for i, arg in enumerate(argv):
        if arg == "--config" and i + 1 < len(argv):
            return Path(argv[i + 1]).expanduser()
        if arg.startswith("--config="):
            return Path(arg.split("=", 1)[1]).expanduser()
    return config_path()


def overrides_in(argv: list[str]) -> list[str]:
    """The path flags in effect, as they'd be printed."""
    out = []
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg in PATH_FLAGS:
            out.append(f"{arg} {argv[i + 1] if i + 1 < len(argv) else ''}".strip())
            i += 2
            continue
        if any(arg.startswith(f + "=") for f in PATH_FLAGS):
            out.append(arg)
        i += 1
    return out


def strip_overrides(argv: list[str]) -> list[str]:
    """Drop the path flags and their values, keeping everything else."""
    kept: list[str] = []
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg in PATH_FLAGS:
            i += 2
            continue
        if any(arg.startswith(f + "=") for f in PATH_FLAGS):
            i += 1
            continue
        kept.append(arg)
        i += 1
    return kept

BG = "#14171a"
PANEL = "#1b1f23"
PANEL2 = "#20262b"
LINE = "#2d353b"
TEXT = "#d6d9d6"
DIM = "#8b9490"
MOSS = "#8fbf6a"
AMBER = "#d9a441"
CLAY = "#c9635a"
BLUE = "#7fa8c9"

STATUS_COLOUR = {"new": MOSS, "update": AMBER, "orphan": CLAY, "ok": DIM, "rename": BLUE}
STATUS_LABEL = {"new": "new", "update": "out of step", "orphan": "device only",
                "ok": "in sync", "rename": "same album, old name"}
STATUS_GLYPH = {"new": "+", "update": "~", "orphan": "?", "ok": "·", "rename": "→"}


def human(n: int | float | None) -> str:
    if not n:
        return "-"
    n = float(n)
    for unit, div in (("GB", 1 << 30), ("MB", 1 << 20), ("kB", 1 << 10)):
        if n >= div:
            return f"{n / div:.1f} {unit}"
    return f"{int(n)} B"


class SettingsDialog(QDialog):
    """Edit the config file. The window's whole notion of "where things are"."""

    FIELDS = [
        ("source", "Library", "dir"),
        ("device", "Device — a plugged-in phone, or pick its folder", "dir"),
        ("root", "Music folder on the device", "text"),
        ("flac_source", "Uncompressed originals (for convert)", "dir"),
        ("wave_script", "Waveform script (optional override)", "file"),
        ("convert_script", "Convert script (optional override)", "file"),
        ("adb", "adb binary, if not on PATH", "file"),
    ]

    def __init__(self, parent, cfg_path: Path, overrides: list[str]):
        super().__init__(parent)
        from .config import Config, HELP

        self.cfg_path = cfg_path
        self.setWindowTitle("settings")
        self.setMinimumWidth(660)
        self.cfg = Config.load(path=cfg_path)          # the file, not the flags
        self.edits: dict[str, QLineEdit] = {}

        outer = QVBoxLayout(self)
        outer.setSpacing(8)

        grid = QHBoxLayout()
        left = QVBoxLayout()
        left.setSpacing(6)
        right = QVBoxLayout()
        right.setSpacing(6)
        for i, (key, label, kind) in enumerate(self.FIELDS):
            row = QHBoxLayout()
            lbl = QLabel(label)
            lbl.setFixedWidth(230)
            lbl.setToolTip(HELP.get(key, ""))
            edit = QLineEdit(str(self.cfg[key] or ""))
            edit.setPlaceholderText(HELP.get(key, ""))
            edit.setToolTip(HELP.get(key, ""))
            self.edits[key] = edit
            row.addWidget(lbl)
            row.addWidget(edit, 1)
            if kind != "text":
                btn = QPushButton("…")
                btn.setFixedWidth(30)
                btn.clicked.connect(lambda _, k=key, kind=kind: self._pick(k, kind))
                row.addWidget(btn)
            (left if i % 2 == 0 else right).addLayout(row)
        grid.addLayout(left, 1)
        grid.addLayout(right, 1)
        outer.addLayout(grid)

        serve_box = QHBoxLayout()
        serve_lbl = QLabel("Port for `serve`")
        serve_lbl.setFixedWidth(230)
        serve_lbl.setToolTip(HELP.get("serve", ""))
        self.edits["serve.port"] = QLineEdit(str((self.cfg.serve or {}).get("port") or ""))
        serve_box.addWidget(serve_lbl)
        serve_box.addWidget(self.edits["serve.port"], 1)
        outer.addLayout(serve_box)

        token_box = QHBoxLayout()
        token_lbl = QLabel("Token the phone sends")
        token_lbl.setFixedWidth(230)
        token_lbl.setToolTip("the shared secret `serve` asks for; the phone app is given the same one")
        self.edits["serve.token"] = QLineEdit((self.cfg.serve or {}).get("token") or "")
        self.edits["serve.token"].setPlaceholderText("made automatically, and remembered")
        token_box.addWidget(token_lbl)
        token_box.addWidget(self.edits["serve.token"], 1)
        outer.addLayout(token_box)

        if overrides:
            note = QLabel("overridden on the command line right now: " + " ".join(overrides))
            note.setStyleSheet(f"color: {AMBER};")
            note.setWordWrap(True)
            outer.addWidget(note)

        where = QLabel(f"config file: {self.cfg_path}"
                       + ("" if self.cfg_path.is_file() else "   (will be created on save)"))
        where.setStyleSheet(f"color: {DIM};")
        where.setWordWrap(True)
        outer.addWidget(where)

        buttons = QHBoxLayout()
        buttons.addStretch(1)
        cancel = QPushButton("Cancel")
        cancel.clicked.connect(self.reject)
        save = QPushButton("Save")
        save.setDefault(True)
        save.clicked.connect(self._save)
        buttons.addWidget(cancel)
        buttons.addWidget(save)
        outer.addLayout(buttons)

    def _pick(self, key: str, kind: str) -> None:
        start = self.edits[key].text() or str(Path.home())
        if key == "device" and kind == "dir":
            start = device_start_dir(start)
        if kind == "dir":
            got = QFileDialog.getExistingDirectory(self, "Choose a folder", start)
        else:
            got, _ = QFileDialog.getOpenFileName(self, "Choose a file", start)
        if not got:
            return
        if key == "device":
            # Nowhere to put a phone but a path: this is the "plug it in and pick
            # it" route, which needs no serial and no adb.
            got = got if got.startswith(("adb", "dir:", "http")) else f"dir:{got}"
        self.edits[key].setText(got)

    def _save(self) -> None:
        changes: dict = {}
        for key, edit in self.edits.items():
            value = edit.text().strip()
            if key.startswith("serve."):
                leaf = key.split(".", 1)[1]
                # only the port is a number -- treating every serve.* value as one
                # turned a hex token into None and silently wiped it
                changes.setdefault("serve", {})[leaf] = (
                    int(value) if leaf == "port" and value.isdigit() else (value or None))
            else:
                changes[key] = value or None
        self.cfg.save(changes)
        self.accept()


class MainWindow(QMainWindow):
    def __init__(self, argv: list[str]):
        super().__init__()
        self.argv = argv
        self.cfg_path = config_path_from(argv)
        self.proc: QProcess | None = None
        self.stdout = ""
        self.intent = ""          # what the running process is for
        self.last_plan: dict | None = None
        self.action_note: tuple[str, str] | None = None   # survives the refresh it triggers
        self.setup_note: str | None = None                # until the library is where it should be
        self.serve_proc: QProcess | None = None
        self.serve_url: str | None = None
        self.serve_token: str | None = None
        self._elapsed = 0

        self.setWindowTitle("marimo sync")
        self.resize(1080, 700)
        self._build()
        self._apply_style()

        self.tick = QTimer(self)
        self.tick.setInterval(1000)
        self.tick.timeout.connect(self._on_tick)

        # what the phone reported and what's being prepared for it: the interesting
        # part of this window, so it's polled rather than asked for by pressing things
        self.health = QTimer(self)
        self.health.setInterval(1500)
        self.health.timeout.connect(self.poll_health)

        QTimer.singleShot(150, self.refresh)
        QTimer.singleShot(400, self.auto_serve)

    # ------------------------------------------------------------------ ui
    def _build(self) -> None:
        root = QWidget()
        self.setCentralWidget(root)
        outer = QVBoxLayout(root)
        outer.setContentsMargins(14, 12, 14, 10)
        outer.setSpacing(9)

        # -- header ----------------------------------------------------
        head = QHBoxLayout()
        head.setSpacing(18)
        left = QVBoxLayout()
        left.setSpacing(2)
        self.lbl_lib = QLabel("library  --")
        self.lbl_dev = QLabel("device   --")
        self.lbl_serve = QLabel("serving  --")
        for w in (self.lbl_lib, self.lbl_dev, self.lbl_serve):
            w.setFont(QFont("monospace", 10))
            left.addWidget(w)
        head.addLayout(left, 1)

        self.lbl_total = QLabel("")
        self.lbl_total.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        self.lbl_total.setFont(QFont("monospace", 11))
        head.addWidget(self.lbl_total)
        outer.addLayout(head)

        rule = QFrame()
        rule.setFrameShape(QFrame.Shape.HLine)
        rule.setStyleSheet(f"color: {LINE};")
        outer.addWidget(rule)

        # -- the phone -------------------------------------------------
        # This window exists to be the thing the phone talks to. So: the address you
        # type into the phone, the last thing the phone said it had, and what we're
        # doing about the difference. The server prepares whatever the phone reports
        # missing, so there is nothing to press in the right order.
        prow = QHBoxLayout()
        prow.setSpacing(10)
        self.btn_serve = QPushButton("Serve")
        self.btn_serve.setMinimumWidth(96)
        self.btn_serve.clicked.connect(self.on_serve_button)
        prow.addWidget(self.btn_serve)
        self.lbl_addr = QLabel("--  not serving")
        self.lbl_addr.setFont(QFont("monospace", 11))
        self.lbl_addr.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        prow.addWidget(self.lbl_addr, 1)
        self.btn_copy = QPushButton("Copy address")
        self.btn_copy.clicked.connect(self.copy_address)
        self.btn_copy.setToolTip("put the address on the clipboard, ready to type into the phone")
        prow.addWidget(self.btn_copy)
        outer.addLayout(prow)

        self.lbl_phone = QLabel("phone    nothing reported yet")
        self.lbl_prep = QLabel("")
        for w in (self.lbl_phone, self.lbl_prep):
            w.setFont(QFont("monospace", 10))
            w.setStyleSheet(f"color: {DIM};")
            outer.addWidget(w)

        # -- controls --------------------------------------------------
        # Kept, but no longer the point: the phone's own report is what usually
        # triggers convert/waves now, so these are for doing something by hand.
        cap = QLabel("by hand — the phone normally drives this")
        cap.setStyleSheet(f"color: {DIM}; font-size: 11px;")
        outer.addWidget(cap)
        ctl = QHBoxLayout()
        ctl.setSpacing(8)
        self.btn_refresh = QPushButton("Refresh")
        self.btn_sync = QPushButton("Sync")
        self.btn_prepare = QPushButton("Prepare")
        self.btn_prepare.setToolTip("convert from your originals and make the waveform sidecars, "
                                    "then stop — no device needed, for when the phone fetches "
                                    "over the network itself")
        self.btn_verify = QPushButton("Verify")
        self.btn_doctor = QPushButton("Doctor")
        self.btn_settings = QPushButton("Settings…")
        self.btn_diary = QPushButton("Diary…")
        self.btn_diary.setToolTip("what the two listening diaries hold, and what the "
                                  "phone last sent — read off the disk, no device needed")
        self.btn_refresh.clicked.connect(lambda: self.refresh(log=True))
        self.btn_sync.clicked.connect(self.do_sync)
        self.btn_prepare.clicked.connect(self.do_prepare)
        self.btn_verify.clicked.connect(self.do_verify)
        self.btn_doctor.clicked.connect(self.do_doctor)
        self.btn_settings.clicked.connect(self.open_settings)
        self.btn_diary.clicked.connect(self.open_diary)
        for b in (self.btn_refresh, self.btn_sync, self.btn_prepare, self.btn_verify,
                  self.btn_doctor, self.btn_settings, self.btn_diary):
            b.setMinimumWidth(88)
            ctl.addWidget(b)

        ctl.addSpacing(14)
        self.cb_convert = QCheckBox("convert from ~/Music")
        self.cb_waves = QCheckBox("generate waveforms")
        self.cb_prune = QCheckBox("prune orphans")
        self.cb_prune.setToolTip("delete things on the device that the library no longer has")
        self.cb_moves = QCheckBox("adopt renames")
        self.cb_moves.setToolTip("when an album on the device is the same album under an old "
                                 "name, move the files there instead of uploading them again")
        self.cb_serve = QCheckBox("serve to phone")
        self.cb_serve.setToolTip("let the phone fetch from this computer while this window is open "
                                 "(marimo-sync serve); the address and token are shown below")
        self.cb_serve.toggled.connect(self.toggle_serve)
        for c in (self.cb_convert, self.cb_waves, self.cb_prune, self.cb_moves, self.cb_serve):
            ctl.addWidget(c)
        ctl.addStretch(1)

        self.bar = QProgressBar()
        self.bar.setRange(0, 0)
        self.bar.setFixedWidth(120)
        self.bar.setVisible(False)
        self.lbl_busy = QLabel("")
        self.lbl_busy.setFixedWidth(150)
        self.lbl_busy.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        ctl.addWidget(self.lbl_busy)
        ctl.addWidget(self.bar)
        outer.addLayout(ctl)

        # -- banner (rescan reminder / errors) -------------------------
        self.banner = QLabel("")
        self.banner.setWordWrap(True)
        self.banner.setVisible(False)
        self.banner.setContentsMargins(9, 6, 9, 6)
        outer.addWidget(self.banner)

        # -- album table ----------------------------------------------
        filt = QHBoxLayout()
        filt.setSpacing(8)
        self.cb_all = QCheckBox("show all 634 albums")
        self.cb_all.toggled.connect(self.fill_table)
        filt.addWidget(self.cb_all)
        self.search = QLineEdit()
        self.search.setPlaceholderText("filter by album name…")
        self.search.setFixedWidth(260)
        self.search.textChanged.connect(self.fill_table)
        filt.addWidget(self.search)
        filt.addStretch(1)
        self.lbl_count = QLabel("")
        self.lbl_count.setMinimumWidth(150)
        self.lbl_count.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        filt.addWidget(self.lbl_count)
        outer.addLayout(filt)

        self.table = QTableWidget(0, 5)
        self.table.setHorizontalHeaderLabels(["", "Album", "Status", "Files", "Size"])
        self.table.verticalHeader().setVisible(False)
        self.table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.table.setShowGrid(False)
        self.table.setAlternatingRowColors(False)
        self.table.setSortingEnabled(True)
        hh = self.table.horizontalHeader()
        hh.setSectionResizeMode(0, QHeaderView.ResizeMode.ResizeToContents)
        hh.setSectionResizeMode(1, QHeaderView.ResizeMode.Stretch)
        hh.setSectionResizeMode(2, QHeaderView.ResizeMode.ResizeToContents)
        hh.setSectionResizeMode(3, QHeaderView.ResizeMode.ResizeToContents)
        hh.setSectionResizeMode(4, QHeaderView.ResizeMode.ResizeToContents)
        self.table.itemDoubleClicked.connect(self.sync_one)
        outer.addWidget(self.table, 3)

        # -- log -------------------------------------------------------
        self.log = QPlainTextEdit()
        self.log.setReadOnly(True)
        self.log.setFont(QFont("monospace", 9))
        self.log.setMaximumBlockCount(4000)
        self.log.setPlaceholderText("what the tool says will appear here")
        outer.addWidget(self.log, 2)

        self.statusBar().showMessage("ready")

    def _apply_style(self) -> None:
        self.setStyleSheet(f"""
            QWidget {{ background: {BG}; color: {TEXT}; font-size: 12px; }}
            QLabel {{ background: transparent; }}
            QPushButton {{
                background: {PANEL2}; border: 1px solid {LINE}; border-radius: 4px;
                padding: 5px 10px; color: {TEXT};
            }}
            QPushButton:hover {{ border-color: {MOSS}; }}
            QPushButton:disabled {{ color: {DIM}; border-color: {LINE}; }}
            QCheckBox {{ background: transparent; }}
            QTableWidget {{
                background: {PANEL}; border: 1px solid {LINE}; border-radius: 4px;
                gridline-color: {LINE}; selection-background-color: {LINE};
            }}
            QHeaderView::section {{
                background: {PANEL2}; color: {DIM}; border: 0px; padding: 5px;
                border-bottom: 1px solid {LINE};
            }}
            QPlainTextEdit {{
                background: {PANEL}; border: 1px solid {LINE}; border-radius: 4px;
                color: {DIM}; padding: 5px;
            }}
            QLineEdit {{
                background: {PANEL2}; border: 1px solid {LINE}; border-radius: 4px;
                padding: 4px 7px; color: {TEXT};
            }}
            QProgressBar {{ border: 0px; background: {PANEL2}; height: 3px; }}
            QProgressBar::chunk {{ background: {MOSS}; }}
            QStatusBar {{ color: {DIM}; }}
        """)

    # ---------------------------------------------------------- processes
    def _start(self, args: list[str], intent: str, quiet: bool = False) -> None:
        if self.proc is not None:
            return
        if not quiet and "--plain" not in args:
            args = ["--plain", *args]       # the log pane isn't a terminal
        self.intent = intent
        self.stdout = ""
        self._elapsed = 0
        self._busy(True)
        if not quiet:
            self.say(f"$ marimo-sync {' '.join(args)}")

        self.proc = QProcess(self)          # parented: we own it, it outlives the call
        self.proc.setProcessChannelMode(QProcess.ProcessChannelMode.SeparateChannels)
        self.proc.readyReadStandardOutput.connect(self._on_out)
        self.proc.readyReadStandardError.connect(self._on_err)
        self.proc.finished.connect(self._on_finished)
        self.proc.errorOccurred.connect(self._on_failed)
        self.proc.start(sys.executable, [str(CLI), *args])
        self.tick.start()

    def _on_out(self) -> None:
        chunk = bytes(self.proc.readAllStandardOutput()).decode(errors="replace")
        self.stdout += chunk
        if self.intent != "status-json":
            self.say(chunk.rstrip("\n"))

    def _on_err(self) -> None:
        text = bytes(self.proc.readAllStandardError()).decode(errors="replace").rstrip("\n")
        if text:
            self.say(text, colour=CLAY)

    def _on_finished(self, code: int, _status) -> None:
        intent = self.intent
        self.tick.stop()
        self.proc.deleteLater()
        self.proc = None
        self._busy(False)

        if intent == "status-json":
            if code == 0 and self.stdout.strip():
                try:
                    self.last_plan = json.loads(self.stdout)
                    self.show_plan(self.last_plan)
                except ValueError:
                    self.banner_set("could not read the plan the tool just produced", CLAY)
            else:
                self.show_device_error()
            return

        if code == 0:
            self.statusBar().showMessage(f"{intent} finished")
            if intent.startswith("sync"):
                pushed = _grep_int(self.stdout, r"pushed (\d+) file")
                removed = len([l for l in self.stdout.splitlines() if l.startswith("removed ")])
                if pushed or removed:
                    bits = []
                    if pushed:
                        bits.append(f"pushed {pushed} file(s)")
                    if removed:
                        bits.append(f"removed {removed} thing(s)")
                    self.action_note = (" · ".join(bits) + "  —  tap rescan in marimo on the phone "
                                        "so the new albums show up", MOSS)
                self.refresh(silent=True)
            else:
                self.refresh(silent=True)
        else:
            self.statusBar().showMessage(f"{intent} exited {code}")
            self.action_note = (f"{intent} exited {code} — see the log", CLAY)
            self.banner_compose("", None)

    def _on_failed(self, err) -> None:
        if self.proc is None:
            return
        self.say(f"could not start the tool: {err}", colour=CLAY)

    def _busy(self, on: bool) -> None:
        for b in (self.btn_refresh, self.btn_sync, self.btn_prepare, self.btn_verify,
                  self.btn_doctor):
            b.setEnabled(not on)
        self.bar.setVisible(on)
        if not on:
            self.lbl_busy.setText("")

    def _on_tick(self) -> None:
        self._elapsed += 1
        self.lbl_busy.setText(f"running… {self._elapsed}s")

    # ------------------------------------------------------------- actions
    def refresh(self, log: bool = False, silent: bool = False) -> None:
        if log:
            self.action_note = None
        self._start([*self.argv, "--json", "status"], "status-json", quiet=not log)

    def do_sync(self) -> None:
        self.action_note = None
        if not self._confirm_if_big():
            return
        args = [*self.argv, "sync"]
        if self.cb_convert.isChecked():
            args.append("--convert")
        if self.cb_waves.isChecked():
            args.append("--waves")
        if self.cb_prune.isChecked():
            args.append("--prune")
        if self.cb_moves.isChecked():
            args.append("--adopt-renames")
        self._start(args, "sync")

    def _confirm_if_big(self) -> bool:
        """Ask before a push big enough that a slip is expensive.

        Sending 8000 files by accident should take a deliberate click. A handful
        of files — the normal case — shouldn't have any friction at all.
        """
        plan = self.last_plan
        if not plan:
            return True
        big = (plan["push_files"] > CONFIRM_FILES
               or plan["push_bytes"] > CONFIRM_BYTES
               or (self.cb_prune.isChecked() and plan["orphan_files"] > CONFIRM_FILES))
        if not big:
            return True
        what = []
        if plan["push_files"]:
            what.append(f"send {plan['push_files']} file(s), {human(plan['push_bytes'])}")
        if self.cb_prune.isChecked() and plan["orphan_files"]:
            what.append(f"delete {plan['orphan_files']} file(s) the library doesn't have")
        box = QMessageBox(self)
        box.setWindowTitle("that's a big one")
        box.setIcon(QMessageBox.Icon.Warning)
        box.setText(" and ".join(what).capitalize() + "?")
        box.setInformativeText(f"on {self.lbl_dev.text().strip()}")
        box.setStandardButtons(QMessageBox.StandardButton.Cancel | QMessageBox.StandardButton.Ok)
        box.setDefaultButton(QMessageBox.StandardButton.Cancel)
        return box.exec() == QMessageBox.StandardButton.Ok

    # ---------------------------------------------------------------- serving
    def auto_serve(self) -> None:
        """Serve the library for as long as this window is open.

        The phone needs a *stable* token, so one is made once and written to the
        config: `serve`'s default is a fresh token per run, which would break the
        phone's saved one on every single launch.
        """
        from .config import Config
        cfg = Config.load(path=self.cfg_path)
        settings = cfg.serve or {}
        if not settings.get("auto", True):
            self.cb_serve.blockSignals(True)
            self.cb_serve.setChecked(False)
            self.cb_serve.blockSignals(False)
            self.serve_label("off", DIM)
            return
        if self.serve_proc is not None:
            return

        token = settings.get("token")
        if not token:
            token = hashlib.sha256(os.urandom(32)).hexdigest()[:12]
            cfg.save({"serve": {"token": token}})
            self.say(f"made a token for the phone and saved it in {self.cfg_path}")
        port = int(settings.get("port") or 8422)
        self.serve_token = token
        self.serve_url = f"http://{lan_address()}:{port}"

        self.serve_proc = QProcess(self)          # parented: we own it, it outlives the call
        self.serve_proc.setProcessChannelMode(QProcess.ProcessChannelMode.SeparateChannels)
        self.serve_proc.readyReadStandardError.connect(self._on_serve_err)
        self.serve_proc.finished.connect(self._on_serve_done)
        # --quiet: the banner and a line per request are noise here -- the label says
        # what matters, and serve's own logging would otherwise fill the pane
        self.serve_proc.start(sys.executable, [str(CLI), *self.argv, "--plain", "serve",
                                              "--quiet", "--port", str(port), "--token", token])
        self.say(f"serving the library to the phone at {self.serve_url}")
        self.serve_label(f"on · {self.serve_url} · token {token}", MOSS)
        self.cb_serve.blockSignals(True)
        self.cb_serve.setChecked(True)
        self.cb_serve.blockSignals(False)

    def on_serve_button(self) -> None:
        """Serve / stop. The checkbox owns the state; this is the big handle for it."""
        self.cb_serve.setChecked(not self.cb_serve.isChecked())

    def copy_address(self) -> None:
        if not self.serve_url:
            self.say("nothing to copy -- the server isn't running", colour=CLAY)
            return
        QApplication.clipboard().setText(self.serve_url)
        self.say(f"copied {self.serve_url} -- that's what goes into the phone")

    def poll_health(self) -> None:
        """The phone's last report, and what we're preparing for it.

        All of it comes from the server this window already started, so the window has
        nothing to be told: the phone says what it has, the server works out the
        difference and gets it ready, and this just shows it happening.
        """
        if self.serve_proc is None or not self.serve_url:
            return

        def get(path):
            req = urllib.request.Request(self.serve_url + path)
            if self.serve_token:
                req.add_header("X-Marimo-Token", self.serve_token)
            with urllib.request.urlopen(req, timeout=2) as r:
                return json.loads(r.read().decode("utf-8", "replace"))

        try:
            prep = get("/api/health").get("preparing") or {}
        except Exception as e:
            self.lbl_prep.setText(f"server   unreachable ({e})")
            return
        state = prep.get("state", "idle")
        album = prep.get("album") or ""
        total = prep.get("total") or 0
        if state in ("queued", "converting", "waveforms", "covers"):
            self.lbl_prep.setText(f"{state}   {album}"
                                  + (f"   ({total} to do)" if total else ""))
        elif state == "ready":
            self.lbl_prep.setText(
                f"ready   {len(prep.get('albums') or [])} album(s) prepared for the phone")
        elif state == "failed":
            self.lbl_prep.setText(f"prepare failed   {prep.get('error', '')}")
        else:
            self.lbl_prep.setText("nothing to prepare")

        try:
            man = get("/api/manifest.json")
        except Exception:
            return
        albums = man.get("albums") or {}
        files = sum(len(a.get("files", {}) or {}) for a in albums.values())
        when = (man.get("reported") or "")[11:19]
        self.lbl_phone.setText(
            f"phone    {len(albums)} albums · {files} files"
            + (f"   ·   reported {when}" if when else "")
            if albums else "phone    nothing reported yet")

    def serve_label(self, text: str, colour: str) -> None:
        self.lbl_serve.setText(f"serving  {text}")
        self.lbl_serve.setStyleSheet(f"color: {colour};")
        on = self.serve_proc is not None
        self.btn_serve.setText("Stop" if on else "Serve")
        if on and self.serve_url:
            self.lbl_addr.setText(f"{self.serve_url}   ·   token {self.serve_token or ''}")
            self.health.start()
        else:
            self.lbl_addr.setText("--  not serving")
            self.health.stop()
            self.lbl_prep.setText("")

    def _on_serve_err(self) -> None:
        if self.serve_proc is None:
            return
        text = bytes(self.serve_proc.readAllStandardError()).decode(errors="replace").strip()
        if text and not text.startswith("token "):     # --quiet's own token line
            self.say(f"serve: {text}", colour=CLAY)

    def _on_serve_done(self, code: int, _status) -> None:
        self.serve_proc = None
        if getattr(self, "_stopping_serve", False):
            self._stopping_serve = False
            return
        self.serve_label(f"stopped (exit {code}) — is the port already in use?", CLAY)

    def stop_serve(self) -> None:
        if self.serve_proc is not None:
            self._stopping_serve = True
            self.serve_proc.terminate()
            if not self.serve_proc.waitForFinished(3000):
                self.serve_proc.kill()
            self.serve_proc = None
        self.serve_label("off", DIM)

    def toggle_serve(self, want: bool) -> None:
        from .config import Config
        Config.load(path=self.cfg_path).save({"serve": {"auto": bool(want)}})
        if want:
            self.auto_serve()
        else:
            self.stop_serve()

    def open_diary(self) -> None:
        """The Diary panel. Read-only, device-free, and useful before the phone has
        ever reported: it says so rather than showing blanks."""
        DiaryDialog(self, self.cfg_path).exec()

    def open_settings(self) -> None:
        overrides = overrides_in(self.argv)
        dlg = SettingsDialog(self, self.cfg_path, overrides)
        if dlg.exec() != QDialog.DialogCode.Accepted:
            return
        dropped = overrides
        self.argv = strip_overrides(self.argv)
        self.say(f"settings saved to {self.cfg_path}")
        if dropped:
            self.say("dropped the command-line overrides so the new settings apply: "
                     + " ".join(dropped))
        self.action_note = ("settings saved", MOSS)
        self.refresh()

    def do_prepare(self) -> None:
        """Convert and make waveforms, then stop. Never touches a device."""
        self.action_note = None
        self._start([*self.argv, "sync", "--convert", "--waves", "--prepare"], "prepare")

    def do_verify(self) -> None:
        self.action_note = None
        self._start([*self.argv, "verify"], "verify")

    def do_doctor(self) -> None:
        self.action_note = None
        self._start([*self.argv, "doctor"], "doctor")

    def sync_one(self, item: QTableWidgetItem) -> None:
        name = self.table.item(item.row(), 1)
        if not name:
            return
        album = name.text()
        args = [*self.argv, "sync", "--album", album]
        if self.cb_waves.isChecked():
            args.append("--waves")
        if self.cb_prune.isChecked():
            args.append("--prune")
        self._start(args, f"sync {album[:24]}")

    # -------------------------------------------------------------- display
    def show_plan(self, plan: dict) -> None:
        lib, dev = plan["library"], plan["device"]
        self.lbl_lib.setText(f"library  {lib['albums']} albums · {lib['files']} files · "
                             f"{human(lib['bytes'])}   {lib['root']}")
        free = dev.get("free_bytes")
        self.lbl_dev.setText(f"device   {dev['label']} · {dev['files']} files"
                            + (f" · {human(free)} free" if free else "")
                            + f"   {dev['root']}")

        bits = []
        if plan["push_files"]:
            bits.append(f"{plan['push_files']} file(s) · {human(plan['push_bytes'])} to push")
        if plan.get("renames"):
            bits.append(f"{plan['renames']} rename(s) · {human(plan['rename_bytes'])} already there")
        if plan["orphan_files"]:
            bits.append(f"{plan['orphan_files']} orphaned")
        if bits:
            self.lbl_total.setText(" · ".join(bits))
            self.lbl_total.setStyleSheet(f"color: {AMBER};")
        else:
            n = plan["albums_in_sync"]
            self.lbl_total.setText(f"{n} album{'' if n == 1 else 's'} in sync")
            self.lbl_total.setStyleSheet(f"color: {MOSS};")

        self.btn_sync.setText(f"Sync  {plan['push_files']} files" if plan["push_files"] else "Sync")

        # First run, or a path that has moved: say what to do instead of showing an
        # empty table and letting them guess.
        root = Path(lib["root"])
        if not lib["albums"]:
            if not root.is_dir():
                self.setup_note = (f"there's no library at {root} — Settings… to point it at "
                                   f"your music folder  ({self.cfg_path})")
            else:
                self.setup_note = f"{root} has no album folders in it"
        else:
            self.setup_note = None

        notes = []
        if plan["needs_sidecar"]:
            notes.append(f"{plan['needs_sidecar']} album(s) with no usable waveform sidecar")
        if plan["unverified"]:
            notes.append(f"{plan['unverified']} file(s) believed by size only — Verify reads them back")
        self.banner_compose(" · ".join(notes), AMBER if notes else None)

        self.fill_table()
        self.cb_all.setText(f"show all {plan['albums_total']} albums")
        self.statusBar().showMessage("up to date")

    def show_device_error(self) -> None:
        self.lbl_lib.setText("library  --")
        self.lbl_dev.setText("device   not reachable")
        self.lbl_total.setText("")
        self.table.setRowCount(0)
        self.setup_note = None
        self.banner_compose(
            f"can't reach the device — Settings… to change it, or plug the phone in "
            f"({self.cfg_path})", CLAY)

    def fill_table(self) -> None:
        if not self.last_plan:
            return
        albums = self.last_plan["albums"]
        needle = self.search.text().strip().lower()
        show_all = self.cb_all.isChecked()
        rows = [a for a in albums
                if (show_all or a["status"] != "ok")
                and (not needle or needle in a["name"].lower())]

        self.table.setSortingEnabled(False)
        self.table.setRowCount(len(rows))
        for r, a in enumerate(rows):
            note = []
            if a["push"]:
                note.append(f"{a['push']} to push" + (f", {a['overwrite']} replacing" if a["overwrite"] else ""))
            if a["orphans"]:
                note.append(f"{a['orphans']} orphaned")
            if a.get("move_to"):
                note.append(f"{a['move_files']} can move to {a['move_to'][:32]} "
                            f"({human(a['move_bytes'])} not uploaded)")
            if a["missing_sidecar"]:
                note.append("no sidecar")
            elif a["stale_sidecar"]:
                note.append(f"sidecar missing {a['stale_sidecar']} track(s)")
            if a["unverified"] and not a["push"]:
                note.append(f"{a['unverified']} unverified")

            glyph = STATUS_GLYPH[a["status"]]
            cells = [
                (glyph, STATUS_COLOUR[a["status"]], Qt.AlignmentFlag.AlignCenter),
                (a["name"], TEXT, Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter),
                (STATUS_LABEL[a["status"]], STATUS_COLOUR[a["status"]], Qt.AlignmentFlag.AlignLeft),
                (str(a["push"]) if a["push"] else "-", DIM, Qt.AlignmentFlag.AlignRight),
                (human(a["bytes"]) if a["bytes"] else "-", DIM, Qt.AlignmentFlag.AlignRight),
            ]
            for c, (value, colour, align) in enumerate(cells):
                item = QTableWidgetItem(value)
                item.setForeground(QColor(colour))
                item.setTextAlignment(align)
                if c == 1:
                    detail = _tooltip(a)
                    if note:
                        detail += "\n\n" + "\n".join("• " + n for n in note)
                    item.setToolTip(detail)
                self.table.setItem(r, c, item)
            self.table.setRowHeight(r, 22)
        self.table.setSortingEnabled(True)
        self.lbl_count.setText(f"{len(rows)} shown of {self.last_plan['albums_total']}")

    def banner_set(self, text: str, colour: str | None) -> None:
        if not text:
            self.banner.setVisible(False)
            return
        self.banner.setVisible(True)
        self.banner.setText(text)
        self.banner.setStyleSheet(
            f"background: {PANEL2}; border-left: 3px solid {colour or LINE}; color: "
            f"{colour or TEXT}; border-radius: 3px;")

    def banner_compose(self, notes: str, note_colour: str | None) -> None:
        """Setup hint, then the action note, then the plan's own notes."""
        parts = []
        if self.setup_note:
            parts.append(self.setup_note)
        if self.action_note:
            parts.append(self.action_note[0])
        if notes:
            parts.append(notes)
        if not parts:
            self.banner_set("", None)
            return
        colour = CLAY if self.setup_note else (
            self.action_note[1] if self.action_note else (note_colour or LINE))
        self.banner_set("\n".join(parts), colour)

    def say(self, text: str, colour: str | None = None) -> None:
        if not text:
            return
        if colour:
            self.log.appendHtml(f'<span style="color:{colour}">{_escape(text)}</span>')
        else:
            self.log.appendPlainText(text)
        self.log.verticalScrollBar().setValue(self.log.verticalScrollBar().maximum())

    # ---------------------------------------------------------------- close
    def closeEvent(self, event) -> None:
        # no threads to join, but don't leave a push running under a dead window,
        # and don't leave a server up behind it either
        self.stop_serve()
        if self.proc is not None and self.proc.state() != QProcess.ProcessState.NotRunning:
            self.proc.terminate()
            if not self.proc.waitForFinished(3000):
                self.proc.kill()
        super().closeEvent(event)


def _tooltip(a: dict) -> str:
    bits = [a["name"], ""]
    bits.append(f"in the library: {a['in_library']} file(s)")
    bits.append(f"on the device:  {a['on_device']} file(s)")
    if a["push"]:
        bits.append(f"to send:        {a['push']} file(s), {human(a['bytes'])}")
    if a["orphans"]:
        bits.append(f"on the device only: {a['orphans']} file(s)")
    if a["unverified"]:
        bits.append(f"unverified:     {a['unverified']} file(s) match by size only")
    if a.get("move_to"):
        bits.append("")
        bits.append(f"this is the same album as: {a['move_to']}")
        bits.append(f"{a['move_files']} file(s), {human(a['move_bytes'])} already on the device")
        bits.append("tick 'adopt renames' and Sync to move them instead of uploading")
    bits += ["", "double-click to sync just this album"]
    return "\n".join(bits)


def _escape(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def _grep_int(text: str, pattern: str) -> int:
    import re
    m = re.search(pattern, text)
    return int(m.group(1)) if m else 0


def main(argv: list[str] | None = None) -> int:
    app = QApplication(sys.argv[:1])
    app.setApplicationName("marimo sync")
    window = MainWindow(list(argv or []))
    window.show()
    return app.exec()
