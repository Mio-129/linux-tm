package com.termux.app.mio;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import java.io.File;
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

    /** The request code of the system file picker, see {@link TermuxActivity#onActivityResult}. */
    public static final int PICK_MEDIA_REQUEST_CODE = 1201;

    /** The font size step of the increase and decrease buttons, matched with the "+"/"-" keys. */
    private static final int FONT_SIZE_STEP = 2;

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
     * Show the main menu of the fork.
     *
     * The menu is built on the fly so that the current state of every toggle can be shown and so
     * that a failure while reading a state cannot prevent the menu from being shown at all.
     */
    public static void show(@NonNull TermuxActivity activity) {
        MioMediaBackgroundView backgroundView = activity.getMioMediaBackgroundView();
        boolean snow = false;
        boolean particles = false;
        boolean hasMedia = false;
        boolean isVideo = false;
        boolean audioEnabled = false;

        if (backgroundView != null) {
            try {
                MioParticleView.Mode mode = backgroundView.getAnimationMode();
                snow = mode == MioParticleView.Mode.SNOW;
                particles = mode == MioParticleView.Mode.PARTICLES;
                hasMedia = backgroundView.hasMedia();
                isVideo = backgroundView.isVideo();
                audioEnabled = backgroundView.isAudioEnabled();
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read the background state", e);
            }
        }

        String volumeLabel = "Ses tuşları: " + (readVolumeKeysEnabled(activity) ? "medya sesi" : "terminal kısayolu (Ctrl/Esc)");
        String audioLabel = !isVideo ? "Video sesi: (video yok)"
            : audioEnabled ? "Video sesi: AÇIK (kapat)"
            : "Video sesi: kapalı (aç)";

        String[] items = new String[]{
            "Arka plan: " + (hasMedia ? "medya ayarlı" : "siyah"),
            "Kar animasyonu: " + (snow ? "AÇIK (kapat)" : "kapalı (aç)"),
            "Parçacık animasyonu: " + (particles ? "AÇIK (kapat)" : "kapalı (aç)"),
            "Arka plan medyası ayarla (galeri / dosya / temizle)",
            audioLabel,
            "Yazı boyutu",
            "Renkler ve tema",
            "Tam ekran: " + (readFullScreen(activity) ? "AÇIK (kapat)" : "kapalı (aç)"),
            volumeLabel,
            "Oturumlar (yeni / yeniden adlandır)",
            "Komutlar ve yardım",
            "Sürüm ve teşhis",
            "Ayarlar"
        };

        new AlertDialog.Builder(activity)
            .setTitle(R.string.mio_menu_title)
            .setItems(items, (dialog, which) -> {
                dialog.dismiss();
                try {
                    switch (which) {
                        case 0:
                            showStatusDialog(activity);
                            break;
                        case 1:
                            toggleSnow(activity);
                            break;
                        case 2:
                            toggleParticles(activity);
                            break;
                        case 3:
                            showBackgroundMediaMenu(activity);
                            break;
                        case 4:
                            toggleVideoAudio(activity);
                            break;
                        case 5:
                            showFontSizeDialog(activity);
                            break;
                        case 6:
                            showColorMenu(activity);
                            break;
                        case 7:
                            toggleFullScreen(activity);
                            break;
                        case 8:
                            toggleVolumeKeys(activity);
                            break;
                        case 9:
                            showSessionMenu(activity);
                            break;
                        case 10:
                            showCommandsDialog(activity);
                            break;
                        case 11:
                            showAboutDialog(activity);
                            break;
                        case 12:
                            activity.startActivity(new Intent(activity, SettingsActivity.class));
                            break;
                        default:
                    }
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Mio menu action failed", e);
                    showToast(activity, "Mio menü hatası: " + e);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Read the fullscreen state without letting a failure prevent the menu from being shown. */
    private static boolean readFullScreen(@NonNull TermuxActivity activity) {
        try {
            return MioTextStyle.isFullScreen(activity);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read the fullscreen state", e);
            return false;
        }
    }

    /** Read the volume keys state without letting a failure prevent the menu from being shown. */
    private static boolean readVolumeKeysEnabled(@NonNull TermuxActivity activity) {
        try {
            return MioTextStyle.isVolumeKeysEnabled(activity);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read the volume keys state", e);
            return false;
        }
    }

    /** Show the current state of the background, the animation, the font and the colors. */
    private static void showStatusDialog(@NonNull TermuxActivity activity) {
        MioMediaBackgroundView backgroundView = activity.getMioMediaBackgroundView();
        StringBuilder message = new StringBuilder();

        if (backgroundView == null || !backgroundView.hasMedia()) {
            message.append("Arka plan: siyah (medya yok)");
        } else {
            File media = backgroundView.getMediaFile();
            message.append("Arka plan: ").append(backgroundView.isVideo() ? "video" : "fotoğraf")
                .append(" — ").append(media == null ? "?" : media.getName());
            if (backgroundView.isVideo())
                message.append("\nSes: ").append(backgroundView.isAudioEnabled() ? "açık" : "kapalı");
        }

        MioParticleView.Mode mode = backgroundView == null ? MioParticleView.Mode.NONE : backgroundView.getAnimationMode();
        message.append("\nAnimasyon: ").append(mode == MioParticleView.Mode.NONE ? "kapalı" :
            mode == MioParticleView.Mode.SNOW ? "kar" : "parçacık");
        message.append("\nYazı boyutu: ").append(MioTextStyle.getFontSize(activity));
        message.append("\nTam ekran: ").append(readFullScreen(activity) ? "açık" : "kapalı");
        message.append("\nSes tuşları: ").append(readVolumeKeysEnabled(activity) ? "medya sesi" : "terminal kısayolu");

        new AlertDialog.Builder(activity)
            .setTitle(R.string.mio_menu_status_title)
            .setMessage(message.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    /** Turn the audio of the background video on or off, the same as the `so` and `so off` commands. */
    private static void toggleVideoAudio(@NonNull TermuxActivity activity) {
        MioMediaBackgroundView backgroundView = activity.getMioMediaBackgroundView();
        if (backgroundView == null || !backgroundView.isVideo()) {
            showToast(activity, "Önce bir video ayarla: swset video.mp4");
            return;
        }

        boolean enabled = backgroundView.isAudioEnabled();
        if (enabled) {
            backgroundView.setAudioEnabled(false);
            showToast(activity, "Video sesi kapatıldı.");
        } else if (backgroundView.setAudioEnabled(true)) {
            showToast(activity, "Video sesi açıldı.");
        } else {
            showToast(activity, "Video sesi açılamadı.");
        }
    }

    /** Switch the volume keys between the media volume and the terminal shortcuts (Ctrl/Esc). */
    private static void toggleVolumeKeys(@NonNull TermuxActivity activity) {
        boolean enabled = !MioTextStyle.isVolumeKeysEnabled(activity);
        MioTextStyle.setVolumeKeysEnabled(activity, enabled);
        showToast(activity, enabled
            ? "Ses tuşları medya sesini değiştirir."
            : "Ses tuşları terminal kısayolu (Ctrl/Esc) olarak çalışır.");
    }

    /** Show the version of the app and of the installed commands, and the state of the background. */
    private static void showAboutDialog(@NonNull TermuxActivity activity) {
        StringBuilder message = new StringBuilder();
        message.append("Uygulama: ").append(com.termux.BuildConfig.VERSION_NAME)
            .append(" (").append(com.termux.BuildConfig.VERSION_CODE).append(")\n");

        MioMediaBackgroundView backgroundView = activity.getMioMediaBackgroundView();
        message.append("Menü: ").append(backgroundView == null ? "arka plan görünümü YOK" : "hazır").append('\n');
        if (backgroundView != null) {
            message.append("Animasyon: ").append(backgroundView.getAnimationMode()).append('\n');
            File media = backgroundView.getMediaFile();
            message.append("Medya: ").append(media == null ? "yok" : media.getName()).append('\n');
        }

        message.append("\nKurulu komutlar:\n");
        for (String command : new String[]{"swset", "so", "mio"}) {
            File file = new File(com.termux.shared.termux.TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, command);
            message.append("  ").append(command).append(": ")
                .append(file.isFile() ? "var (" + file.length() + " bayt)" : "YOK").append('\n');
        }

        // Reading the installed commands is fast, but reading their content is not, so only the
        // presence and the size are shown. The full check is done by `mio teshis`.
        message.append("\nAyrıntılı kontrol için terminalde: mio teshis");

        new AlertDialog.Builder(activity)
            .setTitle(R.string.mio_menu_about_title)
            .setMessage(message.toString())
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.mio_menu_run_command, (dialog, which) -> showRunCommandDialog(activity))
            .show();
    }

    /* ---------------------------------------------------------------------------------------- */
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
    /* Font size                                                                                 */
    /* ---------------------------------------------------------------------------------------- */

    /** Show a dialog with a slider and the buttons to change the font size. */
    private static void showFontSizeDialog(@NonNull TermuxActivity activity) {
        Context context = activity;
        int minSize = 8;
        int maxSize = 72;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, 0);

        TextView valueView = new TextView(context);
        valueView.setText(String.valueOf(MioTextStyle.getFontSize(context)));
        valueView.setTextSize(20);
        valueView.setGravity(android.view.Gravity.CENTER);

        SeekBar seekBar = new SeekBar(context);
        seekBar.setMax(maxSize - minSize);
        seekBar.setProgress(Math.max(0, Math.min(maxSize - minSize, MioTextStyle.getFontSize(context) - minSize)));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                valueView.setText(String.valueOf(minSize + progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button decreaseButton = new Button(context);
        decreaseButton.setText("-");
        decreaseButton.setOnClickListener(v -> {
            int size = MioTextStyle.changeFontSize(context, -FONT_SIZE_STEP);
            seekBar.setProgress(Math.max(0, Math.min(maxSize - minSize, size - minSize)));
            valueView.setText(String.valueOf(size));
        });
        Button increaseButton = new Button(context);
        increaseButton.setText("+");
        increaseButton.setOnClickListener(v -> {
            int size = MioTextStyle.changeFontSize(context, FONT_SIZE_STEP);
            seekBar.setProgress(Math.max(0, Math.min(maxSize - minSize, size - minSize)));
            valueView.setText(String.valueOf(size));
        });
        buttons.addView(decreaseButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(increaseButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        layout.addView(seekBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(valueView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(activity)
            .setTitle(R.string.mio_menu_font_size_title)
            .setView(layout)
            .setPositiveButton(R.string.mio_menu_apply, (dialog, which) -> {
                int size = MioTextStyle.setFontSize(context, minSize + seekBar.getProgress());
                if (size > 0) showToast(activity, "Yazı boyutu: " + size);
            })
            .setNeutralButton("Varsayılan", (dialog, which) -> {
                int size = MioTextStyle.setFontSize(context, 0);
                if (size > 0) showToast(activity, "Yazı boyutu varsayılana döndü: " + size);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Colors                                                                                    */
    /* ---------------------------------------------------------------------------------------- */

    /** Show the color and theme menu. */
    private static void showColorMenu(@NonNull TermuxActivity activity) {
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

    private static void applyTheme(@NonNull TermuxActivity activity, @NonNull String[] theme) {
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
    private static void showSetColorDialog(@NonNull TermuxActivity activity, @NonNull String key) {
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
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Fullscreen                                                                                */
    /* ---------------------------------------------------------------------------------------- */

    private static void toggleFullScreen(@NonNull TermuxActivity activity) {
        boolean enabled = !MioTextStyle.isFullScreen(activity);
        MioTextStyle.setFullScreen(activity, enabled);
        showToast(activity, enabled ? "Tam ekran açıldı." : "Tam ekran kapatıldı.");
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Sessions                                                                                  */
    /* ---------------------------------------------------------------------------------------- */

    /** Show the menu with the actions of the sessions of the terminal. */
    private static void showSessionMenu(@NonNull TermuxActivity activity) {
        String[] items = new String[]{
            "Yeni oturum",
            "Mevcut oturumu yeniden adlandır",
            "Sonraki oturuma geç",
            "Önceki oturuma geç",
            "Oturum listesi"
        };

        new AlertDialog.Builder(activity)
            .setTitle(R.string.mio_menu_sessions_title)
            .setItems(items, (dialog, which) -> {
                dialog.dismiss();
                switch (which) {
                    case 0:
                        activity.getTermuxTerminalSessionClient().addNewSession(false, null);
                        break;
                    case 1:
                        activity.getTermuxTerminalSessionClient().renameSession(activity.getCurrentSession());
                        break;
                    case 2:
                        activity.getTermuxTerminalSessionClient().switchToSession(true);
                        break;
                    case 3:
                        activity.getTermuxTerminalSessionClient().switchToSession(false);
                        break;
                    case 4:
                        activity.getDrawer().openDrawer(android.view.Gravity.START);
                        break;
                    default:
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Commands and help                                                                         */
    /* ---------------------------------------------------------------------------------------- */

    /** Show the list of the commands of the fork, tapping one runs it. */
    private static void showCommandsDialog(@NonNull TermuxActivity activity) {
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
    private static void showRunCommandDialog(@NonNull TermuxActivity activity) {
        EditText input = new EditText(activity);
        input.setHint("mio kay");
        input.setSingleLine(true);

        new AlertDialog.Builder(activity)
            .setTitle(R.string.mio_menu_run_command)
            .setView(input)
            .setPositiveButton(R.string.mio_menu_run, (dialog, which) -> {
                String command = input.getText().toString().trim();
                if (command.isEmpty()) return;
                runCommand(activity, command);
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

    /** Quote a file name so that spaces and quotes in it do not break the command. */
    @NonNull
    private static String quote(@NonNull String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void showToast(@NonNull TermuxActivity activity, @NonNull String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    /** Open the system file picker so that a photo or a video can be picked for `swset`. */
    private static void pickMediaFile(@NonNull TermuxActivity activity) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});

        try {
            activity.startActivityForResult(Intent.createChooser(intent, activity.getString(R.string.mio_menu_pick_file)), PICK_MEDIA_REQUEST_CODE);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open the file picker", e);
            showToast(activity, "Dosya seçici açılamadı, dosya adını elle gir: swset <dosya>");
        }
    }

    /**
     * Set the media file that was picked with the system file picker. The file is copied to
     * `~/.termux/mio/media/` first, since the terminal can only reliably read the files inside its
     * own home directory, and then `swset` is run with the copied path.
     */
    public static void handlePickedMediaFile(@NonNull TermuxActivity activity, @Nullable Uri uri) {
        if (uri == null) return;

        final String name = getDisplayName(activity, uri);
        final File directory = new File(TermuxConstants.TERMUX_FILES_DIR_PATH + "/home/.termux/mio/media");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            showToast(activity, "Klasör oluşturulamadı: " + directory.getAbsolutePath());
            return;
        }

        final File targetFile = new File(directory, name);
        new Thread(() -> {
            boolean copied = false;
            try (java.io.InputStream in = activity.getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(targetFile)) {
                if (in != null) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    copied = true;
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to copy picked file \"" + uri + "\"", e);
            }

            final boolean result = copied;
            activity.runOnUiThread(() -> {
                if (result) {
                    runCommand(activity, "swset " + quote(targetFile.getAbsolutePath()));
                } else {
                    showToast(activity, "Dosya kopyalanamadı: " + name);
                }
            });
        }, "mio-file-picker").start();
    }

    @Nullable
    private static String getDisplayName(@NonNull Context context, @NonNull Uri uri) {
        String name = null;
        try (android.database.Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read the display name of \"" + uri + "\"", e);
        }

        if (name == null || name.trim().isEmpty()) {
            String path = uri.getLastPathSegment();
            name = path == null ? "media" : new File(path).getName();
        }

        // Strip path separators so that the file cannot escape the target directory
        return name.replace('/', '_').replace('\\', '_');
    }
}
