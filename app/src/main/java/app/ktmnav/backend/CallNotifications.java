package app.ktmnav.backend;

import android.app.Notification;
import android.app.Person;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Detects ringing-call notifications posted by dialer apps and forwards the caller name once per ring.
 */
public final class CallNotifications {
    private static final String TAG = "Calls";

    public static final long REPEAT_WINDOW_MS = 5000L;
    static final String EXTRA_CALL_TYPE = "android.callType";
    static final int CALL_TYPE_INCOMING = 1;
    public static final Set<String> DIALER_PACKAGES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.google.android.dialer",
            "com.android.dialer",
            "com.android.incallui",
            "com.android.server.telecom",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui",
            "com.oneplus.dialer"
    )));

    private String lastKey;
    private String lastCaller;
    private long lastAt;

    static boolean isDialer(String pkg) {
        return pkg != null && DIALER_PACKAGES.contains(pkg);
    }

    static boolean isIncomingCall(String pkg, String category, int callType, boolean hasFullScreenIntent) {
        if (!isDialer(pkg) || !Notification.CATEGORY_CALL.equals(category)) {
            return false;
        }
        if (callType != 0) {
            return callType == CALL_TYPE_INCOMING;
        }
        return hasFullScreenIntent;
    }

    static boolean isOngoingCall(String pkg, String category, int callType, boolean hasFullScreenIntent) {
        return isDialer(pkg) && Notification.CATEGORY_CALL.equals(category)
                && !isIncomingCall(pkg, category, callType, hasFullScreenIntent);
    }

    /** True when the dialer replaced the ringing notification for this key with an ongoing-call one. */
    synchronized boolean onAnsweredInPlace(String key) {
        return onRemoved(key);
    }

    synchronized boolean shouldForward(String key, String caller, long now) {
        if (Objects.equals(key, lastKey) && Objects.equals(caller, lastCaller) && now - lastAt < REPEAT_WINDOW_MS) {
            return false;
        }
        lastKey = key;
        lastCaller = caller;
        lastAt = now;
        return true;
    }

    synchronized boolean onRemoved(String key) {
        if (key != null && key.equals(lastKey)) {
            lastKey = null;
            lastCaller = null;
            return true;
        }
        return false;
    }

    public boolean onPosted(StatusBarNotification sbn, CallNotificationSink sink, long now) {
        if (sbn == null || sbn.getNotification() == null || sink == null || !isDialer(sbn.getPackageName())) {
            return false;
        }
        Notification n = sbn.getNotification();
        Bundle extras = n.extras;
        int callType = extras != null ? extras.getInt(EXTRA_CALL_TYPE, 0) : 0;
        boolean fullScreen = n.fullScreenIntent != null;
        if (!isIncomingCall(sbn.getPackageName(), n.category, callType, fullScreen)) {
            if (isOngoingCall(sbn.getPackageName(), n.category, callType, fullScreen) && onAnsweredInPlace(sbn.getKey())) {
                AppLog.d(TAG, "Call notification became ongoing; clearing the caller banner");
                sink.onCallNotificationRemoved();
            }
            return false;
        }
        String caller = callerName(extras);
        if (!shouldForward(sbn.getKey(), caller, now)) {
            return false;
        }
        AppLog.d(TAG, "Incoming call notification from " + sbn.getPackageName());
        sink.onIncomingCallNotification(caller);
        return true;
    }

    public void onRemoved(StatusBarNotification sbn, CallNotificationSink sink) {
        if (sbn != null && sink != null && onRemoved(sbn.getKey())) {
            sink.onCallNotificationRemoved();
        }
    }

    @SuppressWarnings("deprecation")
    private static String callerName(Bundle extras) {
        if (extras == null) {
            return null;
        }
        Parcelable person = extras.getParcelable(Notification.EXTRA_CALL_PERSON);
        if (person instanceof Person && ((Person) person).getName() != null) {
            String name = NavTexts.blankToNull(((Person) person).getName().toString());
            if (name != null) {
                return name;
            }
        }
        return NavTexts.blankToNull(NavTexts.charSequence(extras, Notification.EXTRA_TITLE));
    }
}
