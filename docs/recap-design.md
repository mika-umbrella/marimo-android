# Weekly / Monthly / Yearly Listening Recap (design)

Feature: local, last.fm-style listening recaps for marimo-android — "your week/month/year
in marimo" — delivered as a notification on reset day with a share action, plus viewable
pages in settings. Everything is LOCAL: no external service, no privacy leak; the app keeps
its own diary about what was played.

Suggested by one of nova's friends (2026-08-06). Designed with nova; build deferred to a
later session. DESIGN ONLY — not yet implemented.

## Storage: the history diary

- **One newline-delimited JSON file per finished calendar year**, in the app's private
  internal storage (`context.filesDir`), NOT the SAF tree (it's our own data, real paths are
  fine there).
  - Current year: plain `history.jsonl` (append-only).
  - When a new year starts, roll the completed year aside as `history.YYYY.jsonl.gz`
    (gzip — JSON compresses ~80%; decompress-on-demand is a few ms for ~5MB, and yearly
    reports are rare so inflating them is free).
- **One line per finished listen**:
  `{"ts": <unix_epoch>, "artist": "...", "album": "...", "title": "...", "sec": <seconds_heard>}`
  Written by the Kotlin layer on each track transition (the scrobble hook point).
  `ts` = any unambiguous anchor; `sec` = how much of the track was actually heard.
- **What counts as a "play"**: seconds heard > 30s AND > 50% of the track, so half-skipped
  songs don't inflate the numbers (last.fm scrobble-ish rule, but local).
- **Corrupt/fragment line** (crash mid-write): skip non-JSON lines and self-heal rather than
  failing the whole report.
- **Why per-year files, not one growing log**: a weekly/monthly recap only ever reads the
  current year's file (and skims recent lines) — old data stays sealed + out of the way.
  The gzip is a tidiness/perf bonus, not a space necessity (the whole history is ~5-6MB/year
  even at ~100 tracks/day). The per-year layout handles retention natively (each finished
  year is simply a sealed file) — no extra prune/keep-cap logic needed.

### Approximate size (avg ~130B/line)
| window | 50 tracks/day | 100 tracks/day |
|---|---|---|
| day  | 6.5 KB   | 13 KB   |
| week | ~45 KB   | ~91 KB  |
| month| ~195 KB  | ~390 KB |
| year | ~2.3 MB  | ~4.7 MB |

Keep ~16-18 months of history so the yearly report always has a full window (a year + the
running current year + slack for "vs last year"). Pruning is lazy: on write, drop the oldest
lines/files past the cap.

## Recap suite (all three)

- **Weekly** — last calendar week (Mon–Sun). Notification on **Monday** with headline stats +
  a share action → android share sheet (one tap to bluesky / discord / text).
- **Monthly** — last calendar month. Notification on the **1st**.
- **Yearly** — last calendar year. Notification on **Jan 1**. Includes **vs. last year**
  comparison (needs prior year's file — decompress on demand).

### Screen contents (viewable in settings; all three)
- Headline stat row: **hours listened · tracks · artists · albums**
- **Top 5 artists** (count + proportional bar)
- **Top 5 albums**
- **Top 5 tracks**
- **Bar chart** that adapts to the window so it never reads as a wall of bars:
  - weekly → 7 bars = the days
  - monthly → ~5 bars = each week of the month
  - yearly → 12 bars = the months

### Notification / share
- Notifications on reset day for weekly, monthly, AND yearly (they're rare — a ritual, not a nag).
- Share action composes a clean text card, e.g.
  `"your week in marimo: 214 tracks · 11h 40m · top artist DJ Sharpnel"`.
- Android 13+ needs POST_NOTIFICATIONS permission. Scheduling: WorkManager/JobScheduler for
  the weekly boundary, or compute next-reset and schedule — design detail for build time.

### Empty-week edge case
If a reset-week has 0 plays, consider not skipping silently — maybe show a soft check-in:
`"you barely listened to anything this week. everything okay?"`. **PENDING — nova is unsure,
leaning okay but not locked.** Flag at build time.

## Decisions locked with nova (2026-08-06)
- All three recaps, local, free (monthly is normally last.fm-Pro-gated — ours is free).
- Wrapped weekly anchored to calendar weeks, reset Monday.
- Monthly + yearly notifs too.
- vs. previous period arrows (week vs last week / month vs last month / year vs last year).
- Per-year gzipped archive layout (see Storage).
- Chart adapts days → weeks → months.
- Rolling: NO — calendar-anchored wraps, not rolling windows.

## Build status
**BUILT (2026-08-06)** — data + aggregation + UI + notifications all implemented in the
marimo-android repo (Java, not Kotlin), 20 JVM unit tests green. What was deferred this
session: on-device verification (needs sudo waydroid / a device; nova will test on her phone).

### What shipped (commit d… )
- **HistoryDiary.java** — append-only `history.jsonl` per finished listen in filesDir,
  gzipped year archives (`history.YYYY.jsonl.gz`), lazy prune of years older than the
  previous one, corruption-tolerant reads. Hooked into PlaybackService at BOTH transition
  points (`onMediaItemTransition` + `STATE_ENDED`), alongside the existing scrobble.
  **Design deltas:** (1) added `dur` (duration_ms) to each line so the ">50% of a track
  counts as a play" rule can be enforced at aggregation time — the original spec omitted
  it, which would have made the rule un-enforceable; (2) every transition is logged (raw
  `sec` truth) so future skip-rate / replay / most-skipped stats have data.
- **Recap.java** — pure aggregator (no android deps): play-count rule, hours/tracks/artists/
  albums, top 5s, adaptive bars (7 days / 5 weeks / 12 months), vs-previous deltas,
  calendar window math.
- **RecapScheduler.java + RecapWorker.java** — WorkManager one-shot chains per period that
  reschedule themselves on the next reset (Mon 9am / 1st 9am / Jan 1 9am), full recap
  notification with a share action on reset day.
- **Settings recap screen** — `set_recap` in settings → "your week/month/year in marimo"
  (week/month/year toggle, stat row, delta line, bar chart, top 5s with proportional bars,
  a share button, and a soft empty-state). Reuses the art-gradient theme.
- **POST_NOTIFICATIONS** runtime request on 13+ (already in the manifest).
- **Empty-state decision confirmed:** empty week → gentle check-in notification ("barely
  listened — everything okay?"); empty month/year → silent.

## Note for nova on first real run
The diary only fills from the moment this build is installed and tracks are played — the
recap will look empty until ~30s+ of a few tracks accumulate. The weekly recap is for the
week *that just ended* (Mon 9am), so first recaps appear the Monday after some listening.

## Decisions locked with nova (2026-08-06)

## Mockups (2026-08-06)
Horizontal storyboards (grid of square cards, one idea per screen) in `screens/`:
- `recap-weekly.png` / `recap-monthly.png` / `recap-yearly.png` (2094×1486)
- Weekly = last.fm's real weekly shape + persona + streak + honest-stuff + discovery.
- Monthly = week-bars + "weekend warrior" persona + month stats.
- Yearly = vs-last-year banner card + 12 month-bars + "seasonal listener" + year numbers.
- Stat ideas included: time-of-day persona, listening streak, longest session, skip rate,
  replay king, most-skipped, discovery-vs-comfort. These are LOCAL-only (last.fm can never
  see skips/replays/pauses) — the differentiator of a local recap.
- Minor caveats to fix in the real build: the "hours" value ("11h 40m") overflows its stat
  box (sizing, not design); the procedural marimo mascot is slightly imperfect.
- Built with a one-off PIL script (the design renderer), not the app.

### Extra stat ideas (from the brainstorm, 2026-08-06)
time-of-day persona (midnight/afternoon/morning), listening streak (days in a row),
longest single session, skip rate, replay king (most-looped track), most-skipped track,
discovery-vs-comfort (new artists vs revisited). All local-only values last.fm can't see.
