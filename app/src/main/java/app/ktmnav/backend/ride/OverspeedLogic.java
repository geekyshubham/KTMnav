package app.ktmnav.backend.ride;

import java.util.Locale;

/**
 * Overspeed alert state machine with hysteresis and a beep rate limit.
 * Based on OpenDash SpeedMonitor (MIT License, Copyright (c) 2026 OpenDash contributors).
 */
public final class OverspeedLogic {
    public static final int HYSTERESIS_KMH = 10;
    public static final long BEEP_INTERVAL_MS = 10_000L;
    public static final long BANNER_REFRESH_MS = 2_000L;
    public static final long BANNER_HOLD_MS = 5_000L;
    public static final long STALE_FIX_MS = 15_000L;

    public static final class Action {
        public static final Action NONE = new Action(false, null, false);
        public final boolean beep;
        public final String banner;
        public final boolean clearBanner;

        Action(boolean beep, String banner, boolean clearBanner) {
            this.beep = beep;
            this.banner = banner;
            this.clearBanner = clearBanner;
        }
    }

    private static final Action CLEAR = new Action(false, null, true);

    private boolean alerting;
    private boolean beeped;
    private boolean bannerShown;
    private long lastBeepMs;
    private int lastBannerSpeed = -1;
    private long lastBannerMs;

    public boolean isAlerting() {
        return alerting;
    }

    public boolean isBannerShown() {
        return bannerShown;
    }

    /**
     * The banner is shown only while above the limit and cleared at or below it; the beep re-arms
     * only after dropping below limit - HYSTERESIS_KMH, and a fresh episode beeps immediately.
     */
    public Action onSpeed(float kmh, int limitKmh, long nowMs) {
        if (Float.isNaN(kmh)) return Action.NONE;
        if (!alerting && kmh > limitKmh) {
            alerting = true;
            beeped = false;
            lastBannerSpeed = -1;
        }
        if (!alerting) return Action.NONE;
        if (kmh < limitKmh - HYSTERESIS_KMH) return reset();
        if (kmh <= limitKmh) {
            if (!bannerShown) return Action.NONE;
            bannerShown = false;
            lastBannerSpeed = -1;
            return CLEAR;
        }
        boolean beep = false;
        if (!beeped || nowMs - lastBeepMs >= BEEP_INTERVAL_MS) {
            beeped = true;
            lastBeepMs = nowMs;
            beep = true;
        }
        int shown = Math.round(kmh);
        String banner = null;
        if (!bannerShown || shown != lastBannerSpeed || nowMs - lastBannerMs >= BANNER_REFRESH_MS) {
            lastBannerSpeed = shown;
            lastBannerMs = nowMs;
            bannerShown = true;
            banner = bannerText(shown);
        }
        return beep || banner != null ? new Action(beep, banner, false) : Action.NONE;
    }

    public Action reset() {
        if (!alerting) return Action.NONE;
        alerting = false;
        beeped = false;
        lastBannerSpeed = -1;
        if (!bannerShown) return Action.NONE;
        bannerShown = false;
        return CLEAR;
    }

    public static String bannerText(int kmh) {
        return String.format(Locale.ROOT, "OVERSPEED %d", kmh);
    }
}
