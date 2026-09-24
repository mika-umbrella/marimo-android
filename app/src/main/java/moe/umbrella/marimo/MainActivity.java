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
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.HashMap;

public class MainActivity extends Activity {

    private static final int REQ_PICK_TREE = 7;
    private static final String PREFS = "marimo", KEY_TREE = "tree_uri";

    private android.widget.LinearLayout rootView, tabBar;
    private android.content.SharedPreferences prefs;
    private final List<Object> entries = new ArrayList<>();
    private android.os.HandlerThread bgThread;
    private android.os.Handler bgWorker;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable bgTick = new Runnable() {
        @Override public void run() {
            android.os.Handler w = bgWorker;   /* capture once — beat the
                stopBgLoop() race that nulled it mid-tick on real hardware */
            if (w == null) return;
            if (PlaybackService.isPlaying())
                lastPlayMs = System.currentTimeMillis();
            float t = (lastPlayMs - animStart) / 1000f;
            final android.graphics.drawable.Drawable d =
                    BgManager.gradientFor(MainActivity.this, currentArt, Theme.scrim(), t);
            ui.post(() -> { if (rootView != null) rootView.setBackground(d); });
            /* keep the loop alive; the time offset only advances while playing
             * above, so the art holds still when paused but always drifts
             * while music plays (no uiautomator tradeoff). */
            w.postDelayed(this, BG_INTERVAL_MS);
        }
    };
    private static final long BG_INTERVAL_MS = 50;
    private long animStart;
    private long lastPlayMs;
    private final List<Album> albums = new ArrayList<>();
    /* in-memory token -> Track map of the last cache load, so an incremental
     * rescan doesn't re-parse library.dat from disk every time (the crawl) */
    private final HashMap<String, Track> knownTracks = new HashMap<>();
    private AlbumAdapter adapter;
    private TextView status, folderName;
    private LinearLayout screenLibrary, screenPlayer, screenQueue, screenSettings, screenScrobble;
    private LinearLayout screenRecap, recapBody;
    private int recapMode = Recap.MODE_WEEK;
    private android.widget.EditText etLfKey, etLfSession, etLbToken;
    private TextView pTrack, pArtist, pAlbum, pTime;
    private ImageView pArt;
    private moe.umbrella.marimo.WaveformSeekBar pSeek;
    private ImageButton pPlay;
    private ListView qList;
    private int dragFrom = -1;
    private int tab = 0;
    private String shownToken = "";   /* 0 library, 1 player, 2 queue */
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
        /* crash-to-file logger: dump any uncaught exception to Downloads so
         * a non-adb tester can grab it after a crash. */
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.File f = new java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS),
                        "marimo_crash.txt");
                java.io.PrintWriter w = new java.io.PrintWriter(
                        new java.io.FileWriter(f, true));
                w.println("=== " + new java.util.Date() + " thread=" + t.getName());
                e.printStackTrace(w);
                w.close();
            } catch (Exception ignore) { }
            if (prev != null) prev.uncaughtException(t, e);
            else android.os.Process.killProcess(android.os.Process.myPid());
        });
        setContentView(R.layout.activity_main);

        rootView = findViewById(R.id.root);
        /* Android 15 (api 35) draws every app edge-to-edge whether it asks for
         * it or not, and the window stops insetting itself — so the header
         * (logo + settings button) ended up underneath the status bar and
         * couldn't be tapped. Opt in explicitly and inset the root ourselves:
         * padding moves the children clear of both bars while the art backdrop
         * keeps drawing full-bleed behind them (padding doesn't shrink a
         * View's background). Older releases already inset themselves, so
         * leave them alone rather than padding twice. */
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            final int barsMask = WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout();
            ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
                Insets bars = insets.getInsets(barsMask);
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        tabBar = findViewById(R.id.tab_bar);
        status = findViewById(R.id.status);
        folderName = findViewById(R.id.folder_name);
        screenLibrary = findViewById(R.id.screen_library);
        screenPlayer = findViewById(R.id.screen_player);
        screenQueue = findViewById(R.id.screen_queue);
        screenSettings = findViewById(R.id.screen_settings);
        screenScrobble = findViewById(R.id.screen_scrobble);
        screenRecap = findViewById(R.id.screen_recap);
        recapBody = findViewById(R.id.recap_body);
        etLfKey = findViewById(R.id.et_lf_key);
        etLfSession = findViewById(R.id.et_lf_session);
        etLbToken = findViewById(R.id.et_lb_token);
        pTrack = findViewById(R.id.p_track);
        pArtist = findViewById(R.id.p_artist);
        pAlbum = findViewById(R.id.p_album);
        pTime = findViewById(R.id.p_time);
        pArt = findViewById(R.id.p_art);
        pSeek = findViewById(R.id.p_seek);
        /* long titles scroll sideways (marquee) instead of wrapping */
        ((TextView) findViewById(R.id.p_track)).setHorizontallyScrolling(true);
        ((TextView) findViewById(R.id.p_track)).setSelected(true);
        ((TextView) findViewById(R.id.p_album)).setHorizontallyScrolling(true);
        ((TextView) findViewById(R.id.p_album)).setSelected(true);
        ((TextView) findViewById(R.id.p_artist)).setHorizontallyScrolling(true);
        ((TextView) findViewById(R.id.p_artist)).setSelected(true);
        pPlay = findViewById(R.id.p_play);
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
        /* storage is entirely folder-scoped via the SAF tree (ACTION_OPEN_DOCUMENT_TREE),
         * so no broad READ_MEDIA_* permission is requested at all. */
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{
                    android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        qList = findViewById(R.id.q_list);
        qList.setOnItemClickListener((p, v, pos, id) -> {
            List<Track> q = PlaybackService.peekQueue();
            if (pos < 0 || pos >= q.size()) return;
            PlaybackService.setTracksStatic(q);
            startService(new Intent(this, PlaybackService.class)
                    .setAction(PlaybackService.ACTION_PLAY)
                    .putExtra(PlaybackService.EXTRA_POS, pos));
            showNowPlaying(q.get(pos));
        });
        setupQueueGestures();

        /* near-square screens (Titan 2 Elite 1080x1200) drop the A-Z strip so
         * the list keeps its width. the player's cover stays: it only ever
         * gets the leftover space (layout_weight 1), so it cannot squeeze the
         * text/controls, and a short screen just shrinks it toward nothing. */
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float ratio = (float) Math.min(dm.widthPixels, dm.heightPixels)
                / Math.max(dm.widthPixels, dm.heightPixels);
        if (ratio > 0.86f) {
            findViewById(R.id.letterbar).setVisibility(View.GONE);
        }

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

        findViewById(R.id.btn_settings).setOnClickListener(v -> showTab(3));
        findViewById(R.id.btn_settings_back).setOnClickListener(v -> showTab(0));
        findViewById(R.id.btn_settings_q).setOnClickListener(v -> showTab(3));
        findViewById(R.id.set_pick).setOnClickListener(v -> {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                    REQ_PICK_TREE);
        });
        findViewById(R.id.set_rescan).setOnClickListener(v -> rescan());
        findViewById(R.id.set_scrobble).setOnClickListener(v -> showTab(4));
        findViewById(R.id.set_recap).setOnClickListener(v -> showTab(5));
        findViewById(R.id.btn_recap_back).setOnClickListener(v -> showTab(3));
        findViewById(R.id.recap_week).setOnClickListener(v -> {
            recapMode = Recap.MODE_WEEK;
            renderRecap();
        });
        findViewById(R.id.recap_month).setOnClickListener(v -> {
            recapMode = Recap.MODE_MONTH;
            renderRecap();
        });
        findViewById(R.id.recap_year).setOnClickListener(v -> {
            recapMode = Recap.MODE_YEAR;
            renderRecap();
        });
        findViewById(R.id.btn_scrobble_back).setOnClickListener(v -> showTab(3));
        findViewById(R.id.eye_lf_key).setOnClickListener(v -> toggleMask(etLfKey, findViewById(R.id.eye_lf_key)));
        findViewById(R.id.eye_lf_session).setOnClickListener(v -> toggleMask(etLfSession, findViewById(R.id.eye_lf_session)));
        findViewById(R.id.eye_lb_token).setOnClickListener(v -> toggleMask(etLbToken, findViewById(R.id.eye_lb_token)));
        findViewById(R.id.btn_scrobble_save).setOnClickListener(v -> saveScrobble());
        findViewById(R.id.btn_lf_login).setOnClickListener(v -> loginLastFm());
        findViewById(R.id.set_theme).setOnClickListener(v -> {
            Theme.dark = !Theme.dark;
            prefs.edit().putBoolean("dark", Theme.dark).apply();
            ((Button) findViewById(R.id.set_theme))
                    .setText("dark theme: " + (Theme.dark ? "on" : "off"));
            repaintBg(currentArt);
        });
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

        findViewById(R.id.tab_library).setOnClickListener(v -> showTab(0));
        findViewById(R.id.tab_player).setOnClickListener(v -> showTab(1));
        findViewById(R.id.tab_queue).setOnClickListener(v -> {
            showTab(2);
            refreshQueueList();
        });
        findViewById(R.id.tab_player).setOnClickListener(v -> showTab(1));

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Scrobbler.configure(prefs.getString("lf_key", ""),
                prefs.getString("lf_secret", ""),
                prefs.getString("lf_session", ""),
                prefs.getString("lf_user", ""),
                prefs.getString("lb_token", ""));
        applyTheme();
        startBgLoop();
        RecapScheduler.scheduleAll(this);
        requestRecapPermission();

        String saved = prefs.getString(KEY_TREE, null);
        if (saved != null) {
            try {
                Uri u = Uri.parse(saved);
                getContentResolver().takePersistableUriPermission(
                        u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                treeUri = u;
            } catch (SecurityException ignored) {
            }
        }

        /* the cache is authoritative: show it instantly and DON'T re-scan on
         * every launch — the user rescans (incrementally) only after adding
         * new files. a full scan happens only when there's no cache at all. */
        List<Album> cached = LibraryCache.load(this);
        if (!cached.isEmpty()) {
            albums.addAll(cached);
            for (Album ca : cached)
                for (Track ct : ca.tracks) knownTracks.put(ct.token, ct);
            buildEntries();
            adapter.notifyDataSetChanged();
            status.setText(albums.size() + " albums · "
                    + totalTracks() + " tracks");
            /* covers are decoded lazily on demand by the adapter, so startup
             * never holds a Bitmap per track */
        } else {
            rescan();
        }
        PlaybackService.attachQueueStorage(
                new File(getFilesDir(), "queue.dat"));
        handler.post(uiTick);
        /* queue.dat only persists text tags — load the restored tracks'
         * album art (by path) so the queue shows covers. async + non-blocking. */
        new Thread(() -> {
            List<Track> q = PlaybackService.peekQueue();
            for (Track t : q)
                if (t.art == null) t.art = loadArt(t.token, null);
            runOnUiThread(this::refreshQueueList);
        }).start();
    }

    private Bitmap currentArt;

    /** paint the art-gradient background + restyle every surface */
    private void applyTheme() {
        repaintBg(currentArt);
    }

    /** after a track's art loads, repaint the gradient from it */
    private void repaintBg(Bitmap art) {
        currentArt = art;
        /* paint one static frame now — the drift loop may be stopped (nothing
         * playing), so a theme toggle / art change must repaint immediately
         * instead of waiting for a lock/unlock recreate. */
        rootView.setBackground(BgManager.gradientFor(
                this, art, Theme.scrim(), (lastPlayMs - animStart) / 1000f));
        restyleSurfaces();
    }

    private void restyleSurfaces() {
        tabBar.setBackgroundColor(Theme.tabBar());
        android.graphics.drawable.GradientDrawable sep =
                new android.graphics.drawable.GradientDrawable();
        sep.setColor(Theme.tabSep());
        sep.setSize((int)(2 * getResources().getDisplayMetrics().density + 0.5f), 1);
        tabBar.setDividerDrawable(sep);
        tabBar.setShowDividers(android.widget.LinearLayout.SHOW_DIVIDER_MIDDLE);
        Button themeBtn = findViewById(R.id.set_theme);
        if (themeBtn != null)
            themeBtn.setText("dark theme: " + (Theme.dark ? "on" : "off"));
        Ui.tab(findViewById(R.id.tab_queue));
        Ui.tab(findViewById(R.id.tab_player));
        Ui.tab(findViewById(R.id.tab_library));
        Ui.panel(findViewById(R.id.btn_up));
        Ui.panel(findViewById(R.id.btn_settings));
        Ui.panelPress(findViewById(R.id.set_pick));
        Ui.panelPress(findViewById(R.id.set_rescan));
        Ui.panelPress(findViewById(R.id.set_scrobble));
        Ui.panelPress(findViewById(R.id.set_recap));
        Ui.panelPress(findViewById(R.id.btn_recap_back));
        Ui.panelPress(findViewById(R.id.recap_week));
        Ui.panelPress(findViewById(R.id.recap_month));
        Ui.panelPress(findViewById(R.id.recap_year));
        Ui.panelPress(findViewById(R.id.set_theme));
        Ui.panelPress(findViewById(R.id.btn_settings_back));
        Ui.tint(findViewById(R.id.btn_up), 0);
        Ui.tint(findViewById(R.id.btn_settings), 0);
        Ui.tint(findViewById(R.id.btn_settings_back), 0);
        Ui.panelPress(findViewById(R.id.btn_settings_q));
        Ui.panelPress(findViewById(R.id.btn_scrobble_back));
        Ui.panelPress(findViewById(R.id.btn_scrobble_save));
        Ui.panelPress(findViewById(R.id.btn_lf_login));
        Ui.text(findViewById(R.id.btn_lf_login), 0);
        Ui.tint(findViewById(R.id.btn_settings_q), 0);
        Ui.tint(findViewById(R.id.eye_lf_key), 0);
        Ui.tint(findViewById(R.id.eye_lf_session), 0);
        Ui.tint(findViewById(R.id.eye_lb_token), 0);
        Ui.text(findViewById(R.id.title_scrobble), 2);
        Ui.text(findViewById(R.id.folder_name), 1);
        Ui.text(findViewById(R.id.status), 1);
        Ui.text(findViewById(R.id.set_pick), 0);
        Ui.text(findViewById(R.id.set_rescan), 0);
        Ui.text(findViewById(R.id.set_scrobble), 0);
        Ui.text(findViewById(R.id.set_recap), 0);
        Ui.text(findViewById(R.id.title_recap), 2);
        Ui.tint(findViewById(R.id.btn_recap_back), 0);
        Ui.text(findViewById(R.id.set_theme), 0);
        Ui.press(findViewById(R.id.p_play));
        Ui.press(findViewById(R.id.p_prev));
        Ui.press(findViewById(R.id.p_next));
        Ui.press(findViewById(R.id.p_shuffle));
        Ui.press(findViewById(R.id.p_repeat));
        ((moe.umbrella.marimo.LetterBar) findViewById(R.id.letterbar)).applyTheme();
        ((moe.umbrella.marimo.WaveformSeekBar) findViewById(R.id.p_seek)).applyTheme();
        Ui.text(findViewById(R.id.p_track), 0);
        Ui.text(findViewById(R.id.p_artist), 2);
        Ui.text(findViewById(R.id.p_album), 1);
        Ui.text(findViewById(R.id.p_time), 1);
        Ui.tint(findViewById(R.id.p_play), 0);
        Ui.tint(findViewById(R.id.p_prev), 0);
        Ui.tint(findViewById(R.id.p_next), 0);
        Ui.tint(findViewById(R.id.p_shuffle), 0);
        Ui.tint(findViewById(R.id.p_repeat), 0);
        ((TextView) findViewById(R.id.title)).setTextColor(Theme.acc());
        ((TextView) findViewById(R.id.p_title)).setTextColor(Theme.acc());
        TextView tq = findViewById(R.id.title_queue);
        if (tq != null) tq.setTextColor(Theme.acc());
        TextView ts = findViewById(R.id.title_settings);
        if (ts != null) ts.setTextColor(Theme.acc());
        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onStart() { super.onStart(); startBgLoop(); }

    /** OS back: walk out of nested UI instead of killing the app —
     *  folder -> library, settings/scrobble -> library, else exit. */
    @Override
    public void onBackPressed() {
        if (openAlbum != null) {         /* in an album folder */
            goRoot();
            return;
        }
        if (tab == 3 || tab == 4) {       /* settings / scrobble screens */
            showTab(0);
            return;
        }
        if (tab == 5) {                    /* recap screen -> settings */
            showTab(3);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onStop() { super.onStop(); stopBgLoop(); }

    /** ask for POST_NOTIFICATIONS on 13+ (recap reset notifications) */
    private void requestRecapPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 8);
        }
    }

    private void startBgLoop() {
        if (bgWorker != null) return;
        bgThread = new android.os.HandlerThread("bgperlin");
        bgThread.start();
        bgWorker = new android.os.Handler(bgThread.getLooper());
        animStart = System.currentTimeMillis();
        bgWorker.post(bgTick);
    }

    private void stopBgLoop() {
        if (bgThread != null) {
            bgThread.quitSafely();
            bgThread = null;
            bgWorker = null;
        }
    }

    /** long-press to drag-reorder, horizontal swipe to remove */
    private void setupQueueGestures() {
        qList.setOnTouchListener(new QueueGestureListener());
    }

    /** one gesture handler for the queue: long-press-hold drags a row to
     *  reorder it IN PLACE (it stays in the list, the list reflows around it),
     *  and a horizontal swipe reveals the trash icon and removes the row. */
    private class QueueGestureListener implements View.OnTouchListener {
        private static final int NONE = 0, SWIPE = 1, REORDER = 2;
        private final int slop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
        private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        private Runnable longPress;
        private final LayerDrawable reveal;

        private View downView;
        private float downX, downY, translateX, width;
        private int downPos = -1, mode = NONE;

        QueueGestureListener() {
            GradientDrawable red = new GradientDrawable();
            red.setColor(0x33D32F2F);
            red.setCornerRadius(0);   /* rectangular, matches the row */
            reveal = new LayerDrawable(new Drawable[]{
                    red, getResources().getDrawable(R.drawable.ic_trash)});
            reveal.setLayerGravity(1, android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
            reveal.setLayerInsetRight(1, dp(14));
            reveal.setLayerInsetLeft(1, 2000);   /* keep the icon pinned to the right edge */
        }

        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    int pos = qList.pointToPosition(Math.round(e.getX()), Math.round(e.getY()));
                    if (pos < 0 || pos >= PlaybackService.peekQueue().size()) return false;
                    View child = qList.getChildAt(pos - qList.getFirstVisiblePosition());
                    if (child == null) return false;
                    downView = child; downX = e.getX(); downY = e.getY();
                    downPos = pos; translateX = 0f; mode = NONE;
                    longPress = () -> {
                        if (mode == NONE && downView != null && downPos >= 0) {
                            mode = REORDER;
                            dragFrom = downPos;
                            refreshQueueList();   /* highlights dragIndex row in place */
                            downView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                        }
                    };
                    h.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return false;   /* let tap/scroll work unless we take over */
                }
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    if (mode == REORDER) {
                        int over = qList.pointToPosition(Math.round(e.getX()), Math.round(e.getY()));
                        int count = PlaybackService.peekQueue().size();
                        if (over >= 0 && over < count && over != dragFrom) {
                            PlaybackService.reorderQueue(dragFrom, over);
                            dragFrom = over;
                            refreshQueueList();
                        }
                        return true;
                    }
                    if (mode == NONE) {
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop)
                            h.removeCallbacks(longPress);
                        if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy))
                            mode = SWIPE;          /* horizontal → remove */
                        else if (Math.abs(dy) > slop)
                            return false;          /* vertical → let ListView scroll */
                    }
                    if (mode == SWIPE && downView != null) {
                        width = downView.getWidth();
                        translateX = Math.max(-width, Math.min(0f, dx));
                        downView.setTranslationX(translateX);
                        downView.setAlpha(1f - 0.5f * (Math.abs(translateX) / width));
                        if (downView.getBackground() != reveal)
                            downView.setBackground(reveal);
                        return true;
                    }
                    return false;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    h.removeCallbacks(longPress);
                    if (mode == REORDER) {
                        dragFrom = -1;
                        refreshQueueList();   /* final state already persisted */
                    } else if (mode == SWIPE && downView != null) {
                        final View tv = downView;
                        final int pos = downPos;
                        if (Math.abs(translateX) > width * 0.4f) {
                            tv.animate().translationX(-width).alpha(0f)
                                    .setDuration(180).withEndAction(() -> {
                                        PlaybackService.removeFromQueue(pos);
                                        refreshQueueList();
                                    }).start();
                        } else {
                            tv.animate().translationX(0f).alpha(1f)
                                    .setDuration(150).start();
                            tv.setBackground(null);
                        }
                    }
                    boolean handled = mode == SWIPE || mode == REORDER;
                    mode = NONE; downView = null; downPos = -1;
                    return handled;
                }
                default: return false;
            }
        }

        private int dp(float d) {
            return (int) (d * getResources().getDisplayMetrics().density + 0.5f);
        }
    }

    private void toggleMask(android.widget.EditText et, ImageButton eye) {
        boolean masked = et.getTransformationMethod()
                instanceof android.text.method.PasswordTransformationMethod;
        et.setTransformationMethod(masked ? null
                : android.text.method.PasswordTransformationMethod.getInstance());
        et.setSelection(et.getText().length());
    }

    private void populateScrobble() {
        String[] cur = Scrobbler.current();
        etLfKey.setText(cur[0]);
        etLfSession.setText(cur[2]);
        etLbToken.setText(cur[4]);
        /* what's configured + what each service last replied — on a device with
         * no adb this is the only way the user can see why a scrobble failed */
        TextView st = findViewById(R.id.tv_scrobble_status);
        if (st != null) st.setText(Scrobbler.status());
    }

    /** interactive last.fm login: token -> browser -> poll getSession.
     *  needs the api key set; the session key is fetched automatically. */
    private void loginLastFm() {
        String key = etLfKey.getText().toString().trim();
        if (key.isEmpty()) key = Scrobbler.current()[0];   /* seeded/configured key */
        if (key.isEmpty() || Scrobbler.current()[1].isEmpty()) {
            toast("enter your last.fm api key + secret first");
            return;
        }
        final android.widget.Button btn = findViewById(R.id.btn_lf_login);
        btn.setEnabled(false);
        btn.setText("opening last.fm…");
        new Thread(() -> {
            final String token = Scrobbler.requestToken();
            if (token == null) {
                runOnUiThread(() -> { toast("couldn't reach last.fm"); btn.setEnabled(true); btn.setText("log in with last.fm"); });
                return;
            }
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse(Scrobbler.authUrl(token))));
                } catch (Exception e) { }
                btn.setText("approve it in the browser…");
            });
            /* last.fm gives no callback URL, so poll getSession until the
             * user approves (token becomes authorized), ~90s cap */
            for (int i = 0; i < 45; i++) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                final String[] s = Scrobbler.finishAuth(token);
                if (s != null) {
                    final String user = s[0];
                    runOnUiThread(() -> {
                        etLfSession.setText(Scrobbler.current()[2]);
                        saveScrobble();
                        btn.setEnabled(true);
                        btn.setText("linked to last.fm as " + user + " ✓");
                        toast("linked to last.fm as " + user);
                    });
                    return;
                }
            }
            runOnUiThread(() -> {
                btn.setEnabled(true);
                btn.setText("log in with last.fm");
                toast("login timed out — try again");
            });
        }).start();
    }

    private void saveScrobble() {
        String[] cur = Scrobbler.current();
        String k = etLfKey.getText().toString().trim();
        String s = etLfSession.getText().toString().trim();
        String t = etLbToken.getText().toString().trim();
        Scrobbler.configure(k, cur[1], s, cur[3], t);
        prefs.edit().putString("lf_key", k).putString("lf_session", s)
                .putString("lb_token", t).apply();
        TextView st = findViewById(R.id.tv_scrobble_status);
        if (st != null) st.setText(Scrobbler.status());
        toast(Scrobbler.hasLf() || Scrobbler.hasLb()
                ? "scrobble saved" : "no credentials — scrobbling off");
    }

    private void showTab(int t) {
        tab = t;
        screenLibrary.setVisibility(t == 0 ? View.VISIBLE : View.GONE);
        screenPlayer.setVisibility(t == 1 ? View.VISIBLE : View.GONE);
        screenQueue.setVisibility(t == 2 ? View.VISIBLE : View.GONE);
        screenSettings.setVisibility(t == 3 ? View.VISIBLE : View.GONE);
        screenScrobble.setVisibility(t == 4 ? View.VISIBLE : View.GONE);
        screenRecap.setVisibility(t == 5 ? View.VISIBLE : View.GONE);
        if (t == 4) populateScrobble();
        if (t == 5) renderRecap();
        if (t == 2) refreshQueueList();
    }

    /* ============================ recap screen ============================ */

    private void renderRecap() {
        HistoryDiary.configure(this);   /* dir may not be set if the service never ran */
        long[] w = Recap.windows(recapMode, System.currentTimeMillis());
        List<HistoryDiary.Entry> cur = HistoryDiary.forWindow(w[0], w[1]);
        List<HistoryDiary.Entry> prev = HistoryDiary.forWindow(w[2], w[3]);
        Recap.Result r = Recap.compute(cur, prev, recapMode);

        String title = recapMode == Recap.MODE_WEEK ? "your week in marimo"
                : recapMode == Recap.MODE_MONTH ? "your month in marimo"
                : "your " + fmtYear(w[0]) + " in marimo";
        ((TextView) findViewById(R.id.title_recap)).setText(title);
        stylePeriodButtons();

        recapBody.removeAllViews();
        addSectionLabel(fmtPeriod(recapMode, w));
        addStatRow(r);
        addListeningBehaviour(r);
        addDeltaLine(r);
        addBars(r);
        if (r.empty) {
            TextView e = mkText("not enough plays to recap yet — go listen to something ♪",
                    Theme.sub(), 13, 0);
            e.setGravity(android.view.Gravity.CENTER);
            e.setPadding(0, dp(28), 0, 0);
            recapBody.addView(e);
            return;
        }
        addTopN(KIND_ARTIST, "top artists", r.topArtists);
        addTopN(KIND_ALBUM, "top albums", r.topAlbums);
        addTopN(KIND_TRACK, "top tracks", r.topTracks);
        addShare(r, w);
    }

    private void stylePeriodButtons() {
        android.widget.Button wk = findViewById(R.id.recap_week);
        android.widget.Button mo = findViewById(R.id.recap_month);
        android.widget.Button yr = findViewById(R.id.recap_year);
        stylePeriodBtn(wk, recapMode == Recap.MODE_WEEK);
        stylePeriodBtn(mo, recapMode == Recap.MODE_MONTH);
        stylePeriodBtn(yr, recapMode == Recap.MODE_YEAR);
    }

    private void stylePeriodBtn(android.widget.Button b, boolean active) {
        b.setTextColor(active ? Theme.panel() : Theme.sub());
        b.setBackgroundColor(active ? Theme.acc() : Theme.panel());
    }

    private void addSectionLabel(String s) {
        TextView tv = mkText(s, Theme.sub(), 12, android.graphics.Typeface.BOLD);
        tv.setLetterSpacing(0.08f);
        tv.setPadding(dp(4), dp(14), dp(4), dp(6));
        recapBody.addView(tv);
    }

    private void addStatRow(Recap.Result r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(statCell(fmtHours(r.totalSec), "hours"));
        row.addView(statCell(String.valueOf(r.tracks), "tracks"));
        row.addView(statCell(String.valueOf(r.artists), "artists"));
        row.addView(statCell(String.valueOf(r.albums), "albums"));
        recapBody.addView(row);
    }

    /** one stat in its own rounded cell (spaced from its siblings) */
    private LinearLayout statCell(String val, String lab) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(android.view.Gravity.CENTER);
        cell.setBackgroundColor(Theme.panel());
        cell.setPadding(dp(6), dp(12), dp(6), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        cell.setLayoutParams(lp);
        TextView v = mkText(val, Theme.acc(), 17, android.graphics.Typeface.BOLD);
        TextView l = mkText(lab, Theme.sub(), 11, 0);
        cell.addView(v);
        cell.addView(l);
        return cell;
    }

    private void addDeltaLine(Recap.Result r) {
        long dur = r.hours - r.hoursPrev, dt = r.tracks - r.tracksPrev;
        if (dur == 0 && dt == 0) return;
        String prevLab = recapMode == Recap.MODE_WEEK ? "vs last week"
                : recapMode == Recap.MODE_MONTH ? "vs last month" : "vs last year";
        StringBuilder sb = new StringBuilder(prevLab + "  ");
        appendDelta(sb, dt, "tracks");
        sb.append(" · ");
        appendDelta(sb, dur, "hours");
        TextView tv = mkText(sb.toString(), Theme.sub(), 12, 0);
        tv.setPadding(dp(4), dp(8), dp(4), dp(4));
        recapBody.addView(tv);
    }

    private void appendDelta(StringBuilder sb, long d, String unit) {
        if (d > 0) sb.append("▲ +").append(d).append(' ').append(unit);
        else if (d < 0) sb.append("▼ −").append(-d).append(' ').append(unit);
        else sb.append("— ").append(unit);
    }

    private void addBars(Recap.Result r) {
        long[] bars = r.bars;
        if (bars == null || bars.length == 0) return;
        long max = 1;
        for (long b : bars) if (b > max) max = b;
        addSectionLabel("plays by " + (recapMode == Recap.MODE_WEEK ? "day"
                : recapMode == Recap.MODE_MONTH ? "week" : "month"));
        String[] labels = recapMode == Recap.MODE_WEEK
                ? new String[]{"Mo","Tu","We","Th","Fr","Sa","Su"}
                : recapMode == Recap.MODE_MONTH
                ? new String[]{"wk1","wk2","wk3","wk4","wk5"}
                : new String[]{"J","F","M","A","M","J","J","A","S","O","N","D"};
        for (int i = 0; i < bars.length; i++)
            addBar(labels[i], (float) bars[i] / max, Theme.acc());
    }

    private void addBar(String label, float frac, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout cont = new LinearLayout(this);
        cont.setOrientation(LinearLayout.HORIZONTAL);
        cont.setBackgroundColor(Theme.rowBg());
        View fill = new View(this);
        fill.setBackgroundColor(color);
        View gap = new View(this);
        cont.addView(fill, new LinearLayout.LayoutParams(0, dp(6),
                Math.max(0.02f, frac)));
        cont.addView(gap, new LinearLayout.LayoutParams(0, dp(6),
                Math.max(0.02f, 1f - frac)));
        TextView lab = mkText(label, Theme.sub(), 11, 0);
        lab.setWidth(dp(26));
        row.addView(lab);
        row.addView(cont, new LinearLayout.LayoutParams(0, dp(6), 1));
        recapBody.addView(row);
    }

    private static final int KIND_ARTIST = 0, KIND_ALBUM = 1, KIND_TRACK = 2;
    /** in-memory art cache for recap rows: "kind:name" -> bitmap */
    private final java.util.Map<String, Bitmap> recapArt = new java.util.HashMap<>();
    /** in-memory last.fm artist-photo cache: artist -> bitmap */
    private static final java.util.Map<String, Bitmap> lfArtistArt = new java.util.HashMap<>();
    private static final java.util.Set<String> lfArtistFetching =
            new java.util.HashSet<>();

    private void addTopN(int kind, String header, List<Recap.Row> rows) {
        if (rows == null || rows.isEmpty()) return;
        addSectionLabel(header);
        long max = 1;
        for (Recap.Row r : rows) if (r.count > max) max = r.count;
        int rank = 1;
        for (Recap.Row r : rows) {
            ImageView art = addTopNRow(r, rank++, max, artFor(kind, r.name));
            if (kind == KIND_ARTIST && art != null)
                fetchArtistArt(art, r.name);
        }
    }

    /** build one top-N row with a square art thumbnail; returns the view the
     *  last.fm artist-photo loader can later populate (or null for non-art). */
    private ImageView addTopNRow(Recap.Row r, int rank, long max, Bitmap art) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.panel());
        card.setPadding(dp(10), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        card.setLayoutParams(lp);

        LinearLayout l1 = new LinearLayout(this);
        l1.setOrientation(LinearLayout.HORIZONTAL);
        l1.setGravity(android.view.Gravity.CENTER_VERTICAL);

        ImageView artView = new ImageView(this);
        artView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int size = dp(48);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(size, size);
        alp.setMargins(0, 0, dp(10), 0);
        artView.setLayoutParams(alp);
        if (art != null) {
            artView.setImageBitmap(art);
        } else {
            artView.setImageBitmap(placeholderBitmap(r.name));
        }
        l1.addView(artView);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        l1.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView tv = mkText(rank + ". " + r.name, Theme.txt(), 13,
                android.graphics.Typeface.BOLD);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(tv);
        TextView cnt = mkText(r.count + (r.count == 1 ? " play" : " plays"),
                Theme.dim(), 11, 0);
        col.addView(cnt);

        TextView rt = mkText(String.valueOf(r.count), Theme.acc(), 12,
                android.graphics.Typeface.BOLD);
        l1.addView(rt);
        card.addView(l1);

        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackgroundColor(Theme.rowBg());
        View fill = new View(this);
        float frac = max > 0 ? (float) r.count / max : 0f;
        fill.setBackgroundColor(Theme.acc());
        View gap = new View(this);
        track.addView(fill, new LinearLayout.LayoutParams(0, dp(4),
                Math.max(0.02f, frac)));
        track.addView(gap, new LinearLayout.LayoutParams(0, dp(4),
                Math.max(0.02f, 1f - frac)));
        card.addView(track, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4)));
        recapBody.addView(card);
        return artView;
    }

    /** resolve a row's art from a representative library track (cached); the
     *  recap aggregator only has names, so we match the first known track. */
    private Bitmap artFor(int kind, String name) {
        if (name == null || name.isEmpty()) return null;
        String key = kind + ":" + name;
        synchronized (recapArt) {
            if (recapArt.containsKey(key)) return recapArt.get(key);
        }
        Bitmap b = null;
        synchronized (knownTracks) {
            for (Track t : knownTracks.values()) {
                String f = kind == KIND_ARTIST ? t.artist
                        : kind == KIND_ALBUM ? t.album
                        : (t.title.isEmpty() ? t.name : t.title);
                if (name.equals(f)) { b = loadArt(t.token, null); break; }
            }
        }
        synchronized (recapArt) { recapArt.put(key, b); }
        return b;
    }

    /** a soft placeholder square (panel bg + the leading char) when no art */
    private Bitmap placeholderBitmap(String name) {
        int size = dp(48);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        c.drawColor(Theme.rowBg());
        String ch = (name == null || name.isEmpty()) ? "?" : name.substring(0, 1);
        android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(Theme.dim());
        p.setTextSize(size * 0.5f);
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        android.graphics.Rect r = new android.graphics.Rect();
        p.getTextBounds(ch, 0, ch.length(), r);
        float y = (size + r.height()) / 2f;
        c.drawText(ch, size / 2f, y, p);
        return bmp;
    }

    /** async last.fm artist photo, populates the row's thumbnail when it lands */
    private void fetchArtistArt(final ImageView artView, final String artist) {
        if (artist == null || artist.isEmpty()) return;
        synchronized (lfArtistArt) {
            if (lfArtistArt.containsKey(artist)) {
                artView.setImageBitmap(lfArtistArt.get(artist));
                return;
            }
        }
        synchronized (lfArtistFetching) {
            if (lfArtistFetching.contains(artist)) return;   // in flight
            lfArtistFetching.add(artist);
        }
        new Thread(() -> {
            Bitmap b = lastFmArtistImage(artist);
            synchronized (lfArtistFetching) { lfArtistFetching.remove(artist); }
            if (b != null) {
                synchronized (lfArtistArt) { lfArtistArt.put(artist, b); }
                runOnUiThread(() -> artView.setImageBitmap(b));
            }
        }, "lf-art-" + artist).start();
    }

    /** real last.fm artist photo: artist.getinfo only returns a generic
     *  placeholder, so scrape the artist page's og:image (a static jpg — the
     *  page's own avatars are gifs Android can't decode). */
    private static final String LF_PAGE = "https://www.last.fm/music/";

    private Bitmap lastFmArtistImage(String artist) {
        try {
            String slug = java.net.URLEncoder.encode(artist, "UTF-8")
                    .replace("+", "%20");
            String page = httpGet(LF_PAGE + slug);
            if (page == null) return null;
            java.util.regex.Matcher mt = java.util.regex.Pattern
                    .compile("<meta[^>]*property=\"og:image\"[^>]*>")
                    .matcher(page);
            if (!mt.find()) return null;
            java.util.regex.Matcher cm = java.util.regex.Pattern
                    .compile("content=\"([^\"]+)\"").matcher(mt.group());
            if (!cm.find()) return null;
            String img = cm.group(1).trim();
            if (img.isEmpty() || img.contains("2a96cbd8")) return null;
            byte[] data = httpGetBytes(img);
            if (data == null || data.length == 0) return null;
            return decodeScaled(data, 300);
        } catch (Exception e) {
            return null;
        }
    }

    private String httpGet(String url) throws Exception {
        byte[] d = httpGetBytes(url);
        return d == null ? null : new String(d, StandardCharsets.UTF_8);
    }

    private byte[] httpGetBytes(String url) throws Exception {
        java.net.HttpURLConnection c =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "marimo-android/1.2.3");
        int code = c.getResponseCode();
        if (code != 200) { c.disconnect(); return null; }
        try (java.io.InputStream is = c.getInputStream()) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private void addListeningBehaviour(Recap.Result r) {
        if (r.tracks == 0) return;
        addSectionLabel("listening behaviour");
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.panel());
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(clp);

        addBehaviourRow(card, "mostly " + Recap.aAn(r.persona),
                r.persona, Theme.acc());
        if (r.streakDays > 0)
            addBehaviourRow(card, "streak", r.streakDays + " days in a row", 0);
        if (r.longestSessionSec > 0)
            addBehaviourRow(card, "longest session", fmtHours(r.longestSessionSec), 0);
        addBehaviourRow(card, "skipped",
                r.skipRate + "% of starts", 0);
        if (r.replayKingPlays >= 2)
            addBehaviourRow(card, "looped", "“" + r.replayKing + "” " + r.replayKingPlays + "×", Theme.acc());
        if (r.mostSkippedPlays > 0)
            addBehaviourRow(card, "most-skipped", "“" + r.mostSkipped + "”", Theme.dim());
        addBehaviourRow(card, "discovery",
                r.discoveryPct + "% new artists", 0);
        recapBody.addView(card);
    }

    private void addBehaviourRow(LinearLayout card, String label, String value, int valueColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView l = mkText(label, Theme.dim(), 12, 0);
        l.setPadding(0, dp(4), 0, dp(4));
        row.addView(l, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView v = mkText(value, valueColor == 0 ? Theme.txt() : valueColor,
                12, android.graphics.Typeface.BOLD);
        row.addView(v);
        card.addView(row);
    }

    private void addShare(Recap.Result r, long[] w) {
        android.widget.Button share = new android.widget.Button(this);
        share.setText("share");
        share.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(10);
        share.setLayoutParams(lp);
        share.setOnClickListener(v -> {
            String card = Recap.shareCard(recapMode, w, r);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, card);
            startActivity(Intent.createChooser(i, "share your recap"));
        });
        Ui.press(share);
        recapBody.addView(share);
    }

    private TextView mkText(String s, int color, float sp, int style) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(color);
        tv.setTextSize(sp);
        tv.setTypeface(null, style);
        return tv;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String fmtHours(long totalSeconds) {
        long h = totalSeconds / 3600, m = (totalSeconds % 3600) / 60;
        if (h == 0) return m + "m";
        return h + "h " + String.format("%02dm", m);
    }

    private String fmtPeriod(int mode, long[] w) {
        java.text.SimpleDateFormat d = new java.text.SimpleDateFormat(
                "MMM d", java.util.Locale.US);
        java.text.SimpleDateFormat my = new java.text.SimpleDateFormat(
                "MMMM yyyy", java.util.Locale.US);
        if (mode == Recap.MODE_WEEK)
            return d.format(new java.util.Date(w[0])) + " – "
                    + d.format(new java.util.Date(w[1] - 1));
        if (mode == Recap.MODE_MONTH)
            return my.format(new java.util.Date(w[0]));
        return fmtYear(w[0]);
    }

    /** the calendar year of an epoch ms */
    private String fmtYear(long ms) {
        return new java.text.SimpleDateFormat("yyyy", java.util.Locale.US)
                .format(new java.util.Date(ms));
    }

    /* ---------------- library / album grouping ---------------- */

    private void addAlbum(Album a) {
        for (Album have : albums)
            if (have.folder.equals(a.folder)) return;   /* dedupe by folder */
        albums.add(a);
    }

    private void rescan() {
        new Thread(() -> {
            /* incremental: remember what we already know so rescans only read
             * tags/art for genuinely new tracks — built in memory (no disk
             * re-parse of library.dat), so the crawl stays fast. */
            HashMap<String, Track> known;
            synchronized (knownTracks) { known = new HashMap<>(knownTracks); }

            albums.clear();
            openAlbum = null;
            List<Album> found = new ArrayList<>();
            HashMap<Track, DocumentFile> dirs = new HashMap<>();

            ExecutorService pool = Executors.newFixedThreadPool(
                    Math.max(2, Runtime.getRuntime().availableProcessors()));
            List<Future<?>> futs = new ArrayList<>();

            /* the SAF folder walk (listFiles() per folder) is the real crawl —
             * run each folder's listing in parallel across the pool. folders
             * are independent, so results merge under a lock. */
            scanAppDirCollect(found);
            if (treeUri != null) {
                final DocumentFile rootDir =
                        DocumentFile.fromTreeUri(this, treeUri);
                final Object mergeLock = new Object();
                for (DocumentFile f : rootDir.listFiles()) {
                    futs.add(pool.submit(() -> {
                        List<Album> per = new ArrayList<>();
                        HashMap<Track, DocumentFile> pdirs = new HashMap<>();
                        collectTreeFolder(f, rootDir, per, pdirs);
                        synchronized (mergeLock) {
                            found.addAll(per);
                            dirs.putAll(pdirs);
                        }
                        return null;
                    }));
                }
            }
            /* wait for the folder walk to finish so `found` is complete */
            for (Future<?> f : futs)
                try { f.get(); } catch (Exception ignore) { }
            futs.clear();

            final int totalAlbums = found.size();
            runOnUiThread(() -> {
                FrameLayout layer = findViewById(R.id.scan_progress_layer);
                if (layer != null) layer.setVisibility(View.VISIBLE);
                CircleProgress circle = findViewById(R.id.scan_circle);
                if (circle != null) circle.setProgress(0, totalAlbums, "0 / " + totalAlbums);
            });

            java.util.concurrent.atomic.AtomicInteger albumDone =
                    new java.util.concurrent.atomic.AtomicInteger();
            for (Album a : found) {
                final int nTracks = a.tracks.size();
                final java.util.concurrent.atomic.AtomicInteger remaining =
                        new java.util.concurrent.atomic.AtomicInteger(nTracks);
                for (Track t : a.tracks) {
                    final Track ft = t;
                    futs.add(pool.submit(() -> {
                        /* reuse cached metadata when we've already scanned this
                         * token — only fresh files pay for a real tag read */
                        Track prior = known.get(ft.token);
                        if (prior != null
                                && (!prior.title.isEmpty() || !prior.name.isEmpty())) {
                            ft.title = prior.title;
                            ft.artist = prior.artist;
                            ft.album = prior.album;
                            ft.durationMs = prior.durationMs;
                            ft.track = prior.track;
                            ft.disc = prior.disc;
                            ft.hasArt = prior.hasArt
                                    || LibraryCache.hasArtOnDisk(
                                            MainActivity.this, ft.token);
                        } else {
                            readTags(ft, dirs.get(ft));
                        }
                        if (remaining.decrementAndGet() == 0) {
                            int d = albumDone.incrementAndGet();
                            runOnUiThread(() -> {
                                CircleProgress circle = findViewById(R.id.scan_circle);
                                if (circle != null)
                                    circle.setProgress(d, totalAlbums,
                                            d + " / " + totalAlbums);
                            });
                        }
                    }));
                }
            }
            for (Future<?> f : futs)
                try { f.get(); } catch (Exception ignore) { }

            /* pre-warm the album-cover art cache (bounded pool, bitmap is
             * discarded after write) so navigating back to the library later
             * hits the disk cache instead of re-decoding from SAF on the spot */
            for (Album a : found) {
                int coverIdx = -1;
                for (int i = 0; i < a.tracks.size(); i++)
                    if (a.tracks.get(i).hasArt) { coverIdx = i; break; }
                a.coverIdx = coverIdx;
                sortAlbumTracks(a);
                if (coverIdx < 0 || a.tracks.isEmpty()) continue;
                final Track coverTrack = a.tracks.get(coverIdx);
                final DocumentFile adoc = (DocumentFile) a.albumDoc;
                futs.add(pool.submit(() -> {
                    /* decode + write to disk cache; the bitmap is discarded so
                     * at most pool-size covers are in memory at once */
                    if (LibraryCache.readArt(MainActivity.this, coverTrack.token) != null)
                        return;
                    loadArt(coverTrack.token, adoc);
                    coverTrack.art = null;   /* don't hold it; navigation re-reads cache */
                }));
            }
            for (Future<?> f : futs)
                try { f.get(); } catch (Exception ignore) { }
            pool.shutdown();
            runOnUiThread(() -> {
                FrameLayout layer = findViewById(R.id.scan_progress_layer);
                if (layer != null) layer.setVisibility(View.GONE);
            });

            for (Album a : found) {
                a.resolvedCover = false;
                addAlbum(a);
            }
            Collections.sort(albums, (a, b) ->
                    a.folder.compareToIgnoreCase(b.folder));
            /* refresh the in-memory map so the next incremental rescan reuses
             * what we just scanned (incl. brand-new tracks) — no disk re-read */
            synchronized (knownTracks) {
                knownTracks.clear();
                for (Album a : albums)
                    for (Track t : a.tracks) knownTracks.put(t.token, t);
            }
            LibraryCache.save(this, albums);
            runOnUiThread(() -> {
                buildEntries();
                adapter.notifyDataSetChanged();
                status.setText(albums.size() + " albums · "
                        + totalTracks() + " tracks");
            });
        }).start();
    }

    /** order album tracks by (disc, trackNo); fall back to the leading
     *  digits of the filename when the tag has no track number (keeps the
     *  scan order from listFiles() from being arbitrary). */
    private void sortAlbumTracks(Album a) {
        java.util.Collections.sort(a.tracks, (x, y) -> Integer.compare(key(x), key(y)));
    }

    private int key(Track t) {
        int d = t.disc > 0 ? t.disc : 1;
        int n = t.track;
        if (n <= 0) {
            String s = t.name.trim();
            int i = 0;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i == 0) return Integer.MAX_VALUE;   /* no number -> end */
            try { n = Integer.parseInt(s.substring(0, i)); }
            catch (Exception e) { n = Integer.MAX_VALUE; }
        }
        return d * 10000 + n;
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

    private void scanAppDirCollect(List<Album> found) {
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
                            a.tracks.add(tr);
                        }
                if (!a.tracks.isEmpty()) found.add(a);
            } else if (f.isFile() && isAudio(f.getName())) {
                Album loose = new Album("(loose files)");
                Track tr = new Track(f.getAbsolutePath(), f.getName());
                loose.tracks.add(tr);
                found.add(loose);
            }
        }
    }

    /** list one tree folder (album dir or loose file) into per-thread locals.
     *  called from the parallel walk; caller merges under a lock. */
    private void collectTreeFolder(DocumentFile f, DocumentFile rootDir,
                                   List<Album> per,
                                   HashMap<Track, DocumentFile> pdirs) {
        if (f.isDirectory()) {
            Album a = new Album(f.getName());
            a.albumDoc = f;
            for (DocumentFile t : f.listFiles())
                if (t.isFile() && isAudio(t.getName())) {
                    Track tr = new Track(t.getUri().toString(), t.getName());
                    pdirs.put(tr, f);
                    a.tracks.add(tr);
                }
            if (!a.tracks.isEmpty()) per.add(a);
        } else if (f.isFile() && isAudio(f.getName())) {
            Album loose = new Album("(loose files)");
            Track tr = new Track(f.getUri().toString(), f.getName());
            pdirs.put(tr, rootDir);
            loose.tracks.add(tr);
            per.add(loose);
        }
    }

    private void readTags(Track t, DocumentFile albumDir) {
        try {
            byte[] title = new byte[512], artist = new byte[512], album = new byte[512];
            int[] dur = new int[1], tr = new int[1], dc = new int[1];
            int rc = -1;
            if (t.token.startsWith("content://")) {
                ParcelFileDescriptor pfd = null;
                try {
                    pfd = getContentResolver()
                            .openFileDescriptor(Uri.parse(t.token), "r");
                    if (pfd != null)
                        rc = NativeBridge.tagReadFd(pfd.detachFd(),
                                title, artist, album, dur, tr, dc);
                } catch (Exception e) {
                    /* provider failed -> tags stay at raw filename */
                } finally {
                    if (pfd != null)
                        try { pfd.close(); } catch (Exception ignore) { }
                }
            } else {
                rc = NativeBridge.tagReadPath(t.token,
                        title, artist, album, dur, tr, dc);
            }
            if (rc == 0) {
                t.title = new String(title, StandardCharsets.UTF_8).trim();
                t.artist = new String(artist, StandardCharsets.UTF_8).trim();
                t.album = new String(album, StandardCharsets.UTF_8).trim();
                t.durationMs = dur[0];
                t.track = tr[0];
                t.disc = dc[0];
            }
            /* presence flag only — decode the cover lazily (holding a full
             * Bitmap per track OOMs at ~950 files). raw bytes are discarded. */
            t.hasArt = hasEmbeddedArt(t.token) || treeFolderCover(albumDir) != null;
            t.art = null;   /* decoded on demand by loadArt */
        } catch (Exception e) {
            android.util.Log.e("marimo", "readTags failed: " + t.token, e);
        }
    }

    /** does this track carry an embedded picture? raw-bytes check, no decode.
     *  reads (and discards) the art bytes to learn presence. */
    private boolean hasEmbeddedArt(String token) {
        try {
            if (token.startsWith("content://")) {
                ParcelFileDescriptor pfd = null;
                try {
                    pfd = getContentResolver()
                            .openFileDescriptor(Uri.parse(token), "r");
                    if (pfd != null) {
                        byte[] b = NativeBridge.embeddedArtFd(pfd.detachFd());
                        return b != null && b.length > 0;
                    }
                } catch (Exception e) { /* none */ }
                finally {
                    if (pfd != null)
                        try { pfd.close(); } catch (Exception ignore) { }
                }
            } else {
                byte[] b = NativeBridge.embeddedArtPath(token);
                return b != null && b.length > 0;
            }
        } catch (Exception e) { }
        return false;
    }

    /** read cover/folder/front art from the audio file's folder, matching
     *  filenames CASE-INSENSITIVELY (Folder.jpg, COVER.JPEG, ...) and a few
     *  image extensions. used when a track has no embedded art. folder covers
     *  inside the picked SAF tree are read via ContentResolver. */
    private byte[] treeFolderCover(DocumentFile dir) {
        try {
            if (dir == null) return null;
            for (DocumentFile c : dir.listFiles()) {
                String n = c.getName();
                if (n != null && isFolderCoverName(n)) {
                    java.io.InputStream in = getContentResolver()
                            .openInputStream(c.getUri());
                    return readAll(in);
                }
            }
        } catch (Exception e) { }
        return null;
    }

    /** is this filename a folder-cover candidate, case-insensitively?
     *  stem in {cover, folder, front} and an image extension. */
    private static boolean isFolderCoverName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return false;
        String stem = name.substring(0, dot).toLowerCase();
        String ext = name.substring(dot + 1).toLowerCase();
        boolean goodExt = ext.equals("jpg") || ext.equals("jpeg")
                || ext.equals("png") || ext.equals("bmp") || ext.equals("webp");
        return goodExt && (stem.equals("cover") || stem.equals("folder")
                || stem.equals("front"));
    }

    /** read cover.jpg/... from an app-private folder (needs no permission). */
    private byte[] folderCoverPath(String audioPath) {
        try {
            java.io.File dir = new java.io.File(audioPath).getParentFile();
            if (dir == null) return null;
            java.io.File[] files = dir.listFiles();
            if (files == null) return null;
            String match = null;
            for (File c : files)
                if (c.isFile() && isFolderCoverName(c.getName())) {
                    match = c.getAbsolutePath();
                    break;
                }
            if (match == null) return null;
            java.io.FileInputStream in = new java.io.FileInputStream(match);
            java.io.File c = new java.io.File(match);
            byte[] b = new byte[(int) c.length()];
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r < 0) break;
                off += r;
            }
            in.close();
            if (off == b.length) return b;
        } catch (Exception e) { }
        return null;
    }

    private byte[] readAll(java.io.InputStream in) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            in.close();
            return out.toByteArray();
        } catch (Exception e) { return null; }
    }

    /** decode an album's cover art off the UI thread (on demand, once per
     *  album) so the list never holds a Bitmap per track — the OOM fix. */
    void decodeCoverAsync(Album a) {
        if (a == null || a.coverIdx < 0
                || a.coverIdx >= a.tracks.size()) return;
        new Thread(() -> {
            Track cover = a.tracks.get(a.coverIdx);
            Bitmap b = loadArt(cover.token, (DocumentFile) a.albumDoc);
            if (b != null) {
                runOnUiThread(() -> {
                    /* share the cover across the album so the folder view
                     * shows art on every row, not just the cover track */
                    for (Track t : a.tracks) t.art = b;
                    adapter.notifyDataSetChanged();
                });
            }
        }).start();
    }

    /** the album folder DocumentFile a track belongs to (for lazy art folder
     *  covers), or null if not in any scanned album. */
    private DocumentFile albumDocOf(Track t) {
        for (Album a : albums) {
            if (a.albumDoc != null && a.tracks.contains(t))
                return (DocumentFile) a.albumDoc;
        }
        return null;
    }

    private Bitmap loadArt(String token, DocumentFile albumDir) {
        try {
            /* disk art cache first: no SAF open + decode on repeated launches */
            Bitmap cached = LibraryCache.readArt(this, token);
            if (cached != null) return cached;
            byte[] raw = null;
            if (token.startsWith("content://")) {
                ParcelFileDescriptor pfd = null;
                try {
                    pfd = getContentResolver().openFileDescriptor(Uri.parse(token), "r");
                    if (pfd != null) {
                        try { raw = NativeBridge.embeddedArtFd(pfd.detachFd()); }
                        finally { try { pfd.close(); } catch (Exception ignore) { } }
                    }
                } catch (Exception e) { /* provider failed */ }
                if (raw == null) raw = treeFolderCover(albumDir);
            } else {
                raw = NativeBridge.embeddedArtPath(token);
                if (raw == null) raw = folderCoverPath(token);
            }
            if (raw == null || raw.length == 0) return null;
            Bitmap bmp = decodeScaled(raw, 1024);
            if (bmp != null) LibraryCache.writeArt(this, token, bmp);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    /** decode a cover image, downscaling so huge 1000+px album art doesn't
     *  get fully decoded just to be shown at 48dp — the dominant scan cost. */
    private static Bitmap decodeScaled(byte[] raw, int maxDim) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        int sample = 1;
        /* keep 2x the target so fitCenter on the player stays crisp */
        int cap = Math.max(maxDim, 32);
        while (bounds.outWidth / (sample * 2) > cap
                && bounds.outHeight / (sample * 2) > cap
                && sample < 64) {
            sample *= 2;
        }
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(raw, 0, raw.length, opt);
    }

    /* ---------------- playback ---------------- */

    private void playTrack(Track t) {
        List<Track> ctx = tracksOf(t);
        PlaybackService.setTracksStatic(new ArrayList<>(ctx));
        startService(new Intent(this, PlaybackService.class)
                .setAction(PlaybackService.ACTION_PLAY)
                .putExtra(PlaybackService.EXTRA_POS, ctx.indexOf(t)));
        showNowPlaying(t);
        showTab(1);
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
                        showTab(1);
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

    private void showScrobbleSettings() {
        final String[] cur = Scrobbler.current();
        final android.widget.EditText lfKey = new android.widget.EditText(this);
        lfKey.setHint("last.fm api key");
        lfKey.setText(cur[0]);
        final android.widget.EditText lfSession = new android.widget.EditText(this);
        lfSession.setHint("last.fm session key");
        lfSession.setText(cur[2]);
        final android.widget.EditText lbToken = new android.widget.EditText(this);
        lbToken.setHint("listenbrainz token");
        lbToken.setText(cur[4]);
        android.widget.LinearLayout lay = new android.widget.LinearLayout(this);
        lay.setOrientation(android.widget.LinearLayout.VERTICAL);
        lay.addView(lfKey);
        lay.addView(lfSession);
        lay.addView(lbToken);
        new AlertDialog.Builder(this)
                .setTitle("scrobble")
                .setView(lay)
                .setMessage("last.fm: api key + session key (last.fm/api/account)\n"
                        + "listenbrainz: token (listenbrainz.org/profile)")
                .setPositiveButton("save", (d, w) -> {
                    Scrobbler.configure(lfKey.getText().toString(),
                            cur[1], lfSession.getText().toString(), cur[3],
                            lbToken.getText().toString());
                    getSharedPreferences("marimo", MODE_PRIVATE).edit()
                            .putString("lf_key", lfKey.getText().toString())
                            .putString("lf_session", lfSession.getText().toString())
                            .putString("lb_token", lbToken.getText().toString())
                            .apply();
                    toast(Scrobbler.hasLf() || Scrobbler.hasLb()
                            ? "scrobble saved" : "no credentials — scrobbling off");
                })
                .setNegativeButton("cancel", null)
                .show();
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
                    showTab(1);
                }).show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /* ---------------- player screen ---------------- */

    private void showNowPlaying(Track t) {
        shownToken = t.token;
        pTrack.setText(t.title.isEmpty() ? t.name : t.title);
        pArtist.setText(t.artist);
        pAlbum.setText(t.album);
        pArt.setImageBitmap(t.art);
        repaintBg(t.art);
        /* art is decoded lazily (null after a scan) — pull it in the background
         * and repaint the player + gradient when it lands */
        if (t.art == null) {
            final Track ft = t;
            new Thread(() -> {
                Bitmap b = loadArt(ft.token, albumDocOf(ft));
                runOnUiThread(() -> {
                    if (b != null && shownToken.equals(ft.token)) {
                        ft.art = b;
                        pArt.setImageBitmap(b);
                        repaintBg(b);
                    }
                });
            }).start();
        }
        /* real waveform (async decode — never block the UI thread).
         * while it loads, the seekbar shows a flat silent baseline */
        pSeek.resetWaveform();
        new Thread(() -> {
            int[] peaks = WaveformExtractor.get(this, t.token);
            runOnUiThread(() -> pSeek.setWaveformPeaks(peaks));
        }).start();
        /* pre-warm the rest of the album/queue's waveforms in parallel so the
         * seekbar is already filled (cached) when each track starts — the full
         * software FLAC decode happens in the background, not on first play */
        prewarmWaveforms(t);
        refreshQueueList();
    }

    /** find the track's context (album or queue) and decode its future tracks'
     *  waveforms in the background — non-blocking, skips already-cached. */
    private void prewarmWaveforms(Track t) {
        List<Track> ctx = tracksOf(t);
        if (ctx == null || ctx.isEmpty()) return;
        int idx = ctx.indexOf(t);
        int start = idx < 0 ? 0 : idx;
        java.util.List<String> tokens = new ArrayList<>();
        for (int i = start; i < ctx.size(); i++)
            tokens.add(ctx.get(i).token);
        if (!tokens.isEmpty())
            new Thread(() -> WaveformExtractor.prewarm(this, tokens)).start();
    }

    private void refreshQueueList() {
        List<Track> q = PlaybackService.peekQueue();
        QueueAdapter.dragIndex = dragFrom;
        qList.setAdapter(new QueueAdapter(this, q));
    }

    private long hash(String s) {
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return h;
    }

    private void updatePlayerUi() {
        /* follow auto-advance: when the playing item changes (gapless next),
         * refresh the title/art/waveform — showNowPlaying is not otherwise
         * called on automatic transitions, only on manual taps. */
        Track cur = PlaybackService.current();
        if (cur != null && !shownToken.equals(cur.token)) {
            shownToken = cur.token;
            showNowPlaying(cur);
        }
        boolean playing = PlaybackService.isPlaying();
        pPlay.setImageResource(playing
                ? R.drawable.ic_pause
                : R.drawable.ic_play);
        ImageButton sh = findViewById(R.id.p_shuffle);
        ImageButton rp = findViewById(R.id.p_repeat);
        if (sh != null) sh.setAlpha(PlaybackService.shuffle() == 1 ? 1f : 0.3f);
        if (rp != null) {
            int rep = PlaybackService.repeat();
            rp.setAlpha(rep > 0 ? 1f : 0.3f);
            rp.setImageResource(rep == 2 ? R.drawable.ic_repeat_one
                    : R.drawable.ic_repeat);
        }
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
