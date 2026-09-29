"""The Diary panel, shared by both windows.

One implementation, two callers: the app window's ⋯ menu (`app.py` -- the window that
is normally open) and the push console's button row (`gui.py`). A second copy of this
panel would be a second thing to keep in step, which is exactly the failure mode the
merge rule already taught us to avoid.

Everything it shows comes off the disk: the player's own `history.jsonl` (lines, and
when it last changed) and the record the server writes when the phone posts its diary
(`diary-last-merge.json`, beside the phone's inventory). No transport is constructed and
nothing is fetched, which is what keeps the app window's long-standing "resolves no
device" promise true -- `tests/app_smoke.py` asserts it.
"""

from __future__ import annotations

import datetime
import hashlib
import json
from pathlib import Path

from PyQt6.QtWidgets import QDialog, QHBoxLayout, QLabel, QPushButton, QVBoxLayout


class DiaryDialog(QDialog):
    """What the two listening diaries hold, and what the last merge did."""

    def __init__(self, parent, cfg_path: Path):
        super().__init__(parent)
        from .config import Config

        self.cfg = Config.load(path=cfg_path)
        self.setWindowTitle("diary")
        self.setMinimumWidth(560)
        outer = QVBoxLayout(self)
        outer.setSpacing(8)
        self.body = QLabel("")
        self.body.setWordWrap(True)
        outer.addWidget(self.body)
        row = QHBoxLayout()
        row.addStretch(1)
        shut = QPushButton("Close")
        shut.clicked.connect(self.accept)
        row.addWidget(shut)
        outer.addLayout(row)
        self.fill()

    def fill(self) -> None:
        """Read the diary and the merge record, and say plainly when there is neither."""
        diary = self.cfg.diary / "history.jsonl"
        lines: list[str] = []
        if diary.is_file():
            data = diary.read_bytes()
            count = len([l for l in data.split(b"\n") if l])
            when = datetime.datetime.fromtimestamp(diary.stat().st_mtime)
            lines.append(f"this desktop    {count} lines, last changed {when:%Y-%m-%d %H:%M}, "
                         f"sha256 {hashlib.sha256(data).hexdigest()[:16]}")
        else:
            lines.append(f"this desktop    no diary yet ({diary})")

        info: dict = {}
        if self.cfg.diary_record.is_file():
            try:
                info = json.loads(self.cfg.diary_record.read_text()) or {}
            except ValueError:
                info = {}
        if info:
            # Each number below states its own moment: the file as it is *now* (above),
            # and the exchange as it *was* (here). The live file moves whenever the
            # player logs a track, so "now" and "then" disagreeing is the normal case,
            # not an error -- the labels have to carry that, not the numbers.
            stamped = str(info.get("at", "?")).replace("T", " ")[:16]
            lines.append(f"the phone       posted {info.get('posted_lines', '?')} lines at "
                         f"{info.get('at', '?')}")
            lines.append(f"last merge      {stamped}  added {info.get('added', '?')}, "
                         f"skipped {info.get('duplicates', '?')} duplicate(s), then "
                         f"{info.get('lines_after', '?')} lines")
            lines.append(f"last reply      {str(info.get('sha256', ''))[:16]}  "
                         f"({info.get('size', '?')} bytes), as sent")
        else:
            lines.append("the phone       has not posted its diary yet")
        lines.append("")
        lines.append("The phone sends it when it syncs (in the app: Settings → sync with "
                     "desktop); nothing here needs a cable.")
        self.body.setText("\n".join(lines))
