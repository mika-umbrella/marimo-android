package moe.umbrella.marimo;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Real waveform extraction via MediaExtractor + MediaCodec.
 *  Two passes: count total decoded samples, then bucket each sample by
 *  its absolute index (exact mapping — decoder priming bursts can't pile
 *  into the first bucket, no duration-estimate drift). Each bucket gets
 *  an RMS level, normalized by the 95th percentile of RMS so loud
 *  mastering doesn't flatten the shape. Cached per token; call from a
 *  background thread (a full decode takes ~1s for a 3-minute track). */
public class WaveformExtractor {

    public static final int BUCKETS = 96;
    private static final Map<String, int[]> cache = new HashMap<>();

    public static synchronized int[] get(Context ctx, String token) {
        int[] hit = cache.get(token);
        if (hit != null) return hit;
        int[] peaks = extract(ctx, token);
        if (peaks != null) cache.put(token, peaks);
        return peaks;
    }

    /** Decode once, count samples; -1 on failure. */
    private static long countPass(Context ctx, String token) {
        MediaExtractor ex = open(ctx, token);
        if (ex == null) return -1;
        MediaCodec codec = openCodec(ex);
        if (codec == null) { ex.release(); return -1; }
        long total = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean eos = false;
        try {
            while (!eos) {
                int in = codec.dequeueInputBuffer(10000);
                if (in >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(in);
                    int sz = ex.readSampleData(inBuf, 0);
                    if (sz < 0) {
                        codec.queueInputBuffer(in, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        eos = true;
                    } else {
                        codec.queueInputBuffer(in, 0, sz, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 10000);
                if (out >= 0) {
                    if (info.size > 0) total += info.size / 2;
                    codec.releaseOutputBuffer(out, false);
                }
            }
        } catch (Exception e) {
            total = -1;
        }
        codec.release();
        ex.release();
        return total;
    }

    private static int[] extract(Context ctx, String token) {
        long total = countPass(ctx, token);
        if (total <= 0) return null;

        MediaExtractor ex = open(ctx, token);
        if (ex == null) return null;
        MediaCodec codec = openCodec(ex);
        if (codec == null) { ex.release(); return null; }

        long[] sumsq = new long[BUCKETS];
        int[] maxAmp = new int[BUCKETS];
        long[] counts = new long[BUCKETS];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean eos = false;
        long decoded = 0;
        try {
            while (!eos) {
                int in = codec.dequeueInputBuffer(10000);
                if (in >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(in);
                    int sz = ex.readSampleData(inBuf, 0);
                    if (sz < 0) {
                        codec.queueInputBuffer(in, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        eos = true;
                    } else {
                        codec.queueInputBuffer(in, 0, sz, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 10000);
                if (out >= 0) {
                    ByteBuffer outBuf = codec.getOutputBuffer(out);
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        int n = info.size / 2;
                        long bufStart = decoded;
                        decoded += n;
                        for (int i = 0; i < n; i++) {
                            short s = outBuf.getShort();
                            int b = (int) ((bufStart + i) * BUCKETS / total);
                            if (b < 0) b = 0;
                            if (b >= BUCKETS) b = BUCKETS - 1;
                            int v = s < 0 ? -s : s;
                            sumsq[b] += (long) s * s;
                            if (v > maxAmp[b]) maxAmp[b] = v;
                            counts[b]++;
                        }
                    }
                    codec.releaseOutputBuffer(out, false);
                }
            }
        } catch (Exception e) {
            codec.release();
            ex.release();
            return null;
        }

        /* faithful loudness: RMS per bucket, sqrt curve, normalized by
         * the 95th percentile so a few loud bars don't crush the rest.
         * brickwalled masters read flat — that's honest, not stretched */
        double[] rms = new double[BUCKETS];
        for (int i = 0; i < BUCKETS; i++)
            rms[i] = counts[i] > 0 ? Math.sqrt((double) sumsq[i] / counts[i]) : 0;
        double[] sorted = rms.clone();
        Arrays.sort(sorted);
        double ref = sorted[(int) (BUCKETS * 0.95)];
        if (ref < 1) ref = 1;

        int[] peaks = new int[BUCKETS];
        for (int i = 0; i < BUCKETS; i++) {
            int p;
            if (counts[i] == 0) p = 0;
            else {
                double r = rms[i] / ref;          /* 0..1 */
                p = (int) (100.0 * Math.sqrt(r)); /* sqrt: spread mid-range */
                if (p < 4) p = 4;
                if (p > 100) p = 100;
            }
            peaks[i] = p;
        }
        codec.release();
        ex.release();
        return peaks;
    }

    /** map an externalstorage content:// tree doc to /storage/emulated/0/... */
    private static String pathOf(String token) {
        try {
            String p = Uri.parse(token).getPath();
            int i = p == null ? -1 : p.indexOf("/document/");
            if (i < 0) return null;
            String id = Uri.decode(p.substring(i + 10));
            if (!id.startsWith("primary:")) return null;
            return "/storage/emulated/0/" + id.substring("primary:".length());
        } catch (Exception e) { return null; }
    }

    private static MediaExtractor open(Context ctx, String token) {
        MediaExtractor ex = new MediaExtractor();
        try {
            if (token.startsWith("content://")) {
                /* waydroid's provider NPEs on unindexed files — fall back to
                 * reading the real path (MediaExtractor opens a file path). */
                String path = pathOf(token);
                if (path != null && new java.io.File(path).isFile())
                    ex.setDataSource(path);
                else
                    ex.setDataSource(ctx, Uri.parse(token), null);
            } else {
                ex.setDataSource(token);
            }
        } catch (Exception e) {
            return null;
        }
        int trackIdx = -1;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { trackIdx = i; break; }
        }
        if (trackIdx < 0) { ex.release(); return null; }
        ex.selectTrack(trackIdx);
        return ex;
    }

    private static MediaCodec openCodec(MediaExtractor ex) {
        int trackIdx = -1;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { trackIdx = i; break; }
        }
        if (trackIdx < 0) return null;
        MediaFormat fmt = ex.getTrackFormat(trackIdx);
        String mime = fmt.getString(MediaFormat.KEY_MIME);
        try {
            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();
            return codec;
        } catch (Exception e) {
            return null;
        }
    }
}
