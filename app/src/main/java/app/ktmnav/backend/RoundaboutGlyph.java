package app.ktmnav.backend;

/**
 * Maps roundabout exits to right-hand / left-hand dash glyph sectors.
 * Based on TurnIt RoundaboutGlyph evidence.
 */
public final class RoundaboutGlyph {

    private RoundaboutGlyph() {
    }

    public static TurnIcon rightHandForExit(int exit) {
        if (exit < 1) {
            return TurnIcon.RAB_SECT_8_RH;
        }
        int sector = (exit > 3 ? 14 : exit * 4);
        try {
            return TurnIcon.valueOf("RAB_SECT_" + sector + "_RH");
        } catch (IllegalArgumentException e) {
            return TurnIcon.RAB_SECT_8_RH;
        }
    }

    public static TurnIcon toRightHand(TurnIcon icon) {
        if (icon == null) {
            return null;
        }
        String name = icon.name();
        if (name.startsWith("RAB_SECT_") && name.endsWith("_LH")) {
            try {
                return TurnIcon.valueOf(name.substring(0, name.length() - "_LH".length()) + "_RH");
            } catch (IllegalArgumentException ignored) {
            }
        }
        return icon;
    }
}
