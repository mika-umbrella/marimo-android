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

    private static final int N_BARS = 48;
    private final Paint played = new Paint();
    private final Paint rest = new Paint();
    private final Paint line = new Paint();
    private long seed = 0;
    private int max = 1, progress = 0;
    private int[] heights;
    private boolean tracking;
    private Listener listener;

    public WaveformSeekBar(Context c, AttributeSet a) {
        super(c, a);
        played.setColor(0xFF7DFF7D);
        rest.setColor(0xFF2A2A30);
        line.setColor(0xFF6B6B76);
        heights = new int[N_BARS];
    }

    public void setListener(Listener l) { listener = l; }

    public void setWaveformSeed(long s) {
        if (s == seed) return;
        seed = s;
        long x = s == 0 ? 0x9E3779B97F4A7C15L : s;
        for (int i = 0; i < N_BARS; i++) {
            x ^= x << 13; x ^= x >>> 7; x ^= x << 17;
            heights[i] = (int) (x & 0x7FFFFFFFL) % 100;
        }
        invalidate();
    }

    public void setMax(int m) { max = Math.max(m, 1); invalidate(); }
    public void setProgress(int p) {
        if (p < 0) p = 0;
        if (p > max) p = max;
        if (p != progress) { progress = p; invalidate(); }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        int barW = Math.max(w / N_BARS - 2, 1);
        float frac = (float) progress / max;
        int mid = h / 2;
        int playedX = (int) (w * frac);
        for (int i = 0; i < N_BARS; i++) {
            int x = i * (w / N_BARS) + 1;
            int bh = Math.max((int) (h * 0.9f * heights[i] / 100f), 3);
            int top = mid - bh / 2;
            canvas.drawRect(x, top, x + barW, top + bh,
                    x <= playedX ? played : rest);
        }
        canvas.drawRect(playedX, 0, playedX + 2, h, line);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                tracking = true;
                setProgress((int) (ev.getX() / getWidth() * max));
                return true;
            case MotionEvent.ACTION_UP:
                if (tracking && listener != null) listener.onSeek(progress);
                tracking = false;
                return true;
        }
        return false;
    }
}
