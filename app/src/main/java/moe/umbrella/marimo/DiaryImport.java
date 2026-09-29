package moe.umbrella.marimo;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Import the merged listening diary back from shared storage.
 *
 *  The desktop can never write into the app's private filesDir (release build,
 *  no root), so `Download/marimo/` -- the same drop box {@link HistoryExport}
 *  writes to -- is the exchange point: the desktop leaves the merged copy
 *  there and this reads it back in.
 *
 *  The merge rule (both ends implement the same one, because a cycle must add
 *  nothing the second time):
 *
 *  - a line is identified by `(ts, artist, title)`; `ts` is epoch ms and a
 *    whole number, `artist`/`title` are strings. Unknown extra keys are
 *    tolerated and preserved.
 *  - the app's OWN files are read first, then the imported ones; the union of
 *    lines is kept and the first copy of a triple wins.
 *  - bucket by LOCAL calendar year: current year (or later) -> history.jsonl,
 *    each earlier year -> history.YYYY.jsonl.gz. Each bucket is sorted by `ts`,
 *    ties keeping arrival order.
 *  - a kept line is NEVER re-serialised: files are read as bytes, split on
 *    '\n', and the raw byte runs are written back out, so the player's own
 *    escaping and key order survive byte-for-byte.
 *  - anything it cannot read, decode, unzip, or parse -- a blank line, a
 *    missing/non-numeric `ts`, a non-string `artist`/`title` -- refuses the
 *    whole run with nothing changed. An unreadable file is not an empty file.
 *
 *  Safety rails: every file it replaces gets a hidden byte-copy backup beside
 *  it (the leading dot keeps it out of HistoryDiary's prune and rollover);
 *  writes go to a temp file in the same directory and are renamed into place;
 *  the file is re-read immediately before each replace; and nothing is ever
 *  deleted -- this tool only adds.
 *
 *  Concurrency: the merge runs under {@link HistoryDiary#lock()}, the same
 *  intrinsic monitor {@code HistoryDiary.log} takes, so a play logged by
 *  PlaybackService mid-import cannot be interleaved into a file being replaced. */
public class DiaryImport {

    /** the exchange point — same constant the export writes through */
    static final String RELATIVE_PATH = HistoryExport.RELATIVE_PATH;
    /** this year's live log */
    static final String LIVE = "history.jsonl";
    /** finished years */
    static final String ARCHIVE_RE = "history\\.\\d{4}\\.jsonl\\.gz";
    /** the backup stamp: hidden, and never a name the app's own housekeeping matches */
    static final String STAMP_FMT = "yyyyMMdd-HHmmss";

    /** one input file: its name and the bytes exactly as they were found */
    static final class Piece {
        final String name;
        final byte[] bytes;

        Piece(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    /** the identity of a diary line */
    static final class Key {
        final long ts;
        final String artist;
        final String title;

        Key(long ts, String artist, String title) {
            this.ts = ts;
            this.artist = artist;
            this.title = title;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return ts == k.ts && artist.equals(k.artist) && title.equals(k.title);
        }

        @Override public int hashCode() {
            return Objects.hash(ts, artist, title);
        }
    }

    /** what one reconcile did, for the toast */
    static final class Outcome {
        int totalLines;        // distinct triples in the merged diary
        int fromOwn;           // ... that the app already had
        int fromImport;        // ... arriving with this import
        int duplicates;        // copies dropped
        int filesWritten;      // files actually replaced
        final List<String> skipped = new ArrayList<>();   // changed under us mid-import

        String summary() {
            if (filesWritten == 0) {
                if (!skipped.isEmpty())
                    return "nothing written — " + skipped.size()
                            + (skipped.size() == 1 ? " file changed" : " files changed")
                            + " while importing, try again";
                if (totalLines == 0) return "nothing new — the diary is empty";
                return "nothing new — the diary already holds these " + totalLines + " lines";
            }
            StringBuilder sb = new StringBuilder("imported ").append(totalLines)
                    .append(" lines (").append(fromOwn).append(" already had them), ")
                    .append(filesWritten).append(filesWritten == 1 ? " file" : " files");
            if (!skipped.isEmpty())
                sb.append(" — ").append(skipped.size()).append(" left alone (changed mid-import)");
            return sb.toString();
        }
    }

    /** a refusal: nothing was read into the diary and nothing on disk changed */
    static class Refused extends Exception {
        Refused(String why) {
            super(why);
        }
    }

    /** a validated input file */
    private static final class Input {
        final String name;
        final byte[] bytes;        // exactly what was on disk / in the drop box
        final List<byte[]> lines;  // raw line runs
        final List<Key> keys;      // parsed triples, same order
        final boolean own;

        Input(String name, byte[] bytes, List<byte[]> lines, List<Key> keys, boolean own) {
            this.name = name;
            this.bytes = bytes;
            this.lines = lines;
            this.keys = keys;
            this.own = own;
        }
    }

    /** a file the merge wants to write */
    private static final class Plan {
        final String name;
        final List<byte[]> lines;

        Plan(String name, List<byte[]> lines) {
            this.name = name;
            this.lines = lines;
        }
    }

    /* ---------------- the Android-free merge ---------------- */

    /** Merge the drop-box pieces into the diary at {@code dir} and return what
     *  happened. {@link Refused} means nothing was written and nothing changed;
     *  an {@link IOException} means a write failed — the run may be partly
     *  applied, so it is never reported as a clean refusal. Runs under the
     *  diary's own lock. */
    static Outcome merge(File dir, List<Piece> imported, int currentYear, String stamp)
            throws Refused, IOException {
        synchronized (HistoryDiary.lock()) {
            Map<String, Input> own = readOwn(dir);
            List<Input> inputs = new ArrayList<>(own.values());
            for (Piece p : imported) inputs.add(validate(p.name, p.bytes, false));

            /* own files first, so the app's own bytes survive a duplicate */
            Map<Key, byte[]> kept = new LinkedHashMap<>();
            int fromOwn = 0, fromImport = 0, duplicates = 0;
            for (Input in : inputs) {
                for (int i = 0; i < in.lines.size(); i++) {
                    Key k = in.keys.get(i);
                    if (kept.containsKey(k)) {
                        duplicates++;
                        continue;
                    }
                    kept.put(k, in.lines.get(i));
                    if (in.own) fromOwn++; else fromImport++;
                }
            }

            /* a merge may never lose a line it could see */
            for (Input in : inputs) {
                if (distinct(in.keys) > kept.size())
                    throw new Refused("merging " + in.name + " would drop lines");
            }

            Outcome oc = new Outcome();
            oc.totalLines = kept.size();
            oc.fromOwn = fromOwn;
            oc.fromImport = fromImport;
            oc.duplicates = duplicates;

            for (Plan plan : bucket(kept, currentYear)) {
                Input before = own.get(plan.name);
                List<byte[]> had = before == null ? null : before.lines;
                if (sameLines(plan.lines, had)) continue;   /* nothing to change */

                File target = new File(dir, plan.name);
                byte[] fresh = target.isFile() ? readAll(target) : null;
                if (!sameBytes(fresh, before == null ? null : before.bytes)) {
                    /* the file moved under us — leave it alone rather than
                     * replace a version we never compared */
                    oc.skipped.add(plan.name);
                    continue;
                }
                if (before != null) writeBackup(dir, plan.name, before.bytes, stamp);
                byte[] out = join(plan.lines);
                /* archives are gzip, exactly like the ones the app's own
                 * rollover writes */
                if (plan.name.endsWith(".gz")) out = gzip(out);
                writeAtomic(dir, plan.name, out);
                oc.filesWritten++;
            }
            return oc;
        }
    }

    /** read every diary file in {@code dir}; a missing file is absent, an
     *  unreadable one is a refusal — never an empty file */
    private static Map<String, Input> readOwn(File dir) throws Refused {
        Map<String, Input> out = new LinkedHashMap<>();
        File[] all = dir.listFiles();
        if (all == null) throw new Refused("cannot list " + dir);
        List<File> found = new ArrayList<>();
        for (File f : all) if (isDiaryName(f.getName())) found.add(f);
        found.sort(Comparator.comparing(File::getName));
        for (File f : found) {
            if (!f.isFile()) throw new Refused(f.getName() + " is not a file");
            byte[] raw;
            try {
                raw = readAll(f);
            } catch (IOException e) {
                throw new Refused("cannot read " + f.getName() + " (" + e.getMessage() + ")");
            }
            out.put(f.getName(), validate(f.getName(), raw, true));
        }
        return out;
    }

    /** unzip if needed, check it decodes, and pull the triple out of every line */
    private static Input validate(String name, byte[] raw, boolean own) throws Refused {
        byte[] plain = raw;
        if (name.endsWith(".gz")) {
            try {
                plain = gunzip(raw);
            } catch (IOException e) {
                throw new Refused(name + " is not readable gzip (" + e.getMessage() + ")");
            }
        }
        List<byte[]> lines = splitLines(plain);
        List<Key> keys = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            byte[] line = lines.get(i);
            if (line.length == 0) throw new Refused(name + " has a blank line at " + (i + 1));
            String s;
            try {
                s = decode(line);
            } catch (CharacterCodingException e) {
                throw new Refused(name + " line " + (i + 1) + " is not valid UTF-8");
            }
            Key k = keyOf(s);
            if (k == null)
                throw new Refused(name + " line " + (i + 1)
                        + " is not a diary line (needs a whole-number ts and string artist/title)");
            keys.add(k);
        }
        return new Input(name, raw, lines, keys, own);
    }

    /** the union, bucketed by local year and sorted by ts (ties keep arrival order) */
    private static List<Plan> bucket(Map<Key, byte[]> kept, int currentYear) {
        Map<String, List<Map.Entry<Key, byte[]>>> buckets = new LinkedHashMap<>();
        for (Map.Entry<Key, byte[]> e : kept.entrySet()) {
            int year = yearOf(e.getKey().ts);
            String name = year >= currentYear ? LIVE : archiveName(year);
            List<Map.Entry<Key, byte[]>> bucket = buckets.get(name);
            if (bucket == null) buckets.put(name, bucket = new ArrayList<>());
            bucket.add(e);
        }
        List<Plan> plans = new ArrayList<>();
        for (Map.Entry<String, List<Map.Entry<Key, byte[]>>> b : buckets.entrySet()) {
            List<Map.Entry<Key, byte[]>> bucket = b.getValue();
            bucket.sort(Comparator.comparingLong(en -> en.getKey().ts));   /* stable */
            List<byte[]> lines = new ArrayList<>(bucket.size());
            for (Map.Entry<Key, byte[]> en : bucket) lines.add(en.getValue());
            plans.add(new Plan(b.getKey(), lines));
        }
        /* archives first, then the live log — deterministic, and the file
         * HistoryDiary appends to is touched last */
        plans.sort((a, b) -> a.name.equals(LIVE) ? 1
                : b.name.equals(LIVE) ? -1 : a.name.compareTo(b.name));
        return plans;
    }

    static String archiveName(int year) {
        return "history." + year + ".jsonl.gz";
    }

    static boolean isDiaryName(String name) {
        return name != null && (LIVE.equals(name) || name.matches(ARCHIVE_RE));
    }

    static int yearOf(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.YEAR);
    }

    private static int distinct(List<Key> keys) {
        return new java.util.HashSet<>(keys).size();
    }

    /* ---------------- bytes ---------------- */

    /** the raw byte runs of a diary file, split on '\n'. A trailing newline is
     *  a terminator, not a blank line; any other empty run is a blank line and
     *  {@link #validate} refuses it. Splitting on '\n' can never cut a UTF-8
     *  character in half — 0x0A is not a continuation byte. */
    static List<byte[]> splitLines(byte[] b) {
        List<byte[]> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < b.length; i++) {
            if (b[i] == '\n') {
                out.add(Arrays.copyOfRange(b, start, i));
                start = i + 1;
            }
        }
        if (start < b.length) out.add(Arrays.copyOfRange(b, start, b.length));
        return out;
    }

    /** the kept lines joined back up, each terminated — the raw runs go out
     *  exactly as they came in */
    static byte[] join(List<byte[]> lines) {
        int n = 0;
        for (byte[] line : lines) n += line.length + 1;
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] line : lines) {
            System.arraycopy(line, 0, out, at, line.length);
            at += line.length;
            out[at++] = '\n';
        }
        return out;
    }

    /** strict UTF-8: bytes we can't decode are a refusal, not something to
     *  silently reinterpret */
    static String decode(byte[] b) throws CharacterCodingException {
        CharsetDecoder d = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return d.decode(ByteBuffer.wrap(b)).toString();
    }

    static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HistoryExport.copyStream(in, out);
        return out.toByteArray();
    }

    static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }

    static byte[] gunzip(byte[] gz) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return readAll(in);
        }
    }

    /* ---------------- writing ---------------- */

    /** a hidden byte-copy of what is about to be replaced. The leading dot is
     *  load-bearing: HistoryDiary's prune matches "history.<Y>.jsonl.gz" and its
     *  rollover matches exactly "history.jsonl", so neither eats a backup.
     *  Never overwrites an older backup of the same name. */
    static File writeBackup(File dir, String name, byte[] bytes, String stamp) throws IOException {
        File b = new File(dir, "." + name + ".bak-" + stamp);
        for (int n = 2; b.exists(); n++) b = new File(dir, "." + name + ".bak-" + stamp + "-" + n);
        try (FileOutputStream out = new FileOutputStream(b)) {
            out.write(bytes);
        }
        return b;
    }

    /** temp file in the same directory, fsynced, then renamed into place — a
     *  reader opening the diary mid-import sees either the old file or the new
     *  one, and a power loss cannot leave the new one visible with its data
     *  still unwritten (the rename is the only step that publishes it) */
    static void writeAtomic(File dir, String name, byte[] bytes) throws IOException {
        File target = new File(dir, name);
        File tmp = new File(dir, "." + name + ".tmp-" + System.nanoTime());
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                /* flush the bytes to the device before the rename makes them
                 * visible — same durability story as the desktop side */
                out.getFD().sync();
            }
            if (!tmp.renameTo(target))
                throw new IOException("rename " + tmp.getName() + " into place failed");
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();     /* our own half-written temp — never diary data */
            throw e;
        }
    }

    static boolean sameBytes(byte[] a, byte[] b) {
        if (a == null || b == null) return a == b;
        return Arrays.equals(a, b);
    }

    static boolean sameLines(List<byte[]> a, List<byte[]> b) {
        if (a == null || b == null) return a == b;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (!Arrays.equals(a.get(i), b.get(i))) return false;
        return true;
    }

    /* ---------------- line parsing ---------------- */

    /** the triple that identifies a diary line, or null when the line isn't one
     *  (no whole-number ts, or artist/title not strings) */
    static Key keyOf(String line) {
        if (line == null) return null;
        Scan sc = new Scan(line);
        sc.ws();
        if (!sc.take('{')) return null;
        Long ts = null;
        String artist = null, title = null;
        sc.ws();
        if (sc.take('}')) return null;                     /* {} identifies nothing */
        while (true) {
            sc.ws();
            String key = sc.string();
            if (key == null) return null;
            sc.ws();
            if (!sc.take(':')) return null;
            sc.ws();
            if (sc.peek() == '"') {
                String v = sc.string();
                if (v == null) return null;
                if ("artist".equals(key)) artist = v;
                else if ("title".equals(key)) title = v;
            } else if ("ts".equals(key)) {
                Long l = sc.wholeNumber();
                if (l == null) return null;                /* not a whole number */
                ts = l;
            } else if (!sc.skipValue()) {
                return null;
            }
            sc.ws();
            if (sc.take(',')) continue;
            if (sc.take('}')) break;
            return null;
        }
        sc.ws();
        if (!sc.atEnd()) return null;                      /* trailing junk */
        if (ts == null || artist == null || title == null) return null;
        return new Key(ts, artist, title);
    }

    /** a scanner for one line — just enough JSON to pull out the triple.
     *  Unknown keys are skipped; the raw bytes are what gets written back, so
     *  nothing here has to reproduce the line. */
    private static final class Scan {
        private final String s;
        private int i;

        Scan(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return;
                i++;
            }
        }

        boolean take(char c) {
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        /** a JSON string at the cursor, or null if there isn't a valid one */
        String string() {
            if (i >= s.length() || s.charAt(i) != '"') return null;
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) return null;
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) return null;
                        int cp;
                        try {
                            cp = Integer.parseInt(s.substring(i, i + 4), 16);
                        } catch (NumberFormatException x) {
                            return null;
                        }
                        sb.append((char) cp);
                        i += 4;
                        break;
                    default:
                        return null;
                }
            }
            return null;
        }

        /** a whole number at the cursor, or null — a fraction, an exponent or a
         *  quoted number all count as "not a whole number" */
        Long wholeNumber() {
            int start = i;
            if (i < s.length() && s.charAt(i) == '-') i++;
            int d0 = i;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
            if (i == d0) {
                i = start;
                return null;
            }
            if (i < s.length()) {
                char c = s.charAt(i);
                if (c == '.' || c == 'e' || c == 'E') {
                    i = start;
                    return null;
                }
            }
            try {
                return Long.parseLong(s.substring(start, i));
            } catch (NumberFormatException x) {
                i = start;
                return null;
            }
        }

        /** step over one value of any shape — unknown keys can hold anything */
        boolean skipValue() {
            if (i >= s.length()) return false;
            char c = s.charAt(i);
            if (c == '"') return string() != null;
            if (c == '{' || c == '[') {
                char close = c == '{' ? '}' : ']';
                int depth = 0;
                while (i < s.length()) {
                    char d = s.charAt(i);
                    if (d == '"') {
                        if (string() == null) return false;
                        continue;
                    }
                    if (d == c) depth++;
                    else if (d == close) {
                        depth--;
                        i++;
                        if (depth == 0) return true;
                        continue;
                    }
                    i++;
                }
                return false;
            }
            int start = i;
            while (i < s.length() && s.charAt(i) != ',' && s.charAt(i) != '}') i++;
            return i > start;
        }
    }

    /* ---------------- the Android side ---------------- */

    /** read the drop box, merge it into the diary, and return the line the app
     *  shows. Never throws. */
    public static String importMerged(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            return "import needs android 10 or newer";
        List<Piece> pieces;
        try {
            pieces = dropBox(ctx.getContentResolver());
        } catch (Exception e) {
            Log.e("marimo", "diary import: drop box: " + e);
            return "import refused — " + e.getMessage() + " (nothing changed)";
        }
        if (pieces.isEmpty()) return "nothing to import yet";
        try {
            String stamp = new SimpleDateFormat(STAMP_FMT, Locale.US)
                    .format(new Date());
            return merge(ctx.getFilesDir(), pieces,
                    yearOf(System.currentTimeMillis()), stamp).summary();
        } catch (Refused r) {
            return "import refused — " + r.getMessage() + " (nothing changed)";
        } catch (Exception e) {
            Log.e("marimo", "diary import: " + e);
            return "import failed — see log";
        }
    }

    /** the diary copies sitting in Download/marimo/ — the same permission-free
     *  MediaStore read the export writes through */
    @TargetApi(Build.VERSION_CODES.Q)
    static List<Piece> dropBox(ContentResolver cr) throws Exception {
        List<Piece> out = new ArrayList<>();
        String[] cols = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
        };
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        try (Cursor c = cr.query(collection, cols,
                MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[]{RELATIVE_PATH}, null)) {
            if (c == null) throw new IOException("no cursor for " + RELATIVE_PATH);
            while (c.moveToNext()) {
                String name = c.getString(1);
                if (!isDiaryName(name)) continue;
                byte[] raw;
                try (InputStream in = cr.openInputStream(
                        ContentUris.withAppendedId(collection, c.getLong(0)))) {
                    if (in == null) throw new IOException("no stream for " + name);
                    raw = readAll(in);
                }
                out.add(new Piece(name, raw));
            }
        }
        out.sort(Comparator.comparing(p -> p.name));
        return out;
    }
}
