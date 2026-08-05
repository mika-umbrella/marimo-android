package moe.umbrella.marimo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Deterministic circular progress ring with a centered count/label.
 *  Used to show library-scan progress (albums done / total) while the
 *  parallel scan runs, instead of a chunky horizontal bar. */
public class CircleProgress extends View {

    private int progress;
    private int total = 1;
    private String label = "";

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    public CircleProgress(Context c) { this(c, null); }
    public CircleProgress(Context c, AttributeSet a) {
        super(c, a);
        track.setStyle(Paint.Style.STROKE);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void setProgress(int done, int total, String label) {
        this.progress = done;
        this.total = Math.max(1, total);
        this.label = label == null ? "" : label;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float r = Math.min(w, h) / 2f - dp(5);
        float cx = w / 2f, cy = h / 2f;

        float stroke = dp(6);
        track.setColor(Theme.dim());
        track.setStrokeWidth(stroke);
        arc.setColor(Theme.acc());
        arc.setStrokeWidth(stroke);

        box.set(cx - r, cy - r, cx + r, cy + r);
        cv.drawArc(box, 0, 360, false, track);
        float frac = total > 0 ? (float) progress / total : 0;
        if (frac > 0) cv.drawArc(box, -90, 360 * frac, false, arc);

        text.setColor(Theme.txt());
        text.setTextSize(dp(Math.min(w, h) / 4f));
        text.setFakeBoldText(true);
        float ty = cy - (text.ascent() + text.descent()) / 2f;
        cv.drawText(label, cx, ty, text);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
