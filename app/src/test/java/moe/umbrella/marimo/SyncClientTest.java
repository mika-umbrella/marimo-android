package moe.umbrella.marimo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
}
