package app.ktmnav.backend.ride;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Waypoint {
    private static final String NUM = "([-+]?\\d{1,3}(?:\\.\\d+)?)";
    private static final Pattern PLACE = Pattern.compile("!3d" + NUM + "!4d" + NUM);
    private static final Pattern QUERY = Pattern.compile(
            "[?&](?:q|query|ll|sll|daddr|destination|center)=(?:loc:)?" + NUM + "\\s*,\\s*" + NUM, Pattern.CASE_INSENSITIVE);
    private static final Pattern AT = Pattern.compile("@" + NUM + "\\s*,\\s*" + NUM);
    private static final Pattern GEO = Pattern.compile("^geo:" + NUM + "\\s*,\\s*" + NUM, Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE = Pattern.compile("^\\s*" + NUM + "\\s*[,;\\s]\\s*" + NUM + "\\s*$");

    public final double lat;
    public final double lon;
    public final String label;

    public Waypoint(double lat, double lon, String label) {
        this.lat = lat;
        this.lon = lon;
        this.label = label == null || label.trim().isEmpty() ? null : label.trim();
    }

    public static boolean isValid(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon) && Math.abs(lat) <= 90.0 && Math.abs(lon) <= 180.0;
    }

    public static Waypoint parse(String input) {
        return parse(input, null);
    }

    public static Waypoint parse(String input, String label) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty() || s.length() > 4096) return null;
        String decoded = decode(s);
        for (Pattern p : new Pattern[]{PLACE, QUERY, AT, GEO, BARE}) {
            Matcher m = p.matcher(decoded);
            if (m.find()) {
                double lat = Double.parseDouble(m.group(1));
                double lon = Double.parseDouble(m.group(2));
                if (isValid(lat, lon)) return new Waypoint(lat, lon, label);
            }
        }
        return null;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }

    public String toPref() {
        return String.format(Locale.ROOT, "%.6f,%.6f", lat, lon);
    }

    public String navigationUri() {
        return "google.navigation:q=" + toPref();
    }

    public String geoUri() {
        String uri = "geo:" + toPref() + "?q=" + toPref();
        if (label == null) return uri;
        try {
            return uri + "(" + URLEncoder.encode(label, StandardCharsets.UTF_8.name()).replace("+", "%20") + ")";
        } catch (UnsupportedEncodingException e) {
            return uri;
        }
    }
}
