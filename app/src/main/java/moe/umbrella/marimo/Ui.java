package moe.umbrella.marimo;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

/** restyle helpers for the dynamic-gradient look: sets translucent panel
 *  backgrounds with a press highlight + theme-aware text/tint colors. */
public class Ui {

    /** a translucent rounded panel that highlights when pressed */
    public static void panel(View v) {
        v.setBackground(stateBg(Theme.panel(), Theme.panelHi(), dp(v, 14)));
    }

    /** translucent rounded panel that flashes the accent GREEN on press
     *  (settings rows matching the transport/tab buttons) */
    public static void panelPress(View v) {
        v.setBackground(stateBg(Theme.panel(), Theme.rowSel(), dp(v, 14)));
    }

    /** tab: transparent, square highlight that fills the whole button on
     *  press, sub-coloured label */
    public static void tab(Button b) {
        b.setTextColor(Theme.sub());
        b.setBackground(stateBg(Color.TRANSPARENT, Theme.rowSel(), 0)); /* sq corners */
    }

    /** transparent button with a press highlight (transport controls) */
    public static void press(View v) {
        v.setBackground(stateBg(Color.TRANSPARENT, Theme.rowSel(), dp(v, 12)));
    }

    public static void text(TextView t, int which) {
        t.setTextColor(which == 0 ? Theme.txt() : which == 1 ? Theme.dim() : Theme.acc());
    }

    public static void tint(ImageButton b, int which) {
        b.setColorFilter(which == 0 ? Theme.iconTint() : Theme.iconDim());
    }

    private static StateListDrawable stateBg(int idle, int pressed, float radius) {
        GradientDrawable dIdle = new GradientDrawable();
        dIdle.setColor(idle);
        dIdle.setCornerRadius(radius);
        GradientDrawable dPressed = new GradientDrawable();
        dPressed.setColor(pressed);
        dPressed.setCornerRadius(radius);
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, dPressed);
        sld.addState(new int[]{}, dIdle);
        return sld;
    }

    private static int dp(View v, float d) {
        return (int) (d * v.getResources().getDisplayMetrics().density + 0.5f);
    }
}
