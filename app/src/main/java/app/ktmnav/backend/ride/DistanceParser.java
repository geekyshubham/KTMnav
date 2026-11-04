package app.ktmnav.backend.ride;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DistanceParser {
    public static final int UNKNOWN = -1;

    private static final Pattern DISTANCE = Pattern.compile(
            "(\\d[\\d.,\\u00A0\\u202F\\u2009']*\\d|\\d)[\\s\\u00A0\\u202F\\u2009]*"
                    + "(kilometres|kilometers|km|miles|mile|mi|metres|meters|m|feet|ft|yards|yd|км|м)(?!\\p{L})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private DistanceParser() {
    }

    public static int metres(String text) {
        if (text == null) return UNKNOWN;
        Matcher m = DISTANCE.matcher(text);
        if (!m.find()) return UNKNOWN;
        String unit = m.group(2).toLowerCase(Locale.ROOT);
        double value = number(m.group(1), isSmallUnit(unit));
        if (Double.isNaN(value)) return UNKNOWN;
        double metres;
        switch (unit) {
            case "km": case "kilometres": case "kilometers": case "км": metres = value * 1000.0; break;
            case "mi": case "mile": case "miles": metres = value * 1609.344; break;
            case "ft": case "feet": metres = value * 0.3048; break;
            case "yd": case "yards": metres = value * 0.9144; break;
            default: metres = value;
        }
        if (metres > Integer.MAX_VALUE) return UNKNOWN;
        return (int) Math.round(metres);
    }

    private static boolean isSmallUnit(String unit) {
        switch (unit) {
            case "m": case "metres": case "meters": case "м": case "ft": case "feet": case "yd": case "yards": return true;
            default: return false;
        }
    }

    static double number(String raw, boolean smallUnit) {
        String s = raw.replace(" ", "").replace(" ", "").replace(" ", "").replace("'", "");
        int dots = count(s, '.');
        int commas = count(s, ',');
        if (dots > 0 && commas > 0) {
            char decimal = s.lastIndexOf('.') > s.lastIndexOf(',') ? '.' : ',';
            char group = decimal == '.' ? ',' : '.';
            s = s.replace(String.valueOf(group), "").replace(decimal, '.');
        } else if (dots + commas > 1) {
            s = s.replace(".", "").replace(",", "");
        } else if (dots + commas == 1) {
            char sep = dots == 1 ? '.' : ',';
            int at = s.indexOf(sep);
            boolean grouping = smallUnit && s.length() - at - 1 == 3 && at <= 3;
            s = grouping ? s.replace(String.valueOf(sep), "") : s.replace(sep, '.');
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
