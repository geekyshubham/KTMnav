package app.ktmnav.backend;

import android.app.Notification;
import android.content.Context;
import android.service.notification.StatusBarNotification;

/**
 * Interface implemented by navigation notification parser sources.
 */
public interface NavSource {

    String getPackageName();

    NavUpdate parse(Context context, StatusBarNotification sbn);

    default boolean isGuidanceRemoval(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return false;
        }
        return Notification.CATEGORY_NAVIGATION.equals(sbn.getNotification().category);
    }
}
