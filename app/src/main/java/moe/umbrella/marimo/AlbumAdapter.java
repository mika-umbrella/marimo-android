package moe.umbrella.marimo;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

/** List adapter that shows Albums (library view) or Tracks (folder view). */
public class AlbumAdapter extends ArrayAdapter<Object> {

    public AlbumAdapter(MainActivity ctx, List<Object> entries) {
        super(ctx, 0, entries);
    }

    public int findFirstBucket(int bucket) {
        for (int i = 0; i < getCount(); i++) {
            Object o = getItem(i);
            if (o instanceof Album && ((Album) o).bucket() == bucket) return i;
        }
        return -1;
    }

    public int findAlbum(Album a) {
        for (int i = 0; i < getCount(); i++)
            if (getItem(i) == a) return i;
        return -1;
    }

    @Override
    public View getView(int pos, View convert, ViewGroup parent) {
        if (convert == null) {
            convert = LayoutInflater.from(getContext())
                    .inflate(R.layout.item_track, parent, false);
        }
        Object o = getItem(pos);
        TextView title = convert.findViewById(R.id.item_title);
        TextView sub = convert.findViewById(R.id.item_artist);
        ImageView art = convert.findViewById(R.id.item_art);
        if (o instanceof Album) {
            Album a = (Album) o;
            title.setText(a.titleLine());
            sub.setText(a.subLine());
            if (a.coverIdx >= 0 && a.tracks.get(a.coverIdx).art != null)
                art.setImageBitmap(a.tracks.get(a.coverIdx).art);
            else
                art.setImageResource(android.R.drawable.ic_menu_myplaces);
        } else {
            Track t = (Track) o;
            title.setText(t.title.isEmpty() ? t.name : t.title);
            sub.setText(t.artist.isEmpty() ? t.name : t.artist);
            if (t.art != null) art.setImageBitmap(t.art);
            else art.setImageResource(android.R.drawable.ic_media_play);
        }
        return convert;
    }
}
