package moe.umbrella.marimo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import java.util.Random;

/** Background manager: builds an art-derived, terrain-style backdrop.
 *  The cover's light/dark/mid colours are splashed over the screen using
 *  layered perlin noise (like minecraft terrain seeding), then blurred by
 *  rendering at low res and smooth-scaling up — organic light/dark patches
 *  instead of a flat gradient, with a bias toward dark at the bottom so text
 *  and controls stay readable. */
public class BgManager {

    private static final int CELLS = 2;        /* ~1-2 big sparse blobs */

    /* reused buffers: the perlin grid is constant (fixed seed + size), and the
     * output field is overwritten in place each tick, so the animation loop
     * allocates nothing per frame - no GC pauses, smooth drifting */
    private static Bitmap cached;
    private static int[] cachedPx;

    private static float[][] cachedGrid;
    private static float[][] cachedField;
    private static int cacheW = -1, cacheH = -1, cacheCells = -1;

    /** build an art-derived, terrain-style backdrop that slowly drifts:
     *  `time` (seconds) slides the perlin sampling so the blobs move.
     *  rendered at half screen resolution (the blobs are huge and smooth, so
     *  Android's scale-to-fill is visually identical to full-res) which makes
     *  each tick tiny and rock-regular - no skipped/doubled frames. */
    public static Drawable gradientFor(Context ctx, Bitmap art, int scrim, float time) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int sw = dm.widthPixels, sh = dm.heightPixels;
        int rw = Math.max(180, sw / 2);   /* render resolution = half screen */
        int rh = Math.max(240, sh / 2);

        int[] pal = art != null ? palette(art) : fallbackPalette();

        float dx = 0.015f * time;  /* very gentle slow drift, seamless+tiled */
        float dy = 0.010f * time;
        ensureBuffers(rw, rh, CELLS, dx, dy);
        float[][] field = cachedField;

        if (cached == null || cached.getWidth() != rw || cached.getHeight() != rh) {
            cached = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
            cachedPx = new int[rw * rh];
        }
        int[] px = cachedPx;
        for (int y = 0; y < rh; y++) {
            float v = (float) y / (rh - 1);               /* 0 top .. 1 bottom */
            float darkBias = 0.30f + 0.70f * v;           /* more dark near bottom */
            int yoff = y * rw;
            for (int x = 0; x < rw; x++) {
                float n = field[y][x];                    /* ~ -1..1, coarse */
                float t = (n * 0.5f + 0.5f);              /* 0..1 */
                t = clamp01(t * (1f + 0.7f * v) * darkBias);
                px[yoff + x] = blend(triLerp(pal[0], pal[1], pal[2], t), scrim);
            }
        }
        cached.setPixels(px, 0, rw, 0, 0, rw, rh);

        BitmapDrawable d = new BitmapDrawable(ctx.getResources(), cached);
        d.setFilterBitmap(true);
        return d;
    }

    /* build (once) or slide (each call, in place) the perlin field */
    private static void ensureBuffers(int w, int h, int cells, float dx, float dy) {
        if (cacheW != w || cacheH != h || cacheCells != cells) {
            Random rnd = new Random(0xC0FFEE);
            int gw = cells + 2;
            int gh = Math.max(2, Math.round(cells * (float) h / w) + 2);
            cachedGrid = new float[gh][gw];
            for (int gy = 0; gy < gh; gy++)
                for (int gx = 0; gx < gw; gx++)
                    cachedGrid[gy][gx] = rnd.nextFloat() * 2f - 1f;
            cachedField = new float[h][w];
            cacheW = w; cacheH = h; cacheCells = cells;
        }
        float[][] grid = cachedGrid, out = cachedField;
        int gw = grid[0].length, gh = grid.length;
        float gwx = gw, ghy = gh;                    /* period = `gw`,`gh` cells */
        /* wrap at the real field period (gw/gh), not 1.0 - sliding by exactly
         * one period lands on the identical pattern, so the loop is seamless */
        float ox = (dx - (float) Math.floor(dx)) * gw;
        float oy = (dy - (float) Math.floor(dy)) * gh;
        for (int y = 0; y < h; y++) {
            float fy = (float) y / (h - 1) * ghy + oy;
            int gy = (int) fy; float ty = fy - gy;
            float[] outrow = out[y];
            for (int x = 0; x < w; x++) {
                float fx = (float) x / (w - 1) * gwx + ox;
                int gx = (int) fx; float tx = fx - gx;
                int gx0 = gx % gw, gx1 = (gx + 1) % gw;
                int gy0 = gy % gh, gy1 = (gy + 1) % gh;
                float v00 = grid[gy0][gx0], v10 = grid[gy0][gx1];
                float v01 = grid[gy1][gx0], v11 = grid[gy1][gx1];
                float a = lerp(v00, v10, tx);
                float b = lerp(v01, v11, tx);
                outrow[x] = lerp(a, b, ty);
            }
        }
    }

    /** light->mid->dark smooth interpolation by t in [0,1] */
    private static int triLerp(int light, int mid, int dark, float t) {
        int c;
        if (t < 0.5f) c = blend(light, mid, t * 2f);
        else c = blend(mid, dark, (t - 0.5f) * 2f);
        return c;
    }

    /** light/dark/mid representative colours from the cover */
    private static int[] palette(Bitmap art) {
        int w = art.getWidth(), h = art.getHeight();
        int stride = Math.max(1, (int) Math.sqrt((double) (w * h) / 4000.0));
        long lr = 0, lg = 0, lb = 0;
        long dr = 0, dg = 0, db = 0;
        long mr = 0, mg = 0, mb = 0;
        int lcnt = 0, dcnt = 0, mcnt = 0;
        for (int y = 0; y < h; y += stride) {
            for (int x = 0; x < w; x += stride) {
                int c = art.getPixel(x, y);
                int r0 = Color.red(c), g0 = Color.green(c), b0 = Color.blue(c);
                float lum = (r0 * 0.3f + g0 * 0.59f + b0 * 0.11f) / 255f;
                if (lum > 0.60f) { lr += r0; lg += g0; lb += b0; lcnt++; }
                else if (lum < 0.38f) { dr += r0; dg += g0; db += b0; dcnt++; }
                else { mr += r0; mg += g0; mb += b0; mcnt++; }
            }
        }
        int light = lcnt > 0 ? Color.rgb((int)(lr/lcnt), (int)(lg/lcnt), (int)(lb/lcnt))
                             : 0xFF9FC4D4;
        int dark  = dcnt > 0 ? Color.rgb((int)(dr/dcnt), (int)(dg/dcnt), (int)(db/dcnt))
                             : blend(light, 0xFF20242E, 0.7f);
        int mid   = mcnt > 0 ? Color.rgb((int)(mr/mcnt), (int)(mg/mcnt), (int)(mb/mcnt))
                             : blend(light, dark, 0.5f);
        return new int[]{light, mid, dark};
    }

    private static int[] fallbackPalette() {
        return new int[]{0xFF3A4258, 0xFF232838, 0xFF0D0F14};
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * smooth(t);
    }

    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }

    private static int blend(int base, int over, float t) {
        if (t <= 0f) return base;
        if (t >= 1f) return over;
        int ia = Math.round(255 * (1f - t));
        int a = 255 - ia;
        int r = (Color.red(over) * a + Color.red(base) * ia) / 255;
        int g = (Color.green(over) * a + Color.green(base) * ia) / 255;
        int b = (Color.blue(over) * a + Color.blue(base) * ia) / 255;
        return Color.rgb(r, g, b);
    }

    private static int blend(int base, int over) {
        int a = Color.alpha(over);
        if (a <= 0) return base;
        int ia = 255 - a;
        int r = (Color.red(over) * a + Color.red(base) * ia) / 255;
        int g = (Color.green(over) * a + Color.green(base) * ia) / 255;
        int b = (Color.blue(over) * a + Color.blue(base) * ia) / 255;
        return Color.rgb(r, g, b);
    }
}
