package git.artdeell.mojo.prefs.screens;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import git.artdeell.mojo.R;

public class LauncherPreferenceRecorderFragment extends PreferenceFragmentCompat {

    private static final String TYPE_INTERNAL = "internal";

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.pref_recorder, rootKey);
        ListPreference type = findPreference("recorder_type");
        Preference includeUi = findPreference("recorder_include_ui");
        if (type == null || includeUi == null) return;
        includeUi.setEnabled(TYPE_INTERNAL.equals(type.getValue()));
        type.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean internal = TYPE_INTERNAL.equals(newValue);
            includeUi.setEnabled(internal);
            if (internal) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.recorder_experimental_title)
                        .setMessage(R.string.recorder_experimental_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
            return true;
        });
    }
}