package moe.umbrella.marimo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * The rules the sync screen relies on, checked on the JVM.
 *
 * <p>{@code allowedAddress} is the one that matters: the manifest grants cleartext
 * to the whole app (a network-security-config matches domains, not address ranges,
 * and the desktop's address is whatever DHCP gave it), so this method is where
 * "local network only" is actually enforced. Its rejections are the interesting
 * half -- a rule that only ever says yes would pass every positive test.
 */
public class SyncClientTest {

    /* ========================= the address rule ========================= */

    @Test public void privateAndLoopbackAddressesAreAllowedOverHttp() {
        String[] ok = {
            "http://192.168.0.5:8422",
            "http://192.168.255.255",
            "http://10.0.0.1",
            "http://10.255.255.254",
            "http://172.16.0.1",
            "http://172.31.255.254",
            "http://127.0.0.1:8422",
            "http://localhost:8422",
            "http://localhost",
            "http://titan.local:8422",
            "http://169.254.10.10",
            "http://[::1]:8422",
            "https://example.com",
            "https://192.168.0.5:8422",   /* https is always fine */
        };
        for (String url : ok) {
            assertTrue(url + " should be allowed", SyncClient.allowedAddress(url));
        }
    }

    @Test public void publicAddressesAreRefusedOverHttp() {
        String[] no = {
            "http://8.8.8.8",                    /* a public resolver  */
            "http://1.1.1.1",
            "http://example.com",
            "http://my.server.example.com:8422",
            "http://172.32.0.1",                 /* just outside 172.16/12 */
            "http://172.15.255.255",             /* just below it          */
            "http://192.169.0.1",                /* next door to 192.168   */
            "http://11.0.0.1",
            "http://126.0.0.1",                  /* next to loopback       */
            "http://128.0.0.1",
            "ftp://192.168.0.5",
            "192.168.0.5:8422",                  /* no scheme: not a URL   */
            "http://",
            "",
            null,
        };
        for (String url : no) {
            assertFalse(String.valueOf(url) + " should be refused",
                    SyncClient.allowedAddress(url));
        }
    }

    @Test public void bareIpv4OutOfRangeIsRefused() {
        assertFalse(SyncClient.allowedAddress("http://192.168.0.256"));   /* not an address */
        assertFalse(SyncClient.allowedAddress("http://192.168.0"));
        assertFalse(SyncClient.allowedAddress("http://192.168.0.5.6"));
        assertFalse(SyncClient.allowedAddress("http://192.168.0.5a"));
    }

    /* =========================== normalising input =========================== */

    @Test public void normaliseAcceptsWhatAUserWouldActuallyType() {
        assertEquals("http://192.168.0.5:8422", SyncClient.normalise("192.168.0.5"));
        assertEquals("http://192.168.0.5:9999", SyncClient.normalise("192.168.0.5:9999"));
        assertEquals("http://192.168.0.5:8422", SyncClient.normalise("http://192.168.0.5"));
        assertEquals("http://192.168.0.5:8422", SyncClient.normalise("http://192.168.0.5/"));
        assertEquals("http://192.168.0.5:8422", SyncClient.normalise("  192.168.0.5  "));
        assertEquals("http://192.168.0.5:1234", SyncClient.normalise("http://192.168.0.5:1234"));
        assertEquals("https://host.example:8443", SyncClient.normalise("https://host.example:8443"));
        assertEquals("", SyncClient.normalise(""));
        assertEquals("", SyncClient.normalise(null));
        assertEquals("", SyncClient.normalise("http://"));
    }

    /* ========================== what has to be fetched ========================== */

    private static Map<String, Long> files(Object... nameThenSize) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < nameThenSize.length; i += 2) {
            m.put((String) nameThenSize[i], ((Number) nameThenSize[i + 1]).longValue());
        }
        return m;
    }

    @Test public void missingAlbumsListsAbsentAndIncompleteOnes() {
        Map<String, Map<String, Long>> library = new LinkedHashMap<>();
        library.put("absent", files("01.opus", 100L, "cover.jpg", 10L));
        library.put("complete", files("01.opus", 100L, "cover.jpg", 10L));
        library.put("truncated", files("01.opus", 100L));
        library.put("extra-on-device", files("01.opus", 100L));

        Map<String, Map<String, Long>> device = new LinkedHashMap<>();
        device.put("complete", files("01.opus", 100L, "cover.jpg", 10L));
        device.put("truncated", files("01.opus", 99L));            /* wrong length */
        device.put("extra-on-device", files("01.opus", 100L, "notes.txt", 5L));

        List<String> order = Arrays.asList("absent", "complete", "truncated", "extra-on-device");
        List<String> missing = SyncClient.missingAlbums(order, library, device);
        assertEquals(Arrays.asList("absent", "truncated"), missing);
    }

    @Test public void missingAlbumsIsEmptyWhenEverythingMatches() {
        Map<String, Map<String, Long>> library = new LinkedHashMap<>();
        library.put("a", files("01.opus", 100L));
        Map<String, Map<String, Long>> device = new LinkedHashMap<>();
        device.put("a", files("01.opus", 100L, "waves.marimo", 20L));
        assertTrue(SyncClient.missingAlbums(Arrays.asList("a"), library, device).isEmpty());
    }

    @Test public void missingFilesNamesOnlyWhatDiffersOrIsAbsent() {
        Map<String, Long> want = files("01.opus", 100L, "cover.jpg", 10L, "waves.marimo", 5L);
        Map<String, Long> have = files("01.opus", 100L, "cover.jpg", 11L);   /* cover differs */
        assertEquals(Arrays.asList("cover.jpg", "waves.marimo"),
                SyncClient.missingFiles(want, have));
        assertTrue(SyncClient.missingFiles(want, want).isEmpty());
        assertEquals(Arrays.asList("01.opus", "cover.jpg", "waves.marimo"),
                SyncClient.missingFiles(want, null));            /* nothing there at all */
        assertTrue(SyncClient.missingFiles(null, have).isEmpty());
    }

    @Test public void albumUrlsAreEncodedForTheCharsRealNamesHave() {
        /* Conservative: everything outside the unreserved set is percent-encoded,
         * brackets and parentheses included. The server unquotes the path, and
         * serve_smoke proves the round trip with a real album name that has both. */
        assertEquals("36g%20-%20%282016%29%20%E5%8A%A3%E6%80%A7e.p%20%5BMP3%5D",
                SyncClient.uriEncode("36g - (2016) 劣性e.p [MP3]"));
        assertEquals("Various%20-%20%282020%29%20Compilation%20%5BOPUS%5D",
                SyncClient.uriEncode("Various - (2020) Compilation [OPUS]"));
        assertEquals("a-b_c.d~e", SyncClient.uriEncode("a-b_c.d~e"));
        assertEquals("", SyncClient.uriEncode(""));
    }

    /* ================================ the diary ================================ */

    private static DiaryImport.Piece piece(String name, byte[] bytes) {
        return new DiaryImport.Piece(name, bytes);
    }

    private static Map<String, Object> asMap(Object o) {
        assertNotNull("expected a JSON object, got " + o, o);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) o;
        return m;
    }

    /** The JDK's base64, so the hand-rolled one is checked against something that
     *  was not written in this repo. */
    private static String jdk64(byte[] b) {
        return java.util.Base64.getEncoder().encodeToString(b);
    }

    @Test public void diaryRequestBodyCarriesTheNamesTheSizesAndTheBytes() throws Exception {
        byte[] live = "{\"ts\":1,\"artist\":\"a\",\"title\":\"t\"}\n".getBytes("UTF-8");
        byte[] arch = new byte[]{1, 2, 3, (byte) 0xff, 0, '\n'};
        String body = SyncClient.diaryRequestBody(Arrays.asList(
                piece("history.jsonl", live),
                piece("history.2025.jsonl.gz", arch)));

        Map<String, Object> root = asMap(SyncClient.json(body));
        assertEquals(Long.valueOf(1), root.get("version"));
        List<?> files = (List<?>) root.get("files");
        assertNotNull(files);
        assertEquals(2, files.size());

        Map<String, Object> first = asMap(files.get(0));
        assertEquals("history.jsonl", first.get("name"));
        assertEquals(Long.valueOf(live.length), first.get("size"));
        assertArrayEquals(live,
                java.util.Base64.getDecoder().decode((String) first.get("b64")));

        Map<String, Object> second = asMap(files.get(1));
        assertEquals("history.2025.jsonl.gz", second.get("name"));
        assertArrayEquals(arch,
                java.util.Base64.getDecoder().decode((String) second.get("b64")));
    }

    @Test public void diaryRequestBodyIsStillWellFormedWithNothingToSend() {
        /* a brand-new phone has no diary yet and must still be able to ask for one */
        Map<String, Object> root = asMap(SyncClient.json(
                SyncClient.diaryRequestBody(Arrays.<DiaryImport.Piece>asList())));
        assertTrue(((List<?>) root.get("files")).isEmpty());
    }

    @Test public void diaryReplyCarriesTheMergeNumbersAndTheMergedFiles() throws Exception {
        byte[] merged = "{\"ts\":9,\"artist\":\"a\",\"title\":\"t\"}\n".getBytes("UTF-8");
        byte[] archive = new byte[]{31, (byte) 139, 8, 0};
        String json = "{\"ok\":true,\"before\":47,\"after\":204,\"added\":157,"
                + "\"duplicates\":47,\"digest\":\"9ebedd90\",\"something_new\":\"ignored\","
                + "\"files\":["
                + "{\"name\":\"history.jsonl\",\"size\":" + merged.length
                + ",\"b64\":\"" + jdk64(merged) + "\"},"
                + "{\"name\":\"history.2024.jsonl.gz\",\"size\":" + archive.length
                + ",\"b64\":\"" + jdk64(archive) + "\"}]}";

        SyncClient.DiaryReply r = SyncClient.parseDiaryReply(json);
        assertNotNull(r);
        assertEquals(47, r.before);
        assertEquals(204, r.after);
        assertEquals(157, r.added);
        assertEquals(47, r.duplicates);
        assertEquals("9ebedd90", r.digest);
        assertEquals(2, r.files.size());
        assertTrue(r.ignored.isEmpty());
    }

    @Test public void receivedFilesBecomeThePiecesTheMergeIsGiven() throws Exception {
        /* the seam that matters: what the desktop sends has to arrive as exactly
         * (name, bytes) for DiaryImport.merge -- the merge rule itself is covered by
         * DiaryImportTest, including the canonical digests both sides pin. */
        byte[] merged = "{\"ts\":9,\"artist\":\"a\",\"title\":\"t\"}\n".getBytes("UTF-8");
        String json = "{\"after\":204,\"files\":[{\"name\":\"history.jsonl\","
                + "\"b64\":\"" + jdk64(merged) + "\"}]}";

        List<DiaryImport.Piece> pieces = SyncClient.parseDiaryReply(json).files;
        assertEquals(1, pieces.size());
        assertEquals("history.jsonl", pieces.get(0).name);
        assertTrue("only diary names reach the merge",
                DiaryImport.isDiaryName(pieces.get(0).name));
        assertArrayEquals(merged, pieces.get(0).bytes);
    }

    @Test public void diaryReplyRefusesToHandOverAnythingThatIsNotADiaryFile() {
        String json = "{\"files\":["
                + "{\"name\":\"notes.txt\",\"b64\":\"AA==\"},"
                + "{\"name\":\"history (1).jsonl\",\"b64\":\"AA==\"},"
                + "{\"name\":\"history.jsonl\",\"b64\":\"AA==\"}]}";
        SyncClient.DiaryReply r = SyncClient.parseDiaryReply(json);
        assertNotNull(r);
        assertEquals(1, r.files.size());
        assertEquals("history.jsonl", r.files.get(0).name);
        assertEquals(Arrays.asList("notes.txt", "history (1).jsonl"), r.ignored);
    }

    @Test public void diaryReplyOnSomethingUnreadableIsNull() {
        assertNull(SyncClient.parseDiaryReply(null));
        assertNull(SyncClient.parseDiaryReply(""));
        assertNull(SyncClient.parseDiaryReply("not json"));
        assertNull(SyncClient.parseDiaryReply("[1,2,3]"));      /* not an object */
        assertNull(SyncClient.parseDiaryReply("{\"after\":204")); /* truncated */

        SyncClient.DiaryReply bare = SyncClient.parseDiaryReply("{}");
        assertNotNull("an empty object is a reply that says nothing, not a failure", bare);
        assertEquals(-1, bare.after);            /* no number is not zero lines */
        assertTrue(bare.files.isEmpty());
    }

    @Test public void diaryHttpErrorsNameWhoseEndIsWrong() {
        assertTrue(SyncClient.diaryHttpError(401).contains("token"));
        assertTrue(SyncClient.diaryHttpError(403).contains("token"));

        String missing = SyncClient.diaryHttpError(404);
        assertTrue(missing, missing.contains("/api/diary"));
        assertFalse("an old desktop is version skew, not a bad token",
                missing.contains("token"));

        assertTrue(SyncClient.diaryHttpError(500).contains("500"));
    }

    @Test public void diaryLineSaysWhatHappenedInBothDirections() {
        String added = SyncClient.diaryLine(1, 47, 204, 157);
        assertTrue(added, added.contains("sent 47 lines (1 file)"));
        assertTrue(added, added.contains("got 204 back"));
        assertTrue(added, added.contains("imported 157 new"));

        String settled = SyncClient.diaryLine(2, 204, 204, 0);
        assertTrue(settled, settled.contains("sent 204 lines (2 files)"));
        assertTrue(settled, settled.contains("nothing new"));
        assertFalse(settled, settled.contains("imported"));

        String noNumbers = SyncClient.diaryLine(1, 47, -1, 5);
        assertFalse("no line count is not a line count of zero", noNumbers.contains("got"));
        assertTrue(noNumbers, noNumbers.contains("imported 5 new"));
    }

    @Test public void theDiaryPostIsRefusedOffTheLocalNetwork() {
        String url = "http://8.8.8.8:8422" + SyncClient.DIARY_ENDPOINT;
        java.io.IOException e = assertThrows(java.io.IOException.class,
                () -> SyncClient.open(url, "token", null));
        assertTrue(e.getMessage(), e.getMessage().contains("local network"));
        /* and the rule is a narrowing, not a wall: the same endpoint on the LAN is fine */
        assertTrue(SyncClient.allowedAddress("http://192.168.0.5:8422" + SyncClient.DIARY_ENDPOINT));
    }

    @Test public void diaryBase64MatchesTheJdksEncoderBothWays() throws Exception {
        byte[][] cases = {
            {},
            {0},
            {(byte) 0xff},
            {1, 2},
            {1, 2, 3},
            {'\n', 0, (byte) 0x80, 7, (byte) 0xc3, (byte) 0xa9},
            "the quick brown fox jumps over the lazy dog".getBytes("UTF-8"),
        };
        for (byte[] c : cases) {
            assertEquals("encoding " + c.length + " bytes", jdk64(c), SyncClient.base64(c));
            assertArrayEquals("decoding " + c.length + " bytes",
                    c, SyncClient.base64Decode(jdk64(c)));
        }
        assertNull(SyncClient.base64Decode("!!!!"));
        assertNull(SyncClient.base64Decode("A"));       /* impossible length */
        assertEquals(0, SyncClient.base64Decode("").length);
    }

    @Test public void diaryLineCountCountsArchivesAfterDecompressingThem() throws Exception {
        byte[] live = "{\"ts\":1}\n{\"ts\":2}\n{\"ts\":3}\n".getBytes("UTF-8");
        byte[] arch = DiaryImport.gzip("{\"ts\":4}\n{\"ts\":5}\n".getBytes("UTF-8"));
        assertEquals(5, SyncClient.diaryLineCount(Arrays.asList(
                piece("history.jsonl", live), piece("history.2024.jsonl.gz", arch))));
        assertEquals(0, SyncClient.diaryLineCount(Arrays.<DiaryImport.Piece>asList()));
        /* a piece that is not really gzip contributes nothing rather than a fiction */
        assertEquals(0, SyncClient.diaryLineCount(Arrays.asList(
                piece("history.2024.jsonl.gz", new byte[]{0, 1, 2}))));
    }

    @Test public void theDesktopsOwnReasonRidesAlongWhenItSendsOne() {
        /* 409 and 422 are the two codes where the body is the only place the reason
         * lives: the merge is switched off on that desktop, and which line it refused. */
        String off = SyncClient.diaryHttpError(409,
                "the diary is switched off on this desktop (serve.diary is false)");
        assertTrue(off, off.startsWith("HTTP 409 -- "));
        assertTrue(off, off.contains("serve.diary is false"));

        String refused = SyncClient.diaryHttpError(422,
                "the merge refused it: blank line at 3");
        assertTrue(refused, refused.contains("HTTP 422"));
        assertTrue(refused, refused.contains("blank line at 3"));
    }

    @Test public void aBodyCannotBuryTheCodesThatDiagnoseAnEnd() {
        /* 401/403 and 404 say which END is wrong; a body cannot improve on that, and a
         * framework trace would bury it. For every other code a stack trace still arrives
         * as one bounded line, because a status bar is not a log. */
        String token = SyncClient.diaryHttpError(403, "Traceback\n  at x.y.Z");
        assertTrue(token, token.contains("token"));
        assertFalse(token, token.contains("Traceback"));

        String missing = SyncClient.diaryHttpError(404, "no such route");
        assertTrue(missing, missing.contains("/api/diary"));
        assertFalse(missing, missing.contains("no such route"));

        StringBuilder trace = new StringBuilder("Traceback (most recent call last):");
        for (int i = 0; i < 200; i++) trace.append("\n  File \"x.py\", line ").append(i);
        String flat = SyncClient.diaryHttpError(500, trace.toString());
        assertTrue(flat, flat.startsWith("HTTP 500 -- Traceback"));
        assertFalse(flat, flat.contains("\n"));
        assertTrue("one bounded line, was " + flat.length(), flat.length() < 200);

        assertEquals("HTTP 502 from the desktop", SyncClient.diaryHttpError(502, null));
        assertEquals("HTTP 502 from the desktop", SyncClient.diaryHttpError(502, "  \n\t  "));
    }

    @Test public void theLogToggleSaysWhatItWillDo() {
        /* the label is the only part of "logs collapsed by default" that is not Android
         * view state, so it is the part that can be pinned on the JVM -- the wording
         * lives here so the initial state and the click handler cannot drift apart */
        assertEquals("show log", SyncClient.logToggleLabel(false));
        assertEquals("hide log", SyncClient.logToggleLabel(true));
    }
}
