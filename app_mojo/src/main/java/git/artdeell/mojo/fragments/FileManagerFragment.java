package git.artdeell.mojo.fragments;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;

/**
 * Built-in file manager scoped to the launcher's own data. The app can always read its own
 * external files dir, so this works on every Android version without any storage permission,
 * unlike external file managers which are locked out of Android/data on modern releases.
 * Navigation is confined to the root it was opened with.
 */
public class FileManagerFragment extends Fragment {

    public static final String TAG = "FileManagerFragment";
    private static final String ARG_ROOT = "root";
    private static final String STATE_CURRENT = "current";
    static final long MAX_EDIT_SIZE = 1024 * 1024;

    private static final String[] TEXT_EXTENSIONS = {
            "txt", "json", "cfg", "conf", "config", "properties", "toml", "ini", "log",
            "xml", "yaml", "yml", "csv", "md", "lang", "mcmeta", "snbt", "info"
    };
    private static final String[] ARCHIVE_EXTENSIONS = {"zip", "jar", "mrpack", "rar", "7z", "gz", "tar"};

    private File mRoot;
    private File mCurrent;
    private String mRootPath;
    private EntryAdapter mAdapter;
    private TextView mTitle, mPath, mEmpty;
    private OnBackPressedCallback mUpCallback;

    public FileManagerFragment() {
        super(R.layout.fragment_file_manager);
    }

    /** Opens the file manager rooted at dir. Pass a launcher-data directory only. */
    public static void open(FragmentActivity activity, File dir) {
        Bundle args = new Bundle();
        if (dir != null) args.putString(ARG_ROOT, dir.getAbsolutePath());
        Tools.swapFragment(activity, FileManagerFragment.class, TAG, args);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mRoot = resolveRoot();
        try {
            mRootPath = mRoot.getCanonicalPath();
        } catch (IOException e) {
            mRootPath = mRoot.getAbsolutePath();
        }
        mRoot = new File(mRootPath);

        ImageButton backButton = view.findViewById(R.id.file_manager_back);
        ImageButton newFolderButton = view.findViewById(R.id.file_manager_new_folder);
        ImageButton externalButton = view.findViewById(R.id.file_manager_external);
        mTitle = view.findViewById(R.id.file_manager_title);
        mPath = view.findViewById(R.id.file_manager_path);
        mEmpty = view.findViewById(R.id.file_manager_empty);
        RecyclerView recycler = view.findViewById(R.id.file_manager_recycler);

        if (getParentFragment() instanceof MainMenuFragment) {
            backButton.setVisibility(View.GONE);
        } else {
            backButton.setOnClickListener(v -> {
                if (canGoUp()) goUp();
                else requireActivity().onBackPressed();
            });
        }
        newFolderButton.setOnClickListener(v -> showNameDialog(null));
        externalButton.setOnClickListener(v -> {
            if (mCurrent != null) Tools.openPath(requireContext(), mCurrent, false);
        });

        mAdapter = new EntryAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(mAdapter);

        mUpCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (canGoUp()) goUp();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), mUpCallback);

        File start = mRoot;
        String saved = savedInstanceState != null ? savedInstanceState.getString(STATE_CURRENT, null) : null;
        if (saved != null) {
            File restored = new File(saved);
            if (isInsideRoot(restored)) start = restored;
        }
        load(start);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mCurrent != null) outState.putString(STATE_CURRENT, mCurrent.getAbsolutePath());
    }

    private File resolveRoot() {
        Bundle args = getArguments();
        String path = args != null ? args.getString(ARG_ROOT, null) : null;
        if (path != null) return new File(path);
        try {
            Instance instance = Instances.loadSelectedInstance();
            if (instance != null) return instance.getGameDirectory();
        } catch (Exception ignored) {}
        return new File(Tools.DIR_GAME_NEW);
    }

    private boolean isInsideRoot(File file) {
        try {
            String path = file.getCanonicalPath();
            return path.equals(mRootPath) || path.startsWith(mRootPath + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean canGoUp() {
        return mCurrent != null && isInsideRoot(mCurrent)
                && !mCurrent.getAbsolutePath().equals(mRootPath);
    }

    private void goUp() {
        File parent = mCurrent != null ? mCurrent.getParentFile() : null;
        if (parent != null && isInsideRoot(parent)) load(parent);
    }

    private void load(File dir) {
        MojoApplication.sExecutorService.execute(() -> {
            File[] listed = dir.listFiles();
            List<File> entries = new ArrayList<>();
            if (listed != null) {
                Arrays.sort(listed, (a, b) -> {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                });
                entries.addAll(Arrays.asList(listed));
            }
            Tools.runOnUiThread(() -> {
                if (!isAdded() || getView() == null) return;
                mCurrent = dir;
                mTitle.setText(dir.getName());
                mPath.setText(dir.getAbsolutePath());
                mAdapter.setEntries(entries, canGoUp());
                mEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                mUpCallback.setEnabled(canGoUp());
            });
        });
    }

    private void showEntryOptions(File file) {
        List<String> actions = new ArrayList<>();
        List<Runnable> handlers = new ArrayList<>();
        actions.add(getString(R.string.file_manager_open_external));
        handlers.add(() -> Tools.openPath(requireContext(), file, false));
        if (file.isFile()) {
            if (isTextFile(file) && file.length() <= MAX_EDIT_SIZE) {
                actions.add(getString(R.string.file_manager_edit_text));
                handlers.add(() -> FileEditorFragment.open(requireActivity(), file));
            }
            actions.add(getString(R.string.file_manager_share));
            handlers.add(() -> Tools.openPath(requireContext(), file, true));
        }
        actions.add(getString(R.string.file_manager_rename));
        handlers.add(() -> showNameDialog(file));
        actions.add(getString(R.string.global_delete));
        handlers.add(() -> confirmDelete(file));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(file.getName())
                .setItems(actions.toArray(new CharSequence[0]), (d, which) -> handlers.get(which).run())
                .show();
    }

    /** existing == null creates a new folder in the current directory, otherwise renames. */
    private void showNameDialog(@Nullable File existing) {
        View content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_file_name, null);
        EditText input = content.findViewById(R.id.file_name_input);
        if (existing != null) {
            input.setText(existing.getName());
            input.setSelection(input.getText().length());
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(existing != null ? R.string.file_manager_rename : R.string.file_manager_new_folder)
                .setView(content)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!isValidName(name)) {
                        Toast.makeText(requireContext(), R.string.file_manager_invalid_name, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    File parent = existing != null ? existing.getParentFile() : mCurrent;
                    File target = new File(parent, name);
                    MojoApplication.sExecutorService.execute(() -> {
                        int result;
                        if (target.exists()) result = 1;
                        else if (existing != null ? existing.renameTo(target) : target.mkdirs()) result = 0;
                        else result = 2;
                        Tools.runOnUiThread(() -> {
                            if (!isAdded()) return;
                            if (result == 1) {
                                Toast.makeText(requireContext(), R.string.file_manager_exists, Toast.LENGTH_LONG).show();
                            } else if (result == 2) {
                                Toast.makeText(requireContext(), R.string.file_manager_op_failed, Toast.LENGTH_LONG).show();
                            }
                            load(mCurrent);
                        });
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static boolean isValidName(String name) {
        return !name.isEmpty() && !name.contains("/") && !name.equals(".") && !name.equals("..");
    }

    private void confirmDelete(File file) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.global_delete)
                .setMessage(getString(R.string.file_manager_delete_confirm, file.getName()))
                .setPositiveButton(R.string.global_delete, (d, w) ->
                        MojoApplication.sExecutorService.execute(() -> {
                            boolean ok = deleteRecursively(file);
                            Tools.runOnUiThread(() -> {
                                if (!isAdded()) return;
                                Toast.makeText(requireContext(),
                                        ok ? R.string.file_manager_deleted : R.string.file_manager_op_failed,
                                        Toast.LENGTH_SHORT).show();
                                load(mCurrent);
                            });
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Symlinked folders pointing outside the root are unlinked, never followed. */
    private boolean deleteRecursively(File file) {
        if (file.isDirectory() && isInsideRoot(file)) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        return file.delete() || !file.exists();
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isTextFile(File file) {
        String ext = extensionOf(file.getName());
        for (String e : TEXT_EXTENSIONS) if (e.equals(ext)) return true;
        return false;
    }

    private static int iconFor(File file) {
        if (file.isDirectory()) return R.drawable.ic_folder;
        String ext = extensionOf(file.getName());
        for (String e : ARCHIVE_EXTENSIONS) if (e.equals(ext)) return R.drawable.ic_file_archive;
        for (String e : TEXT_EXTENSIONS) if (e.equals(ext)) return R.drawable.ic_drawer_log;
        return R.drawable.ic_file_generic;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024f);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024f * 1024));
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024f * 1024 * 1024));
    }

    private class EntryAdapter extends RecyclerView.Adapter<EntryHolder> {
        private final List<File> entries = new ArrayList<>();
        private boolean showUp;

        @SuppressLint("NotifyDataSetChanged")
        void setEntries(List<File> files, boolean up) {
            entries.clear();
            entries.addAll(files);
            showUp = up;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public EntryHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new EntryHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_file_entry, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull EntryHolder holder, int position) {
            if (showUp && position == 0) holder.bind(null);
            else holder.bind(entries.get(showUp ? position - 1 : position));
        }

        @Override
        public int getItemCount() {
            return entries.size() + (showUp ? 1 : 0);
        }
    }

    private class EntryHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name, info;

        EntryHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.file_entry_icon);
            name = itemView.findViewById(R.id.file_entry_name);
            info = itemView.findViewById(R.id.file_entry_info);
        }

        void bind(@Nullable File file) {
            if (file == null) {
                icon.setImageResource(R.drawable.ic_folder);
                name.setText("..");
                info.setText(R.string.file_manager_up);
                itemView.setOnClickListener(v -> goUp());
                itemView.setOnLongClickListener(null);
                return;
            }
            icon.setImageResource(iconFor(file));
            name.setText(file.getName());
            CharSequence date = DateUtils.getRelativeTimeSpanString(file.lastModified());
            info.setText(file.isDirectory() ? date : date + " • " + formatSize(file.length()));
            itemView.setOnClickListener(v -> {
                if (file.isDirectory()) load(file);
                else showEntryOptions(file);
            });
            itemView.setOnLongClickListener(v -> {
                showEntryOptions(file);
                return true;
            });
        }
    }
}