package moe.umbrella.marimo;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

/** Parser for the per-album waveform sidecar written by
 *  `scripts/make-waveforms.py` on the workstation.
 *
 *  <pre>magic "MWAVS001" | uint32 count | (uint16 nameLen | name UTF-8 | 96 peaks)*</pre>
 *
 *  Why this exists: decoding a track ON the phone costs ~19s (Android runs the
 *  audio codec in a separate process and charges ~300us per buffer hand-off,
 *  ~7850 of them per track). The workstation decodes the same track in ~0.2s, so
 *  the peaks are shipped alongside the album and there is nothing to decode.
 *
 *  Kept free of Android types on purpose: the binary layout is the part most
 *  likely to be silently wrong, and this way it is unit-tested on the JVM
 *  against a real generated file (see WaveSidecarTest) rather than only being
 *  exercised on a phone. */
final class WaveSidecar {

    /** the file name, looked for in each album folder next to cover.jpg */
    static final String FILENAME = "waves.marimo";
    private static final byte[] MAGIC = "MWAVS001".getBytes(StandardCharsets.US_ASCII);

    private WaveSidecar() { }

    /** filename (NFC-normalised) -> peaks. Never throws: a missing, truncated or
     *  foreign file must just degrade to "decode it like before". A truncated
     *  blob yields whatever complete entries it held. */
    static Map<String, int[]> parse(byte[] blob) {
        Map<String, int[]> out = new HashMap<>();
        if (blob == null || blob.length < 12) return out;
        for (int i = 0; i < MAGIC.length; i++)
            if (blob[i] != MAGIC[i]) return out;
        int count = (blob[8] & 0xff) | ((blob[9] & 0xff) << 8)
                | ((blob[10] & 0xff) << 16) | ((blob[11] & 0xff) << 24);
        if (count <= 0) return out;
        int off = 12;
        for (int i = 0; i < count; i++) {
            if (off + 2 > blob.length) break;
            int len = (blob[off] & 0xff) | ((blob[off + 1] & 0xff) << 8);
            off += 2;
            if (len <= 0 || off + len + WaveformExtractor.BUCKETS > blob.length) break;
            String name = new String(blob, off, len, StandardCharsets.UTF_8);
            off += len;
            int[] peaks = new int[WaveformExtractor.BUCKETS];
            for (int k = 0; k < peaks.length; k++) peaks[k] = blob[off + k] & 0xff;
            off += peaks.length;
            /* NFC both sides: the same Japanese name can come back normalised
             * differently depending on which filesystem it travelled through. */
            out.put(Normalizer.normalize(name, Normalizer.Form.NFC), peaks);
        }
        return out;
    }

    /** peaks for one track, or null if this album's sidecar doesn't cover it */
    static int[] peaksFor(Map<String, int[]> byName, String trackName) {
        return trackName == null ? null
                : byName.get(Normalizer.normalize(trackName, Normalizer.Form.NFC));
    }
}
