package app.ktmnav.backend.ride;

import app.ktmnav.backend.AppLog;
import app.ktmnav.backend.TurnIcon;

/**
 * Stereo turn-approach beeper driven by navigation guidance updates.
 * Based on OpenDash TurnBeeper (MIT License, Copyright (c) 2026 OpenDash contributors).
 */
public final class TurnBeeper {
    private static final String TAG = "TurnBeeper";
    private static final long PREVIEW_GAP_MS = 500L;

    public interface SpeedSource {
        Float speedKmh();
    }

    private final RideScheduler scheduler;
    private final BeepOutput output;
    private final SpeedSource speed;
    private final BeepScheduler plan = new BeepScheduler();
    private boolean ticking;
    private final Runnable tick = this::onTick;

    public TurnBeeper(RideScheduler scheduler, BeepOutput output, SpeedSource speed) {
        this.scheduler = scheduler;
        this.output = output;
        this.speed = speed;
    }

    public void configure(boolean enabled, int volumePercent, boolean swapLeftRight) {
        plan.configure(enabled, volumePercent, swapLeftRight);
        if (!enabled) halt();
    }

    public void onGuidance(String distanceText, TurnIcon icon) {
        if (!plan.onGuidance(distanceText, icon, scheduler.now())) {
            halt();
            return;
        }
        if (!ticking) {
            ticking = true;
            AppLog.d(TAG, "Turn metronome started (" + plan.getSide() + ")");
            scheduler.postDelayed(tick, 0L);
        }
    }

    public void onGuidanceEnded() {
        plan.onEnded();
        halt();
    }

    public boolean isBeeping() {
        return ticking;
    }

    public void preview() {
        BeepScheduler.Side[] order = {BeepScheduler.Side.LEFT, BeepScheduler.Side.RIGHT, BeepScheduler.Side.CENTRE};
        for (int i = 0; i < order.length; i++) {
            float[] g = plan.previewGains(order[i]);
            scheduler.postDelayed(() -> safePlay(g[0], g[1]), i * PREVIEW_GAP_MS);
        }
    }

    public void release() {
        plan.onEnded();
        scheduler.cancel(tick);
        ticking = false;
        output.release();
    }

    private void onTick() {
        BeepScheduler.Beep beep = plan.tick(scheduler.now(), speed != null ? speed.speedKmh() : null);
        if (beep == null) {
            AppLog.d(TAG, "Turn metronome stopped");
            halt();
            return;
        }
        safePlay(beep.left, beep.right);
        scheduler.postDelayed(tick, beep.nextDelayMs);
    }

    private void safePlay(float left, float right) {
        if (left <= 0f && right <= 0f) return;
        try {
            output.play(left, right);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "Beep failed: " + e.getMessage());
        }
    }

    private void halt() {
        scheduler.cancel(tick);
        if (ticking) output.release();
        ticking = false;
    }
}
