package moe.umbrella.marimo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
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
    private int max = 1, progress = 0;
    private int dragProgress = -1;
    private int[] heights;
    private boolean tracking;
    private Listener listener;
    private boolean hasReal;

    public WaveformSeekBar(Context c, AttributeSet a) {
        super(c, a);
        played.setColor(0xFF7DFF7D);
        rest.setColor(0xFF2A2A30);
        line.setColor(0xFF6B6B76);
        heights = new int[N_BARS];
        for (int i = 0; i < N_BARS; i++) heights[i] = 4;   /* silent baseline */
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
    /** external updates (the 200ms ticker) — ignored while dragging so the
     *  thumb doesn't snap back under the finger */
    public void setProgress(int p) {
        if (tracking) return;
        if (p < 0) p = 0;
        if (p > max) p = max;
        if (p != progress) { progress = p; invalidate(); }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        int barW = Math.max(w / N_BARS - 2, 1);
        float frac = (float) (tracking && dragProgress >= 0 ? dragProgress : progress) / max;
        int mid = h / 2;
        int playedX = (int) (w * frac);
        /* detailed thin waveform — only the UNPLAYED bars are drawn */
        for (int i = 0; i < N_BARS; i++) {
            int x = i * (w / N_BARS) + 1;
            if (x > playedX) {
                int bh = Math.max((int) (h * 0.9f * heights[i] / 100f), 2);
                int top = mid - bh / 2;
                canvas.drawRect(x, top, x + barW, top + bh, rest);
            }
        }
        /* played portion: flat green fill from the left edge */
        if (playedX > 0) canvas.drawRect(0, 0, playedX, h, played);
        canvas.drawRect(playedX, 0, playedX + 2, h, line);
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
                android.util.Log.i("marimo", "seekbar UP drag=" + dragProgress
                        + " max=" + max);
                if (tracking && listener != null) listener.onSeek(dragProgress);
                tracking = false;
                dragProgress = -1;
                invalidate();
                return true;
        }
        return false;
    }
}
