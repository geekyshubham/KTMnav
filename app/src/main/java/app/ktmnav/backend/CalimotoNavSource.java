package app.ktmnav.backend;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for Calimoto motorcycle navigation notifications (com.calimoto.calimoto).
 * Based on TurnIt CalimotoNavSource evidence.
 */
public final class CalimotoNavSource implements NavSource {

    public static final String PACKAGE_NAME = "com.calimoto.calimoto";
    public static final String NAVIGATION_CHANNEL_ID = "com.calimoto.calimoto.notification.Navigation";

    private static final Pattern TITLE = Pattern.compile("^in\\s+(\\d+(?:[.,]\\d+)?|one|einem|eine)\\s+(\\p{L}+)\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROUNDABOUT_EN = Pattern.compile("^(?:at|in) the roundabout, take exit (\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROUNDABOUT_DE = Pattern.compile("^im kreisverkehr ausfahrt (\\d+)", Pattern.CASE_INSENSITIVE);

    private static final List<String> JOINT_WORDS = Arrays.asList("onto", "toward", "towards", "auf", "richtung");
    private static final List<String> TRAILING_VERBS = Arrays.asList("nehmen", "auffahren");

    private static final List<Map.Entry<String, TurnIcon>> PHRASE_PREFIXES = Arrays.asList(
            entry("turn left and merge", TurnIcon.ENTER_HIGHWAY_LEFT_LANE),
            entry("turn right and merge", TurnIcon.ENTER_HIGHWAY_RIGHT_LANE),
            entry("take the exit", TurnIcon.LEAVE_HIGHWAY_RIGHT_LANE),
            entry("keep left", TurnIcon.KEEP_LEFT),
            entry("keep right", TurnIcon.KEEP_RIGHT),
            entry("stay left", TurnIcon.KEEP_LEFT),
            entry("stay right", TurnIcon.KEEP_RIGHT),
            entry("make a slight left", TurnIcon.LIGHT_LEFT),
            entry("make a slight right", TurnIcon.LIGHT_RIGHT),
            entry("make a sharp left", TurnIcon.HEAVY_LEFT),
            entry("make a sharp right", TurnIcon.HEAVY_RIGHT),
            entry("turn slight left", TurnIcon.LIGHT_LEFT),
            entry("turn slight right", TurnIcon.LIGHT_RIGHT),
            entry("turn sharp left", TurnIcon.HEAVY_LEFT),
            entry("turn sharp right", TurnIcon.HEAVY_RIGHT),
            entry("turn left", TurnIcon.QUITE_LEFT),
            entry("turn right", TurnIcon.QUITE_RIGHT),
            entry("continue", TurnIcon.GO_STRAIGHT),
            entry("follow the road", TurnIcon.GO_STRAIGHT),
            entry("please make a u-turn", TurnIcon.UTURN_LEFT),
            entry("you've reached your destination", TurnIcon.END),
            entry("links abbiegen und auf", TurnIcon.ENTER_HIGHWAY_LEFT_LANE),
            entry("rechts abbiegen und auf", TurnIcon.ENTER_HIGHWAY_RIGHT_LANE),
            entry("ausfahrt nehmen", TurnIcon.LEAVE_HIGHWAY_RIGHT_LANE),
            entry("links halten", TurnIcon.KEEP_LEFT),
            entry("rechts halten", TurnIcon.KEEP_RIGHT),
            entry("leicht links abbiegen", TurnIcon.LIGHT_LEFT),
            entry("leicht rechts abbiegen", TurnIcon.LIGHT_RIGHT),
            entry("scharf links abbiegen", TurnIcon.HEAVY_LEFT),
            entry("scharf rechts abbiegen", TurnIcon.HEAVY_RIGHT),
            entry("links abbiegen", TurnIcon.QUITE_LEFT),
            entry("rechts abbiegen", TurnIcon.QUITE_RIGHT),
            entry("geradeaus", TurnIcon.GO_STRAIGHT),
            entry("weiter auf", TurnIcon.GO_STRAIGHT),
            entry("dem straßenverlauf", TurnIcon.GO_STRAIGHT),
            entry("bitte wenden", TurnIcon.UTURN_LEFT),
            entry("ziel erreicht", TurnIcon.END)
    );

    private static final Map<String, String> UNIT_ABBREVIATIONS = new HashMap<>();

    static {
        for (String u : new String[]{"kilometer", "kilometers", "kilometre", "kilometres"}) {
            UNIT_ABBREVIATIONS.put(u, "km");
        }
        for (String u : new String[]{"meter", "meters", "metern", "metre", "metres"}) {
            UNIT_ABBREVIATIONS.put(u, "m");
        }
        for (String u : new String[]{"mile", "miles", "meile", "meilen"}) {
            UNIT_ABBREVIATIONS.put(u, "mi");
        }
        for (String u : new String[]{"foot", "feet", "fuß"}) {
            UNIT_ABBREVIATIONS.put(u, "ft");
        }
    }

    private static Map.Entry<String, TurnIcon> entry(String phrase, TurnIcon icon) {
        return new AbstractMap.SimpleImmutableEntry<>(phrase, icon);
    }

    @Override
    public String getPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public NavUpdate parse(Context context, StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (!NAVIGATION_CHANNEL_ID.equals(notification.getChannelId())) {
            return null;
        }
        Bundle bundle = notification.extras;
        String title = NavTexts.charSequence(bundle, Notification.EXTRA_TITLE);
        String text = NavTexts.charSequence(bundle, Notification.EXTRA_TEXT);
        return parseTexts(title, text);
    }

    @Override
    public boolean isGuidanceRemoval(StatusBarNotification sbn) {
        return sbn != null && sbn.getNotification() != null
                && NAVIGATION_CHANNEL_ID.equals(sbn.getNotification().getChannelId());
    }

    public static NavUpdate parseTexts(String title, String text) {
        String cleanTitle = NavTexts.blankToNull(NavTexts.normalizeSpaces(title));
        String cleanText = NavTexts.blankToNull(NavTexts.normalizeSpaces(text));
        if (cleanTitle == null && cleanText == null) {
            return null;
        }
        String normDist = null;
        if (cleanTitle != null) {
            Matcher matcher = TITLE.matcher(cleanTitle);
            if (matcher.matches()) {
                normDist = normalizeDistance(matcher.group(1), matcher.group(2));
                cleanTitle = matcher.group(3);
            }
        }
        TurnIcon icon = resolveTurnIcon(cleanTitle);
        String road = extractRoad(cleanText, cleanTitle);
        int exit = roundaboutExitOf(cleanTitle);

        if (normDist == null && road == null && icon == null) {
            return null;
        }
        return new NavUpdate(normDist, road, null, null, icon, null).withRoundaboutExit(exit);
    }

    public static TurnIcon resolveTurnIcon(String phrase) {
        if (phrase == null) {
            return null;
        }
        String lower = phrase.trim().toLowerCase(Locale.ROOT);
        int exit = roundaboutExitOf(lower);
        if (exit >= 1) {
            return RoundaboutGlyph.rightHandForExit(exit);
        }
        for (Map.Entry<String, TurnIcon> entry : PHRASE_PREFIXES) {
            if (lower.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static int roundaboutExitOf(String str) {
        if (str == null) {
            return 0;
        }
        String lower = str.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = ROUNDABOUT_EN.matcher(lower);
        if (!matcher.find()) {
            matcher = ROUNDABOUT_DE.matcher(lower);
            if (!matcher.find()) {
                return 0;
            }
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException unused) {
            return 0;
        }
    }

    private static String normalizeDistance(String numStr, String unitStr) {
        if (!Character.isDigit(numStr.charAt(0))) {
            numStr = "1";
        }
        String abbrev = UNIT_ABBREVIATIONS.get(unitStr.toLowerCase(Locale.ROOT));
        if (abbrev != null) {
            unitStr = abbrev;
        }
        return numStr + " " + unitStr;
    }

    private static String extractRoad(String text, String phrase) {
        if (text == null) {
            return null;
        }
        if (phrase != null) {
            String[] textWords = text.trim().split(" ");
            String[] phraseWords = phrase.trim().split(" ");
            int common = 0;
            while (common < textWords.length && common < phraseWords.length
                    && textWords[common].equalsIgnoreCase(phraseWords[common])) {
                common++;
            }
            if (common > 0) {
                int end = textWords.length;
                String lastWord = phraseWords[phraseWords.length - 1].toLowerCase(Locale.ROOT);
                if (end > common && TRAILING_VERBS.contains(lastWord)
                        && textWords[end - 1].equalsIgnoreCase(lastWord)) {
                    end--;
                }
                if (common < end && JOINT_WORDS.contains(textWords[common].toLowerCase(Locale.ROOT))) {
                    common++;
                }
                if (common >= end) {
                    return null;
                }
                return NavTexts.blankToNull(String.join(" ", Arrays.asList(textWords).subList(common, end)));
            }
        }
        return text;
    }
}
