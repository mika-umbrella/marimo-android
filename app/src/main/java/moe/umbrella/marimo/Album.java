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

    private static final Pattern YEAR = Pattern.compile("\\((\\d{4})\\)");

    private void parse(String name) {
        name = name.trim();
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
        Matcher m = YEAR.matcher(name);
        if (m.find()) {
            year = Integer.parseInt(m.group(1));
            name = (name.substring(0, m.start()) + " " + name.substring(m.end())).trim();
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
