package moe.umbrella.marimo;

import android.graphics.Bitmap;

/** One playable track: a path or content URI + tags from the C core. */
public class Track {
    public final String token;   // absolute path or content:// URI
    public final String name;    // filename
    public String title = "";
    public String artist = "";
    public String album = "";
    public int durationMs;
    public int track;
    public int disc;
    public Bitmap art;           // embedded cover from the C core

    public Track(String token, String name) {
        this.token = token;
        this.name = name;
    }

    public String display() {
        if (!title.isEmpty()) {
            return artist.isEmpty() ? title : artist + " — " + title;
        }
        return name;
    }
}
