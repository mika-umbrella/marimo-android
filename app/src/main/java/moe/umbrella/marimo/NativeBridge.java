package moe.umbrella.marimo;

/** JNI bridge to libmarimo_core.so — the C core (tags/queue/md5/fs). */
public class NativeBridge {
    static {
        System.loadLibrary("marimo_core");
    }

    /** Runs the on-device core selftest against musicDir, returns the text. */
    public static native String runSelftest(String musicDir);

    /** Parses tags from an open fd (SAF). Consumes the fd! Returns 0 on success. */
    public static native int tagReadFd(int fd, byte[] title, byte[] artist,
                                       byte[] album, int[] dur, int[] track, int[] disc);

    /** Parses tags from a real filesystem path (app-private storage). */
    public static native int tagReadPath(String path, byte[] title, byte[] artist,
                                         byte[] album, int[] dur, int[] track, int[] disc);

    /** Embedded cover art from an fd (SAF). Consumes the fd! Returns raw
     *  image bytes (jpeg/png) or null. */
    public static native byte[] embeddedArtFd(int fd);

    /** Embedded cover art from a path (app-private storage). */
    public static native byte[] embeddedArtPath(String path);

    /** Opaque C queue handle. */
    public static native long queueNew(int shuffle, int repeat);
    public static native int queueAdd(long q, String token, String name, long size);
    public static native int queueNext(long q);
    public static native int queueLen(long q);
    public static native void queueFree(long q);
}
