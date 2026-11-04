package app.ktmnav.backend;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Maps TurnIcon enums to Gen2 dash icon names.
 * Based on TurnIt Gen2Icons evidence.
 */
public final class Gen2Icons {

    private static final Set<String> GEN2_NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "END", "GO_STRAIGHT", "FERRY", "KEEP_LEFT", "KEEP_RIGHT",
            "LIGHT_LEFT", "LIGHT_RIGHT", "QUITE_LEFT", "QUITE_RIGHT",
            "HEAVY_LEFT", "HEAVY_RIGHT", "UTURN_LEFT", "UTURN_RIGHT",
            "LEAVE_HIGHWAY_LEFT_LANE", "LEAVE_HIGHWAY_RIGHT_LANE",
            "RAB_SECT_4_LH", "RAB_SECT_4_RH",
            "RAB_SECT_6_LH", "RAB_SECT_6_RH",
            "RAB_SECT_8_LH", "RAB_SECT_8_RH",
            "RAB_SECT_10_LH", "RAB_SECT_10_RH",
            "RAB_SECT_12_LH", "RAB_SECT_12_RH",
            "RAB_SECT_14_LH", "RAB_SECT_14_RH",
            "RAB_SECT_16_LH", "RAB_SECT_16_RH"
    )));

    private Gen2Icons() {
    }

    public static String gen2Name(TurnIcon icon) {
        if (icon == null) {
            return null;
        }
        String name = icon.name();
        if (name.startsWith("RAB_SECT_")) {
            return roundaboutName(name);
        }
        switch (icon) {
            case KEEP_MIDDLE:
            case START:
            case HEAD_TO:
                return "GO_STRAIGHT";
            case ENTER_HIGHWAY_RIGHT_LANE:
            case HIGHWAY_KEEP_RIGHT:
                return "KEEP_RIGHT";
            case ENTER_HIGHWAY_LEFT_LANE:
            case HIGHWAY_KEEP_LEFT:
                return "KEEP_LEFT";
            case UNKNOWN:
            case UNDEFINED:
            case PASS_STATION:
            case CHANGE_LINE:
                return null;
            default:
                if (GEN2_NAMES.contains(name)) {
                    return name;
                }
                return null;
        }
    }

    private static String roundaboutName(String name) {
        boolean isLh = name.endsWith("_LH");
        try {
            int sect = Integer.parseInt(name.substring("RAB_SECT_".length(), name.length() - (isLh ? 3 : 3)));
            if (sect % 2 == 1) {
                sect++;
            }
            sect = Math.min(16, Math.max(4, sect));
            return "RAB_SECT_" + sect + (isLh ? "_LH" : "_RH");
        } catch (NumberFormatException unused) {
            return null;
        }
    }
}
