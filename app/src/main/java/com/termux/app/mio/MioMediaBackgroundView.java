package com.termux.app.mio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.media.MediaPlayer.TrackInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.logger.Logger;

import java.io.File;

/**
 * The background view that is shown behind the {@link com.termux.view.TerminalView} when the user
 * sets a media file with the `swset` command.
 *
 * The view is a {@link FrameLayout} containing:
 * - An {@link android.widget.ImageView} that shows an image set with `swset image.png`, scaled with
 *   center crop so that no black bars are shown on the sides.
 * - A {@link TextureView} that shows video set with `swset video.mp4` (or `swset video` if it is
 *   the only media file in the directory) with media audio muted by default. The audio is only
 *   played when the user runs the `so` command. If the video has no audio stream, then the audio
 *   of a sibling file with the same name but an audio extension (mp4/mkv/webm/m4a/mp3/...) is played
 *   instead, as long as one exists.
 *
 * The default background is black, which is retained while media is loading or if the file could
 * not be loaded, since the terminal is rendered on top of this view.
 */
public final class MioMediaBackgroundView extends FrameLayout implements TextureView.SurfaceTextureListener {

    public static final String LOG_TAG = "MioMediaBackgroundView";

    /** The default (black) background color, matched with `@color/black` of the app. */
    public static final int DEFAULT_BACKGROUND_COLOR = 0xFF000000;

    /** The maximum dimension an image is decoded with, if the view has not been laid out yet. */
    private static final int MAX_DECODED_IMAGE_DIMENSION = 2048;

    private final ImageView mImageView;
    private final TextureView mTextureView;
    /**
     * The animated overlay (snow or particles) that is drawn above the media and below the
     * terminal text, like the "snow" and "particles" effects of ZeroTermux.
     */
    private final MioParticleView mParticleView;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /** The currently shown media file, `null` for the black background. */
    @Nullable
    private File mMediaFile;
    /** If the currently shown media is a video. */
    private boolean mIsVideo;
    /** The media player for the video. */
    @Nullable
    private MediaPlayer mMediaPlayer;
    /** The media player for the separate audio file if the video has no audio track of its own. */
    @Nullable
    private MediaPlayer mAudioPlayer;
    /** The surface of the {@link #mTextureView} the video should be rendered on. */
    @Nullable
    private Surface mSurface;
    /** If the audio of the currently shown video is enabled by the `so` command. */
    private boolean mAudioEnabled;

    /**
     * The reason the audio of the current video may not be audible, like a video without an audio
     * track that has no audio file with the same name next to it. {@code null} while unknown or
     * while the audio should work.
     */
    @Nullable
    private String mAudioProblem;

    /** The pending play request which is run when the {@link #mSurface} becomes available. */
    @Nullable
    private Runnable mPendingPlayRunnable;

    /**
     * Static accessor, set when a view is created, so that {@link MioCommandManager} can find it
     * even if the activity was not created yet, like when the command was run while the app was in
     * the background and the media should be applied when the activity is created.
     */
    @Nullable
    private static MioMediaBackgroundView sInstance;

    /** The listener that is notified when the media state changes, to update the terminal background. */
    @Nullable
    private MediaStateListener mMediaStateListener;

    /**
     * Listener to be notified when the background of the terminal changes, either because a media
     * was set or cleared with the `swset` command, or because an animation was enabled or disabled.
     */
    public interface MediaStateListener {
        /**
         * @param hasBackground {@code true} if the terminal must be rendered transparently so that
         * the media and/or the animation behind it is visible, {@code false} if the terminal
         * should paint its own (black) background.
         */
        void onMediaStateChanged(boolean hasBackground);
    }

    public MioMediaBackgroundView(@NonNull Context context) {
        this(context, null);
    }

    public MioMediaBackgroundView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MioMediaBackgroundView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        setBackgroundColor(DEFAULT_BACKGROUND_COLOR);

        // The media views are added at the bottom of the view (index 0) so that the child views of
        // this layout from the xml layout, like the terminal view, are drawn over them
        mImageView = new ImageView(context);
        LayoutParams imageLayoutParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        imageLayoutParams.gravity = Gravity.CENTER;
        mImageView.setScaleType(ImageView.ScaleType.MATRIX);
        mImageView.setBackgroundColor(DEFAULT_BACKGROUND_COLOR);
        mImageView.setId(R.id.mio_media_image_view);
        addView(mImageView, 0, imageLayoutParams);

        mTextureView = new TextureView(context);
        mTextureView.setSurfaceTextureListener(this);
        mTextureView.setId(R.id.mio_media_texture_view);
        addView(mTextureView, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // The animation is added at the end since the xml children (like the terminal view) are
        // added after this constructor, so the animation ends up above the media and below them
        mParticleView = new MioParticleView(context);
        mParticleView.setId(R.id.mio_particle_view);
        addView(mParticleView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        setInstance();
    }

    @Nullable
    public static MioMediaBackgroundView getInstance() {
        return sInstance;
    }

    /**
     * Set the listener that is notified when the media state changes.
     */
    public void setMediaStateListener(@Nullable MediaStateListener listener) {
        mMediaStateListener = listener;
        if (listener != null) listener.onMediaStateChanged(hasVisualBackground());
    }

    private void notifyMediaStateChanged() {
        if (mMediaStateListener != null) mMediaStateListener.onMediaStateChanged(hasVisualBackground());
    }

    /**
     * @return Returns {@code true} if the terminal must be rendered transparently, which is the
     * case if a media is set or an animation is enabled behind it.
     */
    public boolean hasVisualBackground() {
        return hasMedia() || mParticleView.getMode() != MioParticleView.Mode.NONE;
    }

    /** @return Returns the animation view that is drawn above the media and below the terminal. */
    @NonNull
    public MioParticleView getParticleView() {
        return mParticleView;
    }

    /**
     * Set the animation that is drawn behind the terminal text.
     *
     * @param mode The animation, {@link MioParticleView.Mode#NONE} disables it.
     */
    public void setAnimationMode(@NonNull MioParticleView.Mode mode) {
        mParticleView.setMode(mode);
        notifyMediaStateChanged();
    }

    /** @return Returns the currently drawn animation. */
    @NonNull
    public MioParticleView.Mode getAnimationMode() {
        return mParticleView.getMode();
    }

    void setInstance() {
        sInstance = this;
    }

    @Override
    protected void onDetachedFromWindow() {
        // The instance is not cleared here intentionally, the view is detached when the activity is
        // destroyed, but the media should still be applied when the next activity is created, and
        // the static instance is only used to apply the media on the main thread
        releaseMediaPlayer();
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInstance();
        notifyMediaStateChanged();

        // The surface is re-created by the texture view when it is attached again, play the video
        // again after the surface is available if it was set before the view was detached
        if (mIsVideo && mMediaFile != null && mMediaPlayer == null) {
            final File file = mMediaFile;
            final boolean playAudio = mAudioEnabled;
            final Runnable playRunnable = () -> startPlayback(file, playAudio);
            mPendingPlayRunnable = playRunnable;
            mMainHandler.post(() -> {
                if (!mTextureView.isAvailable() || mSurface == null) return;
                mPendingPlayRunnable = null;
                startPlayback(file, playAudio);
            });
        }
    }

    /**
     * Clear the background so that only the default black background is shown.
     */
    public void clearMedia() {
        mMediaFile = null;
        mIsVideo = false;
        mAudioEnabled = false;
        mPendingPlayRunnable = null;
        hideImage();
        releaseMediaPlayer();
        mTextureView.setVisibility(GONE);
        mTextureView.setTransform(new Matrix());
        mImageView.setVisibility(GONE);
        setBackgroundColor(DEFAULT_BACKGROUND_COLOR);
        notifyMediaStateChanged();
    }

    /**
     * Set the image file that is shown behind the terminal.
     *
     * @param file The image file.
     * @return Returns {@code true} if the image was loaded and is now shown, otherwise {@code false}.
     */
    public boolean setImage(@NonNull File file) {
        releaseMediaPlayer();
        mTextureView.setVisibility(GONE);

        try {
            Bitmap bitmap = decodeImage(file);
            if (bitmap == null) return false;

            mImageView.setImageDrawable(new BitmapDrawable(getResources(), bitmap));
            mImageView.setVisibility(VISIBLE);
            mMediaFile = file;
            mIsVideo = false;
            mAudioEnabled = false;
            mAudioProblem = null;
            releaseAudioPlayer();
            updateImageMatrix();
            notifyMediaStateChanged();
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load image file \"" + file.getAbsolutePath() + "\"", e);
            return false;
        }
    }

    /**
     * Set the video file that is shown behind the terminal. The video is muted unless
     * {@link #setAudioEnabled(boolean)}} is called with {@code true}.
     *
     * @param file The video file.
     * @param playAudio If the audio of the video should be played.
     * @return Returns {@code true} if the video was started, otherwise {@code false}.
     */
    public boolean setVideo(@NonNull File file, boolean playAudio) {
        hideImage();
        releaseMediaPlayer();

        mMediaFile = file;
        mIsVideo = true;
        mAudioEnabled = playAudio;
        mAudioProblem = null;
        mTextureView.setVisibility(VISIBLE);

        notifyMediaStateChanged();

        final Runnable playRunnable = () -> startPlayback(file, playAudio);
        mPendingPlayRunnable = playRunnable;
        if (mSurface != null) {
            mMainHandler.post(playRunnable);
        } else if (mTextureView.isAvailable()) {
            // The surface is not created yet, ensure it is created and then play
            mMainHandler.post(() -> {
                if (!mTextureView.isAvailable()) return;
                if (mSurface == null) {
                    onSurfaceTextureAvailable(mTextureView.getSurfaceTexture(),
                        mTextureView.getWidth(), mTextureView.getHeight());
                }
                startPlayback(file, playAudio);
            });
        }
        return true;
    }

    /**
     * Enable or disable the audio of the currently shown video. If no video is currently shown,
     * then nothing is done.
     *
     * @param enabled If the audio should be played or not.
     * @return Returns {@code true} if the currently shown media is a video, otherwise {@code false}.
     */
    public boolean setAudioEnabled(boolean enabled) {
        if (!mIsVideo || mMediaFile == null) return false;

        mAudioEnabled = enabled;
        if (mMediaPlayer != null) {
            try {
                if (enabled) {
                    mMediaPlayer.setVolume(1f, 1f);
                } else {
                    mMediaPlayer.setVolume(0f, 0f);
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to set media player volume", e);
            }

            if (enabled) {
                if (mMediaPlayer != null && !hasAudioTrack(mMediaPlayer) && mAudioPlayer == null) {
                    startSeparateAudioFileIfNeeded(mMediaFile, mMediaPlayer);
                }
            } else {
                releaseAudioPlayer();
            }
        } else {
            // The player was not created yet or was released, start playback again with the new state
            final File file = mMediaFile;
            final Runnable playRunnable = () -> startPlayback(file, enabled);
            mPendingPlayRunnable = playRunnable;
            if (mSurface != null) mMainHandler.post(playRunnable);
        }
        return true;
    }

    /**
     * @return Returns {@code true} if a video is currently shown and its audio is enabled.
     */
    public boolean isAudioEnabled() {
        return mIsVideo && mAudioEnabled;
    }

    public boolean isVideo() {
        return mIsVideo;
    }

    public boolean hasMedia() {
        return mMediaFile != null;
    }

    @Nullable
    public File getMediaFile() {
        return mMediaFile;
    }

    /**
     * Rescale the currently shown image, should be called when the view is resized.
     */
    public void updateImageMatrix() {
        if (mMediaFile == null || mIsVideo) return;

        Drawable drawable = mImageView.getDrawable();
        if (!(drawable instanceof BitmapDrawable)) return;
        Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return;
        if (getWidth() <= 0 || getHeight() <= 0) return;

        float scale = Math.max((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
        float dx = (getWidth() - bitmap.getWidth() * scale) / 2f;
        float dy = (getHeight() - bitmap.getHeight() * scale) / 2f;

        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        matrix.postTranslate(dx, dy);
        mImageView.setImageMatrix(matrix);
    }

    /**
     * Decode the image file, down sampling it if it is larger than the view so that big photos do
     * not use excessive memory and do not cause an {@link OutOfMemoryError}.
     *
     * @param file The image file.
     * @return Returns the decoded {@link Bitmap} or {@code null} if it could not be decoded.
     */
    @Nullable
    private Bitmap decodeImage(@NonNull File file) {
        try {
            BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
            boundsOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), boundsOptions);
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                Logger.logError(LOG_TAG, "Failed to read image bounds of \"" + file.getAbsolutePath() + "\"");
                return null;
            }

            // The view may not have been laid out yet, in which case a maximum dimension is used
            int targetWidth = getWidth();
            int targetHeight = getHeight();
            if (targetWidth <= 0 || targetHeight <= 0) {
                int maxDimension = Math.max(boundsOptions.outWidth, boundsOptions.outHeight);
                float scale = maxDimension > MAX_DECODED_IMAGE_DIMENSION
                    ? (float) MAX_DECODED_IMAGE_DIMENSION / maxDimension : 1f;
                targetWidth = Math.max((int) (boundsOptions.outWidth * scale), 1);
                targetHeight = Math.max((int) (boundsOptions.outHeight * scale), 1);
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calculateSampleSize(boundsOptions.outWidth, boundsOptions.outHeight,
                targetWidth, targetHeight);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError e) {
            Logger.logError(LOG_TAG, "Out of memory while decoding image file \"" + file.getAbsolutePath() + "\"");
            return null;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to decode image file \"" + file.getAbsolutePath() + "\"", e);
            return null;
        }
    }

    /** Calculate the inSampleSize so that the image is not much larger than the view. */
    private static int calculateSampleSize(int width, int height, int targetWidth, int targetHeight) {
        int sampleSize = 1;
        while (width / (sampleSize * 2) >= targetWidth && height / (sampleSize * 2) >= targetHeight) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    private void hideImage() {
        mImageView.setImageDrawable(new ColorDrawable(DEFAULT_BACKGROUND_COLOR));
        mImageView.setVisibility(GONE);
    }

    /**
     * Rescale the video so that it fills the view (center crop) instead of being stretched.
     */
    private void updateVideoTransform() {
        if (mMediaPlayer == null || !mIsVideo) return;

        int videoWidth = mMediaPlayer.getVideoWidth();
        int videoHeight = mMediaPlayer.getVideoHeight();
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return;

        float viewAspect = (float) viewWidth / viewHeight;
        float videoAspect = (float) videoWidth / videoHeight;

        float scaleX = 1f, scaleY = 1f;
        if (videoAspect > viewAspect) {
            // Video is wider than the view, scale it up vertically
            scaleY = videoAspect / viewAspect;
        } else {
            // Video is taller than the view, scale it up horizontally
            scaleX = viewAspect / videoAspect;
        }

        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f);
        mTextureView.setTransform(matrix);
    }

    private void startPlayback(@NonNull File file, final boolean playAudio) {
        mPendingPlayRunnable = null;
        releaseMediaPlayer();

        if (mSurface == null) {
            Logger.logWarn(LOG_TAG, "Cannot start video playback since surface is not available");
            return;
        }

        try {
            MediaPlayer mediaPlayer = new MediaPlayer();
            mediaPlayer.setSurface(mSurface);
            mediaPlayer.setLooping(true);
            mediaPlayer.setDataSource(getContext(), Uri.fromFile(file));
            mediaPlayer.setVolume(playAudio ? 1f : 0f, playAudio ? 1f : 0f);
            mediaPlayer.setOnPreparedListener(mp -> {
                Logger.logDebug(LOG_TAG, "Starting video playback for \"" + file.getAbsolutePath() + "\", audio " + (playAudio ? "enabled" : "muted"));
                mp.start();
                updateVideoTransform();
                if (playAudio) startSeparateAudioFileIfNeeded(file, mp);
            });
            mediaPlayer.setOnVideoSizeChangedListener((mp, width, height) -> updateVideoTransform());
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Logger.logError(LOG_TAG, "Media player error for \"" + file.getAbsolutePath() + "\": what=" + what + ", extra=" + extra);
                return false;
            });
            mMediaPlayer = mediaPlayer;
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start video playback for \"" + file.getAbsolutePath() + "\"", e);
            releaseMediaPlayer();
        }
    }

    /**
     * If the video has no audio track, try to play a sibling audio file with the same base name
     * instead (for example `swset video.mp4` + `video.mp3`), so that the `so` command still plays
     * audio while the video keeps playing silently.
     */
    private void startSeparateAudioFileIfNeeded(@Nullable File videoFile, @NonNull MediaPlayer mediaPlayer) {
        if (videoFile == null) return;
        if (hasAudioTrack(mediaPlayer)) {
            mAudioProblem = null;
            return;
        }
        if (!mAudioEnabled || mAudioPlayer != null) return;

        File audioFile = MioMediaFiles.findSiblingAudioFile(videoFile);
        if (audioFile == null) {
            Logger.logWarn(LOG_TAG, "Video file has no audio track and no sibling audio file found for \"" + videoFile.getAbsolutePath() + "\"");
            mAudioProblem = "videoda ses yok, yanına aynı isimli bir .mp3/.m4a koy";
            return;
        }

        Logger.logInfo(LOG_TAG, "Video file has no audio track, playing audio from sibling file \"" + audioFile.getAbsolutePath() + "\"");
        mAudioProblem = null;
        try {
            MediaPlayer audioPlayer = new MediaPlayer();
            audioPlayer.setLooping(true);
            audioPlayer.setVolume(1f, 1f);
            audioPlayer.setDataSource(getContext(), Uri.fromFile(audioFile));
            audioPlayer.setOnPreparedListener(MediaPlayer::start);
            audioPlayer.setOnErrorListener((mp, what, extra) -> {
                Logger.logError(LOG_TAG, "Audio player error for \"" + audioFile.getAbsolutePath() + "\": what=" + what + ", extra=" + extra);
                return false;
            });
            audioPlayer.prepareAsync();
            mAudioPlayer = audioPlayer;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start audio playback for \"" + audioFile.getAbsolutePath() + "\"", e);
        }
    }

    private void releaseAudioPlayer() {
        MediaPlayer audioPlayer = mAudioPlayer;
        mAudioPlayer = null;
        if (audioPlayer == null) return;
        try {
            if (audioPlayer.isPlaying()) audioPlayer.stop();
        } catch (Exception e) {
            // Ignore
        }
        try {
            audioPlayer.release();
        } catch (Exception e) {
            // Ignore
        }
    }

    private static boolean hasAudioTrack(@NonNull MediaPlayer mediaPlayer) {
        try {
            return hasAudioTrack(mediaPlayer.getTrackInfo());
        } catch (Exception e) {
            // getTrackInfo() is available on api 16+, assume audio may be present
            return true;
        }
    }

    private static boolean hasAudioTrack(@Nullable TrackInfo[] trackInfos) {
        if (trackInfos == null) return false;
        for (TrackInfo trackInfo : trackInfos) {
            if (trackInfo != null && trackInfo.getTrackType() == TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                return true;
            }
        }
        return false;
    }

    private void releaseMediaPlayer() {
        releaseAudioPlayer();
        MediaPlayer mediaPlayer = mMediaPlayer;
        mMediaPlayer = null;
        if (mediaPlayer == null) return;
        try {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
        } catch (Exception e) {
            // Ignore, the player may have already been released or in an invalid state
        }
        try {
            mediaPlayer.release();
        } catch (Exception e) {
            // Ignore
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateImageMatrix();
        updateVideoTransform();
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
        mSurface = new Surface(surfaceTexture);
        if (mPendingPlayRunnable != null) {
            Runnable runnable = mPendingPlayRunnable;
            mPendingPlayRunnable = null;
            mMainHandler.post(runnable);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
        updateVideoTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
        releaseMediaPlayer();
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
        // Nothing to do
    }
}
