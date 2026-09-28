package moe.umbrella.marimo;

import java.text.Normalizer;
import java.util.Locale;

/** Which file in an album folder is the cover, decided from its name alone.
 *
 *  Deliberately pure Java (no Android types) so {@link CoverNameTest} runs it on
 *  the JVM against real filenames from a library — the same reason WaveSidecar
 *  is separate from the scanner.
 *
 *  Two families qualify:
 *    - the conventional names: cover / folder / front / album, any image
 *      extension, case-insensitively (Folder.jpg, COVER.JPEG) — plus any
 *      `cover*` variant, which is how the extra scans of a cover arrive
 *      (`cover_1.jpg`, `cover_1_2_3.jpg`, `Cover [Limited Edition].jpg`,
 *      `cover 2.jpeg`);
 *    - the album's own "ARTIST - ALBUM" filename, which is how plenty of rips
 *      ship their art. Album folders read "ARTIST - (YEAR) ALBUM [FORMAT]", so
 *      both sides are folded by {@link #key} before comparing: drop the (year)
 *      and [format], unify the dash characters (folders use -, – and —), NFC,
 *      lower case.
 *
 *  Ranks are ordered so a folder holding several candidates resolves the same
 *  way every time, and always to the deliberate front cover when there is one.
 *  `album` is matched exactly, not as a prefix: `AlbumArtSmall.jpg` and
 *  `albumart.jpg` are 6 KB Windows Media Player thumbnails, not covers. */
public final class CoverName {

    public static final int NOT_A_COVER = -1;

    private CoverName() { }

    /** 0 cover, 1 folder, 2 front, 3 album, 4 a cover* variant, 5 the
     *  artist-album name, -1 not a cover. */
    public static int rank(String name, String folderName) {
        if (name == null) return NOT_A_COVER;
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return NOT_A_COVER;
        String stem = name.substring(0, dot).toLowerCase(Locale.ROOT);
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!(ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png")
                || ext.equals("bmp") || ext.equals("webp"))) return NOT_A_COVER;
        if (stem.equals("cover")) return 0;
        if (stem.equals("folder")) return 1;
        if (stem.equals("front")) return 2;
        if (stem.equals("album")) return 3;
        if (stem.startsWith("cover")) return 4;
        String fk = key(folderName);
        return !fk.isEmpty() && key(stem).equals(fk) ? 5 : NOT_A_COVER;
    }

    /** comparable form of a folder name or a cover filename. */
    public static String key(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFC);
        t = t.replaceAll("\\([^)]*\\)", " ").replaceAll("\\[[^\\]]*\\]", " ");
        t = t.replaceAll("[\u2010-\u2015\u2212]", "-");
        t = t.replaceAll("\\s*-\\s*", " - ");
        return t.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
