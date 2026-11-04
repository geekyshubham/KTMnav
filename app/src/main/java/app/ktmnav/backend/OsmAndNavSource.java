package app.ktmnav.backend;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for OsmAnd navigation notifications (net.osmand and net.osmand.plus).
 * Based on TurnIt OsmAndNavSource evidence.
 */
public final class OsmAndNavSource implements NavSource {

    public static final String PACKAGE_FREE = "net.osmand";
    public static final String PACKAGE_PLUS = "net.osmand.plus";

    private static final Map<String, TurnIcon> PHRASES = new HashMap<>();
    private static final char SEGMENT_BULLET = 8226; // '•'
    private static final Pattern EXIT_NUMBER = Pattern.compile("\\d+");
    private static final Pattern TRAILING_DISTANCE = Pattern.compile("\\s+\\d+([.,]\\d+)?\\s*(km|mi|m|ft|yd|nmi)$", Pattern.CASE_INSENSITIVE);

    static {
        PHRASES.put("head", TurnIcon.GO_STRAIGHT);
        PHRASES.put("turn left and go", TurnIcon.QUITE_LEFT);
        PHRASES.put("turn right and go", TurnIcon.QUITE_RIGHT);
        PHRASES.put("turn slightly left and go", TurnIcon.LIGHT_LEFT);
        PHRASES.put("turn slightly right and go", TurnIcon.LIGHT_RIGHT);
        PHRASES.put("turn sharply left and go", TurnIcon.HEAVY_LEFT);
        PHRASES.put("turn sharply right and go", TurnIcon.HEAVY_RIGHT);
        PHRASES.put("keep left and go", TurnIcon.KEEP_LEFT);
        PHRASES.put("keep right and go", TurnIcon.KEEP_RIGHT);
        PHRASES.put("make u-turn and go", TurnIcon.UTURN_LEFT);
        PHRASES.put("geradeaus", TurnIcon.GO_STRAIGHT);
        PHRASES.put("links abbiegen, dann", TurnIcon.QUITE_LEFT);
        PHRASES.put("rechts abbiegen, dann", TurnIcon.QUITE_RIGHT);
        PHRASES.put("halb links abbiegen, dann", TurnIcon.LIGHT_LEFT);
        PHRASES.put("halb rechts abbiegen, dann", TurnIcon.LIGHT_RIGHT);
        PHRASES.put("scharf links abbiegen, dann", TurnIcon.HEAVY_LEFT);
        PHRASES.put("scharf rechts abbiegen, dann", TurnIcon.HEAVY_RIGHT);
        PHRASES.put("links halten, dann", TurnIcon.KEEP_LEFT);
        PHRASES.put("rechts halten, dann", TurnIcon.KEEP_RIGHT);
        PHRASES.put("wenden", TurnIcon.UTURN_LEFT);
    }

    private final String packageName;

    public OsmAndNavSource(String packageName) {
        this.packageName = packageName;
    }

    @Override
    public String getPackageName() {
        return packageName;
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
        String text = NavTexts.charSequence(bundle, Notification.EXTRA_BIG_TEXT);
        if (text == null) {
            text = NavTexts.charSequence(bundle, Notification.EXTRA_TEXT);
        }
        NavUpdate update = parseTexts(title, text);
        if (update == null) {
            return null;
        }
        String phrase = turnPhraseOf(NavTexts.normalizeSpaces(title));
        int hash = ArrowHash.of(arrowPixels);
        TurnIcon icon = resolveTurnIcon(phrase, hash);
        int exit = roundaboutExitOf(phrase);
        return update.withArrow(icon, arrowPixels).withRoundaboutExit(exit);
    }

    public static NavUpdate parseTexts(String title, String text) {
        String normTitle = NavTexts.normalizeSpaces(title);
        String normText = NavTexts.normalizeSpaces(text);
        String dist = NavTexts.blankToNull(distanceOf(normTitle));
        String[] etaRemaining = new String[2];
        String road = null;

        if (normText != null) {
            String[] lines = normText.split("\n");
            etaRemaining = NavTexts.parseEtaAndRemainingDistance(lines[lines.length - 1]);
            if (lines.length >= 2) {
                road = extractRoad(lines[0], turnPhraseOf(normTitle));
            }
        }
        if (dist == null && road == null) {
            return null;
        }
        return new NavUpdate(dist, road, etaRemaining[0], etaRemaining[1], null, null);
    }

    public static TurnIcon resolveTurnIcon(String phrase, Integer hash) {
        TurnIcon rab = roundaboutFromPhrase(phrase);
        if (rab != null) {
            return rab;
        }
        if (hash != null) {
            TurnIcon icon = OsmAndArrows.lookup(hash);
            if (icon != null) {
                return icon;
            }
        }
        if (phrase == null) {
            return null;
        }
        return PHRASES.get(phrase.trim().toLowerCase(Locale.ROOT));
    }

    private static String distanceOf(String str) {
        if (str == null) {
            return null;
        }
        int idx = str.indexOf(SEGMENT_BULLET);
        return idx < 0 ? str : str.substring(0, idx);
    }

    public static String turnPhraseOf(String str) {
        if (str == null) {
            return null;
        }
        int idx = str.indexOf(SEGMENT_BULLET);
        if (idx >= 0) {
            return NavTexts.blankToNull(str.substring(idx + 1));
        }
        return null;
    }

    private static String extractRoad(String textLine, String turnPhrase) {
        String clean = textLine.trim();
        if (clean.indexOf(SEGMENT_BULLET) >= 0) {
            return null;
        }
        if (turnPhrase != null) {
            String tp = turnPhrase.trim();
            if (clean.regionMatches(true, 0, tp, 0, tp.length())) {
                clean = clean.substring(tp.length()).trim();
            }
        }
        return NavTexts.blankToNull(TRAILING_DISTANCE.matcher(clean).replaceFirst(""));
    }

    private static TurnIcon roundaboutFromPhrase(String phrase) {
        int exit = roundaboutExitOf(phrase);
        if (exit < 1) {
            return null;
        }
        return RoundaboutGlyph.rightHandForExit(exit);
    }

    public static int roundaboutExitOf(String str) {
        if (str == null) {
            return 0;
        }
        Matcher matcher = EXIT_NUMBER.matcher(str);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group());
        } catch (NumberFormatException unused) {
            return 0;
        }
    }
}
