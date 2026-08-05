package moe.umbrella.marimo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Seekbar with a decorative waveform: bars are a stable PRNG seeded by
 *  the track, so each song gets its own little skyline. Played portion
 *  is drawn in the accent green. (Real waveform needs a decode pass —
 *  this is the cute cheap version.) */
public class WaveformSeekBar extends View {

    public interface Listener { void onSeek(int ms); }

    private static final int N_BARS = 96;
    private final Paint played = new Paint();
    private final Paint rest = new Paint();
    private final Paint line = new Paint();
    private final Paint dot = new Paint();
    private int max = 1, progress = 0;
    private int dragProgress = -1;
    private long ignoreTicksUntil;
    private int[] heights;
    private boolean tracking;
    private Listener listener;
    private boolean hasReal;
    private final Path wavePath = new Path();

    public WaveformSeekBar(Context c, AttributeSet a) {
        super(c, a);
        applyTheme();
        heights = new int[N_BARS];
        for (int i = 0; i < N_BARS; i++) heights[i] = 4;   /* silent baseline */
    }

    public void applyTheme() {
        played.setColor(Theme.acc());
        rest.setColor(Theme.dim());
        line.setColor(Theme.dim());
        dot.setColor(Theme.acc());
        invalidate();
    }

    public void setListener(Listener l) { listener = l; }

    /** use a real extracted waveform (peaks 0..100) */
    public void setWaveformPeaks(int[] peaks) {
        if (peaks == null) return;
        hasReal = true;
        for (int i = 0; i < N_BARS; i++)
            heights[i] = peaks[Math.min(i, peaks.length - 1)];
        invalidate();
    }

    /** back to the silent flat baseline */
    public void resetWaveform() {
        hasReal = false;
        for (int i = 0; i < N_BARS; i++) heights[i] = 4;
        invalidate();
    }

    public void setMax(int m) { max = Math.max(m, 1); invalidate(); }
    /** external updates (the 200ms ticker) — ignored while dragging AND
     *  for a short grace after a seek, so the bar holds the dragged
     *  position while the player catches up (no flash-back) */
    public void setProgress(int p) {
        if (tracking || SystemClock.uptimeMillis() < ignoreTicksUntil) return;
        if (p < 0) p = 0;
        if (p > max) p = max;
        if (p != progress) { progress = p; invalidate(); }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        float frac = (float) (tracking && dragProgress >= 0 ? dragProgress : progress) / max;
        int playedX = (int) (w * frac);
        int mid = h / 2;

        /* waveform silhouette: dark, full height (the envelope) */
        wavePath.reset();
        wavePath.moveTo(0, mid);
        float colW = w / (float) N_BARS;
        for (int i = 0; i < N_BARS; i++) {
            float x = i * colW + colW / 2f;
            float amp = h * 0.42f * heights[i] / 100f;
            wavePath.lineTo(x, mid - amp);
        }
        wavePath.lineTo(w, mid);
        for (int i = N_BARS - 1; i >= 0; i--) {
            float x = i * colW + colW / 2f;
            float amp = h * 0.42f * heights[i] / 100f;
            wavePath.lineTo(x, mid + amp);
        }
        wavePath.close();
        canvas.drawPath(wavePath, rest);

        /* played portion: green fill CLIPPED to the waveform shape, so
         * the fill follows the envelope instead of the full bar area */
        if (playedX > 0) {
            canvas.save();
            canvas.clipPath(wavePath);
            canvas.drawRect(0, 0, playedX, h, played);
            canvas.restore();
        }
        /* actual seekbar: centre line + position dot */
        canvas.drawLine(0, mid, w, mid, line);
        canvas.drawCircle(playedX, mid, 9, line);   /* grey halo */
        canvas.drawCircle(playedX, mid, 6, dot);
        canvas.drawCircle(playedX, mid, 3, played);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                tracking = true;
                dragProgress = (int) (ev.getX() / getWidth() * max);
                if (dragProgress < 0) dragProgress = 0;
                if (dragProgress > max) dragProgress = max;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (tracking && listener != null) listener.onSeek(dragProgress);
                /* optimistic: keep the bar at the dragged position while
                 * the player seeks — ticker corrections are ignored for a
                 * beat so it doesn't flash back to the old spot */
                if (dragProgress >= 0) progress = dragProgress;
                ignoreTicksUntil = SystemClock.uptimeMillis() + 700;
                tracking = false;
                dragProgress = -1;
                invalidate();
                return true;
        }
        return false;
    }
}
