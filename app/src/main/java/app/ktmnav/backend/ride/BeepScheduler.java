package app.ktmnav.backend.ride;

import app.ktmnav.backend.TurnIcon;

/**
 * Turn-approach metronome timing, panning and ducking.
 * Based on OpenDash TurnBeeper (MIT License, Copyright (c) 2026 OpenDash contributors).
 */
public final class BeepScheduler {
    public static final int MAX_DISTANCE_M = 400;
    public static final long MIN_GAP_MS = 160L;
    public static final long MAX_GAP_MS = 1600L;
    public static final long STALE_GUIDANCE_MS = 20_000L;
    public static final float STATIONARY_KMH = 4f;
    public static final float DUCK_FACTOR = 0.12f;
    public static final float CENTRE_GAIN = 0.7071f;

    public enum Side { LEFT, CENTRE, RIGHT }

    public static final class Beep {
        public final float left;
        public final float right;
        public final long nextDelayMs;

        Beep(float left, float right, long nextDelayMs) {
            this.left = left;
            this.right = right;
            this.nextDelayMs = nextDelayMs;
        }
    }

    private boolean enabled;
    private int volumePercent = RidePrefs.DEFAULT_BEEPER_VOLUME;
    private boolean swap;
    private boolean active;
    private int metres = DistanceParser.UNKNOWN;
    private Side side = Side.CENTRE;
    private long lastGuidanceMs;

    public void configure(boolean enabled, int volumePercent, boolean swap) {
        this.enabled = enabled;
        this.volumePercent = Math.max(0, Math.min(100, volumePercent));
        this.swap = swap;
        if (!enabled) active = false;
    }

    public boolean onGuidance(String distanceText, TurnIcon icon, long nowMs) {
        int m = DistanceParser.metres(distanceText);
        if (!enabled || m == DistanceParser.UNKNOWN || m > MAX_DISTANCE_M) {
            active = false;
            return false;
        }
        metres = m;
        side = sideOf(icon);
        lastGuidanceMs = nowMs;
        active = true;
        return true;
    }

    public void onEnded() {
        active = false;
    }

    public boolean isActive() {
        return active;
    }

    public Side getSide() {
        return side;
    }

    public Beep tick(long nowMs, Float speedKmh) {
        if (!active) return null;
        if (nowMs - lastGuidanceMs > STALE_GUIDANCE_MS) {
            active = false;
            return null;
        }
        boolean ducked = speedKmh != null && speedKmh < STATIONARY_KMH;
        float amplitude = volumePercent / 100f * (ducked ? DUCK_FACTOR : 1f);
        float[] g = gains(side, swap, amplitude);
        return new Beep(g[0], g[1], gapForMetres(metres));
    }

    public float[] previewGains(Side previewSide) {
        return gains(previewSide, swap, volumePercent / 100f);
    }

    public static long gapForMetres(int metres) {
        int m = Math.max(0, Math.min(MAX_DISTANCE_M, metres));
        return MIN_GAP_MS + (MAX_GAP_MS - MIN_GAP_MS) * m / MAX_DISTANCE_M;
    }

    public static Side sideOf(TurnIcon icon) {
        if (icon == null) return Side.CENTRE;
        String name = icon.name();
        if (name.contains("LEFT") || name.endsWith("_LH")) return Side.LEFT;
        if (name.contains("RIGHT") || name.endsWith("_RH")) return Side.RIGHT;
        return Side.CENTRE;
    }

    public static float[] gains(Side side, boolean swap, float amplitude) {
        float left;
        float right;
        switch (side) {
            case LEFT: left = amplitude; right = 0f; break;
            case RIGHT: left = 0f; right = amplitude; break;
            default: left = amplitude * CENTRE_GAIN; right = amplitude * CENTRE_GAIN;
        }
        return swap ? new float[]{right, left} : new float[]{left, right};
    }
}
