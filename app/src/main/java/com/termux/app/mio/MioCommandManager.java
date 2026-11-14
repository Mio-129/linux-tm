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

    /** The broadcast action sent by the `mio` command to open the Mio menu. */
    public static final String ACTION_MENU = "com.termux.mio.action.MENU";
    /** The broadcast action sent by the `mio kay` and `mio parcacik` commands to set the animation. */
    public static final String ACTION_ANIMATION = "com.termux.mio.action.ANIMATION";
    /** The broadcast action sent by the `mio yazi` command to change the font size. */
    public static final String ACTION_FONT_SIZE = "com.termux.mio.action.FONT_SIZE";
    /** The broadcast action sent by the `mio renk` and `mio tema` commands to change the colors. */
    public static final String ACTION_COLORS = "com.termux.mio.action.COLORS";
    /** The broadcast action sent by the `mio tam ekran` command to toggle the fullscreen mode. */
    public static final String ACTION_FULLSCREEN = "com.termux.mio.action.FULLSCREEN";
    /** The broadcast action sent by the `mio oturum` command to create or switch sessions. */
    public static final String ACTION_SESSION = "com.termux.mio.action.SESSION";
    /** The broadcast action to toggle the side menus or to show the current state. */
    public static final String ACTION_STYLE = "com.termux.mio.action.STYLE";
    /** The broadcast action sent by the `mio ses` command to switch the volume keys behaviour. */
    public static final String ACTION_VOLUME_KEYS = "com.termux.mio.action.VOLUME_KEYS";
    /** The broadcast action sent by `swset ses` and `so ses` to latch the media audio on or off. */
    public static final String ACTION_AUDIO_STREAM = "com.termux.mio.action.AUDIO_STREAM";

    public static final String MODE_VOLUME_UP = "volume-up";
    public static final String MODE_VOLUME_DOWN = "volume-down";

    /** The extra for the animation mode, valid values are {@link #MODE_SNOW}, {@link #MODE_PARTICLES} and {@link #MODE_OFF}. */
    public static final String EXTRA_ANIMATION_MODE = "mode";
    /** The extra for the color name, like {@code foreground} or {@code red}. */
    public static final String EXTRA_KEY = "key";
    /** The extra for the color value, like {@code #FF0000}. */
    public static final String EXTRA_VALUE = "value";
    /** The extra for the theme name. */
    public static final String EXTRA_THEME_NAME = "name";
    /** The extra for the font size value. */
    public static final String EXTRA_SIZE = "size";

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

    public static final String MODE_SNOW = "snow";
    public static final String MODE_PARTICLES = "particles";
    public static final String MODE_INCREASE = "increase";
    public static final String MODE_DECREASE = "decrease";
    public static final String MODE_RESET = "reset";
    public static final String MODE_SET = "set";
    public static final String MODE_THEME = "theme";
    public static final String MODE_STATUS = "status";
    public static final String MODE_NEW = "new";
    public static final String MODE_TOGGLE_BARS = "toggle-bars";

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

    /**
     * The animation that was last set, restored when the activity is recreated. The value is
     * stored in the shared preferences so that it also survives the process being killed.
     */
    @NonNull
    private MioParticleView.Mode mLastAnimationMode = MioParticleView.Mode.NONE;

    private static final String PREFERENCES_NAME = "mio";
    private static final String PREFERENCE_KEY_ANIMATION = "animation_mode";

    private MioCommandManager(@NonNull Application application) {
        mApplication = application;
        mLastAnimationMode = readStoredAnimationMode();
    }

    /** Store the animation mode that was changed outside of this manager, like from the menu. */
    public void onAnimationModeChanged(@NonNull MioParticleView.Mode mode) {
        storeAnimationMode(mode);
    }

    /** @return Returns the animation mode stored in the shared preferences. */
    @NonNull
    private MioParticleView.Mode readStoredAnimationMode() {
        try {
            String value = mApplication.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(PREFERENCE_KEY_ANIMATION, null);
            if (MODE_SNOW.equals(value)) return MioParticleView.Mode.SNOW;
            if (MODE_PARTICLES.equals(value)) return MioParticleView.Mode.PARTICLES;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read the stored animation mode", e);
        }
        return MioParticleView.Mode.NONE;
    }

    /** Store the animation mode so that it is restored on the next app start. */
    private void storeAnimationMode(@NonNull MioParticleView.Mode mode) {
        mLastAnimationMode = mode;
        String value = mode == MioParticleView.Mode.SNOW ? MODE_SNOW
            : mode == MioParticleView.Mode.PARTICLES ? MODE_PARTICLES : MODE_OFF;
        try {
            mApplication.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putString(PREFERENCE_KEY_ANIMATION, value).apply();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to store the animation mode", e);
        }
    }

    public static synchronized MioCommandManager getInstance(@NonNull Context context) {
        if (sInstance == null) {
            sInstance = new MioCommandManager((Application) context.getApplicationContext());
        }
        return sInstance;
    }

    /** How often the request file of the `mio` command is checked while the service runs. */
    private static final long REQUEST_POLL_INTERVAL_MS = 600;

    /** The last handled request, so that the same request is not handled twice. */
    private long mLastHandledRequestSeconds = -1;
    private String mLastHandledRequestAction;

    private Runnable mRequestPollRunnable;

    /**
     * Start checking the request file written by the `mio` command, so that the commands of the
     * terminal work even when `am broadcast`/`am startservice`/`am start` are blocked by the system.
     * Called when {@link com.termux.app.TermuxService} is created; the polling runs on the main
     * thread and is cheap (one `File.isFile()` per interval).
     */
    public void startRequestPolling() {
        if (mRequestPollRunnable != null) return;

        mRequestPollRunnable = new Runnable() {
            @Override
            public void run() {
                handlePendingRequest();
                mMainHandler.postDelayed(this, REQUEST_POLL_INTERVAL_MS);
            }
        };
        mMainHandler.postDelayed(mRequestPollRunnable, REQUEST_POLL_INTERVAL_MS);
    }

    /** Stop the polling, called when the service is destroyed. */
    public void stopRequestPolling() {
        if (mRequestPollRunnable != null) {
            mMainHandler.removeCallbacks(mRequestPollRunnable);
            mRequestPollRunnable = null;
        }
    }

    /**
     * Handle the request file of the `mio` command if it was written since the last handled one.
     *
     * @return {@code true} if a request was handled.
     */
    public boolean handlePendingRequest() {
        MioRequest request = MioRequest.fromAnyRequestFile();
        if (request == null) return false;

        long created = request.getCreatedSeconds();
        String action = request.getAction();
        if (created <= 0 || action == null) return false;
        if (created < System.currentTimeMillis() / 1000 - 30) return false;
        if (created == mLastHandledRequestSeconds && action.equals(mLastHandledRequestAction)) return false;

        mLastHandledRequestSeconds = created;
        mLastHandledRequestAction = action;

        Logger.logInfo(LOG_TAG, "Handling the request \"" + action + "\" from the request file");
        MioRequest.deleteRequestFile();

        Intent intent = new Intent(action);
        String args = request.get(KEY_ARGS_FOR_INTENT);
        if (args != null) {
            // `mode up`, `mode down`, `size 20`, ... are passed as --es mode up
            String[] parts = args.trim().split("\\s+");
            for (int i = 0; i + 1 < parts.length; i += 2) {
                intent.putExtra(parts[i], parts[i + 1]);
            }
        }
        return handleServiceIntent(intent);
    }

    /** The key of the arguments of the command in the request file. */
    private static final String KEY_ARGS_FOR_INTENT = MioRequest.KEY_ARGS;

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
            case ACTION_MENU: {
                MioRequest request = MioRequest.fromRequestFile("mio");
                if (request != null) MioRequest.deleteRequestFile();
                runOnActivity(MioMenu::show);
                return true;
            }
            case ACTION_ANIMATION: {
                MioRequest request = MioRequest.fromRequestFile("mio");
                if (request != null) MioRequest.deleteRequestFile();
                setAnimation(intent.getStringExtra(EXTRA_ANIMATION_MODE));
                return true;
            }
            case ACTION_FONT_SIZE: {
                MioRequest request = MioRequest.fromRequestFile("mio");
                if (request != null) MioRequest.deleteRequestFile();
                setFontSize(intent.getStringExtra(EXTRA_MODE), intent.getStringExtra(EXTRA_SIZE));
                return true;
            }
            case ACTION_COLORS: {
                MioRequest request = MioRequest.fromRequestFile("mio");
                if (request != null) MioRequest.deleteRequestFile();
                setColors(intent.getStringExtra(EXTRA_MODE), intent.getStringExtra(EXTRA_KEY),
                    intent.getStringExtra(EXTRA_VALUE), intent.getStringExtra(EXTRA_THEME_NAME));
                return true;
            }
            case ACTION_FULLSCREEN: {
                MioRequest request = MioRequest.fromRequestFile("mio");
                if (request != null) MioRequest.deleteRequestFile();
                toggleFullScreen();
                return true;
            }
            case ACTION_SESSION: {
                MioRequest request = MioRequest.fromRequestFile("mio");
                if (request != null) MioRequest.deleteRequestFile();
                setSession(intent.getStringExtra(EXTRA_MODE));
                return true;
            }
            case ACTION_STYLE: {
                MioRequest request = MioRequest.fromRequestFile("mio");
                if (request != null) MioRequest.deleteRequestFile();
                setStyle(intent.getStringExtra(EXTRA_MODE));
                return true;
            }
            case ACTION_VOLUME_KEYS: {
                MioRequest request = MioRequest.fromRequestFile("mio");
                if (request != null) MioRequest.deleteRequestFile();
                // `mio ses +|-|durum` passes a mode, without a mode the volume key menu is toggled
                String volumeMode = intent.getStringExtra(EXTRA_MODE);
                if (volumeMode == null || volumeMode.isEmpty()) {
                    toggleVolumeKeys();
                } else if ("up".equals(volumeMode)) {
                    MioAudioStream.adjustMediaVolume(mApplication, 10);
                } else if ("down".equals(volumeMode)) {
                    MioAudioStream.adjustMediaVolume(mApplication, -10);
                } else if ("status".equals(volumeMode)) {
                    int percent = MioAudioStream.getInstance(mApplication).getVolumePercent();
                    Logger.showToast(mApplication, "Medya sesi: "
                        + (percent < 0 ? "bilinmiyor" : percent + "%"), true);
                }
                return true;
            }
            case ACTION_AUDIO_STREAM: {
                MioRequest request = MioRequest.fromRequestFile(null);
                if (request != null) MioRequest.deleteRequestFile();
                setAudioStream(intent.getStringExtra(EXTRA_MODE));
                return true;
            }
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
            case ACTION_MENU:
            case ACTION_ANIMATION:
            case ACTION_FONT_SIZE:
            case ACTION_COLORS:
            case ACTION_FULLSCREEN:
            case ACTION_SESSION:
            case ACTION_STYLE:
            case ACTION_VOLUME_KEYS:
            case ACTION_AUDIO_STREAM:
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
        final MioParticleView.Mode animationMode = readStoredAnimationMode();

        mMainHandler.post(() -> {
            MioMediaBackgroundView backgroundView = MioMediaBackgroundView.getInstance();
            if (backgroundView == null) return;

            if (animationMode != MioParticleView.Mode.NONE && backgroundView.getAnimationMode() != animationMode) {
                Logger.logInfo(LOG_TAG, "Restoring animation mode \"" + animationMode + "\"");
                backgroundView.setAnimationMode(animationMode);
            }

            if (file == null || backgroundView.hasMedia()) return;
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
                if (!playAudio) MioAudioStream.getInstance(mApplication).applyLatchedState();
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

    /* ---------------------------------------------------------------------------------------- */
    /* Menu, animations, font, colors, fullscreen, sessions                                      */
    /* ---------------------------------------------------------------------------------------- */

    /** An action that runs on the {@link com.termux.app.TermuxActivity}, like opening the menu. */
    private interface ActivityAction {
        void run(@NonNull com.termux.app.TermuxActivity activity);
    }

    /** Run an action on the activity if it exists, otherwise show a toast. */
    private void runOnActivity(@NonNull ActivityAction action) {
        mMainHandler.post(() -> {
            com.termux.app.TermuxActivity activity = com.termux.app.TermuxActivity.getInstance();
            if (activity == null) {
                showToast("mio: Termux ekranı açık değil, komutu uygulamayı açtıktan sonra çalıştır.");
                return;
            }
            action.run(activity);
        });
    }

    /** Set the animation that is drawn behind the terminal text, like `mio kay`. */
    private void setAnimation(@Nullable String mode) {
        mMainHandler.post(() -> {
            com.termux.app.TermuxActivity activity = com.termux.app.TermuxActivity.getInstance();
            MioMediaBackgroundView backgroundView = MioMediaBackgroundView.getInstance();
            if (backgroundView == null) {
                showToast("mio: Termux ekranı açık değil.");
                return;
            }

            MioParticleView.Mode current = backgroundView.getAnimationMode();
            MioParticleView.Mode next;
            if (MODE_OFF.equals(mode)) {
                next = MioParticleView.Mode.NONE;
            } else if (MODE_PARTICLES.equals(mode)) {
                next = current == MioParticleView.Mode.PARTICLES ? MioParticleView.Mode.NONE : MioParticleView.Mode.PARTICLES;
            } else {
                next = current == MioParticleView.Mode.SNOW ? MioParticleView.Mode.NONE : MioParticleView.Mode.SNOW;
            }

            backgroundView.setAnimationMode(next);
            storeAnimationMode(next);
            showToast("mio: " + (next == MioParticleView.Mode.NONE ? "animasyon kapalı" :
                next == MioParticleView.Mode.SNOW ? "kar animasyonu açık" : "parçacık animasyonu açık"));
        });
    }

    /** Change the font size, like `mio yazi +`. */
    private void setFontSize(@Nullable String mode, @Nullable String size) {
        mExecutor.execute(() -> {
            int current = MioTextStyle.getFontSize(mApplication);
            int next;
            if (MODE_INCREASE.equals(mode)) {
                next = MioTextStyle.changeFontSize(mApplication, 2);
            } else if (MODE_DECREASE.equals(mode)) {
                next = MioTextStyle.changeFontSize(mApplication, -2);
            } else if (MODE_RESET.equals(mode)) {
                next = MioTextStyle.setFontSize(mApplication, 0);
            } else if (MODE_SET.equals(mode) && size != null) {
                try {
                    next = MioTextStyle.setFontSize(mApplication, Integer.parseInt(size.trim()));
                } catch (NumberFormatException e) {
                    next = -1;
                }
            } else {
                showToast("mio: yazı boyutu " + current);
                return;
            }

            final int result = next;
            if (result <= 0) {
                showToast("mio: yazı boyutu geçersiz.");
            } else {
                showToast("mio: yazı boyutu " + result);
            }
        });
    }

    /** Change the colors of the terminal, like `mio renk foreground #00FF66` or `mio tema matrix`. */
    private void setColors(@Nullable String mode, @Nullable String key, @Nullable String value,
                           @Nullable String themeName) {
        mExecutor.execute(() -> {
            if (MODE_STATUS.equals(mode)) {
                showToast("mio: renkler\n" + MioTextStyle.getColorsString(), true);
                return;
            }

            if (MODE_RESET.equals(mode)) {
                showToast(MioTextStyle.resetColors(mApplication) ? "mio: renkler varsayılana döndü." : "mio: renkler sıfırlanamadı.");
                return;
            }

            if (MODE_THEME.equals(mode)) {
                String[] theme = getTheme(themeName);
                if (theme == null) {
                    showToast("mio: tema bulunamadı: " + themeName + " (varsayilan, matrix, gece, mor, sicak)", true);
                    return;
                }
                if (theme[1] == null) {
                    MioTextStyle.resetColors(mApplication);
                    showToast("mio: varsayılan tema uygulandı.");
                    return;
                }
                boolean ok = MioTextStyle.setColor(mApplication, MioTextStyle.KEY_FOREGROUND, theme[1]) &&
                    MioTextStyle.setColor(mApplication, MioTextStyle.KEY_BACKGROUND, theme[2]) &&
                    MioTextStyle.setColor(mApplication, MioTextStyle.KEY_CURSOR, theme[3]);
                showToast(ok ? "mio: tema uygulandı: " + theme[0] : "mio: tema uygulanamadı.");
                return;
            }

            if (isNullOrEmpty(key) || isNullOrEmpty(value)) {
                showToast("mio: kullanım: mio renk <ad> <#RRGGBB>", true);
                return;
            }

            String colorKey = key.trim().toLowerCase(java.util.Locale.ROOT);
            String colorValue = value.trim();
            if (MioTextStyle.getColorIndex(colorKey) < 0 &&
                !MioTextStyle.KEY_FOREGROUND.equals(colorKey) &&
                !MioTextStyle.KEY_BACKGROUND.equals(colorKey) &&
                !MioTextStyle.KEY_CURSOR.equals(colorKey)) {
                showToast("mio: bilinmeyen renk adı: " + colorKey, true);
                return;
            }

            if (!colorValue.startsWith("#")) colorValue = "#" + colorValue;
            boolean ok = MioTextStyle.setColor(mApplication, colorKey, colorValue);
            showToast(ok ? "mio: " + colorKey + "=" + colorValue : "mio: renk ayarlanamadı.");
        });
    }

    /** @return Returns {name, foreground, background, cursor} for a theme, foreground being null for the default theme. */
    @Nullable
    private static String[] getTheme(@Nullable String name) {
        if (name == null) return null;
        String key = name.trim().toLowerCase(java.util.Locale.ROOT);
        switch (key) {
            case "varsayilan":
            case "varsayılan":
            case "default":
                return new String[]{"varsayılan", null, null, null};
            case "matrix":
                return new String[]{"matrix", "#00FF66", "#000000", "#00FF66"};
            case "gece":
            case "gece mavisi":
            case "night":
                return new String[]{"gece mavisi", "#CFE8FF", "#081018", "#4FC3F7"};
            case "mor":
            case "purple":
                return new String[]{"karanlık mor", "#E6D7FF", "#12061F", "#B388FF"};
            case "sicak":
            case "sıcak":
            case "warm":
                return new String[]{"sıcak", "#FFE9C7", "#140B04", "#FFB74D"};
            default:
                return null;
        }
    }

    /** Toggle the fullscreen mode, like `mio tam ekran`. */
    private void toggleFullScreen() {
        mExecutor.execute(() -> {
            boolean enabled = !MioTextStyle.isFullScreen(mApplication);
            MioTextStyle.setFullScreen(mApplication, enabled);
            showToast(enabled ? "mio: tam ekran açıldı." : "mio: tam ekran kapatıldı.");
        });
    }

    /** Latch the audio of the background video on or off, like `so ses` and `so ses kapat`. */
    private void setAudioStream(@Nullable String mode) {
        MioAudioStream stream = MioAudioStream.getInstance(mApplication);
        if (MODE_VOLUME_UP.equals(mode)) {
            stream.changeVolume(true);
        } else if (MODE_VOLUME_DOWN.equals(mode)) {
            stream.changeVolume(false);
        } else if (MODE_OFF.equals(mode)) {
            stream.setLatched(false);
        } else {
            stream.setLatched(true);
        }
    }

    /** Switch the volume keys between the media volume and the terminal shortcuts, like `mio ses`. */
    private void toggleVolumeKeys() {
        mExecutor.execute(() -> {
            boolean enabled = !MioTextStyle.isVolumeKeysEnabled(mApplication);
            MioTextStyle.setVolumeKeysEnabled(mApplication, enabled);
            showToast(enabled ? "mio: ses tuşları medya sesini değiştirir."
                : "mio: ses tuşları terminal kısayolu (Ctrl/Esc).", true);
        });
    }

    /** Create a session, like `mio oturum`. */
    private void setSession(@Nullable String mode) {
        runOnActivity(activity -> {
            if (MODE_NEW.equals(mode) || mode == null) {
                activity.getTermuxTerminalSessionClient().addNewSession(false, null);
                showToast("mio: yeni oturum açıldı.");
            }
        });
    }

    /** Toggle the side menus or show the state, like `mio menuler` and `mio durum`. */
    private void setStyle(@Nullable String mode) {
        if (MODE_TOGGLE_BARS.equals(mode)) {
            runOnActivity(activity -> {
                androidx.drawerlayout.widget.DrawerLayout drawer = activity.getDrawer();
                if (drawer == null) return;
                if (drawer.isDrawerOpen(android.view.Gravity.START)) {
                    drawer.closeDrawers();
                } else {
                    drawer.openDrawer(android.view.Gravity.START);
                }
            });
            return;
        }

        showFullStatus();
    }

    /** Show the state of the background media, the animation and the font in one toast. */
    private void showFullStatus() {
        mMainHandler.post(() -> {
            MioMediaBackgroundView backgroundView = MioMediaBackgroundView.getInstance();
            MioParticleView.Mode mode = backgroundView == null ? MioParticleView.Mode.NONE : backgroundView.getAnimationMode();
            String animation = mode == MioParticleView.Mode.NONE ? "kapalı" :
                mode == MioParticleView.Mode.SNOW ? "kar" : "parçacık";

            StringBuilder message = new StringBuilder("mio: ");
            if (backgroundView == null || !backgroundView.hasMedia()) {
                message.append("arka plan siyah");
            } else {
                File file = backgroundView.getMediaFile();
                message.append(backgroundView.isVideo() ? "video" : "fotoğraf").append(": ")
                    .append(file != null ? file.getName() : "?");
                if (backgroundView.isVideo())
                    message.append(" (ses ").append(backgroundView.isAudioEnabled() ? "açık" : "kapalı").append(")");
            }
            message.append(", animasyon ").append(animation)
                .append(", yazı boyutu ").append(MioTextStyle.getFontSize(mApplication));
            showToast(message.toString(), true);
        });
    }

    private String getHelpString() {
        return "Mio menüsü      : ses açma tuşuna kısa bas (uzun basış Fn olarak kalır)\n" +
            "mio             : Mio menüsünü aç (arka plan, animasyon, yazı, renk, tam ekran)\n" +
            "mio kay         : kar animasyonunu aç/kapat\n" +
            "mio parcacik    : parçacık animasyonunu aç/kapat\n" +
            "mio yazi +|-    : yazı boyutunu büyüt/küçült\n" +
            "mio tema <ad>   : varsayilan, matrix, gece, mor, sicak\n" +
            "mio renk <ad> <#RRGGBB> : rengi değiştir\n" +
            "mio tam ekran   : tam ekranı aç/kapat\n" +
            "mio oturum      : yeni oturum aç\n" +
            "mio yardim      : bu yardım\n\n" +
            "swset <dosya>   : fotoğraf/videoyu arkaya koy (yazıların arkasında)\n" +
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
