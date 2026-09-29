package moe.umbrella.marimo;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The diary leg of the wifi sync: send what this phone has, import what comes back.
 *
 *  The cable path ({@link HistoryExport}, {@link DiaryImport}) is untouched and still
 *  works for anyone with a cable, and its UI rows behave exactly as before; this is the
 *  same exchange over the connection the sync screen already uses, folded into the sync
 *  action so the owner has one button rather than two.
 *
 *  The merge is NOT reimplemented here. The bytes that come back are handed to
 *  {@link DiaryImport#merge} as ordinary pieces -- a downloaded file is just a
 *  {@code Piece(name, bytes)} -- so the lock against the playback service, the hidden
 *  backups, the temp-file-and-rename write and the refusals are all the ones already
 *  written and tested. The wifi path supplies the pieces where the MediaStore query
 *  used to.
 *
 *  Nothing here touches the diary unless the whole exchange succeeded: an unreachable
 *  desktop, a wrong token, a desktop with no diary endpoint, an unreadable reply or a
 *  refusal all leave the diary exactly as it was and put one line in the sync log.
 *  Blocking -- the caller runs it off the UI thread, like {@link SyncEngine}.
 */
final class DiarySync {

    interface Listener {
        void log(String line);
    }

    private DiarySync() {
    }

    /** Run the leg and return the line the sync screen shows. Never throws. */
    static String run(Context ctx, String base, String token, Listener log) {
        List<DiaryImport.Piece> mine = new ArrayList<>();
        try {
            /* the same enumeration the export uses: this year's history.jsonl plus any
             * history.YYYY.jsonl.gz beside it */
            for (File f : HistoryExport.diaryFiles(ctx.getFilesDir())) {
                mine.add(new DiaryImport.Piece(f.getName(), DiaryImport.readAll(f)));
            }
        } catch (IOException e) {
            return "diary: couldn't read this phone's diary (" + e.getMessage()
                    + ") -- nothing sent, nothing changed";
        }
        long sentLines = SyncClient.diaryLineCount(mine);
        String sent = "diary: sent " + sentLines + " lines (" + mine.size()
                + (mine.size() == 1 ? " file)" : " files)");

        SyncClient.DiaryReply reply;
        try {
            reply = send(base, token, mine);
        } catch (IOException e) {
            return sent + " -- " + e.getMessage();
        }
        if (reply == null) return sent + " -- the desktop's reply wasn't something I could read";
        if (log != null && !reply.ignored.isEmpty())
            log.log("diary: ignored " + reply.ignored + " (not diary files)");

        if (reply.files.isEmpty()) {
            return sent + (reply.after >= 0 ? ", desktop has " + reply.after + " lines" : "")
                    + ", nothing to bring back";
        }
        try {
            DiaryImport.Outcome oc = DiaryImport.merge(ctx.getFilesDir(), reply.files,
                    DiaryImport.yearOf(System.currentTimeMillis()),
                    new SimpleDateFormat(DiaryImport.STAMP_FMT, Locale.US).format(new Date()));
            String line = SyncClient.diaryLine(mine.size(), sentLines, reply.after, oc.fromImport);
            if (log != null && !reply.digest.isEmpty()) log.log("diary: desktop digest " + reply.digest);
            return line;
        } catch (DiaryImport.Refused r) {
            /* a refusal is a clean outcome: nothing was written */
            return sent + " -- import refused, " + r.getMessage() + " (nothing changed)";
        } catch (IOException e) {
            return sent + " -- import failed, " + e.getMessage();
        }
    }

    /** One POST of the whole diary, and the reply.
     *
     *  Package-visible so the wire path can be driven by the host-side contract check
     *  against a real `serve` -- the body shape, the POST itself and the error-body read
     *  are then the same code that ships, rather than a transcription of it. */
    static SyncClient.DiaryReply send(String base, String token,
                                      List<DiaryImport.Piece> mine) throws IOException {
        HttpURLConnection c = SyncClient.open(base + SyncClient.DIARY_ENDPOINT, token, null);
        try {
            byte[] body = SyncClient.diaryRequestBody(mine).getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setFixedLengthStreamingMode(body.length);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            int code = c.getResponseCode();
            if (code != 200) {
                /* The desktop puts the reason in the body -- "the diary is switched off
                 * on this desktop (serve.diary is false)", "the merge refused it: <line>"
                 * -- so carry it into the message instead of a bare status. Reading it
                 * must not be able to turn a clear status into a confusing failure, hence
                 * the swallow: the code alone is a perfectly good message. */
                String reason = null;
                try (InputStream err = c.getErrorStream()) {
                    if (err != null) reason = SyncClient.readAll(err);
                } catch (IOException ignored) {
                    /* no body, or it died on the way: the status code stands alone */
                }
                throw new IOException(SyncClient.diaryHttpError(code, reason));
            }
            try (InputStream in = c.getInputStream()) {
                return SyncClient.parseDiaryReply(SyncClient.readAll(in));
            }
        } finally {
            c.disconnect();
        }
    }
}
