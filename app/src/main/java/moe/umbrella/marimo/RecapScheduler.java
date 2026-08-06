package moe.umbrella.marimo;

import android.content.Context;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/** Schedules the recap "ritual" notifications on their reset days (09:00):
 *  weekly on Monday, monthly on the 1st, yearly on Jan 1.
 *
 *  Each reset is a single one-time WorkManager job delayed until the next
 *  occurrence; the worker re-schedules the following occurrence when it runs,
 *  so the chain keeps itself alive. WorkManager persists across reboots. */
public final class RecapScheduler {

    /** the local hour recaps are delivered */
    private static final int HOUR = 9;

    private RecapScheduler() { }

    private static String uniqueName(int period) {
        return "marimo_recap_" + period;
    }

    /** schedule one period's next reset (replaces any pending one) */
    public static void schedule(Context c, int period) {
        long now = System.currentTimeMillis();
        long delayMs = delayToNextReset(period, now);
        Data d = new Data.Builder().putInt(RecapWorker.PERIOD, period).build();
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(RecapWorker.class)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(d)
                .build();
        WorkManager.getInstance(c).enqueueUniqueWork(
                uniqueName(period), ExistingWorkPolicy.REPLACE, req);
    }

    /** (re)establish all three chains — call on app start */
    public static void scheduleAll(Context c) {
        schedule(c, Recap.MODE_WEEK);
        schedule(c, Recap.MODE_MONTH);
        schedule(c, Recap.MODE_YEAR);
    }

    /** ms until the next reset of `period`, at 09:00 local time */
    static long delayToNextReset(int period, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, HOUR);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long candidate;
        if (period == Recap.MODE_WEEK) {
            c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);   // Monday of this week
            candidate = c.getTimeInMillis();
            if (candidate <= now) candidate += 7L * 24 * 3600 * 1000;  // next Monday
        } else if (period == Recap.MODE_MONTH) {
            c.set(Calendar.DAY_OF_MONTH, 1);                // 1st of this month
            candidate = c.getTimeInMillis();
            if (candidate <= now) {
                c.add(Calendar.MONTH, 1);
                c.set(Calendar.DAY_OF_MONTH, 1);            // normalise (31st edge)
                candidate = c.getTimeInMillis();
            }
        } else {
            c.set(Calendar.MONTH, Calendar.JANUARY);
            c.set(Calendar.DAY_OF_MONTH, 1);                // Jan 1
            candidate = c.getTimeInMillis();
            if (candidate <= now) {
                c.add(Calendar.YEAR, 1);
                candidate = c.getTimeInMillis();
            }
        }
        long d = candidate - now;
        return Math.max(d, 1000L);
    }
}
