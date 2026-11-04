package app.ktmnav.backend.ride;

public interface RideBannerSink {
    int PRIORITY_ALERT = 4;

    boolean show(String owner, String text, int priority, long holdMs);

    void clear(String owner);
}
