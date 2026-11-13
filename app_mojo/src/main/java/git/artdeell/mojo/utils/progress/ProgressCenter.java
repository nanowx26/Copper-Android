package git.artdeell.mojo.utils.progress;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import git.artdeell.mojo.R;
import git.artdeell.mojo.progresskeeper.ProgressKeeper;
import git.artdeell.mojo.progresskeeper.ProgressListener;
import git.artdeell.mojo.progresskeeper.TaskCountListener;

public class ProgressCenter implements TaskCountListener {

    private static final String[] KEYS = {
            "download_minecraft", "unpack_runtime", "install_modpack", "authenticate",
            "download_verlist", "instance_install", "data_migration", "extract_components",
            "extract_single_files", "driver_install"
    };
    private static final long RENDER_INTERVAL_MS = 100;

    private static class Task {
        int progress = -1;
        int resid = -1;
        Object[] args;
    }

    private static class Row {
        View root;
        TextView text;
        LinearProgressIndicator bar;
        TextView percent;
    }

    private final Activity activity;
    private final LayoutInflater inflater;
    private final ImageButton downloadButton;
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final Map<String, KeyListener> listeners = new LinkedHashMap<>();
    private final Map<String, Row> rows = new LinkedHashMap<>();

    private AlertDialog dialog;
    private LinearLayout taskContainer;
    private TextView taskCountView;
    // Sticky: only set false when the user taps the download icon (show()).
    // No progress event ever clears it, so Hide stays hidden.
    private boolean minimized;
    private boolean renderPosted;

    public ProgressCenter(Activity activity, ImageButton downloadButton) {
        this.activity = activity;
        this.inflater = LayoutInflater.from(activity);
        this.downloadButton = downloadButton;
        downloadButton.setOnClickListener(v -> show());
        for (String key : KEYS) {
            KeyListener listener = new KeyListener(key);
            listeners.put(key, listener);
            ProgressKeeper.addListener(key, listener);
        }
        ProgressKeeper.addTaskCountListener(this);
    }

    public void detach() {
        for (Map.Entry<String, KeyListener> entry : listeners.entrySet()) {
            ProgressKeeper.removeListener(entry.getKey(), entry.getValue());
        }
        ProgressKeeper.removeTaskCountListener(this);
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    /** User tapped the minimized download icon: un-minimize and show the dialog. */
    public void show() {
        minimized = false;
        updateUi();
    }

    /** User tapped Hide: minimize to the icon. Stays minimized until show() is tapped. */
    private void hide() {
        minimized = true;
        updateUi();
    }

    /** Single decision point: dialog vs. icon vs. nothing. Never changes 'minimized'. */
    private void updateUi() {
        if (tasks.isEmpty()) {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
            downloadButton.setVisibility(View.GONE);
            return;
        }
        if (minimized) {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
            downloadButton.setVisibility(View.VISIBLE);
        } else {
            downloadButton.setVisibility(View.GONE);
            if (dialog == null) buildDialog();
            if (!dialog.isShowing() && !activity.isFinishing()) dialog.show();
            renderNow();
        }
    }

    @Override
    public boolean onUpdateTaskCount(int count) {
        activity.runOnUiThread(() -> {
            if (count <= 0) {
                tasks.clear();
                rows.clear();
                if (taskContainer != null) taskContainer.removeAllViews();
            }
            updateUi();
        });
        return false;
    }

    private void buildDialog() {
        View view = inflater.inflate(R.layout.dialog_progress_center, null);
        taskCountView = view.findViewById(R.id.progress_center_count);
        taskContainer = view.findViewById(R.id.progress_center_tasks);
        dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.progress_center_title)
                .setView(view)
                .setNegativeButton(R.string.progress_center_hide, (d, w) -> hide())
                .setCancelable(false)
                .create();
    }

    private void scheduleRender() {
        if (dialog == null || !dialog.isShowing() || renderPosted) return;
        renderPosted = true;
        taskContainer.postDelayed(() -> {
            renderPosted = false;
            renderNow();
        }, RENDER_INTERVAL_MS);
    }

    private void renderNow() {
        if (dialog == null || !dialog.isShowing() || taskContainer == null) return;
        taskCountView.setText(activity.getResources().getQuantityString(
                R.plurals.progress_center_tasks, tasks.size(), tasks.size()));

        Iterator<Map.Entry<String, Row>> it = rows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Row> entry = it.next();
            if (!tasks.containsKey(entry.getKey())) {
                taskContainer.removeView(entry.getValue().root);
                it.remove();
            }
        }

        for (Map.Entry<String, Task> entry : tasks.entrySet()) {
            Task task = entry.getValue();
            Row row = rows.get(entry.getKey());
            if (row == null) {
                row = new Row();
                row.root = inflater.inflate(R.layout.item_progress_task, taskContainer, false);
                row.text = row.root.findViewById(R.id.progress_task_text);
                row.bar = row.root.findViewById(R.id.progress_task_bar);
                row.percent = row.root.findViewById(R.id.progress_task_percent);
                rows.put(entry.getKey(), row);
                taskContainer.addView(row.root);
            }
            String text = "";
            if (task.resid != -1) {
                try { text = activity.getString(task.resid, task.args); }
                catch (Exception e) { text = ""; }
            } else if (task.args != null && task.args.length > 0 && task.args[0] instanceof String) {
                text = (String) task.args[0];
            }
            row.text.setText(text);
            row.text.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
            if (task.progress < 0) {
                row.bar.setIndeterminate(true);
                row.percent.setText("");
            } else {
                row.bar.setIndeterminate(false);
                row.bar.setProgress(task.progress);
                row.percent.setText(task.progress + "%");
            }
        }
    }

    private class KeyListener implements ProgressListener {
        private final String key;

        KeyListener(String key) {
            this.key = key;
        }

        @Override
        public void onProgressStarted() {
            activity.runOnUiThread(() -> {
                tasks.put(key, new Task());
                updateUi();
            });
        }

        @Override
        public void onProgressUpdated(int progress, int resid, Object... args) {
            activity.runOnUiThread(() -> {
                Task task = tasks.get(key);
                if (task == null) {
                    task = new Task();
                    tasks.put(key, task);
                }
                task.progress = progress;
                task.resid = resid;
                task.args = args;
                scheduleRender();
            });
        }

        @Override
        public void onProgressEnded() {
            activity.runOnUiThread(() -> {
                tasks.remove(key);
                if (tasks.isEmpty()) {
                    rows.clear();
                    if (taskContainer != null) taskContainer.removeAllViews();
                }
                updateUi();
            });
        }
    }
}