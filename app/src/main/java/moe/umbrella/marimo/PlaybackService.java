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
    private Thread ticker;

    public static void setTracksStatic(List<Track> list) {
        synchronized (tracks) {
            tracks = new ArrayList<>(list);
        }
    }

    public static List<Track> peekQueue() {
        synchronized (tracks) { return new ArrayList<>(tracks); }
    }

    public static void seek(int ms) {
        PlaybackService s = instance;
        if (s != null && s.player != null) s.player.seekTo(ms);
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
    public static int currentIndex() {
        PlaybackService s = instance;
        return s != null && s.player != null ? s.player.getCurrentMediaItemIndex() : -1;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        player = new ExoPlayer.Builder(this).build();
        player.setRepeatMode(repeat == 2 ? Player.REPEAT_MODE_ONE
                : repeat == 1 ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
        player.setShuffleModeEnabled(shuffle == 1);
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean b) { playing = b; }
        });
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

        ticker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (player != null) {
                        positionMs = player.getCurrentPosition();
                        durationMs = player.getDuration();
                    }
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception ignored) {
                }
            }
        });
        ticker.start();
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
            else { player.play(); postWidget(); }
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
        List<Track> list;
        synchronized (tracks) { list = new ArrayList<>(tracks); }
        List<MediaItem> items = new ArrayList<>();
        for (Track t : list) {
            MediaItem mi = new MediaItem.Builder()
                    .setUri(Uri.parse(t.token))
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
        player.setMediaItems(items, 0, 0);
    }

    @Override
    public void onDestroy() {
        playing = false;
        if (ticker != null) ticker.interrupt();
        if (session != null) session.release();
        if (player != null) player.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return super.onBind(intent); }
}
