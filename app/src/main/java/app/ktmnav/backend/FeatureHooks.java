package app.ktmnav.backend;

/**
 * Registry Bridge fills at startup so notification features reach the dash without compile-time coupling.
 */
public final class FeatureHooks {

    private static volatile BannerSink bannerSink;
    private static volatile CallNotificationSink callNotificationSink;

    private FeatureHooks() {
    }

    public static void setBannerSink(BannerSink sink) {
        bannerSink = sink;
    }

    public static BannerSink getBannerSink() {
        return bannerSink;
    }

    public static void setCallNotificationSink(CallNotificationSink sink) {
        callNotificationSink = sink;
    }

    public static CallNotificationSink getCallNotificationSink() {
        return callNotificationSink;
    }

    static void reset() {
        bannerSink = null;
        callNotificationSink = null;
    }
}
