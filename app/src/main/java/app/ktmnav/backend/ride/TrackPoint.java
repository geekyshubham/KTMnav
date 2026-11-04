package app.ktmnav.backend.ride;

public final class TrackPoint {
    public static final long NO_TIME = Long.MIN_VALUE;

    public final double lat;
    public final double lon;
    public final double ele;
    public final long timeMs;
    public final float speedKmh;

    public TrackPoint(double lat, double lon, double ele, long timeMs, float speedKmh) {
        this.lat = lat;
        this.lon = lon;
        this.ele = ele;
        this.timeMs = timeMs;
        this.speedKmh = speedKmh;
    }

    public boolean hasEle() {
        return !Double.isNaN(ele);
    }

    public boolean hasTime() {
        return timeMs != NO_TIME;
    }

    public boolean hasSpeed() {
        return !Float.isNaN(speedKmh);
    }
}
