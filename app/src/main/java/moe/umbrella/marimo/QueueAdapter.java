package moe.umbrella.marimo;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

/** Queue list with small cover thumbs + playing marker, like the library. */
public class QueueAdapter extends ArrayAdapter<Track> {

    public QueueAdapter(MainActivity ctx, List<Track> tracks) {
        super(ctx, 0, tracks);
    }

    @Override
    public View getView(int pos, View convert, ViewGroup parent) {
        if (convert == null) {
            convert = LayoutInflater.from(getContext())
                    .inflate(R.layout.item_track, parent, false);
        }
        Track t = getItem(pos);
        TextView title = convert.findViewById(R.id.item_title);
        TextView sub = convert.findViewById(R.id.item_artist);
        ImageView art = convert.findViewById(R.id.item_art);
        title.setTextColor(Theme.txt());
        sub.setTextColor(Theme.dim());
        int cur = PlaybackService.currentIndex();
        String name = t.title.isEmpty() ? t.name : t.title;
        title.setText((pos == cur ? "▶ " : "") + String.format("%02d", pos + 1)
                + ".  " + name);
        sub.setText(t.artist);
        if (t.art != null) art.setImageBitmap(t.art);
        else art.setImageResource(android.R.drawable.ic_media_play);
        return convert;
    }
}
