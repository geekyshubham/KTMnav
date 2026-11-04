package app.ktmnav.backend;

import java.util.HashMap;
import java.util.Map;

/**
 * Arrow icon hash mapping and reference name resolution for TomTom navigation.
 * Based on TurnIt TomTomArrows evidence.
 */
public final class TomTomArrows {

    private static final Map<Integer, TurnIcon> HASH_TO_ICON = new HashMap<>();
    private static final String REFERENCE_PREFIX = "ic_decision_point_arrow_";

    static {
        HASH_TO_ICON.put(2097022476, fromReferenceName("bear_left"));
        HASH_TO_ICON.put(385054297, fromReferenceName("bear_right"));
        HASH_TO_ICON.put(1506236594, fromReferenceName("left"));
        HASH_TO_ICON.put(-1904252653, fromReferenceName("right"));
        HASH_TO_ICON.put(-2003222442, fromReferenceName("straight"));
        HASH_TO_ICON.put(-49302981, fromReferenceName("roundaboutright"));
        HASH_TO_ICON.put(-1323461244, fromReferenceName("roundaboutleft"));
        HASH_TO_ICON.put(-1121596958, fromReferenceName("roundaboutaround"));
        HASH_TO_ICON.put(1402751437, fromReferenceName("roundaboutright3"));
        HASH_TO_ICON.put(227907998, fromReferenceName("roundaboutcross"));
        HASH_TO_ICON.put(1933815519, TurnIcon.END);
        HASH_TO_ICON.put(1484777465, fromReferenceName("uturn_left"));
        HASH_TO_ICON.put(-1667933858, TurnIcon.END);
        HASH_TO_ICON.put(-14846204, fromReferenceName("straight"));
        HASH_TO_ICON.put(970810216, fromReferenceName("sharp_left"));
        HASH_TO_ICON.put(960866504, fromReferenceName("sharp_right"));
        HASH_TO_ICON.put(-826679603, fromReferenceName("roundaboutleft3"));
        HASH_TO_ICON.put(1346204041, TurnIcon.END);
    }

    private TomTomArrows() {
    }

    public static TurnIcon lookup(int hash) {
        return HASH_TO_ICON.get(hash);
    }

    public static TurnIcon fromReferenceName(String name) {
        if (name == null) {
            return null;
        }
        String lower = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) {
            lower = lower.substring(0, lower.length() - 4);
        }
        if (lower.startsWith(REFERENCE_PREFIX)) {
            lower = lower.substring(REFERENCE_PREFIX.length());
        }
        boolean uk = lower.endsWith("_uk");
        if (uk) {
            lower = lower.substring(0, lower.length() - 3);
        }
        if (lower.startsWith("roundabout")) {
            return roundabout(lower.substring("roundabout".length()), uk);
        }
        if (uk) {
            return null;
        }
        switch (lower) {
            case "bear_left":
                return TurnIcon.LIGHT_LEFT;
            case "bear_right":
                return TurnIcon.LIGHT_RIGHT;
            case "uturn_left":
                return TurnIcon.UTURN_LEFT;
            case "sharp_left":
                return TurnIcon.HEAVY_LEFT;
            case "left":
                return TurnIcon.QUITE_LEFT;
            case "right":
                return TurnIcon.QUITE_RIGHT;
            case "uturn_right":
                return TurnIcon.UTURN_RIGHT;
            case "sharp_right":
                return TurnIcon.HEAVY_RIGHT;
            case "straight":
                return TurnIcon.GO_STRAIGHT;
            default:
                return null;
        }
    }

    private static TurnIcon roundabout(String suffix, boolean uk) {
        int sect;
        switch (suffix) {
            case "around":
                sect = 16;
                break;
            case "right1":
                sect = 2;
                break;
            case "right3":
                sect = 6;
                break;
            case "left":
                sect = 12;
                break;
            case "cross":
                sect = 8;
                break;
            case "left1":
                sect = 14;
                break;
            case "left3":
                sect = 10;
                break;
            case "right":
                sect = 4;
                break;
            default:
                return null;
        }
        try {
            return TurnIcon.valueOf("RAB_SECT_" + sect + (uk ? "_LH" : "_RH"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
