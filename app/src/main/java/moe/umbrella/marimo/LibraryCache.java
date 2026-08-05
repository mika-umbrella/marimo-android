package moe.umbrella.marimo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.util.ArrayList;
import java.util.List;

/** Persists the last library scan so startup is instant: load the cached
 *  album/track list instead of re-walking the whole SAF tree + re-reading
 *  every tag from disk. The UI shows the cache immediately and rescan()
 *  refreshes it in the background.
 *
 *  Format mirrors queue.dat — line-based UTF-8, one field per line, a blank
 *  line between albums. `al=` opens an album, `t=` opens a track.
 *
 *  Album art is cached separately as downscaled JPEGs keyed by token hash
 *  (same idea as the waveform cache) so covers don't need a content:// open
 *  + full decode on every launch either. */
public class LibraryCache {

    private static final String FILE = "library.dat";
    private static final String ART_DIR = "art";

    /* ---------------- metadata cache ---------------- */

    public static void save(Context ctx, List<Album> albums) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(
                    new java.io.File(ctx.getFilesDir(), FILE),
                    java.nio.charset.StandardCharsets.UTF_8);
            for (Album a : albums) {
                if (a.tracks.isEmpty()) continue;
                w.write("al=" + a.folder + "\n");
                if (!a.artist.isEmpty()) w.write("an=" + a.artist + "\n");
                if (a.year > 0) w.write("ay=" + a.year + "\n");
                if (a.format != null) w.write("af=" + a.format + "\n");
                w.write("ac=" + a.coverIdx + "\n");
                for (Track t : a.tracks) {
                    w.write("t=" + t.token + "\n");
                    w.write("tn=" + t.name + "\n");
                    if (!t.title.isEmpty()) w.write("tti=" + t.title + "\n");
                    if (!t.artist.isEmpty()) w.write("tar=" + t.artist + "\n");
                    if (!t.album.isEmpty()) w.write("tal=" + t.album + "\n");
                    if (t.durationMs > 0) w.write("td=" + t.durationMs + "\n");
                    if (t.track > 0) w.write("ttr=" + t.track + "\n");
                    if (t.disc > 0) w.write("tdc=" + t.disc + "\n");
                }
                w.write("\n");
            }
            w.close();
        } catch (Exception e) {
            android.util.Log.e("marimo", "library save: " + e);
        }
    }

    /** loads the cached library; tracks have no art bitmap (art is loaded
     *  lazily from the art cache / SAF by loadArt). returns empty on miss. */
    public static List<Album> load(Context ctx) {
        List<Album> out = new ArrayList<>();
        java.io.File f = new java.io.File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return out;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            new java.io.FileInputStream(f),
                            java.nio.charset.StandardCharsets.UTF_8));
            Album cur = null;
            Track tr = null;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) { tr = null; continue; }
                if (line.startsWith("al=")) {
                    cur = new Album(line.substring(3));
                    out.add(cur);
                    tr = null;
                } else if (line.startsWith("t=")) {
                    if (cur == null) continue;
                    String token = line.substring(2);
                    String name = token;
                    tr = new Track(token, name);
                    cur.tracks.add(tr);
                } else if (tr != null && line.startsWith("tn=")) {
                    tr.name = line.substring(3);
                } else if (tr != null && line.startsWith("tti=")) {
                    tr.title = line.substring(4);
                } else if (tr != null && line.startsWith("tar=")) {
                    tr.artist = line.substring(4);
                } else if (tr != null && line.startsWith("tal=")) {
                    tr.album = line.substring(4);
                } else if (tr != null && line.startsWith("td=")) {
                    try { tr.durationMs = Integer.parseInt(line.substring(3)); }
                    catch (Exception ignore) { }
                } else if (tr != null && line.startsWith("ttr=")) {
                    try { tr.track = Integer.parseInt(line.substring(4)); }
                    catch (Exception ignore) { }
                } else if (tr != null && line.startsWith("tdc=")) {
                    try { tr.disc = Integer.parseInt(line.substring(4)); }
                    catch (Exception ignore) { }
                } else if (cur != null && line.startsWith("an=")) {
                    cur.artist = line.substring(3);
                } else if (cur != null && line.startsWith("ay=")) {
                    try { cur.year = Integer.parseInt(line.substring(3)); }
                    catch (Exception ignore) { }
                } else if (cur != null && line.startsWith("af=")) {
                    cur.format = line.substring(3);
                } else if (cur != null && line.startsWith("ac=")) {
                    try { cur.coverIdx = Integer.parseInt(line.substring(3)); }
                    catch (Exception ignore) { }
                }
            }
            r.close();
        } catch (Exception e) {
            android.util.Log.e("marimo", "library load: " + e);
        }
        /* drop albums whose file vanished (only checkable for plain paths) */
        List<Album> alive = new ArrayList<>();
        for (Album a : out) {
            boolean keep = true;
            for (Track t : a.tracks)
                if (!t.token.startsWith("content://")) {
                    if (!new java.io.File(t.token).exists()) { keep = false; break; }
                }
            if (keep) alive.add(a);
        }
        return alive;
    }

    /* ---------------- art cache ---------------- */

    static long tokenHash(String token) {
        long h = 1125899906842597L;
        for (int i = 0; i < token.length(); i++) h = 31 * h + token.charAt(i);
        return h;
    }

    static java.io.File artFile(Context ctx, String token) {
        java.io.File d = new java.io.File(ctx.getFilesDir(), ART_DIR);
        d.mkdirs();
        return new java.io.File(d, Long.toHexString(tokenHash(token)) + ".jpg");
    }

    /** downscaled art from disk cache (fast, no SAF round-trip). */
    public static Bitmap readArt(Context ctx, String token) {
        try {
            java.io.File f = artFile(ctx, token);
            if (!f.exists()) return null;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            return b;
        } catch (Exception e) { return null; }
    }

    /** store a downscaled JPEG so the next launch doesn't re-read + re-decode */
    public static void writeArt(Context ctx, String token, Bitmap bmp) {
        try {
            java.io.File tmp = new java.io.File(
                    ctx.getFilesDir(), ART_DIR + "/.tmp.jpg");
            try (java.io.FileOutputStream out =
                         new java.io.FileOutputStream(tmp)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out);
            }
            if (!tmp.renameTo(artFile(ctx, token))) {
                java.io.File dst = artFile(ctx, token);
                java.nio.file.Files.copy(tmp.toPath(), dst.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmp.delete();
            }
        } catch (Exception e) { }
    }
}
