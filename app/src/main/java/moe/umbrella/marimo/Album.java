package moe.umbrella.marimo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An album = a music folder. Folder names are our rename scheme:
 *  "ARTIST - (YEAR) ALBUM [FORMAT]" — parsed into artist/album/year. */
public class Album {
    public final String folder;
    public String artist = "";
    public String album;
    public int year;
    public String format;
    public int coverIdx;      // index into tracks for the cover art
    public java.util.List<Track> tracks = new java.util.ArrayList<>();

    public Album(String folder) {
        this.folder = folder;
        this.album = folder;
        parse(folder);
    }

    /* a 4-digit plausible year (1900-2099) */
    private static final Pattern YEAR4 =
            Pattern.compile("(19\\d\\d|20\\d\\d)");
    /* a (..) or [..] bracket group (round and square matched independently,
     * so an open round can't be closed by a square) */
    private static final Pattern BRACKET =
            Pattern.compile("\\(([^()]*)\\)|\\[([^\\[\\]]*)\\]");
    /* a bare standalone year in the album title: not part of a word (letters),
     * a range (X-Y), or inside any bracket — and plausibility-gated by YEAR4. */
    private static final Pattern BARE =
            Pattern.compile("(?<![A-Za-z0-9(\\[\\-])(19\\d\\d|20\\d\\d)(?![A-Za-z0-9\\-])");

    private void parse(String name) {
        name = name.trim();
        /* {..} groups are distro/catalog IDs — strip, never a year */
        name = name.replaceAll("\\{[^}]*\\}", " ").trim();

        /* bracketed year tag is AUTHORITATIVE: a (..)/[..] whose content is a
         * bare year or date with no letters (rejects "(2026 Remaster)"). take
         * the earliest year when there are several "(1999, 2008)" -> 1999. */
        Matcher bm = BRACKET.matcher(name);
        while (bm.find()) {
            String inner = (bm.group(1) != null ? bm.group(1) : bm.group(2)).trim();
            if (inner.isEmpty() || inner.matches(".*[A-Za-z].*")) continue;
            Matcher y4 = YEAR4.matcher(inner);
            int best = Integer.MAX_VALUE;
            boolean found = false;
            while (y4.find()) {
                best = Math.min(best, Integer.parseInt(y4.group(1)));
                found = true;
            }
            if (found) {
                year = best;
                name = (name.substring(0, bm.start())
                        + " " + name.substring(bm.end())).trim();
                break;
            }
        }

        /* now a trailing [FORMAT] is safe to strip (any square-bracket year is
         * already gone above) */
        int fmtStart = name.lastIndexOf('[');
        if (fmtStart > 0 && name.endsWith("]")) {
            format = name.substring(fmtStart + 1, name.length() - 1).trim();
            name = name.substring(0, fmtStart).trim();
        }
        int dash = name.indexOf(" - ");
        if (dash > 0) {
            artist = name.substring(0, dash).trim();
            name = name.substring(dash + 3).trim();
        }
        /* bare standalone year — only if no bracketed year won above, and only
         * a plausible value (1234 / 9801 fall outside 1900-2099 and are ignored) */
        if (year == 0) {
            Matcher m = BARE.matcher(name);
            if (m.find()) {
                year = Integer.parseInt(m.group(1));
                name = (name.substring(0, m.start())
                        + " " + name.substring(m.end())).trim();
            }
        }
        album = name.isEmpty() ? folder : name;
    }

    public String titleLine() {
        if (artist.isEmpty()) return album;
        return album;
    }

    public String subLine() {
        StringBuilder sb = new StringBuilder();
        if (!artist.isEmpty()) sb.append(artist);
        if (year > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(year);
        }
        return sb.toString();
    }

    /** first-char bucket: '#' digits/symbols, A-Z letters, あ non-ascii */
    public int bucket() {
        String n = folder.trim();
        if (n.isEmpty()) return 26;
        char c = n.charAt(0);
        if (c >= 'a' && c <= 'z') return c - 'a' + 1;
        if (c >= 'A' && c <= 'Z') return c - 'A' + 1;
        if (c >= 0x80) return 27;
        return 0;   /* '#' */
    }
}
