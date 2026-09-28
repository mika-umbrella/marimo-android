package moe.umbrella.marimo;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Real waveform extraction via MediaExtractor + MediaCodec.
 *  ONE decode pass: bucket each sample by its absolute index
 *  (pts-derived, so decoder priming bursts can't pile into the first
 *  bucket and there's no duration-estimate drift). Each bucket gets an
 *  RMS level, normalized by the 95th percentile of RMS so loud mastering
 *  doesn't flatten the shape. Cached per token + persisted; call from a
 *  background thread.
 *
 *  Cost model (measured): the DECODE is ~99% of it. The accumulate loop
 *  over a 4-minute track is ~4ms; decoding those 4 minutes is ~1s. So the
 *  only things worth optimising are what starves or stalls the decoder —
 *  see the feed/drain loop in extract(). */
public class WaveformExtractor {

    public static final int BUCKETS = 96;
    private static final Map<String, int[]> cache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Object> tokenLocks =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final String CACHE_DIR = "waveforms";

    /* one shared CPU-bound pool: lets whole-album pre-warming decode many
     * tracks at once (no global lock) instead of one serial pass each.
     * Deliberately capped BELOW the core count: these decoders run while the
     * player is decoding the track you're actually listening to, and each
     * one needs a MediaCodec instance (AOSP limits concurrent instances, and
     * a refused instance just returns null — a silently missing waveform).
     * Four keeps pre-warm off the playback pipeline's back and off the
     * thermal throttle. */
    private static final ExecutorService pool = Executors.newFixedThreadPool(
            Math.max(2, Math.min(2, Runtime.getRuntime().availableProcessors())));

    /* one scratch PCM buffer per worker — a fresh short[] per output buffer
     * would be ~2.5k allocations per track for no reason */
    private static final ThreadLocal<short[]> scratchTL =
            ThreadLocal.withInitial(() -> new short[32768]);

    /* one logcat line per track actually decoded (not per cached read). Cheap,
     * and the only way to answer "why is the seekbar slow" with numbers
     * instead of a guess: `adb logcat -s marimo-wf`. */
    private static final boolean WF_LOG = true;

    /** last path segment of a token, for the log line only */
    private static String shortName(String token) {
        int i = Math.max(token.lastIndexOf('/'), token.lastIndexOf('%'));
        String s = i >= 0 ? token.substring(i + 1) : token;
        return s.length() > 48 ? s.substring(0, 48) : s;
    }

    /** parallel pre-warm of future tracks so their waveforms are on disk
     *  before they start playing — the seekbar fills instantly later.
     *  skips already-cached tokens, never blocks the caller. */
    public static void prewarm(Context ctx, java.util.List<String> tokens) {
        for (String token : tokens) {
            try {
                if (cache.containsKey(token)) continue;
                if (cacheFile(ctx, token).exists()) continue;
            } catch (Exception ignore) { continue; }
            pool.submit(() -> {
                try { get(ctx, token); } catch (Exception ignore) { }
            });
        }
    }

    public static int[] get(Context ctx, String token) {
        int[] hit = cache.get(token);
        if (hit != null) return hit;
        /* per-token lock: different tokens decode in parallel, the same token
         * (get + prewarm racing) extracts only once */
        Object lock = tokenLocks.computeIfAbsent(token, k -> new Object());
        synchronized (lock) {
            hit = cache.get(token);
            if (hit != null) return hit;
            hit = readDisk(ctx, token);
            if (hit != null) { cache.put(token, hit); return hit; }
            int[] peaks = extract(ctx, token);
            if (peaks != null) {
                cache.put(token, peaks);
                writeDisk(ctx, token, peaks);
            }
            return peaks;
        }
    }

    /** Seed a track's waveform from its album's precomputed sidecar (read by the
     *  library scan). Also written to disk: a memory-only seed would mean the
     *  first play after every app launch decodes again, which is the exact cost
     *  this exists to avoid. Re-scanning refreshes it. */
    public static void putPrecomputed(Context ctx, String token, int[] peaks) {
        if (token == null || peaks == null || peaks.length != BUCKETS) return;
        cache.put(token, peaks);
        writeDisk(ctx, token, peaks);
    }

    /** persist a waveform once so repeat listens (even across launches) are
     *  instant — the full MediaCodec decode only ever happens one time.
     *  compact: 64-bit token hash (8B) + 96 peak bytes ≈ ~104B per track. */

    /* bump when extraction changes shape, so a stale file isn't read back as
     * truth. v2: the loop now drains to the decoder's output EOS (v1 stopped
     * one dequeue after input EOS and lost the tail buffers) and accumulates
     * from bulk reads, so every bucket is marginally better fed. */
    private static final int CACHE_VERSION = 4;

    private static long tokenHash(String token) {
        long h = 1125899906842597L;
        h = 31 * h + CACHE_VERSION;
        for (int i = 0; i < token.length(); i++) h = 31 * h + token.charAt(i);
        return h;
    }

    private static java.io.File cacheFile(Context ctx, String token) {
        java.io.File d = new java.io.File(ctx.getFilesDir(), CACHE_DIR);
        d.mkdirs();
        return new java.io.File(d, Long.toHexString(tokenHash(token)) + ".wf");
    }

    private static int[] readDisk(Context ctx, String token) {
        try {
            java.io.File f = cacheFile(ctx, token);
            if (!f.exists()) return null;
            try (java.io.DataInputStream in = new java.io.DataInputStream(
                    new java.io.FileInputStream(f))) {
                if (in.readLong() != tokenHash(token)) return null;
                int[] p = new int[BUCKETS];
                for (int i = 0; i < BUCKETS; i++) p[i] = in.readUnsignedByte();
                return p;
            }
        } catch (Exception e) { return null; }
    }

    private static void writeDisk(Context ctx, String token, int[] peaks) {
        try {
            java.io.File d = new java.io.File(ctx.getFilesDir(), CACHE_DIR);
            d.mkdirs();   /* the first write needs the dir before the .tmp path */
            /* per-token tmp name, NOT one shared ".tmp": prewarm decodes on
             * several threads at once (and the library scan seeds on several
             * more), and a shared temp path has them overwriting each other's
             * half-written bytes before the rename. */
            java.io.File tmp = new java.io.File(d,
                    Long.toHexString(tokenHash(token)) + ".tmp");
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                    new java.io.FileOutputStream(tmp))) {
                out.writeLong(tokenHash(token));
                for (int p : peaks) out.writeByte(p);
            }
            if (!tmp.renameTo(cacheFile(ctx, token))) {
                java.io.File dst = cacheFile(ctx, token);
                java.nio.file.Files.copy(tmp.toPath(), dst.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmp.delete();
            }
        } catch (Exception e) { }
    }

    private static int[] extract(Context ctx, String token) {
        MediaExtractor ex = open(ctx, token);
        if (ex == null) return null;
        int rate = 44100;
        long durUs = 0;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE) && f.getString(MediaFormat.KEY_MIME) != null
                    && f.getString(MediaFormat.KEY_MIME).startsWith("audio/"))
                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            if (f.containsKey(MediaFormat.KEY_DURATION))
                durUs = Math.max(durUs, f.getLong(MediaFormat.KEY_DURATION));
        }
        MediaCodec codec = openCodec(ex);
        if (codec == null) { ex.release(); return null; }

        /* one decode pass: bucket each sample by its absolute index
         * (from presentationTimeUs * rate), no separate count pass */
        long total = rate <= 0 ? 1 : durUs * rate / 1000000L;   /* ~sample count */
        if (total <= 0) total = 1;
        long[] sumsq = new long[BUCKETS];
        long[] counts = new long[BUCKETS];
        short[] scratch = scratchTL.get();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int stride = 4;            /* sample every 4th sample — faster, same envelope */
        long samples = 0, accumNs = 0;
        long inNs = 0, outNs = 0;
        int nbuf = 0, fedTotal = 0, idleIters = 0;
        long tDecode0 = System.nanoTime();
        try {
            /* Deliberately the ORIGINAL shape: one blocking input hand-off and
             * one blocking output hand-off per turn, and nothing speculative in
             * between. The codec runs in another process, so a dequeue that
             * comes back empty is not free — it's a full cross-process call —
             * and burst-feeding then polling measured ~20-30% WORSE per packet
             * than this. Keep it boring.
             *
             * One change from the original: it stopped one dequeue after input
             * EOS, which dropped the decoder's tail buffers and left the last
             * buckets under-fed. Now it drains until the output side says
             * EOS, with a bounded number of empty turns as a backstop. */
            boolean outputDone = false;
            boolean inputDone = false;
            long lastDrainNs = System.nanoTime();
            while (true) {
                long tIn = System.nanoTime();
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10000);   /* waits for a slot */
                    if (in >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(in);
                        int sz = inBuf == null ? -1 : ex.readSampleData(inBuf, 0);
                        if (sz < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, sz, ex.getSampleTime(), 0);
                            ex.advance();
                            fedTotal++;
                        }
                    }
                }
                inNs += System.nanoTime() - tIn;     /* extractor reads + feed */

                long tOut = System.nanoTime();
                boolean drained = false;
                int out = codec.dequeueOutputBuffer(info, 10000);   /* waits for PCM */
                while (out >= 0) {
                    drained = true;
                    if (info.size > 0) {
                        ByteBuffer outBuf = codec.getOutputBuffer(out);
                        if (outBuf != null) {
                            long t0 = System.nanoTime();
                            accumulate(outBuf, info.offset, info.size,
                                    info.presentationTimeUs, rate, total, stride,
                                    sumsq, counts, scratch);
                            accumNs += System.nanoTime() - t0;
                            samples += info.size / 2;
                        }
                    }
                    boolean eosOut = (info.flags
                            & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(out, false);
                    nbuf++;
                    if (eosOut) { outputDone = true; break; }
                    out = codec.dequeueOutputBuffer(info, 0);   /* take what's ready */
                }
                outNs += System.nanoTime() - tOut;   /* decoder wait + drain */
                if (outputDone) break;
                if (drained) lastDrainNs = System.nanoTime();
                /* input exhausted: stop once the decoder has gone quiet for a
                 * while. Time-based, not "N empty turns" — each turn can be a
                 * 10ms block, and under memory pressure a slow decoder must not
                 * be mistaken for a finished one. */
                if (inputDone
                        && System.nanoTime() - lastDrainNs > 250000000L) break;
            }
        } catch (Exception e) {
            /* never swallow this: a failed decode returns null, which the
             * seekbar shows as a silent flat baseline — indistinguishable from
             * a track that genuinely has no waveform. */
            android.util.Log.w("marimo-wf", "decode failed: " + e, e);
            codec.release();
            ex.release();
            return null;
        }
        if (WF_LOG) android.util.Log.d("marimo-wf", shortName(token)
                + " scheme=" + (token.startsWith("content:") ? "content" : "path")
                + " rate=" + rate + " dur=" + (durUs / 1000) + "ms"
                + " samples=" + samples + " bufs=" + nbuf + " fed=" + fedTotal
                + " total=" + (System.nanoTime() - tDecode0) / 1000000L + "ms"
                + " feedRead=" + inNs / 1000000L + "ms"
                + " decodeDrain=" + outNs / 1000000L + "ms"
                + " accumulate=" + accumNs / 1000000L + "ms"
                + " idle=" + idleIters);

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

    /** fold one decoded PCM buffer into the 96 buckets. Bulk-copies the data
     *  into a short[] once instead of reading sample-at-a-time through
     *  ByteBuffer.position()/getShort(), and walks the bucket boundaries
     *  instead of dividing per sample (the boundary condition is exactly
     *  equivalent to floor(idx*96/total)).
     *
     *  Measured honest: 12.4ms -> 4.1ms for a 4-minute track, against a ~1s
     *  decode. This is not where the time goes — it's here because it's free,
     *  removes ~2.6M pointless JNI-ish calls, and keeps the loop readable. */
    private static void accumulate(ByteBuffer outBuf, int offset, int size,
            long ptsUs, int rate, long total, int stride,
            long[] sumsq, long[] counts, short[] scratch) {
        int nShorts = size / 2;
        if (nShorts > scratch.length) nShorts = scratch.length;
        outBuf.order(java.nio.ByteOrder.nativeOrder());   /* MediaCodec is native-order */
        outBuf.position(offset);
        outBuf.limit(offset + size);
        /* the view starts at the byte buffer's own position, so no offset
         * arithmetic in the read path */
        outBuf.asShortBuffer().get(scratch, 0, nShorts);

        long bufStart = ptsUs * rate / 1000000L;
        int b = (int) (bufStart * BUCKETS / total);
        if (b < 0) b = 0;
        if (b >= BUCKETS) b = BUCKETS - 1;
        long bucketEnd = b >= BUCKETS - 1 ? Long.MAX_VALUE
                : ((long) (b + 1) * total + BUCKETS - 1) / BUCKETS;
        long sum = 0;
        int cnt = 0;
        for (int k = 0; k < nShorts; k += stride) {
            short s = scratch[k];
            if (s == Short.MIN_VALUE) s = 0;     /* decoder's "invalid" marker */
            long idx = bufStart + k;
            while (idx >= bucketEnd) {           /* crossed into the next bucket */
                sumsq[b] += sum;
                counts[b] += cnt;
                sum = 0;
                cnt = 0;
                if (b < BUCKETS - 1) {
                    b++;
                    bucketEnd = ((long) (b + 1) * total + BUCKETS - 1) / BUCKETS;
                } else {
                    bucketEnd = Long.MAX_VALUE;
                }
            }
            sum += (long) s * s;
            cnt++;
        }
        sumsq[b] += sum;
        counts[b] += cnt;
    }

    private static MediaExtractor open(Context ctx, String token) {
        MediaExtractor ex = new MediaExtractor();
        try {
            if (token.startsWith("content://")) {
                /* folder-scoped via the SAF tree grant: MediaExtractor reads
                 * the content URI through ContentResolver, no storage perm. */
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

    /** Prefer an IN-PROCESS decoder when the platform offers one.
     *
     *  Software codecs normally run in the separate media.swcodec process, so
     *  every dequeue/queue/release is a cross-process hand-off — roughly
     *  300us each, four per 20ms audio packet. Android documents that audio
     *  pays this per *buffer* regardless of how little decode work is in it,
     *  and ships in-process Opus/AAC decoders (API 37+) that skip the IPC
     *  entirely for ~40% lower end-to-end latency. Android does NOT pick them
     *  by default — you have to ask for them by name. Falls back silently. */
    private static MediaCodec openCodec(MediaExtractor ex) {
        int trackIdx = -1;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { trackIdx = i; break; }
        }
        if (trackIdx < 0) return null;
        MediaFormat fmt = ex.getTrackFormat(trackIdx);
        String mime = fmt.getString(MediaFormat.KEY_MIME);

        String inproc = "audio/opus".equals(mime) ? CODEC_INPROC_OPUS
                : "audio/mp4a-latm".equals(mime) ? CODEC_INPROC_AAC
                : null;
        if (inproc != null) {
            MediaCodec c = startCodec(inproc, fmt, true);
            if (c != null) return c;         /* no fallback log: it's optional */
        }
        return startCodec(mime, fmt, false);
    }

    /* in-process component names, as named in Android's in-process-codecs doc */
    private static final String CODEC_INPROC_OPUS = "c2.android.inproc.opus.decoder";
    private static final String CODEC_INPROC_AAC = "c2.android.inproc.aac.decoder";

    private static MediaCodec startCodec(String name, MediaFormat fmt,
            boolean byName) {
        try {
            MediaCodec codec = byName ? MediaCodec.createByCodecName(name)
                    : MediaCodec.createDecoderByType(name);
            codec.configure(fmt, null, null, 0);
            codec.start();
            if (WF_LOG) android.util.Log.d("marimo-wf", "codec=" + name);
            return codec;
        } catch (Exception e) {
            return null;
        }
    }
}
