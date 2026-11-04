package app.ktmnav.backend.obd;

import java.util.Locale;

/**
 * Formats the optional speed/gear dash banner from a snapshot; null when the values are stale.
 */
public final class ObdDashBanner {

    public static final String OWNER = "obd";
    public static final long PERIOD_MS = 2000L;
    public static final long FRESH_MS = 3000L;
    public static final long HOLD_MS = 2500L;

    private ObdDashBanner() {
    }

    public static String text(ObdSnapshot snapshot, long nowMs, GearEstimator.Profile profile) {
        if (snapshot == null || !snapshot.connected) {
            return null;
        }
        Double speed = snapshot.fresh(ObdPids.Pid.SPEED, nowMs, FRESH_MS);
        if (speed == null) {
            return null;
        }
        Integer gear = snapshot.estimatedGear(profile, nowMs, FRESH_MS);
        return String.format(Locale.ROOT, "%d km/h  G%s", Math.round(speed), gear == null ? "-" : String.valueOf(gear));
    }
}
