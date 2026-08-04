package moe.umbrella.marimo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Vertical A-Z jump strip (with '#' + 'あ' buckets) on the list edge. */
public class LetterBar extends View {

    public interface Listener { void onPick(int bucket); }

    private static final String LETTERS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ\u3042";
    private final Paint text = new Paint();
    private final Paint highlight = new Paint();
    private int hover = -1;
    private Listener listener;

    public LetterBar(Context c, AttributeSet a) {
        super(c, a);
        text.setColor(0xFF6B6B76);
        text.setTextSize(17f);
        text.setTextAlign(Paint.Align.CENTER);
        highlight.setColor(0xFF1E1E23);
    }

    public void setListener(Listener l) { listener = l; }

    private int pick(int y) {
        int n = LETTERS.length();
        int slot = getHeight() / n;
        if (slot <= 0) slot = 1;
        int i = y / slot;
        if (i < 0) i = 0;
        if (i >= n) i = n - 1;
        return i;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = LETTERS.length();
        int slot = getHeight() / n;
        if (slot <= 0) slot = 1;
        for (int i = 0; i < n; i++) {
            int y = slot * i + (slot / 2) + (int) (text.getTextSize() / 3f);
            if (i == hover) {
                canvas.drawRect(0, slot * i, getWidth(), slot * i + slot, highlight);
                text.setColor(0xFFC9C9D1);
            } else {
                text.setColor(0xFF6B6B76);
            }
            canvas.drawText(String.valueOf(LETTERS.charAt(i)), getWidth() / 2f, y, text);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int i = pick((int) ev.getY());
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                if (i != hover) {
                    hover = i;
                    if (listener != null) listener.onPick(i);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                hover = -1;
                invalidate();
                return true;
        }
        return false;
    }
}
