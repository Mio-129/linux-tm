package com.termux.app.mio;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The manager for the `swset` and `so` commands of this fork.
 *
 * The commands are shell scripts installed to `$PREFIX/bin` from the assets of the app, see
 * {@link MioBootstrap}. The scripts send a broadcast to the app with the {@code termux-am} command
 * which is received by the {@link com.termux.app.TermuxService}, which then calls
 * {@link #handleIntent(Intent)} or {@link #handleServiceIntent(Intent)}.
 *
 * The commands are:
 * - {@code swset <file>} to set an image or a video as the background behind the terminal. If the
 *   background is already set and a file name or no argument is passed, then the image/video in the
 *   users directory is used. The terminal keeps its black background, the media is shown behind the
 *   terminal text.
 * - {@code so} to play the audio of the video set with {@code swset}. The video itself is muted
 *   when it is first set.
 * - {@code so off} to mute the audio again.
 * - {@code so stop} to stop the audio (and the video playback) and to clear the audio state.
 * - {@code swset off} to clear the background.
 * - {@code so status} to show the current state of the background media.
 */
public final class MioCommandManager {

    public static final String LOG_TAG = "MioCommandManager";

    /** The broadcast action sent by the `swset` command to set an image or a video as the background. */
    public static final String ACTION_SET_WALLPAPER = "com.termux.mio.action.SET_WALLPAPER";
    /** The broadcast action sent by the `so` command to enable or disable the audio of the video. */
    public static final String ACTION_SET_AUDIO = "com.termux.mio.action.SET_AUDIO";
    /** The broadcast action to clear the background media. */
    public static final String ACTION_CLEAR_WALLPAPER = "com.termux.mio.action.CLEAR_WALLPAPER";
    /** The broadcast action to get the current state of the background media. */
    public static final String ACTION_STATUS = "com.termux.mio.action.STATUS";
    /** The broadcast action to print the help of the commands. */
    public static final String ACTION_HELP = "com.termux.mio.action.HELP";

    /** The extra for the name of the file, as passed to the `swset` or `so` command. */
    public static final String EXTRA_NAME = "name";
    /** The extra for the type of the media, valid values are {@link #MEDIA_TYPE_IMAGE} and {@link #MEDIA_TYPE_VIDEO}. */
    public static final String EXTRA_MEDIA_TYPE = "media_type";
    /** The extra for the audio state of the video, valid values are "0" and "1". */
    public static final String EXTRA_AUDIO = "audio";
    /** The extra for the mode of the `so` command, valid values are {@link #MODE_ON}, {@link #MODE_OFF}, {@link #MODE_STOP}. */
    public static final String EXTRA_MODE = "mode";
    /** The extra for the inner intent of the {@link TERMUX_SERVICE#ACTION_SERVICE_EXECUTE} intent. */
    public static final String EXTRA_INNER_INTENT = "mio.intent";

    public static final String MEDIA_TYPE_IMAGE = "image";
    public static final String MEDIA_TYPE_VIDEO = "video";

    public static final String MODE_ON = "on";
    public static final String MODE_OFF = "off";
    public static final String MODE_STOP = "stop";

    private static MioCommandManager sInstance;

    private final Application mApplication;
    /** The single threaded executor used for scanning directories for media files. */
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private Toast mLastToast;

    /** The media file that was last set, used to restore the background after the activity is recreated. */
    @Nullable
    private File mLastMediaFile;
    /** If the audio of {@link #mLastMediaFile} was enabled. */
    private boolean mLastPlayAudio;

    private MioCommandManager(@NonNull Application application) {
        mApplication = application;
    }

    public static synchronized MioCommandManager getInstance(@NonNull Context context) {
        if (sInstance == null) {
            sInstance = new MioCommandManager((Application) context.getApplicationContext());
        }
        return sInstance;
    }

    /**
     * Handle the intents sent to the {@link com.termux.app.TermuxActivity} with
     * {@link android.app.Activity#onNewIntent(Intent)}.
     */
    public void handleIntent(@Nullable Intent intent) {
        handleServiceIntent(intent);
    }

    /**
     * Handle the intents sent to the {@link com.termux.app.TermuxService} with
     * {@link android.app.Service#onStartCommand(Intent, int, int)}.
     *
     * @return Returns {@code true} if the intent was handled by this class, otherwise {@code false}.
     */
    public boolean handleServiceIntent(@Nullable Intent intent) {
        if (intent == null || intent.getAction() == null) return false;

        String action = intent.getAction();
        Logger.logDebug(LOG_TAG, "Handling intent with action \"" + action + "\"");

        switch (action) {
            case ACTION_SET_WALLPAPER: {
                MioRequest request = MioRequest.fromRequestFile("swset");
                setWallpaper(intent.getStringExtra(EXTRA_NAME), intent.getStringExtra(EXTRA_MEDIA_TYPE),
                    "1".equals(intent.getStringExtra(EXTRA_AUDIO)), request);
                return true;
            }
            case ACTION_SET_AUDIO: {
                MioRequest request = MioRequest.fromRequestFile("so");
                setAudio(intent.getStringExtra(EXTRA_MODE), intent.getStringExtra(EXTRA_NAME), request);
                if (request != null) MioRequest.deleteRequestFile();
                return true;
            }
            case ACTION_CLEAR_WALLPAPER:
                clearWallpaper();
                return true;
            case ACTION_STATUS:
                showStatus();
                return true;
            case ACTION_HELP:
                Logger.showToast(mApplication, getHelpString(), true);
                return true;
            default:
                // The scripts may fall back to `termux-am startservice` with ACTION_SERVICE_EXECUTE
                // if broadcasts are restricted on the device, in which case the extra intent is
                // passed with the EXTRA_INNER_INTENT extra
                if (TERMUX_SERVICE.ACTION_SERVICE_EXECUTE.equals(action)) {
                    Intent extraIntent = intent.getParcelableExtra(EXTRA_INNER_INTENT);
                    if (extraIntent != null && extraIntent.getAction() != null &&
                        !TERMUX_SERVICE.ACTION_SERVICE_EXECUTE.equals(extraIntent.getAction())) {
                        return handleServiceIntent(extraIntent);
                    }
                }
                return false;
        }
    }

    /**
     * @return Returns {@code true} if the passed action belongs to one of the commands of this fork,
     * like `swset` or `so`.
     */
    public static boolean isMioAction(@Nullable String action) {
        if (action == null) return false;
        switch (action) {
            case ACTION_SET_WALLPAPER:
            case ACTION_SET_AUDIO:
            case ACTION_CLEAR_WALLPAPER:
            case ACTION_STATUS:
            case ACTION_HELP:
                return true;
            default:
                return false;
        }
    }

    private static boolean isNullOrEmpty(@Nullable String string) {
        return string == null || string.trim().isEmpty();
    }

    /**
     * Set the image or the video as the background behind the terminal.
     *
     * @param name The name of the file passed to the command, may be {@code null} in which case the
     * first image/video file found in the users directory is used.
     * @param mediaType The type of the media, {@link #MEDIA_TYPE_IMAGE}, {@link #MEDIA_TYPE_VIDEO}
     * or {@code null} for any media, in which case the type is determined by the extension of the
     * file, or the first image/video file found if the name is {@code null}.
     * @param playAudio If the audio of the video should be played, the video is always muted until
     * the `so` command is run.
     */
    public void setWallpaper(@Nullable String name, @Nullable String mediaType, final boolean playAudio,
                             @Nullable final MioRequest request) {
        if (request != null) {
            if (isNullOrEmpty(name)) name = request.getName();
            if (isNullOrEmpty(mediaType)) mediaType = request.getMediaType();
        }
        final String finalName = name;
        final String finalMediaType = mediaType;
        final boolean finalPlayAudio = playAudio || (request != null && request.getAudio());
        final File searchDirectory = request != null ? request.getSearchDirectory() : null;

        mExecutor.execute(() -> {
            final File file = resolveMediaFile(finalName, finalMediaType, searchDirectory, true);
            if (request != null) MioRequest.deleteRequestFile();
            if (file == null) {
                String message = finalName == null
                    ? "swset: arka plan için fotoğraf veya video bulunamadı."
                    : "swset: \"" + finalName + "\" bulunamadı veya desteklenen bir fotoğraf/video değil.";
                showToast(message);
                return;
            }

            // Remember the media, so that it can be applied when the activity is created if the
            // activity is not running, like when the command was run from a background session
            mLastMediaFile = file;
            mLastPlayAudio = finalPlayAudio;

            mMainHandler.post(() -> applyMediaFile(file, finalPlayAudio));
        });
    }

    /**
     * Restore the background media that was set before the activity was recreated, like after a
     * configuration change, or before the activity was created. Must be called on the main thread.
     */
    public void restoreState() {
        final File file = mLastMediaFile;
        final boolean playAudio = mLastPlayAudio;
        if (file == null) return;

        mMainHandler.post(() -> {
            MioMediaBackgroundView backgroundView = MioMediaBackgroundView.getInstance();
            if (backgroundView == null || backgroundView.hasMedia()) return;
            Logger.logInfo(LOG_TAG, "Restoring background media \"" + file.getAbsolutePath() + "\"");
            applyMediaFile(file, playAudio, false);
        });
    }

    /**
     * Apply the media file on the main thread, where the {@link MioMediaBackgroundView} is updated.
     */
    private void applyMediaFile(@NonNull File file, boolean playAudio) {
        applyMediaFile(file, playAudio, true);
    }

    /**
     * Apply the media file on the main thread, where the {@link MioMediaBackgroundView} is updated.
     *
     * @param file The media file to set.
     * @param playAudio If the audio of the video should be played.
     * @param showToastIfFailed If a toast should be shown if the media could not be set.
     */
    private void applyMediaFile(@NonNull File file, boolean playAudio, boolean showToastIfFailed) {
        mLastMediaFile = file;
        mLastPlayAudio = playAudio;

        MioMediaBackgroundView backgroundView = MioMediaBackgroundView.getInstance();
        if (backgroundView == null) {
            String message = "swset: Termux ekranı açık değil, " + file.getName() + " Termux açılınca uygulanacak.";
            Logger.logError(LOG_TAG, "MioMediaBackgroundView instance is not available, cannot set media file \"" + file.getAbsolutePath() + "\"");
            if (showToastIfFailed) showToast(message);
            return;
        }

        if (MioMediaFiles.isVideo(file)) {
            // The audio of the video is only played if the `so` command is run, unless the
            // `--ses` flag was passed to `swset`
            if (backgroundView.setVideo(file, playAudio)) {
                if (showToastIfFailed)
                    showToast("swset: video arka plan olarak ayarlandı: " + file.getName() +
                        (playAudio ? " (ses açık)" : " (sessiz, sesi açmak için: so)"));
            } else {
                if (showToastIfFailed) showToast("swset: video açılamadı: " + file.getName());
            }
        } else {
            if (backgroundView.setImage(file)) {
                if (showToastIfFailed) showToast("swset: fotoğraf arka plan olarak ayarlandı: " + file.getName());
            } else {
                if (showToastIfFailed) showToast("swset: fotoğraf açılamadı: " + file.getName());
            }
        }
    }

    /**
     * Enable, disable or stop the audio of the currently set video.
     *
     * @param mode The mode, one of {@link #MODE_ON}, {@link #MODE_OFF}, {@link #MODE_STOP}. If the
     * mode is {@code null}, then the audio is enabled.
     * @param name The name of the media file, if the user wants to set a video and enable its audio
     * at the same time, like `so video.mp4`. May be {@code null}.
     */
    public void setAudio(@Nullable String mode, @Nullable String name, @Nullable final MioRequest request) {
        if (request != null) {
            if (isNullOrEmpty(name)) name = request.getName();
        }
        final String finalName = name;
        final String finalMode = mode;
        final File searchDirectory = request != null ? request.getSearchDirectory() : null;

        mExecutor.execute(() -> {
            if (finalName != null && !finalName.isEmpty()) {
                // A file name was passed, set it as the media and enable its audio
                final File file = resolveMediaFile(finalName, MEDIA_TYPE_VIDEO, searchDirectory, false);
                if (request != null) MioRequest.deleteRequestFile();
                if (file == null) {
                    showToast("so: \"" + finalName + "\" bulunamadı.");
                    return;
                }
                mMainHandler.post(() -> applyMediaFile(file, true));
                return;
            }

            mMainHandler.post(() -> {
                MioMediaBackgroundView backgroundView = MioMediaBackgroundView.getInstance();
                if (backgroundView == null || !backgroundView.hasMedia()) {
                    showToast("so: önce bir video ayarla: swset video.mp4");
                    return;
                }

                if (MODE_OFF.equals(finalMode)) {
                    mLastPlayAudio = false;
                    backgroundView.setAudioEnabled(false);
                    showToast("so: ses kapatıldı.");
                } else if (MODE_STOP.equals(finalMode)) {
                    backgroundView.clearMedia();
                    showToast("so: ses ve arka plan durduruldu.");
                } else {
                    if (!backgroundView.isVideo()) {
                        showToast("so: arka planda video yok, önce bir video ayarla: swset video.mp4");
                        return;
                    }
                    if (backgroundView.setAudioEnabled(true)) {
                        mLastPlayAudio = true;
                        showToast("so: ses açıldı: " + (backgroundView.getMediaFile() != null ? backgroundView.getMediaFile().getName() : ""));
                    } else {
                        showToast("so: ses açılamadı.");
                    }
                }
            });
        });
    }

    /** Clear the background media so that the default black background is shown again. */
    public void clearWallpaper() {
        mLastMediaFile = null;
        mLastPlayAudio = false;

        mMainHandler.post(() -> {
            MioMediaBackgroundView backgroundView = MioMediaBackgroundView.getInstance();
            if (backgroundView != null) backgroundView.clearMedia();
            showToast("swset: arka plan temizlendi.");
        });
    }

    /** Show the current state of the background media. */
    public void showStatus() {
        mMainHandler.post(() -> {
            MioMediaBackgroundView backgroundView = MioMediaBackgroundView.getInstance();
            if (backgroundView == null || !backgroundView.hasMedia()) {
                showToast("mio: arka plan ayarlanmadı (siyah).");
                return;
            }
            File file = backgroundView.getMediaFile();
            showToast("mio: " + (backgroundView.isVideo() ? "video" : "fotoğraf") + ": " +
                (file != null ? file.getAbsolutePath() : "?") +
                (backgroundView.isVideo() ? " (ses " + (backgroundView.isAudioEnabled() ? "açık" : "kapalı") + ")" : ""), true);
        });
    }

    /**
     * Resolve the media file to set as the background.
     *
     * @param name The name of the file, if {@code null} the first media file found is returned.
     * @param mediaType The type of the media, if {@code null} then the type is determined by the
     * extension of the file.
     * @param searchDirectory The directory the command was run from, may be {@code null} in which
     * case `~` is used.
     * @param firstIfMissing If the first media file in the search directory should be used if the
     * file with the passed name is not found.
     */
    @Nullable
    private File resolveMediaFile(@Nullable String name, @Nullable String mediaType,
                                  @Nullable File searchDirectory, boolean firstIfMissing) {
        MioMediaFiles.MediaType type;
        if (MEDIA_TYPE_IMAGE.equals(mediaType)) {
            type = MioMediaFiles.MediaType.IMAGE;
        } else if (MEDIA_TYPE_VIDEO.equals(mediaType)) {
            type = MioMediaFiles.MediaType.VIDEO;
        } else {
            type = MioMediaFiles.MediaType.VISUAL;
        }

        if (name != null && !name.isEmpty()) {
            File file = MioMediaFiles.findMediaFile(name, searchDirectory);
            if (file != null) {
                // If the file type does not match the requested type, then it is still used, since
                // the `swset video` and `swset image` commands are only hints for the file search
                return file;
            }
            if (!firstIfMissing) return null;
            Logger.logWarn(LOG_TAG, "File \"" + name + "\" not found, falling back to the first media file in the directory");
        }

        return MioMediaFiles.findFirstMediaFile(MioMediaFiles.getSearchDirectory(searchDirectory), type);
    }

    private String getHelpString() {
        return "swset <dosya>  : fotoğraf/videoyu arkaya koy (yazıların arkasında)\n" +
            "swset           : klasördeki ilk fotoğraf/videoyu koy\n" +
            "swset off       : arka planı temizle\n" +
            "so              : videonun sesini aç\n" +
            "so off          : sesi kapat\n" +
            "so stop         : sesi ve arka planı durdur\n" +
            "so status       : durumu göster";
    }

    private void showToast(@NonNull String message) {
        showToast(message, false);
    }

    private void showToast(@NonNull final String message, final boolean longDuration) {
        Logger.logInfo(LOG_TAG, message);
        mMainHandler.post(() -> {
            if (mLastToast != null) mLastToast.cancel();
            mLastToast = Toast.makeText(mApplication, message, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
            mLastToast.show();
        });
    }

}
