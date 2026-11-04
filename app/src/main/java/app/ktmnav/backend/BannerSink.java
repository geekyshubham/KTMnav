package app.ktmnav.backend;

/**
 * Destination for short dash banner texts; Bridge implements it and owns ASCII cleaning and marquee timing.
 */
public interface BannerSink {
    int PRIORITY_INFO = 0;
    int PRIORITY_TRACK = 1;
    int PRIORITY_MESSAGE = 2;
    int PRIORITY_CALL = 3;
    int PRIORITY_ALERT = 4;

    boolean show(String owner, String text, int priority, long holdMs);

    void clear(String owner);
}
