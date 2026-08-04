package moe.umbrella.marimo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.IBinder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** MediaPlayer-backed playback + MediaSession (lockscreen / BT keys). */
public class PlaybackService extends Service implements MediaPlayer.OnCompletionListener {

    public static final String ACTION_PLAY = "moe.umbrella.marimo.PLAY";
    public static final String ACTION_PAUSE = "moe.umbrella.marimo.PAUSE";
    public static final String ACTION_NEXT = "moe.umbrella.marimo.NEXT";
    public static final String ACTION_PREV = "moe.umbrella.marimo.PREV";
    public static final String EXTRA_POS = "pos";

    private static final String CHANNEL_ID = "marimo_playback";

    private MediaPlayer mp;
    private MediaSession session;
    private static volatile List<Track> tracks = new ArrayList<>();
    private int cur = -1;

    /** MainActivity hands the current track list here before starting us. */
    public static void setTracksStatic(List<Track> list) { tracks = list; }

    @Override
    public void onCreate() {
        super.onCreate();
        mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build());
        mp.setOnCompletionListener(this);

        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "marimo playback",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);

        session = new MediaSession(this, "marimo");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onStop() { stopSelf(); }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            int pos = intent.getIntExtra(EXTRA_POS, -1);
            if (pos >= 0) cur = pos;
            String a = intent.getAction();
            if (ACTION_PLAY.equals(a)) play();
            else if (ACTION_PAUSE.equals(a)) pause();
            else if (ACTION_NEXT.equals(a)) next();
            else if (ACTION_PREV.equals(a)) prev();
        }
        return START_NOT_STICKY;
    }

    public void setTracks(List<Track> list) { tracks = list; }

    private void play() {
        if (cur < 0 || cur >= tracks.size()) return;
        if (mp.isPlaying()) { pause(); return; }   /* UI button toggles */
        Track t = tracks.get(cur);
        try {
            mp.reset();
            mp.setDataSource(this, Uri.parse(t.token));
            mp.prepare();
            mp.start();
            publish(t);
            startForeground(1, buildNotification(t, true));
        } catch (IOException e) {
            stopSelf();
        }
    }

    private void pause() {
        if (mp.isPlaying()) {
            mp.pause();
            updateNotification();
        }
    }

    private void next() {
        if (cur < tracks.size() - 1) { cur++; play(); }
    }

    private void prev() {
        if (cur > 0) { cur--; play(); }
    }

    @Override
    public void onCompletion(MediaPlayer p) { next(); }

    private void publish(Track t) {
        MediaMetadata md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title.isEmpty() ? t.name : t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                .build();
        session.setMetadata(md);
        session.setPlaybackState(new android.media.session.PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PLAYING, mp.getCurrentPosition(), 1f)
                .build());
    }

    private Notification buildNotification(Track t, boolean playing) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE);
        Intent playPause = new Intent(this, PlaybackService.class)
                .setAction(playing ? ACTION_PAUSE : ACTION_PLAY);
        PendingIntent pp = PendingIntent.getService(this, 1, playPause,
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(t.title.isEmpty() ? t.name : t.title)
                .setContentText(t.artist)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true);
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_media_previous, "prev",
                    PendingIntent.getService(this, 2,
                            new Intent(this, PlaybackService.class).setAction(ACTION_PREV),
                            PendingIntent.FLAG_IMMUTABLE)).build());
            b.addAction(new Notification.Action.Builder(
                    playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                    playing ? "pause" : "play", pp).build());
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_media_next, "next",
                    PendingIntent.getService(this, 3,
                            new Intent(this, PlaybackService.class).setAction(ACTION_NEXT),
                            PendingIntent.FLAG_IMMUTABLE)).build());
        }
        return b.build();
    }

    private void updateNotification() {
        if (cur >= 0 && cur < tracks.size())
            getSystemService(NotificationManager.class)
                    .notify(1, buildNotification(tracks.get(cur), mp.isPlaying()));
    }

    @Override
    public void onDestroy() {
        mp.release();
        session.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
