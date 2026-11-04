package app.ktmnav.backend;

import android.os.Bundle;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text parsing utilities for localized distance, ETA, and road strings.
 * Based on TurnIt NavTexts evidence.
 */
public final class NavTexts {

    private static final String EXIT_SEPARATOR = " · ";
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*(am|pm)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISTANCE = Pattern.compile("\\d+([.,]\\d+)?\\s*(km|mi|m)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEGMENT_SEPARATOR = Pattern.compile("[·•\\-–—|]");

    private NavTexts() {
    }

    public static String[] parseEtaAndRemainingDistance(String str) {
        String[] result = new String[2];
        if (str != null && !str.trim().isEmpty()) {
            for (String segment : SEGMENT_SEPARATOR.split(str)) {
                String trim = segment.trim();
                if (!trim.isEmpty()) {
                    if (result[0] == null) {
                        Matcher matcher = TIME.matcher(trim);
                        if (matcher.find()) {
                            int hours = Integer.parseInt(matcher.group(1));
                            String mins = matcher.group(2);
                            String ampm = matcher.group(3) == null ? "" : matcher.group(3).toLowerCase(Locale.ROOT);
                            if (ampm.equals("pm") && hours < 12) {
                                hours += 12;
                            }
                            if (ampm.equals("am") && hours == 12) {
                                hours = 0;
                            }
                            result[0] = String.format(Locale.ROOT, "%02d:%s", hours, mins);
                        }
                    }
                    if (result[1] == null && DISTANCE.matcher(trim).matches()) {
                        result[1] = trim;
                    }
                }
            }
        }
        return result;
    }

    public static String charSequence(Bundle bundle, String key) {
        if (bundle == null) {
            return null;
        }
        CharSequence cs = bundle.getCharSequence(key);
        return cs != null ? cs.toString() : null;
    }

    public static String blankToNull(String str) {
        if (str == null) {
            return null;
        }
        String trim = str.trim();
        return trim.isEmpty() ? null : trim;
    }

    public static String normalizeSpaces(String str) {
        if (str == null) {
            return null;
        }
        return str.replaceAll("\\p{Zs}+", " ");
    }

    public static String withRoundaboutExit(String road, int exitNumber) {
        if (exitNumber < 1) {
            return road;
        }
        String exitStr = Integer.toString(exitNumber);
        String cleanRoad = blankToNull(road);
        return cleanRoad == null ? exitStr : exitStr + EXIT_SEPARATOR + cleanRoad;
    }
}
