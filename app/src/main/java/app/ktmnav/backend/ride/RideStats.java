package app.ktmnav.backend.ride;

import java.util.List;

public final class RideStats {
    public static final double EARTH_RADIUS_M = 6_371_008.8;
    public static final double MOVING_KMH = 3.0;
    public static final double ELEVATION_NOISE_M = 3.0;
    public static final RideStats EMPTY = new RideStats(0, 0, 0, 0, 0, 0, 0, TrackPoint.NO_TIME, TrackPoint.NO_TIME);

    public final double distanceM;
    public final long durationMs;
    public final long movingMs;
    public final double maxKmh;
    public final double avgMovingKmh;
    public final double elevationGainM;
    public final int points;
    public final long startMs;
    public final long endMs;

    RideStats(double distanceM, long durationMs, long movingMs, double maxKmh, double avgMovingKmh,
              double elevationGainM, int points, long startMs, long endMs) {
        this.distanceM = distanceM;
        this.durationMs = durationMs;
        this.movingMs = movingMs;
        this.maxKmh = maxKmh;
        this.avgMovingKmh = avgMovingKmh;
        this.elevationGainM = elevationGainM;
        this.points = points;
        this.startMs = startMs;
        this.endMs = endMs;
    }

    public static RideStats of(List<TrackPoint> pts) {
        if (pts == null || pts.isEmpty()) return EMPTY;
        double distance = 0;
        long moving = 0;
        double maxRecorded = Double.NaN;
        double maxDerived = 0;
        double gain = 0;
        double eleRef = Double.NaN;
        long start = TrackPoint.NO_TIME;
        long end = TrackPoint.NO_TIME;
        TrackPoint prev = null;
        for (TrackPoint p : pts) {
            if (p.hasTime()) {
                if (start == TrackPoint.NO_TIME) start = p.timeMs;
                end = p.timeMs;
            }
            if (p.hasSpeed()) maxRecorded = Double.isNaN(maxRecorded) ? p.speedKmh : Math.max(maxRecorded, p.speedKmh);
            if (p.hasEle()) {
                if (Double.isNaN(eleRef)) {
                    eleRef = p.ele;
                } else if (Math.abs(p.ele - eleRef) >= ELEVATION_NOISE_M) {
                    if (p.ele > eleRef) gain += p.ele - eleRef;
                    eleRef = p.ele;
                }
            }
            if (prev != null) {
                double d = haversine(prev.lat, prev.lon, p.lat, p.lon);
                distance += d;
                if (prev.hasTime() && p.hasTime() && p.timeMs > prev.timeMs) {
                    long dt = p.timeMs - prev.timeMs;
                    double kmh = d / (dt / 1000.0) * 3.6;
                    if (kmh > MOVING_KMH) moving += dt;
                    if (dt >= 1000L) maxDerived = Math.max(maxDerived, kmh);
                }
            }
            prev = p;
        }
        long duration = start != TrackPoint.NO_TIME ? end - start : 0;
        double avg = moving > 0 ? distance / (moving / 1000.0) * 3.6 : 0;
        double max = Double.isNaN(maxRecorded) ? maxDerived : maxRecorded;
        return new RideStats(distance, duration, moving, max, avg, gain, pts.size(), start, end);
    }

    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
