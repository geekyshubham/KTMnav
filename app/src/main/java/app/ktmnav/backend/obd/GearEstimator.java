package app.ktmnav.backend.obd;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Estimates the engaged gear from the engine-rpm to road-speed ratio. OBD-II has no gear PID,
 * so every result is an estimate and must be presented as such.
 */
public final class GearEstimator {

    public static final double TOLERANCE = 0.14;
    public static final double MIN_SPEED_KMH = 5.0;
    public static final double MIN_RPM = 1500.0;

    public static final class Profile {
        public final String id;
        public final String name;
        public final double[] rpmPerKmh;
        public final boolean verified;
        public final String source;

        public Profile(String id, String name, double[] rpmPerKmh, boolean verified, String source) {
            this.id = id;
            this.name = name;
            this.rpmPerKmh = rpmPerKmh.clone();
            this.verified = verified;
            this.source = source;
        }

        public int gears() {
            return rpmPerKmh.length;
        }
    }

    public static final Profile KTM_390_DUKE_GEN3 = new Profile(
            "ktm-390-duke-gen3",
            "KTM 390 Duke (2024 Gen 3, 6-speed)",
            new double[]{225, 158, 124, 104, 91, 82},
            false,
            "Unverified: rpm-per-km/h table from the ktm-Connect-App fact sheet; no published primary/gearbox/final-drive ratios for the Gen 3 were found");

    public static final List<Profile> PROFILES = Collections.unmodifiableList(Arrays.asList(KTM_390_DUKE_GEN3));

    private GearEstimator() {
    }

    public static Profile profileById(String id) {
        for (Profile p : PROFILES) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return KTM_390_DUKE_GEN3;
    }

    public static Integer estimate(Double rpm, Double speedKmh, Profile profile) {
        if (rpm == null || speedKmh == null || profile == null || profile.rpmPerKmh.length == 0) {
            return null;
        }
        if (speedKmh < MIN_SPEED_KMH || rpm < MIN_RPM) {
            return null;
        }
        double measured = rpm / speedKmh;
        int best = -1;
        double bestError = Double.MAX_VALUE;
        for (int i = 0; i < profile.rpmPerKmh.length; i++) {
            double error = Math.abs(measured - profile.rpmPerKmh[i]) / profile.rpmPerKmh[i];
            if (error < bestError) {
                bestError = error;
                best = i + 1;
            }
        }
        return bestError <= TOLERANCE ? best : null;
    }

    public static String label(Integer gear) {
        return gear == null ? "- (estimated)" : gear + " (estimated)";
    }
}
