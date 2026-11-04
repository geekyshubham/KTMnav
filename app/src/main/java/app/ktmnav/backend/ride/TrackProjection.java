package app.ktmnav.backend.ride;

import java.util.List;

public final class TrackProjection {
    public static final float[] BAND_LIMITS_KMH = {30f, 60f, 90f};

    private TrackProjection() {
    }

    public static float[] project(List<TrackPoint> pts, float width, float height, float padding) {
        int n = pts == null ? 0 : pts.size();
        float[] xy = new float[n * 2];
        if (n == 0) return xy;
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE, minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (TrackPoint p : pts) {
            minLat = Math.min(minLat, p.lat);
            maxLat = Math.max(maxLat, p.lat);
            minLon = Math.min(minLon, p.lon);
            maxLon = Math.max(maxLon, p.lon);
        }
        double k = Math.cos(Math.toRadians((minLat + maxLat) / 2.0));
        double spanX = (maxLon - minLon) * k;
        double spanY = maxLat - minLat;
        double availW = Math.max(1.0, width - 2.0 * padding);
        double availH = Math.max(1.0, height - 2.0 * padding);
        double scale = Math.min(spanX > 0 ? availW / spanX : Double.MAX_VALUE, spanY > 0 ? availH / spanY : Double.MAX_VALUE);
        if (scale == Double.MAX_VALUE) scale = 0;
        double offX = padding + (availW - spanX * scale) / 2.0;
        double offY = padding + (availH - spanY * scale) / 2.0;
        for (int i = 0; i < n; i++) {
            TrackPoint p = pts.get(i);
            xy[i * 2] = (float) (offX + (p.lon - minLon) * k * scale);
            xy[i * 2 + 1] = (float) (offY + (maxLat - p.lat) * scale);
        }
        return xy;
    }

    public static float[] speeds(List<TrackPoint> pts) {
        int n = pts == null ? 0 : pts.size();
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            TrackPoint p = pts.get(i);
            if (p.hasSpeed()) {
                out[i] = p.speedKmh;
            } else if (i > 0 && p.hasTime() && pts.get(i - 1).hasTime() && p.timeMs > pts.get(i - 1).timeMs) {
                TrackPoint q = pts.get(i - 1);
                out[i] = (float) (RideStats.haversine(q.lat, q.lon, p.lat, p.lon) / ((p.timeMs - q.timeMs) / 1000.0) * 3.6);
            } else {
                out[i] = i > 0 ? out[i - 1] : 0f;
            }
        }
        return out;
    }

    public static int band(float kmh) {
        for (int i = 0; i < BAND_LIMITS_KMH.length; i++) if (kmh < BAND_LIMITS_KMH[i]) return i;
        return BAND_LIMITS_KMH.length;
    }
}
