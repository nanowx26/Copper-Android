package git.artdeell.mojo.fragments;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;

/** Simple text editor for small config/log files inside the launcher's data. */
public class FileEditorFragment extends Fragment {

    public static final String TAG = "FileEditorFragment";
    private static final String ARG_PATH = "path";

    private File mFile;
    private EditText mInput;
    private ImageButton mSaveButton;
    private String mOriginal = "";
    private boolean mLoaded;

    public FileEditorFragment() {
        super(R.layout.fragment_file_editor);
    }

    public static void open(FragmentActivity activity, File file) {
        Bundle args = new Bundle();
        args.putString(ARG_PATH, file.getAbsolutePath());
        Tools.swapFragment(activity, FileEditorFragment.class, TAG, args);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        String path = args != null ? args.getString(ARG_PATH, null) : null;
        if (path == null) {
            Tools.removeCurrentFragment(requireActivity());
            return;
        }
        mFile = new File(path);

        ImageButton backButton = view.findViewById(R.id.file_editor_back);
        mSaveButton = view.findViewById(R.id.file_editor_save);
        TextView title = view.findViewById(R.id.file_editor_title);
        mInput = view.findViewById(R.id.file_editor_input);
        title.setText(mFile.getName());
        mSaveButton.setEnabled(false);
        backButton.setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
        mSaveButton.setOnClickListener(v -> save());

        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mLoaded && !mOriginal.equals(mInput.getText().toString())) {
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.file_editor_discard_title)
                            .setMessage(R.string.file_editor_discard_message)
                            .setPositiveButton(R.string.file_editor_discard, (d, w) -> leave())
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                } else {
                    leave();
                }
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), callback);

        if (mFile.length() > FileManagerFragment.MAX_EDIT_SIZE) {
            Toast.makeText(requireContext(), R.string.file_editor_too_large, Toast.LENGTH_LONG).show();
            leave();
            return;
        }
        load();
    }

    private void leave() {
        Tools.removeCurrentFragment(requireActivity());
    }

    private void load() {
        MojoApplication.sExecutorService.execute(() -> {
            String text = null;
            try {
                text = Tools.read(mFile);
            } catch (IOException ignored) {}
            String finalText = text;
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (finalText == null) {
                    Toast.makeText(requireContext(), R.string.file_editor_load_failed, Toast.LENGTH_LONG).show();
                    leave();
                    return;
                }
                mOriginal = finalText;
                mInput.setText(finalText);
                mLoaded = true;
                mSaveButton.setEnabled(true);
            });
        });
    }

    private void save() {
        String text = mInput.getText().toString();
        mSaveButton.setEnabled(false);
        MojoApplication.sExecutorService.execute(() -> {
            final boolean ok = writeSafely(text);
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                mSaveButton.setEnabled(true);
                if (ok) {
                    mOriginal = text;
                    Toast.makeText(requireContext(), R.string.file_editor_saved, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireContext(), R.string.file_editor_save_failed, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private boolean writeSafely(String text) {
        try {
            Tools.write(mFile, text);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}