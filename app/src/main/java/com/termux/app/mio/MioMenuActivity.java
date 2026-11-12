package com.termux.app.mio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
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

import java.io.File;
import java.util.List;

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

    /** The scroll view of the panel, its position is kept when the panel is rebuilt. */
    private ScrollView mScrollView;

    /** The name of the package that is selected in the "Paketler" section. */
    private String mSelectedPackage;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.logDebug(LOG_TAG, "onCreate");

        ScrollView scrollView = new ScrollView(this);
        mScrollView = scrollView;
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

        // Keep the position of the list, so that a button press does not jump to the top
        final int scrollY = mScrollView == null ? 0 : mScrollView.getScrollY();
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
            pendingVolume[0] = Math.max(0, pendingVolume[0] - 10);
            MioAudioStream.getInstance(context).setVolumePercent(pendingVolume[0]);
            volumeSeekBar.setProgress(pendingVolume[0]);
            volumeLabel.setText("Medya sesi: " + pendingVolume[0] + "%");
            toast("Medya sesi: " + pendingVolume[0] + "%");
        });
        addButton(volumeRow, "Ses +", v -> {
            pendingVolume[0] = Math.min(100, pendingVolume[0] + 10);
            MioAudioStream.getInstance(context).setVolumePercent(pendingVolume[0]);
            volumeSeekBar.setProgress(pendingVolume[0]);
            volumeLabel.setText("Medya sesi: " + pendingVolume[0] + "%");
            toast("Medya sesi: " + pendingVolume[0] + "%");
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

        // A short press on the volume up key opens this panel, a long press stays the Fn key
        LinearLayout menuKeyRow = newRow();
        addButton(menuKeyRow, "Ses açma tuşu menü: " + (MioTextStyle.isMioMenuOnVolumeUp(this) ? "AÇIK" : "kapalı"),
            v -> {
                boolean enabled = !MioTextStyle.isMioMenuOnVolumeUp(this);
                MioTextStyle.setMioMenuOnVolumeUp(this, enabled);
                toast(enabled
                    ? "Ses açma tuşuna kısa basınca menü açılacak."
                    : "Ses açma tuşu menüyü açmayacak.");
                buildPanel();
            });
        mRoot.addView(menuKeyRow);

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
        addButton(toolsRow2, "Kod editörü", v -> showFileChooser());
        addButton(toolsRow2, "SSH başlat", v -> runCommand("pkg install -y openssh >/dev/null 2>&1; sshd"));
        addButton(toolsRow2, "X11 kur", v -> runCommand(
            "pkg install -y x11-repo >/dev/null 2>&1; pkg install -y termux-x11-nightly xfce4 >/dev/null 2>&1; echo X11 kuruldu"));
        addButton(toolsRow2, "Teşhis", v -> runCommand("mio teshis"));
        mRoot.addView(toolsRow2);

        // ------------------------------------------------------------- ZeroTermux araçları ----
        addSection("Konteyner, QEMU, görevler");

        LinearLayout ztRow1 = newRow();
        addButton(ztRow1, "Konteyner", v -> showContainerDialog());
        addButton(ztRow1, "QEMU", v -> showQemuDialog());
        addButton(ztRow1, "X11 başlat", v -> runCommand("termux-x11 :0 &"));
        mRoot.addView(ztRow1);

        LinearLayout ztRow2 = newRow();
        addButton(ztRow2, "Zamanlanmış görev", v -> showCronDialog());
        addButton(ztRow2, "Yedeği geri yükle", v -> showRestoreDialog());
        addButton(ztRow2, "Beautify sıfırla", v -> confirmResetBeautify());
        mRoot.addView(ztRow2);

        // ------------------------------------------------------------------- projects -----
        addSection("Proje oluştur");

        LinearLayout projectRow = newRow();
        addButton(projectRow, "C", v -> createProject("c"));
        addButton(projectRow, "Java", v -> createProject("java"));
        addButton(projectRow, "Python", v -> createProject("python"));
        mRoot.addView(projectRow);

        LinearLayout projectRow2 = newRow();
        addButton(projectRow2, "PHP", v -> createProject("php"));
        addButton(projectRow2, "Node", v -> createProject("node"));
        addButton(projectRow2, "Proje aç", v -> showOpenProjectDialog());
        mRoot.addView(projectRow2);

        // ------------------------------------------------------------------- terminal -----
        addSection("Terminal ayarları");

        LinearLayout shellRow = newRow();
        addButton(shellRow, "Başlangıç dosyası", v -> showStartupFileDialog());
        addButton(shellRow, "Karşılama mesajı", v -> showWelcomeMessageDialog());
        addButton(shellRow, "Komut tanımı", v -> showAliasDialog());
        mRoot.addView(shellRow);

        LinearLayout shellRow2 = newRow();
        addButton(shellRow2, "Alias'ları uygula", v -> applyAliases());
        addButton(shellRow2, "Alt klavye tuşları", v -> MioEditorActivity.startFromContext(this,
            "/data/data/com.termux/files/home/.termux/termux.properties"));
        addButton(shellRow2, "Açılışta çalıştır", v -> showBootCommandDialog());
        addButton(shellRow2, "SHELL çalıştır", v -> showShellRunDialog());
        mRoot.addView(shellRow2);

        // ---------------------------------------------------------------------- online -----
        addSection("Çevrimiçi");

        LinearLayout onlineRow = newRow();
        addButton(onlineRow, "Wiki", v -> openUrl("https://wiki.zerotermux.dev"));
        addButton(onlineRow, "Zero forum", v -> openUrl("https://bbs.zerotermux.dev"));
        addButton(onlineRow, "Kaynak adresi", v -> openUrl("https://github.com/Mio-129/linux-tm"));
        mRoot.addView(onlineRow);

        // ------------------------------------------------------------------- zt tools -----
        addSection("ZT işlevleri");

        LinearLayout ztRow3 = newRow();
        addButton(ztRow3, "FTP servisi", v -> showFtpDialog());
        addButton(ztRow3, "ADB kablosuz", v -> showAdbDialog());
        addButton(ztRow3, "SMS/Arama", v -> showSmsDialog());
        mRoot.addView(ztRow3);

        LinearLayout ztRow4 = newRow();
        addButton(ztRow4, "Dizin aç", v -> showOpenPathDialog());
        addButton(ztRow4, "Kısayollar", v -> showShortcutDialog());
        addButton(ztRow4, "Dil / tema", v -> MioMenu.showColorMenuFor(this));
        mRoot.addView(ztRow4);

        // ----------------------------------------------------------------------- packages -----
        addSection("Paketler");

        List<MioPackages.Package> packages = MioPackages.getPackages();
        List<MioPackages.Package> updatable = MioPackages.getUpdatablePackages();

        addText(packages.isEmpty()
            ? "Paket yok. ~/.mio/pkgs/<paket> klasörüne .a.sh, .b.sh ve .start-up.sh betiklerini koy."
            : packages.size() + " paket • " + (updatable.isEmpty()
                ? "hepsi güncel" : updatable.size() + " paket güncellenebilir"));

        LinearLayout packageRow = newRow();
        addButton(packageRow, "Paket " + (updatable.isEmpty() ? "güncelle" : "güncelle (" + updatable.size() + ")"),
            v -> updateAllPackages());
        addButton(packageRow, "Örnek oluştur", v -> createTemplatePackage());
        addButton(packageRow, "Yenile", v -> buildPanel());
        mRoot.addView(packageRow);

        LinearLayout moduleRow = newRow();
        addButton(moduleRow, "Modül kur (ZIP)", v -> showModuleDialog());
        addButton(moduleRow, "Modül klasörü", v -> runCommand(
            "mkdir -p ~/.mio/modules && ls -l ~/.mio/modules"));
        addButton(moduleRow, "VNC", v -> showVncDialog());
        mRoot.addView(moduleRow);

        if (!packages.isEmpty()) {
            MioPackages.Package selected = getSelectedPackage(packages);
            addText("Seçili paket: " + (selected == null ? "yok" : selected.getSummary()));

            LinearLayout packageSelectRow = newRow();
            for (MioPackages.Package pkg : packages) {
                boolean isSelected = selected != null && selected.name.equals(pkg.name);
                addButton(packageSelectRow, (isSelected ? "• " : "") + pkg.name, v -> selectPackage(pkg.name));
                if (packageSelectRow.getChildCount() >= 3) {
                    mRoot.addView(packageSelectRow);
                    packageSelectRow = newRow();
                }
            }
            if (packageSelectRow.getChildCount() > 0) mRoot.addView(packageSelectRow);

            if (selected != null) {
                LinearLayout packageActionRow = newRow();
                addButton(packageActionRow, selected.isInstalled() ? "Yeniden kur" : "Kur",
                    v -> runPackage(selected, false));
                addButton(packageActionRow, "Güncelle", v -> runPackage(selected, true));
                addButton(packageActionRow, "Başlangıç", v -> runPackageStartUp(selected));
                mRoot.addView(packageActionRow);

                LinearLayout packageActionRow2 = newRow();
                addButton(packageActionRow2, "Günlük", v -> showPackageLog(selected));
                addButton(packageActionRow2, selected.isEnabled() ? "Kapat" : "Aç",
                    v -> togglePackage(selected));
                addButton(packageActionRow2, "Düzenle", v -> showPackageEditor(selected));
                addButton(packageActionRow2, "Klasör", v -> showPackageFolder(selected));
                mRoot.addView(packageActionRow2);

                String logTail = selected.getLogTail(3);
                if (!logTail.isEmpty()) addText("Son çalıştırma:\n" + logTail);
            }
        }

        // ------------------------------------------------------------------------ close -----
        LinearLayout closeRow = newRow();
        addButton(closeRow, "Kapat", v -> finish());
        mRoot.addView(closeRow);

        if (scrollY > 0) {
            mScrollView.post(() -> mScrollView.scrollTo(0, scrollY));
        }
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

    /* -------------------------------------------------------------------------------------- */
    /* ZeroTermux ek özellikleri                                                              */
    /* -------------------------------------------------------------------------------------- */

    /** Konteyner (proot-distro) komutları: liste, kur, gir. */
    private void showContainerDialog() {
        final String[] items = new String[]{
            "Kurulu dağıtımları listele",
            "Debian kur (proot-distro)",
            "Debian'a gir",
            "Ubuntu kur (proot-distro)",
            "Ubuntu'ya gir",
            "proot-distro kur"
        };
        new AlertDialog.Builder(this)
            .setTitle("Konteyner (proot-distro)")
            .setItems(items, (dialog, which) -> {
                switch (which) {
                    case 0: runCommand("proot-distro list"); break;
                    case 1: runCommand("pkg install -y proot-distro >/dev/null 2>&1; proot-distro install debian"); break;
                    case 2: runCommand("proot-distro login debian"); break;
                    case 3: runCommand("pkg install -y proot-distro >/dev/null 2>&1; proot-distro install ubuntu"); break;
                    case 4: runCommand("proot-distro login ubuntu"); break;
                    default: runCommand("pkg install -y proot-distro"); break;
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** QEMU (sanal makine) komutları. */
    private void showQemuDialog() {
        final String[] items = new String[]{
            "QEMU x86_64 kur",
            "QEMU sürümünü göster",
            "Kurulum klasörünü göster (~/qemu)"
        };
        new AlertDialog.Builder(this)
            .setTitle("QEMU sanal makine")
            .setMessage("QEMU ile başka bir sistemin ISO'sunu çalıştırabilirsin.")
            .setItems(items, (dialog, which) -> {
                switch (which) {
                    case 0: runCommand("pkg install -y qemu-system-x86-64-headless qemu-utils"); break;
                    case 1: runCommand("qemu-system-x86_64 --version 2>&1 | head -3"); break;
                    default: runCommand("mkdir -p ~/qemu && ls -l ~/qemu"); break;
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Zamanlanmış görevler (cronie) için komutlar. */
    private void showCronDialog() {
        final String[] items = new String[]{
            "crond kur ve başlat",
            "Görevleri düzenle (crontab -e)",
            "Görevleri listele (crontab -l)",
            "crond'u durdur"
        };
        new AlertDialog.Builder(this)
            .setTitle("Zamanlanmış görevler")
            .setItems(items, (dialog, which) -> {
                switch (which) {
                    case 0: runCommand("pkg install -y cronie >/dev/null 2>&1; crond; pgrep crond || crond"); break;
                    case 1: runCommand("crontab -e"); break;
                    case 2: runCommand("crontab -l"); break;
                    default: runCommand("pkill crond"); break;
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Yedek dosyalarını listele ve seçileni geri yükle. */
    private void showRestoreDialog() {
        final File home = new File("/data/data/com.termux/files/home");
        final File[] files = home.listFiles((dir, name) ->
            name.startsWith("termux-yedek-") && name.endsWith(".tar.gz"));
        if (files == null || files.length == 0) {
            toast("Yedek bulunamadı. Önce Yedekle'ye bas.");
            return;
        }
        final String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) names[i] = files[i].getName();

        new AlertDialog.Builder(this)
            .setTitle("Yedeği geri yükle")
            .setItems(names, (dialog, which) -> {
                final String name = names[which];
                new AlertDialog.Builder(this)
                    .setTitle("Geri yüklensin mi?")
                    .setMessage(name + "\n\nMevcut home ve usr klasörlerinin üzerine yazılır.")
                    .setPositiveButton(R.string.mio_menu_apply,
                        (d, w) -> runCommand("tar -xzf ~/" + name + " -C /data/data/com.termux/files"))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Bütün beautify ayarlarını (medya, animasyon, renkler, yazı) varsayılana döndür. */
    private void confirmResetBeautify() {
        new AlertDialog.Builder(this)
            .setTitle("Beautify sıfırla")
            .setMessage("Arka plan medyası, animasyonlar ve renkler varsayılana döner. Yazı boyutu korunur.")
            .setPositiveButton(R.string.mio_menu_apply, (dialog, which) -> {
                MioMediaBackgroundView view = MioMediaBackgroundView.getInstance();
                if (view != null) {
                    view.setAnimationMode(MioParticleView.Mode.NONE);
                    MioCommandManager.getInstance(this).onAnimationModeChanged(MioParticleView.Mode.NONE);
                }
                MioCommandManager.getInstance(this).clearWallpaper();
                MioTextStyle.resetColors(this);
                toast("Beautify ayarları sıfırlandı.");
                buildPanel();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /* -------------------------------------------------------------------------------------- */
    /* Paketler                                                                               */
    /* -------------------------------------------------------------------------------------- */

    /** The selected package, the first one if the selected one does not exist anymore. */
    @Nullable
    private MioPackages.Package getSelectedPackage(@NonNull List<MioPackages.Package> packages) {
        if (packages.isEmpty()) return null;
        if (mSelectedPackage != null) {
            for (MioPackages.Package pkg : packages) {
                if (pkg.name.equals(mSelectedPackage)) return pkg;
            }
        }
        return packages.get(0);
    }

    private void selectPackage(@NonNull String name) {
        mSelectedPackage = name;
        buildPanel();
    }

    /** Install a package (or update it), then show the result. */
    private void runPackage(@NonNull MioPackages.Package pkg, boolean update) {
        toast((update ? "Güncelleniyor: " : "Kuruluyor: ") + pkg.name);
        final MioPackageRunner.Result[] result = new MioPackageRunner.Result[1];
        // The scripts can take a while, so they are run outside of the UI thread
        new Thread(() -> {
            result[0] = MioPackageRunner.install(pkg);
            runOnUiThread(() -> {
                toast(result[0].message);
                if (!result[0].success) showPackageLog(pkg);
                buildPanel();
            });
        }, "mio-package-install").start();
    }

    private void runPackageStartUp(@NonNull MioPackages.Package pkg) {
        toast("start-up çalıştırılıyor: " + pkg.name);
        new Thread(() -> {
            final MioPackageRunner.Result result = MioPackageRunner.runStartUp(pkg);
            runOnUiThread(() -> {
                toast(result.message);
                buildPanel();
            });
        }, "mio-package-start-up").start();
    }

    /** Install or update every package that has a new version. */
    private void updateAllPackages() {
        toast("Paketler güncelleniyor...");
        new Thread(() -> {
            final MioPackageRunner.Result result = MioPackageRunner.installAll();
            runOnUiThread(() -> {
                toast(result.message);
                buildPanel();
            });
        }, "mio-package-update-all").start();
    }

    private void createTemplatePackage() {
        MioPackages.Package pkg = MioPackageRunner.createTemplatePackage();
        if (pkg == null) {
            toast("Örnek paket oluşturulamadı: " + MioPackages.PACKAGE_DIR_PATH);
            return;
        }
        mSelectedPackage = pkg.name;
        toast(pkg.name + " oluşturuldu: .a.sh, .b.sh ve .start-up.sh");
        buildPanel();
    }

    private void togglePackage(@NonNull MioPackages.Package pkg) {
        boolean enabled = !pkg.isEnabled();
        pkg.setEnabled(enabled);
        toast(pkg.name + (enabled ? " açıldı (start-up çalışacak)." : " kapatıldı (start-up çalışmayacak)."));
        buildPanel();
    }

    private void showPackageLog(@NonNull MioPackages.Package pkg) {
        String log = pkg.getLogTail(60);
        if (log.isEmpty()) log = "(günlük yok, paket henüz çalıştırılmadı)";

        new AlertDialog.Builder(this)
            .setTitle(pkg.name + " — günlük")
            .setMessage(log)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton("Terminalde aç", (dialog, which) -> runCommand(
                "less " + MioPackages.quote(pkg.getLogFile().getAbsolutePath())))
            .show();
    }

    /* -------------------------------------------------------------------------------------- */
    /* Modüller ve VNC (ZeroTermux)                                                           */
    /* -------------------------------------------------------------------------------------- */

    /**
     * Install a ZeroTermux style module: a ZIP with an `INSTALL.txt` whose lines are
     * `kaynak -> hedef -> izin`. The modules are picked from `~/.mio/modules` or from the gallery.
     */
    private void showModuleDialog() {
        final java.util.List<File> modules = MioModuleInstaller.getModules();

        if (modules.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("Modül kur")
                .setMessage("~/.mio/modules klasörüne bir ZIP koy, içinde INSTALL.txt olsun.\n\n"
                    + "Her satır: kaynak -> hedef -> izin\n"
                    + "örnek: bin/foo -> /data/data/com.termux/files/usr/bin/foo -> 700")
                .setPositiveButton("Galeriden seç", (dialog, which) -> pickModuleZip())
                .setNeutralButton("Klasörü aç", (dialog, which) -> runCommand(
                    "mkdir -p ~/.mio/modules && ls -l ~/.mio/modules"))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }

        final String[] names = new String[modules.size()];
        for (int i = 0; i < modules.size(); i++) names[i] = modules.get(i).getName();

        new AlertDialog.Builder(this)
            .setTitle("Modül kur — " + modules.size() + " modül")
            .setItems(names, (dialog, which) -> installModule(modules.get(which)))
            .setPositiveButton("Galeriden seç", (dialog, which) -> pickModuleZip())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static final int PICK_MODULE_REQUEST_CODE = 1202;

    private void pickModuleZip() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/zip");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "Modül ZIP seç"), PICK_MODULE_REQUEST_CODE);
        } catch (Exception e) {
            toast("Dosya seçici açılamadı: " + e);
        }
    }

    /** Copy the picked ZIP to `~/.mio/modules` and install it. */
    private void installPickedModule(@NonNull Uri uri) {
        final File directory = new File(MioModuleInstaller.MODULE_DIR_PATH);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            toast("Modül klasörü oluşturulamadı.");
            return;
        }

        final String name = getDisplayName(uri);
        final File target = new File(directory, name.endsWith(".zip") ? name : name + ".zip");
        toast("Kopyalanıyor: " + target.getName());

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
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to copy the module", e);
            }

            final boolean result = copied;
            runOnUiThread(() -> {
                if (result) {
                    installModule(target);
                } else {
                    toast("Modül kopyalanamadı: " + target.getName());
                }
            });
        }, "mio-module-copy").start();
    }

    /** Install the module in the background and show the log. */
    private void installModule(@NonNull final File zipFile) {
        toast("Modül kuruluyor: " + zipFile.getName());
        new Thread(() -> {
            final MioModuleInstaller.Result result = MioModuleInstaller.install(zipFile);
            runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle(result.success ? "Modül kuruldu" : "Modül kurulamadı")
                .setMessage(result.message)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> buildPanel())
                .show());
        }, "mio-module-install").start();
    }

    /** VNC: install and start a VNC server for the X11 desktop, like the VNC entry of ZeroTermux. */
    private void showVncDialog() {
        final String[] items = new String[]{
            "TigerVNC kur (x11-repo)",
            "VNC sunucusunu başlat (:1, 5901)",
            "VNC sunucusunu durdur",
            "Bağlantı adresi"
        };
        new AlertDialog.Builder(this)
            .setTitle("VNC")
            .setMessage("X11 masaüstünü telefondan veya bilgisayardan görüntülemek için.")
            .setItems(items, (dialog, which) -> {
                switch (which) {
                    case 0:
                        runCommand("pkg install -y x11-repo >/dev/null 2>&1; "
                            + "pkg install -y tigervnc xfce4 >/dev/null 2>&1; echo 'TigerVNC kuruldu'");
                        break;
                    case 1:
                        runCommand("pgrep -f Xvnc >/dev/null && echo 'VNC zaten çalışıyor' || "
                            + "(vncserver :1 -geometry 1280x720 -depth 24 >/dev/null 2>&1 &); "
                            + "sleep 2; echo 'VNC: 127.0.0.1:5901'");
                        break;
                    case 2:
                        runCommand("vncserver -kill :1 2>/dev/null; echo 'VNC durduruldu'");
                        break;
                    default:
                        runCommand("ip addr 2>/dev/null | grep 'inet ' | head -3; echo 'VNC portu: 5901'");
                        break;
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /* -------------------------------------------------------------------------------------- */
    /* Kod editörü                                                                            */
    /* -------------------------------------------------------------------------------------- */

    /** Paketin betiklerinden birini seçip editörde aç. */
    private void showPackageEditor(@NonNull MioPackages.Package pkg) {
        if (pkg.scripts.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle(pkg.name + " — betik yok")
                .setMessage("Pakette .sh dosyası yok.\n\nYeni bir .a.sh dosyası oluşturulsun mu?")
                .setPositiveButton("Oluştur", (dialog, which) -> MioEditorActivity.startFromContext(this,
                    new File(pkg.directory, pkg.name + MioPackages.SCRIPT_A_SUFFIX).getAbsolutePath()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }

        final String[] labels = new String[pkg.scripts.size()];
        for (int i = 0; i < pkg.scripts.size(); i++) {
            labels[i] = pkg.scripts.get(i).getLabel() + " — " + pkg.scripts.get(i).file.getName();
        }

        new AlertDialog.Builder(this)
            .setTitle(pkg.name + " — düzenle")
            .setItems(labels, (dialog, which) ->
                MioEditorActivity.startFromContext(this, pkg.scripts.get(which).file.getAbsolutePath()))
            .setNeutralButton("Yeni dosya", (dialog, which) -> showNewFileDialog(pkg.directory,
                pkg.name + MioPackages.SCRIPT_A_SUFFIX))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Editörün açabileceği dosyalar: paket betikleri, ev dizinindeki .sh dosyaları ve ayarlar. */
    private void showFileChooser() {
        final java.util.List<File> files = MioEditorActivity.getScriptFiles();
        files.add(new File("/data/data/com.termux/files/home/.termux/termux.properties"));
        files.add(new File("/data/data/com.termux/files/home/.termux/colors.properties"));
        files.add(new File("/data/data/com.termux/files/home/.bashrc"));

        final String[] labels = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            String directory = file.getParentFile() == null ? "" : file.getParentFile().getName();
            labels[i] = directory + "/" + file.getName() + (file.isFile() ? "" : " (yok)");
        }

        new AlertDialog.Builder(this)
            .setTitle("Kod editörü — dosya seç")
            .setItems(labels, (dialog, which) ->
                MioEditorActivity.startFromContext(this, files.get(which).getAbsolutePath()))
            .setNeutralButton("Yeni dosya", (dialog, which) -> showNewFileDialog(
                new File("/data/data/com.termux/files/home"), "yeni.sh"))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Dosya adı sor ve editörü o dosya ile aç. */
    private void showNewFileDialog(@NonNull final File directory, @NonNull String suggestion) {
        final EditText input = new EditText(this);
        input.setHint("dosya.sh");
        input.setText(suggestion);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);

        new AlertDialog.Builder(this)
            .setTitle("Yeni dosya — " + directory.getName())
            .setMessage("Klasör: " + directory.getAbsolutePath())
            .setView(input)
            .setPositiveButton("Aç", (dialog, which) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) {
                    toast("Dosya adı boş olamaz.");
                    return;
                }
                if (name.contains("/")) {
                    toast("Dosya adında / olamaz.");
                    return;
                }
                MioEditorActivity.startFromContext(this, new File(directory, name).getAbsolutePath());
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }


    /* -------------------------------------------------------------------------------------- */
    /* Proje oluştur                                                                          */
    /* -------------------------------------------------------------------------------------- */

    private static final String HOME = "/data/data/com.termux/files/home";

    /** Proje şablonları: klasör adı, örnek dosya adı ve içeriği. */
    private void createProject(@NonNull String type) {
        final EditText input = new EditText(this);
        input.setHint("proje-adi");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);

        final String[] fileNames = new String[]{"main.c", "Main.java", "main.py", "index.php", "index.js"};
        final String[] labels = new String[]{"C", "Java", "Python", "PHP", "Node"};

        new AlertDialog.Builder(this)
            .setTitle("Yeni proje — " + type)
            .setMessage("Klasör ~/projects/<ad> olarak oluşturulur.")
            .setView(input)
            .setPositiveButton("Oluştur", (dialog, which) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) {
                    toast("Proje adı boş olamaz.");
                    return;
                }
                final String projectName = name.replace("/", "_").replace(" ", "-");
                final String path = HOME + "/projects/" + projectName;
                final String typeName = type;

                new Thread(() -> {
                    StringBuilder log = new StringBuilder();
                    boolean ok = new File(path).mkdirs() || new File(path).isDirectory();
                    log.append(ok ? "Klasör: " + path + "\n" : "Klasör oluşturulamadı: " + path + "\n");

                    String fileName = "README.md";
                    String content = "# " + projectName + "\n";
                    if (typeName.equals("c")) {
                        fileName = "main.c";
                        content = "#include <stdio.h>\n\nint main(void) {\n    printf(\"Merhaba\\n\");\n    return 0;\n}\n";
                    } else if (typeName.equals("java")) {
                        fileName = "Main.java";
                        content = "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Merhaba\");\n    }\n}\n";
                    } else if (typeName.equals("python")) {
                        fileName = "main.py";
                        content = "#!/usr/bin/env python3\n\n\ndef main():\n    print(\"Merhaba\")\n\n\nif __name__ == \"__main__\":\n    main()\n";
                    } else if (typeName.equals("php")) {
                        fileName = "index.php";
                        content = "<?php\necho \"Merhaba\\n\";\n";
                    } else if (typeName.equals("node")) {
                        fileName = "index.js";
                        content = "console.log(\"Merhaba\");\n";
                    }

                    if (ok) {
                        try (java.io.FileOutputStream out =
                                 new java.io.FileOutputStream(new File(path, fileName))) {
                            out.write(content.getBytes("UTF-8"));
                            log.append("Dosya: ").append(fileName).append('\n');
                        } catch (Exception e) {
                            log.append("Dosya yazılamadı: ").append(e).append('\n');
                        }
                    }

                    final String message = log.toString();
                    final String openPath = path + "/" + fileName;
                    runOnUiThread(() -> {
                        toast(message.replace('\n', ' ').trim());
                        new AlertDialog.Builder(this)
                            .setTitle("Proje hazır")
                            .setMessage(message)
                            .setPositiveButton("Düzenle", (d, w) ->
                                MioEditorActivity.startFromContext(this, openPath))
                            .setNegativeButton("Tamam", null)
                            .show();
                    });
                }, "mio-create-project").start();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** ~/projects altındaki projeleri listeler, seçilen klasörü terminalde açar. */
    private void showOpenProjectDialog() {
        final File projects = new File(HOME, "projects");
        final File[] directories = projects.listFiles(File::isDirectory);
        if (directories == null || directories.length == 0) {
            toast("Proje yok. Önce Proje oluştur.");
            return;
        }

        final String[] names = new String[directories.length];
        for (int i = 0; i < directories.length; i++) names[i] = directories[i].getName();

        new AlertDialog.Builder(this)
            .setTitle("Proje aç")
            .setItems(names, (dialog, which) -> runCommand("cd " + MioPackages.quote(
                directories[which].getAbsolutePath()) + " && ls -la"))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /* -------------------------------------------------------------------------------------- */
    /* Terminal ayarları                                                                      */
    /* -------------------------------------------------------------------------------------- */

    /**
     * Başlangıç dosyası: hangi dosyanın açılışta çalıştırılacağını `termux.properties` içindeki
     * `shell-startup-command` ile değiştirir, dosya yoksa oluşturur.
     */
    private void showStartupFileDialog() {
        final String[] items = new String[]{
            "~/.bashrc (varsayılan)",
            "~/.profile",
            "~/.termux/shell-startup.sh (özel)",
            "Şu anki dosyayı düzenle"
        };

        new AlertDialog.Builder(this)
            .setTitle("Başlangıç dosyası")
            .setItems(items, (dialog, which) -> {
                String path;
                if (which == 3) {
                    String current = MioTextStyle.getProperty("shell-startup-command");
                    path = current == null ? HOME + "/.bashrc" : expandPath(current);
                    MioEditorActivity.startFromContext(this, path);
                    return;
                }
                path = which == 0 ? HOME + "/.bashrc"
                    : which == 1 ? HOME + "/.profile"
                    : HOME + "/.termux/shell-startup.sh";

                File file = new File(path);
                if (!file.isFile()) {
                    try {
                        File parent = file.getParentFile();
                        if (parent != null && !parent.isDirectory()) parent.mkdirs();
                        if (file.createNewFile()) file.setExecutable(true, false);
                    } catch (Exception e) {
                        toast("Dosya oluşturulamadı: " + e);
                        return;
                    }
                }
                toast("Başlangıç dosyası: " + file.getName());
                MioEditorActivity.startFromContext(this, path);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * Append the line to the file if it is not there yet, created for the `# mio ...` blocks of the
     * terminal section. If {@code marker} is {@code null}, the line is added when it is not present.
     */
    private void appendLineOnce(@NonNull String path, @Nullable String marker, @NonNull String line) {
        File file = new File(path);
        String existing = file.isFile() ? readTextFile(file) : "";
        if (marker != null && existing.contains(marker)) {
            toast("Zaten ekli: " + marker);
            return;
        }
        if (marker == null && existing.contains(line)) {
            toast("Zaten ekli.");
            return;
        }

        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file, true)) {
            out.write(((marker == null ? "" : "\n" + marker + "\n") + line + "\n").getBytes("UTF-8"));
        } catch (Exception e) {
            toast("~/.bashrc yazılamadı: " + e);
        }
    }

    @NonNull
    private String expandPath(@NonNull String path) {
        return path.startsWith("~") ? HOME + path.substring(1) : path;
    }

    /** Karşılama mesajını ~/.mio/welcome içine yazar ve .bashrc'ye ekler. */
    private void showWelcomeMessageDialog() {
        final File welcomeFile = new File(HOME, ".mio/welcome");
        final EditText input = new EditText(this);
        input.setHint("Termux'a hoş geldin!");
        input.setSingleLine(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        if (welcomeFile.isFile()) {
            String current = readTextFile(welcomeFile);
            if (!current.isEmpty()) input.setText(current);
        }

        new AlertDialog.Builder(this)
            .setTitle("Karşılama mesajı")
            .setMessage("~/.bashrc içine eklenecek, her oturumda gösterilir.")
            .setView(input)
            .setPositiveButton("Kaydet", (dialog, which) -> {
                String message = input.getText().toString().trim();
                File parent = welcomeFile.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(welcomeFile)) {
                    out.write(message.getBytes("UTF-8"));
                } catch (Exception e) {
                    toast("Yazılamadı: " + e);
                    return;
                }

                appendLineOnce(HOME + "/.bashrc", "# mio welcome",
                    "[ -f ~/.mio/welcome ] && cat ~/.mio/welcome");
                toast("Karşılama mesajı kaydedildi.");
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    @NonNull
    private static String readTextFile(@NonNull File file) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(new java.io.FileInputStream(file), "UTF-8"))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (builder.length() > 0) builder.append('\n');
                builder.append(line);
            }
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Komut tanımı (alias) ekler ve ~/.bashrc'ye yazar. */
    /**
     * Apply the alias lines of `~/.bashrc` (and of the files that are sourced from it) to the
     * commands of the fork: the lines are collected in `~/.mio/aliases`, a `mio-alias` command is
     * installed to `$PREFIX/bin`, and `~/.bashrc` gets one line that sources the file, so that the
     * aliases are available in every new session and in the code editor.
     */
    private void applyAliases() {
        final File bashrc = new File(HOME, ".bashrc");
        if (!bashrc.isFile()) {
            toast("~/.bashrc yok. Önce Başlangıç dosyası oluştur.");
            return;
        }

        final File aliasesFile = new File(HOME, ".mio/aliases");
        final File parent = aliasesFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            toast("~/.mio klasörü oluşturulamadı.");
            return;
        }

        new Thread(() -> {
            StringBuilder collected = new StringBuilder();
            int count = 0;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(bashrc), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("alias ")) {
                        collected.append(trimmed).append('\n');
                        count++;
                    }
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read ~/.bashrc", e);
                runOnUiThread(() -> toast("~/.bashrc okunamadı: " + e));
                return;
            }

            final int found = count;
            if (count == 0) {
                runOnUiThread(() -> toast("~/.bashrc içinde alias satırı yok."));
                return;
            }

            try (java.io.FileOutputStream out = new java.io.FileOutputStream(aliasesFile)) {
                out.write(("# mio alias listesi — " + new java.util.Date() + "\n" + collected).getBytes("UTF-8"));
            } catch (Exception e) {
                runOnUiThread(() -> toast("~/.mio/aliases yazılamadı: " + e));
                return;
            }

            // The source line is added once, so that a new session sees the aliases
            appendLineOnce(HOME + "/.bashrc", "# mio aliases", "[ -f ~/.mio/aliases ] && . ~/.mio/aliases");


            runOnUiThread(() -> {
                toast(found + " alias ~/.mio/aliases dosyasına yazıldı.");
                new AlertDialog.Builder(this)
                    .setTitle("Alias'lar")
                    .setMessage(collected.toString().trim()
                        + "\n\nTerminalde hemen kullanmak için:\nsource ~/.bashrc")
                    .setPositiveButton("Yeni oturum", (dialog, which) -> {
                        TermuxActivity activity = TermuxActivity.getInstance();
                        if (activity == null) {
                            toast("Termux ekranı açık değil.");
                            return;
                        }
                        activity.getTermuxTerminalSessionClient().addNewSession(false, null);
                        finish();
                    })
                    .setNeutralButton("Terminal", (dialog, which) -> finish())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            });
        }, "mio-apply-aliases").start();
    }

    private void showAliasDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(12), dp(20), 0);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("kısayol adı, örnek: ll");
        nameInput.setSingleLine(true);
        layout.addView(nameInput);

        final EditText commandInput = new EditText(this);
        commandInput.setHint("komut, örnek: ls -alF");
        commandInput.setSingleLine(true);
        layout.addView(commandInput);

        new AlertDialog.Builder(this)
            .setTitle("Komut tanımı")
            .setView(layout)
            .setPositiveButton("Ekle", (dialog, which) -> {
                String name = nameInput.getText().toString().trim();
                String command = commandInput.getText().toString().trim();
                if (name.isEmpty() || command.isEmpty()) {
                    toast("Ad ve komut gerekli.");
                    return;
                }
                if (!name.matches("[A-Za-z0-9_.-]+")) {
                    toast("Ad sadece harf, rakam, _ . - içerebilir.");
                    return;
                }
                appendLineOnce(HOME + "/.bashrc", null,
                    "alias " + name + "=\"" + command.replace("\"", "\\\"") + "\"");
                toast("Komut tanımı eklendi: " + name + " (yeni oturumda geçerli)");
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Açılışta çalışacak komutları ~/.mio/boot-cmd içine yazar, .bashrc'den çalıştırılır. */
    private void showBootCommandDialog() {
        final File bootFile = new File(HOME, ".mio/boot-cmd");
        final EditText input = new EditText(this);
        input.setHint("sshd\ntermux-x11 :0 &");
        input.setSingleLine(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        if (bootFile.isFile()) {
            String current = readTextFile(bootFile);
            if (!current.isEmpty()) input.setText(current);
        }

        new AlertDialog.Builder(this)
            .setTitle("Açılışta çalıştır")
            .setMessage("Bu komutlar Termux her açıldığında çalışır.")
            .setView(input)
            .setPositiveButton("Kaydet", (dialog, which) -> {
                File parent = bootFile.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(bootFile)) {
                    out.write(input.getText().toString().getBytes("UTF-8"));
                } catch (Exception e) {
                    toast("Yazılamadı: " + e);
                    return;
                }
                appendLineOnce(HOME + "/.bashrc", "# mio boot",
                    "[ -f ~/.mio/boot-cmd ] && sh ~/.mio/boot-cmd");
                toast("Açılış komutları kaydedildi.");
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Tek seferlik bir shell komutu çalıştırır, çıktıyı gösterir. */
    private void showShellRunDialog() {
        final EditText input = new EditText(this);
        input.setHint("echo merhaba");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);

        new AlertDialog.Builder(this)
            .setTitle("SHELL çalıştır")
            .setView(input)
            .setPositiveButton("Çalıştır", (dialog, which) -> {
                String command = input.getText().toString().trim();
                if (command.isEmpty()) {
                    toast("Komut boş.");
                    return;
                }
                runCommand(command);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /* -------------------------------------------------------------------------------------- */
    /* Çevrimiçi ve ZT işlevleri                                                              */
    /* -------------------------------------------------------------------------------------- */

    private void openUrl(@NonNull String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("Bağlantı açılamadı: " + url);
        }
    }

    private void showFtpDialog() {
        final String[] items = new String[]{
            "FTP sunucusu kur (pyftpdlib)",
            "FTP'yi başlat (2121, ~)",
            "FTP'yi durdur"
        };
        new AlertDialog.Builder(this)
            .setTitle("FTP servisi")
            .setItems(items, (dialog, which) -> {
                switch (which) {
                    case 0: runCommand("pkg install -y python && pip install pyftpdlib"); break;
                    case 1: runCommand("pgrep -f pyftpdlib >/dev/null || "
                        + "(python -m pyftpdlib -p 2121 -d ~ >/dev/null 2>&1 &); sleep 1; echo FTP hazır: ftp://127.0.0.1:2121"); break;
                    default: runCommand("pkill -f pyftpdlib; echo 'FTP durduruldu'"); break;
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showAdbDialog() {
        final String[] items = new String[]{
            "android-tools kur",
            "Kablosuz ADB'yi aç (5555)",
            "Kablosuz ADB'yi kapat",
            "Bağlı cihazları listele"
        };
        new AlertDialog.Builder(this)
            .setTitle("ADB")
            .setItems(items, (dialog, which) -> {
                switch (which) {
                    case 0: runCommand("pkg install -y android-tools"); break;
                    case 1: runCommand("setprop service.adb.tcp.port 5555; stop adbd; start adbd; "
                        + "echo 'ADB kablosuz açıldı (root gerekir)'"); break;
                    case 2: runCommand("setprop service.adb.tcp.port -1; stop adbd; start adbd; echo 'ADB kapandı'"); break;
                    default: runCommand("adb devices -l"); break;
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showSmsDialog() {
        final String[] items = new String[]{
            "SMS gönder (termux-api)",
            "Arama geçmişi (termux-api)",
            "termux-api kur"
        };
        new AlertDialog.Builder(this)
            .setTitle("SMS / Arama")
            .setMessage("Termux:API uygulaması kurulu olmalı.")
            .setItems(items, (dialog, which) -> {
                switch (which) {
                    case 0: showSmsComposeDialog(); break;
                    case 1: runCommand("termux-call-log 2>/dev/null | head -20"); break;
                    default: runCommand("pkg install -y termux-api"); break;
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showSmsComposeDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(12), dp(20), 0);

        final EditText numberInput = new EditText(this);
        numberInput.setHint("telefon numarası");
        numberInput.setSingleLine(true);
        numberInput.setInputType(InputType.TYPE_CLASS_PHONE);
        layout.addView(numberInput);

        final EditText textInput = new EditText(this);
        textInput.setHint("mesaj");
        layout.addView(textInput);

        new AlertDialog.Builder(this)
            .setTitle("SMS gönder")
            .setView(layout)
            .setPositiveButton("Gönder", (dialog, which) -> {
                String number = numberInput.getText().toString().trim();
                String text = textInput.getText().toString().trim();
                if (number.isEmpty() || text.isEmpty()) {
                    toast("Numara ve mesaj gerekli.");
                    return;
                }
                runCommand("termux-sms-send -n " + MioPackages.quote(number) + " "
                    + MioPackages.quote(text));
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showOpenPathDialog() {
        final String[] items = new String[]{
            "Ana dizin (~)",
            "Projeler (~/projects)",
            "Paketler (~/.mio/pkgs)",
            "Termux ayarları (~/.termux)"
        };
        new AlertDialog.Builder(this)
            .setTitle("Dizin aç")
            .setItems(items, (dialog, which) -> {
                String path = which == 0 ? HOME
                    : which == 1 ? HOME + "/projects"
                    : which == 2 ? MioPackages.PACKAGE_DIR_PATH
                    : HOME + "/.termux";
                runCommand("cd " + MioPackages.quote(path) + " && ls -la");
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showShortcutDialog() {
        final String[] items = new String[]{
            "~/.mio/bin içine kısayol oluştur",
            "Kısayolları listele",
            "Örnek: hızlı yedek"
        };
        new AlertDialog.Builder(this)
            .setTitle("Kısayollar")
            .setItems(items, (dialog, which) -> {
                switch (which) {
                    case 0: showNewShortcutDialog(); break;
                    case 1: runCommand("ls -l ~/.mio/bin 2>/dev/null || echo 'Henüz kısayol yok'"); break;
                    default: runCommand("mkdir -p ~/.mio/bin && "
                        + "printf '#!/data/data/com.termux/files/usr/bin/sh\ntar -czf ~/yedek-$(date +%%Y%%m%%d).tar.gz ~/projects\n' "
                        + "> ~/.mio/bin/hizli-yedek && chmod +x ~/.mio/bin/hizli-yedek; echo 'hizli-yedek oluşturuldu'"); break;
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showNewShortcutDialog() {
        final EditText nameInput = new EditText(this);
        nameInput.setHint("kisayol-adi");
        nameInput.setSingleLine(true);

        new AlertDialog.Builder(this)
            .setTitle("Yeni kısayol")
            .setMessage("~/.mio/bin/<ad> oluşturulur ve editörde açılır.")
            .setView(nameInput)
            .setPositiveButton("Oluştur", (dialog, which) -> {
                String name = nameInput.getText().toString().trim();
                if (name.isEmpty()) {
                    toast("Ad boş olamaz.");
                    return;
                }
                if (!name.matches("[A-Za-z0-9_.-]+")) {
                    toast("Ad sadece harf, rakam, _ . - içerebilir.");
                    return;
                }
                MioEditorActivity.startFromContext(this, HOME + "/.mio/bin/" + name);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }


    private void showPackageFolder(@NonNull MioPackages.Package pkg) {
        String path = pkg.directory.getAbsolutePath();
        new AlertDialog.Builder(this)
            .setTitle(pkg.name + " — klasör")
            .setMessage(path + "\n\nDosyalar: " + pkg.scripts.size() + " betik")
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton("Terminalde aç", (dialog, which) -> runCommand(
                "cd " + MioPackages.quote(path) + " && ls -l"))
            .show();
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
        } else if (requestCode == PICK_MODULE_REQUEST_CODE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            installPickedModule(data.getData());
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
