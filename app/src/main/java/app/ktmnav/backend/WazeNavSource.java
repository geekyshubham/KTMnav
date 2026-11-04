package app.ktmnav.backend;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text parser for Waze (com.waze) turn-by-turn notifications. Waze ships no maneuver image that a
 * hash table could match, so the next turn is read from the English instruction text the way the
 * RideConnect app does: keyword lookup for the arrow, the first distance for the countdown, an
 * arrival time or minutes-to-go for the ETA, and the notification progress bar for what remains.
 */
public final class WazeNavSource implements NavSource {
    public static final String PACKAGE_NAME = "com.waze";

    private static final Pattern METRES = Pattern.compile("(\\d+)\\s*m(?!k|i)(?:\\b|eter)");
    private static final Pattern KM = Pattern.compile("([\\d.]+)\\s*km\\b");
    private static final Pattern MILES = Pattern.compile("([\\d.]+)\\s*mi(?:\\b|les?)");
    private static final Pattern FEET = Pattern.compile("(\\d+)\\s*ft(?:\\b|eet)");
    private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*min");
    private static final Pattern HOURS = Pattern.compile("(\\d+)\\s*h(?:r|our)?\\b");
    private static final Pattern ARRIVE = Pattern.compile("(?:arrive|arrival|eta)\\D{0,12}(\\d{1,2}):(\\d{2})\\s*(am|pm)?");
    private static final Pattern EXIT_NUMBER = Pattern.compile("(\\d+)(?:st|nd|rd|th)\\s+exit");
    private static final Pattern DISTANCE_PREFIX = Pattern.compile("^(?:in\\s+)?\\d+(?:\\.\\d+)?\\s*(?:m|km|mi|ft|miles|feet)\\s*[·•,\\-–—:]?\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISTANCE_ONLY = Pattern.compile("^\\d+(?:\\.\\d+)?\\s*(?:m|km|mi|ft|miles|feet)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HAS_DIGIT = Pattern.compile(".*\\d.*");
    private static final Pattern EXIT_ONTO = Pattern.compile("exit(?:\\s+and\\s+stay|\\s+and\\s+continue)?\\s+(?:onto|on|to)\\s+(.+)", Pattern.CASE_INSENSITIVE);
    private static final String[] INSTRUCTION_WORDS = {"turn", "head", "exit", "roundabout", "merge", "keep", "continue",
            "destination", "arriving", "arrived", "road", "street", "ave", "lane", "way", "blvd", "dr", "hwy", "highway",
            "stop", "ramp", "fork", "u-turn", "straight", "left", "right"};

    @Override
    public String getPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public boolean isGuidanceRemoval(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return false;
        }
        return Notification.CATEGORY_NAVIGATION.equals(sbn.getNotification().category) || sbn.isOngoing();
    }

    @Override
    public NavUpdate parse(Context context, StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null) {
            return null;
        }
        if (!Notification.CATEGORY_NAVIGATION.equals(notification.category) && !sbn.isOngoing()) {
            return null;
        }
        Bundle extras = notification.extras;
        List<String> texts = new ArrayList<>(collectTexts(extras, notification.tickerText));
        int progress = extras != null ? extras.getInt(Notification.EXTRA_PROGRESS, 0) : 0;
        int progressMax = extras != null ? extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) : 0;
        return parseTexts(texts, progress, progressMax, System.currentTimeMillis());
    }

    static Set<String> collectTexts(Bundle extras, CharSequence ticker) {
        Set<String> texts = new LinkedHashSet<>();
        if (extras != null) {
            for (String key : new String[]{Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG, Notification.EXTRA_TEXT,
                    Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_SUMMARY_TEXT}) {
                add(texts, NavTexts.charSequence(extras, key));
            }
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) {
                for (CharSequence line : lines) {
                    add(texts, line != null ? line.toString() : null);
                }
            }
        }
        add(texts, ticker != null ? ticker.toString() : null);
        return texts;
    }

    private static void add(Set<String> texts, String value) {
        String clean = NavTexts.blankToNull(NavTexts.normalizeSpaces(value));
        if (clean != null) {
            texts.add(clean);
        }
    }

    /**
     * Pure parser: {@code texts} are the notification strings in display order, {@code progress}/{@code progressMax}
     * the notification progress bar (remaining route when Waze sets it) and {@code nowMs} the wall clock used to
     * turn "12 min" into an arrival time.
     */
    public static NavUpdate parseTexts(List<String> texts, int progress, int progressMax, long nowMs) {
        if (texts == null || texts.isEmpty()) {
            return null;
        }
        String joined = String.join(" ", texts).toLowerCase(Locale.ROOT).replace(' ', ' ').replaceAll("\\s+", " ");
        String distanceText = firstDistance(joined);
        String instruction = pickInstruction(texts);
        if (instruction == null && distanceText == null) {
            return null;
        }
        String lower = instruction != null ? instruction.toLowerCase(Locale.ROOT) : "";
        TurnIcon icon = iconFor(lower);
        int exit = 0;
        if (lower.contains("roundabout")) {
            Matcher m = EXIT_NUMBER.matcher(lower);
            exit = m.find() ? Integer.parseInt(m.group(1)) : 1;
        }
        String road = cleanInstruction(instruction, icon, exit);
        String eta = etaFor(joined, nowMs);
        String remaining = remainingFor(progress, progressMax);
        NavUpdate update = new NavUpdate(distanceText, road, eta, remaining, icon, null);
        return exit > 0 ? update.withRoundaboutExit(exit) : update;
    }

    static String firstDistance(String joined) {
        Matcher m = METRES.matcher(joined);
        if (m.find()) return m.group(1) + " m";
        m = KM.matcher(joined);
        if (m.find()) return trimNumber(m.group(1)) + " km";
        m = MILES.matcher(joined);
        if (m.find()) return trimNumber(m.group(1)) + " mi";
        m = FEET.matcher(joined);
        if (m.find()) return m.group(1) + " ft";
        return null;
    }

    private static String trimNumber(String n) {
        return n.endsWith(".") ? n.substring(0, n.length() - 1) : n;
    }

    static String pickInstruction(List<String> texts) {
        for (String t : texts) {
            String lower = t.toLowerCase(Locale.ROOT);
            for (String word : INSTRUCTION_WORDS) {
                if (lower.contains(word)) return t;
            }
        }
        for (String t : texts) {
            if (!t.isEmpty() && !HAS_DIGIT.matcher(t).matches() && !t.toLowerCase(Locale.ROOT).contains("waze")) return t;
        }
        return texts.get(0);
    }

    static TurnIcon iconFor(String lower) {
        if (lower.isEmpty()) return null;
        if (lower.contains("u-turn") || lower.contains("u turn")) {
            return lower.contains("right") ? TurnIcon.UTURN_RIGHT : TurnIcon.UTURN_LEFT;
        }
        if (lower.contains("roundabout")) {
            Matcher m = EXIT_NUMBER.matcher(lower);
            return RoundaboutGlyph.rightHandForExit(m.find() ? Integer.parseInt(m.group(1)) : 1);
        }
        if (lower.contains("arriving") || lower.contains("arrived") || lower.contains("destination")) return TurnIcon.END;
        if (lower.contains("keep") || lower.contains("fork") || lower.contains("stay")) {
            if (lower.contains("left")) return TurnIcon.KEEP_LEFT;
            if (lower.contains("right")) return TurnIcon.KEEP_RIGHT;
        }
        if (lower.contains("sharp left")) return TurnIcon.HEAVY_LEFT;
        if (lower.contains("sharp right")) return TurnIcon.HEAVY_RIGHT;
        if (lower.contains("slight left") || lower.contains("bear left")) return TurnIcon.LIGHT_LEFT;
        if (lower.contains("slight right") || lower.contains("bear right")) return TurnIcon.LIGHT_RIGHT;
        if (lower.contains("left")) return TurnIcon.QUITE_LEFT;
        if (lower.contains("right")) return TurnIcon.QUITE_RIGHT;
        if (lower.contains("straight") || lower.contains("continue")) return TurnIcon.GO_STRAIGHT;
        if (lower.contains("head")) return TurnIcon.HEAD_TO;
        if (lower.contains("ferry")) return TurnIcon.FERRY;
        if (lower.contains("merge") || lower.contains("ramp")) return TurnIcon.CHANGE_LINE;
        return null;
    }

    static String cleanInstruction(String instruction, TurnIcon icon, int exit) {
        if (instruction == null) return null;
        String road = DISTANCE_PREFIX.matcher(instruction).replaceFirst("").trim();
        if (exit > 0) {
            Matcher onto = EXIT_ONTO.matcher(road);
            String target = onto.find() ? onto.group(1).trim().replaceAll("\\.$", "") : null;
            road = target != null && !target.isEmpty() ? target : "Roundabout";
        }
        if (road.isEmpty() || DISTANCE_ONLY.matcher(road).matches()) {
            if (icon == TurnIcon.END) return "Destination ahead";
            if (exit > 0) return "Roundabout ahead";
            if (icon != null && icon != TurnIcon.GO_STRAIGHT) return "Upcoming turn";
            return "Continue on route";
        }
        return road;
    }

    static String etaFor(String joined, long nowMs) {
        Matcher arrive = ARRIVE.matcher(joined);
        if (arrive.find()) {
            int hours = Integer.parseInt(arrive.group(1));
            String ampm = arrive.group(3);
            if ("pm".equals(ampm) && hours < 12) hours += 12;
            if ("am".equals(ampm) && hours == 12) hours = 0;
            if (hours <= 23) return String.format(Locale.ROOT, "%02d:%s", hours, arrive.group(2));
        }
        Matcher min = MINUTES.matcher(joined);
        Matcher hr = HOURS.matcher(joined);
        int minutes = (min.find() ? Integer.parseInt(min.group(1)) : 0) + (hr.find() ? Integer.parseInt(hr.group(1)) * 60 : 0);
        if (minutes <= 0) return null;
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(nowMs + minutes * 60_000L);
        return String.format(Locale.ROOT, "%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    static String remainingFor(int progress, int progressMax) {
        if (progressMax <= 0 || progress < 0 || progress > progressMax) return null;
        int metres = progressMax - progress;
        if (metres >= 1000) return String.format(Locale.ROOT, "%.1f km", metres / 1000.0);
        return metres + " m";
    }
}
