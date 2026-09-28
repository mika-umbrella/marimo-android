package moe.umbrella.marimo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;

/** Parses a REAL generated sidecar (app/src/test/resources/36g-ep.waves.marimo,
 *  produced by scripts/make-waveforms.py from the phone's own library) and
 *  checks the values against what the generator computed independently in
 *  Python. This is the half of the feature that is a hand-rolled binary format,
 *  so it gets tested here rather than discovered on the phone. */
public class WaveSidecarTest {

    private static final int BUCKETS = 96;

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = WaveSidecarTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            assertNotNull("missing test resource: " + name, in);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            return bos.toByteArray();
        }
    }

    @Test
    public void parsesEveryTrackInARealSidecar() throws Exception {
        Map<String, int[]> m = WaveSidecar.parse(resource("36g-ep.waves.marimo"));

        assertEquals("all four tracks of the EP", 4, m.size());
        for (String name : new String[]{"01. 劣性.mp3", "02. カスミ.mp3",
                "03. サツキ.mp3", "04. ホーム_スイートホーム.mp3"}) {
            assertNotNull("expected an entry for " + name, m.get(name));
        }
        for (int[] peaks : m.values()) {
            assertEquals(BUCKETS, peaks.length);
            for (int p : peaks) {
                assertTrue("peak out of range: " + p, p >= 0 && p <= 100);
            }
        }
    }

    /** the exact row the generator produced for track 1 (Python side) */
    @Test
    public void peaksMatchTheGeneratorsOwnOutput() throws Exception {
        Map<String, int[]> m = WaveSidecar.parse(resource("36g-ep.waves.marimo"));

        assertArrayEquals(new int[]{
                32, 46, 52, 64, 72, 73, 75, 81, 80, 80, 85, 88, 87, 91, 91, 90,
                89, 94, 93, 92, 94, 94, 88, 76, 85, 86, 94, 98, 99, 99, 98, 99,
                98, 99, 99, 100, 99, 98, 99, 98, 99, 100, 98, 99, 98, 97, 95, 98,
                97, 97, 98, 97, 96, 97, 98, 98, 97, 97, 97, 97, 98, 97, 97, 98,
                98, 98, 97, 97, 97, 97, 98, 96, 97, 98, 99, 97, 97, 97, 98, 97,
                98, 97, 97, 97, 98, 100, 100, 100, 99, 92, 82, 47, 12, 7, 4, 4},
                m.get("01. 劣性.mp3"));
    }

    /** lookup is by file name and must survive unicode normalisation differences */
    @Test
    public void lookupIsNormalisationProof() throws Exception {
        Map<String, int[]> m = WaveSidecar.parse(resource("36g-ep.waves.marimo"));

        assertNotNull(WaveSidecar.peaksFor(m, "02. カスミ.mp3"));
        assertNotNull(WaveSidecar.peaksFor(m, "04. ホーム_スイートホーム.mp3"));
        assertNull("a track this sidecar doesn't cover", WaveSidecar.peaksFor(m, "zz.mp3"));
        assertNull(WaveSidecar.peaksFor(m, null));
    }

    /** a sidecar that is absent, foreign or cut short must degrade quietly to
     *  "decode it like before" -- never throw, never return junk peaks */
    @Test
    public void degradesQuietlyOnBadInput() throws Exception {
        assertTrue(WaveSidecar.parse(null).isEmpty());
        assertTrue(WaveSidecar.parse(new byte[0]).isEmpty());
        assertTrue(WaveSidecar.parse(new byte[11]).isEmpty());
        assertTrue("wrong magic", WaveSidecar.parse("NOTOURS!............".getBytes())
                .isEmpty());
        assertTrue("magic only", WaveSidecar.parse("MWAVS001".getBytes()).isEmpty());

        byte[] real = resource("36g-ep.waves.marimo");
        for (int cut : new int[]{13, 20, 60, 200, real.length - 1}) {
            Map<String, int[]> m = WaveSidecar.parse(
                    java.util.Arrays.copyOf(real, cut));
            for (int[] peaks : m.values()) assertEquals(BUCKETS, peaks.length);
        }
    }

    /** the count field must not be trusted into a huge loop */
    @Test
    public void absurdCountDoesNotRunAway() {
        byte[] blob = new byte[13];
        System.arraycopy("MWAVS001".getBytes(), 0, blob, 0, 8);
        blob[8] = (byte) 0xff;   // ~1.1e9 entries declared, no bytes behind them
        blob[9] = (byte) 0xff;
        blob[10] = (byte) 0xff;
        blob[11] = (byte) 0x3f;
        assertTrue(WaveSidecar.parse(blob).isEmpty());
    }
}
