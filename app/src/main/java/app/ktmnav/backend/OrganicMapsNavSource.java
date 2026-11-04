package app.ktmnav.backend;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

/**
 * Parser for Organic Maps navigation notifications (app.organicmaps).
 * Based on TurnIt OrganicMapsNavSource evidence.
 */
public final class OrganicMapsNavSource implements NavSource {

    public static final String PACKAGE_NAME = "app.organicmaps";

    @Override
    public String getPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public NavUpdate parse(Context context, StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (!Notification.CATEGORY_NAVIGATION.equals(notification.category)) {
            return null;
        }
        int[] arrowPixels = ArrowIcons.loadArrowPixels(context, sbn);
        if (arrowPixels == null) {
            return null;
        }
        Bundle bundle = notification.extras;
        String title = NavTexts.charSequence(bundle, Notification.EXTRA_TITLE);
        String text = NavTexts.charSequence(bundle, Notification.EXTRA_TEXT);
        NavUpdate update = parseTexts(title, text);
        if (update == null) {
            return null;
        }
        int hash = ArrowHash.of(arrowPixels);
        TurnIcon icon = OrganicMapsArrows.lookup(hash);
        int exit = OrganicMapsArrows.exitFor(hash);
        return update.withArrow(icon, arrowPixels).withRoundaboutExit(exit);
    }

    public static NavUpdate parseTexts(String title, String text) {
        String dist = NavTexts.blankToNull(NavTexts.normalizeSpaces(title));
        String road = NavTexts.blankToNull(NavTexts.normalizeSpaces(text));
        if (dist == null && road == null) {
            return null;
        }
        return new NavUpdate(dist, road, null, null, null, null);
    }
}
