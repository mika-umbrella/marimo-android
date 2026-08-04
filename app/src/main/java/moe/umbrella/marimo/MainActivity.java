package moe.umbrella.marimo;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PICK_TREE = 7;
    private static final String PREFS = "marimo", KEY_TREE = "tree_uri";

    private final List<Track> tracks = new ArrayList<>();
    private TrackAdapter adapter;
    private TextView status, nowplaying;
    private LinearLayout screenLibrary, screenPlayer;
    private TextView pTitle, pTrack, pArtist, pAlbum;
    private ImageView pArt;
    private Uri treeUri;

    private static boolean isAudio(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".flac") || n.endsWith(".mp3") || n.endsWith(".ogg")
                || n.endsWith(".opus") || n.endsWith(".m4a") || n.endsWith(".wav")
                || n.endsWith(".aac") || n.endsWith(".ape");
    }

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
        rescan();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        screenLibrary = findViewById(R.id.screen_library);
        screenPlayer = findViewById(R.id.screen_player);
        pTitle = findViewById(R.id.p_title);
        pTrack = findViewById(R.id.p_track);
        pArtist = findViewById(R.id.p_artist);
        pAlbum = findViewById(R.id.p_album);
        pArt = findViewById(R.id.p_art);

        adapter = new TrackAdapter();
        ListView list = findViewById(R.id.tracklist);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= tracks.size()) return;
            Track t = tracks.get(pos);
            PlaybackService.setTracksStatic(tracks);
            startService(new Intent(this, PlaybackService.class)
                    .setAction(PlaybackService.ACTION_PLAY)
                    .putExtra(PlaybackService.EXTRA_POS, pos));
            showNowPlaying(t);
            showTab(true);           /* jump to the player screen */
        });

        findViewById(R.id.btn_pick).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(i, REQ_PICK_TREE);
        });
        findViewById(R.id.btn_scan).setOnClickListener(v -> rescan());
        findViewById(R.id.btn_test).setOnClickListener(v ->
                new Thread(() -> {
                    String out = NativeBridge.runSelftest(
                            new File(getFilesDir(), "music").getPath());
                    runOnUiThread(() -> status.setText(out));
                }).start());

        /* player screen controls */
        findViewById(R.id.p_play).setOnClickListener(v ->
                startService(new Intent(this, PlaybackService.class)
                        .setAction(PlaybackService.ACTION_PLAY)));
        findViewById(R.id.p_prev).setOnClickListener(v ->
                startService(new Intent(this, PlaybackService.class)
                        .setAction(PlaybackService.ACTION_PREV)));
        findViewById(R.id.p_next).setOnClickListener(v ->
                startService(new Intent(this, PlaybackService.class)
                        .setAction(PlaybackService.ACTION_NEXT)));

        /* bottom tabs */
        findViewById(R.id.tab_library).setOnClickListener(v -> showTab(false));
        findViewById(R.id.tab_player).setOnClickListener(v -> showTab(true));

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

        rescan();
    }

    private void showTab(boolean player) {
        screenLibrary.setVisibility(player ? View.GONE : View.VISIBLE);
        screenPlayer.setVisibility(player ? View.VISIBLE : View.GONE);
    }

    private void rescan() {
        tracks.clear();
        new Thread(() -> {
            scanAppDirInto(tracks);
            if (treeUri != null) scanTree(DocumentFile.fromTreeUri(this, treeUri));
            runOnUiThread(() -> {
                adapter.notifyDataSetChanged();
                status.setText(tracks.size() + " tracks indexed");
            });
        }).start();
    }

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
            t.art = loadArt(t.token);
        } catch (Exception e) {
            android.util.Log.e("marimo", "readTags failed: " + t.token, e);
        }
    }

    /** Embedded cover art via the C core (new!). */
    private Bitmap loadArt(String token) {
        try {
            byte[] raw;
            if (token.startsWith("content://")) {
                ParcelFileDescriptor pfd =
                        getContentResolver().openFileDescriptor(Uri.parse(token), "r");
                if (pfd == null) return null;
                try {
                    raw = NativeBridge.embeddedArtFd(pfd.detachFd());
                } finally {
                    pfd.close();
                }
            } else {
                raw = NativeBridge.embeddedArtPath(token);
            }
            if (raw == null || raw.length == 0) return null;
            return BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Exception e) {
            return null;
        }
    }

    private void showNowPlaying(Track t) {
        pTrack.setText(t.title.isEmpty() ? t.name : t.title);
        pArtist.setText(t.artist);
        pAlbum.setText(t.album);
        pArt.setImageBitmap(t.art);
        pTitle.setText("marimo");
    }

    /* ---- track list adapter (bigger rows + cover thumbs) ---- */

    private class TrackAdapter extends ArrayAdapter<Track> {
        TrackAdapter() {
            super(MainActivity.this, 0, tracks);
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            if (convert == null) {
                convert = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_track, parent, false);
            }
            Track t = getItem(pos);
            ((TextView) convert.findViewById(R.id.item_title))
                    .setText(t.title.isEmpty() ? t.name : t.title);
            ((TextView) convert.findViewById(R.id.item_artist))
                    .setText(t.artist.isEmpty() ? t.name : t.artist);
            ImageView art = convert.findViewById(R.id.item_art);
            if (t.art != null) art.setImageBitmap(t.art);
            else art.setImageResource(android.R.drawable.ic_media_play);
            return convert;
        }
    }
}
