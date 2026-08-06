package moe.umbrella.marimo;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Aggregation for the recap feature: turns raw diary entries (see
 *  HistoryDiary) into a "your week/month/year in marimo" summary.
 *
 *  Pure + static (no android deps) so it can be unit-tested standalone.
 *
 *  "Counts as a play" rule: heard > 30s AND (duration unknown ? heard >= 4min
 *  : heard >= 50% of the track). Half-skipped tracks don't pad the numbers. */
public final class Recap {

    /** a ranked row in a top-5 list */
    public static class Row {
        public final String name;
        public final long count;
        public Row(String name, long count) { this.name = name; this.count = count; }
    }

    /** a fully aggregated recap */
    public static class Result {
        public long hours, totalSec, tracks, artists, albums;
        public long hoursPrev, totalSecPrev, tracksPrev;  // previous-period deltas
        public List<Row> topArtists = new ArrayList<>();
        public List<Row> topAlbums = new ArrayList<>();
        public List<Row> topTracks = new ArrayList<>();
        public long[] bars;                      // per-day / per-week / per-month
        public boolean empty;                    // nothing qualified in window
    }

    private Recap() { }

    public static boolean countsAsPlay(HistoryDiary.Entry e) {
        if (e.sec <= 30) return false;          // <31s heard: skip
        if (e.dur <= 0) return e.sec >= 240;    // duration unknown: 4min fallback
        return e.sec * 1000 >= e.dur / 2;       // heard >= 50% of the track
    }

    /** period labels for bar buckets */
    public static final int MODE_WEEK = 0, MODE_MONTH = 1, MODE_YEAR = 2;

    /** calendar-anchored window boundaries (epoch ms) for a period ending at
     *  or before `now`. returns {curStart, curEnd, prevStart, prevEnd}. */
    public static long[] windows(int mode, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        long curStart, curEnd, prevStart, prevEnd;
        if (mode == MODE_WEEK) {
            // previous completed Mon-Sun week before now
            c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
            curEnd = c.getTimeInMillis();                     // this Monday
            curStart = curEnd - 7L * 24 * 3600 * 1000;
            prevStart = curStart - 7L * 24 * 3600 * 1000;
        } else if (mode == MODE_MONTH) {
            c.set(Calendar.DAY_OF_MONTH, 1);
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
            curEnd = c.getTimeInMillis();                     // 1st of this month
            c.add(Calendar.MONTH, -1);
            curStart = c.getTimeInMillis();
            c.add(Calendar.MONTH, -1);
            prevStart = c.getTimeInMillis();
        } else { // YEAR
            c.set(Calendar.DAY_OF_YEAR, 1);
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
            curEnd = c.getTimeInMillis();                     // Jan 1 this year
            c.add(Calendar.YEAR, -1);
            curStart = c.getTimeInMillis();
            c.add(Calendar.YEAR, -1);
            prevStart = c.getTimeInMillis();
        }
        prevEnd = curStart;
        long[] w = {curStart, curEnd, prevStart, prevEnd};
        return w;
    }

    /** aggregate [cur] for the current window against [prev] for deltas.
     *  Pure: callers fetch the entry lists (HistoryDiary.forWindow) using the
     *  windows() helper below. */
    public static Result compute(List<HistoryDiary.Entry> cur,
                                 List<HistoryDiary.Entry> prev, int mode) {
        Result r = new Result();

        // count plays + totals for this window
        long secs = 0;
        Map<String, Long> artists = new HashMap<>(), albums = new HashMap<>();
        Map<String, Long> tracks = new HashMap<>();
        for (HistoryDiary.Entry e : cur) {
            if (!countsAsPlay(e)) continue;
            secs += e.sec;
            bump(artists, e.artist);
            bump(albums, e.album);
            bump(tracks, e.title);
        }
        r.totalSec = secs;
        r.hours = secs / 3600;
        r.tracks = curCount(cur);
        r.artists = artists.size();
        r.albums = albums.size();
        r.empty = r.tracks == 0;

        // deltas vs previous period (hours + tracks)
        long pSecs = 0;
        for (HistoryDiary.Entry e : prev)
            if (countsAsPlay(e)) pSecs += e.sec;
        r.totalSecPrev = pSecs;
        r.hoursPrev = pSecs / 3600;
        r.tracksPrev = prevCount(prev);

        // top 5s
        r.topArtists = topN(artists);
        r.topAlbums = topN(albums);
        r.topTracks = topN(tracks);

        // adaptive bar chart
        r.bars = bars(cur, mode);
        return r;
    }

    private static long curCount(List<HistoryDiary.Entry> cur) {
        long n = 0;
        for (HistoryDiary.Entry e : cur) if (countsAsPlay(e)) n++;
        return n;
    }

    private static long prevCount(List<HistoryDiary.Entry> prev) {
        long n = 0;
        for (HistoryDiary.Entry e : prev) if (countsAsPlay(e)) n++;
        return n;
    }

    private static void bump(Map<String, Long> m, String key) {
        if (key == null || key.isEmpty()) key = "?";
        m.put(key, m.getOrDefault(key, 0L) + 1);
    }

    private static List<Row> topN(Map<String, Long> m) {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, Long> e : m.entrySet())
            rows.add(new Row(e.getKey(), e.getValue()));
        rows.sort((a, b) -> Long.compare(b.count, a.count));   // desc by count
        if (rows.size() > 5) rows = new ArrayList<>(rows.subList(0, 5));
        return rows;
    }

    /** per-day (week) / per-week (month) / per-month (year) play counts */
    private static long[] bars(List<HistoryDiary.Entry> cur, int mode) {
        int n = mode == MODE_WEEK ? 7 : mode == MODE_MONTH ? 5 : 12;
        long[] bars = new long[n];
        Calendar c = Calendar.getInstance();
        for (HistoryDiary.Entry e : cur) {
            if (!countsAsPlay(e)) continue;
            c.setTimeInMillis(e.ts);
            int idx;
            if (mode == MODE_WEEK) {
                idx = c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;   // Sun..Sat
            } else if (mode == MODE_MONTH) {
                idx = Math.min(4, (c.get(Calendar.DAY_OF_MONTH) - 1) / 7);
            } else {
                idx = c.get(Calendar.MONTH);                            // 0..11
            }
            if (idx >= 0 && idx < n) bars[idx]++;
        }
        return bars;
    }

    /** format seconds-of-hours as "1h 05m" / "42m" */
    public static String fmtHours(long totalSeconds, boolean zeroOk) {
        long h = totalSeconds / 3600, m = (totalSeconds % 3600) / 60;
        if (h == 0 && m == 0) return zeroOk ? "0m" : "—";
        if (h == 0) return m + "m";
        return h + "h " + String.format("%02dm", m);
    }

    /** delta arrow helper: +N / −N (green up / red down), 0 = "—" */
    public static String delta(long prev, long cur, String unit) {
        long d = cur - prev;
        if (d == 0) return "—";
        return (d > 0 ? "▲ +" : "▼ −") + Math.abs(d) + " " + unit;
    }
}
