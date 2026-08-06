package moe.umbrella.marimo;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Local listening diary — the data behind the recap feature.
 *
 *  One newline-delimited JSON line per FINISHED listen (logged at the same
 *  point the app scrobbles — i.e. on each track transition). Everything is
 *  app-private (context.filesDir), no network, no privacy leak.
 *
 *  Line schema: {"ts":<epoch_ms>,"artist":"...","album":"...","title":"...",
 *                "sec":<seconds_heard>,"dur":<duration_ms>}
 *  - `sec` is how much of the track was actually heard (raw truth — used for
 *    the diary, and later for skip-rate / replay / most-skipped stats).
 *  - `dur` is the known duration so the "counts as a play" rule (heard > 30s
 *    AND > 50% of the track) can be applied at aggregation time. The design
 *    spec originally omitted `dur` — added here deliberately.
 *
 *  Layout: current year is history.jsonl (append-only). When the calendar year
 *  rolls over, the finished year is gzipped aside as history.YYYY.jsonl.gz and
 *  a fresh history.jsonl starts. Archived files older than ~18 months are
 *  pruned as they're written. Reads tolerate corrupt/partial lines (skip, heal). */
public class HistoryDiary {

    /** one finished listen, parsed from a diary line */
    public static class Entry {
        public long ts;          // epoch ms
        public long sec;         // seconds heard
        public long dur;         // duration ms (0 = unknown)
        public String artist = "";
        public String album = "";
        public String title = "";
    }

    private static volatile String dir;
    private static final Object LOCK = new Object();

    public static void configure(Context ctx) {
        setDir(ctx.getFilesDir().getAbsolutePath());
    }

    /** set the diary directory directly — package-visible for JVM tests */
    static void setDir(String path) {
        dir = path;
    }

    private static File currentFile() {
        return new File(dir, "history.jsonl");
    }

    private static File archiveFile(int year) {
        return new File(dir, "history." + year + ".jsonl.gz");
    }

    /** append a finished listen to the diary (best-effort, never throws) */
    public static void log(Track t, long playedMs) {
        if (dir == null || t == null) return;
        try {
            long ts = System.currentTimeMillis();
            String line = line(t, playedMs, ts);
            synchronized (LOCK) {
                File f = currentFile();
                int year = yearOf(ts);
                /* roll over when the file on disk belongs to a different year
                 * (handles both live rollover and cold start after Jan 1) */
                long lm = (f.exists() && f.length() > 0) ? f.lastModified() : -1;
                int fileYear = lm > 0 ? yearOf(lm) : year;
                if (fileYear != year) {
                    if (lm > 0) gzip(f, archiveFile(fileYear));
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
                append(f, line);
                prune(year);
            }
        } catch (Exception e) {
            android.util.Log.e("marimo", "diary log: " + e);
        }
    }

    /** build one JSON line (manual escaping — no JSONObject dependency) */
    static String line(Track t, long playedMs, long ts) {
        String title = t.title.isEmpty() ? t.name : t.title;
        return "{\"ts\":" + ts
                + ",\"artist\":" + q(t.artist)
                + ",\"album\":" + q(t.album)
                + ",\"title\":" + q(title)
                + ",\"sec\":" + (playedMs / 1000)
                + ",\"dur\":" + (t.durationMs > 0 ? t.durationMs : 0)
                + "}\n";
    }

    private static String q(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static void append(File f, String line) throws Exception {
        try (FileOutputStream os = new FileOutputStream(f, true)) {
            os.write(line.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** gzip a whole file to the archive path */
    private static void gzip(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             GZIPOutputStream out = new GZIPOutputStream(new FileOutputStream(dst))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** drop archived years older than the previous one (keeps a full
     *  previous year for the vs-last-year report, i.e. ~15-27 months) */
    private static void prune(int currentYear) {
        File[] files = new File(dir).listFiles((d, name) ->
                name.matches("history\\.\\d{4}\\.jsonl\\.gz"));
        if (files == null) return;
        for (File f : files) {
            try {
                String y = f.getName().substring("history.".length(),
                        "history.".length() + 4);
                int year = Integer.parseInt(y);
                if (year < currentYear - 1) //noinspection ResultOfMethodCallIgnored
                    f.delete();
            } catch (Exception ignored) { }
        }
    }

    /* ---------------- reading ---------------- */

    /** all entries with ts in [startMs, endMs), across the current year and
     *  the prior archived year (so Jan-crossing windows still resolve) */
    public static List<Entry> forWindow(long startMs, long endMs) {
        List<Entry> out = new ArrayList<>();
        if (dir == null) return out;
        int startYear = yearOf(startMs);
        for (int y = startYear; y <= yearOf(endMs - 1); y++) {
            File src = y == yearOf(System.currentTimeMillis())
                    ? currentFile() : archiveFile(y);
            readFile(src, out);
        }
        List<Entry> filtered = new ArrayList<>();
        for (Entry e : out)
            if (e.ts >= startMs && e.ts < endMs) filtered.add(e);
        return filtered;
    }

    /** read every line from a file (gz if archived), skipping corrupt lines */
    private static void readFile(File f, List<Entry> out) {
        if (f == null || !f.exists()) return;
        try {
            java.io.InputStream is = f.getName().endsWith(".gz")
                    ? new GZIPInputStream(new FileInputStream(f))
                    : new FileInputStream(f);
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8));
            String ln;
            while ((ln = r.readLine()) != null) {
                Entry e = parse(ln);
                if (e != null) out.add(e);
            }
            r.close();
        } catch (Exception ignored) { }
    }

    /** parse one line; return null if it isn't a valid diary entry */
    static Entry parse(String ln) {
        try {
            ln = ln.trim();
            if (ln.isEmpty() || !ln.startsWith("{")) return null;
            Entry e = new Entry();
            e.ts = longVal(ln, "ts", -1);
            e.sec = longVal(ln, "sec", 0);
            e.dur = longVal(ln, "dur", 0);
            e.artist = strVal(ln, "artist");
            e.album = strVal(ln, "album");
            e.title = strVal(ln, "title");
            if (e.ts < 0) return null;
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    private static long longVal(String ln, String key, long dflt) {
        try {
            String k = "\"" + key + "\":";
            int i = ln.indexOf(k);
            if (i < 0) return dflt;
            int j = i + k.length();
            while (j < ln.length() && (ln.charAt(j) == ' ')) j++;
            int end = j;
            while (end < ln.length() && Character.isDigit(ln.charAt(end))) end++;
            return Long.parseLong(ln.substring(j, end));
        } catch (Exception e) { return dflt; }
    }

    private static String strVal(String ln, String key) {
        try {
            String k = "\"" + key + "\":";
            int i = ln.indexOf(k);
            if (i < 0) return "";
            int j = i + k.length();
            while (j < ln.length() && ln.charAt(j) != '"') j++;
            if (j >= ln.length()) return "";
            j++;  // opening quote
            StringBuilder sb = new StringBuilder();
            boolean esc = false;
            for (; j < ln.length(); j++) {
                char c = ln.charAt(j);
                if (esc) {
                    switch (c) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        default: sb.append(c);
                    }
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private static int yearOf(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.YEAR);
    }
}
