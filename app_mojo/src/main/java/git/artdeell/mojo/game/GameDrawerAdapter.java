package git.artdeell.mojo.game;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import git.artdeell.mojo.R;

public class GameDrawerAdapter extends ArrayAdapter<String> {

    private final int[] icons;

    public GameDrawerAdapter(@NonNull Context context, @NonNull String[] titles, @NonNull int[] icons) {
        super(context, R.layout.item_game_drawer, R.id.game_drawer_title, titles);
        this.icons = icons;
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View view = super.getView(position, convertView, parent);
        ImageView icon = view.findViewById(R.id.game_drawer_icon);
        if (position < icons.length) {
            icon.setImageResource(icons[position]);
            icon.setVisibility(View.VISIBLE);
        } else {
            icon.setVisibility(View.GONE);
        }
        return view;
    }
}