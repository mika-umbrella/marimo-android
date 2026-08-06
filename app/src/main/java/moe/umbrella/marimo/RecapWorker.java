package moe.umbrella.marimo;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.List;

/** Fires a recap notification on reset day (weekly/monthly/yearly) with a
 *  share action, then re-schedules the next reset for the same period.
 *
 *  Empty week gets a gentle check-in instead of silence (locked with nova);
 *  monthly/yearly only notify when there's something to show. */
public class RecapWorker extends Worker {

    public static final String PERIOD = "period";
    private static final String CHANNEL = "marimo_recap";

    public RecapWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    @NonNull @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        HistoryDiary.configure(ctx);
        int period = getInputData().getInt(PERIOD, Recap.MODE_WEEK);
        long now = System.currentTimeMillis();
        long[] w = Recap.windows(period, now);
        List<HistoryDiary.Entry> cur = HistoryDiary.forWindow(w[0], w[1]);
        List<HistoryDiary.Entry> prev = HistoryDiary.forWindow(w[2], w[3]);
        Recap.Result r = Recap.compute(cur, prev, period);

        if (r.empty) {
            // silent by default; only the weekly gives a soft check-in
            if (period == Recap.MODE_WEEK) postCheckIn(ctx);
        } else {
            postRecap(ctx, period, r);
        }
        // keep the chain alive for the next reset
        RecapScheduler.schedule(ctx, period);
        return Result.success();
    }

    private void postRecap(Context ctx, int period, Recap.Result r) {
        String pname = period == Recap.MODE_WEEK ? "week"
                : period == Recap.MODE_MONTH ? "month" : "year";
        String title = "your " + pname + " in marimo";
        String card = r.tracks + " tracks · " + Recap.fmtHours(r.totalSec, true)
                + (!r.topArtists.isEmpty()
                        ? " · top artist " + r.topArtists.get(0).name : "");
        String body = card;
        if ((r.hoursPrev != r.hours) || (r.tracksPrev != r.tracks)) {
            body += "\nvs last " + pname + ": "
                    + Recap.delta(r.tracksPrev, r.tracks, "tracks")
                    + " · " + Recap.delta(r.hoursPrev, r.hours, "hours");
        }

        ensureChannel(ctx);
        Intent open = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, title + ": " + card);
        PendingIntent sp = PendingIntent.getActivity(ctx, 1,
                Intent.createChooser(share, "share your recap"),
                PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b =
                new NotificationCompat.Builder(ctx, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_notify_more)
                        .setContentTitle(title)
                        .setContentText(card)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .addAction(android.R.drawable.ic_menu_share, "share", sp);
        notify(ctx, b.build(), period);
    }

    private void postCheckIn(Context ctx) {
        ensureChannel(ctx);
        Intent open = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b =
                new NotificationCompat.Builder(ctx, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_notify_more)
                        .setContentTitle("barely listened this week")
                        .setContentText("everything okay, angel? ♪")
                        .setContentIntent(pi)
                        .setAutoCancel(true);
        notify(ctx, b.build(), Recap.MODE_WEEK);
    }

    private void notify(Context ctx, android.app.Notification n, int period) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(100 + period, n);
    }

    private static void ensureChannel(Context ctx) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "Recaps",
                    NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
