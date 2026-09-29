package moe.umbrella.marimo;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Copy the private listening diary out of filesDir into public storage.
 *
 *  The diary is app-private (see HistoryDiary) and the phone runs the release
 *  build, so `adb shell run-as` refuses and there is no root -- the only way
 *  the file leaves the device is for the app to hand a copy to someone else.
 *  This puts that copy in the public Downloads collection via MediaStore, which
 *  the file managers, MTP and the desktop sync tool can all read.
 *
 *  MediaStore owns the bytes: the app only opens a stream, so no
 *  WRITE_EXTERNAL_STORAGE and no MANAGE_EXTERNAL_STORAGE is involved. That is
 *  Q-and-later behaviour, hence the version gate.
 *
 *  The diary itself is only ever READ here -- the export never rotates,
 *  truncates or rewrites the live log, and it never touches the SAF music tree. */
public class HistoryExport {

    /** where the copies land, relative to the shared storage root */
    static final String RELATIVE_PATH = "Download/marimo/";
    /** the same destination as it reads in a message */
    static final String DEST_LABEL = "Download/marimo";

    /** the diary files currently in {@code dir}: this year's live history.jsonl
     *  plus every archived history.YYYY.jsonl.gz, archives first (name order,
     *  which puts the years before the live file) so an export is deterministic.
     *  Plain java.io, so the JVM tests drive it. */
    static List<File> diaryFiles(File dir) {
        List<File> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] archives = dir.listFiles((d, name) ->
                name.matches("history\\.\\d{4}\\.jsonl\\.gz"));
        if (archives != null) {
            Arrays.sort(archives, (a, b) -> a.getName().compareTo(b.getName()));
            for (File f : archives) if (f.isFile()) out.add(f);
        }
        File current = new File(dir, "history.jsonl");
        if (current.isFile()) out.add(current);
        return out;
    }

    /** sizes a person reads: "0 bytes" / "812 bytes" / "1.4 KB" / "2.1 MB" */
    static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + (bytes == 1 ? " byte" : " bytes");
        if (bytes < 1024L * 1024L)
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** stream copy; returns the byte count so the report can total the export */
    static long copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        long n = 0;
        int r;
        while ((r = in.read(buf)) > 0) {
            out.write(buf, 0, r);
            n += r;
        }
        return n;
    }

    /** copy every diary file into Download/marimo/ and return the line the app
     *  shows. Never throws: any failure comes back as the message text. */
    public static String export(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            return "export needs android 10 or newer";
        List<File> files = diaryFiles(ctx.getFilesDir());
        if (files.isEmpty()) return "no diary to export yet";
        ContentResolver cr = ctx.getContentResolver();
        int done = 0;
        long total = 0;
        for (File f : files) {
            try {
                total += copyOne(cr, f);
                done++;
            } catch (Exception e) {
                Log.e("marimo", "history export " + f.getName() + ": " + e);
            }
        }
        if (done == 0) return "export failed — see log";
        return "exported " + done + (done == 1 ? " file (" : " files (")
                + humanSize(total) + ") to " + DEST_LABEL;
    }

    /** insert one diary copy next to the shared collection's Download tree and
     *  stream the source into it -- insert pending, write, clear pending.
     *  Re-exporting replaces the previous copy instead of stacking
     *  "history (1).jsonl" beside it. Returns the bytes written. */
    @TargetApi(Build.VERSION_CODES.Q)
    private static long copyOne(ContentResolver cr, File src) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                        + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{RELATIVE_PATH, src.getName()});
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, src.getName());
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri dst = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (dst == null) throw new IOException("insert returned no uri");
        long n;
        try {
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = cr.openOutputStream(dst, "w")) {
                if (out == null) throw new IOException("no output stream");
                n = copyStream(in, out);
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            cr.delete(dst, null, null);   /* never leave a pending stub behind */
            throw e;
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(dst, v, null, null);
        return n;
    }
}
