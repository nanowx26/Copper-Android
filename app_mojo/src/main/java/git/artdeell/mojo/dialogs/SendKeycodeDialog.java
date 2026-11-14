package git.artdeell.mojo.dialogs;

import android.app.Activity;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import git.artdeell.mojo.R;
import git.artdeell.mojo.utils.KeycodeUtils;

/**
 * Visual keyboard for sending custom keycodes, replacing the old scrolling list.
 * Keys are laid out like a real keyboard over two pages; several keycaps can be
 * selected (in tap order) and are sent one after another when Send is pressed.
 * A keycap whose name isn't in the launcher's keycode table renders disabled,
 * so the layout can never reference an index that doesn't exist.
 */
public final class SendKeycodeDialog {

    private static class Key {
        final String display;
        final String name;
        final float weight;

        Key(String display, String name, float weight) {
            this.display = display;
            this.name = name;
            this.weight = weight;
        }
    }

    private static Key k(String display, String name, float weight) {
        return new Key(display, name, weight);
    }

    private static final Key[][] MAIN_ROWS = {
            {k("Esc", "ESCAPE", 1.3f), k("F1", "F1", 1f), k("F2", "F2", 1f), k("F3", "F3", 1f),
                    k("F4", "F4", 1f), k("F5", "F5", 1f), k("F6", "F6", 1f), k("F7", "F7", 1f),
                    k("F8", "F8", 1f), k("F9", "F9", 1f), k("F10", "F10", 1f), k("F11", "F11", 1f),
                    k("F12", "F12", 1f)},
            {k("`", "GRAVE", 1f), k("1", "1", 1f), k("2", "2", 1f), k("3", "3", 1f), k("4", "4", 1f),
                    k("5", "5", 1f), k("6", "6", 1f), k("7", "7", 1f), k("8", "8", 1f), k("9", "9", 1f),
                    k("0", "0", 1f), k("-", "MINUS", 1f), k("=", "EQUALS", 1f), k("Backspace", "DEL", 1.8f)},
            {k("Tab", "TAB", 1.5f), k("Q", "Q", 1f), k("W", "W", 1f), k("E", "E", 1f), k("R", "R", 1f),
                    k("T", "T", 1f), k("Y", "Y", 1f), k("U", "U", 1f), k("I", "I", 1f), k("O", "O", 1f),
                    k("P", "P", 1f), k("[", "LEFT_BRACKET", 1f), k("]", "RIGHT_BRACKET", 1f), k("\\", "BACKSLASH", 1.3f)},
            {k("Caps", "CAPS_LOCK", 1.9f), k("A", "A", 1f), k("S", "S", 1f), k("D", "D", 1f), k("F", "F", 1f),
                    k("G", "G", 1f), k("H", "H", 1f), k("J", "J", 1f), k("K", "K", 1f), k("L", "L", 1f),
                    k(";", "SEMICOLON", 1f), k("'", "APOSTROPHE", 1f), k("Enter", "ENTER", 2.2f)},
            {k("Shift", "SHIFT_LEFT", 2.3f), k("Z", "Z", 1f), k("X", "X", 1f), k("C", "C", 1f), k("V", "V", 1f),
                    k("B", "B", 1f), k("N", "N", 1f), k("M", "M", 1f), k(",", "COMMA", 1f), k(".", "PERIOD", 1f),
                    k("/", "SLASH", 1f), k("Shift", "SHIFT_RIGHT", 2.3f)},
            {k("Ctrl", "CTRL_LEFT", 1.5f), k("Alt", "ALT_LEFT", 1.5f), k("Space", "SPACE", 5.5f),
                    k("Alt", "ALT_RIGHT", 1.5f), k("Ctrl", "CTRL_RIGHT", 1.5f)}
    };

    private static final Key[][] EXTRA_LEFT_ROWS = {
            {k("Print", "SYSRQ", 1f), k("Scroll", "SCROLL_LOCK", 1f), k("Pause", "BREAK", 1f)},
            {k("Insert", "INSERT", 1f), k("Home", "HOME", 1f), k("PgUp", "PAGE_UP", 1f)},
            {k("Delete", "FORWARD_DEL", 1f), k("End", "END", 1f), k("PgDn", "PAGE_DOWN", 1f)},
            {k("Up", "DPAD_UP", 1f)},
            {k("Left", "DPAD_LEFT", 1f), k("Down", "DPAD_DOWN", 1f), k("Right", "DPAD_RIGHT", 1f)}
    };

    private static final Key[][] EXTRA_RIGHT_ROWS = {
            {k("NumLk", "NUM_LOCK", 1f), k("/", "NUMPAD_DIVIDE", 1f), k("*", "NUMPAD_MULTIPLY", 1f), k("-", "NUMPAD_SUBTRACT", 1f)},
            {k("7", "NUMPAD_7", 1f), k("8", "NUMPAD_8", 1f), k("9", "NUMPAD_9", 1f), k("+", "NUMPAD_ADD", 1f)},
            {k("4", "NUMPAD_4", 1f), k("5", "NUMPAD_5", 1f), k("6", "NUMPAD_6", 1f)},
            {k("1", "NUMPAD_1", 1f), k("2", "NUMPAD_2", 1f), k("3", "NUMPAD_3", 1f), k("Enter", "NUMPAD_ENTER", 1f)},
            {k("0", "NUMPAD_0", 2f), k(".", "NUMPAD_DOT", 1f), k(",", "NUMPAD_COMMA", 1f)}
    };

    private SendKeycodeDialog() {}

    public static void show(Activity activity) {
        List<String> names = Arrays.asList(KeycodeUtils.generateKeyName());
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_send_keycode, null);
        LinearLayout mainPage = view.findViewById(R.id.keycode_page_main);
        LinearLayout extraPage = view.findViewById(R.id.keycode_page_extra);
        Button done = view.findViewById(R.id.keycode_done);
        List<Integer> order = new ArrayList<>();

        for (Key[] row : MAIN_ROWS) addRow(activity, mainPage, row, names, order, done);

        LinearLayout leftColumn = column(activity);
        LinearLayout rightColumn = column(activity);
        for (Key[] row : EXTRA_LEFT_ROWS) addRow(activity, leftColumn, row, names, order, done);
        for (Key[] row : EXTRA_RIGHT_ROWS) addRow(activity, rightColumn, row, names, order, done);
        extraPage.addView(leftColumn);
        extraPage.addView(rightColumn);

        TabLayout tabs = view.findViewById(R.id.keycode_tabs);
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                boolean main = tab.getPosition() == 0;
                mainPage.setVisibility(main ? View.VISIBLE : View.GONE);
                extraPage.setVisibility(main ? View.GONE : View.VISIBLE);
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        done.setOnClickListener(v -> {
            for (int index : new ArrayList<>(order)) KeycodeUtils.execKeyIndex(index);
            dialog.dismiss();
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static LinearLayout column(Activity activity) {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return column;
    }

    private static void addRow(Activity activity, LinearLayout parent, Key[] keys,
                               List<String> names, List<Integer> order, Button done) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int margin = dp(activity, 3);
        for (Key key : keys) {
            TextView keycap = new TextView(activity);
            keycap.setText(key.display);
            keycap.setBackgroundResource(R.drawable.bg_keycap);
            keycap.setTextColor(ContextCompat.getColorStateList(activity, R.color.keycap_text));
            keycap.setGravity(Gravity.CENTER);
            keycap.setMinHeight(dp(activity, 42));
            keycap.setPadding(dp(activity, 2), dp(activity, 10), dp(activity, 2), dp(activity, 10));
            keycap.setTextSize(13);
            int index = names.indexOf(key.name);
            if (index < 0) {
                keycap.setEnabled(false);
            } else {
                keycap.setOnClickListener(v -> {
                    boolean selected = !keycap.isSelected();
                    keycap.setSelected(selected);
                    if (selected) order.add(index);
                    else order.remove(Integer.valueOf(index));
                    done.setEnabled(!order.isEmpty());
                });
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, key.weight);
            params.setMargins(margin, margin, margin, margin);
            keycap.setLayoutParams(params);
            row.addView(keycap);
        }
        parent.addView(row);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}