package app.ktmnav.backend;

/**
 * Log-safe views of personal data: Bluetooth addresses keep only the last two octets and
 * navigation text is reduced to lengths and the turn icon.
 */
public final class LogRedact {

    private LogRedact() {
    }

    public static String mac(String address) {
        if (address == null) {
            return "none";
        }
        String a = address.trim();
        String[] parts = a.split(":");
        if (parts.length != 6) {
            return "<" + a.length() + " chars>";
        }
        return "XX:XX:XX:XX:" + parts[4] + ":" + parts[5];
    }

    public static String text(String value) {
        return value == null ? "none" : value.length() + " chars";
    }

    public static String nav(NavUpdate update) {
        if (update == null) {
            return "none";
        }
        return "turn=" + update.getTurnIcon()
                + ", distance=" + text(update.getDistanceText())
                + ", road=" + text(update.getRoadText())
                + ", eta=" + text(update.getEtaText())
                + ", remaining=" + text(update.getRemainingDistanceText());
    }
}
