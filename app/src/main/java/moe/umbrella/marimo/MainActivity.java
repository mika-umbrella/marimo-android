package moe.umbrella.marimo;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private final List<Track> tracks = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private TextView status, nowplaying;
    private Uri treeUri;

    private static final int REQ_PICK_TREE = 7;
    private static final String PREFS = "marimo", KEY_TREE = "tree_uri";

    private void grantTree(Uri uri) {
        treeUri = uri;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_TREE, uri.toString()).apply();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK_TREE || res != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        grantTree(uri);
        status.setText("scanning " + uri + " …");
        new Thread(() -> {
            scanTree(DocumentFile.fromTreeUri(this, uri));
            runOnUiThread(() -> {
                refreshList();
                status.setText(tracks.size() + " tracks indexed");
            });
        }).start();
    }

    private static boolean isAudio(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".flac") || n.endsWith(".mp3") || n.endsWith(".ogg")
                || n.endsWith(".opus") || n.endsWith(".m4a") || n.endsWith(".wav")
                || n.endsWith(".aac") || n.endsWith(".ape");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        nowplaying = findViewById(R.id.nowplaying);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);

        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE, null);
        if (saved != null) {
            try {
                Uri u = Uri.parse(saved);
                getContentResolver().takePersistableUriPermission(
                        u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                treeUri = u;
            } catch (SecurityException ignored) {
            }
        }
        ListView list = findViewById(R.id.tracklist);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < tracks.size()) {
                PlaybackService.setTracksStatic(tracks);
                Intent i = new Intent(this, PlaybackService.class)
                        .setAction(PlaybackService.ACTION_PLAY)
                        .putExtra(PlaybackService.EXTRA_POS, pos);
                startService(i);
                nowplaying.setText(tracks.get(pos).display());
            }
        });

        findViewById(R.id.btn_pick).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(i, REQ_PICK_TREE);
        });
        findViewById(R.id.btn_scan).setOnClickListener(v -> {
            tracks.clear();
            new Thread(() -> {
                scanAppDirInto(tracks);
                if (treeUri != null) scanTree(DocumentFile.fromTreeUri(this, treeUri));
                runOnUiThread(() -> {
                    refreshList();
                    status.setText(tracks.size() + " tracks indexed");
                });
            }).start();
        });
        findViewById(R.id.btn_play).setOnClickListener(v -> {
            Intent i = new Intent(this, PlaybackService.class)
                    .setAction(PlaybackService.ACTION_PLAY);
            startService(i);
        });
        findViewById(R.id.btn_prev).setOnClickListener(v ->
                startService(new Intent(this, PlaybackService.class)
                        .setAction(PlaybackService.ACTION_PREV)));
        findViewById(R.id.btn_next).setOnClickListener(v ->
                startService(new Intent(this, PlaybackService.class)
                        .setAction(PlaybackService.ACTION_NEXT)));
        findViewById(R.id.btn_test).setOnClickListener(v ->
                new Thread(() -> {
                    String out = NativeBridge.runSelftest(new File(getFilesDir(), "music").getPath());
                    runOnUiThread(() -> status.setText(out));
                }).start());

        new Thread(() -> {
            scanAppDirInto(tracks);
            runOnUiThread(() -> {
                refreshList();
                status.setText(tracks.size() + " tracks in app music dir");
            });
        }).start();
    }

    /** Scan app-private music dir (adb-pushable; no permissions needed). */
    private void scanAppDirInto(List<Track> into) {
        File dir = new File(getFilesDir(), "music");
        dir.mkdirs();
        File[] files = dir.listFiles();
        if (files != null)
            for (File f : files)
                if (f.isFile() && isAudio(f.getName())) {
                    Track t = new Track(f.getAbsolutePath(), f.getName());
                    readTags(t);
                    into.add(t);
                }
    }

    private void scanTree(DocumentFile dir) {
        for (DocumentFile f : dir.listFiles()) {
            if (f.isDirectory()) scanTree(f);
            else if (f.isFile() && isAudio(f.getName())) {
                Track t = new Track(f.getUri().toString(), f.getName());
                readTags(t);
                tracks.add(t);
            }
        }
    }

    /** Tags via the C core: fd route for SAF URIs, path route for real paths. */
    private void readTags(Track t) {
        try {
            byte[] title = new byte[512], artist = new byte[512], album = new byte[512];
            int[] dur = new int[1], tr = new int[1], dc = new int[1];
            int rc;
            if (t.token.startsWith("content://")) {
                ParcelFileDescriptor pfd =
                        getContentResolver().openFileDescriptor(Uri.parse(t.token), "r");
                if (pfd == null) return;
                try {
                    rc = NativeBridge.tagReadFd(pfd.detachFd(), title, artist, album,
                            dur, tr, dc);
                } finally {
                    pfd.close();
                }
            } else {
                rc = NativeBridge.tagReadPath(t.token, title, artist, album, dur, tr, dc);
            }
            if (rc == 0) {
                t.title = new String(title, StandardCharsets.UTF_8).trim();
                t.artist = new String(artist, StandardCharsets.UTF_8).trim();
                t.album = new String(album, StandardCharsets.UTF_8).trim();
                t.durationMs = dur[0];
                t.track = tr[0];
                t.disc = dc[0];
            }
        } catch (Exception e) {
            android.util.Log.e("marimo", "readTags failed: " + t.token, e);
        }
    }

    private void refreshList() {
        adapter.clear();
        for (Track t : tracks) adapter.add(t.display());
        adapter.notifyDataSetChanged();
    }
}
