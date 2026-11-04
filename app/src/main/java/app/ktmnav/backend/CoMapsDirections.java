package app.ktmnav.backend;

/**
 * Translates CoMaps navigation provider directions to KTM dashboard glyphs.
 */
public final class CoMapsDirections {
    private CoMapsDirections() {}

    public static TurnIcon icon(String carDirection, int exitNum) {
        if (carDirection == null) {
            return null;
        }
        switch (carDirection) {
            case "TURN_LEFT":
                return TurnIcon.QUITE_LEFT;
            case "NO_TURN":
            case "GO_STRAIGHT":
                return TurnIcon.GO_STRAIGHT;
            case "U_TURN_LEFT":
                return TurnIcon.UTURN_LEFT;
            case "ENTER_ROUND_ABOUT":
                return RoundaboutGlyph.rightHandForExit(exitNum);
            case "TURN_SHARP_RIGHT":
                return TurnIcon.HEAVY_RIGHT;
            case "START_AT_THE_END_OF_STREET":
                return TurnIcon.HEAD_TO;
            case "EXIT_HIGHWAY_TO_LEFT":
                return TurnIcon.LEAVE_HIGHWAY_LEFT_LANE;
            case "TURN_SLIGHT_LEFT":
                return TurnIcon.LIGHT_LEFT;
            case "TURN_SLIGHT_RIGHT":
                return TurnIcon.LIGHT_RIGHT;
            case "LEAVE_ROUND_ABOUT":
            case "STAY_ON_ROUND_ABOUT":
                return null;
            case "U_TURN_RIGHT":
                return TurnIcon.UTURN_RIGHT;
            case "TURN_SHARP_LEFT":
                return TurnIcon.HEAVY_LEFT;
            case "EXIT_HIGHWAY_TO_RIGHT":
                return TurnIcon.LEAVE_HIGHWAY_RIGHT_LANE;
            case "TURN_RIGHT":
                return TurnIcon.QUITE_RIGHT;
            case "REACHED_YOUR_DESTINATION":
                return TurnIcon.END;
            default:
                return TurnIcon.UNKNOWN;
        }
    }

    public static int roundaboutExit(String carDirection, int exitNum) {
        if (carDirection != null && exitNum >= 1) {
            switch (carDirection) {
                case "ENTER_ROUND_ABOUT":
                case "LEAVE_ROUND_ABOUT":
                case "STAY_ON_ROUND_ABOUT":
                    return exitNum;
                default:
                    return 0;
            }
        }
        return 0;
    }

    public static boolean isTerminalState(String sessionState) {
        return "RouteFinished".equals(sessionState) || "NoValidRoute".equals(sessionState);
    }
}
