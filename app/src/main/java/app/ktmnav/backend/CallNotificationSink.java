package app.ktmnav.backend;

/**
 * Receives incoming-call notifications detected from dialer apps by NavListener.
 */
public interface CallNotificationSink {

    void onIncomingCallNotification(String callerName);

    default void onCallNotificationRemoved() {
    }
}
