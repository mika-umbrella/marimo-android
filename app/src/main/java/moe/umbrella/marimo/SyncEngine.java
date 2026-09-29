package moe.umbrella.marimo;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One pass of "fetch what the desktop has and this phone hasn't got".
 *
 * <p>Android-aware on purpose -- SAF, JSON, HTTP -- but every decision it makes
 * lives in {@link SyncClient}, which is pure Java and covered by
 * {@link SyncClientTest}. This class only wires those decisions to storage.
 *
 * <p>Blocking; the caller runs it off the main thread.
 */
public final class SyncEngine {

    /** Deliberately not a mime type derived from the extension: some SAF providers
     *  append a canonical extension for the type they're given, which turns
     *  "01. Song.opus" into "01. Song.opus.ogg". Only the display name matters to
     *  the player, so octet-stream everywhere avoids that entirely. */
    private static final String MIME_FILE = "application/octet-stream";

    /** How long to wait for the desktop to finish converting before fetching anyway.
     *  A disc album from FLAC over a network share is not instant, but nothing
     *  should hang on it forever: what's ready is always fetchable. */
    private static final long WAIT_FOR_DESKTOP_MS = 10 * 60 * 1000L;

    public interface Listener {
        void log(String line);
        void progress(int done, int total, String what);
    }

    public static final class Result {
        public int albumsFetched, filesFetched, alreadyThere;
        public long bytes;

        public String summary() {
            if (filesFetched == 0) {
                return alreadyThere == 0 ? "nothing to do -- the phone already has everything"
                        : "already up to date (" + alreadyThere + " files checked)";
            }
            return "fetched " + filesFetched + " file(s) into " + albumsFetched
                    + " album(s), " + human(bytes);
        }

        private static String human(long n) {
            if (n >= 1 << 30) return String.format(java.util.Locale.ROOT, "%.1f GB", n / (double) (1 << 30));
            if (n >= 1 << 20) return String.format(java.util.Locale.ROOT, "%.1f MB", n / (double) (1 << 20));
            if (n >= 1 << 10) return (n >> 10) + " kB";
            return n + " B";
        }
    }

    private final Context ctx;
    private final Uri treeUri;
    private final String base;
    private final String token;

    public SyncEngine(Context ctx, Uri treeUri, String base, String token) {
        this.ctx = ctx;
        this.treeUri = treeUri;
        this.base = base;
        this.token = token;
    }

    /** List every album folder's files, across a pool.
     *
     *  This is the whole cost of a check: `listFiles()` is a provider query per
     *  folder, and 634 of them one at a time takes minutes on this phone. The
     *  scanner parallelises the same walk for the same reason. */
    private Map<String, Map<String, Long>> deviceFiles(Map<String, DocumentFile> dirs,
                                                      List<String> albums, Listener log) {
        Map<String, Map<String, Long>> out = new java.util.concurrent.ConcurrentHashMap<>();
        List<DocumentFile> present = new ArrayList<>();
        for (String album : albums) {
            DocumentFile dir = dirs.get(album);
            if (dir != null) present.add(dir);
        }
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        for (DocumentFile dir : present) {
            futures.add(pool.submit(() -> {
                out.put(dir.getName(), heldFiles(dir));
                int n = done.incrementAndGet();
                if (log != null) {
                    log.progress(n, present.size(), "reading this phone's folders… (" + n + "/"
                            + present.size() + ")");
                }
            }));
        }
        for (java.util.concurrent.Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }
        pool.shutdown();
        return out;
    }

    private static List<String> albumNames(JSONArray albums) throws Exception {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < albums.length(); i++) names.add(albums.getJSONObject(i).getString("name"));
        return names;
    }

    /** Ask the server what it has and say how many albums are missing. Read-only. */
    public int[] peek(Listener log) throws Exception {
        JSONArray albums = index();
        Map<String, DocumentFile> dirs = childDirs(tree());
        Map<String, Map<String, Long>> device = deviceFiles(dirs, albumNames(albums), log);
        int missingAlbums = 0, missingFiles = 0;
        for (int i = 0; i < albums.length(); i++) {
            JSONObject a = albums.getJSONObject(i);
            List<String> need = SyncClient.missingFiles(wantedFiles(a), device.get(a.getString("name")));
            if (!need.isEmpty()) {
                missingAlbums++;
                missingFiles += need.size();
            }
        }
        if (log != null) {
            log.log(albums.length() + " albums on the desktop, " + missingAlbums + " not fully here ("
                    + missingFiles + " file(s))");
        }

        /* Answer the desktop back. Asking what it has and never saying what we have is
         * half a conversation: its window shows the phone's last report, and a plain
         * `status` against this phone is only truthful as of the last time we spoke.
         * The walk above already knows everything needed, so this costs one POST. */
        int reported = 0;
        try {
            postInventory(inventoryOf(device));
            reported = 1;
            if (log != null) log.log("told the desktop what this phone holds");
        } catch (Exception e) {
            if (log != null) log.log("couldn't report back: " + e.getMessage());
        }
        return new int[]{missingAlbums, missingFiles, reported};
    }

    /** Fetch everything missing. Returns what it did. */
    public Result run(Listener log) throws Exception {
        JSONArray albums = index();
        DocumentFile root = tree();
        Map<String, DocumentFile> dirs = childDirs(root);
        Map<String, Map<String, Long>> device = deviceFiles(dirs, albumNames(albums), log);

        /* Tell the desktop what this phone holds *before* working out the job.
         * That report is what makes it convert anything the library hasn't got yet,
         * and waiting for it is what makes a newly built album arrive in THIS run.
         * Asking, fetching and only then reporting is why a folder that exists purely
         * as originals "failed" on the phone and needed a second sync to show up. */
        try {
            if (log != null) log.log("telling the desktop what this phone holds");
            postInventory(inventoryOf(device));
            if (waitForDesktop(log)) {
                if (log != null) log.log("re-reading what the desktop has now");
                albums = index();
            }
        } catch (Exception e) {
            if (log != null) log.log("couldn't report to the desktop: " + e.getMessage());
        }

        /* work out the whole job first, so progress means something */
        List<Object[]> jobs = new ArrayList<>();          /* {album, dir, want, missing} */
        int totalFiles = 0;
        for (int i = 0; i < albums.length(); i++) {
            JSONObject a = albums.getJSONObject(i);
            String album = a.getString("name");
            Map<String, Long> want = wantedFiles(a);
            List<String> need = SyncClient.missingFiles(want, device.get(album));
            if (!need.isEmpty()) {
                jobs.add(new Object[]{album, dirs.get(album), want, need});
                totalFiles += need.size();
            }
        }
        if (log != null) log.log(jobs.isEmpty() ? "nothing to fetch" : totalFiles + " file(s) to fetch");

        final int grandTotal = totalFiles;      /* final: the lambda below captures it */
        Result res = new Result();
        List<String> touched = new ArrayList<>();
        int done = 0;
        for (Object[] job : jobs) {
            String album = (String) job[0];
            Map<String, Long> want = mapOf(job[2]);
            List<String> need = listOf(job[3]);
            DocumentFile dir = dirs.get(album);
            if (dir == null) {
                dir = root.createDirectory(album);
                if (dir == null) {
                    if (log != null) log.log("couldn't create " + album);
                    continue;
                }
                dirs.put(album, dir);
            }
            boolean anyDone = false;
            for (String name : need) {
                done++;
                final int slot = done;             /* final: captured by the lambda */
                final String who = name;
                if (log != null) log.progress(slot, grandTotal, album + "/" + who);
                DocumentFile existing = findIn(dir, who);
                long have = existing != null ? existing.length() : 0;
                try {
                    long got = SyncClient.download(base, token, album, who, have,
                            sinkFor(dir, who, existing),
                            (at, total) -> {
                                if (log != null) log.progress(slot - 1, grandTotal, who);
                            });
                    res.bytes += Math.max(0, got - have);
                    anyDone = true;
                } catch (IOException e) {
                    if (log != null) log.log("failed: " + who + " (" + e.getMessage() + ")");
                }
            }
            if (anyDone) res.albumsFetched++;
            touched.add(album);
        }

        /* The inventory comes from the walk we already did -- re-listing 633
         * folders here, one at a time, was minutes of the same SAF queries that
         * the parallel walk had just done. Only the albums actually written to
         * need re-reading, and there are usually a handful. */
        JSONObject inventory = inventoryOf(device);
        for (String album : touched) {
            DocumentFile dir = dirs.get(album);
            if (dir != null) inventory.put(album, inventoryFor(dir));
        }
        res.alreadyThere = 0;
        if (log != null) log.log("reporting " + inventory.length() + " albums back to the desktop");
        try {
            postInventory(inventory);
            if (log != null) log.log("told the desktop what this phone now holds");
        } catch (Exception e) {
            if (log != null) log.log("couldn't report back: " + e.getMessage());
        }
        return res;
    }

    /** What this phone holds, in the shape the desktop's manifest reader expects. */
    private static JSONObject inventoryOf(Map<String, Map<String, Long>> device)
            throws Exception {
        JSONObject inventory = new JSONObject();
        for (Map.Entry<String, Map<String, Long>> e : device.entrySet()) {
            inventory.put(e.getKey(), sizesJson(e.getValue()));
        }
        return inventory;
    }

    /** Wait for the desktop to finish preparing, so what it just built can be fetched
     *  now instead of on the next sync. Polls /api/health -- the same signal the sync
     *  screen shows. Returns true if it actually waited on something. */
    private boolean waitForDesktop(Listener log) throws Exception {
        long deadline = System.currentTimeMillis() + WAIT_FOR_DESKTOP_MS;
        boolean waited = false;
        while (System.currentTimeMillis() < deadline) {
            String state = "idle";
            String album = "";
            try {
                JSONObject prep = new JSONObject(
                        SyncClient.get(base + "/api/health", token)).optJSONObject("preparing");
                if (prep != null) {
                    state = prep.optString("state", "idle");
                    album = prep.optString("album", "");
                }
            } catch (Exception e) {
                return waited;                 /* an older desktop with no health field */
            }
            if ("ready".equals(state) || "failed".equals(state) || "idle".equals(state)) {
                return waited;
            }
            waited = true;
            if (log != null) {
                log.log(album.isEmpty() ? "desktop is " + state
                                        : "desktop is " + state + ": " + album);
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return waited;
            }
        }
        if (log != null) log.log("the desktop is still preparing; fetching what's ready");
        return waited;
    }

    /** One album's entry in the shape the desktop's manifest reader expects:
     *  {"files": {name: {"size": n}}} -- not the file map itself, which was a real
     *  bug: the server counted 5 files for a 7820-file phone and a later
     *  `status --device http://…` would have believed it. */
    private static JSONObject sizesJson(Map<String, Long> files) {
        JSONObject inner = new JSONObject();
        for (Map.Entry<String, Long> f : files.entrySet()) {
            try {
                inner.put(f.getKey(), new JSONObject().put("size", f.getValue()));
            } catch (Exception ignored) {
            }
        }
        JSONObject album = new JSONObject();
        try {
            album.put("files", inner);
        } catch (Exception ignored) {
        }
        return album;
    }

    /* ------------------------------------------------------------------ */

    private JSONArray index() throws Exception {
        String json = SyncClient.get(base + "/api/index.json", token);
        return new JSONObject(json).getJSONArray("albums");
    }

    private DocumentFile tree() throws IOException {
        if (treeUri == null) throw new IOException("no music folder chosen yet");
        DocumentFile root = DocumentFile.fromTreeUri(ctx, treeUri);
        if (root == null || !root.isDirectory()) throw new IOException("the music folder is gone -- re-pick it");
        if (!root.canWrite()) throw new IOException("the music folder isn't writable -- re-pick it in settings");
        return root;
    }

    private Map<String, DocumentFile> childDirs(DocumentFile root) {
        Map<String, DocumentFile> out = new HashMap<>();
        for (DocumentFile d : root.listFiles()) {
            if (d.isDirectory()) out.put(d.getName(), d);
        }
        return out;
    }

    private static Map<String, Long> wantedFiles(JSONObject album) throws Exception {
        Map<String, Long> want = new LinkedHashMap<>();
        JSONArray files = album.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.getJSONObject(i);
            want.put(f.getString("name"), f.getLong("size"));
        }
        return want;
    }

    private static Map<String, Long> heldFiles(DocumentFile dir) {
        Map<String, Long> have = new HashMap<>();
        if (dir == null) return have;
        for (DocumentFile f : dir.listFiles()) have.put(f.getName(), f.length());
        return have;
    }

    /** Find a file by album-relative name ("[Disc 1]/01.opus"), making dirs as needed. */
    private DocumentFile findIn(DocumentFile dir, String name) {
        DocumentFile at = dir;
        String[] parts = name.split("/");
        for (int i = 0; i < parts.length; i++) {
            DocumentFile next = at.findFile(parts[i]);
            if (i == parts.length - 1) return next;
            if (next == null) return null;
            at = next;
        }
        return null;
    }

    private SyncClient.Sink sinkFor(DocumentFile dir, String name, DocumentFile existing) {
        return append -> {
            DocumentFile target = existing;
            if (target == null || !append) {
                DocumentFile at = dir;
                String[] parts = name.split("/");
                for (int i = 0; i < parts.length - 1; i++) {
                    DocumentFile next = at.findFile(parts[i]);
                    if (next == null) next = at.createDirectory(parts[i]);
                    at = next;
                }
                String leaf = parts[parts.length - 1];
                target = append && existing != null ? existing : at.findFile(leaf);
                if (target == null) target = at.createFile(MIME_FILE, leaf);
                if (target == null) throw new IOException("couldn't create " + name);
            }
            OutputStream out = ctx.getContentResolver().openOutputStream(target.getUri(), append ? "wa" : "w");
            if (out == null) throw new IOException("couldn't write " + name);
            return out;
        };
    }

    private JSONObject inventoryFor(DocumentFile dir) {
        JSONObject files = new JSONObject();
        try {
            for (DocumentFile f : dir.listFiles()) {
                if (!f.isDirectory()) files.put(f.getName(), new JSONObject().put("size", f.length()));
            }
        } catch (Exception ignored) {
        }
        JSONObject album = new JSONObject();
        try {
            album.put("files", files);
        } catch (Exception ignored) {
        }
        return album;
    }

    private void postInventory(JSONObject albums) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("version", 1);
            body.put("root", "/sdcard (via SAF)");
            body.put("albums", albums);
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
        java.net.HttpURLConnection c = SyncClient.open(base + "/api/manifest", token, null);
        byte[] payload = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setFixedLengthStreamingMode(payload.length);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = c.getOutputStream()) {
            out.write(payload);
        }
        int code = c.getResponseCode();
        c.disconnect();
        if (code != 200) throw new IOException("HTTP " + code + " reporting the inventory");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> mapOf(Object o) {
        return (Map<String, Long>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOf(Object o) {
        return (List<String>) o;
    }
}
