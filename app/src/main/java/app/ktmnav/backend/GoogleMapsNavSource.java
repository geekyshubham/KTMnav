package app.ktmnav.backend;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

/**
 * Parser for Google Maps turn-by-turn navigation notifications.
 */
public final class GoogleMapsNavSource implements NavSource {
    public static final String PACKAGE_NAME = "com.google.android.apps.maps";

    @Override
    public String getPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public NavUpdate parse(Context context, StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null) {
            return null;
        }

        // Navigation notifications usually have CATEGORY_NAVIGATION
        if (!Notification.CATEGORY_NAVIGATION.equals(notification.category)) {
            return null;
        }

        int[] arrowPixels = ArrowIcons.loadArrowPixels(context, sbn);
        if (arrowPixels == null) {
            return null;
        }

        Bundle extras = notification.extras;
        if (extras == null) {
            return null;
        }

        String title = NavTexts.charSequence(extras, Notification.EXTRA_TITLE);
        String text = NavTexts.charSequence(extras, Notification.EXTRA_TEXT);
        NavUpdate texts = parseTexts(title, text, NavTexts.charSequence(extras, Notification.EXTRA_SUB_TEXT));

        if (texts == null) {
            return null;
        }

        ManeuverText.Result phrase = ManeuverText.parse(title, text, NavTexts.charSequence(extras, Notification.EXTRA_BIG_TEXT));
        IconCalibrationStore calibration = IconCalibrationStore.get(context);
        TurnIcon calibrated = phrase != null && phrase.exact ? null : calibration.lookup(PACKAGE_NAME, arrowPixels);
        ManeuverResolver.Resolution resolution = ManeuverResolver.resolve(phrase, calibrated,
                () -> ManeuverClassifier.classify(context, arrowPixels));
        if (resolution.source != ManeuverResolver.Source.TEXT && resolution.source != ManeuverResolver.Source.CALIBRATION) {
            calibration.noteCapture(PACKAGE_NAME, arrowPixels, resolution.icon, resolution.source, System.currentTimeMillis());
        }
        return texts.withArrow(RoundaboutGlyph.toRightHand(resolution.icon), arrowPixels);
    }

    static NavUpdate parseTexts(String title, String text, String subText) {
        String cleanTitle = NavTexts.blankToNull(NavTexts.normalizeSpaces(title));
        String cleanText = NavTexts.blankToNull(NavTexts.normalizeSpaces(text));

        if (cleanTitle == null && cleanText == null) {
            return null;
        }

        String cleanSubText = NavTexts.normalizeSpaces(subText);
        String[] etaAndDist = parseEtaAndRemainingDistance(cleanSubText);

        return new NavUpdate(cleanTitle, cleanText, etaAndDist[0], etaAndDist[1], null, null);
    }

    static String[] parseEtaAndRemainingDistance(String subText) {
        return NavTexts.parseEtaAndRemainingDistance(subText);
    }
}
