package app.ktmnav.backend.ride;

import android.content.SharedPreferences;
import java.util.Locale;

public final class RidePrefs {
    public static final String PREFS = "ktmnav";
    public static final String BEEPER_ENABLED = "beeper_enabled";
    public static final String BEEPER_VOLUME = "beeper_volume";
    public static final String BEEPER_SWAP_LR = "beeper_swap_lr";
    public static final String OVERSPEED_ENABLED = "overspeed_enabled";
    public static final String OVERSPEED_LIMIT_KMH = "overspeed_limit_kmh";
    public static final String RIDE_RECORDING = "ride_recording";
    public static final String WAYPOINT = "waypoint";
    public static final String WAYPOINT_LABEL = "waypoint_label";

    public static final String MODE_OFF = "off";
    public static final String MODE_WHILE_CONNECTED = "while_connected";
    public static final String MODE_MANUAL = "manual";

    public static final int DEFAULT_BEEPER_VOLUME = 60;
    public static final int DEFAULT_OVERSPEED_LIMIT = 80;
    public static final int MIN_OVERSPEED_LIMIT = 20;
    public static final int MAX_OVERSPEED_LIMIT = 250;

    private RidePrefs() {
    }

    public static String normaliseMode(String mode) {
        if (mode == null) return MODE_OFF;
        String m = mode.trim().toLowerCase(Locale.ROOT);
        if (MODE_WHILE_CONNECTED.equals(m) || MODE_MANUAL.equals(m)) return m;
        return MODE_OFF;
    }

    public static boolean shouldRecord(String mode, boolean bikeConnected, boolean manualActive) {
        switch (normaliseMode(mode)) {
            case MODE_WHILE_CONNECTED: return bikeConnected || manualActive;
            case MODE_MANUAL: return manualActive;
            default: return false;
        }
    }

    public static int clampLimit(int kmh) {
        return Math.max(MIN_OVERSPEED_LIMIT, Math.min(MAX_OVERSPEED_LIMIT, kmh));
    }

    public static int clampVolume(int v) {
        return Math.max(0, Math.min(100, v));
    }

    public static int intPref(SharedPreferences prefs, String key, int fallback) {
        if (prefs == null) return fallback;
        try {
            return prefs.getInt(key, fallback);
        } catch (ClassCastException e) {
            try {
                return Integer.parseInt(prefs.getString(key, String.valueOf(fallback)).trim());
            } catch (RuntimeException e2) {
                return fallback;
            }
        }
    }

    public static boolean beeperEnabled(SharedPreferences p) { return p != null && p.getBoolean(BEEPER_ENABLED, false); }

    public static int beeperVolume(SharedPreferences p) { return clampVolume(intPref(p, BEEPER_VOLUME, DEFAULT_BEEPER_VOLUME)); }

    public static boolean beeperSwap(SharedPreferences p) { return p != null && p.getBoolean(BEEPER_SWAP_LR, false); }

    public static boolean overspeedEnabled(SharedPreferences p) { return p != null && p.getBoolean(OVERSPEED_ENABLED, false); }

    public static int overspeedLimit(SharedPreferences p) { return clampLimit(intPref(p, OVERSPEED_LIMIT_KMH, DEFAULT_OVERSPEED_LIMIT)); }

    public static String recordingMode(SharedPreferences p) { return normaliseMode(p != null ? p.getString(RIDE_RECORDING, MODE_OFF) : MODE_OFF); }
}
