package app.ktmnav.backend.obd;

public interface ObdBannerSink {

    int PRIORITY_INFO = 0;

    boolean show(String owner, String text, int priority, long holdMs);
}
