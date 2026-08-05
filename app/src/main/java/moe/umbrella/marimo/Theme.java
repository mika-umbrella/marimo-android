package moe.umbrella.marimo;

import android.graphics.Color;

/** Central palette. dark = current look; light mode swaps text/panel
 *  colors so the art gradient stays the hero either way. */
public class Theme {
    public static boolean dark = true;

    public static final int ACC_DARK = 0xFF7DFF7D;   /* winamp green */
    public static final int ACC_LIGHT = 0xFF1E8A1E;

    public static int acc()      { return dark ? ACC_DARK : ACC_LIGHT; }
    public static int txt()      { return dark ? 0xFFE8DDD0 : 0xFF1B1B20; }
    public static int dim()      { return dark ? 0xFF6B6B76 : 0xFF6E6E76; }
    public static int sub()      { return dark ? 0xFFC9C9D1 : 0xFF3A3A42; }

    /** translucent panel over the art gradient — dark panels are dark
     *  with alpha; light panels are white with alpha */
    public static int panel()    { return dark ? 0xCC141418 : 0xD9F2F0EE; }
    public static int panelHi()  { return dark ? 0xE0232329 : 0xFFE3E1DE; }
    public static int rowBg()    { return dark ? 0x662A2A30 : 0x80FFFFFF; }
    public static int rowSel()   { return dark ? 0x8C145C14 : 0x9928B928; }
    public static int divider()  { return dark ? 0x332A2A30 : 0x401B1B20; }
    public static int iconTint() { return dark ? 0xFFE8DDD0 : 0xFF1B1B20; }
    public static int iconDim()  { return dark ? 0x336B6B76 : 0x551B1B20; }
    public static int tabBar()   { return dark ? 0x590D0D0F : 0x99EAE8E5; }
    public static int tabSep()   { return dark ? 0x59FFFFFF : 0x661B1B20; }
    public static int statusBar(){ return dark ? 0x00000000 : 0x00000000; }
    public static int navBar()   { return dark ? 0xCC0D0D0F : 0xD9EAE8E5; }

    /** background gradient overlay: darkens light art, lightens dark art
     *  so text stays readable on both themes */
    public static int scrim()    { return dark ? 0x99000000 : 0x66FFFFFF; }
}
