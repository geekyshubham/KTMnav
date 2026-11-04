package app.ktmnav.backend.ride;

import java.util.Locale;

public final class RideFormat {
    private RideFormat() {
    }

    public static String distance(double metres) {
        if (metres < 1000) return String.format(Locale.ROOT, "%d m", Math.round(metres));
        return String.format(Locale.ROOT, "%.1f km", metres / 1000.0);
    }

    public static String duration(long ms) {
        long totalMin = Math.max(0L, ms) / 60_000L;
        long h = totalMin / 60;
        long m = totalMin % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d h %02d min", h, m) : String.format(Locale.ROOT, "%d min", m);
    }

    public static String speed(double kmh) {
        return String.format(Locale.ROOT, "%.0f km/h", kmh);
    }

    public static String describe(RideStats s) {
        if (s == null || s.points == 0) return "Ride track, no points recorded";
        return "Ride track, " + distance(s.distanceM) + ", " + duration(s.durationMs)
                + ", max " + speed(s.maxKmh);
    }
}
