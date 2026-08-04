package moe.umbrella.marimo;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** Real waveform extraction via MediaExtractor + MediaCodec (PCM → peaks).
 *  Async per track; results cached by token. This is also the decode
 *  pipeline a future gapless pass would build on. */
public class WaveformExtractor {

    public static final int BUCKETS = 48;
    private static final Map<String, int[]> cache = new HashMap<>();

    /** peaks[48] normalized 0..100, or null if the track can't decode. */
    public static synchronized int[] get(Context ctx, String token) {
        int[] hit = cache.get(token);
        if (hit != null) return hit;
        int[] peaks = extract(ctx, token);
        if (peaks != null) cache.put(token, peaks);
        return peaks;
    }

    private static int[] extract(Context ctx, String token) {
        MediaExtractor ex = new MediaExtractor();
        try {
            if (token.startsWith("content://"))
                ex.setDataSource(ctx, Uri.parse(token), null);
            else
                ex.setDataSource(token);
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
        MediaFormat fmt = ex.getTrackFormat(trackIdx);
        String mime = fmt.getString(MediaFormat.KEY_MIME);
        long durationUs = fmt.containsKey(MediaFormat.KEY_DURATION)
                ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;

        MediaCodec codec = null;
        try {
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();
        } catch (Exception e) {
            ex.release();
            if (codec != null) codec.release();
            return null;
        }

        int[] sums = new int[BUCKETS];
        long[] counts = new long[BUCKETS];
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
                    ByteBuffer outBuf = codec.getOutputBuffer(out);
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        long bufTime = info.presentationTimeUs;
                        int n = info.size / 2;
                        for (int i = 0; i < n; i++) {
                            short s = outBuf.getShort();
                            int b = (durationUs > 0)
                                    ? (int) ((bufTime * BUCKETS) / durationUs)
                                    : (i * BUCKETS / Math.max(n, 1));
                            if (b < 0) b = 0;
                            if (b >= BUCKETS) b = BUCKETS - 1;
                            int v = s < 0 ? -s : s;
                            sums[b] += v;
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

        int[] peaks = new int[BUCKETS];
        long maxAmp = 1;
        for (int i = 0; i < BUCKETS; i++) {
            if (counts[i] > 0) {
                long avg = sums[i] / counts[i];
                if (avg > maxAmp) maxAmp = avg;
            }
        }
        for (int i = 0; i < BUCKETS; i++) {
            peaks[i] = counts[i] > 0
                    ? (int) Math.max(3, Math.min(100, 100L * sums[i] / counts[i] / maxAmp))
                    : 3;
        }
        try { codec.release(); } catch (Exception ignored) { }
        ex.release();
        return peaks;
    }
}
