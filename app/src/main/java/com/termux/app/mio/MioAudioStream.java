package com.termux.app.mio;

import android.app.Application;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

/**
 * The "hands free" audio of the background video.
 *
 * The video itself is always muted by the player, the audio is played by a separate audio player (or
 * by the muted video player when the user runs `so`). Since the app does not get the audio focus of
 * the terminal, the media volume of the device cannot be changed by the terminal commands.
 *
 * So that the sound can be enabled and controlled without typing a command every time, the audio of
 * the background video can be "latched" here: when the audio of the background video is turned off
 * (by the app being recreated, by the media being re-set, or by the player being released), the
 * latched state is applied again. The commands are:
 *
 * - `swset ses` / `so ses` to latch the audio on,
 * - `so ses kapat` to latch it off,
 * - `so ses +` and `so ses -` to raise or lower the audio volume of the media stream with the
 *   current volume as the starting point, since the stream volume of the app can be changed with the
 *   {@link AudioManager} only when the app has the audio focus, which the latched state requests.
 */
public final class MioAudioStream {

    public static final String LOG_TAG = "MioAudioStream";

    /** The volume step of `so ses +` and `so ses -`, as a fraction of the max volume. */
    private static final int VOLUME_STEP = 1;

    private static MioAudioStream sInstance;

    private final Application mApplication;
    private final AudioManager mAudioManager;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /** If the audio of the background video should be kept enabled while it is available. */
    private boolean mLatched = false;

    /** The last volume of the media stream that was set by `so ses +`/`-`, -1 if not set. */
    private int mVolume = -1;

    /**
     * The reason the last action could not play any sound, for example because the video has no
     * audio track and there is no audio file with the same name next to it. The value is shown in
     * the panel and by the commands, so that a silent video is not mistaken for a broken button.
     */
    @Nullable
    private String mLastProblem;

    private Toast mLastToast;

    private MioAudioStream(@NonNull Application application) {
        mApplication = application;
        mAudioManager = (AudioManager) application.getSystemService(Context.AUDIO_SERVICE);
    }

    public static synchronized MioAudioStream getInstance(@NonNull Context context) {
        if (sInstance == null) {
            sInstance = new MioAudioStream((Application) context.getApplicationContext());
        }
        return sInstance;
    }

    public boolean isLatched() {
        return mLatched;
    }

    /**
     * Turn the latched audio on or off, and apply it to the background video right away.
     *
     * @param enabled If the audio should stay enabled.
     */
    public void setLatched(final boolean enabled) {
        mLatched = enabled;
        mLastProblem = null;
        mMainHandler.post(() -> {
            MioMediaBackgroundView backgroundView = MioMediaBackgroundView.getInstance();
            if (backgroundView == null || !backgroundView.isVideo()) {
                showToast(enabled
                    ? "Ses açık tutuldu; bir video ayarlayınca sesi duyulacak."
                    : "Ses kapatıldı.", true);
                return;
            }

            boolean ok = backgroundView.setAudioEnabled(enabled);
            if (enabled) {
                mLastProblem = backgroundView.getAudioProblem();
                if (mLastProblem != null) {
                    showToast("Ses açıldı ama ses yok: " + mLastProblem, true);
                } else if (ok) {
                    showToast("Ses açıldı (açık kalacak).", true);
                } else {
                    showToast("Ses açılamadı.", true);
                }
            } else {
                showToast("Ses kapatıldı.", true);
            }
        });
    }

    /** @return Returns the reason the audio may not be audible, or {@code null} if it should work. */
    @Nullable
    public String getLastProblem() {
        MioMediaBackgroundView backgroundView = MioMediaBackgroundView.getInstance();
        String problem = backgroundView == null ? null : backgroundView.getAudioProblem();
        return problem != null ? problem : mLastProblem;
    }

    /**
     * Turn the latched audio on and raise the media volume, or turn it off.
     *
     * @param increase {@code true} to raise the volume, {@code false} to lower it.
     */
    public void changeVolume(final boolean increase) {
        mMainHandler.post(() -> {
            if (!mLatched) {
                // Changing the volume without the audio being latched would have no effect on the
                // background video, so the latch is enabled first
                mLatched = true;
                MioMediaBackgroundView backgroundView = MioMediaBackgroundView.getInstance();
                if (backgroundView != null && backgroundView.isVideo()) {
                    backgroundView.setAudioEnabled(true);
                }
            }

            if (mAudioManager == null) {
                showToast("Ses yöneticisi bulunamadı.", true);
                return;
            }

            int max = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int current = mVolume >= 0 ? mVolume : mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int next = Math.max(0, Math.min(max, current + (increase ? VOLUME_STEP : -VOLUME_STEP)));
            mVolume = next;

            try {
                mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    increase ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to adjust the media volume", e);
            }

            String problem = MioMediaBackgroundView.getInstance() == null ? null
                : MioMediaBackgroundView.getInstance().getAudioProblem();
            showToast("Ses seviyesi: " + next + "/" + max + (problem == null ? "" : " — " + problem), true);
        });
    }

    /** The volume of the media stream as a `0-100` percentage, -1 if it is not available. */
    public int getVolumePercent() {
        if (mAudioManager == null) return -1;
        try {
            int max = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max <= 0) return -1;
            int current = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            return Math.round(100f * current / max);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Re-apply the latched audio after the background video was re-created or re-set. */
    public void applyLatchedState() {
        if (!mLatched) return;
        mMainHandler.post(() -> {
            MioMediaBackgroundView backgroundView = MioMediaBackgroundView.getInstance();
            if (backgroundView != null && backgroundView.isVideo() && !backgroundView.isAudioEnabled()) {
                backgroundView.setAudioEnabled(true);
            }
        });
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
