package com.almog.spotifytablet;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.lang.ref.WeakReference;
import java.util.Random;

/**
 * Encapsulates AOD (Always-On Display) clock and toggle button movement to protect against OLED burn-in.
 * Uses WeakReferences to prevent leaking Activity instances.
 */
public class AodBurnInController {
    private static final long CLOCK_MOVE_INTERVAL = 60000L;
    private static final long TOGGLE_MOVE_INTERVAL = 180000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WeakReference<View> clockContentRef;
    private final WeakReference<View> toggleAodRef;
    private final Random random = new Random();

    private boolean isClockActive = false;
    private boolean isToggleActive = false;

    private final Runnable clockMover = new Runnable() {
        @Override
        public void run() {
            if (!isClockActive) return;
            moveClock();
            handler.postDelayed(this, CLOCK_MOVE_INTERVAL);
        }
    };

    private final Runnable toggleMover = new Runnable() {
        @Override
        public void run() {
            if (!isToggleActive) return;
            moveToggle();
            handler.postDelayed(this, TOGGLE_MOVE_INTERVAL);
        }
    };

    public AodBurnInController(View clockContent, View toggleAod) {
        this.clockContentRef = new WeakReference<>(clockContent);
        this.toggleAodRef = new WeakReference<>(toggleAod);
    }

    public void startClockMovement() {
        if (isClockActive) return;
        isClockActive = true;
        handler.removeCallbacks(clockMover);
        handler.post(clockMover);
    }

    public void stopClockMovement() {
        isClockActive = false;
        handler.removeCallbacks(clockMover);
        View clockContent = clockContentRef.get();
        if (clockContent != null) {
            clockContent.animate().cancel();
        }
    }

    public void startToggleMovement() {
        if (isToggleActive) return;
        isToggleActive = true;
        handler.removeCallbacks(toggleMover);
        handler.post(toggleMover);
    }

    public void stopToggleMovement() {
        isToggleActive = false;
        handler.removeCallbacks(toggleMover);
    }

    private void moveClock() {
        View clockContent = clockContentRef.get();
        if (clockContent == null || clockContent.getContext() == null) return;

        int sw = clockContent.getResources().getDisplayMetrics().widthPixels;
        int sh = clockContent.getResources().getDisplayMetrics().heightPixels;
        int cw = clockContent.getWidth();
        int ch = clockContent.getHeight();

        if (cw == 0 || ch == 0) {
            clockContent.post(this::moveClock);
            return;
        }

        float tx = random.nextInt(Math.max(1, sw - cw));
        float ty = random.nextInt(Math.max(1, sh - ch));

        clockContent.animate()
                .x(tx)
                .y(ty)
                .setDuration(3000)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .start();
    }

    private void moveToggle() {
        View toggleAod = toggleAodRef.get();
        if (toggleAod == null) return;

        int dx = random.nextInt(15) - 7;
        int dy = random.nextInt(15) - 7;
        toggleAod.setTranslationX(dx);
        toggleAod.setTranslationY(dy);
    }

    public void cleanup() {
        stopClockMovement();
        stopToggleMovement();
        handler.removeCallbacksAndMessages(null);
    }
}
