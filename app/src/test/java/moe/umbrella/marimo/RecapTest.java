package moe.umbrella.marimo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import static org.junit.Assert.*;

/** Pure-JVM tests for the recap aggregation math (Recap) + the diary's
 *  "counts as a play" rule. HistoryDiary.Entry is a plain holder, so no
 *  android runtime is exercised here. */
public class RecapTest {

    private static HistoryDiary.Entry entry(long ts, long sec, long dur,
                                            String artist, String album, String title) {
        HistoryDiary.Entry e = new HistoryDiary.Entry();
        e.ts = ts; e.sec = sec; e.dur = dur;
        e.artist = artist; e.album = album; e.title = title;
        return e;
    }

    /* ---------------- counts-as-a-play rule ---------------- */

    @Test public void shortListensDoNotCount() {
        HistoryDiary.Entry e = entry(0, 20, 300_000, "A", "B", "C"); // 20s <30s
        assertFalse(Recap.countsAsPlay(e));
    }

    @Test public void halfSkippedDoesNotCount() {
        HistoryDiary.Entry e = entry(0, 60, 300_000, "A", "B", "C"); // 60s of 300s = 20%
        assertFalse(Recap.countsAsPlay(e));
    }

    @Test public void fullTrackCounts() {
        HistoryDiary.Entry e = entry(0, 300, 300_000, "A", "B", "C"); // 300s of 300s
        assertTrue(Recap.countsAsPlay(e));
    }

    @Test public void overHalfCounts() {
        HistoryDiary.Entry e = entry(0, 170, 300_000, "A", "B", "C"); // 170>150
        assertTrue(Recap.countsAsPlay(e));
    }

    @Test public void exactlyHalfCounts() {
        HistoryDiary.Entry e = entry(0, 150, 300_000, "A", "B", "C"); // 150>=150
        assertTrue(Recap.countsAsPlay(e));
    }

    @Test public void unknownDurationFallsBackToFourMinutes() {
        assertFalse(Recap.countsAsPlay(entry(0, 120, 0, "A", "B", "C")));  // 2m
        assertTrue(Recap.countsAsPlay(entry(0, 241, 0, "A", "B", "C")));  // 4m+
    }

    /* ---------------- aggregation totals + top 5s ---------------- */

    @Test public void aggregatesTotalsAndRanks() {
        List<HistoryDiary.Entry> cur = new ArrayList<>();
        long t = 1_700_000_000_000L;
        // 4 qualifying plays + 2 that don't count
        cur.add(entry(t, 200, 300_000, "DJ Sharpnel", "Algo-Logic", "In the Blue"));
        cur.add(entry(t, 200, 300_000, "DJ Sharpnel", "Algo-Logic", "In the Blue"));
        cur.add(entry(t, 200, 300_000, "Utsu-P", "TRAUMATIC", "Love For You"));
        cur.add(entry(t, 100, 300_000, "Utsu-P", "TRAUMATIC", "Love For You")); // 100<150 skip
        cur.add(entry(t, 20, 300_000, "Halfman", "X", "Skim"));               // 20s skip
        cur.add(entry(t, 250, 300_000, "boris", "Amplifier Worship", "Huge"));

        Recap.Result r = Recap.compute(cur, new ArrayList<>(), Recap.MODE_WEEK);

        assertEquals(4, r.tracks);            // DJ Sharpnel x2, Utsu-P x1, boris x1
        assertEquals(3, r.artists);           // DJ Sharpnel, Utsu-P, boris
        assertEquals(3, r.albums);            // Algo-Logic, TRAUMATIC, Amplifier Worship
        assertEquals(3, r.topArtists.size()); // ranked list of unique artists
        assertEquals("DJ Sharpnel", r.topArtists.get(0).name);
        assertEquals(2, r.topArtists.get(0).count);
    }

    @Test public void topArtistsRankedByCount() {
        List<HistoryDiary.Entry> cur = new ArrayList<>();
        long t = 1_700_000_000_000L;
        cur.add(entry(t, 200, 300_000, "A", "a1", "t1"));
        cur.add(entry(t, 200, 300_000, "A", "a1", "t2"));
        cur.add(entry(t, 200, 300_000, "B", "b1", "t3"));
        cur.add(entry(t, 200, 300_000, "C", "c1", "t4"));

        Recap.Result r = Recap.compute(cur, new ArrayList<>(), Recap.MODE_WEEK);
        assertEquals("A", r.topArtists.get(0).name);
        assertEquals(2, r.topArtists.get(0).count);
        assertEquals(3, r.topArtists.size());
    }

    /* ---------------- bar bucketing ---------------- */

    @Test public void weeklyBarsBucketedByDay() {
        List<HistoryDiary.Entry> cur = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        // a fixed Wednesday
        cal.set(2026, Calendar.AUGUST, 5, 12, 0, 0);
        cur.add(entry(cal.getTimeInMillis(), 200, 300_000, "A", "a", "t"));
        // a fixed Sunday
        cal.set(2026, Calendar.AUGUST, 2, 12, 0, 0);
        cur.add(entry(cal.getTimeInMillis(), 200, 300_000, "A", "a", "t"));

        Recap.Result r = Recap.compute(cur, new ArrayList<>(), Recap.MODE_WEEK);
        assertEquals(7, r.bars.length);
        assertEquals(1, r.bars[0]);        // Sunday (index 0)
        assertEquals(1, r.bars[3]);        // Wednesday (Sun+3)
    }

    @Test public void yearlyBarsBucketedByMonth() {
        List<HistoryDiary.Entry> cur = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        cal.set(2026, Calendar.MARCH, 15, 12, 0, 0);
        cur.add(entry(cal.getTimeInMillis(), 200, 300_000, "A", "a", "t"));
        cal.set(2026, Calendar.DECEMBER, 1, 12, 0, 0);
        cur.add(entry(cal.getTimeInMillis(), 200, 300_000, "A", "a", "t"));

        Recap.Result r = Recap.compute(cur, new ArrayList<>(), Recap.MODE_YEAR);
        assertEquals(12, r.bars.length);
        assertEquals(1, r.bars[2]);   // March
        assertEquals(1, r.bars[11]);  // December
    }

    /* ---------------- window math ---------------- */

    @Test public void weeklyWindowIsMonToLastMonday() {
        // 2026-08-08 is a Saturday; completed week = Mon 2026-08-03 .. Mon 2026-08-10
        Calendar now = Calendar.getInstance();
        now.set(2026, Calendar.AUGUST, 8, 14, 30, 0);
        long[] w = Recap.windows(Recap.MODE_WEEK, now.getTimeInMillis());
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(w[0]);
        assertEquals(Calendar.MONDAY, start.get(Calendar.DAY_OF_WEEK));
        Calendar end = Calendar.getInstance();
        end.setTimeInMillis(w[1]);
        assertEquals(Calendar.MONDAY, end.get(Calendar.DAY_OF_WEEK));
        assertEquals(7L * 24 * 3600 * 1000, w[1] - w[0]);
        assertEquals(w[0] - (7L * 24 * 3600 * 1000), w[2]);  // prev start
    }

    @Test public void monthlyWindowIsPreviousCalendarMonth() {
        Calendar now = Calendar.getInstance();
        now.set(2026, Calendar.AUGUST, 15, 0, 0, 0);
        long[] w = Recap.windows(Recap.MODE_MONTH, now.getTimeInMillis());
        Calendar s = Calendar.getInstance(); s.setTimeInMillis(w[0]);
        assertEquals(Calendar.JULY, s.get(Calendar.MONTH));      // previous month
        assertEquals(1, s.get(Calendar.DAY_OF_MONTH));
        Calendar e = Calendar.getInstance(); e.setTimeInMillis(w[1]);
        assertEquals(Calendar.AUGUST, e.get(Calendar.MONTH));    // 1st of current month
        assertEquals(1, e.get(Calendar.DAY_OF_MONTH));
        Calendar ps = Calendar.getInstance(); ps.setTimeInMillis(w[2]);
        assertEquals(Calendar.JUNE, ps.get(Calendar.MONTH));     // prev-of-prev
    }

    /* ---------------- formatting + deltas ---------------- */

    @Test public void formatsHours() {
        assertEquals("42m", Recap.fmtHours(42 * 60, true));
        assertEquals("1h 05m", Recap.fmtHours(65 * 60, true));
        assertEquals("—", Recap.fmtHours(0, false));
    }

    @Test public void deltaArrows() {
        assertEquals("▲ +20 tracks", Recap.delta(50, 70, "tracks"));
        assertEquals("▼ −3 hours", Recap.delta(10, 7, "hours"));
        assertEquals("—", Recap.delta(5, 5, "x"));
    }
}
