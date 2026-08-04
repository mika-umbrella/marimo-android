package moe.umbrella.marimo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PICK_TREE = 7;
    private static final String PREFS = "marimo", KEY_TREE = "tree_uri";

    private final List<Object> entries = new ArrayList<>();
    private final List<Album> albums = new ArrayList<>();
    private AlbumAdapter adapter;
    private TextView status, folderName;
    private LinearLayout screenLibrary, screenPlayer;
    private TextView pTrack, pArtist, pAlbum, pTime;
    private ImageView pArt;
    private moe.umbrella.marimo.WaveformSeekBar pSeek;
    private ImageButton pPlay;
    private Button pQueueToggle;
    private ListView pQueue;
    private boolean queueVisible;
    private Uri treeUri;
    private Album openAlbum;         // null = root (albums), else its tracks
    private final Handler handler = new Handler();
    private final Runnable uiTick = new Runnable() {
        @Override public void run() {
            updatePlayerUi();
            handler.postDelayed(this, 200);
        }
    };

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
        folderName = findViewById(R.id.folder_name);
        screenLibrary = findViewById(R.id.screen_library);
        screenPlayer = findViewById(R.id.screen_player);
        pTrack = findViewById(R.id.p_track);
        pArtist = findViewById(R.id.p_artist);
        pAlbum = findViewById(R.id.p_album);
        pTime = findViewById(R.id.p_time);
        pArt = findViewById(R.id.p_art);
        pSeek = findViewById(R.id.p_seek);
        pPlay = findViewById(R.id.p_play);
        pQueueToggle = findViewById(R.id.p_queue_toggle);
        pQueue = findViewById(R.id.p_queue);
        ImageButton pShuffle = findViewById(R.id.p_shuffle);
        ImageButton pRepeat = findViewById(R.id.p_repeat);
        pShuffle.setOnClickListener(v -> {
            startService(new Intent(this, PlaybackService.class)
                    .setAction(PlaybackService.ACTION_SHUFFLE));
            updatePlayerUi();
        });
        pRepeat.setOnClickListener(v -> {
            startService(new Intent(this, PlaybackService.class)
                    .setAction(PlaybackService.ACTION_REPEAT));
            updatePlayerUi();
        });
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{
                    android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        pQueueToggle.setOnClickListener(v -> {
            queueVisible = !queueVisible;
            pQueue.setVisibility(queueVisible ? View.VISIBLE : View.GONE);
            pQueueToggle.setText(queueVisible ? "queue ▴" : "queue ▾");
            if (queueVisible) refreshQueueList();
        });
        pQueue.setOnItemClickListener((p, v, pos, id) -> {
            List<Track> q = PlaybackService.peekQueue();
            if (pos < 0 || pos >= q.size()) return;
            PlaybackService.setTracksStatic(q);
            startService(new Intent(this, PlaybackService.class)
                    .setAction(PlaybackService.ACTION_PLAY)
                    .putExtra(PlaybackService.EXTRA_POS, pos));
            showNowPlaying(q.get(pos));
        });

        adapter = new AlbumAdapter(this, entries);
        ListView list = findViewById(R.id.tracklist);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Object o = adapter.getItem(pos);
            if (o instanceof Album) openAlbum((Album) o);
            else playTrack((Track) o);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            Object o = adapter.getItem(pos);
            if (o instanceof Album) albumMenu((Album) o);
            else trackMenu((Track) o);
            return true;
        });

        findViewById(R.id.btn_settings).setOnClickListener(v -> showSettings());
        findViewById(R.id.btn_up).setOnClickListener(v -> goRoot());
        findViewById(R.id.letterbar).setOnTouchListener(null);
        ((moe.umbrella.marimo.LetterBar) findViewById(R.id.letterbar))
                .setListener(bucket -> {
                    if (openAlbum == null) {
                        int i = adapter.findFirstBucket(bucket);
                        if (i >= 0)
                            ((ListView) findViewById(R.id.tracklist))
                                    .setSelection(i);
                    }
                });

        /* player screen */
        findViewById(R.id.p_play).setOnClickListener(v ->
                startService(new Intent(this, PlaybackService.class)
                        .setAction(PlaybackService.ACTION_PLAY)));
        findViewById(R.id.p_prev).setOnClickListener(v ->
                startService(new Intent(this, PlaybackService.class)
                        .setAction(PlaybackService.ACTION_PREV)));
        findViewById(R.id.p_next).setOnClickListener(v ->
                startService(new Intent(this, PlaybackService.class)
                        .setAction(PlaybackService.ACTION_NEXT)));
        pSeek.setListener(ms -> moe.umbrella.marimo.PlaybackService.seek(ms));

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
        handler.post(uiTick);
    }

    private void showTab(boolean player) {
        screenLibrary.setVisibility(player ? View.GONE : View.VISIBLE);
        screenPlayer.setVisibility(player ? View.VISIBLE : View.GONE);
    }

    /* ---------------- library / album grouping ---------------- */

    private void addAlbum(Album a) {
        for (Album have : albums)
            if (have.folder.equals(a.folder)) return;   /* dedupe by folder */
        albums.add(a);
    }

    private void rescan() {
        new Thread(() -> {
            albums.clear();
            openAlbum = null;
            scanAppDirInto(null);
            if (treeUri != null) scanTree(DocumentFile.fromTreeUri(this, treeUri));
            Collections.sort(albums, (a, b) ->
                    a.folder.compareToIgnoreCase(b.folder));
            runOnUiThread(() -> {
                buildEntries();
                adapter.notifyDataSetChanged();
                status.setText(albums.size() + " albums · "
                        + totalTracks() + " tracks");
            });
        }).start();
    }

    private int totalTracks() {
        int n = 0;
        for (Album a : albums) n += a.tracks.size();
        return n;
    }

    private void buildEntries() {
        entries.clear();
        for (Album a : albums) entries.add(a);
    }

    private void openAlbum(Album a) {
        openAlbum = a;
        entries.clear();
        entries.addAll(a.tracks);
        folderName.setText(a.folder);
        adapter.notifyDataSetChanged();
    }

    private void goRoot() {
        openAlbum = null;
        folderName.setText("library");
        buildEntries();
        adapter.notifyDataSetChanged();
    }

    /* ---------------- scanning ---------------- */

    private void scanAppDirInto(Void unused) {
        File dir = new File(getFilesDir(), "music");
        dir.mkdirs();
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                Album a = new Album(f.getName());
                File[] ts = f.listFiles();
                if (ts != null)
                    for (File t : ts)
                        if (t.isFile() && isAudio(t.getName())) {
                            Track tr = new Track(t.getAbsolutePath(), t.getName());
                            readTags(tr);
                            if (a.coverIdx < 0 && tr.art != null) a.coverIdx = a.tracks.size();
                            a.tracks.add(tr);
                        }
                if (!a.tracks.isEmpty()) addAlbum(a);
            } else if (f.isFile() && isAudio(f.getName())) {
                Album loose = new Album("(loose files)");
                Track tr = new Track(f.getAbsolutePath(), f.getName());
                readTags(tr);
                if (loose.coverIdx < 0 && tr.art != null) loose.coverIdx = 0;
                loose.tracks.add(tr);
                addAlbum(loose);
            }
        }
    }

    private void scanTree(DocumentFile dir) {
        for (DocumentFile f : dir.listFiles()) {
            if (f.isDirectory()) {
                Album a = new Album(f.getName());
                for (DocumentFile t : f.listFiles())
                    if (t.isFile() && isAudio(t.getName())) {
                        Track tr = new Track(t.getUri().toString(), t.getName());
                        readTags(tr);
                        if (a.coverIdx < 0 && tr.art != null) a.coverIdx = a.tracks.size();
                        a.tracks.add(tr);
                    }
                if (!a.tracks.isEmpty()) addAlbum(a);
            } else if (f.isFile() && isAudio(f.getName())) {
                Album loose = new Album("(loose files)");
                Track tr = new Track(f.getUri().toString(), f.getName());
                readTags(tr);
                if (loose.coverIdx < 0 && tr.art != null) loose.coverIdx = 0;
                loose.tracks.add(tr);
                addAlbum(loose);
            }
        }
    }

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

    /* ---------------- playback ---------------- */

    private void playTrack(Track t) {
        List<Track> ctx = tracksOf(t);
        PlaybackService.setTracksStatic(new ArrayList<>(ctx));
        startService(new Intent(this, PlaybackService.class)
                .setAction(PlaybackService.ACTION_PLAY)
                .putExtra(PlaybackService.EXTRA_POS, ctx.indexOf(t)));
        showNowPlaying(t);
        showTab(true);
    }

    private List<Track> tracksOf(Track t) {
        for (Album a : albums)
            for (Track x : a.tracks)
                if (x == t) return a.tracks;
        return Collections.singletonList(t);
    }

    private void albumMenu(Album a) {
        new AlertDialog.Builder(this)
                .setTitle(a.titleLine())
                .setItems(new String[]{"play album", "add to queue", "open"}, (d, which) -> {
                    if (which == 0) {
                        PlaybackService.setTracksStatic(new ArrayList<>(a.tracks));
                        startService(new Intent(this, PlaybackService.class)
                                .setAction(PlaybackService.ACTION_PLAY)
                                .putExtra(PlaybackService.EXTRA_POS, 0));
                        showNowPlaying(a.tracks.get(0));
                        showTab(true);
                    } else if (which == 1) {
                        PlaybackService.addToQueueStatic(new ArrayList<>(a.tracks));
                        toast("queued " + a.tracks.size() + " tracks");
                    } else {
                        openAlbum(a);
                    }
                }).show();
    }

    private void trackMenu(Track t) {
        new AlertDialog.Builder(this)
                .setTitle(t.title.isEmpty() ? t.name : t.title)
                .setItems(new String[]{"play now", "add to queue"}, (d, which) -> {
                    if (which == 0) playTrack(t);
                    else {
                        List<Track> one = new ArrayList<>();
                        one.add(t);
                        PlaybackService.addToQueueStatic(one);
                        toast("queued");
                    }
                }).show();
    }

    private void showSettings() {
        new AlertDialog.Builder(this)
                .setTitle("settings")
                .setItems(new String[]{"pick music folder", "rescan",
                                "run core selftest"},
                        (d, which) -> {
                            if (which == 0) {
                                startActivityForResult(
                                        new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                                        REQ_PICK_TREE);
                            } else if (which == 1) {
                                rescan();
                            } else {
                                new Thread(() -> {
                                    String out = NativeBridge.runSelftest(
                                            new File(getFilesDir(), "music").getPath());
                                    runOnUiThread(() -> status.setText(out));
                                }).start();
                            }
                        }).show();
    }

    private void showQueue() {
        synchronized (PlaybackService.class) {
            // build a simple listing from the service's static tracks
        }
        final List<Track> q;
        try {
            q = new ArrayList<>(PlaybackService.peekQueue());
        } catch (Exception e) {
            toast("queue empty");
            return;
        }
        String[] names = new String[q.size()];
        for (int i = 0; i < q.size(); i++)
            names[i] = q.get(i).title.isEmpty() ? q.get(i).name : q.get(i).title;
        new AlertDialog.Builder(this)
                .setTitle("queue (" + q.size() + ")")
                .setItems(names, (d, which) -> {
                    PlaybackService.setTracksStatic(q);
                    startService(new Intent(this, PlaybackService.class)
                            .setAction(PlaybackService.ACTION_PLAY)
                            .putExtra(PlaybackService.EXTRA_POS, which));
                    if (which < q.size()) showNowPlaying(q.get(which));
                    showTab(true);
                }).show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /* ---------------- player screen ---------------- */

    private void showNowPlaying(Track t) {
        pTrack.setText(t.title.isEmpty() ? t.name : t.title);
        pArtist.setText(t.artist);
        pAlbum.setText(t.album);
        pArt.setImageBitmap(t.art);
        /* real waveform (async decode — never block the UI thread).
         * while it loads, the seekbar shows a flat silent baseline */
        pSeek.resetWaveform();
        new Thread(() -> {
            int[] peaks = WaveformExtractor.get(this, t.token);
            runOnUiThread(() -> pSeek.setWaveformPeaks(peaks));
        }).start();
        refreshQueueList();
    }

    private void refreshQueueList() {
        List<Track> q = PlaybackService.peekQueue();
        int cur = PlaybackService.currentIndex();
        String[] lines = new String[q.size()];
        for (int i = 0; i < q.size(); i++) {
            Track t = q.get(i);
            String title = t.title.isEmpty() ? t.name : t.title;
            String num = String.format("%02d", i + 1);
            lines[i] = (i == cur ? "▶ " : "") + num + ".  " + title
                    + (t.artist.isEmpty() ? "" : "  —  " + t.artist);
        }
        pQueue.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, lines));
    }

    private long hash(String s) {
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return h;
    }

    private void updatePlayerUi() {
        boolean playing = PlaybackService.isPlaying();
        pPlay.setImageResource(playing
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play);
        ImageButton sh = findViewById(R.id.p_shuffle);
        ImageButton rp = findViewById(R.id.p_repeat);
        if (sh != null) sh.setAlpha(PlaybackService.shuffle() == 1 ? 1f : 0.3f);
        if (rp != null) rp.setAlpha(PlaybackService.repeat() > 0 ? 1f : 0.3f);
        long pos = PlaybackService.position();
        long dur = PlaybackService.duration();
        pTime.setText(dur > 0 ? fmt(pos) + " / " + fmt(dur)
                : fmt(pos) + " / --:--");
        if (dur > 0) {
            pSeek.setMax((int) dur);
            pSeek.setProgress((int) pos);
        }
    }

    private String fmt(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }
}
