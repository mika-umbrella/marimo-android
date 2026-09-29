package moe.umbrella.marimo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** JVM tests for the diary import's Android-free half: the merge rule (dedup
 *  on the triple, byte-preservation of carried lines, year bucketing, the
 *  backup, idempotence) and the refusals. The MediaStore read and the rename
 *  on a real filesystem need a device. */
public class DiaryImportTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final int YEAR = 2026;
    private static final String STAMP = "20260929-132000";
    private static final byte[] NL = {'\n'};

    /* ---------------- the pinned canonical case ----------------
     * One case, two implementations: the desktop merger (tools/marimo-sync,
     * section 11 of tests/diary_smoke.py) asserts these same three digests, so
     * if either rule drifts its own suite goes red instead of the two recaps
     * silently forking. The clock is pinned to 2026 and every ts below is a
     * literal integer, so nothing here depends on the machine's timezone. */

    /** own filesDir, in this order, each line terminated by exactly one 0x0A */
    private static final String C_OWN_FIRST =
            "{\"ts\":1772712000000,\"artist\":\"Alpha\",\"album\":\"Canon\",\"title\":\"first\",\"sec\":100,\"dur\":259853}";
    private static final String C_OWN_FIRST_DUP =
            "{\"ts\":1772712000000,\"artist\":\"Alpha\",\"album\":\"Canon\",\"title\":\"first\",\"sec\":0,\"dur\":259853}";
    private static final String C_OWN_PREV =
            "{\"ts\":1762084800000,\"artist\":\"Bravo\",\"album\":\"Canon\",\"title\":\"previous year\",\"sec\":250,\"dur\":0}";
    /** the escaped line, built from this hex rather than a Java string literal so
     *  an escaping slip cannot quietly turn the fixture into a different case.
     *
     *  THIS HEX AND THE THREE PINNED DIGESTS BELOW ARE A PAIR — they are relayed
     *  from the desktop merger (tools/marimo-sync, tests/diary_smoke.py section
     *  11), which asserts the same three digests. If this test fails, one side's
     *  merge rule has moved, and that is the whole point of the pin.
     *
     *  Before blaming the rule, check the fixture: a typo in this hex is
     *  indistinguishable from a rule divergence until you localise the byte, and
     *  the localisation is byte-for-byte work (which line, which offset, ours
     *  versus theirs) — not a judgement call. That happened once already: the
     *  relayed hex spelled the title "escpaped", the pinned digest was computed
     *  from "escaped", and the only difference between the two outputs was that
     *  one extra byte. The test was right to fail until it was removed. */
    private static final byte[] C_OWN_ESCAPED = fromHex(
            "7b227473223a313737353034343830303030302c22617274697374223a22715c22625c5c5c6e63e9acb1222c22616c62756d223a2243616e6f6e222c227469746c65223a2265736361706564222c22736563223a34322c22647572223a3235393835337d");

    /** the drop box: history.jsonl (three lines) plus history.2025.jsonl.gz (one) */
    private static final String C_DROP_FIRST =
            "{\"ts\":1772712000000,\"artist\":\"Alpha\",\"album\":\"Canon\",\"title\":\"first\",\"sec\":161,\"dur\":259853}";
    private static final String C_DROP_FRESH =
            "{\"ts\":1778068800000,\"artist\":\"Charlie\",\"album\":\"Canon\",\"title\":\"fresh\",\"sec\":200,\"dur\":240000}";
    private static final String C_DROP_PRUNED =
            "{\"ts\":1717675200000,\"artist\":\"Delta\",\"album\":\"Canon\",\"title\":\"pruned\",\"sec\":100,\"dur\":200000}";
    private static final String C_DROP_ARCHIVED =
            "{\"ts\":1754654400000,\"artist\":\"Echo\",\"album\":\"Canon\",\"title\":\"archived\",\"sec\":30,\"dur\":0}";

    /** sha256 of history.jsonl exactly as written; of the archives AFTER
     *  decompression — gzip framing differs between java.util.zip and zlib, and
     *  that must never be mistaken for a difference in the rule */
    private static final String C_LIVE_SHA =
            "bdb1519f5ac4a3d3f24d2b46d4bde44870fa0ce5315a00a779ead3cb6a67074f";
    private static final String C_Y2025_SHA =
            "9bdf8a128ca670bb07a55a7be8c1a885caf9286cbe4544cb15738e04e7506f05";
    private static final String C_Y2024_SHA =
            "30dd158bbd57761c79f72a38b4bf9a13aee545dac0a2ee1cce0c90f01e8e3531";

    /* ---------------- helpers ---------------- */

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** one fixture line, terminated by exactly one 0x0A */
    private static byte[] nl(String line) {
        return concat(b(line), NL);
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    private static byte[] fromHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private static void writeBytes(File dir, String name, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            out.write(bytes);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        java.security.MessageDigest md =
                java.security.MessageDigest.getInstance("SHA-256");
        return String.format("%064x", new java.math.BigInteger(1, md.digest(bytes)));
    }

    /** an archive's content after decompression — the digest is pinned over
     *  this, never over the .gz bytes */
    private static byte[] decompressed(File f) throws Exception {
        byte[] raw = DiaryImport.readAll(f);
        return f.getName().endsWith(".gz") ? DiaryImport.gunzip(raw) : raw;
    }

    /** epoch ms at noon on the given day — noon keeps the test timezone-proof,
     *  so no zone shift can move it into another year */
    private static long at(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.set(year, month - 1, day, 12, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String line(long ts, String artist, String title) {
        return "{\"ts\":" + ts + ",\"artist\":\"" + artist + "\",\"album\":\"\","
                + "\"title\":\"" + title + "\",\"sec\":180,\"dur\":200000}\n";
    }

    /** one raw line run as it sits inside a file — terminator stripped, the
     *  shape {@link DiaryImport#splitLines} hands back */
    private static byte[] lineRun(String line) {
        return DiaryImport.splitLines(b(line)).get(0);
    }

    private static void write(File dir, String name, String body) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            out.write(b(body));
        }
    }

    private static void writeGz(File dir, String name, String body) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            out.write(DiaryImport.gzip(b(body)));
        }
    }

    private static DiaryImport.Piece piece(String name, String body) {
        return new DiaryImport.Piece(name, b(body));
    }

    private static DiaryImport.Piece gzPiece(String name, String body) throws Exception {
        return new DiaryImport.Piece(name, DiaryImport.gzip(b(body)));
    }

    private static List<DiaryImport.Piece> pieces(DiaryImport.Piece... p) {
        return Arrays.asList(p);
    }

    /** every file in the dir, name -> bytes */
    private static Map<String, byte[]> snapshot(File dir) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        File[] all = dir.listFiles();
        if (all == null) return out;
        Arrays.sort(all);
        for (File f : all) out.put(f.getName(), DiaryImport.readAll(f));
        return out;
    }

    private static void assertUnchanged(File dir, Map<String, byte[]> before) throws Exception {
        Map<String, byte[]> after = snapshot(dir);
        assertEquals("file set changed: " + after.keySet(), before.keySet(), after.keySet());
        for (Map.Entry<String, byte[]> e : before.entrySet())
            assertArrayEquals("bytes of " + e.getKey() + " changed",
                    e.getValue(), after.get(e.getKey()));
    }

    /** decompressed raw line runs of a diary file */
    private static List<byte[]> rawLines(File f) throws Exception {
        byte[] raw = DiaryImport.readAll(f);
        return DiaryImport.splitLines(f.getName().endsWith(".gz")
                ? DiaryImport.gunzip(raw) : raw);
    }

    private static List<String> lines(File f) throws Exception {
        List<String> out = new ArrayList<>();
        for (byte[] l : rawLines(f)) out.add(new String(l, StandardCharsets.UTF_8));
        return out;
    }

    /* ---------------- the merge rule ---------------- */

    @Test public void dedupsOnTheTripleAndKeepsTheOwnersCopy() throws Exception {
        File dir = tmp.newFolder();
        String a = line(at(2026, 2, 1), "Utsu-P", "Love For You");
        String bb = line(at(2026, 3, 1), "Kikuo", "Sunshine");
        String c = line(at(2026, 4, 1), "PinocchioP", "God-ish");
        write(dir, "history.jsonl", a + bb);
        write(dir, "notes.txt", "not a diary\n");

        /* the same triple written differently (\u002d is '-'), plus one new line */
        String aAgain = "{\"sec\":9,\"ts\":" + at(2026, 2, 1) + ",\"extra\":[1,2],"
                + "\"title\":\"Love For You\",\"artist\":\"Utsu\\u002dP\"}\n";

        DiaryImport.Outcome oc = DiaryImport.merge(dir, pieces(
                piece("history.jsonl", aAgain + c)), YEAR, STAMP);

        assertEquals(3, oc.totalLines);
        assertEquals(2, oc.fromOwn);
        assertEquals(1, oc.fromImport);
        assertEquals(1, oc.duplicates);
        assertEquals(1, oc.filesWritten);

        List<byte[]> got = rawLines(new File(dir, "history.jsonl"));
        assertEquals(3, got.size());
        assertArrayEquals("the owner's own bytes must survive a duplicate",
                lineRun(a), got.get(0));
        assertArrayEquals(lineRun(bb), got.get(1));
        assertArrayEquals(lineRun(c), got.get(2));
        assertEquals(Arrays.asList("not a diary"), lines(new File(dir, "notes.txt")));
    }

    @Test public void carriedLinesKeepTheirExactBytes() throws Exception {
        File dir = tmp.newFolder();
        /* a line the app could have written: odd key order, escapes, extra keys */
        String odd = "{\"title\":\"a\\u00e9\\\"q\\\"\",\"ts\":" + at(2026, 5, 5)
                + ",\"artist\":\"b\\\\c\",\"sec\":1,\"note\":{\"k\":[1,2]}}\n";
        String other = line(at(2026, 5, 9), "x", "y");
        write(dir, "history.jsonl", odd + other);

        DiaryImport.Outcome oc = DiaryImport.merge(dir, pieces(
                piece("history.jsonl", line(at(2026, 5, 1), "z", "earlier"))), YEAR, STAMP);
        assertEquals(3, oc.fromOwn + oc.fromImport);

        List<byte[]> got = rawLines(new File(dir, "history.jsonl"));
        assertEquals(3, got.size());
        assertArrayEquals("a carried line goes back out byte-for-byte",
                lineRun(odd), got.get(1));
        /* the escape is still a literal backslash-u in the file, not an
         * escaped quote or a re-encoded character */
        String second = new String(got.get(1), StandardCharsets.UTF_8);
        assertTrue(second.contains("\\u00e9"));
        assertTrue(second.startsWith("{\"title\":\"a\\u00e9\\\"q\\\"\""));
    }

    @Test public void bucketsByLocalYearAndSortsTheLiveFile() throws Exception {
        File dir = tmp.newFolder();
        long tie = at(2026, 7, 15);
        write(dir, "history.jsonl",
                line(tie, "A", "ownTie") + line(at(2026, 3, 10), "A", "first"));

        DiaryImport.Outcome oc = DiaryImport.merge(dir, pieces(
                piece("history.jsonl",
                        line(at(2026, 1, 5), "B", "jan")
                                + line(at(2025, 11, 2), "B", "lastYear")
                                + line(at(2024, 6, 6), "B", "oldYear")
                                + line(tie, "B", "importTie"))), YEAR, STAMP);

        /* archives for the earlier years, the current year staying live */
        assertTrue(new File(dir, "history.2025.jsonl.gz").isFile());
        assertTrue(new File(dir, "history.2024.jsonl.gz").isFile());
        assertFalse(new File(dir, "history.2026.jsonl.gz").exists());
        assertEquals(3, oc.filesWritten);
        assertEquals(6, oc.totalLines);
        assertEquals(4, oc.fromImport);

        List<String> live = lines(new File(dir, "history.jsonl"));
        assertEquals(4, live.size());
        long prev = Long.MIN_VALUE;
        for (String l : live) {
            DiaryImport.Key k = DiaryImport.keyOf(l);
            assertNotNull(l, k);
            assertTrue("not sorted by ts: " + l, k.ts >= prev);
            prev = k.ts;
        }
        assertTrue(live.get(0).contains("\"jan\""));
        assertTrue(live.get(1).contains("\"first\""));
        /* equal ts: the owner's line arrived first and keeps that place */
        assertTrue(live.get(2).contains("\"ownTie\""));
        assertTrue(live.get(3).contains("\"importTie\""));

        List<String> y25 = lines(new File(dir, "history.2025.jsonl.gz"));
        assertEquals(1, y25.size());
        assertTrue(y25.get(0).contains("\"lastYear\""));
        assertEquals(1, lines(new File(dir, "history.2024.jsonl.gz")).size());
    }

    @Test public void archivesRoundTripThroughGzip() throws Exception {
        File dir = tmp.newFolder();
        write(dir, "history.jsonl", line(at(2026, 8, 8), "A", "live"));

        DiaryImport.Outcome oc = DiaryImport.merge(dir,
                pieces(gzPiece("history.2025.jsonl.gz",
                        line(at(2025, 2, 2), "B", "one") + line(at(2025, 9, 9), "B", "two"))),
                YEAR, STAMP);
        assertEquals(2, oc.fromImport);

        File archive = new File(dir, "history.2025.jsonl.gz");
        byte[] onDisk = DiaryImport.readAll(archive);
        assertArrayEquals("not a gzip file", new byte[]{(byte) 0x1f, (byte) 0x8b},
                new byte[]{onDisk[0], onDisk[1]});
        assertArrayEquals(DiaryImport.join(rawLines(archive)),
                DiaryImport.gunzip(onDisk));
        List<String> back = lines(archive);
        assertEquals(2, back.size());
        assertTrue(back.get(0).contains("\"one\""));
        assertTrue(back.get(1).contains("\"two\""));
    }

    @Test public void readsAPieceWithNoTrailingNewline() throws Exception {
        File dir = tmp.newFolder();
        write(dir, "history.jsonl", line(at(2026, 2, 2), "A", "a"));
        DiaryImport.Outcome oc = DiaryImport.merge(dir, pieces(
                piece("history.jsonl", line(at(2026, 3, 3), "B", "b").trim())), YEAR, STAMP);
        assertEquals(1, oc.fromImport);
        byte[] out = DiaryImport.readAll(new File(dir, "history.jsonl"));
        assertEquals('\n', out[out.length - 1]);
        assertEquals(2, rawLines(new File(dir, "history.jsonl")).size());
    }

    /* ---------------- backups, idempotence ---------------- */

    @Test public void replacesGetAHiddenBackupOfTheBytesItCompared() throws Exception {
        File dir = tmp.newFolder();
        String a = line(at(2026, 2, 1), "A", "kept");
        write(dir, "history.jsonl", a);
        byte[] before = DiaryImport.readAll(new File(dir, "history.jsonl"));

        DiaryImport.Outcome oc = DiaryImport.merge(dir, pieces(
                piece("history.jsonl", line(at(2026, 6, 6), "B", "new"))), YEAR, STAMP);
        assertEquals(1, oc.filesWritten);

        File backup = new File(dir, ".history.jsonl.bak-" + STAMP);
        assertTrue("no backup beside the replaced file", backup.isFile());
        assertArrayEquals(before, DiaryImport.readAll(backup));
        /* the leading dot is load-bearing: neither the app's prune nor its
         * rollover may match a backup */
        assertFalse(DiaryImport.isDiaryName(backup.getName()));
    }

    @Test public void importingWhatTheDiaryAlreadyHoldsWritesNothing() throws Exception {
        File dir = tmp.newFolder();
        write(dir, "history.jsonl", line(at(2026, 2, 1), "A", "kept"));
        writeGz(dir, "history.2025.jsonl.gz", line(at(2025, 4, 4), "B", "old"));

        List<DiaryImport.Piece> drop = pieces(
                piece("history.jsonl", line(at(2026, 2, 1), "A", "kept")),
                gzPiece("history.2025.jsonl.gz", line(at(2025, 4, 4), "B", "old")));

        DiaryImport.Outcome first = DiaryImport.merge(dir, drop, YEAR, STAMP);
        Map<String, byte[]> settled = snapshot(dir);
        long liveMtime = new File(dir, "history.jsonl").lastModified();

        DiaryImport.Outcome again = DiaryImport.merge(dir, drop, YEAR, STAMP);
        assertEquals(0, again.fromImport);
        assertEquals(0, again.filesWritten);
        assertEquals(2, again.duplicates);
        assertEquals(2, again.totalLines);
        assertTrue(again.summary().contains("nothing new"));
        assertEquals(first.totalLines, again.totalLines);
        assertUnchanged(dir, settled);
        assertEquals("the live file was rewritten", liveMtime,
                new File(dir, "history.jsonl").lastModified());
        assertFalse("a backup appeared for a file that was not replaced",
                new File(dir, ".history.jsonl.bak-" + STAMP).exists());
    }

    /* ---------------- refusals ---------------- */

    private static void assertRefused(File dir, List<DiaryImport.Piece> drop) throws Exception {
        Map<String, byte[]> before = snapshot(dir);
        try {
            DiaryImport.merge(dir, drop, YEAR, STAMP);
            fail("merge should have refused");
        } catch (DiaryImport.Refused expected) {
            assertNotNull(expected.getMessage());
        }
        assertUnchanged(dir, before);
    }

    @Test public void refusesACorruptLine() throws Exception {
        File dir = tmp.newFolder();
        write(dir, "history.jsonl", line(at(2026, 2, 1), "A", "a"));
        assertRefused(dir, pieces(piece("history.jsonl",
                line(at(2026, 3, 3), "B", "b") + "garbage not json\n")));
    }

    @Test public void refusesABlankLine() throws Exception {
        File dir = tmp.newFolder();
        write(dir, "history.jsonl", line(at(2026, 2, 1), "A", "a"));
        assertRefused(dir, pieces(piece("history.jsonl",
                line(at(2026, 3, 3), "B", "b") + "\n" + line(at(2026, 4, 4), "C", "c"))));
    }

    @Test public void refusesALineMissingTheTriple() throws Exception {
        File dir = tmp.newFolder();
        write(dir, "history.jsonl", line(at(2026, 2, 1), "A", "a"));
        assertRefused(dir, pieces(piece("history.jsonl",
                "{\"ts\":1,\"artist\":\"A\"}\n")));                       /* no title */
        assertRefused(dir, pieces(piece("history.jsonl",
                "{\"artist\":\"A\",\"title\":\"t\"}\n")));                /* no ts */
        assertRefused(dir, pieces(piece("history.jsonl",
                "{\"ts\":\"1\",\"artist\":\"A\",\"title\":\"t\"}\n")));   /* quoted ts */
        assertRefused(dir, pieces(piece("history.jsonl",
                "{\"ts\":1.5,\"artist\":\"A\",\"title\":\"t\"}\n")));     /* not whole */
        assertRefused(dir, pieces(piece("history.jsonl",
                "{\"ts\":1,\"artist\":5,\"title\":\"t\"}\n")));           /* ts ok, artist not a string */
    }

    @Test public void refusesGzipThatWillNotDecompress() throws Exception {
        File dir = tmp.newFolder();
        write(dir, "history.jsonl", line(at(2026, 2, 1), "A", "a"));
        assertRefused(dir, pieces(piece("history.2025.jsonl.gz",
                line(at(2025, 1, 1), "B", "b"))));
    }

    @Test public void refusesBytesThatAreNotUtf8() throws Exception {
        File dir = tmp.newFolder();
        byte[] bad = new byte[]{'{', '"', 't', 's', '"', ':', (byte) 0xC3, 0x28, '}'};
        try (FileOutputStream out = new FileOutputStream(new File(dir, "history.jsonl"))) {
            out.write(bad);
        }
        assertRefused(dir, pieces(piece("history.jsonl", line(at(2026, 3, 3), "B", "b"))));
    }

    @Test public void refusesAnUnreadableOwnFile() throws Exception {
        File dir = tmp.newFolder();
        write(dir, "history.jsonl", line(at(2026, 2, 1), "A", "a"));
        Map<String, byte[]> before = snapshot(dir);
        File f = new File(dir, "history.jsonl");
        Set<PosixFilePermission> saved = Files.getPosixFilePermissions(f.toPath());
        try {
            Files.setPosixFilePermissions(f.toPath(),
                    EnumSet.noneOf(PosixFilePermission.class));
            org.junit.Assume.assumeFalse("cannot deny read while running as root",
                    f.canRead());
            try {
                DiaryImport.merge(dir, pieces(
                        piece("history.jsonl", line(at(2026, 3, 3), "B", "b"))), YEAR, STAMP);
                fail("merge should have refused an unreadable diary, not treated it as empty");
            } catch (DiaryImport.Refused expected) {
                /* the point: an unreadable file is not an empty file */
            }
        } finally {
            Files.setPosixFilePermissions(f.toPath(), saved);
        }
        assertUnchanged(dir, before);
    }

    @Test public void aMissingOwnFileIsNotARefusal() throws Exception {
        File dir = tmp.newFolder();
        write(dir, "notes.txt", "x\n");
        DiaryImport.Outcome oc = DiaryImport.merge(dir, pieces(
                piece("history.jsonl", line(at(2026, 3, 3), "B", "b"))), YEAR, STAMP);
        assertEquals(1, oc.fromImport);
        assertEquals(1, oc.filesWritten);
        assertEquals(Arrays.asList("x"), lines(new File(dir, "notes.txt")));
        assertEquals(1, rawLines(new File(dir, "history.jsonl")).size());
    }

    /* ---------------- the parser and the name filter ---------------- */

    @Test public void theTripleNeedsAWholeNumberTsAndStringArtistAndTitle() {
        DiaryImport.Key k = DiaryImport.keyOf(
                "{\"sec\":5,\"artist\":\"Utsu-P\",\"extra\":{\"a\":[1,2]},"
                        + "\"title\":\"Love For You\",\"ts\":1700000000000}");
        assertNotNull(k);
        assertEquals(1_700_000_000_000L, k.ts);
        assertEquals("Utsu-P", k.artist);
        assertEquals("Love For You", k.title);

        assertEquals("escapes decode before the triple is compared",
                k, DiaryImport.keyOf("{\"artist\":\"Utsu\\u002dP\",\"ts\":1700000000000,"
                        + "\"title\":\"Love For You\"}"));
        assertNull(DiaryImport.keyOf("{}"));
        assertNull(DiaryImport.keyOf("garbage"));
        assertNull(DiaryImport.keyOf("{\"ts\":1,\"artist\":\"a\"}"));
        assertNull(DiaryImport.keyOf("{\"ts\":1.5,\"artist\":\"a\",\"title\":\"t\"}"));
        assertNull(DiaryImport.keyOf("{\"ts\":\"1\",\"artist\":\"a\",\"title\":\"t\"}"));
        assertNull(DiaryImport.keyOf("{\"ts\":1,\"artist\":\"a\",\"title\":7}"));
        assertNull(DiaryImport.keyOf("{\"ts\":1,\"artist\":\"a\",\"title\":\"t\"}x"));
        assertEquals(new DiaryImport.Key(1, "a", "t"),
                DiaryImport.keyOf("  {\"ts\":1,\"artist\":\"a\",\"title\":\"t\"}  "));
    }

    @Test public void onlyTheTwoDiaryNamesAreInputs() {
        assertTrue(DiaryImport.isDiaryName("history.jsonl"));
        assertTrue(DiaryImport.isDiaryName("history.2025.jsonl.gz"));
        assertFalse(DiaryImport.isDiaryName("history (1).jsonl"));
        assertFalse(DiaryImport.isDiaryName(".history.jsonl.bak-20260929-132000"));
        assertFalse(DiaryImport.isDiaryName("history.2025.jsonl"));
        assertFalse(DiaryImport.isDiaryName("notes.txt"));
        assertFalse(DiaryImport.isDiaryName(null));
    }

    /* ---------------- the pinned canonical case ---------------- */

    @Test public void thePinnedCanonicalCaseMatchesTheDesktopMerger() throws Exception {
        File dir = tmp.newFolder();
        writeBytes(dir, "history.jsonl", concat(
                nl(C_OWN_FIRST), nl(C_OWN_FIRST_DUP), nl(C_OWN_PREV),
                concat(C_OWN_ESCAPED, NL)));
        byte[] ownBefore = DiaryImport.readAll(new File(dir, "history.jsonl"));

        List<DiaryImport.Piece> drop = Arrays.asList(
                new DiaryImport.Piece("history.jsonl",
                        concat(nl(C_DROP_FIRST), nl(C_DROP_FRESH), nl(C_DROP_PRUNED))),
                new DiaryImport.Piece("history.2025.jsonl.gz",
                        DiaryImport.gzip(nl(C_DROP_ARCHIVED))));

        DiaryImport.Outcome oc = DiaryImport.merge(dir, drop, YEAR, STAMP);

        /* the live file: own's first (sec 100 — own wins both its own duplicate
         * and the drop box's), own's escaped line, then the phone's fresh line */
        File live = new File(dir, "history.jsonl");
        assertEquals("history.jsonl sha256", C_LIVE_SHA, sha256(DiaryImport.readAll(live)));
        List<String> liveLines = lines(live);
        assertEquals(3, liveLines.size());
        assertEquals(C_OWN_FIRST, liveLines.get(0));
        assertEquals(C_DROP_FRESH, liveLines.get(2));
        assertArrayEquals("own's escaped line, byte for byte",
                C_OWN_ESCAPED, rawLines(live).get(1));

        /* one bucket per earlier year, each sorted by ts */
        File y2025 = new File(dir, "history.2025.jsonl.gz");
        assertEquals("history.2025.jsonl.gz sha256 (decompressed)",
                C_Y2025_SHA, sha256(decompressed(y2025)));
        assertEquals(Arrays.asList(C_DROP_ARCHIVED, C_OWN_PREV), lines(y2025));

        File y2024 = new File(dir, "history.2024.jsonl.gz");
        assertEquals("history.2024.jsonl.gz sha256 (decompressed)",
                C_Y2024_SHA, sha256(decompressed(y2024)));
        assertEquals(Arrays.asList(C_DROP_PRUNED), lines(y2024));

        /* the bookkeeping the rule implies, and the replaced file's backup */
        assertEquals(6, oc.totalLines);
        assertEquals(3, oc.fromOwn);
        assertEquals(3, oc.fromImport);
        assertEquals(2, oc.duplicates);
        assertEquals(3, oc.filesWritten);
        assertArrayEquals("the live file's pre-merge bytes",
                ownBefore, DiaryImport.readAll(new File(dir, ".history.jsonl.bak-" + STAMP)));
    }
}
