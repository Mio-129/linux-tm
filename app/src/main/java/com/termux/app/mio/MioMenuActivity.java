package com.termux.app.mio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import com.termux.terminal.TerminalSession;

import java.io.File;

/**
 * The "Mio menü" of this fork, shown as its own activity instead of a dialog.
 *
 * A dialog of the terminal activity made the panel hard to use: on short screens, with the limited
 * dialog height and with the activity being recreated while the panel was open, the panel could be
 * clipped or dismissed right after a button was pressed. A separate activity with its own scrolling
 * screen is stable and can be navigated like the side menu of ZeroTermux.
 *
 * The activity only shows the panel and delegates the actions to {@link MioMenu}, so that every
 * action is available from both the panel and the terminal commands.
 */
public final class MioMenuActivity extends Activity {

    public static final String LOG_TAG = "MioMenuActivity";

    /** The request code of the system file picker. */
    private static final int PICK_MEDIA_REQUEST_CODE = 1201;

    /** The container the panel is (re)built into after an action changed a state. */
    private LinearLayout mRoot;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.logDebug(LOG_TAG, "onCreate");

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(0xFF101418);

        mRoot = new LinearLayout(this);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        mRoot.setPadding(padding, padding, padding, padding);

        scrollView.addView(mRoot, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(scrollView);
        buildPanel();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rebuild so that the state labels are up to date, like after a command was typed
        if (mRoot != null) buildPanel();
    }

    /** Build (or rebuild) the panel content from the current state. */
    private void buildPanel() {
        if (mRoot == null) return;
        mRoot.removeAllViews();

        Context context = this;
        MioMediaBackgroundView backgroundView = MioMediaBackgroundView.getInstance();

        boolean hasMedia = false;
        boolean isVideo = false;
        boolean audioEnabled = false;
        MioParticleView.Mode animationMode = MioParticleView.Mode.NONE;
        File mediaFile = null;

        if (backgroundView != null) {
            try {
                hasMedia = backgroundView.hasMedia();
                isVideo = backgroundView.isVideo();
                audioEnabled = backgroundView.isAudioEnabled();
                animationMode = backgroundView.getAnimationMode();
                mediaFile = backgroundView.getMediaFile();
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read the background state", e);
            }
        }

        // ------------------------------------------------------------------------ title -----
        TextView title = new TextView(context);
        title.setText(R.string.mio_menu_title);
        title.setTextSize(22);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        mRoot.addView(title);

        String summary = (hasMedia
            ? "Arka plan: " + (isVideo ? "video" : "fotoğraf") + " — " + (mediaFile == null ? "?" : mediaFile.getName())
            : "Arka plan: siyah (medya yok)")
            + "\nAnimasyon: " + animationName(animationMode)
            + " • Ses: " + (isVideo ? (audioEnabled ? "açık" : "kapalı") : "-")
            + " • Yazı: " + MioTextStyle.getFontSize(context);
        addText(summary);

        // ------------------------------------------------------------------------ audio -----
        addSection("Ses");

        int volumePercent = MioAudioStream.getInstance(context).getVolumePercent();
        final int[] pendingVolume = {volumePercent < 0 ? 50 : volumePercent};

        final TextView volumeLabel = new TextView(context);
        volumeLabel.setTextColor(0xFFFFFFFF);
        volumeLabel.setText("Medya sesi: " + pendingVolume[0] + "%");

        SeekBar volumeSeekBar = new SeekBar(context);
        volumeSeekBar.setMax(100);
        volumeSeekBar.setProgress(pendingVolume[0]);
        volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                volumeLabel.setText("Medya sesi: " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                pendingVolume[0] = seekBar.getProgress();
            }
        });
        mRoot.addView(volumeSeekBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mRoot.addView(volumeLabel, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout volumeRow = newRow();
        addButton(volumeRow, "Ses −", v -> {
            MioAudioStream.getInstance(context).setVolumePercent(Math.max(0, pendingVolume[0] - 10));
            pendingVolume[0] = Math.max(0, pendingVolume[0] - 10);
            volumeSeekBar.setProgress(pendingVolume[0]);
            buildPanel();
        });
        addButton(volumeRow, "Ses +", v -> {
            MioAudioStream.getInstance(context).setVolumePercent(Math.min(100, pendingVolume[0] + 10));
            pendingVolume[0] = Math.min(100, pendingVolume[0] + 10);
            volumeSeekBar.setProgress(pendingVolume[0]);
            buildPanel();
        });
        addButton(volumeRow, "Uygula", v -> {
            MioAudioStream.getInstance(context).setVolumePercent(volumeSeekBar.getProgress());
            toast("Medya sesi " + volumeSeekBar.getProgress() + "% yapıldı.");
        });
        mRoot.addView(volumeRow);

        LinearLayout audioRow = newRow();
        addButton(audioRow, audioEnabled ? "Sesi kapat" : "Sesi aç", v -> {
            MioAudioStream.getInstance(context).setLatched(!isAudioEnabledNow());
            buildPanel();
        });
        addButton(audioRow, "Video sesi", v -> {
            MioMediaBackgroundView view = MioMediaBackgroundView.getInstance();
            if (view == null || !view.isVideo()) {
                toast("Önce bir video ayarla (Arka plan → Galeriden seç).");
                return;
            }
            boolean enabled = view.setAudioEnabled(!view.isAudioEnabled());
            String problem = view.getAudioProblem();
            toast(!enabled ? "Ses değiştirilemedi."
                : problem != null ? "Ses açıldı ama ses yok: " + problem
                : view.isAudioEnabled() ? "Ses açıldı." : "Ses kapatıldı.");
            buildPanel();
        });
        addButton(audioRow, "Hands-free", v -> {
            MioAudioStream.getInstance(context).setLatched(true);
            buildPanel();
        });
        mRoot.addView(audioRow);

        String problem = MioAudioStream.getInstance(context).getLastProblem();
        if (problem != null) addWarning("⚠ " + problem);

        // ------------------------------------------------------------------- background -----
        addSection("Arka plan medyası");

        LinearLayout backgroundRow = newRow();
        addButton(backgroundRow, "Galeriden seç", v -> pickMediaFile());
        addButton(backgroundRow, "Dosya adı", v -> askFileName());
        mRoot.addView(backgroundRow);

        LinearLayout backgroundRow2 = newRow();
        addButton(backgroundRow2, "İlk fotoğraf/video", v -> runCommand("swset"));
        addButton(backgroundRow2, "Temizle", v -> {
            MioCommandManager.getInstance(this).clearWallpaper();
            buildPanel();
        });
        mRoot.addView(backgroundRow2);

        // -------------------------------------------------------------------- animation -----
        addSection("Animasyonlar");

        LinearLayout animationRow = newRow();
        addButton(animationRow, "Kar: " + (animationMode == MioParticleView.Mode.SNOW ? "AÇIK" : "kapalı"),
            v -> {
                TermuxActivity activity = TermuxActivity.getInstance();
                if (activity == null) {
                    toast("Termux ekranı açık değil.");
                    return;
                }
                MioMenu.toggleSnow(activity);
                buildPanel();
            });
        addButton(animationRow, "Parçacık: " + (animationMode == MioParticleView.Mode.PARTICLES ? "AÇIK" : "kapalı"),
            v -> {
                TermuxActivity activity = TermuxActivity.getInstance();
                if (activity == null) {
                    toast("Termux ekranı açık değil.");
                    return;
                }
                MioMenu.toggleParticles(activity);
                buildPanel();
            });
        addButton(animationRow, "Kapat", v -> {
            MioMediaBackgroundView view = MioMediaBackgroundView.getInstance();
            if (view != null) {
                view.setAnimationMode(MioParticleView.Mode.NONE);
                MioCommandManager.getInstance(this).onAnimationModeChanged(MioParticleView.Mode.NONE);
            }
            buildPanel();
        });
        mRoot.addView(animationRow);

        // ------------------------------------------------------------------------- font -----
        addSection("Yazı ve renkler");

        LinearLayout fontRow = newRow();
        addButton(fontRow, "Yazı −", v -> {
            MioTextStyle.changeFontSize(this, -2);
            buildPanel();
        });
        addButton(fontRow, "Yazı +", v -> {
            MioTextStyle.changeFontSize(this, 2);
            buildPanel();
        });
        addButton(fontRow, "Boyut seç", v -> showFontSizeDialog());
        mRoot.addView(fontRow);

        LinearLayout colorRow = newRow();
        addButton(colorRow, "Renkler / tema", v -> MioMenu.showColorMenuFor(this));
        addButton(colorRow, "Renkleri sıfırla", v -> {
            MioTextStyle.resetColors(this);
            toast("Renkler varsayılana döndü.");
            buildPanel();
        });
        mRoot.addView(colorRow);

        // ----------------------------------------------------------------------- window -----
        addSection("Pencere ve tuşlar");

        LinearLayout windowRow = newRow();
        addButton(windowRow, "Tam ekran: " + (MioTextStyle.isFullScreen(this) ? "AÇIK" : "kapalı"), v -> {
            MioTextStyle.setFullScreen(this, !MioTextStyle.isFullScreen(this));
            buildPanel();
        });
        addButton(windowRow, "Ses tuşları: " + (MioTextStyle.isVolumeKeysEnabled(this) ? "medya" : "Ctrl/Esc"),
            v -> {
                MioTextStyle.setVolumeKeysEnabled(this, !MioTextStyle.isVolumeKeysEnabled(this));
                buildPanel();
            });
        mRoot.addView(windowRow);

        // --------------------------------------------------------------------- sessions -----
        addSection("Oturumlar ve diğer");

        LinearLayout sessionRow = newRow();
        addButton(sessionRow, "Yeni oturum", v -> {
            TermuxActivity activity = TermuxActivity.getInstance();
            if (activity == null) {
                toast("Termux ekranı açık değil.");
                return;
            }
            activity.getTermuxTerminalSessionClient().addNewSession(false, null);
            finish();
        });
        addButton(sessionRow, "Terminal", v -> finish());
        addButton(sessionRow, "Ayarlar", v ->
            startActivity(new Intent(this, SettingsActivity.class)));
        mRoot.addView(sessionRow);

        LinearLayout otherRow = newRow();
        addButton(otherRow, "Durum", v -> showStatusDialog());
        addButton(otherRow, "Komutlar", v -> MioMenu.showCommandsFor(this));
        addButton(otherRow, "Yardım", v -> startActivity(new Intent(this, HelpActivity.class)));
        mRoot.addView(otherRow);

        // ------------------------------------------------------------------------ tools -----
        addSection("Terminal araçları");

        LinearLayout toolsRow1 = newRow();
        addButton(toolsRow1, "Kaynak değiştir", v -> runCommand("termux-change-repo"));
        addButton(toolsRow1, "Yedekle", v -> runCommand(
            "cd ~ && tar -czf ~/termux-yedek-$(date +%Y%m%d-%H%M).tar.gz -C /data/data/com.termux/files home usr"));
        addButton(toolsRow1, "IP adresi", v -> runCommand("ifconfig 2>/dev/null || ip addr"));
        mRoot.addView(toolsRow1);

        LinearLayout toolsRow2 = newRow();
        addButton(toolsRow2, "SSH başlat", v -> runCommand("pkg install -y openssh >/dev/null 2>&1; sshd"));
        addButton(toolsRow2, "X11 kur", v -> runCommand(
            "pkg install -y x11-repo >/dev/null 2>&1; pkg install -y termux-x11-nightly xfce4 >/dev/null 2>&1; echo X11 kuruldu"));
        addButton(toolsRow2, "Teşhis", v -> runCommand("mio teshis"));
        mRoot.addView(toolsRow2);

        // ------------------------------------------------------------------------ close -----
        LinearLayout closeRow = newRow();
        addButton(closeRow, "Kapat", v -> finish());
        mRoot.addView(closeRow);
    }

    /* -------------------------------------------------------------------------------------- */

    private boolean isAudioEnabledNow() {
        MioMediaBackgroundView view = MioMediaBackgroundView.getInstance();
        return view != null && view.isVideo() && view.isAudioEnabled();
    }

    private void addSection(@NonNull String text) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTextSize(15);
        header.setTextColor(0xFF7FD4FF);
        header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
        header.setPadding(0, dp(18), 0, dp(6));
        mRoot.addView(header);
    }

    private void addText(@NonNull String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(0xFFB9C6D1);
        view.setPadding(0, dp(6), 0, dp(4));
        mRoot.addView(view);
    }

    private void addWarning(@NonNull String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(0xFFFFC857);
        view.setPadding(0, dp(4), 0, dp(4));
        mRoot.addView(view);
    }

    @NonNull
    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));
        return row;
    }

    private void addButton(@NonNull LinearLayout row, @NonNull String text, @NonNull View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(listener);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        row.addView(button, params);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(@NonNull String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /* -------------------------------------------------------------------------------------- */

    /** Run a command in the terminal, the panel is closed first so that the output is visible. */
    private void runCommand(@NonNull String command) {
        TermuxActivity activity = TermuxActivity.getInstance();
        if (activity == null) {
            toast("Termux ekranı açık değil.");
            return;
        }
        finish();
        MioMenu.runCommand(activity, command);
    }

    /** Ask for a file name and set it as the background. */
    private void askFileName() {
        EditText input = new EditText(this);
        input.setHint("foto.png veya video.mp4");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
            .setTitle(R.string.mio_menu_background_file_title)
            .setMessage(R.string.mio_menu_background_file_message)
            .setView(input)
            .setPositiveButton(R.string.mio_menu_apply, (dialog, which) -> {
                String name = input.getText().toString().trim();
                if (!TextUtils.isEmpty(name)) runCommand("swset '" + name.replace("'", "'\\''") + "'");
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Font size dialog with a slider. */
    private void showFontSizeDialog() {
        int min = 8;
        int max = 72;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(12), dp(20), 0);

        TextView value = new TextView(this);
        value.setTextSize(18);
        value.setText(String.valueOf(MioTextStyle.getFontSize(this)));

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(max - min);
        seekBar.setProgress(Math.max(0, Math.min(max - min, MioTextStyle.getFontSize(this) - min)));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText(String.valueOf(min + progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });

        layout.addView(seekBar);
        layout.addView(value);

        new AlertDialog.Builder(this)
            .setTitle(R.string.mio_menu_font_size_title)
            .setView(layout)
            .setPositiveButton(R.string.mio_menu_apply, (dialog, which) -> {
                MioTextStyle.setFontSize(this, min + seekBar.getProgress());
                buildPanel();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Show the state of everything in a dialog. */
    private void showStatusDialog() {
        StringBuilder message = new StringBuilder();
        MioMediaBackgroundView view = MioMediaBackgroundView.getInstance();
        message.append("Arka plan: ").append(view == null || !view.hasMedia() ? "siyah (medya yok)"
            : (view.isVideo() ? "video" : "fotoğraf") + " — " +
            (view.getMediaFile() == null ? "?" : view.getMediaFile().getName())).append('\n');
        message.append("Animasyon: ").append(view == null ? "kapalı" : animationName(view.getAnimationMode())).append('\n');
        message.append("Yazı boyutu: ").append(MioTextStyle.getFontSize(this)).append('\n');
        message.append("Tam ekran: ").append(MioTextStyle.isFullScreen(this) ? "açık" : "kapalı").append('\n');
        message.append("Ses tuşları: ").append(MioTextStyle.isVolumeKeysEnabled(this) ? "medya sesi" : "Ctrl/Esc").append('\n');
        message.append("Medya sesi: ").append(MioAudioStream.getInstance(this).getVolumePercent()).append("%\n");
        message.append("Uygulama: ").append(com.termux.BuildConfig.VERSION_NAME)
            .append(" (").append(com.termux.BuildConfig.VERSION_CODE).append(')');

        new AlertDialog.Builder(this)
            .setTitle(R.string.mio_menu_status_title)
            .setMessage(message.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    /** Open the system picker for a photo or a video. */
    private void pickMediaFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.mio_menu_pick_file)),
                PICK_MEDIA_REQUEST_CODE);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open the file picker", e);
            toast("Dosya seçici açılamadı, dosya adını elle gir.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_MEDIA_REQUEST_CODE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            copyAndSetMedia(data.getData());
        }
    }

    /** Copy the picked file to `~/.termux/mio/media/` and set it with `swset`. */
    private void copyAndSetMedia(@NonNull Uri uri) {
        final String name = getDisplayName(uri);
        final File directory = new File("/data/data/com.termux/files/home/.termux/mio/media");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            toast("Klasör oluşturulamadı: " + directory.getAbsolutePath());
            return;
        }

        final File target = new File(directory, name);
        toast("Kopyalanıyor: " + name);
        new Thread(() -> {
            boolean copied = false;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
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
            runOnUiThread(() -> {
                if (result) {
                    runCommand("swset '" + target.getAbsolutePath() + "'");
                } else {
                    toast("Dosya kopyalanamadı: " + name);
                }
            });
        }, "mio-panel-picker").start();
    }

    @NonNull
    private String getDisplayName(@NonNull Uri uri) {
        String name = null;
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read the display name", e);
        }
        if (TextUtils.isEmpty(name)) {
            String path = uri.getLastPathSegment();
            name = path == null ? "media" : new File(path).getName();
        }
        return name.replace('/', '_').replace('\\', '_');
    }

    @NonNull
    private static String animationName(@NonNull MioParticleView.Mode mode) {
        return mode == MioParticleView.Mode.SNOW ? "kar"
            : mode == MioParticleView.Mode.PARTICLES ? "parçacık" : "kapalı";
    }

    /** Open the panel. */
    public static void start(@NonNull Context context) {
        Intent intent = new Intent(context, MioMenuActivity.class);
        if (!(context instanceof Activity)) intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
