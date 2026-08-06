package moe.umbrella.marimo;

import org.junit.Test;

import static org.junit.Assert.*;

/** JVM tests for the HistoryDiary line serialization: JSON shape, escaping,
 *  and corruption tolerance. (The write/rollover path needs a real filesDir,
 *  so it's covered on-device; the pure line/parse round-trip lives here.) */
public class HistoryDiaryTest {

    private static Track track(String title, String artist, String album, int durMs) {
        Track t = new Track("content://x", "file.flac");
        t.title = title;
        t.artist = artist;
        t.album = album;
        t.durationMs = durMs;
        return t;
    }

    @Test public void lineHasExpectedShape() {
        String ln = HistoryDiary.line(track("Love For You", "Utsu-P", "TRAUMATIC", 189000),
                178_000L, 1_700_000_000_000L);
        assertTrue(ln.startsWith("{\"ts\":"));
        assertTrue(ln.contains("\"artist\":\"Utsu-P\""));
        assertTrue(ln.contains("\"album\":\"TRAUMATIC\""));
        assertTrue(ln.contains("\"title\":\"Love For You\""));
        assertTrue(ln.contains("\"sec\":178"));
        assertTrue(ln.contains("\"dur\":189000"));
        assertTrue(ln.endsWith("}\n"));
    }

    @Test public void emptyTitleFallsBackToName() {
        Track t = new Track("content://x", "silly-name.flac");
        String ln = HistoryDiary.line(t, 0, 1L);
        assertTrue(ln.contains("\"title\":\"silly-name.flac\""));
        assertTrue(ln.contains("\"artist\":\"\""));
    }

    @Test public void roundTrips() {
        Track t = track("a \"quote\" & \\backslash\n", "ærtist", "album", 250_000);
        String ln = HistoryDiary.line(t, 90_000L, 1_700_000_000_999L);
        HistoryDiary.Entry e = HistoryDiary.parse(ln);
        assertNotNull(e);
        assertEquals(1_700_000_000_999L, e.ts);
        assertEquals(90, e.sec);
        assertEquals(250_000, e.dur);
        assertEquals("ærtist", e.artist);
        assertEquals("album", e.album);
        assertEquals("a \"quote\" & \\backslash\n", e.title);
    }

    @Test public void corruptLinesYieldNull() {
        assertNull(HistoryDiary.parse(""));
        assertNull(HistoryDiary.parse("garbage not json"));
        assertNull(HistoryDiary.parse("{\"ts\": notanumber}"));
        assertNull(HistoryDiary.parse("{\"sec\":5}"));        // no ts = not a diary line
    }

    @Test public void partialLineStillGivesWhatItHas() {
        // a crash mid-write leaves fields but maybe not all — parse what exists
        HistoryDiary.Entry e = HistoryDiary.parse(
                "{\"ts\":1700000000000,\"artist\":\"Utsu-P\"}");
        assertNotNull(e);
        assertEquals(1700000000000L, e.ts);
        assertEquals("Utsu-P", e.artist);
        assertEquals(0, e.sec);          // missing sec defaults to 0 (won't count as play)
    }

    @Test public void forWindowReadsAndFiltersFile() throws Exception {
        java.io.File dir = java.io.File.createTempFile("diary", "dir");
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        HistoryDiary.setDir(dir.getAbsolutePath());

        // one week: Mon 2026-03-02 .. Sun 2026-03-08
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(2026, java.util.Calendar.MARCH, 3, 12, 0, 0);   // Tue in-week
        long inTs = cal.getTimeInMillis();
        cal.set(2026, java.util.Calendar.MARCH, 10, 12, 0, 0);  // Tue next week
        long outTs = cal.getTimeInMillis();

        java.io.File f = new java.io.File(dir, "history.jsonl");
        Track t = new Track("content://x", "f.flac");
        t.artist = "A"; t.album = "B"; t.title = "C"; t.durationMs = 200_000;
        try (java.io.FileWriter w = new java.io.FileWriter(f,
                java.nio.charset.StandardCharsets.UTF_8)) {
            w.write(HistoryDiary.line(t, 150_000, inTs));
            w.write(HistoryDiary.line(t, 150_000, inTs + 60_000));
            w.write(HistoryDiary.line(t, 150_000, outTs));   // outside window
            w.write("not valid json\n");                      // corrupt line
        }

        java.util.List<HistoryDiary.Entry> got =
                HistoryDiary.forWindow(inTs, outTs);
        assertEquals(2, got.size());
        for (HistoryDiary.Entry e : got) {
            assertTrue(e.ts >= inTs && e.ts < outTs);
        }
    }
}