package com.termux.app.mio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Random;

/**
 * The animated overlay that is drawn between the background media (the image or the video set with
 * the `swset` command) and the terminal text, like the "snow" (雪花) and "particles" (粒子) effects
 * of ZeroTermux.
 *
 * The view never handles touch events and never draws a background, so the black background of
 * {@link MioMediaBackgroundView} and the media stay visible, and the terminal text stays readable.
 *
 * The animation is only run while a mode other than {@link Mode#NONE} is set and the view is
 * attached to a window, so that no battery is used while the effects are disabled.
 */
public final class MioParticleView extends View {

    public static final String LOG_TAG = "MioParticleView";

    /** The animation that should be drawn. */
    public enum Mode {
        /** No animation, the view does not draw anything. */
        NONE,
        /** Snowflakes (雪花) slowly falling down with a light wind. */
        SNOW,
        /** Glowing particles (粒子) floating around, connected by thin lines. */
        PARTICLES
    }

    /** The maximum frame rate of the animation, the effects are ambient so 30 fps is enough. */
    private static final long FRAME_DELAY_MS = 33;

    private static final int SNOW_FLAKE_COUNT = 110;
    private static final int PARTICLE_COUNT = 60;
    /** The distance in dp under which two particles are connected with a line. */
    private static final float PARTICLE_LINK_DISTANCE_DP = 70f;

    private final Random mRandom = new Random();

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @NonNull
    private Mode mMode = Mode.NONE;

    /** If the animation loop is currently posted. */
    private boolean mRunning = false;
    /** If the view has been laid out at least once, the particles are created on the first layout. */
    private boolean mInitialized = false;

    private float[] mX = new float[0];
    private float[] mY = new float[0];
    private float[] mRadius = new float[0];
    private float[] mSpeedY = new float[0];
    private float[] mDriftA = new float[0];
    private float[] mDriftB = new float[0];
    private float[] mPhase = new float[0];
    private float[] mAlpha = new float[0];

    private float mWindPhase = 0f;
    private final float mParticleLinkDistance;

    private final Runnable mFrameRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) return;
            step();
            invalidate();
            postDelayed(this, FRAME_DELAY_MS);
        }
    };

    public MioParticleView(@NonNull Context context) {
        this(context, null);
    }

    public MioParticleView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MioParticleView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mParticleLinkDistance = PARTICLE_LINK_DISTANCE_DP * getResources().getDisplayMetrics().density;
        // The view is only an overlay, it must never intercept the touches of the terminal view
        setClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** @return Returns the currently drawn animation. */
    @NonNull
    public Mode getMode() {
        return mMode;
    }

    /**
     * Set the animation that should be drawn.
     *
     * @param mode The new animation, {@link Mode#NONE} stops the animation.
     */
    public void setMode(@NonNull Mode mode) {
        if (mMode == mode) return;
        mMode = mode;
        if (mode == Mode.NONE) {
            stopAnimation();
            invalidate();
        } else {
            initializeParticlesIfNeeded();
            startAnimation();
            invalidate();
        }
    }

    public boolean isSnowEnabled() {
        return mMode == Mode.SNOW;
    }

    public boolean isParticlesEnabled() {
        return mMode == Mode.PARTICLES;
    }

    /** Set the snow animation on or off, the particles are turned off if it is turned on. */
    public void setSnowEnabled(boolean enabled) {
        setMode(enabled ? Mode.SNOW : (mMode == Mode.SNOW ? Mode.NONE : mMode));
    }

    /** Set the particle animation on or off, the snow is turned off if it is turned on. */
    public void setParticlesEnabled(boolean enabled) {
        setMode(enabled ? Mode.PARTICLES : (mMode == Mode.PARTICLES ? Mode.NONE : mMode));
    }

    private void startAnimation() {
        if (mRunning || mMode == Mode.NONE) return;
        mRunning = true;
        postDelayed(mFrameRunnable, FRAME_DELAY_MS);
    }

    private void stopAnimation() {
        mRunning = false;
        removeCallbacks(mFrameRunnable);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mMode != Mode.NONE) startAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mInitialized = false;
        initializeParticlesIfNeeded();
    }

    /** Create the particle arrays for the current mode, the previous positions are discarded. */
    private void initializeParticlesIfNeeded() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0 || mMode == Mode.NONE) return;

        int count = mMode == Mode.SNOW ? SNOW_FLAKE_COUNT : PARTICLE_COUNT;
        mX = new float[count];
        mY = new float[count];
        mRadius = new float[count];
        mSpeedY = new float[count];
        mDriftA = new float[count];
        mDriftB = new float[count];
        mPhase = new float[count];
        mAlpha = new float[count];

        float density = getResources().getDisplayMetrics().density;

        for (int i = 0; i < count; i++) {
            mX[i] = mRandom.nextFloat() * width;
            mY[i] = mRandom.nextFloat() * height;
            mPhase[i] = mRandom.nextFloat() * (float) (Math.PI * 2);
            if (mMode == Mode.SNOW) {
                // Snowflakes: 1dp to 4dp, falling at 0.2dp to 1.2dp per frame
                mRadius[i] = (0.6f + mRandom.nextFloat() * 2.4f) * density;
                mSpeedY[i] = (0.15f + mRandom.nextFloat() * 0.9f) * density;
                mDriftA[i] = (0.2f + mRandom.nextFloat() * 0.9f) * density;
                mDriftB[i] = 0.4f + mRandom.nextFloat() * 0.8f;
                mAlpha[i] = 120 + mRandom.nextInt(110);
            } else {
                // Particles: 0.8dp to 2.2dp, floating slowly at different speeds
                mRadius[i] = (0.8f + mRandom.nextFloat() * 1.4f) * density;
                mSpeedY[i] = (0.08f + mRandom.nextFloat() * 0.35f) * density;
                mDriftA[i] = (0.15f + mRandom.nextFloat() * 0.55f) * density;
                mDriftB[i] = 0.3f + mRandom.nextFloat() * 0.7f;
                mAlpha[i] = 90 + mRandom.nextInt(120);
            }
        }

        mInitialized = true;
    }

    /** Move the particles, wrapping them around the view edges when they leave it. */
    private void step() {
        if (!mInitialized) {
            initializeParticlesIfNeeded();
            if (!mInitialized) return;
        }

        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        mWindPhase += 0.02f;
        if (mWindPhase > Math.PI * 2) mWindPhase -= (float) (Math.PI * 2);

        boolean snow = mMode == Mode.SNOW;

        for (int i = 0; i < mX.length; i++) {
            float speedY = snow ? mSpeedY[i] : -mSpeedY[i];
            mY[i] += speedY;
            mX[i] += (float) Math.sin(mWindPhase + mPhase[i]) * mDriftA[i] * mDriftB[i];

            if (mY[i] > height + 8) {
                mY[i] = -8;
                mX[i] = mRandom.nextFloat() * width;
            } else if (mY[i] < -8) {
                mY[i] = height + 8;
                mX[i] = mRandom.nextFloat() * width;
            }

            if (mX[i] < -8) {
                mX[i] = width + 8;
            } else if (mX[i] > width + 8) {
                mX[i] = -8;
            }
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (mMode == Mode.NONE || !mInitialized) return;

        if (mMode == Mode.SNOW) {
            drawSnow(canvas);
        } else {
            drawParticles(canvas);
        }
    }

    private void drawSnow(@NonNull Canvas canvas) {
        for (int i = 0; i < mX.length; i++) {
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(Color.argb((int) mAlpha[i], 255, 255, 255));
            canvas.drawCircle(mX[i], mY[i], mRadius[i], mPaint);
        }
    }

    private void drawParticles(@NonNull Canvas canvas) {
        // The lines are drawn first so that the particles are drawn on top of them
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(1f);
        float maxDistanceSquared = mParticleLinkDistance * mParticleLinkDistance;
        int linkCount = Math.min(mX.length, 40);

        for (int i = 0; i < linkCount; i++) {
            for (int j = i + 1; j < linkCount; j++) {
                float dx = mX[i] - mX[j];
                float dy = mY[i] - mY[j];
                float distanceSquared = dx * dx + dy * dy;
                if (distanceSquared > maxDistanceSquared) continue;

                float distance = (float) Math.sqrt(distanceSquared);
                int alpha = (int) (70 * (1f - distance / mParticleLinkDistance));
                if (alpha <= 0) continue;

                mPaint.setColor(Color.argb(alpha, 140, 220, 255));
                canvas.drawLine(mX[i], mY[i], mX[j], mY[j], mPaint);
            }
        }

        mPaint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < mX.length; i++) {
            // A faint halo and a brighter core give the particle a soft glow without a BlurMaskFilter
            mPaint.setColor(Color.argb((int) (mAlpha[i] * 0.25f), 130, 210, 255));
            canvas.drawCircle(mX[i], mY[i], mRadius[i] * 2.6f, mPaint);
            mPaint.setColor(Color.argb((int) mAlpha[i], 190, 235, 255));
            canvas.drawCircle(mX[i], mY[i], mRadius[i], mPaint);
        }
    }
}
