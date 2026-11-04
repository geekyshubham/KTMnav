package app.ktmnav.backend;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

/**
 * Parser for Kurviger motorcycle navigation notifications (com.kurviger.app).
 * Based on TurnIt KurvigerNavSource evidence.
 */
public final class KurvigerNavSource implements NavSource {

    public static final String PACKAGE_NAME = "com.kurviger.app";

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
        int hash = ArrowHash.of(arrowPixels);
        if (KurvigerArrows.isNonGuidance(hash)) {
            return null;
        }
        Bundle bundle = notification.extras;
        String title = NavTexts.charSequence(bundle, Notification.EXTRA_TITLE);
        String text = NavTexts.charSequence(bundle, Notification.EXTRA_TEXT);
        String subText = NavTexts.charSequence(bundle, Notification.EXTRA_SUB_TEXT);
        NavUpdate update = parseTexts(title, text, subText);
        if (update == null) {
            return null;
        }
        return update.withArrow(KurvigerArrows.lookup(hash), arrowPixels);
    }

    public static NavUpdate parseTexts(String title, String text, String subText) {
        String dist = NavTexts.blankToNull(NavTexts.normalizeSpaces(title));
        String road = NavTexts.blankToNull(NavTexts.normalizeSpaces(text));
        if (dist == null && road == null) {
            return null;
        }
        String[] etaRemaining = NavTexts.parseEtaAndRemainingDistance(NavTexts.normalizeSpaces(subText));
        return new NavUpdate(dist, road, etaRemaining[0], etaRemaining[1], null, null);
    }
}
