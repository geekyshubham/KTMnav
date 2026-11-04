package app.ktmnav.backend;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text rules for KTM dashboards, which render only printable ASCII (0x20..0x7E).
 * asciiClean: NFD-normalise, drop combining marks, transliterate a few letters that have no
 * decomposition (for example "ß" becomes "ss"), map every other character outside 0x20..0x7E to a
 * space, collapse whitespace runs and trim. Callers apply it before any length limit or ellipsis.
 */
public final class DashText {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern CLOCK = Pattern.compile(
            "(?<![0-9])([0-9]{1,2})(?::|h(?=[0-9]))([0-9]{2})(?![0-9])(?:\\s*([ap])\\.?\\s*m\\b\\.?)?",
            Pattern.CASE_INSENSITIVE);

    private DashText() {
    }

    public static String asciiClean(String text) {
        return WHITESPACE.matcher(toAscii(text)).replaceAll(" ").trim();
    }

    public static String asciiOnly(String text) {
        return toAscii(text);
    }

    private static String toAscii(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(decomposed.length());
        int i = 0;
        while (i < decomposed.length()) {
            int cp = decomposed.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                    || type == Character.COMBINING_SPACING_MARK) {
                continue;
            }
            if (cp >= 0x20 && cp <= 0x7E) {
                sb.append((char) cp);
                continue;
            }
            String mapped = transliterate(cp);
            sb.append(mapped != null ? mapped : " ");
        }
        return sb.toString();
    }

    public static String normalizeEta(String text) {
        String clean = asciiClean(text);
        if (clean.isEmpty()) {
            return null;
        }
        Matcher m = CLOCK.matcher(clean);
        if (!m.find()) {
            return null;
        }
        int hour = Integer.parseInt(m.group(1));
        int minute = Integer.parseInt(m.group(2));
        String meridiem = m.group(3);
        if (minute > 59) {
            return null;
        }
        if (meridiem != null) {
            if (hour < 1 || hour > 12) {
                return null;
            }
            boolean pm = meridiem.equalsIgnoreCase("p");
            hour = (hour % 12) + (pm ? 12 : 0);
        } else if (hour > 23) {
            return null;
        }
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
    }

    private static String transliterate(int cp) {
        switch (cp) {
            case 0x00DF: return "ss";
            case 0x1E9E: return "SS";
            case 0x00E6: return "ae";
            case 0x00C6: return "AE";
            case 0x0153: return "oe";
            case 0x0152: return "OE";
            case 0x00F8: return "o";
            case 0x00D8: return "O";
            case 0x0142: return "l";
            case 0x0141: return "L";
            case 0x0111: return "d";
            case 0x0110: return "D";
            case 0x00F0: return "d";
            case 0x00D0: return "D";
            case 0x00FE: return "th";
            case 0x00DE: return "Th";
            case 0x0131: return "i";
            case 0x2018: case 0x2019: case 0x201A: case 0x2032: return "'";
            case 0x201C: case 0x201D: case 0x201E: case 0x2033: return "\"";
            case 0x2010: case 0x2011: case 0x2012: case 0x2013: case 0x2014: case 0x2212: return "-";
            case 0x2026: return "...";
            default: return null;
        }
    }
}
