package moe.umbrella.marimo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The cover-name rule, against filenames that actually exist in a library.
 *
 *  The interesting cases are the ones that must NOT match: a tour-booklet scan,
 *  a disc scan, a "proof" print and a *different* album's artist-album name all
 *  sit next to real covers, and any of them matching would silently give an
 *  album the wrong art. */
public class CoverNameTest {

    /* ---------- the album's own "ARTIST - ALBUM" filename ---------- */

    @Test public void artistAlbumNamesMatchTheFolder() {
        // (folder, image) — the folder is "ARTIST - (YEAR) ALBUM [FORMAT]"
        String[][] real = {
            {"96-glass & Lzie - (2021) Rave Like Mashing [OPUS]",
             "96-glass & Lzie - Rave Like Mashing.jpg"},
            {"Cotton Pantie's - (2002) My Sweet Honey Biscuit! [OPUS]",
             "Cotton Pantie's - My Sweet Honey Biscuit!.jpg"},
            {"DJ Sharpnel - (2005) Mad Breaks [OPUS]",
             "DJ Sharpnel - MAD BREAKS.jpg"},              // case differs
            {"MASS OF THE FERMENTING DREGS - (2010) ゼロコンマ、色とりどりの世界 [OPUS]",
             "MASS OF THE FERMENTING DREGS - ゼロコンマ、色とりどりの世界.jpg"},
            {"きのこ帝国 - (2013) eureka [OPUS]",
             "きのこ帝国 - eureka.jpg"},
            {"Babymetal - (2014) Babymetal [OPUS]",
             "Babymetal - Babymetal.jpg"},
        };
        for (String[] c : real) {
            assertEquals("cover: " + c[1] + " in " + c[0],
                    5, CoverName.rank(c[1], c[0]));
        }
    }

    @Test public void dashFlavourDoesNotMatter() {
        // the folder uses an en dash, the image a plain hyphen
        assertEquals(5, CoverName.rank("DJ Sharpnel - 悩殺♥ ハードブレイク.jpg",
                "DJ Sharpnel \u2013 (2004) 悩殺♥ ハードブレイク [OPUS]"));
        // and an em dash, as in ヒトリエ — (2014) …
        assertEquals(5, CoverName.rank("ヒトリエ - WONDER and WONDER.jpg",
                "ヒトリエ \u2014 (2014) WONDER and WONDER [OPUS]"));
    }

    @Test public void parenthesesInsideTheAlbumTitleAreNotSpecial() {
        // both sides lose "(KSL Edition)" together, so they still agree
        assertEquals(5, CoverName.rank("Rammstein - Mutter (KSL Edition).jpg",
                "Rammstein - (2019) Mutter (KSL Edition) [OPUS]"));
    }

    @Test public void decomposedJapaneseStillMatches() {
        // U+30D0 (バ) vs U+30CF + U+3099 — NFC folding on both sides
        String nfd = java.text.Normalizer.normalize("結束バンド - 結束バンド.jpg",
                java.text.Normalizer.Form.NFD);
        assertEquals("NFD input should still match",
                5, CoverName.rank(nfd, "結束バンド - (2022) 結束バンド [OPUS]"));
    }

    /* ---------- must NOT match ---------- */

    @Test public void bookletScansAndOtherJunkAreNotCovers() {
        String folder = "Angus McSix - (2023) Angus McSix and the Sword of Power [OPUS]";
        String[] notCovers = {
            "Booklet_01.jpg", "Booklet_11.jpg", "Disc_01.jpg", "Disc_02_matrix.jpg",
            "Digipak_Ouside.jpg", "Digipak_Inside_01_left_center.jpg",
        };
        for (String n : notCovers)
            assertEquals(n, CoverName.NOT_A_COVER, CoverName.rank(n, folder));

        String arc = "Archspire - (2014) The Lucid Collective [OPUS]";
        assertEquals("a 'proof' print next to the real cover",
                CoverName.NOT_A_COVER,
                CoverName.rank("00. Archspire - The Lucid Collective proof.jpg", arc));

        String boris = "Boris - (2020) Volume Five Pink Days [OPUS]";
        assertEquals("artist - album - extra is a different name",
                CoverName.NOT_A_COVER,
                CoverName.rank("Boris - Volume Five -Pink Days- - Archive2020_text_Five.png", boris));

        assertEquals(CoverName.NOT_A_COVER, CoverName.rank("output.png", "Boris - (1996) Absolutego [OPUS]"));
    }

    @Test public void anotherAlbumsNameDoesNotMatch() {
        assertEquals(CoverName.NOT_A_COVER,
                CoverName.rank("Babymetal - Babymetal.jpg",
                        "96-glass & Lzie - (2021) Rave Like Mashing [OPUS]"));
    }

    @Test public void nonImagesAndOddNamesAreNotCovers() {
        String folder = "SICK HACK - (2023) BOCCHI THE ROCK! EXTRA MUSIC 3 [OPUS]";
        assertEquals(CoverName.NOT_A_COVER, CoverName.rank("waves.marimo", folder));
        assertEquals(CoverName.NOT_A_COVER, CoverName.rank("01 ワタシダケユウレイ.opus", folder));
        assertEquals(CoverName.NOT_A_COVER, CoverName.rank("cover", folder));       // no extension
        assertEquals(CoverName.NOT_A_COVER, CoverName.rank("cover.txt", folder));  // not an image
        assertEquals(CoverName.NOT_A_COVER, CoverName.rank(null, folder));
    }

    /* ---------- conventional names, and the order they win in ---------- */

    @Test public void conventionalNamesRankAndBeatTheArtistAlbumName() {
        String folder = "cephalo - (2025) gloaming point [OPUS]";
        assertEquals(0, CoverName.rank("cover.jpg", folder));
        assertEquals(0, CoverName.rank("COVER.JPEG", folder));
        assertEquals(1, CoverName.rank("Folder.png", folder));
        assertEquals(2, CoverName.rank("front.webp", folder));
        assertEquals(CoverName.NOT_A_COVER, CoverName.rank("cover.webm", folder));

        // a folder with both must resolve to cover.jpg, not the scan
        String other = "96-glass & Lzie - (2021) Rave Like Mashing [OPUS]";
        assertTrue(CoverName.rank("cover.jpg", other)
                < CoverName.rank("96-glass & Lzie - Rave Like Mashing.jpg", other));
    }

    @Test public void albumAndCoverVariantsCountButRankBelowPlainCover() {
        String folder = "SICK HACK - (2023) BOCCHI THE ROCK! EXTRA MUSIC 3 [OPUS]";
        assertEquals(3, CoverName.rank("album.jpg", folder));
        assertEquals(3, CoverName.rank("Album.JPG", folder));

        // cover* variants: the extra scans of a cover, numbered or bracketed
        assertEquals(4, CoverName.rank("cover_1.jpg", folder));
        assertEquals(4, CoverName.rank("cover_1_2_3_4_5_6_7.jpg", folder));
        assertEquals(4, CoverName.rank("cover 2.jpeg", folder));
        assertEquals(4, CoverName.rank("Cover [Limited Edition].jpg", folder));
        assertEquals(4, CoverName.rank("Cover no obi.jpg", folder));

        // ...but never ahead of a plain cover, and ahead of the artist-album name
        assertTrue(CoverName.rank("cover.jpg", folder) < CoverName.rank("cover_1.jpg", folder));
        assertTrue(CoverName.rank("cover_1.jpg", folder)
                < CoverName.rank("SICK HACK - BOCCHI THE ROCK! EXTRA MUSIC 3.jpg", folder));
    }

    @Test public void windowsMediaPlayerThumbnailsAreNotCovers() {
        // 6 KB thumbnails that sit next to real art; matching these would put a
        // postage stamp on the player. `album` is exact for exactly this reason.
        String folder = "Rammstein - (2019) RAMMSTEIN [OPUS]";
        assertEquals(CoverName.NOT_A_COVER, CoverName.rank("AlbumArtSmall.jpg", folder));
        assertEquals(CoverName.NOT_A_COVER, CoverName.rank("albumart.jpg", folder));
        assertEquals(CoverName.NOT_A_COVER, CoverName.rank("Folder_thumb.png", folder));
    }
}
