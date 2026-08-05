package moe.umbrella.marimo;

import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import java.util.ArrayList;
import java.util.List;

/** ExoPlayer-backed playback + MediaSessionService.
 *  ExoPlayer gives TRUE gapless between queued tracks (same-format) and
 *  MediaSessionService posts the notification widget + lockscreen
 *  controls automatically. Shuffle/repeat come from the player. */
public class PlaybackService extends MediaSessionService {

    public static final String ACTION_PLAY = "moe.umbrella.marimo.PLAY";
    public static final String ACTION_PAUSE = "moe.umbrella.marimo.PAUSE";
    public static final String ACTION_NEXT = "moe.umbrella.marimo.NEXT";
    public static final String ACTION_PREV = "moe.umbrella.marimo.PREV";
    public static final String ACTION_SHUFFLE = "moe.umbrella.marimo.SHUFFLE";
    public static final String ACTION_REPEAT = "moe.umbrella.marimo.REPEAT";
    public static final String EXTRA_POS = "pos";

    private static volatile List<Track> tracks = new ArrayList<>();
    private static volatile boolean playing;
    private static volatile long positionMs, durationMs;
    private static volatile int shuffle = 0, repeat = 0;   /* 0 off,1 on / 0 off,1 all,2 one */
    private static volatile PlaybackService instance;

    private ExoPlayer player;
    private MediaSession session;
    private android.os.Handler tickHandler;
    private Runnable ticker;

    public static void setTracksStatic(List<Track> list) {
        synchronized (tracks) {
            tracks = new ArrayList<>(list);
        }
        saveQueue(null);
    }

    /** drag-reorder: move the track at `from` to index `to`, keeping the live
     *  ExoPlayer playlist, the static list and the persisted queue in sync */
    public static void reorderQueue(int from, int to) {
        PlaybackService s = instance;
        synchronized (tracks) {
            if (from < 0 || from >= tracks.size() || to < 0 || to >= tracks.size())
                return;
            Track t = tracks.remove(from);
            tracks.add(to, t);
        }
        if (s != null && s.player != null && from != to)
            s.player.moveMediaItem(from, to);
        saveQueue(null);
        if (s != null) s.postWidget();
    }

    /** swipe-remove: drop the track at `pos` from the queue entirely */
    public static void removeFromQueue(int pos) {
        PlaybackService s = instance;
        synchronized (tracks) {
            if (pos < 0 || pos >= tracks.size()) return;
            tracks.remove(pos);
        }
        if (s != null && s.player != null)
            s.player.removeMediaItem(pos);
        saveQueue(null);
        if (s != null) s.postWidget();
    }

    /** queue memory: persists token list + current index to app files */
    private static volatile java.io.File queueFile;
    private static int savedCur = -1;

    public static void attachQueueStorage(java.io.File file) {
        queueFile = file;
        loadQueue();
    }

    private static void loadQueue() {
        if (queueFile == null || !queueFile.exists()) return;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            new java.io.FileInputStream(queueFile),
                            java.nio.charset.StandardCharsets.UTF_8));
            String line;
            int cur = -1;
            List<String> toks = new ArrayList<>();
            List<String> names = new ArrayList<>();
            List<String> titles = new ArrayList<>();
            List<String> artists = new ArrayList<>();
            List<Integer> durs = new ArrayList<>();
            while ((line = r.readLine()) != null) {
                if (line.startsWith("cur=")) {
                    try { cur = Integer.parseInt(line.substring(4)); } catch (Exception e) { }
                } else if (line.startsWith("t=")) toks.add(line.substring(2));
                else if (line.startsWith("n=")) names.add(line.substring(2));
                else if (line.startsWith("ti=")) titles.add(line.substring(3));
                else if (line.startsWith("ar=")) artists.add(line.substring(3));
                else if (line.startsWith("d=")) {
                    try { durs.add(Integer.parseInt(line.substring(2))); } catch (Exception e) { durs.add(0); }
                }
            }
            r.close();
            synchronized (tracks) {
                tracks = new ArrayList<>();
                for (int i = 0; i < toks.size(); i++) {
                    String tok = toks.get(i);
                    /* desktop rule: drop vanished plain-file entries. content://
                     * tokens can't be checked with new File().exists() (they're
                     * URIs, not paths) — keep them so android queues restore. */
                    if (!tok.startsWith("content://")) {
                        java.io.File f = new java.io.File(tok);
                        if (!f.exists()) continue;
                    }
                    Track t = new Track(tok,
                            i < names.size() ? names.get(i) : tok);
                    if (i < titles.size()) t.title = titles.get(i);
                    if (i < artists.size()) t.artist = artists.get(i);
                    if (i < durs.size()) t.durationMs = durs.get(i);
                    tracks.add(t);
                }
            }
            savedCur = cur;
        } catch (Exception e) {
            android.util.Log.e("marimo", "queue load: " + e);
        }
    }

    public static void saveQueue(Integer curOverride) {
        if (queueFile == null) return;
        try {
            synchronized (tracks) {
                StringBuilder sb = new StringBuilder();
                sb.append("cur=").append(
                        curOverride != null ? curOverride : savedCur).append('\n');
                for (Track t : tracks) {
                    sb.append("t=").append(t.token).append('\n');
                    sb.append("n=").append(t.name).append('\n');
                    if (!t.title.isEmpty()) sb.append("ti=").append(t.title).append('\n');
                    if (!t.artist.isEmpty()) sb.append("ar=").append(t.artist).append('\n');
                    if (t.durationMs > 0) sb.append("d=").append(t.durationMs).append('\n');
                }
                java.io.FileWriter w = new java.io.FileWriter(
                        queueFile, java.nio.charset.StandardCharsets.UTF_8);
                w.write(sb.toString());
                w.close();
            }
        } catch (Exception e) {
            android.util.Log.e("marimo", "queue save: " + e);
        }
    }

    /** current index accessor used by the UI */
    public static int restoredIndex() { return savedCur; }

    public static List<Track> peekQueue() {
        synchronized (tracks) { return new ArrayList<>(tracks); }
    }

    /** prepare needed only from IDLE/ENDED — BUFFERING is already on its
     *  way to READY and re-preparing double-starts the source */
    private boolean needPrepare() {
        int st = player.getPlaybackState();
        return st == Player.STATE_IDLE || st == Player.STATE_ENDED;
    }

    private Track currentTrack() {
        androidx.media3.common.MediaItem mi = player.getCurrentMediaItem();
        if (mi != null && mi.localConfiguration != null
                && mi.localConfiguration.tag instanceof Track)
            return (Track) mi.localConfiguration.tag;
        return null;
    }

    /** replay from an exhausted source: waydroid's flac decoder cannot
     *  re-init the same instance (repeat-one hangs too) — rebuild the
     *  whole player fresh so the decoder starts clean */
    public static void replayFrom(int ms) {
        PlaybackService s = instance;
        if (s == null) return;
        s.rebuildPlayer(ms);
    }

    private ExoPlayer makePlayer() {
        ExoPlayer p = new ExoPlayer.Builder(this)
                .setLoadControl(new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                        .setBufferDurationsMs(5000, 15000, 500, 1500)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build())
                .build();
        p.setRepeatMode(repeat == 2 ? Player.REPEAT_MODE_ONE
                : repeat == 1 ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
        p.setShuffleModeEnabled(shuffle == 1);
        p.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean b) {
                playing = b;
                if (b) {
                    Track cur = currentTrack();
                    if (cur != null) Scrobbler.nowPlaying(cur);
                }
            }
            @Override public void onMediaItemTransition(
                    androidx.media3.common.MediaItem mi, int reason) {
                if (mi != null && mi.localConfiguration != null
                        && mi.localConfiguration.tag instanceof Track) {
                    Scrobbler.nowPlaying((Track) mi.localConfiguration.tag);
                }
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    Track t = currentTrack();
                    if (t != null) Scrobbler.scrobble(t, t.durationMs);
                }
            }
            @Override public void onPlayerError(androidx.media3.common.PlaybackException e) {
                android.util.Log.e("marimo", "playerError " + e.errorCode
                        + " " + e.getMessage());
            }
        });
        return p;
    }

    private void rebuildPlayer(final int seekMs) {
        android.util.Log.i("marimo", "rebuildPlayer seek=" + seekMs
                + " oldState=" + player.getPlaybackState());
        final int idx = Math.max(player.getCurrentMediaItemIndex(), 0);
        final List<Track> list;
        synchronized (tracks) { list = new ArrayList<>(tracks); }
        final ExoPlayer oldPlayer = player;
        /* brand-new player (fresh decoder) but the SAME session — media3
         * lets us swap players underneath so the platform connection
         * (and the notification widget) survives */
        player = makePlayer();
        session.setPlayer(player);
        List<MediaItem> items = new ArrayList<>();
        for (Track t : list) {
            items.add(new MediaItem.Builder()
                    .setUri(playUri(t.token))
                    .setMediaId(t.token)
                    .setTag(t)
                    .setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(t.title.isEmpty() ? t.name : t.title)
                            .setArtist(t.artist)
                            .setAlbumTitle(t.album)
                            .build())
                    .build());
        }
        player.setMediaItems(items,
                Math.min(idx, Math.max(0, items.size() - 1)), 0);
        player.prepare();
        player.seekTo(seekMs);
        /* stay PAUSED after a seek into a finished track — the play
         * button resumes; only ACTION_PLAY's own path calls play() */
        if (oldPlayer != null) oldPlayer.release();
        postWidget();
    }

    public static void seek(int ms) {
        PlaybackService s = instance;
        if (s == null || s.player == null) return;
        android.util.Log.i("marimo", "seek(" + ms + ") state="
                + s.player.getPlaybackState());
        if (s.needPrepare()) {
            s.rebuildPlayer(ms);
        } else {
            s.player.seekTo(ms);
        }
    }

    public static int addToQueueStatic(List<Track> add) {
        synchronized (tracks) {
            int first = tracks.size();
            tracks.addAll(add);
            return first;
        }
    }

    public static boolean isPlaying() { return playing; }
    public static long position() { return positionMs; }
    public static long duration() { return durationMs; }
    public static int shuffle() { return shuffle; }
    public static int repeat() { return repeat; }
    /** waydroid's provider can't serve files it never indexed, so map the
     *  content:// tree doc to a real path (ExoPlayer then reads it directly
     *  via FileDataSource, using our READ_MEDIA_AUDIO). falls back to content.
     */
    private static Uri playUri(String token) {
        if (token == null || !token.startsWith("content://")) return Uri.parse(token);
        try {
            String p = Uri.parse(token).getPath();
            int i = p == null ? -1 : p.indexOf("/document/");
            if (i >= 0) {
                String id = android.net.Uri.decode(p.substring(i + 10));
                if (id.startsWith("primary:"))
                    return Uri.fromFile(new java.io.File(
                            "/storage/emulated/0/" + id.substring("primary:".length())));
            }
        } catch (Exception e) { }
        return Uri.parse(token);
    }

    public static int currentIndex() {
        PlaybackService s = instance;
        return s != null && s.player != null ? s.player.getCurrentMediaItemIndex() : -1;
    }

    /** the currently-playing Track (null if none) — for the UI to follow
     *  auto-advance across the queue. */
    public static Track current() {
        PlaybackService s = instance;
        return s != null ? s.currentTrack() : null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        player = makePlayer();
        session = new MediaSession.Builder(this, player)
                .setSessionActivity(android.app.PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class),
                        android.app.PendingIntent.FLAG_IMMUTABLE))
                .build();
        /* media3's manager only posts when started by a media command;
         * our direct-start path posts its own MediaStyle widget instead.
         * no-op provider so media3 never posts a duplicate. */
        setMediaNotificationProvider(new androidx.media3.session.MediaNotification.Provider() {
            @Override
            public androidx.media3.session.MediaNotification createNotification(
                    androidx.media3.session.MediaSession s,
                    com.google.common.collect.ImmutableList<
                            androidx.media3.session.CommandButton> cmds,
                    androidx.media3.session.MediaNotification.ActionFactory af,
                    androidx.media3.session.MediaNotification.Provider.Callback cb) {
                return null;   /* we post our own MediaStyle widget */
            }

            @Override
            public boolean handleCustomCommand(
                    androidx.media3.session.MediaSession s, String action,
                    android.os.Bundle extras) {
                return false;
            }

            @Override
            public NotificationChannelInfo getNotificationChannelInfo() {
                return null;
            }
        });

        final android.os.Handler tickHandler =
                new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable ticker = new Runnable() {
            @Override
            public void run() {
                if (player != null) {
                    long dur = player.getDuration();
                    if (dur == androidx.media3.common.C.TIME_UNSET
                            || dur <= 0) {
                        dur = 0;
                        androidx.media3.common.MediaItem mi =
                                player.getCurrentMediaItem();
                        if (mi != null && mi.localConfiguration != null
                                && mi.localConfiguration.tag instanceof Track) {
                            Track t = (Track) mi.localConfiguration.tag;
                            dur = t.durationMs;
                        }
                    }
                    durationMs = dur;
                    positionMs = player.getCurrentPosition();
                }
                tickHandler.postDelayed(this, 200);
            }
        };
        tickHandler.post(ticker);
        /* keep a reference so onDestroy can stop it */
        this.tickHandler = tickHandler;
        this.ticker = ticker;
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int rc = super.onStartCommand(intent, flags, startId);  /* media3 machinery */
        if (intent == null) return rc;
        String a = intent.getAction();
        int pos = intent.getIntExtra(EXTRA_POS, -1);
        if (pos >= 0) {
            queueTracks();
            player.seekTo(pos, 0);
            player.prepare();
            player.play();
            postWidget();
        } else if (ACTION_PLAY.equals(a)) {
            if (player.isPlaying()) player.pause();
            else {
                if (needPrepare()) {
                    /* waydroid's decoder can't re-init an exhausted source
                     * — a fresh player starts clean (same track) */
                    rebuildPlayer(0);
                } else {
                    player.play();
                    postWidget();
                }
            }
        } else if (ACTION_PAUSE.equals(a)) {
            player.pause();
        } else if (ACTION_NEXT.equals(a)) {
            player.seekToNextMediaItem();
        } else if (ACTION_PREV.equals(a)) {
            player.seekToPreviousMediaItem();
        } else if (ACTION_SHUFFLE.equals(a)) {
            shuffle = shuffle == 1 ? 0 : 1;
            player.setShuffleModeEnabled(shuffle == 1);
        } else if (ACTION_REPEAT.equals(a)) {
            repeat = (repeat + 1) % 3;
            player.setRepeatMode(repeat == 2 ? Player.REPEAT_MODE_ONE
                    : repeat == 1 ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
        }
        return rc;
    }

    /** MediaStyle notification wired to our MediaSession token: the
     *  pulldown widget with prev/play/next that controls the player. */
    private void postWidget() {
        Track t = player.getCurrentMediaItem() != null
                ? (Track) player.getCurrentMediaItem().localConfiguration.tag
                : null;
        if (t == null) return;
        Intent open = new Intent(this, MainActivity.class);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                this, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE);
        androidx.core.app.NotificationCompat.Builder b =
                new androidx.core.app.NotificationCompat.Builder(this, "marimo_playback")
                        .setContentTitle(t.title.isEmpty() ? t.name : t.title)
                        .setContentText(t.artist)
                        .setSmallIcon(android.R.drawable.ic_media_play)
                        .setContentIntent(pi)
                        .setOngoing(true)
                        .setVisibility(androidx.core.app.NotificationCompat
                                .VISIBILITY_PUBLIC)
                        .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                                .setMediaSession(android.support.v4.media.session.MediaSessionCompat.Token.fromToken(session.getPlatformToken()))
                                .setShowActionsInCompactView(0, 1, 2))
                        .addAction(new androidx.core.app.NotificationCompat.Action(
                                android.R.drawable.ic_media_previous, "prev",
                                ctl(PlaybackService.ACTION_PREV)))
                        .addAction(new androidx.core.app.NotificationCompat.Action(
                                player.isPlaying() ? android.R.drawable.ic_media_pause
                                        : android.R.drawable.ic_media_play,
                                player.isPlaying() ? "pause" : "play",
                                ctl(PlaybackService.ACTION_PLAY)))
                        .addAction(new androidx.core.app.NotificationCompat.Action(
                                android.R.drawable.ic_media_next, "next",
                                ctl(PlaybackService.ACTION_NEXT)));
        startForeground(1, b.build());
        android.app.NotificationManager nm =
                getSystemService(android.app.NotificationManager.class);
        nm.notify(1, b.build());
    }

    private android.app.PendingIntent ctl(String action) {
        return android.app.PendingIntent.getService(this, action.hashCode(),
                new Intent(this, PlaybackService.class).setAction(action),
                android.app.PendingIntent.FLAG_IMMUTABLE);
    }

    private void queueTracks() {
        queueTracksAt(0);
    }

    private void queueTracksAt(int index) {
        List<Track> list;
        synchronized (tracks) { list = new ArrayList<>(tracks); }
        List<MediaItem> items = new ArrayList<>();
        for (Track t : list) {
            MediaItem mi = new MediaItem.Builder()
                    .setUri(playUri(t.token))
                    .setMediaId(t.token)
                    .setTag(t)
                    .setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(t.title.isEmpty() ? t.name : t.title)
                            .setArtist(t.artist)
                            .setAlbumTitle(t.album)
                            .build())
                    .build();
            items.add(mi);
        }
        if (index < 0) index = 0;
        if (index >= items.size()) index = Math.max(0, items.size() - 1);
        player.setMediaItems(items, index, 0);
    }

    @Override
    public void onDestroy() {
        playing = false;
        if (tickHandler != null && ticker != null) tickHandler.removeCallbacks(ticker);
        if (session != null) session.release();
        if (player != null) player.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return super.onBind(intent); }
}
