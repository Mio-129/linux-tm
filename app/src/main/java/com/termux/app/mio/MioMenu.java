package com.termux.app.mio;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.activities.HelpActivity;
import com.termux.shared.logger.Logger;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.List;

/**
 * The "Mio menü" (Mio menu) of this fork.
 *
 * The menu collects the commands of the fork in one place, so that the background media, the
 * animations, the font and colors, the fullscreen mode, the sessions and a few helpers can be used
 * without typing the commands, in the spirit of the side menu and the "beautify" (美化) settings of
 * ZeroTermux.
 *
 * The menu is shown with a long press on the terminal (context menu -> "Mio menü") or by running
 * the `mio` command without an argument.
 */
public final class MioMenu {

    public static final String LOG_TAG = "MioMenu";

    /** The color keys that can be set from the menu, with their Turkish labels. */
    private static final String[][] COLOR_ENTRIES = new String[][]{
        {"foreground", "Yazı rengi"},
        {"background", "Arka plan rengi"},
        {"cursor", "İmleç rengi"},
        {"red", "Kırmızı"},
        {"green", "Yeşil"},
        {"yellow", "Sarı"},
        {"blue", "Mavi"},
        {"magenta", "Mor"},
        {"cyan", "Turkuaz"},
        {"white", "Beyaz"},
        {"bright_black", "Parlak siyah"},
        {"bright_white", "Parlak beyaz"}
    };

    /** A few ready made color schemes (name, foreground, background, cursor) for the color menu. */
    private static final String[][] THEMES = new String[][]{
        {"Varsayılan", null, null, null},
        {"Matrix", "#00FF66", "#000000", "#00FF66"},
        {"Gece mavisi", "#CFE8FF", "#081018", "#4FC3F7"},
        {"Karanlık mor", "#E6D7FF", "#12061F", "#B388FF"},
        {"Sıcak", "#FFE9C7", "#140B04", "#FFB74D"}
    };

    private MioMenu() {
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Main menu                                                                                 */
    /* ---------------------------------------------------------------------------------------- */

    /**
     * Show the Mio panel of the fork.
     *
     * The panel is its own activity ({@link MioMenuActivity}), not a dialog of the terminal, so that
     * it is not clipped by the small dialog window and is not dismissed when the terminal activity
     * is recreated. The panel is a scrollable column of sections like the side menu of ZeroTermux.
     */
    public static void show(@NonNull TermuxActivity activity) {
        try {
            MioMenuActivity.start(activity);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open the Mio panel", e);
            showToast(activity, "Panel açılamadı: " + e);
        }
    }    /** Read the volume keys state without letting a failure prevent the menu from being shown. */    /** Turn the audio of the background video on or off, the same as the `so` and `so off` commands. */    /** Switch the volume keys between the media volume and the terminal shortcuts (Ctrl/Esc). */    /* ---------------------------------------------------------------------------------------- */
    /* Background media                                                                          */
    /* ---------------------------------------------------------------------------------------- */

    /** Show the menu with the actions of the `swset` and `so` commands. */
    public static void showBackgroundMediaMenu(@NonNull TermuxActivity activity) {
        MioMediaBackgroundView backgroundView = activity.getMioMediaBackgroundView();
        boolean hasMedia = backgroundView != null && backgroundView.hasMedia();
        boolean isVideo = backgroundView != null && backgroundView.isVideo();
        boolean isAudioEnabled = backgroundView != null && backgroundView.isAudioEnabled();

        List<String> items = new ArrayList<>();
        items.add("Galeriden seç");
        items.add("Dosya adı ile ayarla (swset)");
        items.add("Klasördeki ilk fotoğraf/videoyu koy (swset)");
        if (hasMedia) {
            items.add("Arka planı temizle (swset off)");
            if (isVideo) {
                items.add(isAudioEnabled ? "Sesi kapat (so off)" : "Sesi aç (so)");
            }
        }
        try {
            boolean latched = MioAudioStream.getInstance(activity).isLatched();
            items.add(latched ? "Ses akışı: AÇIK (kapat)" : "Ses akışı: kapalı (aç)");
        } catch (Exception e) {
            items.add("Ses akışı");
        }
        items.add("Durumu göster");
        items.add("Komutları göster");

        String[] labels = items.toArray(new String[0]);

        new AlertDialog.Builder(activity)
            .setTitle(R.string.action_mio_background_media)
            .setItems(labels, (dialog, which) -> {
                dialog.dismiss();
                String selected = labels[which];
                if (selected.startsWith("Galeriden")) {
                    pickMediaFile(activity);
                } else if (selected.startsWith("Dosya adı")) {
                    showSetMediaByFileNameDialog(activity);
                } else if (selected.startsWith("Klasördeki")) {
                    runCommand(activity, "swset");
                } else if (selected.startsWith("Arka planı temizle")) {
                    runCommand(activity, "swset off");
                } else if (selected.startsWith("Sesi kapat")) {
                    runCommand(activity, "so off");
                } else if (selected.startsWith("Sesi aç")) {
                    runCommand(activity, "so");
                } else if (selected.startsWith("Ses akışı")) {
                    runCommand(activity, "so ses" + (selected.contains("AÇIK") ? " kapat" : ""));
                } else if (selected.startsWith("Durumu")) {
                    runCommand(activity, "swset status");
                } else {
                    showCommandsDialog(activity);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Ask for a file name and set it as the background with the `swset` command. */
    private static void showSetMediaByFileNameDialog(@NonNull TermuxActivity activity) {
        EditText input = new EditText(activity);
        input.setHint("foto.png veya video.mp4");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);

        new AlertDialog.Builder(activity)
            .setTitle(R.string.mio_menu_background_file_title)
            .setMessage(R.string.mio_menu_background_file_message)
            .setView(input)
            .setPositiveButton(R.string.mio_menu_apply, (dialog, which) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) {
                    showToast(activity, "Dosya adı boş olamaz.");
                    return;
                }
                runCommand(activity, "swset " + quote(name));
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Animations                                                                                */
    /* ---------------------------------------------------------------------------------------- */

    /** Turn the snow animation on or off. */
    public static void toggleSnow(@NonNull TermuxActivity activity) {
        MioMediaBackgroundView backgroundView = activity.getMioMediaBackgroundView();
        if (backgroundView == null) return;

        boolean enabled = backgroundView.getAnimationMode() == MioParticleView.Mode.SNOW;
        MioParticleView.Mode mode = enabled ? MioParticleView.Mode.NONE : MioParticleView.Mode.SNOW;
        backgroundView.setAnimationMode(mode);
        MioCommandManager.getInstance(activity).onAnimationModeChanged(mode);
        showToast(activity, enabled ? "Kar animasyonu kapatıldı." : "Kar animasyonu açıldı.");
    }

    /** Turn the particle animation on or off. */
    public static void toggleParticles(@NonNull TermuxActivity activity) {
        MioMediaBackgroundView backgroundView = activity.getMioMediaBackgroundView();
        if (backgroundView == null) return;

        boolean enabled = backgroundView.getAnimationMode() == MioParticleView.Mode.PARTICLES;
        MioParticleView.Mode mode = enabled ? MioParticleView.Mode.NONE : MioParticleView.Mode.PARTICLES;
        backgroundView.setAnimationMode(mode);
        MioCommandManager.getInstance(activity).onAnimationModeChanged(mode);
        showToast(activity, enabled ? "Parçacık animasyonu kapatıldı." : "Parçacık animasyonu açıldı.");
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Colors                                                                                    */
    /* ---------------------------------------------------------------------------------------- */

    /** Open the color and theme menu, used by the panel activity. */
    public static void showColorMenuFor(@NonNull android.app.Activity activity) {
        showColorMenuInternal(activity);
    }

    /** Open the command list, used by the panel activity. */
    public static void showCommandsFor(@NonNull android.app.Activity activity) {
        showCommandsInternal(activity);
    }    private static void showCommandsDialog(@NonNull TermuxActivity activity) {
        showCommandsInternal(activity);
    }

    private static void showColorMenuInternal(@NonNull android.app.Activity activity) {
        String[] items = new String[THEMES.length + COLOR_ENTRIES.length];
        for (int i = 0; i < THEMES.length; i++) items[i] = "Tema: " + THEMES[i][0];
        for (int i = 0; i < COLOR_ENTRIES.length; i++) {
            items[THEMES.length + i] = "  " + COLOR_ENTRIES[i][1] + " (" + COLOR_ENTRIES[i][0] + ")";
        }

        new AlertDialog.Builder(activity)
            .setTitle(R.string.mio_menu_colors_title)
            .setItems(items, (dialog, which) -> {
                dialog.dismiss();
                if (which < THEMES.length) {
                    applyTheme(activity, THEMES[which]);
                } else {
                    String key = COLOR_ENTRIES[which - THEMES.length][0];
                    showSetColorDialog(activity, key);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static void applyTheme(@NonNull android.app.Activity activity, @NonNull String[] theme) {
        if (theme[1] == null) {
            MioTextStyle.resetColors(activity);
            showToast(activity, "Renkler varsayılana döndü.");
            return;
        }

        boolean ok = MioTextStyle.setColor(activity, MioTextStyle.KEY_FOREGROUND, theme[1]) &&
            MioTextStyle.setColor(activity, MioTextStyle.KEY_BACKGROUND, theme[2]) &&
            MioTextStyle.setColor(activity, MioTextStyle.KEY_CURSOR, theme[3]);
        showToast(activity, ok ? "Tema uygulandı: " + theme[0] : "Tema uygulanamadı.");
    }

    /** Ask for a color value for the passed key, like `foreground` or `red`. */
    private static void showSetColorDialog(@NonNull android.app.Activity activity, @NonNull String key) {
        Context context = activity;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, 0);

        EditText input = new EditText(context);
        input.setHint("#RRGGBB");
        input.setSingleLine(true);
        String current = MioTextStyle.getColor(key);
        if (current != null) input.setText(current);

        TextView presetsView = new TextView(context);
        presetsView.setText("Hazır renkler için aşağıdaki butonları kullan:");
        presetsView.setPadding(0, padding / 2, 0, 0);

        LinearLayout presets = new LinearLayout(context);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        String[] colors = new String[]{"#FFFFFF", "#000000", "#00FF66", "#4FC3F7", "#FF5252", "#FFD740"};
        for (String color : colors) {
            Button button = new Button(context);
            button.setText(color);
            button.setTextSize(11);
            button.setOnClickListener(v -> input.setText(color));
            presets.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        layout.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(presetsView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(presets, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(activity)
            .setTitle("Renk: " + key)
            .setView(layout)
            .setPositiveButton(R.string.mio_menu_apply, (dialog, which) -> {
                String value = input.getText().toString().trim();
                if (!value.startsWith("#")) value = "#" + value;
                boolean ok = MioTextStyle.setColor(context, key, value);
                showToast(activity, ok ? "Renk ayarlandı: " + key + "=" + value : "Renk ayarlanamadı.");
            })
            .setNeutralButton("Sıfırla", (dialog, which) -> {
                MioTextStyle.resetColors(context);
                showToast(activity, "Renkler varsayılana döndü.");
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }    /* ---------------------------------------------------------------------------------------- */    /* ---------------------------------------------------------------------------------------- */
    /* Commands and help                                                                         */
    /* ---------------------------------------------------------------------------------------- */

    /** Show the list of the commands of the fork, tapping one runs it. */
    private static void showCommandsInternal(@NonNull android.app.Activity activity) {
        final String[] commands = new String[]{
            "swset <dosya>", "swset", "swset off", "swset status",
            "so", "so off", "so stop", "so status",
            "mio", "mio kay", "mio parcacik", "mio yazi +", "mio tema matrix",
            "mio tam ekran", "mio oturum", "mio yardim"
        };

        StringBuilder message = new StringBuilder();
        message.append(activity.getString(R.string.mio_menu_commands_message)).append("\n\n");
        for (String command : commands) message.append("  ").append(command).append('\n');

        new AlertDialog.Builder(activity)
            .setTitle(R.string.mio_menu_commands_title)
            .setMessage(message.toString().trim())
            .setPositiveButton(R.string.mio_menu_open_help, (dialog, which) ->
                activity.startActivity(new Intent(activity, HelpActivity.class)))
            .setNeutralButton(R.string.mio_menu_run_command, (dialog, which) -> showRunCommandDialog(activity))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Ask for an arbitrary command and run it in the current session. */
    private static void showRunCommandDialog(@NonNull android.app.Activity activity) {
        EditText input = new EditText(activity);
        input.setHint("mio kay");
        input.setSingleLine(true);

        new AlertDialog.Builder(activity)
            .setTitle(R.string.mio_menu_run_command)
            .setView(input)
            .setPositiveButton(R.string.mio_menu_run, (dialog, which) -> {
                String command = input.getText().toString().trim();
                if (command.isEmpty()) return;
                TermuxActivity terminalActivity = TermuxActivity.getInstance();
                if (terminalActivity == null) {
                    Toast.makeText(activity, "Termux ekranı açık değil.", Toast.LENGTH_SHORT).show();
                    return;
                }
                runCommand(terminalActivity, command);
                if (activity != terminalActivity) activity.finish();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Helpers                                                                                   */
    /* ---------------------------------------------------------------------------------------- */

    /**
     * Run the command in the terminal session that is currently shown, so that the command is
     * executed by the shell like the user typed it and the app is notified in the usual way.
     *
     * The visible session is preferred over a finished one, since the current session may already
     * have exited, like after the user typed `exit`.
     */
    public static void runCommand(@NonNull TermuxActivity activity, @NonNull final String command) {
        final TerminalSession session = getRunnableSession(activity);
        if (session == null) {
            showToast(activity, "Komutun çalıştırılacağı oturum yok.");
            return;
        }

        // Write on a background thread since the write may block if the shell is busy
        new Thread(() -> {
            byte[] bytes = (command + "\n").getBytes();
            session.write(bytes, 0, bytes.length);
        }, "mio-command-writer").start();

        // Show the session the command was sent to, so that its output is visible
        if (activity.getCurrentSession() != session) {
            activity.getTermuxTerminalSessionClient().setCurrentSession(session);
        }

        showToast(activity, command);
    }

    /**
     * @return Returns the running session with the lowest index that the user can type in, falling
     * back to the current session, or {@code null} if the service is not bound.
     */
    @Nullable
    private static TerminalSession getRunnableSession(@NonNull TermuxActivity activity) {
        TerminalSession currentSession = activity.getCurrentSession();
        if (currentSession != null && currentSession.isRunning()) return currentSession;

        com.termux.app.TermuxService service = activity.getTermuxService();
        if (service != null) {
            for (com.termux.shared.termux.shell.command.runner.terminal.TermuxSession termuxSession : service.getTermuxSessions()) {
                TerminalSession session = termuxSession.getTerminalSession();
                if (session != null && session.isRunning()) return session;
            }
        }

        return currentSession;
    }

}
