package com.idemobile.app;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Универсальная заглушка для вкладок «Проводник», «Редактор кода», «GitHub».
 */
public class PlaceholderFragment extends Fragment {

    private static final String ARG_TITLE = "title";
    private static final String ARG_ICON = "icon";

    public static PlaceholderFragment create(String title, int iconRes) {
        PlaceholderFragment f = new PlaceholderFragment();
        Bundle b = new Bundle();
        b.putString(ARG_TITLE, title);
        b.putInt(ARG_ICON, iconRes);
        f.setArguments(b);
        return f;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
        View root = inflater.inflate(R.layout.fragment_placeholder, container, false);

        String title = "Раздел";
        int icon = R.drawable.ic_tab_explorer;
        if (getArguments() != null) {
            title = getArguments().getString(ARG_TITLE, title);
            icon = getArguments().getInt(ARG_ICON, icon);
        }

        ((TextView) root.findViewById(R.id.ph_title)).setText(title);
        ((ImageView) root.findViewById(R.id.ph_icon)).setImageResource(icon);
        return root;
    }
}
